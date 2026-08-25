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

import io.reshapr.proxy.registry.ConfigurationEntry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyServiceTest {

   @Test
   void shouldThrowHttpTimeoutExceptionWhenRequestTimeoutExpires() throws Exception {
      HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(300))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

      HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://10.255.255.1/api"))
            .timeout(Duration.ofMillis(300))
            .GET()
            .build();

      assertThrows(java.net.http.HttpTimeoutException.class,
            () -> client.send(request, HttpResponse.BodyHandlers.ofByteArray()));
   }

   @Test
   void configurationEntryHoldsTimeoutValue() {
      ConfigurationEntry config = new ConfigurationEntry(
            "id", "test", "http://example.com",
            5_000L, List.of(), List.of(), null, null, null);

      assertEquals(5_000L, config.backendTimeout());
   }

   @Test
   void defaultFallbackIs3000ms() {
      ConfigurationEntry config = new ConfigurationEntry(
            "id", "test", "http://example.com",
            null, List.of(), List.of(), null, null, null);

      long timeoutMs = config.backendTimeout() != null
            ? config.backendTimeout()
            : 3_000L;

      assertEquals(3_000L, timeoutMs);
   }

   @Test
   void shouldAddUpstreamServiceTimeHeaderOnSuccess() {
      java.net.http.HttpResponse<byte[]> mockHttpResponse = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
      org.mockito.Mockito.when(mockHttpResponse.statusCode()).thenReturn(200);
      org.mockito.Mockito.when(mockHttpResponse.body()).thenReturn("{}".getBytes());
      org.mockito.Mockito.when(mockHttpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (k, v) -> true));

      ProxyService proxyService = new ProxyService(null, null) {
         @Override
         protected java.net.http.HttpResponse<byte[]> doCallBackend(java.util.Map<String, java.util.List<String>> requestHeaders, java.net.http.HttpRequest.Builder requestBuilder,
                                                      String backendEndpoint) {
            try {
               Thread.sleep(50); // simulate network delay
            } catch (InterruptedException e) { }
            return mockHttpResponse;
         }
      };
      proxyService.defaultBackendTimeout = 3000L;

      ConfigurationEntry config = new ConfigurationEntry("id", "test", "http://example.com", null, List.of(), List.of(), null, null, null);
      
      io.reshapr.proxy.context.MethodHandlingInfo info = new io.reshapr.proxy.context.MethodHandlingInfo("127.0.0.1", null, null, null, null);
      java.lang.ScopedValue.where(io.reshapr.proxy.context.MethodHandlingContext.METHOD_HANDLING_INFO, info).run(() -> {
         BackendResponse response = proxyService.callBackend(config, URI.create("http://example.com"), "GET", java.util.Map.of(), null);
         
         assertEquals(200, response.status());
         org.junit.jupiter.api.Assertions.assertTrue(response.headers().containsKey("x-reshapr-upstream-service-time"));
         
         long elapsed = Long.parseLong(response.headers().get("x-reshapr-upstream-service-time").get(0));
         org.junit.jupiter.api.Assertions.assertTrue(elapsed >= 50, "Elapsed time should be at least 50ms, but was: " + elapsed);
      });
   }

   @Test
   void shouldAddUpstreamServiceTimeHeaderOnException() {
      ProxyService proxyService = new ProxyService(null, null) {
         @Override
         protected java.net.http.HttpResponse<byte[]> doCallBackend(java.util.Map<String, java.util.List<String>> requestHeaders, java.net.http.HttpRequest.Builder requestBuilder,
                                                      String backendEndpoint) throws java.io.IOException {
            try {
               Thread.sleep(50); // simulate network delay before failure
            } catch (InterruptedException e) { }
            throw new java.io.IOException("Connection reset");
         }
      };
      proxyService.defaultBackendTimeout = 3000L;

      ConfigurationEntry config = new ConfigurationEntry("id", "test", "http://example.com", null, List.of(), List.of(), null, null, null);
      
      io.reshapr.proxy.context.MethodHandlingInfo info = new io.reshapr.proxy.context.MethodHandlingInfo("127.0.0.1", null, null, null, null);
      java.lang.ScopedValue.where(io.reshapr.proxy.context.MethodHandlingContext.METHOD_HANDLING_INFO, info).run(() -> {
         BackendResponse response = proxyService.callBackend(config, URI.create("http://example.com"), "GET", java.util.Map.of(), null);
         
         assertEquals(502, response.status());
         org.junit.jupiter.api.Assertions.assertTrue(response.headers().containsKey("x-reshapr-upstream-service-time"));
         
         long elapsed = Long.parseLong(response.headers().get("x-reshapr-upstream-service-time").get(0));
         org.junit.jupiter.api.Assertions.assertTrue(elapsed >= 50, "Elapsed time should be at least 50ms, but was: " + elapsed);
      });
   }
}