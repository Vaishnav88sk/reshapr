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
package io.reshapr.benchmarks.graphql;

import io.reshapr.benchmarks.proxy.NaiveProxyService;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.converters.GraphQLMcpToolConverter;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone diagnostic tool for the GraphQL benchmark setup — <b>not a benchmark</b>.
 *
 * <p><b>Why it exists:</b> {@code GraphQLMcpToolConverter.getCallResponse()} catches all exceptions
 * internally (logs them and returns {@code null}). When {@code GraphQLGetCallResponseBenchmark}
 * fails its setup with {@code "Sanity check failed: unexpected response null"}, JMH does not
 * surface the root cause. This class replays the exact same conversion (same exposition, operation
 * and MCP request) outside JMH, so the underlying stack trace is printed on the console.
 * It is how the graphql-java anti-DoS parsing limit (1 MiB cap on the GitHub schema) was diagnosed.</p>
 *
 * <p><b>When to use it:</b></p>
 * <ul>
 *   <li>the benchmark aborts with {@code "Sanity check failed"} and you need the real error;</li>
 *   <li>you switch to another schema ({@code -p specPath=...}) and want to validate it first;</li>
 *   <li>you add a new converter implementation to {@link ConverterFactory} and want a quick
 *       functional check before a full benchmark run.</li>
 * </ul>
 *
 * <p><b>How to run it</b> (from the {@code benchmarks/} directory, after {@code mvn package}):</p>
 * <pre>{@code
 * # Bundled GitHub schema (src/main/resources/github-api.graphql):
 * java --enable-preview -cp target/benchmarks.jar io.reshapr.benchmarks.graphql.GraphQLSanityCheck
 *
 * # Custom schema from the filesystem:
 * java --enable-preview -cp target/benchmarks.jar io.reshapr.benchmarks.graphql.GraphQLSanityCheck /path/to/schema.graphql
 * }</pre>
 *
 * <p>Expected output on success: {@code Response: Response[content={"status":"ok","id":"42"}, isFault=false]}
 * (the canned {@link NaiveProxyService} payload). A {@code Response: null} means the conversion
 * failed — the stack trace logged just above gives the root cause.</p>
 *
 * @author laurent
 */
public class GraphQLSanityCheck {

   public static void main(String[] args) throws Exception {
      // Raise graphql-java anti-DoS parsing limits (schema is 1.38 MiB, default cap is 1 MiB).
      graphql.parser.ParserOptions.setDefaultParserOptions(
            graphql.parser.ParserOptions.getDefaultSdlParserOptions());

      String schema;
      if (args.length > 0) {
         schema = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
      } else {
         // Load the schema bundled in src/main/resources.
         try (var stream = GraphQLSanityCheck.class.getClassLoader().getResourceAsStream("github-api.graphql")) {
            if (stream == null) {
               throw new IllegalStateException("Resource 'github-api.graphql' not found on classpath");
            }
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
         }
      }
      System.out.printf("Loaded schema: %.1f KiB%n", schema.length() / 1024.0);

      ArtifactEntry artifact = new ArtifactEntry("artifact-gql-1", "github-api.graphql",
            "github-api.graphql", ArtifactEntryType.GRAPHQL_SCHEMA, true, schema);
      OperationEntry operation = new OperationEntry("user", "QUERY", null, null, null);
      ServiceEntry service = new ServiceEntry("svc-gql-1", "reshapr", "GitHub GraphQL", "20250917",
            "GRAPHQL", List.of(operation));
      ConfigurationEntry configuration = new ConfigurationEntry("cfg-gql-1", "github-graphql-default",
            "http://localhost:1/graphql", 1000L, null, null, null, null, null);
      ExpositionEntry exposition = new ExpositionEntry("expo-gql-1", "github-graphql-default", service,
            configuration, artifact, List.of());

      McpToolConverter converter = new GraphQLMcpToolConverter(exposition, new WorkCache(1000),
            new ObjectMapper(), new NaiveProxyService());

      Map<String, List<String>> headers = new HashMap<>();
      headers.put("Accept", List.of("application/json"));

      McpToolConverter.Response response = converter.getCallResponse(operation, configuration,
            new McpSchema.SimpleRequest("user", newArguments()), headers);
      System.out.println("Response: " + response);

      // Equivalence check: every implementation registered in ConverterFactory must send the very
      // same backend request body (the generated GraphQL query) as the reference one.
      String referenceBody = captureBody(ConverterFactory.forName("current"), exposition, configuration, operation);
      for (String implName : ConverterFactory.FACTORIES.keySet()) {
         String body = captureBody(ConverterFactory.forName(implName), exposition, configuration, operation);
         System.out.printf("Implementation '%s': generated body %s (%d chars)%n",
               implName, body.equals(referenceBody) ? "IDENTICAL to reference" : "*** DIFFERS from reference ***",
               body.length());
      }
   }

   /** A NaiveProxyService that captures the last request body sent to the backend. */
   private static class CapturingProxyService extends NaiveProxyService {
      String lastBody;

      @Override
      public io.reshapr.proxy.proxy.BackendResponse callBackend(ConfigurationEntry configuration,
            java.net.URI externalUrl, String method, Map<String, List<String>> headers, String body) {
         this.lastBody = body;
         return super.callBackend(configuration, externalUrl, method, headers, body);
      }
   }

   /** Invoke the converter twice (cold then warm cache) and return the warm request body. */
   private static String captureBody(ConverterFactory factory, ExpositionEntry exposition,
                                     ConfigurationEntry configuration, OperationEntry operation) {
      CapturingProxyService capturingProxy = new CapturingProxyService();
      McpToolConverter converter = factory.create(exposition, new WorkCache(1000), new ObjectMapper(), capturingProxy);
      // First call fills the caches, second call exercises the cached paths — both bodies must match.
      converter.getCallResponse(operation, configuration, new McpSchema.SimpleRequest("user", newArguments()), new HashMap<>());
      String coldBody = capturingProxy.lastBody;
      converter.getCallResponse(operation, configuration, new McpSchema.SimpleRequest("user", newArguments()), new HashMap<>());
      if (!capturingProxy.lastBody.equals(coldBody)) {
         throw new IllegalStateException("Cold and warm bodies differ for " + converter.getClass().getSimpleName());
      }
      return capturingProxy.lastBody;
   }

   /** Build fresh MCP request arguments. */
   private static Map<String, Object> newArguments() {
      Map<String, Object> arguments = new HashMap<>();
      arguments.put("login", "octocat");
      arguments.put("__relation_avatarUrl", new HashMap<>(Map.of("size", 32)));
      arguments.put("__relation_followers", new HashMap<>(Map.of("last", 10)));
      return arguments;
   }
}




