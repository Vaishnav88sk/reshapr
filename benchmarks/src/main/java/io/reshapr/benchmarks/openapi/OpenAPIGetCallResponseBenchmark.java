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
package io.reshapr.benchmarks.openapi;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark for {@code OpenAPIMcpToolConverter.getCallResponse()}.
 *
 * <p>Axes:</p>
 * <ul>
 *   <li>{@code operationCount}: 6 (few), 30 (medium), 80 (many) operations in the spec</li>
 *   <li>{@code specSize}: SMALL / MEDIUM / LARGE schema complexity (drives document byte size)</li>
 *   <li>{@code pathDepth}: SHALLOW (1 path param) / DEEP (3 path params, 7 segments)</li>
 *   <li>{@code refStyle}: INLINE (local refs) / EXTERNAL (parameters + schemas in attached YAML file)</li>
 *   <li>cold vs warm {@link WorkCache} (dedicated benchmark methods)</li>
 *   <li>{@code converterImpl}: implementation under test (see {@link ConverterFactory})</li>
 * </ul>
 *
 * <p>The {@link ProxyService} is replaced by {@link NaiveProxyService} so no I/O is measured —
 * only the conversion logic. Run with {@code -prof gc} to also capture per-op allocations.</p>
 *
 * @author laurent
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = { "--enable-preview", "-Xms1g", "-Xmx1g" })
public class OpenAPIGetCallResponseBenchmark {

   private static final String ARTIFACT_ID = "artifact-1";
   private static final String EXTERNAL_ARTIFACT_ID = "artifact-ext-1";

   /** Shared, per-trial state: generated spec, exposition, stubbed proxy and a pre-warmed converter. */
   @State(Scope.Benchmark)
   public static class BenchState {

      @Param({ "6", "30", "80" })
      public int operationCount;

      @Param({ "SMALL", "MEDIUM", "LARGE" })
      public String specSize;

      @Param({ "SHALLOW", "DEEP" })
      public String pathDepth;

      @Param({ "INLINE", "EXTERNAL" })
      public String refStyle;

      @Param({ "current", "optimized" })
      public String converterImpl;

      ObjectMapper mapper;
      ProxyService proxyStub;
      ConverterFactory factory;
      ExpositionEntry exposition;
      ConfigurationEntry configuration;
      OperationEntry operation;
      Map<String, Object> argumentsTemplate;

      WorkCache warmCache;
      McpToolConverter warmConverter;

      int specBytes;

      @Setup(Level.Trial)
      public void setupTrial() {
         mapper = new ObjectMapper();
         proxyStub = new NaiveProxyService();
         factory = ConverterFactory.forName(converterImpl);

         // Generate the synthetic OpenAPI specification.
         OpenAPISpecGenerator.GeneratedSpec generated = new OpenAPISpecGenerator().generate(operationCount,
               OpenAPISpecGenerator.Complexity.valueOf(specSize),
               OpenAPISpecGenerator.PathDepth.valueOf(pathDepth),
               OpenAPISpecGenerator.RefStyle.valueOf(refStyle));
         specBytes = generated.content().getBytes(StandardCharsets.UTF_8).length;
         int externalBytes = generated.hasExternalArtifact()
               ? generated.externalArtifactContent().getBytes(StandardCharsets.UTF_8).length : 0;
         System.out.printf("%n>>> Generated spec: %d operations, complexity %s, %s, %s, size %.1f KiB (+ external %.1f KiB)%n",
               operationCount, specSize, pathDepth, refStyle, specBytes / 1024.0, externalBytes / 1024.0);

         ArtifactEntry artifact = new ArtifactEntry(ARTIFACT_ID, "synthetic-openapi.json",
               "synthetic-openapi.json", ArtifactEntryType.OPEN_API_SPEC, true, generated.content());
         List<ArtifactEntry> attachedArtifacts = generated.hasExternalArtifact()
               ? List.of(new ArtifactEntry(EXTERNAL_ARTIFACT_ID, generated.externalArtifactName(),
                     generated.externalArtifactName(), ArtifactEntryType.OPEN_API_SPEC, false,
                     generated.externalArtifactContent()))
               : List.of();

         operation = new OperationEntry(generated.benchmarkedOperation(), "GET", null, null, null);
         List<OperationEntry> operations = new ArrayList<>();
         operations.add(operation);

         ServiceEntry service = new ServiceEntry("svc-1", "reshapr", "Synthetic API", "1.0.0", "REST", operations);
         configuration = new ConfigurationEntry("cfg-1", "synthetic-default", "http://localhost:1/api",
               1000L, null, null, null, null, null);
         exposition = new ExpositionEntry("expo-1", "synthetic-default", service, configuration, artifact,
               attachedArtifacts);

         // Build the arguments template matching the benchmarked operation parameters.
         argumentsTemplate = new HashMap<>();
         for (String pathParam : generated.pathParamNames()) {
            argumentsTemplate.put(pathParam, "42");
         }
         argumentsTemplate.put("limit", 25);
         argumentsTemplate.put("verbose", true);
         argumentsTemplate.put("tags", List.of("alpha", "gamma"));
         argumentsTemplate.put("x-trace-id", "trace-123");

         // Build a converter with a pre-warmed cache for the 'warm' benchmarks.
         warmCache = new WorkCache(1000);
         warmConverter = factory.create(exposition, warmCache, mapper, proxyStub);
         McpToolConverter.Response response = warmConverter.getCallResponse(operation, configuration,
               newRequest(this), newHeaders());
         if (response == null || response.isFault()) {
            throw new IllegalStateException("Sanity check failed: unexpected response " + response);
         }
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
         // Evict all cached entries for the artifacts so every call starts from a cold cache.
         coldCache.invalidateMajor(ARTIFACT_ID);
         coldCache.invalidateMajor(EXTERNAL_ARTIFACT_ID);
         // Recreate the converter: it holds a per-instance cache of parsed external artifacts
         // (attachedArtifactsContent) that must also be cold.
         coldConverter = bench.factory.create(bench.exposition, coldCache, bench.mapper, bench.proxyStub);
      }
   }

   /** First call scenario: the work cache is empty, the spec (and external refs) must be re-parsed. */
   @Benchmark
   public McpToolConverter.Response coldCache(BenchState bench, ColdState cold) {
      return cold.coldConverter.getCallResponse(bench.operation, bench.configuration, newRequest(bench), newHeaders());
   }

   /** Steady-state scenario: the parsed spec is served from the work cache. */
   @Benchmark
   public McpToolConverter.Response warmCache(BenchState bench) {
      return bench.warmConverter.getCallResponse(bench.operation, bench.configuration, newRequest(bench), newHeaders());
   }

   /**
    * Production-like steady-state: warm {@link WorkCache} but a fresh converter per call, as done by
    * {@code ToolCallExecutor.buildMcpToolConverter()}. The per-instance cache of parsed external
    * artifacts is thus always cold: with {@code refStyle=EXTERNAL} the external file is re-parsed
    * on every call.
    */
   @Benchmark
   public McpToolConverter.Response warmCacheFreshConverter(BenchState bench) {
      McpToolConverter converter = bench.factory.create(bench.exposition, bench.warmCache, bench.mapper, bench.proxyStub);
      return converter.getCallResponse(bench.operation, bench.configuration, newRequest(bench), newHeaders());
   }

   /** Build a fresh request: getCallResponse() mutates the arguments map, so it cannot be shared. */
   private static McpSchema.SimpleRequest newRequest(BenchState bench) {
      return new McpSchema.SimpleRequest("benchmarked_get", new HashMap<>(bench.argumentsTemplate));
   }

   /** Build fresh mutable protocol headers: getCallResponse() may add header parameters to them. */
   private static Map<String, List<String>> newHeaders() {
      Map<String, List<String>> headers = new HashMap<>();
      headers.put("Accept", List.of("application/json"));
      return headers;
   }
}









