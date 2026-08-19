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
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;
import io.reshapr.proxy.util.GrpcUtil;

import com.google.protobuf.Descriptors;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Behavioural verification that {@link OptimizedGrpcProxyService} preserves <strong>per-call
 * authorization</strong> while reusing a single pooled {@code ManagedChannel} per endpoint.
 *
 * <p>This is the correctness companion of {@link GrpcProxyServiceCallBackendBenchmark}: the benchmark proves the
 * pooling is <em>faster</em>, this program proves it is still <em>correct</em> with respect to the one
 * invariant that channel reuse could plausibly break — that two different users, multiplexed over the
 * same transport, still present their own credentials to the backend.</p>
 *
 * <p>It runs three checks against a {@link CapturingGrpcBackend} that records the {@code Authorization}
 * metadata of every call:</p>
 * <ol>
 *   <li>Two calls with different inbound {@code Authorization} headers are seen by the backend with
 *       their respective tokens (per-call request metadata path).</li>
 *   <li>A call using a backend-secret token is seen with that token via gRPC {@code CallCredentials}
 *       (per-call {@code CallOptions} path).</li>
 *   <li>Exactly one {@code ManagedChannel} is created for the endpoint across all of the above.</li>
 * </ol>
 *
 * <p>Exit code 0 means all checks passed; 1 means at least one failed.</p>
 *
 * @author laurent
 */
public final class GrpcChannelReuseVerification {

   private static final byte[] CANNED_RESPONSE_BYTES =
         Base64.getDecoder().decode("ChFIZWxsbywgQmVuY2htYXJrIQ==");

   private static final String REQUEST_BODY = "{\"firstname\":\"Bench\",\"lastname\":\"Mark\"}";

   public static void main(String[] args) throws Exception {
      String service = GrpcProxyServiceCallBackendBenchmark.SERVICE_NAME;
      String method = GrpcProxyServiceCallBackendBenchmark.METHOD_NAME;
      String descriptor = GrpcProxyServiceCallBackendBenchmark.HELLO_V1_DESCRIPTOR_BASE64;

      boolean ok = true;

      try (CapturingGrpcBackend backend = new CapturingGrpcBackend(service, method, CANNED_RESPONSE_BYTES)) {
         String endpoint = "http://localhost:" + backend.port();
         Descriptors.MethodDescriptor md = GrpcUtil.findMethodDescriptor(descriptor, service, method);

         OptimizedGrpcProxyService proxy =
               new OptimizedGrpcProxyService(new SecretReferenceResolver(List.of()), new UserSecretStore(null));
         proxy.defaultBackendTimeout = 10_000L;

         MethodHandlingInfo handlingInfo = new MethodHandlingInfo("127.0.0.1", null, null, null, "org-verify");

         // --- Check 1: per-user inbound Authorization header, no backend secret --------------------
         ConfigurationEntry cfgNoSecret = new ConfigurationEntry("cfg-verify-1", "verify-grpc", endpoint,
               10_000L, null, null, null, null, null);

         callWithAuthorization(proxy, cfgNoSecret, md, handlingInfo, "Bearer user-A-token");
         callWithAuthorization(proxy, cfgNoSecret, md, handlingInfo, "Bearer user-B-token");

         List<String> seen = backend.capturedAuthorizations();
         boolean check1 = seen.equals(List.of("Bearer user-A-token", "Bearer user-B-token"));
         ok &= report("Per-call Authorization isolation over pooled channel", check1,
               "backend observed " + seen);

         // --- Check 2: backend-secret token via CallCredentials ------------------------------------
         backend.clearCaptured();
         SecretEntry secret = new SecretEntry("verify-secret", null, null,
               "svc-token-xyz", null, null, false, null);
         ConfigurationEntry cfgSecret = new ConfigurationEntry("cfg-verify-2", "verify-grpc", endpoint,
               10_000L, null, null, null, null, secret);

         callWithAuthorization(proxy, cfgSecret, md, handlingInfo, null);

         List<String> seenSecret = backend.capturedAuthorizations();
         boolean check2 = seenSecret.equals(List.of("Bearer svc-token-xyz"));
         ok &= report("Backend-secret token applied per-call via CallCredentials", check2,
               "backend observed " + seenSecret);

         // --- Check 3: a single ManagedChannel was pooled for the endpoint -------------------------
         long channelCount = proxy.pooledChannelCount();
         boolean check3 = channelCount == 1;
         ok &= report("Exactly one pooled ManagedChannel for the endpoint", check3,
               "channel cache size = " + channelCount);

         proxy.shutdown();
      }

      System.out.println();
      System.out.println(ok ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
      System.exit(ok ? 0 : 1);
   }

   private static void callWithAuthorization(OptimizedGrpcProxyService proxy, ConfigurationEntry cfg,
         Descriptors.MethodDescriptor md, MethodHandlingInfo handlingInfo, String authorization) {
      Map<String, List<String>> headers = new HashMap<>();
      headers.put("x-reshapr-key", List.of("rspr_dummy_gateway_key"));
      if (authorization != null) {
         headers.put("Authorization", List.of(authorization));
      }
      ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, handlingInfo).run(() -> {
         try {
            BackendResponse response = proxy.callBackend(cfg, md, headers, REQUEST_BODY);
            if (response.status() != 0) {
               throw new IllegalStateException("Unexpected gRPC status " + response.status()
                     + " - " + new String(response.content()));
            }
         } catch (Exception e) {
            throw new RuntimeException("gRPC proxy call failed", e);
         }
      });
   }

   private static boolean report(String name, boolean passed, String detail) {
      System.out.printf("[%s] %s (%s)%n", passed ? "PASS" : "FAIL", name, detail);
      return passed;
   }
}
