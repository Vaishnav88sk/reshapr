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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the two elicitation delivery formats produced for a {@code tools/call} that requires backend
 * secrets: the legacy {@code URL_ELICITATION_REQUIRED} error and the stateless
 * {@code InputRequiredResult} ("URL Mode" {@code elicitation/create}) payload.
 * @author laurent
 */
class McpSchemaElicitationTest {

   private static final ObjectMapper MAPPER = new ObjectMapper();

   private static McpSchema.URLElicitation elicitation(String id) {
      return new McpSchema.URLElicitation(id, "https://gw/elicitation/connect?elicitationId=" + id,
            "Please provide backend secret information by visiting the above URL.");
   }

   @Test
   void testLegacyUrlElicitationRequiredErrorFormat() throws Exception {
      McpSchema.JSONRPCResponse.JSONRPCError error =
            McpSchema.buildURLElicitationRequiredError(List.of(elicitation("e1")));

      JsonNode json = MAPPER.valueToTree(error);
      assertEquals(McpSchema.ErrorCodes.URL_ELICITATION_REQUIRED, json.get("code").asInt());
      assertTrue(json.get("data").has("elicitations"));
      JsonNode first = json.get("data").get("elicitations").get(0);
      assertEquals("url", first.get("mode").asText());
      assertEquals("e1", first.get("elicitationId").asText());
   }

   @Test
   void testStatelessInputRequiredResultFormat() throws Exception {
      McpSchema.InputRequiredResult result =
            McpSchema.buildInputRequiredResult(List.of(elicitation("e1"), elicitation("e2")), "rs-42");

      JsonNode json = MAPPER.valueToTree(result);

      // inputRequests is a map keyed by elicitation id, not an array.
      JsonNode inputRequests = json.get("inputRequests");
      assertTrue(inputRequests.isObject());
      assertEquals(2, inputRequests.size());

      // No JSON-RPC error is used in stateless mode.
      assertFalse(json.has("code"));

      // The mandatory resultType discriminator must be present with the input_required value.
      assertEquals(McpSchema.RESULT_TYPE_INPUT_REQUIRED, json.get("resultType").asText());

      // The opaque resume token must be present for stateless URL Mode OAuth.
      assertEquals("rs-42", json.get("requestState").asText());

      JsonNode first = inputRequests.get("e1");
      // Each entry holds only method + params (no jsonrpc, no id).
      assertFalse(first.has("jsonrpc"));
      assertFalse(first.has("id"));
      assertEquals(McpSchema.METHOD_ELICITATION_CREATE, first.get("method").asText());
      assertEquals("url", first.get("params").get("mode").asText());
      assertEquals("e1", first.get("params").get("elicitationId").asText());

      assertEquals("e2", inputRequests.get("e2").get("params").get("elicitationId").asText());
   }
}

