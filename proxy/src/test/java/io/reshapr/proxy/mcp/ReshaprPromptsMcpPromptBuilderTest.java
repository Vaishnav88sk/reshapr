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

import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 7ter: prompts must be aggregated across ALL attached Prompts artifacts of the same type, not just the
 * first one resolved.
 * @author laurent
 */
class ReshaprPromptsMcpPromptBuilderTest {

   private static final String FIRST_ARTIFACT = """
         apiVersion: reshapr.io/v1alpha1
         kind: Prompts
         prompts:
           greeting:
             description: Say hi
             result: "Hello ${name}"
         """;

   private static final String SECOND_ARTIFACT = """
         apiVersion: reshapr.io/v1alpha1
         kind: Prompts
         prompts:
           farewell:
             description: Say bye
             arguments:
               - name: name
             result: "Bye ${name}"
         """;

   @Test
   void promptsAreAggregatedAcrossMultipleArtifacts() {
      ServiceEntry service = new ServiceEntry("svc-1", "org-1", "Test API", "1.0.0", "REST", null);
      ArtifactEntry a1 = new ArtifactEntry("art-1", "prompts-1.yaml", null,
            ArtifactEntryType.RESHAPR_PROMPTS, false, FIRST_ARTIFACT);
      ArtifactEntry a2 = new ArtifactEntry("art-2", "prompts-2.yaml", null,
            ArtifactEntryType.RESHAPR_PROMPTS, false, SECOND_ARTIFACT);

      ReshaprPromptsMcpPromptBuilder builder = new ReshaprPromptsMcpPromptBuilder(
            service, List.of(a1, a2), new WorkCache(100), new ObjectMapper());

      List<McpSchema.Prompt> prompts = builder.listPrompts();

      // Both prompts (one per artifact) must be listed.
      Set<String> names = prompts.stream().map(McpSchema.Prompt::name).collect(Collectors.toSet());
      assertEquals(Set.of("greeting", "farewell"), names);

      // And each prompt is retrievable, including the one declared in the second artifact.
      McpSchema.PromptMessage farewell = builder.getPrompt(
            new McpSchema.SimpleRequest("farewell", java.util.Map.of("name", "Bob")));
      assertTrue(((McpSchema.TextContent) farewell.content()).text().contains("Bye Bob"));
   }
}


