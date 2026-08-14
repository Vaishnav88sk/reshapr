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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic generator for the synthetic request/response payloads and header sets used by
 * {@code ProxyServiceCallBackendBenchmark}. Everything is generated once at trial setup so that
 * the benchmark loop only measures the proxying work.
 *
 * @author laurent
 */
public final class ProxyBenchPayloads {

   /** Payload size axis: roughly 100 B / 10 KiB / 500 KiB of JSON. */
   public enum PayloadSize {
      SMALL(1),
      MEDIUM(100),
      LARGE(5_000);

      private final int items;

      PayloadSize(int items) {
         this.items = items;
      }

      /** Build a deterministic JSON document of the target magnitude. */
      public String buildJson() {
         return ProxyBenchPayloads.buildJson(items);
      }
   }

   /**
    * Build a deterministic JSON document with the given number of items (~130 B of JSON per item).
    * Also used by the end-to-end benchmark environment where item counts are configurable.
    */
   public static String buildJson(int items) {
      StringBuilder sb = new StringBuilder(items * 140 + 64);
      sb.append("{\"kind\":\"bench\",\"items\":[");
      for (int i = 0; i < items; i++) {
         if (i > 0) {
            sb.append(',');
         }
         sb.append("{\"id\":").append(i)
               .append(",\"name\":\"item-").append(i)
               .append("\",\"description\":\"A synthetic benchmark item with some realistic padding text\"")
               .append(",\"active\":").append(i % 2 == 0)
               .append(",\"score\":").append(i * 3.14)
               .append('}');
      }
      sb.append("]}");
      return sb.toString();
   }

   /** Header count axis: 3 / 12 / 40 incoming headers. */
   public enum HeaderCount {
      SMALL(3),
      MEDIUM(12),
      LARGE(40);

      private final int count;

      HeaderCount(int count) {
         this.count = count;
      }

      /**
       * Build the incoming header template. It always contains a few realistic headers (including
       * restricted ones that the proxy must filter out) then is padded with synthetic ones.
       */
      public Map<String, List<String>> buildHeaders() {
         Map<String, List<String>> headers = new HashMap<>();
         headers.put("Accept", List.of("application/json"));
         headers.put("Host", List.of("mcp.reshapr.io"));
         headers.put("x-reshapr-key", List.of("rspr_dummy_gateway_key"));
         for (int i = headers.size(); i < count; i++) {
            headers.put("X-Bench-Header-" + i, List.of("value-" + i + "-abcdefghijklmnopqrstuvwxyz"));
         }
         return headers;
      }
   }

   private ProxyBenchPayloads() {
      // Utility class.
   }
}

