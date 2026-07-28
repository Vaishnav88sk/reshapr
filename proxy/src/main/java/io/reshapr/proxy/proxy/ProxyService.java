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
package io.reshapr.proxy.proxy;

import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A service to proxy HTTP requests to external backends.
 * It handles the request, forwards it to the specified external URL, and returns the response.
 * @author laurent
 */
@ApplicationScoped
public class ProxyService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final HttpClient httpClient = HttpClient.newBuilder()
         .connectTimeout(Duration.ofSeconds(3))
         .version(HttpClient.Version.HTTP_1_1).build();

   private static final List<String> RESTRICTED_HEADERS = List.of("host", "connection", "x-reshapr-key");

   private final SecretReferenceResolver secretResolver;
   private final UserSecretStore userSecretStore;

   @ConfigProperty(name = "reshapr.gateway.backend.http.default-timeout")
   Long defaultBackendTimeout;

   /**
    * Build a ProxyService with required dependencies.
    * @param secretResolver The resolver used to resolve secret references locally on the gateway.
    * @param userSecretStore The per-user elicited secret store (stateless mode).
    */
   public ProxyService(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore) {
      this.secretResolver = secretResolver;
      this.userSecretStore = userSecretStore;
   }

   /**
    * @param configuration The configuration entry containing backend security details.
    * @param externalUrl The backend URL overriding the one from configuration.
    * @param method The HTTP method to use for the request (e.g., GET, POST).
    * @param headers The headers to include in the request.
    * @param body The body of the request, if applicable (e.g., for POST requests).
    * @return A BackendResponse containing the status code, body, and headers from the backend response.
    */
   public BackendResponse callBackend(ConfigurationEntry configuration, URI externalUrl, String method, Map<String, List<String>> headers, String body) {
      // Set timeout with priority to configuration value, then default if not set.
      long timeoutMs = configuration.backendTimeout() != null ? configuration.backendTimeout() : defaultBackendTimeout;

      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(externalUrl)
            .timeout(Duration.ofMillis(timeoutMs))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));

      // Some headers are restricted in HttpClient and must not be propagated.
      Map<String, List<String>> requestHeaders = new HashMap<>();
      headers.entrySet().stream()
            .filter(entry -> !RESTRICTED_HEADERS.contains(entry.getKey().toLowerCase()))
            .forEach(entry -> requestHeaders.put(entry.getKey(), entry.getValue()));

      // Manage the Forwarded and X-Forwarded-For headers.
      HeadersUtil.addForwardingHeaders(requestHeaders);

      // If the configuration has a backend secret, manage security headers.
      if (configuration.backendSecret() != null) {
         manageSecurityHeaders(configuration.backendSecret(), requestHeaders);
      }

      if (logger.isDebugEnabled()) {
         logger.debugf("Proxy request url: '%s'", externalUrl);
         logger.debugf("Proxy request headers: '%s'", requestHeaders);
         logger.tracef("Proxy request body: '%s'", body);
      }

      try {
         // Call the backend.
         HttpResponse<byte[]> response = doCallBackend(requestHeaders, requestBuilder, externalUrl.toString());

         if (logger.isDebugEnabled()) {
            logger.debugf("Proxy returned: '%s'", response.statusCode());
            logger.debugf("Proxy response headers: '%s'", response.headers());
            logger.tracef("Proxy response body: '%s'", new String(response.body(), StandardCharsets.UTF_8));
         }

         // If authorization failed, it can be because of a bad elicitation secret value. We need to evict it.
         if (response.statusCode() == 401 && configuration.backendSecret() != null && configuration.backendSecret().useElicitation()) {
            logger.warnf("Proxy authorization failed with 401, evicting elicitation secret '%s'", configuration.backendSecret().name());
            evictElicitedSecret(configuration.backendSecret());
         }

         // If authorization failed with empty body, explanations may be in the WWW-Authenticate header.
         if (response.statusCode() == 401 && response.body().length == 0 && response.headers().firstValue("www-authenticate").isPresent()) {
            return new BackendResponse(response.statusCode(),
                  response.headers().allValues("www-authenticate").toString().getBytes(StandardCharsets.UTF_8),
                  response.headers().map());
         }

         // Return the response as is.
         return new BackendResponse(response.statusCode(), response.body(), response.headers().map());
      } catch (HttpTimeoutException e) {
         logger.errorf("Proxy timed out after %dms calling: '%s'", timeoutMs, externalUrl);
         return new BackendResponse(504, ("Backend timed out after " + timeoutMs + "ms").getBytes(StandardCharsets.UTF_8), Map.of());
      } catch (ConnectException e) {
         logger.errorf("Proxy connection refused by backend '%s': %s", externalUrl, e.getMessage());
         return new BackendResponse(503, "Service Unavailable: backend refused the connection".getBytes(StandardCharsets.UTF_8), Map.of());
      } catch (IOException e) {
         logger.errorf("Proxy I/O error calling backend '%s': %s", externalUrl, e.getMessage());
         return new BackendResponse(502, "Bad Gateway: unexpected network error".getBytes(StandardCharsets.UTF_8), Map.of());
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         logger.errorf("Proxy call to backend '%s' was interrupted", externalUrl);
         return new BackendResponse(500, "Internal Server Error: request was interrupted".getBytes(StandardCharsets.UTF_8), Map.of());
      } catch (Exception e) {
         String message = e.getMessage() != null ? e.getMessage() : "Unknown error";
         logger.errorf("Proxy raised unexpected error calling backend '%s': %s", externalUrl, message);
         return new BackendResponse(500, ("Internal Server Error: " + message).getBytes(StandardCharsets.UTF_8), Map.of());
      }
   }

   @WithSpan(kind = SpanKind.CLIENT)
   protected HttpResponse<byte[]> doCallBackend(Map<String, List<String>> requestHeaders, HttpRequest.Builder requestBuilder,
                                                @SpanAttribute("backendEndpoint") String backendEndpoint) throws IOException, InterruptedException {

      // Inject OpenTelemetry tracing headers here to get correct parent (this current client span).
      HeadersUtil.injectTracingHeaders(requestHeaders);

      // Apply headers to request builder before calling backend.
      requestHeaders.forEach((key, values) -> values.forEach(value -> requestBuilder.header(key, value)));

      return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
   }

   private void manageSecurityHeaders(SecretEntry secret, Map<String, List<String>> headers) {
      if (!secret.useElicitation()) {
         // Add security headers based on the secret.
         if (secret.token() != null) {
            // If Token authentication required, set request property.
            String token = secretResolver.resolve(secret.token());
            if (secret.tokenHeader() != null && !secret.tokenHeader().isBlank()) {
               logger.debug("Secret contains token and token header, adding them as request header");
               headers.put(secret.tokenHeader(), List.of(token));
            } else {
               logger.debug("Secret contains token only, assuming Authorization Bearer");
               headers.put(HttpHeaders.AUTHORIZATION, List.of("Bearer " + token));
            }
         } else if (secret.username() != null && secret.password() != null) {
            // If Basic authentication required, set request property.
            logger.debug("Secret contains username/password, assuming Authorization Basic");
            String username = secretResolver.resolve(secret.username());
            String password = secretResolver.resolve(secret.password());
            String basicAuth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(basicAuth.getBytes(StandardCharsets.UTF_8));
            headers.put(HttpHeaders.AUTHORIZATION, List.of("Basic " + encodedAuth));
         }
      } else {
         // Elicitation is used: resolve the secret value according to the mode (legacy session vs stateless user).
         String secretValue = resolveElicitedSecretValue(secret);
         if (secretValue != null) {
            if (secret.tokenHeader() != null && !secret.tokenHeader().isBlank()) {
               logger.debug("Elicited secret contains token header, adding them as request header");
               headers.put(secret.tokenHeader(), List.of(secretValue));
            } else {
               logger.debug("Elicited secret does not contain token header, assuming Authorization Bearer");
               headers.put(HttpHeaders.AUTHORIZATION, List.of("Bearer " + secretValue));
            }
         } else {
            logger.warn("Elicited secret value not found for current request");
         }
      }
   }

   /**
    * Resolve an elicited secret value according to the current request mode:
    * <ul>
    *   <li><b>legacy</b> (an MCP session is bound) ⇒ read it from the session;</li>
    *   <li><b>stateless</b> (no session) ⇒ read it from the replicated user-secret store keyed by
    *       {@code (userKey, organizationId + '/' + secret.name())}.</li>
    * </ul>
    * @return the resolved secret value, or {@code null} if none is available.
    */
   private String resolveElicitedSecretValue(SecretEntry secret) {
      SessionInfo sessionInfo = MethodHandlingContext.getSessionInfo();
      if (sessionInfo != null) {
         return sessionInfo.getSecretValue(secret);
      }
      String userKey = MethodHandlingContext.getUserKey();
      if (userKey == null) {
         logger.warn("No session and no user identity available, cannot resolve elicited secret value");
         return null;
      }
      return userSecretStore.getSecret(userKey, secretRef(secret));
   }

   /** Evict the elicited secret value bound to the current request (used on a backend 401). */
   private void evictElicitedSecret(SecretEntry secret) {
      SessionInfo sessionInfo = MethodHandlingContext.getSessionInfo();
      if (sessionInfo != null) {
         sessionInfo.removeSecretValue(secret);
         return;
      }
      String userKey = MethodHandlingContext.getUserKey();
      if (userKey != null) {
         userSecretStore.removeSecret(userKey, secretRef(secret));
      }
   }

   /** Build the stable per-user secret reference ({@code organizationId + '/' + secret.name()}). */
   private static String secretRef(SecretEntry secret) {
      return MethodHandlingContext.getOrganizationId() + '/' + secret.name();
   }
}
