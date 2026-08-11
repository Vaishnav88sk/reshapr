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
import io.reshapr.proxy.context.MethodHandlingInfo;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark for {@link ProxyService#callBackend}: pure proxying performance, end-to-end
 * over loopback, against an ultra-fast canned-response HTTP backend ({@link MinimalHttpBackend})
 * so the backend itself is never the bottleneck.
 *
 * <p>Axes:</p>
 * <ul>
 *   <li>{@code requestPayload}: SMALL / MEDIUM / LARGE incoming request body (~100 B / ~10 KiB / ~500 KiB)</li>
 *   <li>{@code headerCount}: SMALL / MEDIUM / LARGE incoming header set (3 / 12 / 40 headers)</li>
 *   <li>{@code secretMode}: NONE / TOKEN (Bearer via secret resolver) / BASIC (username+password, Base64)</li>
 *   <li>{@code responsePayload}: SMALL / MEDIUM / LARGE backend response body</li>
 *   <li>concurrency: dedicated benchmark methods at 1 (none), 4 (low), 16 (medium), 64 (high) threads</li>
 *   <li>{@code proxyImpl}: implementation under test (see {@link ProxyFactory})</li>
 * </ul>
 *
 * <p>The {@code ProxyService} is a highly shared component, so the concurrency axis matters: the
 * static {@code HttpClient} and its connection pool are exercised exactly as in production. Each
 * call runs inside a bound {@code MethodHandlingContext} scoped value, as done by the MCP layer.
 * Run with {@code -prof gc} to also capture per-op allocations.</p>
 *
 * @author laurent
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = { "--enable-preview", "-Xms1g", "-Xmx1g" })
public class ProxyServiceCallBackendBenchmark {

   /** Shared, per-trial state: backend server, proxy under test and pre-built request material. */
   @State(Scope.Benchmark)
   public static class BenchState {

      @Param({ "SMALL", "MEDIUM", "LARGE" })
      public String requestPayload;

      @Param({ "SMALL", "MEDIUM", "LARGE" })
      public String headerCount;

      @Param({ "NONE", "TOKEN", "BASIC" })
      public String secretMode;

      @Param({ "SMALL", "MEDIUM", "LARGE" })
      public String responsePayload;

      @Param({ "current", "optimized" })
      public String proxyImpl;

      MinimalHttpBackend backend;
      ProxyService proxy;
      ConfigurationEntry configuration;
      URI backendUri;
      String requestBody;
      Map<String, List<String>> headersTemplate;
      MethodHandlingInfo handlingInfo;

      @Setup(Level.Trial)
      public void setupTrial() throws IOException {
         // Start the minimal backend serving the canned response of the requested size.
         byte[] responseBody = ProxyBenchPayloads.PayloadSize.valueOf(responsePayload)
               .buildJson().getBytes(StandardCharsets.UTF_8);
         backend = new MinimalHttpBackend(responseBody);
         backendUri = URI.create("http://127.0.0.1:" + backend.port() + "/api/bench");

         // Build the incoming request material once: callBackend() never mutates its inputs.
         requestBody = ProxyBenchPayloads.PayloadSize.valueOf(requestPayload).buildJson();
         headersTemplate = ProxyBenchPayloads.HeaderCount.valueOf(headerCount).buildHeaders();

         // Secret axis: NONE, or a non-elicited TOKEN (Bearer) / BASIC (Base64) backend secret.
         SecretEntry secret = switch (secretMode) {
            case "NONE" -> null;
            case "TOKEN" -> new SecretEntry("bench-secret", null, null,
                  "tok_bench_0123456789abcdef", null, null, false, null);
            case "BASIC" -> new SecretEntry("bench-secret", "bench-user", "bench-password",
                  null, null, null, false, null);
            default -> throw new IllegalArgumentException("Unknown secretMode: " + secretMode);
         };

         // Explicit backend timeout so the CDI-injected default is never dereferenced.
         configuration = new ConfigurationEntry("cfg-1", "bench-default", backendUri.toString(),
               10_000L, null, null, null, null, secret);

         proxy = ProxyFactory.forName(proxyImpl)
               .create(new SecretReferenceResolver(List.of()), new UserSecretStore(null));

         // Stateless-mode handling info, as bound by the MCP layer around each tool call.
         handlingInfo = new MethodHandlingInfo("127.0.0.1", null, null, null, "org-bench");

         System.out.printf("%n>>> Proxy bench: request %.1f KiB, %d headers, secret %s, response %.1f KiB, impl %s%n",
               requestBody.getBytes(StandardCharsets.UTF_8).length / 1024.0, headersTemplate.size(),
               secretMode, responseBody.length / 1024.0, proxyImpl);

         // Sanity check: one full round-trip must succeed before measuring anything.
         BackendResponse response = doCall(this);
         if (response.status() != 200 || response.content().length != responseBody.length) {
            throw new IllegalStateException("Sanity check failed: status " + response.status()
                  + ", body " + response.content().length + " bytes (expected " + responseBody.length + ")");
         }
      }

      @TearDown(Level.Trial)
      public void tearDownTrial() {
         backend.close();
      }
   }

   /** No concurrency: single caller, pure sequential latency of the proxy path. */
   @Benchmark
   @Threads(1)
   public BackendResponse callBackend_noConcurrency(BenchState bench) {
      return checkedCall(bench);
   }

   /** Low concurrency: 4 simultaneous callers sharing the proxy and its HttpClient. */
   @Benchmark
   @Threads(4)
   public BackendResponse callBackend_lowConcurrency(BenchState bench) {
      return checkedCall(bench);
   }

   /** Medium concurrency: 16 simultaneous callers. */
   @Benchmark
   @Threads(16)
   public BackendResponse callBackend_mediumConcurrency(BenchState bench) {
      return checkedCall(bench);
   }

   /** High concurrency: 64 simultaneous callers. */
   @Benchmark
   @Threads(64)
   public BackendResponse callBackend_highConcurrency(BenchState bench) {
      return checkedCall(bench);
   }

   /** Call the proxy and fail fast if anything but a 200 comes back (avoids measuring errors). */
   private static BackendResponse checkedCall(BenchState bench) {
      BackendResponse response = doCall(bench);
      if (response.status() != 200) {
         throw new IllegalStateException("Unexpected backend response status: " + response.status()
               + " - " + new String(response.content(), StandardCharsets.UTF_8));
      }
      return response;
   }

   /** Execute one proxied call inside a bound MethodHandlingContext, as the MCP layer does. */
   private static BackendResponse doCall(BenchState bench) {
      return ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, bench.handlingInfo)
            .call(() -> bench.proxy.callBackend(bench.configuration, bench.backendUri, "POST",
                  bench.headersTemplate, bench.requestBody));
   }
}

