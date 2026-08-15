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

import io.reshapr.proxy.context.MethodHandlingContext;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;

import java.util.List;
import java.util.Map;

/**
 * Utility class for handling HTTP headers.
 * @author laurent
 */
public class HeadersUtil {

   public static final String FORWARDED = "Forwarded";
   public static final String X_FORWARDED_FOR = "X-Forwarded-For";
   private static final String FORWARDED_FOR = "for=";

   private static final TextMapPropagator OTEL_PROPAGATOR =
         TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(),
               W3CBaggagePropagator.getInstance());

   private HeadersUtil () {
      // Utility class
   }

   /**
    * Add or update Forwarded and X-Forwarded-For headers to include the remote address
    * of the original client making the request.
    * @param headers The headers map to update.
    */
   public static void addForwardingHeaders(Map<String, List<String>> headers) {
      // Add standard Forwarded header first.
      String forwardedHeader = headers.getOrDefault(FORWARDED, List.of()).stream().findFirst().orElse(null);
      if (forwardedHeader != null && !forwardedHeader.isBlank()) {
         headers.put(FORWARDED, List.of(forwardedHeader + ", " + FORWARDED_FOR + getForAddress(MethodHandlingContext.getRemoteAddress())));
      } else {

         // Non considered best practice to have both Forwarded and X-Forwarded-For headers,
         String xForwardedForHeader = headers.getOrDefault(X_FORWARDED_FOR, List.of()).stream().findFirst().orElse(null);
         if (xForwardedForHeader != null && !xForwardedForHeader.isBlank()) {
            headers.put(X_FORWARDED_FOR, List.of(xForwardedForHeader + ", " + MethodHandlingContext.getRemoteAddress()));
         } else {
            // No Forwarded nor X-Forwarded-For headers, we can add Forwarded.
            headers.put(FORWARDED, List.of(FORWARDED_FOR + getForAddress(MethodHandlingContext.getRemoteAddress())));
         }
      }
   }

   /**
    * Inject tracing headers into the given map.
    * @param headers The headers map to inject into.
    */
   public static void injectTracingHeaders(Map<String, List<String>> headers) {
      OTEL_PROPAGATOR.inject(Context.current(), headers, new MapTextMapSetter());
   }

   private static String getForAddress(String remoteAddress) {
      if (!remoteAddress.contains(":")) {
         return remoteAddress;
      }
      // IP v6 address, must be enclosed in square brackets and quoted.
      // See https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Forwarded#transitioning_from_x-forwarded-for_to_forwarded
      return "\"[" + remoteAddress + "]\"";
   }

   private static class MapTextMapSetter implements TextMapSetter<Map<String, List<String>>> {
      @Override
      public void set(Map<String, List<String>> carrier, String key, String value) {
         if (carrier != null) {
            carrier.put(key, List.of(value));
         }
      }
   }

   /**
    * Filter headers based on an allow-list or deny-list.
    * Allow-list takes precedence. If allow-list is present, only headers in it are kept.
    * Otherwise, if deny-list is present, headers in it are removed.
    */
   public static Map<String, List<String>> filterHeaders(Map<String, List<String>> headers,
         List<String> allowed, List<String> denied) {
      if ((allowed == null || allowed.isEmpty()) && (denied == null || denied.isEmpty())) {
         return headers;
      }

      Map<String, List<String>> filtered = new java.util.HashMap<>();
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
         String headerName = entry.getKey().toLowerCase();
         if (allowed != null && !allowed.isEmpty()) {
            if (isHeaderInList(headerName, allowed)) {
               filtered.put(entry.getKey(), entry.getValue());
            }
         } else if (denied != null && !denied.isEmpty()) {
            if (!isHeaderInList(headerName, denied)) {
               filtered.put(entry.getKey(), entry.getValue());
            }
         }
      }
      return filtered;
   }

   private static boolean isHeaderInList(String headerName, List<String> list) {
      for (String item : list) {
         if (item.toLowerCase().equals(headerName)) {
            return true;
         }
      }
      return false;
   }
}
