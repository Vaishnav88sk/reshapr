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

import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.OperationEntry;

import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.Map;

/**
 * A naive {@link McpToolConverter} stub that always returns the very same canned response,
 * without any parsing, I/O or payload inspection. This isolates the benchmark on the
 * {@code McpController} request handling logic only (dispatch, validation, header handling,
 * result shaping) — the conversion cost is measured elsewhere (see the {@code openapi} and
 * {@code graphql} benchmarks).
 * @author laurent
 */
public class NaiveMcpToolConverter extends McpToolConverter {

   /** The canned content returned on every call. */
   public static final String CANNED_CONTENT = "{\"status\":\"ok\",\"id\":\"42\"}";

   private static final McpSchema.JsonSchema EMPTY_SCHEMA =
         new McpSchema.JsonSchema("object", Map.of(), List.of(), Boolean.FALSE);

   private static final Response CANNED_RESPONSE = new Response(CANNED_CONTENT, false);

   @Override
   public String getToolDescription(OperationEntry operation) {
      return "Naive benchmark tool";
   }

   @Override
   public McpSchema.JsonSchema getInputSchema(OperationEntry operation) {
      return EMPTY_SCHEMA;
   }

   @Override
   public Response getCallResponse(OperationEntry operation, ConfigurationEntry configuration,
                                   McpSchema.SimpleRequest request, Map<String, List<String>> headers) {
      return CANNED_RESPONSE;
   }

   @Override
   public Uni<Response> getCallResponseUni(OperationEntry operation, McpSchema.SimpleRequest request,
                                           Map<String, List<String>> headers) {
      return Uni.createFrom().item(CANNED_RESPONSE);
   }
}

