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
import java.util.List;
import java.util.Map;

/**
 * Factory abstraction over the gRPC proxy implementation under benchmark, mirroring
 * {@code io.reshapr.benchmarks.proxy.ProxyFactory}.
 *
 * <p>Unlike the HTTP {@code ProxyService}/{@code OptimizedProxyService} pair (which share a common
 * supertype), {@link GrpcProxyService} (production, per-call channel) and
 * {@link OptimizedGrpcProxyService} (candidate, pooled channels) are unrelated types. This factory
 * therefore hands out a thin {@link GrpcProxyInvoker} adapter with a uniform {@code callBackend}
 * signature so both can be measured side by side in a single run.</p>
 *
 * <p>To evaluate a further implementation, register it in {@link #FACTORIES} with a new key and add
 * that key to the {@code grpcProxyImpl} {@code @Param} of {@code GrpcProxyServiceCallBackendBenchmark}.</p>
 *
 * @author laurent
 */
public interface GrpcProxyFactory {

   /** Default backend timeout injected into the implementation under test (no CDI in benchmarks). */
   long DEFAULT_TIMEOUT_MS = 10_000L;

   GrpcProxyInvoker create(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore);

   /** Registry of gRPC proxy implementations under benchmark, keyed by the {@code grpcProxyImpl} param. */
   Map<String, GrpcProxyFactory> FACTORIES = Map.of(
         "current", (resolver, store) -> new CurrentGrpcProxyInvoker(resolver, store),
         "optimized", (resolver, store) -> new OptimizedGrpcProxyInvoker(resolver, store)
   );

   static GrpcProxyFactory forName(String name) {
      GrpcProxyFactory factory = FACTORIES.get(name);
      if (factory == null) {
         throw new IllegalArgumentException("Unknown gRPC proxy implementation: " + name);
      }
      return factory;
   }

   /**
    * Uniform adapter over a gRPC proxy implementation's {@code callBackend} entry point plus its
    * optional pooled-channel lifecycle, so the benchmark can drive any implementation identically.
    */
   interface GrpcProxyInvoker {

      BackendResponse callBackend(ConfigurationEntry configuration, Descriptors.MethodDescriptor md,
            Map<String, List<String>> headers, String body) throws IOException;

      /** Number of pooled channels, or {@code -1} if the implementation does not pool. */
      default long pooledChannelCount() {
         return -1L;
      }

      /** Release any pooled resources. No-op for implementations that create a channel per call. */
      default void shutdown() {
      }
   }

   /** Adapter for the production {@link GrpcProxyService} (creates and shuts down a channel per call). */
   final class CurrentGrpcProxyInvoker implements GrpcProxyInvoker {

      private final GrpcProxyService delegate;

      CurrentGrpcProxyInvoker(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore) {
         this.delegate = new GrpcProxyService(secretResolver, userSecretStore);
         // GrpcProxyService reads its timeout from a CDI @ConfigProperty field; inject it directly.
         try {
            java.lang.reflect.Field field = GrpcProxyService.class.getDeclaredField("defaultBackendTimeout");
            field.setAccessible(true);
            field.set(delegate, DEFAULT_TIMEOUT_MS);
         } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to inject default timeout into GrpcProxyService", e);
         }
      }

      @Override
      public BackendResponse callBackend(ConfigurationEntry configuration, Descriptors.MethodDescriptor md,
            Map<String, List<String>> headers, String body) throws IOException {
         return delegate.callBackend(configuration, md, headers, body);
      }
   }

   /** Adapter for the candidate {@link OptimizedGrpcProxyService} (pooled channels, per-call credentials). */
   final class OptimizedGrpcProxyInvoker implements GrpcProxyInvoker {

      private final OptimizedGrpcProxyService delegate;

      OptimizedGrpcProxyInvoker(SecretReferenceResolver secretResolver, UserSecretStore userSecretStore) {
         this.delegate = new OptimizedGrpcProxyService(secretResolver, userSecretStore);
         this.delegate.defaultBackendTimeout = DEFAULT_TIMEOUT_MS;
      }

      @Override
      public BackendResponse callBackend(ConfigurationEntry configuration, Descriptors.MethodDescriptor md,
            Map<String, List<String>> headers, String body) throws IOException {
         return delegate.callBackend(configuration, md, headers, body);
      }

      @Override
      public long pooledChannelCount() {
         return delegate.pooledChannelCount();
      }

      @Override
      public void shutdown() {
         delegate.shutdown();
      }
   }
}
