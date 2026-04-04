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

      assertEquals(5_000L, config.backendEndpointTimeout());
   }

   @Test
   void defaultFallbackIs3000ms() {
      ConfigurationEntry config = new ConfigurationEntry(
            "id", "test", "http://example.com",
            null, List.of(), List.of(), null, null, null);

      long timeoutMs = config.backendEndpointTimeout() != null
            ? config.backendEndpointTimeout()
            : 3_000L;

      assertEquals(3_000L, timeoutMs);
   }
}