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
package io.reshapr.proxy.mcp;

import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.HeadersUtil;

import jakarta.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A carrier of response-level attributes (header-shaped key/value pairs) surfaced by the tool
 * invocation pipeline, from the backend {@link BackendResponse} up to the
 * MCP HTTP response emitted by {@link McpController}.
 * <p>
 * Only a curated allow-list of headers is propagated. Each header is associated with a
 * {@link MergePolicy} that dictates how multiple values are aggregated when a tool call fans out
 * to several backends (e.g. via a custom-tool script). For example:
 * <ul>
 *   <li>{@code X-Reshapr-Upstream-Service-Time} uses {@link MergePolicy#MAX_LONG} so the worst
 *       backend time governs;</li>
 *   <li>a future cache related header would use {@link MergePolicy#MIN_LONG} so the most
 *       constraining TTL governs.</li>
 * </ul>
 * Instances are thread-safe: mutating methods synchronize on the internal map. This matters
 * because a custom-tool script may accumulate attributes concurrently through
 * {@link io.reshapr.proxy.mcp.script.ReshaprToolsBuiltins} asynchronous calls.
 * @author laurent
 */
public final class ResponseAttributes {

   /** Aggregation strategy used when the same header is contributed by several backends. */
   public enum MergePolicy {
      /** Keep the maximum of the two values, parsed as {@code long} (fallback: string compare). */
      MAX_LONG,
      /** Keep the minimum of the two values, parsed as {@code long} (fallback: string compare). */
      MIN_LONG,
      /** Keep the first non-null value ever set (subsequent updates are ignored). */
      FIRST,
      /** Keep the latest value set (overwrite on every update). */
      LAST
   }

   /**
    * Registered allow-list of headers exposed through this carrier and their merge policy.
    * Keys are compared case-insensitively; the map itself preserves the canonical casing.
    */
   private static final Map<String, MergePolicy> POLICIES;
   static {
      Map<String, MergePolicy> policies = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      policies.put(HeadersUtil.UPSTREAM_SERVICE_TIME, MergePolicy.MAX_LONG);
      POLICIES = Collections.unmodifiableMap(policies);
   }

   /** Case-insensitive map of currently-held header values (keyed by canonical name). */
   private final Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

   /** Build an empty carrier. */
   public static ResponseAttributes empty() {
      return new ResponseAttributes();
   }

   /**
    * Build a carrier from a {@link BackendResponse}. Reads the typed
    * {@code upstreamServiceTimeMs} field (populated by the proxy services) and future backend
    * headers matching the allow-list.
    * @param backendResponse The backend response to inspect (may be {@code null}).
    * @return A new carrier holding the extracted attributes.
    */
   public static ResponseAttributes fromBackend(@Nullable BackendResponse backendResponse) {
      ResponseAttributes attrs = new ResponseAttributes();
      if (backendResponse == null) {
         return attrs;
      }
      if (backendResponse.upstreamServiceTimeMs() >= 0) {
         attrs.set(HeadersUtil.UPSTREAM_SERVICE_TIME, String.valueOf(backendResponse.upstreamServiceTimeMs()));
      }
      // Future header-based attributes (e.g. cache TTL) will be extracted from backendResponse.headers() here.
      return attrs;
   }

   /**
    * Build a carrier from a raw headers map. Kept as a convenience for tests and callers that
    * only hold header maps; production code should prefer
    * {@link #fromBackend(io.reshapr.proxy.proxy.BackendResponse)}.
    * @param backendHeaders The raw response headers (may be {@code null}).
    * @return A new carrier holding the allow-listed headers.
    */
   public static ResponseAttributes fromBackend(@Nullable Map<String, List<String>> backendHeaders) {
      ResponseAttributes attrs = new ResponseAttributes();
      if (backendHeaders == null || backendHeaders.isEmpty()) {
         return attrs;
      }
      for (Map.Entry<String, MergePolicy> policy : POLICIES.entrySet()) {
         String header = policy.getKey();
         List<String> matched = findHeaderValues(backendHeaders, header);
         if (matched != null && !matched.isEmpty()) {
            attrs.set(header, matched.getFirst());
         }
      }
      return attrs;
   }

   /**
    * Set the value of an allow-listed header, applying the header's merge policy against any
    * previously-held value. Unknown headers are silently ignored.
    * @param header The canonical header name.
    * @param value The value to set (must not be {@code null}).
    */
   public void set(String header, String value) {
      if (header == null || value == null) {
         return;
      }
      MergePolicy policy = POLICIES.get(header);
      if (policy == null) {
         return;
      }
      synchronized (values) {
         String current = values.get(header);
         values.put(header, mergeValue(policy, current, value));
      }
   }

   /**
    * Merge another carrier into this one, applying each header's merge policy per key.
    * @param other The carrier to merge into this one (may be {@code null}, in which case nothing happens).
    */
   public void merge(@Nullable ResponseAttributes other) {
      if (other == null) {
         return;
      }
      Map<String, String> snapshot;
      synchronized (other.values) {
         snapshot = new LinkedHashMap<>(other.values);
      }
      snapshot.forEach(this::set);
   }

   /** Whether this carrier holds no attributes. */
   public boolean isEmpty() {
      synchronized (values) {
         return values.isEmpty();
      }
   }

   /**
    * Snapshot the carrier as an HTTP headers map ready to be emitted by the MCP response builder.
    * The returned map is a defensive copy and can be mutated by the caller.
    */
   public Map<String, List<String>> toHttpHeaders() {
      Map<String, List<String>> headers = new LinkedHashMap<>();
      synchronized (values) {
         values.forEach((key, value) -> headers.put(key, List.of(value)));
      }
      return headers;
   }

   /** Case-insensitive header lookup returning all values, or {@code null} if none matches. */
   @Nullable
   private static List<String> findHeaderValues(Map<String, List<String>> headers, String name) {
      List<String> direct = headers.get(name);
      if (direct != null) {
         return direct;
      }
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
         if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
            return entry.getValue();
         }
      }
      return null;
   }

   /** Apply the given policy to combine the previously-held value with the incoming one. */
   private static String mergeValue(MergePolicy policy, @Nullable String current, String incoming) {
      if (current == null) {
         return incoming;
      }
      return switch (policy) {
         case FIRST -> current;
         case LAST -> incoming;
         case MAX_LONG -> pickLong(current, incoming, true);
         case MIN_LONG -> pickLong(current, incoming, false);
      };
   }

   /** Pick the max (or min) of two string-encoded longs; falls back to lexicographic on parse error. */
   private static String pickLong(String a, String b, boolean max) {
      try {
         long la = Long.parseLong(a.strip());
         long lb = Long.parseLong(b.strip());
         return max ? (la >= lb ? a : b) : (la <= lb ? a : b);
      } catch (NumberFormatException e) {
         int cmp = a.compareTo(b);
         return max ? (cmp >= 0 ? a : b) : (cmp <= 0 ? a : b);
      }
   }

   @Override
   public String toString() {
      synchronized (values) {
         return "ResponseAttributes" + values;
      }
   }

   /** Whether the given header name is allow-listed and would be retained by this carrier. */
   public static boolean isTracked(String header) {
      return header != null && POLICIES.containsKey(header.toLowerCase(Locale.ROOT))
            || (header != null && POLICIES.keySet().stream().anyMatch(h -> h.equalsIgnoreCase(header)));
   }
}
