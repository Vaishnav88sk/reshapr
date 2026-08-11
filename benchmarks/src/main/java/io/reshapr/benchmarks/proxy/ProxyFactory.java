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
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import java.util.Map;

/**
 * Factory abstraction over the {@link ProxyService} implementation under benchmark.
 *
 * <p>To evaluate an alternative/optimized implementation, register it in {@link #FACTORIES}
 * with a new key and add that key to the {@code proxyImpl} {@code @Param} of
 * {@code ProxyServiceCallBackendBenchmark}. Both implementations will then be measured side by side.</p>
 *
 * @author laurent
 */
public interface ProxyFactory {

   ProxyService create(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore);

   /** Registry of proxy implementations under benchmark, keyed by the {@code proxyImpl} param. */
   Map<String, ProxyFactory> FACTORIES = Map.of(
         "current", ProxyService::new,
         "optimized", OptimizedProxyService::new
   );

   static ProxyFactory forName(String name) {
      ProxyFactory factory = FACTORIES.get(name);
      if (factory == null) {
         throw new IllegalArgumentException("Unknown proxy implementation: " + name);
      }
      return factory;
   }
}

