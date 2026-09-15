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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test case for {@link HeaderPolicyEngine}.
 * @author laurent
 */
class HeaderPolicyEngineTest {

   @Test
   void testStripsBaselineAndDefaultDeniedHeadersWithNullPolicy() {
      Map<String, List<String>> in = headers(
            "Host", "example.com",
            "Connection", "keep-alive",
            "X-Reshapr-Key", "internal",
            "Authorization", "Bearer client-token",
            "Cookie", "sid=abc",
            "X-Custom", "value");

      Map<String, List<String>> out = HeaderPolicyEngine.applyRequestPolicy(in, null);

      assertFalse(has(out, "Host"));
      assertFalse(has(out, "Connection"));
      assertFalse(has(out, "X-Reshapr-Key"));
      assertFalse(has(out, "Authorization"), "Authorization must be denied by default");
      assertFalse(has(out, "Cookie"), "Cookie must be denied by default");
      assertTrue(has(out, "X-Custom"));
      assertEquals("value", value(out, "X-Custom"));
   }

   @Test
   void testStripsMcpTransportHeaders() {
      Map<String, List<String>> in = headers(
            "MCP-Session-Id", "sess-123",
            "MCP-Protocol-Version", "2026-07-28",
            "Mcp-Method", "tools/call",
            "Mcp-Name", "myTool",
            "X-Custom", "value");

      Map<String, List<String>> out = HeaderPolicyEngine.applyRequestPolicy(in, null);

      assertFalse(has(out, "MCP-Session-Id"));
      assertFalse(has(out, "MCP-Protocol-Version"));
      assertFalse(has(out, "Mcp-Method"));
      assertFalse(has(out, "Mcp-Name"));
      assertTrue(has(out, "X-Custom"));
   }

   @Test
   void testExplicitAllowReenablesDefaultDeniedHeader() {
      Map<String, List<String>> in = headers("Authorization", "Bearer client-token", "X-Other", "v");
      HeaderPolicyEntry policy = new HeaderPolicyEntry(
            new HeaderRulesEntry(List.of("Authorization"), null, null), null);

      Map<String, List<String>> out = HeaderPolicyEngine.applyRequestPolicy(in, policy);

      assertTrue(has(out, "Authorization"));
      assertEquals("Bearer client-token", value(out, "Authorization"));
      // Allow-list is authoritative: non-listed headers are dropped.
      assertFalse(has(out, "X-Other"));
   }

   @Test
   void testUserDenyRemovesHeader() {
      Map<String, List<String>> in = headers("X-Trace", "abc", "X-Keep", "v");
      HeaderPolicyEntry policy = new HeaderPolicyEntry(
            new HeaderRulesEntry(null, List.of("X-Trace"), null), null);

      Map<String, List<String>> out = HeaderPolicyEngine.applyRequestPolicy(in, policy);

      assertFalse(has(out, "X-Trace"));
      assertTrue(has(out, "X-Keep"));
   }

   @Test
   void testRenameMovesValueAndBypassesDefaultDeny() {
      Map<String, List<String>> in = headers("X-Authorization", "Bearer upstream");
      HeaderPolicyEntry policy = new HeaderPolicyEntry(
            new HeaderRulesEntry(null, null,
                  List.of(new HeaderRenameEntry("X-Authorization", "Authorization"))),
            null);

      Map<String, List<String>> out = HeaderPolicyEngine.applyRequestPolicy(in, policy);

      assertTrue(has(out, "Authorization"), "rename target bypasses the default deny");
      assertEquals("Bearer upstream", value(out, "Authorization"));
      assertFalse(has(out, "X-Authorization"), "RENAME removes the source header");
   }

   @Test
   void testRenameWithMissingSourceIsNoOp() {
      Map<String, List<String>> in = headers("X-Custom", "v");
      HeaderPolicyEntry policy = new HeaderPolicyEntry(
            new HeaderRulesEntry(null, null,
                  List.of(new HeaderRenameEntry("X-Absent", "X-Target"))),
            null);

      Map<String, List<String>> out = HeaderPolicyEngine.applyRequestPolicy(in, policy);

      assertNull(value(out, "X-Target"));
   }

   private static Map<String, List<String>> headers(String... kv) {
      Map<String, List<String>> map = new LinkedHashMap<>();
      for (int i = 0; i < kv.length; i += 2) {
         map.put(kv[i], List.of(kv[i + 1]));
      }
      return map;
   }

   private static boolean has(Map<String, List<String>> headers, String name) {
      return headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name));
   }

   private static String value(Map<String, List<String>> headers, String name) {
      return headers.entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(name))
            .map(e -> e.getValue().getFirst())
            .findFirst().orElse(null);
   }
}
