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
package io.reshapr.benchmarks.mcp;

import io.reshapr.proxy.security.SecureEndpointFilter;

import io.opentelemetry.api.OpenTelemetry;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.infinispan.commons.api.BasicCache;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Container-free stubs for the JAX-RS / Vert.x / CDI collaborators required to invoke
 * {@code McpController} outside of Quarkus. Only the members actually touched by the
 * {@code tools/call} handling path are functional; everything else throws or returns defaults.
 * @author laurent
 */
public final class McpBenchStubs {

   private McpBenchStubs() {
   }

   // ----------------------------------------------------------------------------------------------------
   // JAX-RS HttpHeaders — concrete implementation (hot path: getRequestHeader is called several times).
   // ----------------------------------------------------------------------------------------------------

   /** A minimal, read-only {@link HttpHeaders} backed by a {@link MultivaluedMap}. */
   public static final class BenchHttpHeaders implements HttpHeaders {

      private final MultivaluedMap<String, String> headers;

      public BenchHttpHeaders(Map<String, List<String>> values) {
         this.headers = new MultivaluedHashMap<>();
         values.forEach(headers::put);
      }

      @Override
      public List<String> getRequestHeader(String name) {
         return headers.get(name);
      }

      @Override
      public String getHeaderString(String name) {
         List<String> values = headers.get(name);
         return (values == null || values.isEmpty()) ? null : String.join(",", values);
      }

      @Override
      public MultivaluedMap<String, String> getRequestHeaders() {
         return headers;
      }

      @Override
      public List<MediaType> getAcceptableMediaTypes() {
         return List.of(MediaType.APPLICATION_JSON_TYPE);
      }

      @Override
      public List<Locale> getAcceptableLanguages() {
         return List.of(Locale.ENGLISH);
      }

      @Override
      public MediaType getMediaType() {
         return MediaType.APPLICATION_JSON_TYPE;
      }

      @Override
      public Locale getLanguage() {
         return null;
      }

      @Override
      public Map<String, Cookie> getCookies() {
         return Map.of();
      }

      @Override
      public Date getDate() {
         return null;
      }

      @Override
      public int getLength() {
         return -1;
      }
   }

   // ----------------------------------------------------------------------------------------------------
   // Vert.x HttpServerRequest — dynamic proxy (only remoteAddress()/getHeader() are ever touched).
   // ----------------------------------------------------------------------------------------------------

   /** Build a Vert.x {@link HttpServerRequest} stub answering {@code remoteAddress()} and {@code getHeader()}. */
   public static HttpServerRequest httpServerRequest(Map<String, List<String>> headers) {
      SocketAddress remoteAddress = SocketAddress.inetSocketAddress(51515, "127.0.0.1");
      InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
         case "remoteAddress" -> remoteAddress;
         case "getHeader" -> {
            List<String> values = headers.get((String) args[0]);
            yield (values == null || values.isEmpty()) ? null : values.getFirst();
         }
         default -> defaultValue(method);
      };
      return (HttpServerRequest) Proxy.newProxyInstance(McpBenchStubs.class.getClassLoader(),
            new Class<?>[] { HttpServerRequest.class }, handler);
   }

   // ----------------------------------------------------------------------------------------------------
   // JAX-RS ContainerRequestContext — dynamic proxy (only getProperty() is ever touched).
   // ----------------------------------------------------------------------------------------------------

   /** Build a {@link ContainerRequestContext} stub carrying the security properties set by the auth filter. */
   public static ContainerRequestContext containerRequestContext(String userId, String issuer) {
      InvocationHandler handler = (proxy, method, args) -> {
         if ("getProperty".equals(method.getName())) {
            String name = (String) args[0];
            if (SecureEndpointFilter.USER_ID_PROPERTY.equals(name)) {
               return userId;
            }
            if (SecureEndpointFilter.ISSUER_PROPERTY.equals(name)) {
               return issuer;
            }
            return null;
         }
         return defaultValue(method);
      };
      return (ContainerRequestContext) Proxy.newProxyInstance(McpBenchStubs.class.getClassLoader(),
            new Class<?>[] { ContainerRequestContext.class }, handler);
   }

   // ----------------------------------------------------------------------------------------------------
   // Infinispan BasicCache — dynamic proxy backed by a local ConcurrentHashMap (session store backend).
   // ----------------------------------------------------------------------------------------------------

   /** Build a {@link BasicCache} stub backed by an in-process {@link ConcurrentHashMap}. */
   @SuppressWarnings("unchecked")
   public static <K, V> BasicCache<K, V> basicCache() {
      ConcurrentHashMap<K, V> map = new ConcurrentHashMap<>();
      InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
         case "get" -> map.get((K) args[0]);
         case "put" -> map.put((K) args[0], (V) args[1]);
         case "remove" -> map.remove(args[0]);
         case "containsKey" -> map.containsKey(args[0]);
         case "size" -> map.size();
         case "getName" -> "bench-cache";
         default -> defaultValue(method);
      };
      return (BasicCache<K, V>) Proxy.newProxyInstance(McpBenchStubs.class.getClassLoader(),
            new Class<?>[] { BasicCache.class }, handler);
   }

   // ----------------------------------------------------------------------------------------------------
   // CDI Instance<OpenTelemetry> — always unresolvable, so AuditLogger disables OTEL emission.
   // ----------------------------------------------------------------------------------------------------

   /** Build an unresolvable {@link Instance} so the audit logger stays disabled (no OTEL emission). */
   @SuppressWarnings("unchecked")
   public static Instance<OpenTelemetry> emptyOpenTelemetryInstance() {
      InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
         case "isResolvable" -> false;
         case "isUnsatisfied" -> true;
         case "isAmbiguous" -> false;
         default -> defaultValue(method);
      };
      return (Instance<OpenTelemetry>) Proxy.newProxyInstance(McpBenchStubs.class.getClassLoader(),
            new Class<?>[] { Instance.class }, handler);
   }

   /** Neutral default value for un-stubbed proxy methods (avoids NPE on primitive returns). */
   private static Object defaultValue(Method method) {
      Class<?> type = method.getReturnType();
      if (type == boolean.class) {
         return false;
      }
      if (type == int.class || type == long.class || type == short.class || type == byte.class) {
         return 0;
      }
      if (type == double.class || type == float.class) {
         return 0d;
      }
      if (type == char.class) {
         return (char) 0;
      }
      return null;
   }
}

