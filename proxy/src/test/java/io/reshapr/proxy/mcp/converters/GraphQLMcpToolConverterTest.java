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
package io.reshapr.proxy.mcp.converters;

import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.parser.ParserOptions;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This is a test case for GraphQLMcpToolConverter.
 * @author laurent
 */
class GraphQLMcpToolConverterTest {

   @Test
   void testGraphQLRelationsConversion() throws Exception {
      String specification = FileUtils.readFileToString(
            new File("target/test-classes/io/reshapr/proxy/mcp/github-api.graphql"),
            StandardCharsets.UTF_8);

      ArtifactEntry artifactEntry = new ArtifactEntry("1", "github-api.graphql",
            "GRAPHQL", ArtifactEntryType.GRAPHQL_SCHEMA, true, specification);

      List<OperationEntry> operations = List.of(
            new OperationEntry("user", "QUERY", null, "NonNullType{type=TypeName{name='String'}}", "User")
      );

      ServiceEntry serviceEntry = new ServiceEntry("1", "reshapr", "GitHub GraphQL",
            "20250917", "GRAPHQL", operations);

      ConfigurationEntry configurationEntry = new ConfigurationEntry("1", "GitHub-GraphQL-default",
            null, null, null, null, null, null, null);
      ExpositionEntry exposition = new ExpositionEntry("1", "GitHub-GraphQL-default", serviceEntry,  configurationEntry,
            artifactEntry, List.of());

      // Create ObjectMapper with correct options.
      ParserOptions.setDefaultParserOptions(
            ParserOptions.getDefaultParserOptions().transform(
                  opts -> opts.maxCharacters(100000000).maxTokens(100000)));
      ObjectMapper objectMapper = new ObjectMapper();

      GraphQLMcpToolConverter converter = new GraphQLMcpToolConverter(exposition, new WorkCache(1000),
            objectMapper, new ProxyService(new SecretReferenceResolver(java.util.List.of())));

      for (OperationEntry operation : operations) {
         McpSchema.JsonSchema schema = converter.getInputSchema(operation);
         if ("user".equals(operation.name())) {
            String schemaStr = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
            assertTrue(schemaStr.contains("__relation_anyPinnableItems"));
            assertTrue(schemaStr.contains("__relation_avatarUrl"));
            assertTrue(schemaStr.contains("__relation_followers"));
         }
      }
   }
}
