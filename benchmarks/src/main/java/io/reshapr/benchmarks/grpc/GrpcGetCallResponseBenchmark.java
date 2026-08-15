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
package io.reshapr.benchmarks.grpc;

import io.reshapr.benchmarks.proxy.NaiveGrpcProxyService;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.converters.GrpcMcpToolConverter;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.proxy.GrpcProxyService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark for {@code GrpcMcpToolConverter.getCallResponse()}.
 *
 * <p>Axes:</p>
 * <ul>
 *   <li>{@code operationCount}: 6 (few), 30 (medium), 80 (many) operations in the spec</li>
 *   <li>{@code specSize}: SMALL / MEDIUM / LARGE schema complexity (drives document byte size)</li>
 *   <li>{@code refStyle}: INLINE (local refs) / EXTERNAL (parameters + schemas in attached YAML file)</li>
 *   <li>cold vs warm {@link WorkCache} (dedicated benchmark methods)</li>
 * </ul>
 *
 * <p>The {@link GrpcProxyService} is replaced by {@link NaiveGrpcProxyService} so no I/O is measured —
 * only the conversion logic. Run with {@code -prof gc} to also capture per-op allocations.</p>
 *
 * @author laurent
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = { "--enable-preview", "-Xms1g", "-Xmx1g" })
public class GrpcGetCallResponseBenchmark {

   private static final String ARTIFACT_ID = "artifact-grpc-1";

   /** Shared, per-trial state: generated spec, exposition, stubbed proxy and a pre-warmed converter. */
   @State(Scope.Benchmark)
   public static class BenchState {

      @Param({ "6", "30", "80" })
      public int operationCount;

      @Param({ "SMALL", "MEDIUM", "LARGE" })
      public String specSize;

      @Param({ "INLINE", "EXTERNAL" })
      public String refStyle;

      ObjectMapper mapper;
      GrpcProxyService proxyStub;
      ExpositionEntry exposition;
      ConfigurationEntry configuration;
      OperationEntry operation;
      Map<String, Object> argumentsTemplate;

      WorkCache warmCache;
      GrpcMcpToolConverter warmConverter;

      @Setup(Level.Trial)
      public void setupTrial() {
         mapper = new ObjectMapper();
         proxyStub = new NaiveGrpcProxyService();

         // Generate the synthetic gRPC Protobuf specification.
         GrpcSpecGenerator.GeneratedSpec generated = new GrpcSpecGenerator().generate(operationCount,
               GrpcSpecGenerator.Complexity.valueOf(specSize),
               GrpcSpecGenerator.RefStyle.valueOf(refStyle));

         System.out.printf("%n>>> Generated gRPC spec: %d operations, complexity %s, %s%n",
               operationCount, specSize, refStyle);

         ArtifactEntry artifact = new ArtifactEntry(ARTIFACT_ID, "synthetic.proto",
               "synthetic.proto", ArtifactEntryType.PROTOBUF_DESCRIPTOR, true, generated.base64DescriptorSet());

         operation = new OperationEntry(generated.benchmarkedOperation(), "UNARY", null, null, null);

         ServiceEntry service = new ServiceEntry("svc-grpc-1", "reshapr", generated.serviceName(), "1.0.0", "GRPC", List.of(operation));
         configuration = new ConfigurationEntry("cfg-grpc-1", "synthetic-grpc", "grpc://localhost:1",
               1000L, null, null, null, null, null);
         exposition = new ExpositionEntry("expo-grpc-1", "synthetic-grpc", service, configuration, artifact,
               List.of());

         // Build the arguments template matching the benchmarked operation parameters.
         argumentsTemplate = new HashMap<>();
         int fields = GrpcSpecGenerator.Complexity.valueOf(specSize).fieldsPerMessage;
         for (int i = 1; i <= fields; i++) {
            argumentsTemplate.put("field_" + i, "value_" + i);
         }

         // Build a converter with a pre-warmed cache for the 'warm' benchmarks.
         warmCache = new WorkCache(1000);
         warmConverter = new GrpcMcpToolConverter(exposition, warmCache, mapper, proxyStub);
         McpToolConverter.Response response = warmConverter.getCallResponse(operation, configuration,
               newRequest(), newHeaders());
         if (response == null || response.isFault()) {
            throw new IllegalStateException("Sanity check failed: unexpected response " + response);
         }
      }

      public McpSchema.SimpleRequest newRequest() {
         return new McpSchema.SimpleRequest(operation.name(), argumentsTemplate);
      }

      public Map<String, List<String>> newHeaders() {
         return Map.of("x-reshapr-key", List.of("dummy"));
      }
   }

   /**
    * Cold cache scenario: empty WorkCache. The converter is newly instantiated per call
    * and MUST parse the Protobuf descriptor from the base64 string.
    */
   @Benchmark
   public McpToolConverter.Response coldCache(BenchState bench) {
      WorkCache emptyCache = new WorkCache(100);
      GrpcMcpToolConverter converter = new GrpcMcpToolConverter(bench.exposition, emptyCache, bench.mapper, bench.proxyStub);
      return converter.getCallResponse(bench.operation, bench.configuration, bench.newRequest(), bench.newHeaders());
   }

   /**
    * Warm cache, pre-warmed converter scenario: the WorkCache is pre-populated with the parsed
    * Descriptor objects, and the converter instance itself is reused across calls.
    * This is the absolute fast-path.
    */
   @Benchmark
   public McpToolConverter.Response warmCache(BenchState bench) {
      return bench.warmConverter.getCallResponse(bench.operation, bench.configuration, bench.newRequest(), bench.newHeaders());
   }

   /**
    * Warm cache, fresh converter scenario: the WorkCache is pre-populated, but a fresh converter
    * instance is created per call. This mimics production behavior where ToolCallExecutor
    * uses `buildMcpToolConverter()` which instantiates a new converter per request (reusing the cache).
    */
   @Benchmark
   public McpToolConverter.Response warmCacheFreshConverter(BenchState bench) {
      GrpcMcpToolConverter converter = new GrpcMcpToolConverter(bench.exposition, bench.warmCache, bench.mapper, bench.proxyStub);
      return converter.getCallResponse(bench.operation, bench.configuration, bench.newRequest(), bench.newHeaders());
   }
}
