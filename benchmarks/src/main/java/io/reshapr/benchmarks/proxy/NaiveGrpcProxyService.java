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
import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.GrpcProxyService;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import com.google.protobuf.Descriptors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A naive {@link GrpcProxyService} stub that never performs any I/O: it always returns the very
 * same canned response. This isolates the benchmark on the converter logic only.
 * @author laurent
 */
public class NaiveGrpcProxyService extends GrpcProxyService {

   private static final byte[] CANNED_BODY = "{\"status\":\"ok\",\"id\":\"42\"}".getBytes(StandardCharsets.UTF_8);

   public NaiveGrpcProxyService() {
      super(new SecretReferenceResolver(List.of()), new UserSecretStore(null));
   }

   @Override
   public BackendResponse callBackend(ConfigurationEntry configuration, Descriptors.MethodDescriptor md,
                                      Map<String, List<String>> headers, String body) throws IOException {
      return new BackendResponse(0, CANNED_BODY, Map.of()); // 0 is gRPC OK status
   }
}
