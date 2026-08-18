/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.benchmarks.proxy;

import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.HeadersUtil;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;
import io.reshapr.proxy.security.TokenCallCredentials;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.ClientCalls;
import io.reshapr.proxy.util.GrpcUtil;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A channel-pooling variant of {@code io.reshapr.proxy.proxy.GrpcProxyService}, kept in the benchmark
 * module so the production service stays untouched while its performance is measured against this
 * candidate optimization (see {@link GrpcChannelReuseBenchmark}).
 *
 * <p>Functionally identical to {@code GrpcProxyService} — same request/response conversion, same
 * per-call authorization model — but instead of opening and shutting down one {@code ManagedChannel}
 * per call, it caches and reuses a single {@code ManagedChannel} per backend endpoint. A gRPC channel
 * multiplexes many concurrent RPCs over a single (or a small pool of) HTTP/2 connection(s), so reuse
 * removes the connection setup/teardown cost that otherwise churns ephemeral ports and collapses
 * under sustained load.</p>
 *
 * <h2>Channel lifecycle &amp; connection loss</h2>
 * <p>A {@code ManagedChannel} is a <em>logical</em> channel, not a physical connection: gRPC opens
 * transports lazily, keeps them warm, and — as long as the channel is not {@code shutdown()} —
 * transparently reconnects (with exponential backoff) after a transient connection loss or after the
 * channel has gone {@code IDLE}. Reusing a cached channel across calls is therefore safe: a dropped
 * connection is re-established on the next RPC. The only unusable state is an explicitly shut-down
 * channel, which here can only ever be an <em>evicted</em> one (removed from the cache); a defensive
 * {@link ManagedChannel#isShutdown()} guard in {@link #getOrCreateChannel} rebuilds it should that
 * ever be observed (e.g. the narrow evict-then-use race).</p>
 *
 * <h2>Bounded pool &amp; eviction</h2>
 * <p>The pool is a Caffeine cache (the same building block as
 * {@code io.reshapr.proxy.mcp.WorkCache}) bounded by {@code maximumSize} (LRU) and
 * {@code expireAfterAccess} (idle TTL). This prevents unbounded growth if endpoints churn. A removal
 * listener performs a <em>graceful</em> {@link ManagedChannel#shutdown()} on every evicted entry so
 * in-flight RPCs drain while no resource leaks.</p>
 *
 * <h2>Stale-entry invalidation on redeploy</h2>
 * <p>The cache key is the transport identity: {@code protocol://host:port} plus the TLS trust
 * material reference. When an exposition is redeployed with a changed endpoint or certificate, the
 * key changes, so the next call naturally builds a fresh channel and the previous entry — now
 * unreferenced — is reclaimed by the idle TTL / size bound (its listener shutting it down). For
 * immediate reclamation, {@link #invalidateEndpoint(String)} can be wired to the registry/EDS sync
 * that already tracks exposition updates.</p>
 *
 * <h2>Per-user authorization preserved</h2>
 * <p>Authorization credentials are never attached to the channel: they are supplied <em>per call</em>
 * through {@link CallOptions#withCallCredentials(io.grpc.CallCredentials)} (see
 * {@link #manageSecurityHeaders}) and per-call request metadata via {@link #METADATA_CUSTOM_CALL_OPTION}.
 * The cached channel only carries the transport, which is a property of the exposition configuration —
 * not of the calling user — so two requests from two different users to the same backend share the
 * transport while still presenting their own credentials.</p>
 *
 * @author laurent
 */
public class OptimizedGrpcProxyService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /* Call Option used to pass gRPC Metadata from client invocation to header client interceptor */
   public static final String CUSTOM_CALL_OPTION_NAME = "request-metadata";
   public static final CallOptions.Key<Metadata> METADATA_CUSTOM_CALL_OPTION = CallOptions.Key
         .createWithDefault(CUSTOM_CALL_OPTION_NAME, null);

   private static final List<String> RESTRICTED_HEADERS = List.of("host", "connection", "accept",
         "content-type", "content-length", "user-agent");

   /** Default upper bound on the number of pooled channels (distinct endpoints). */
   private static final long DEFAULT_MAX_CHANNELS = 512L;
   /** Default idle time-to-live after which an unused endpoint's channel is evicted. */
   private static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(15);

   private final SecretReferenceResolver secretResolver;
   private final UserSecretStore userSecretStore;

   /** Per-endpoint {@code ManagedChannel} pool: bounded (LRU + idle TTL), evictions shut down gracefully. */
   private final Cache<String, ManagedChannel> channelCache;

   long defaultBackendTimeout = 10_000L;

   /**
    * Build an OptimizedGrpcProxyService with required dependencies and default pool bounds.
    * @param secretResolver The resolver used to resolve secret references locally on the gateway.
    * @param userSecretStore The per-user elicited secret store (stateless mode).
    */
   public OptimizedGrpcProxyService(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore) {
      this(secretResolver, userSecretStore, DEFAULT_MAX_CHANNELS, DEFAULT_IDLE_TTL);
   }

   /**
    * Build an OptimizedGrpcProxyService with explicit pool bounds.
    * @param secretResolver The resolver used to resolve secret references locally on the gateway.
    * @param userSecretStore The per-user elicited secret store (stateless mode).
    * @param maxChannels Maximum number of pooled channels (LRU eviction beyond this).
    * @param idleTtl Idle time-to-live after which an unused endpoint's channel is evicted.
    */
   public OptimizedGrpcProxyService(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore,
         long maxChannels, Duration idleTtl) {
      this.secretResolver = secretResolver;
      this.userSecretStore = userSecretStore;
      this.channelCache = Caffeine.newBuilder()
            .maximumSize(maxChannels)
            .expireAfterAccess(idleTtl)
            .<String, ManagedChannel>removalListener((key, channel, cause) -> {
               if (channel != null) {
                  logger.debugf("Evicting pooled gRPC channel for '%s' (cause %s)", key, cause);
                  gracefulShutdown(channel);
               }
            })
            .build();
   }

   /**
    * @param configuration The configuration entry containing backend security details.
    * @param md The MethodDescriptor for the gRPC method to call.
    * @param headers The headers to include in the request.
    * @param body The body of the request, if applicable (e.g., for POST requests).
    * @return A BackendResponse containing the status code, body, and headers from the backend response.
    */
   public BackendResponse callBackend(ConfigurationEntry configuration, Descriptors.MethodDescriptor md,
         Map<String, List<String>> headers, String body) throws IOException {

      URL endpoint = URI.create(configuration.backendEndpoint()).toURL();

      if (logger.isDebugEnabled()) {
         logger.debugf("Proxy request url: '%s'", endpoint);
         logger.debugf("Proxy request method: '%s'", md.getFullName());
         logger.debugf("Proxy request headers: '%s'", headers);
         logger.tracef("Proxy request body: '%s'", body);
      }

      // Reuse a cached ManagedChannel for this endpoint instead of creating one per call. The channel
      // carries only the transport; per-user credentials and headers are still applied per call below.
      ManagedChannel originChannel = getOrCreateChannel(endpoint, configuration);

      // Add a custom header interceptor which adds the request-specific headers.
      ClientInterceptor headerInterceptor = new HeaderInterceptor();
      Channel channel = ClientInterceptors.intercept(originChannel, headerInterceptor);

      // Use a builder for out type with a Json parser to merge content and build outMsg.
      DynamicMessage.Builder reqBuilder = DynamicMessage.newBuilder(md.getInputType());
      JsonFormat.Parser parser = JsonFormat.parser();

      // Now produce the request message byte array.
      try {
         parser.merge(body, reqBuilder);
      } catch (InvalidProtocolBufferException e) {
         String message = e.getMessage() != null ? e.getMessage() : "Invalid JSON to Protobuf conversion";
         logger.errorf("Exception while parsing JSON to Protobuf: '%s'", message);
         return new BackendResponse(400, message.getBytes(StandardCharsets.UTF_8), Map.of());
      }
      byte[] requestBytes = reqBuilder.build().toByteArray();

      // Set timeout with priority to configuration value, then default if not set.
      long timeoutMs = configuration.backendTimeout() != null ? configuration.backendTimeout() : defaultBackendTimeout;
      CallOptions callOptions = CallOptions.DEFAULT.withDeadline(Deadline.after(timeoutMs, TimeUnit.MILLISECONDS));

      if (configuration.backendSecret() != null) {
         // Set the authentication token as call credentials if provided in the configuration.
         callOptions = manageSecurityHeaders(configuration.backendSecret(), callOptions, headers);
      }

      // Now we can call the gRPC service using the channel and method descriptor.
      byte[] responseBytes = null;
      try {
         String methodName = md.getService().getFullName() + "/" + md.getName();
         responseBytes = doCallBackend(channel, GrpcUtil.buildGenericUnaryMethodDescriptor(methodName), callOptions,
               headers, requestBytes, configuration.backendEndpoint());

         if (logger.isDebugEnabled()) {
            logger.debugf("Proxy returned: '%s'", Status.Code.OK.name());
            logger.tracef("Proxy response body: '%s'", new String(responseBytes, StandardCharsets.UTF_8));
         }

         String contentResponse;
         try {
            // Validate incoming message parsing a DynamicMessage.
            DynamicMessage respMsg = DynamicMessage.parseFrom(md.getOutputType(), responseBytes);

            // Now update response content with readable content.
            JsonFormat.Printer printer = JsonFormat.printer();
            contentResponse = printer.print(respMsg);
         } catch (InvalidProtocolBufferException ipbe) {
            String message = ipbe.getMessage() != null ? ipbe.getMessage() : "Invalid Protobuf to JSON conversion";
            logger.errorf("Exception while converting Protobuf to JSON: '%s'", message);
            return new BackendResponse(400, message.getBytes(StandardCharsets.UTF_8), Map.of());
         }

         return new BackendResponse(Status.Code.OK.value(),
               contentResponse.getBytes(StandardCharsets.UTF_8), Map.of());
      } catch (StatusRuntimeException sre) {
         int httpStatus = mapGrpcStatusToHttp(sre.getStatus().getCode());
         String message = sre.getMessage() != null ? sre.getMessage() : sre.getStatus().getCode().name();
         logger.errorf("gRPC proxy error calling backend '%s' [%s -> HTTP %d]: %s",
               configuration.backendEndpoint(), sre.getStatus().getCode(), httpStatus, message);

         // If authorization failed, it can be because of a bad elicitation secret value. We need to evict it.
         if (httpStatus == 401 && configuration.backendSecret() != null && configuration.backendSecret().useElicitation()) {
            logger.warnf("Proxy authorization failed with 401, evicting elicitation secret '%s'", configuration.backendSecret().name());
            SecretEntry secret = configuration.backendSecret();
            SessionInfo sessionInfo = MethodHandlingContext.getSessionInfo();
            if (sessionInfo != null) {
               sessionInfo.removeSecretValue(secret);
            } else {
               String userKey = MethodHandlingContext.getUserKey();
               if (userKey != null) {
                  userSecretStore.removeSecret(userKey, secretRef(secret));
               }
            }
         }

         return new BackendResponse(httpStatus, message.getBytes(StandardCharsets.UTF_8), Map.of());
      }
      // Note: the channel is intentionally NOT shut down here — it is pooled and reused across calls.
   }

   /**
    * Return the pooled {@code ManagedChannel} for this endpoint, building it lazily on first use.
    *
    * <p>The cache key is derived from the transport identity only (protocol, host, port and, for TLS,
    * the configured trust material reference). It deliberately excludes anything user-specific, so a
    * single channel is safely shared by all users while credentials remain per call.</p>
    */
   private ManagedChannel getOrCreateChannel(URL endpoint, ConfigurationEntry configuration) {
      String key = channelKey(endpoint, configuration);
      ManagedChannel channel = channelCache.get(key, k -> buildChannel(endpoint, configuration));
      // Defensive guard against the narrow evict-then-use race: an evicted channel is shut down by the
      // removal listener; if we ever hand out a shut-down channel, drop it and rebuild.
      if (channel != null && channel.isShutdown()) {
         channelCache.asMap().remove(key, channel);
         channel = channelCache.get(key, k -> buildChannel(endpoint, configuration));
      }
      return channel;
   }

   /** Build the cache key for a channel from its transport identity. */
   private static String channelKey(URL endpoint, ConfigurationEntry configuration) {
      String base = endpoint.getProtocol() + "://" + endpoint.getHost() + ":" + endpoint.getPort();
      boolean tls = endpoint.getProtocol().equals("https") || endpoint.getPort() == 443;
      if (tls && configuration.backendSecret() != null && configuration.backendSecret().certPem() != null) {
         // Different trust material must never share a channel.
         return base + "|cert=" + configuration.backendSecret().certPem();
      }
      return base;
   }

   /** Build a new {@code ManagedChannel} for the given endpoint (plaintext or TLS). */
   private ManagedChannel buildChannel(URL endpoint, ConfigurationEntry configuration) {
      if (endpoint.getProtocol().equals("https") || endpoint.getPort() == 443) {
         TlsChannelCredentials.Builder tlsBuilder = TlsChannelCredentials.newBuilder();
         if (configuration.backendSecret() != null && configuration.backendSecret().certPem() != null) {
            // Install a trust manager with custom CA certificate.
            String certPem = secretResolver.resolve(configuration.backendSecret().certPem());
            try {
               tlsBuilder.trustManager(new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
               throw new IllegalStateException("Unable to install custom trust manager for gRPC backend", e);
            }
         }
         // Build a Channel using the TLS Builder.
         return Grpc.newChannelBuilderForAddress(endpoint.getHost(), endpoint.getPort(), tlsBuilder.build())
               .build();
      }
      // Build a simple Channel using no creds (now default to plain text so usePlainText() is no longer necessary).
      return Grpc
            .newChannelBuilderForAddress(endpoint.getHost(), endpoint.getPort(), InsecureChannelCredentials.create())
            .build();
   }

   /**
    * Invalidate (and gracefully shut down) every pooled channel whose transport matches the given
    * backend endpoint. Intended to be called from the registry/EDS sync when an exposition is
    * redeployed so obsolete channels are reclaimed immediately rather than at idle-TTL expiry.
    */
   public void invalidateEndpoint(String backendEndpoint) {
      try {
         URL endpoint = URI.create(backendEndpoint).toURL();
         String base = endpoint.getProtocol() + "://" + endpoint.getHost() + ":" + endpoint.getPort();
         List<String> keys = channelCache.asMap().keySet().stream()
               .filter(k -> k.equals(base) || k.startsWith(base + "|"))
               .toList();
         channelCache.invalidateAll(keys);
      } catch (Exception e) {
         logger.warnf("Unable to invalidate pooled channel for endpoint '%s': %s", backendEndpoint, e.getMessage());
      }
   }

   /** @return the current number of pooled channels (for tests/benchmarks). */
   public long pooledChannelCount() {
      channelCache.cleanUp();
      return channelCache.estimatedSize();
   }

   /** Shut down all pooled channels. Usable from tests/benchmarks or a container destroy callback. */
   public void shutdown() {
      channelCache.asMap().values().forEach(OptimizedGrpcProxyService::gracefulShutdown);
      channelCache.invalidateAll();
      channelCache.cleanUp();
   }

   private static void gracefulShutdown(ManagedChannel channel) {
      try {
         channel.shutdown();
         if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow();
         }
      } catch (InterruptedException e) {
         channel.shutdownNow();
         Thread.currentThread().interrupt();
      }
   }

   protected byte[] doCallBackend(Channel channel, MethodDescriptor<byte[], byte[]> unaryMethodDescriptor,
                                  CallOptions callOptions, Map<String, List<String>> headers, byte[] requestBytes,
                                  String backendEndpoint) throws StatusRuntimeException {
      // Set the other headers as Metadata in the CallOptions.
      callOptions = callOptions.withOption(METADATA_CUSTOM_CALL_OPTION, convertHeadersToMetadata(headers));
      return ClientCalls.blockingUnaryCall(channel, unaryMethodDescriptor, callOptions, requestBytes);
   }

   private static int mapGrpcStatusToHttp(Status.Code code) {
      return switch (code) {
         case DEADLINE_EXCEEDED -> 504;
         case UNAVAILABLE -> 503;
         case UNAUTHENTICATED -> 401;
         case PERMISSION_DENIED -> 403;
         case NOT_FOUND -> 404;
         case INVALID_ARGUMENT -> 400;
         case UNIMPLEMENTED -> 501;
         case RESOURCE_EXHAUSTED -> 429;
         default -> 502;
      };
   }

   private CallOptions manageSecurityHeaders(SecretEntry secret, CallOptions callOptions, Map<String, List<String>> headers) {
      if (!secret.useElicitation()) {
         // Add security headers based on the secret.
         // Set the authentication token as call credentials if provided in the configuration.
         if (secret.token() != null) {
            logger.debug("Secret contains token and maybe token header, adding them as call credentials");
            String token = secretResolver.resolve(secret.token());
            callOptions = callOptions.withCallCredentials(new TokenCallCredentials(token, secret.tokenHeader()));

            // Remove the token header from the request headers if it exists,
            String headerToRemove = secret.tokenHeader() != null
                  ? secret.tokenHeader()
                  : TokenCallCredentials.AUTHORIZATION_METADATA_KEY.name();
            headers.remove(headerToRemove);
         } else if (containsIgnoreCase(headers, TokenCallCredentials.AUTHORIZATION_METADATA_KEY.originalName())) {
            // If no backend secret token but incoming request authorization, treat it as call credentials.
            logger.debug("Got an Authorization header from incoming request, adding it as call credentials");
            callOptions = callOptions.withCallCredentials(
                  new TokenCallCredentials(getIgnoreCase(headers, TokenCallCredentials.AUTHORIZATION_METADATA_KEY.originalName())));

            // Remove the Authorization header from the request headers.
            removeIgnoreCase(headers, TokenCallCredentials.AUTHORIZATION_METADATA_KEY.originalName());
         }

      } else {
         SessionInfo sessionInfo = MethodHandlingContext.getSessionInfo();

         // We need to clean any existing token header from the request headers to they don't conflict with elicication one.
         // Remove the token header from the request headers if it exists,
         String headerToRemove = secret.tokenHeader() != null ? secret.tokenHeader() : TokenCallCredentials.AUTHORIZATION_METADATA_KEY.name();
         headers.remove(headerToRemove);

         // Remove the Authorization header from the request headers.
         removeIgnoreCase(headers, TokenCallCredentials.AUTHORIZATION_METADATA_KEY.originalName());

         if (sessionInfo != null) {
            // Elicitation is used, retrieve secret value from session info.
            String secretValue = sessionInfo.getSecretValue(secret);
            if (secretValue != null) {
               logger.debug("Elicited secret contains token header, adding them as call credentials");
               callOptions = callOptions.withCallCredentials(new TokenCallCredentials(secretValue, secret.tokenHeader()));
            } else {
               logger.warn("Elicited secret value not found in session info");
            }
         } else {
            // Stateless mode: resolve the secret from the replicated user-secret store.
            String userKey = MethodHandlingContext.getUserKey();
            String secretValue = userKey != null ? userSecretStore.getSecret(userKey, secretRef(secret)) : null;
            if (secretValue != null) {
               logger.debug("Elicited secret resolved for user, adding it as call credentials");
               callOptions = callOptions.withCallCredentials(new TokenCallCredentials(secretValue, secret.tokenHeader()));
            } else {
               logger.warn("Elicited secret value not found for current request (stateless mode)");
            }
         }
      }
      return callOptions;
   }

   /** Build the stable per-user secret reference ({@code organizationId + '/' + secret.name()}). */
   private static String secretRef(SecretEntry secret) {
      return MethodHandlingContext.getOrganizationId() + '/' + secret.name();
   }

   /** */
   class HeaderInterceptor implements ClientInterceptor {

      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                 CallOptions callOptions, Channel next) {
         return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
               // Extract custom headers from CallOptions
               Metadata customHeaders = callOptions.getOption(METADATA_CUSTOM_CALL_OPTION);
               if (customHeaders != null) {
                  logger.debugf("Adding headers to client request: %s", customHeaders.keys());
                  headers.merge(customHeaders);
               }
               logger.debugf("Headers: %s", headers);
               super.start(responseListener, headers);
            }
         };

      }
   }

   private static boolean containsIgnoreCase(Map<String, List<String>> headers, String key) {
      return headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(key));
   }

   private static String getIgnoreCase(Map<String, List<String>> headers, String key) {
      return headers.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(key))
            .map(entry -> entry.getValue().getFirst())
            .findFirst()
            .orElse(null);
   }

   private static void removeIgnoreCase(Map<String, List<String>> headers, String key) {
      List<String> actualKeys = headers.keySet().stream()
            .filter(k -> k.equalsIgnoreCase(key))
            .toList();
      if (!actualKeys.isEmpty()) {
         actualKeys.forEach(headers::remove);
      }
   }

   private static Metadata convertHeadersToMetadata(Map<String, List<String>> headers) {
      Metadata metadata = new Metadata();

      // Manage the Forwarded and X-Forwarded-For headers.
      HeadersUtil.addForwardingHeaders(headers);

      // Also inject OpenTelemetry tracing headers.
      HeadersUtil.injectTracingHeaders(headers);

      // Some specific headers must not be included in the gRPC metadata.
      headers.entrySet().stream()
            .filter(entry -> !RESTRICTED_HEADERS.contains(entry.getKey().toLowerCase()))
            .forEach(entry -> {
               // If the header is "authorization", we need to use the correct key where case matters.
               String key = entry.getKey().equalsIgnoreCase("authorization") ? "Authorization" : entry.getKey();
               entry.getValue().forEach(value -> metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value));
            });

      return metadata;
   }
}
