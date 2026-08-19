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
import io.reshapr.proxy.proxy.GrpcProxyService;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;
import io.reshapr.proxy.util.GrpcUtil;

import com.google.protobuf.Descriptors;
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

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark for {@link GrpcProxyService#callBackend}: pure gRPC proxying performance,
 * end-to-end over loopback, against an ultra-fast canned-response gRPC backend
 * ({@link MinimalGrpcBackend}) so the backend itself is never the bottleneck.
 *
 * <p>This benchmark uses the {@code hello-v1} proto schema (HelloService/greeting), the
 * simplest schema already present in the project dev resources.</p>
 *
 * <p>Axes:</p>
 * <ul>
 *   <li>{@code grpcProxyImpl}: implementation under test (see {@link GrpcProxyFactory}) —
 *       {@code current} (new channel per call) vs {@code optimized} (pooled channels)</li>
 *   <li>{@code requestPayload}: SMALL / MEDIUM / LARGE incoming JSON body (~50 B / ~500 B / ~2 KiB)</li>
 *   <li>{@code secretMode}: NONE / TOKEN (Bearer token backend secret)</li>
 *   <li>concurrency: dedicated benchmark methods at 1 (none), 4 (low), 16 (medium), 64 (high) threads</li>
 * </ul>
 *
 * <p><strong>Comparing implementations in one run:</strong> the {@code grpcProxyImpl} axis is wired
 * through {@link GrpcProxyFactory}, so a single invocation measures both the production
 * {@link GrpcProxyService} (which creates and shuts down a new {@code ManagedChannel} on every call,
 * see {@code GrpcProxyService#callBackend} lines ~117–133) and the candidate
 * {@link OptimizedGrpcProxyService} (which pools channels per endpoint) side by side.</p>
 *
 * <p>Run with {@code -prof gc} to also capture per-op allocations.</p>
 *
 * @author laurent
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = { "--enable-preview", "-Xms512m", "-Xmx512m" })
public class GrpcProxyServiceCallBackendBenchmark {

   /**
    * Base64-encoded FileDescriptorSet for hello-v1.proto (HelloService/greeting).
    * Generated from the proto schema at dev/hello-v1.proto using GrpcUtil-compatible encoding.
    */
   static final String HELLO_V1_DESCRIPTOR_BASE64 =
         "Cq4CCg5oZWxsby12MS5wcm90bxIgaW8uZ2l0aHViLm1pY3JvY2tzLmdycGMuaGVsbG8udjEiSAoM"
         + "SGVsbG9SZXF1ZXN0EhwKCWZpcnN0bmFtZRgBIAEoCVIJZmlyc3RuYW1lEhoKCGxhc3RuYW1lGAIg"
         + "ASgJUghsYXN0bmFtZSIrCg1IZWxsb1Jlc3BvbnNlEhoKCGdyZWV0aW5nGAEgASgJUghncmVldGlu"
         + "ZzJ7CgxIZWxsb1NlcnZpY2USawoIZ3JlZXRpbmcSLi5pby5naXRodWIubWljcm9ja3MuZ3JwYy5o"
         + "ZWxsby52MS5IZWxsb1JlcXVlc3QaLy5pby5naXRodWIubWljcm9ja3MuZ3JwYy5oZWxsby52MS5I"
         + "ZWxsb1Jlc3BvbnNlYgZwcm90bzM=";

   static final String SERVICE_NAME = "io.github.microcks.grpc.hello.v1.HelloService";
   static final String METHOD_NAME = "greeting";

   /** Canned serialized HelloResponse{greeting="Hello, Benchmark!"} bytes (base64-encoded). */
   private static final byte[] CANNED_RESPONSE_BYTES =
         Base64.getDecoder().decode("ChFIZWxsbywgQmVuY2htYXJrIQ==");

   /** Shared, per-trial state: gRPC backend, proxy under test and pre-built request material. */
   @State(Scope.Benchmark)
   public static class BenchState {

      @Param({ "current", "optimized" })
      public String grpcProxyImpl;

      @Param({ "SMALL", "MEDIUM", "LARGE" })
      public String requestPayload;

      @Param({ "NONE", "TOKEN" })
      public String secretMode;

      MinimalGrpcBackend backend;
      GrpcProxyFactory.GrpcProxyInvoker invoker;
      ConfigurationEntry configuration;
      Descriptors.MethodDescriptor methodDescriptor;
      String requestBody;
      MethodHandlingInfo handlingInfo;

      @Setup(Level.Trial)
      public void setupTrial() throws Exception {
         // Start the minimal backend serving the canned HelloResponse.
         backend = new MinimalGrpcBackend(SERVICE_NAME, METHOD_NAME, CANNED_RESPONSE_BYTES);
         String backendEndpoint = "http://localhost:" + backend.port();

         // Resolve the method descriptor from the hello-v1 proto.
         methodDescriptor = GrpcUtil.findMethodDescriptor(HELLO_V1_DESCRIPTOR_BASE64, SERVICE_NAME, METHOD_NAME);

         // Build request JSON for the HelloRequest message (firstname + lastname fields).
         // NOTE: HelloRequest only has firstname + lastname, and the proxy's JSON->protobuf parser
         //       is STRICT (unknown fields are rejected). Larger payloads are therefore produced by
         //       inflating the valid lastname value, never by adding synthetic fields.
         requestBody = switch (requestPayload) {
            case "SMALL" -> "{\"firstname\":\"Bench\",\"lastname\":\"Mark\"}";
            case "MEDIUM" -> buildRequest(500);   // ~500 B
            case "LARGE"  -> buildRequest(2048);  // ~2 KiB
            default -> throw new IllegalArgumentException("Unknown requestPayload: " + requestPayload);
         };

         // Secret axis: NONE or a pre-resolved TOKEN (Bearer) secret.
         SecretEntry secret = switch (secretMode) {
            case "NONE"  -> null;
            case "TOKEN" -> new SecretEntry("bench-secret", null, null,
                  "tok_bench_0123456789abcdef", null, null, false, null);
            default -> throw new IllegalArgumentException("Unknown secretMode: " + secretMode);
         };

         configuration = new ConfigurationEntry("cfg-grpc-1", "bench-grpc", backendEndpoint,
               10_000L, null, null, null, null, secret);

         invoker = GrpcProxyFactory.forName(grpcProxyImpl)
               .create(new SecretReferenceResolver(List.of()), new UserSecretStore(null));

         handlingInfo = new MethodHandlingInfo("127.0.0.1", null, null, null, "org-bench");

         System.out.printf("%n>>> gRPC proxy bench: impl %s, requestPayload %s (%d bytes), secret %s%n",
               grpcProxyImpl, requestPayload, requestBody.length(), secretMode);

         // Sanity check: one successful round-trip before measurement.
         // NOTE: GrpcProxyService returns status 0 on success (gRPC Status.Code.OK.value()),
         //       NOT the HTTP 200 used by the HTTP ProxyService.
         BackendResponse response = doCall(this);
         if (response.status() != 0) {
            throw new IllegalStateException("Sanity check failed: status " + response.status()
                  + " - " + new String(response.content()));
         }
         System.out.printf(">>> Sanity check passed: gRPC status %d, body %d bytes%n",
               response.status(), response.content().length);
      }

      @TearDown(Level.Trial)
      public void tearDownTrial() {
         if (invoker != null) {
            invoker.shutdown();
         }
         if (backend != null) {
            backend.close();
         }
      }

      /**
       * Build a larger JSON request that stays schema-valid by inflating the {@code lastname}
       * value to approximately {@code targetBytes} of padding. HelloRequest exposes only
       * firstname + lastname and the proxy rejects unknown fields, so we never add synthetic ones.
       */
      private static String buildRequest(int targetBytes) {
         StringBuilder lastname = new StringBuilder("Mark-");
         while (lastname.length() < targetBytes) {
            lastname.append("padding-");
         }
         return "{\"firstname\":\"Bench\",\"lastname\":\"" + lastname + "\"}";
      }
   }

   /** No concurrency: single caller, pure sequential latency of the gRPC proxy path. */
   @Benchmark
   @Threads(1)
   public BackendResponse callBackend_noConcurrency(BenchState bench) {
      return checkedCall(bench);
   }

   /** Low concurrency: 4 simultaneous callers sharing the gRPC proxy. */
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

   /**
    * Call the proxy and fail fast if anything but gRPC status 0 (OK) comes back.
    * Note: GrpcProxyService returns Status.Code.OK.value() = 0, not HTTP 200.
    */
   private static BackendResponse checkedCall(BenchState bench) {
      BackendResponse response = doCall(bench);
      if (response.status() != 0) {
         throw new IllegalStateException("Unexpected gRPC backend response status: " + response.status()
               + " - " + new String(response.content()));
      }
      return response;
   }

   /**
    * Execute one proxied gRPC call inside a bound MethodHandlingContext, exactly as the MCP layer
    * does when invoking a gRPC-backed tool.
    */
   private static BackendResponse doCall(BenchState bench) {
      Map<String, List<String>> headers = new HashMap<>();
      headers.put("x-reshapr-key", List.of("rspr_dummy_gateway_key"));
      return ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, bench.handlingInfo)
            .call(() -> {
               try {
                  return bench.invoker.callBackend(bench.configuration, bench.methodDescriptor,
                        headers, bench.requestBody);
               } catch (Exception e) {
                  throw new RuntimeException("gRPC proxy call failed", e);
               }
            });
   }
}
