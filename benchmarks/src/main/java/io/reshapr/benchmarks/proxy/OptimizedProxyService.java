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

import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.proxy.HeadersUtil;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optimized {@link ProxyService} implementing the P1-A recommendation of
 * {@code docs/proxy-service-optimizations.md}: <b>sharded JDK {@code HttpClient}s</b>.
 *
 * <p>Instead of a single static client (whose lone {@code SelectorManager} platform thread
 * serializes all I/O readiness events and caps throughput under concurrency), a small fixed pool
 * of N clients (N = CPU/2 clamped to [2..8]) is used, selected round-robin per request. Each
 * client owns its selector thread and its per-host connection pool, multiplying the I/O
 * event-processing capacity by N. Every client is also given an explicit
 * <b>virtual-thread-per-task executor</b> so response delivery and async completions never hop
 * through the default cached pool of platform threads.</p>
 *
 * <p>Only {@link #doCallBackend} is overridden — request preparation, header filtering, security
 * and error handling remain strictly inherited from {@link ProxyService} to keep a future port
 * of this optimization trivial.</p>
 *
 * @author laurent
 */
public class OptimizedProxyService extends ProxyService {

   /** Number of client shards: half the cores, clamped to [2..8]. */
   private static final int SHARD_COUNT = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 2, 8);

   /** The sharded clients, each with its own selector thread, connection pool and VT executor. */
   private static final HttpClient[] CLIENTS = buildClients();

   /** Round-robin cursor for shard selection. */
   private static final AtomicInteger CURSOR = new AtomicInteger();

   public OptimizedProxyService(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore) {
      super(secretResolver, userSecretStore);
   }

   private static HttpClient[] buildClients() {
      HttpClient[] clients = new HttpClient[SHARD_COUNT];
      for (int i = 0; i < SHARD_COUNT; i++) {
         clients[i] = HttpClient.newBuilder()
               .connectTimeout(Duration.ofSeconds(3))
               .version(HttpClient.Version.HTTP_1_1)
               .executor(Executors.newVirtualThreadPerTaskExecutor())
               .build();
      }
      return clients;
   }

   @Override
   @WithSpan(kind = SpanKind.CLIENT)
   protected HttpResponse<byte[]> doCallBackend(Map<String, List<String>> requestHeaders, HttpRequest.Builder requestBuilder,
                                                @SpanAttribute("backendEndpoint") String backendEndpoint) throws IOException, InterruptedException {

      // Inject OpenTelemetry tracing headers here to get correct parent (this current client span).
      HeadersUtil.injectTracingHeaders(requestHeaders);

      // Apply headers to request builder before calling backend.
      requestHeaders.forEach((key, values) -> values.forEach(value -> requestBuilder.header(key, value)));

      // Round-robin shard selection: spreads I/O event processing over SHARD_COUNT selector threads.
      HttpClient client = CLIENTS[Math.floorMod(CURSOR.getAndIncrement(), SHARD_COUNT)];
      return client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
   }
}

