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

import io.reshapr.json.ObjectMapperFactory;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * An implementation of McpPromptBuilder that builds prompts from Reshapr Prompts artifacts.
 * @author laurent
 */
class ReshaprPromptsMcpPromptBuilder implements McpPromptBuilder {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final String CACHE_KEYS_PREFIX = "rpmcppb-";

   private static final ObjectMapper YAML_MAPPER = ObjectMapperFactory.getYamlObjectMapper();
   private static final String ARGUMENT_START_MARKER = "${";

   private final ServiceEntry service;
   private final List<ArtifactEntry> attachedArtifacts;
   private final WorkCache workCache;
   private final ObjectMapper mapper;

   public ReshaprPromptsMcpPromptBuilder(ServiceEntry service, List<ArtifactEntry> attachedArtifacts,
                                         WorkCache workCache, ObjectMapper mapper) {
      this.service = service;
      this.attachedArtifacts = attachedArtifacts;
      this.workCache = workCache;
      this.mapper = mapper;
   }

   @Override
   public List<McpSchema.Prompt> listPrompts() {
      // First, check cache to see if we have already loaded prompts.
      JsonNode promptsNode = getPromptsNode();
      if (promptsNode == null) {
         logger.debugf("No Prompts artifact found for service '%s'", service.id());
         return Collections.emptyList();
      }

      // Convert to list of Prompt objects.
      List<McpSchema.Prompt> prompts = new ArrayList<>();
      Iterator<String> namesIterator = promptsNode.fieldNames();
      while (namesIterator.hasNext()) {
         String name = namesIterator.next();

         JsonNode promptNode = promptsNode.get(name);
         String title = promptNode.has("title") ? promptNode.get("title").asText() : null;
         String description = promptNode.has("description") ? promptNode.get("description").asText() : null;
         List<McpSchema.PromptArgument> arguments = promptNode.has("arguments") ?
               mapper.convertValue(promptNode.get("arguments"),
                     new TypeReference<List<McpSchema.PromptArgument>>() {}) : Collections.emptyList();

         prompts.add(new McpSchema.Prompt(name, title, description, arguments));
      }
      return prompts;
   }

   @Override
   public McpSchema.PromptMessage getPrompt(McpSchema.SimpleRequest request) {
      // First, check cache to see if we have already loaded prompts.
      JsonNode promptsNode = getPromptsNode();
      if (promptsNode == null) {
         logger.debugf("No Prompts artifact found for service '%s'", service.id());
         return new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT,
               new McpSchema.TextContent("No prompt messages found."));
      }

      // Find the requested prompt.
      JsonNode promptNode = promptsNode.get(request.name());
      if (promptNode == null) {
         logger.debugf("No prompt named '%s' found for service '%s'", request.name(), service.id());
         return new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT,
               new McpSchema.TextContent("No prompt named '" + request.name() + "' found."));
      }

      // Build the prompt message content.
      String result = promptNode.path("result").asText();
      if (result.contains(ARGUMENT_START_MARKER) && promptNode.has("arguments")) {
         for (Map.Entry<String, Object> argument : request.arguments().entrySet()) {
            String name = argument.getKey();
            String value = argument.getValue().toString();

            if (value != null) {
               result = result.replace(ARGUMENT_START_MARKER + name + "}", value);
            }
         }
      }
      return new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(result));
   }

   /**
    * Get the aggregated root JsonNode of prompt definitions across <b>all</b> attached Prompts artifacts.
    * Each artifact is parsed and cached individually (keyed by its id); the resulting {@code prompts} nodes
    * are merged by prompt name (first declaring artifact wins on collision, deterministic by attachment
    * order). Returns null when no attached Prompts artifact declares any prompt.
    */
   private @Nullable JsonNode getPromptsNode() {
      if (attachedArtifacts == null || attachedArtifacts.isEmpty()) {
         return null;
      }
      ObjectNode merged = YAML_MAPPER.createObjectNode();
      for (ArtifactEntry artifact : attachedArtifacts) {
         if (!ArtifactEntryType.RESHAPR_PROMPTS.equals(artifact.type())) {
            continue;
         }
         JsonNode promptsNode = getPromptsNodeForArtifact(artifact);
         if (promptsNode != null && promptsNode.isObject()) {
            Iterator<String> names = promptsNode.fieldNames();
            while (names.hasNext()) {
               String name = names.next();
               if (!merged.has(name)) {
                  merged.set(name, promptsNode.get(name));
               }
            }
         }
      }
      return merged.isEmpty() ? null : merged;
   }

   /** Parse and cache (keyed by artifact id) the {@code prompts} sub-node of a single Prompts artifact. */
   private @Nullable JsonNode getPromptsNodeForArtifact(ArtifactEntry artifact) {
      if (workCache.get(artifact.id(), CACHE_KEYS_PREFIX) instanceof JsonNode cached) {
         logger.tracef("Got a cached value of Prompts JsonNode for artifact '%s'", artifact.id());
         return cached;
      }
      try {
         JsonNode artifactNode = YAML_MAPPER.readTree(artifact.content());
         JsonNode promptsNode = artifactNode.get("prompts");
         if (promptsNode != null) {
            workCache.set(artifact.id(), CACHE_KEYS_PREFIX, promptsNode);
         }
         return promptsNode;
      } catch (Exception e) {
         logger.errorf(e, "Cannot read Reshapr Prompts artifact '%s' for service '%s'", artifact.id(), service.id());
         return null;
      }
   }
}
