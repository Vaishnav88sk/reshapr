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

import io.reshapr.proxy.registry.ConfigurationEntry.HeaderPolicyEntry;
import io.reshapr.proxy.registry.ConfigurationEntry.HeaderRenameEntry;
import io.reshapr.proxy.registry.ConfigurationEntry.HeaderRulesEntry;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies the header propagation policy of a configuration plan to the headers flowing from the
 * MCP client to the backend. The engine enforces two tiers:
 * <ol>
 *   <li>a non-overridable <b>baseline</b> of hop-by-hop and internal headers that is always
 *       stripped (see {@link #BASELINE_STRIPPED});</li>
 *   <li>an overridable <b>default deny-list</b> of sensitive headers ({@link #DEFAULT_DENY}) that
 *       protects the client's own credentials from leaking to the backend unless the operator
 *       explicitly allows them.</li>
 * </ol>
 * On top of filtering it applies operator-defined <b>rename</b> directives, which express explicit
 * intent and may therefore (re)introduce a header that would otherwise be denied (e.g. rename an
 * incoming {@code X-Authorization} to {@code Authorization}).
 * <p>
 * The engine only renames/filters the client-supplied headers. Gateway-managed headers
 * (forwarding, tracing) and the backend secret credentials are injected by {@link ProxyService}
 * <em>after</em> this stage, so a denied {@code Authorization} never removes the credential the
 * gateway itself injects.
 * @author laurent
 */
public final class HeaderPolicyEngine {

   /**
    * Hop-by-hop (RFC 7230 §6.1), Reshapr-internal and MCP transport headers that must never reach
    * the backend. Always stripped regardless of the configured policy. The MCP headers
    * ({@code MCP-Session-Id}, {@code MCP-Protocol-Version}, {@code Mcp-Method}, {@code Mcp-Name})
    * belong to the client↔gateway protocol and carry no meaning for a backend. Lower-cased for
    * case-insensitive matching.
    */
   static final Set<String> BASELINE_STRIPPED = Set.of(
         "host",
         "connection",
         "keep-alive",
         "proxy-authenticate",
         "proxy-authorization",
         "te",
         "trailer",
         "transfer-encoding",
         "upgrade",
         "content-length",
         "x-reshapr-key",
         "mcp-session-id",
         "mcp-protocol-version",
         "mcp-method",
         "mcp-name");

   /**
    * Sensitive headers denied by default (overridable through an explicit allow-list). These carry
    * the MCP client's own credentials/session, which are meant for the gateway, not the backend.
    * Lower-cased for case-insensitive matching.
    */
   static final Set<String> DEFAULT_DENY = Set.of(
         "authorization",
         "cookie");

   private HeaderPolicyEngine() {
      // Utility class.
   }

   /**
    * Apply the request-direction header policy to the client-supplied headers.
    * @param incoming The headers received from the MCP client (case-preserving keys).
    * @param policy   The plan's header policy (may be {@code null} ⇒ defaults only).
    * @return A new mutable map holding the headers to forward to the backend.
    */
   public static Map<String, List<String>> applyRequestPolicy(Map<String, List<String>> incoming,
                                                              HeaderPolicyEntry policy) {
      HeaderRulesEntry rules = policy != null ? policy.request() : null;

      Set<String> allow = lowerCaseSet(rules != null ? rules.allow() : null);
      Set<String> userDeny = lowerCaseSet(rules != null ? rules.deny() : null);

      // effectiveDeny = DEFAULT_DENY ∪ userDeny − allow (explicit allow wins over the default deny).
      // When the plan defines no allow/deny rules (the common case) the result is exactly the
      // immutable DEFAULT_DENY, so avoid allocating (and populating) a fresh HashSet per request.
      Set<String> effectiveDeny;
      if (userDeny.isEmpty() && allow.isEmpty()) {
         effectiveDeny = DEFAULT_DENY;
      } else {
         effectiveDeny = new HashSet<>(DEFAULT_DENY);
         effectiveDeny.addAll(userDeny);
         effectiveDeny.removeAll(allow);
      }
      boolean hasAllow = !allow.isEmpty();

      // 1. Filter the client-supplied headers. Pre-size the result to the incoming size so no
      //    rehashing happens (the filtered map can never grow larger than the input).
      Map<String, List<String>> result = new LinkedHashMap<>(capacityFor(incoming.size()));
      for (Map.Entry<String, List<String>> entry : incoming.entrySet()) {
         String key = entry.getKey();
         if (key == null) {
            continue;
         }
         // The lower-cased form is needed for every case-insensitive check below; compute it once.
         String lower = key.toLowerCase(Locale.ROOT);
         if (BASELINE_STRIPPED.contains(lower)
               || (hasAllow && !allow.contains(lower))
               || effectiveDeny.contains(lower)) {
            continue;
         }
         result.put(key, entry.getValue());
      }

      // 2. Apply rename directives (explicit intent, may re-introduce denied targets).
      List<HeaderRenameEntry> renames = rules != null ? rules.rename() : null;
      if (renames != null && !renames.isEmpty()) {
         for (HeaderRenameEntry rename : renames) {
            applyRename(result, rename);
         }
      }

      return result;
   }

   /** Initial {@link java.util.HashMap} capacity that holds {@code size} entries without resizing. */
   private static int capacityFor(int size) {
      return (int) (size / 0.75f) + 1;
   }

   /** Apply a single rename directive to the (already filtered) header map. */
   private static void applyRename(Map<String, List<String>> headers, HeaderRenameEntry rename) {
      if (rename == null || rename.from() == null || rename.from().isBlank()
            || rename.to() == null || rename.to().isBlank()) {
         return;
      }
      List<String> values = removeValues(headers, rename.from());
      if (values != null) {
         removeValues(headers, rename.to());
         headers.put(rename.to(), values);
      }
   }

   /** Case-insensitive removal returning the removed values, or {@code null} when absent. */
   private static List<String> removeValues(Map<String, List<String>> headers, String name) {
      if (name == null) {
         return null;
      }
      String matchedKey = null;
      for (String key : headers.keySet()) {
         if (key != null && key.equalsIgnoreCase(name)) {
            matchedKey = key;
            break;
         }
      }
      return matchedKey != null ? headers.remove(matchedKey) : null;
   }

   /** Build a lower-cased set from a nullable list of header names (blank entries ignored). */
   private static Set<String> lowerCaseSet(List<String> names) {
      if (names == null || names.isEmpty()) {
         return Set.of();
      }
      Set<String> set = new HashSet<>();
      for (String name : names) {
         if (name != null && !name.isBlank()) {
            set.add(name.toLowerCase(Locale.ROOT));
         }
      }
      return set;
   }
}
