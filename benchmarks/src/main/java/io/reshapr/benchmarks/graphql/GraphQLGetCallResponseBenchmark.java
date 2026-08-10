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
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark for {@code GraphQLMcpToolConverter.getCallResponse()}.
 *
 * <p>Uses the real-world GitHub GraphQL schema ({@code github-api.graphql}, ~1.4 MiB, bundled in
 * {@code src/main/resources} — copied from {@code dev/github-api.graphql}) and the
 * {@code user} query tool, invoked with the MCP request:</p>
 * <pre>{@code
 * {
 *   "login": "octocat",
 *   "__relation_avatarUrl": { "size": 32 },
 *   "__relation_followers": { "last": 10 }
 * }
 * }</pre>
 *
 * <p>Scenarios:</p>
 * <ul>
 *   <li>{@code coldCache}: empty {@link WorkCache} — the GraphQL document is re-parsed</li>
 *   <li>{@code warmCache}: parsed document served from the work cache, converter reused</li>
 *   <li>{@code warmCacheFreshConverter}: warm cache but a fresh converter per call, as done in
 *       production by {@code ToolCallExecutor.buildMcpToolConverter()}</li>
 * </ul>
 *
 * <p>The {@link ProxyService} is replaced by {@link NaiveProxyService} so no I/O is measured —
 * only the conversion logic (document lookup + GraphQL query building). Run with {@code -prof gc}
 * to also capture per-op allocations.</p>
 *
 * @author laurent
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = { "--enable-preview", "-Xms1g", "-Xmx1g" })
public class GraphQLGetCallResponseBenchmark {

   private static final String ARTIFACT_ID = "artifact-gql-1";

   /** Shared, per-trial state: loaded schema, exposition, stubbed proxy and a pre-warmed converter. */
   @State(Scope.Benchmark)
   public static class BenchState {

      /**
       * Name of the GraphQL schema: resolved on the classpath first (bundled in
       * {@code src/main/resources}), then as a filesystem path (to benchmark another schema
       * without rebuilding: {@code -p specPath=/path/to/schema.graphql}).
       */
      @Param({ "github-api.graphql" })
      public String specPath;

      @Param({ "current", "optimized" })
      public String converterImpl;

      ObjectMapper mapper;
      ProxyService proxyStub;
      ConverterFactory factory;
      ExpositionEntry exposition;
      ConfigurationEntry configuration;
      OperationEntry operation;

      WorkCache warmCache;
      McpToolConverter warmConverter;

      @Setup(Level.Trial)
      public void setupTrial() throws IOException {
         // The GitHub schema (1.38 MiB) exceeds graphql-java anti-DoS parsing limits for operations
         // (1 MiB / 15k tokens). Raise the JVM-wide defaults to the (unlimited) SDL ones, since the
         // converter parses a schema document with the operation parser.
         graphql.parser.ParserOptions.setDefaultParserOptions(
               graphql.parser.ParserOptions.getDefaultSdlParserOptions());

         mapper = new ObjectMapper();
         proxyStub = new NaiveProxyService();
         factory = ConverterFactory.forName(converterImpl);

         // Load the GraphQL schema (try the configured path, then from the project root).
         String schema = loadSchema(specPath);
         System.out.printf("%n>>> Loaded GraphQL schema '%s', size %.1f KiB%n",
               specPath, schema.getBytes(StandardCharsets.UTF_8).length / 1024.0);

         ArtifactEntry artifact = new ArtifactEntry(ARTIFACT_ID, "github-api.graphql",
               "github-api.graphql", ArtifactEntryType.GRAPHQL_SCHEMA, true, schema);

         // The benchmarked tool is the 'user' query.
         operation = new OperationEntry("user", "QUERY", null, null, null);

         ServiceEntry service = new ServiceEntry("svc-gql-1", "reshapr", "GitHub GraphQL", "20250917",
               "GRAPHQL", List.of(operation));
         configuration = new ConfigurationEntry("cfg-gql-1", "github-graphql-default", "http://localhost:1/graphql",
               1000L, null, null, null, null, null);
         exposition = new ExpositionEntry("expo-gql-1", "github-graphql-default", service, configuration,
               artifact, List.of());

         // Build a converter with a pre-warmed cache for the 'warm' benchmarks.
         warmCache = new WorkCache(1000);
         warmConverter = factory.create(exposition, warmCache, mapper, proxyStub);
         McpToolConverter.Response response = warmConverter.getCallResponse(operation, configuration,
               newRequest(), newHeaders());
         if (response == null || response.isFault()) {
            throw new IllegalStateException("Sanity check failed: unexpected response " + response);
         }
      }

      private static String loadSchema(String configuredPath) throws IOException {
         // #1 Try the classpath (schema bundled in the benchmark jar from src/main/resources).
         try (var stream = GraphQLGetCallResponseBenchmark.class.getClassLoader().getResourceAsStream(configuredPath)) {
            if (stream != null) {
               return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
         }
         // #2 Fallback: resolve as a filesystem path (custom schema without rebuilding).
         Path path = Path.of(configuredPath);
         if (Files.exists(path)) {
            return Files.readString(path, StandardCharsets.UTF_8);
         }
         throw new IOException("GraphQL schema '" + configuredPath + "' found neither on classpath nor on filesystem");
      }
   }

   /** Per-invocation cold state: work cache and converter are reset before every single call. */
   @State(Scope.Thread)
   public static class ColdState {

      BenchState bench;
      WorkCache coldCache;
      McpToolConverter coldConverter;

      @Setup(Level.Trial)
      public void setupTrial(BenchState bench) {
         this.bench = bench;
         coldCache = new WorkCache(1000);
      }

      @Setup(Level.Invocation)
      public void setupInvocation() {
         // Evict all cached entries for the artifact so every call starts from a cold cache.
         coldCache.invalidateMajor(ARTIFACT_ID);
         coldConverter = bench.factory.create(bench.exposition, coldCache, bench.mapper, bench.proxyStub);
      }
   }

   /** First call scenario: the work cache is empty, the GraphQL document must be re-parsed. */
   @Benchmark
   public McpToolConverter.Response coldCache(BenchState bench, ColdState cold) {
      return cold.coldConverter.getCallResponse(bench.operation, bench.configuration, newRequest(), newHeaders());
   }

   /** Steady-state scenario: the parsed document is served from the work cache. */
   @Benchmark
   public McpToolConverter.Response warmCache(BenchState bench) {
      return bench.warmConverter.getCallResponse(bench.operation, bench.configuration, newRequest(), newHeaders());
   }

   /**
    * Production-like steady-state: warm {@link WorkCache} but a fresh converter per call, as done
    * by {@code ToolCallExecutor.buildMcpToolConverter()}.
    */
   @Benchmark
   public McpToolConverter.Response warmCacheFreshConverter(BenchState bench) {
      McpToolConverter converter = bench.factory.create(bench.exposition, bench.warmCache, bench.mapper, bench.proxyStub);
      return converter.getCallResponse(bench.operation, bench.configuration, newRequest(), newHeaders());
   }

   /** Build a fresh request with fresh nested maps: converters must never observe shared state. */
   private static McpSchema.SimpleRequest newRequest() {
      Map<String, Object> arguments = new HashMap<>();
      arguments.put("login", "octocat");

      Map<String, Object> avatarUrlArgs = new HashMap<>();
      avatarUrlArgs.put("size", 32);
      arguments.put("__relation_avatarUrl", avatarUrlArgs);

      Map<String, Object> followersArgs = new HashMap<>();
      followersArgs.put("last", 10);
      arguments.put("__relation_followers", followersArgs);

      return new McpSchema.SimpleRequest("user", arguments);
   }

   /** Build fresh mutable protocol headers. */
   private static Map<String, List<String>> newHeaders() {
      Map<String, List<String>> headers = new HashMap<>();
      headers.put("Accept", List.of("application/json"));
      return headers;
   }
}



