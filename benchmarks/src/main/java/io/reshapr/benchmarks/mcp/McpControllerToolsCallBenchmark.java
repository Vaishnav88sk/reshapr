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

import io.reshapr.benchmarks.proxy.NaiveProxyService;
import io.reshapr.proxy.audit.AuditLogger;
import io.reshapr.proxy.mcp.McpController;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.ToolCallExecutor;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.mcp.state.SessionStore;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
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
 * Micro-benchmark for the {@code McpController} {@code tools/call} handling path, isolated from
 * any I/O and any real conversion cost:
 * <ul>
 *   <li>the {@link McpToolConverter} is replaced by {@link NaiveMcpToolConverter} (canned response),</li>
 *   <li>the {@link ProxyService} is replaced by {@link NaiveProxyService} (never reached anyway),</li>
 *   <li>audit is driven by the {@code audit} axis; OTEL is unresolved (no-op {@link AuditLogger}),</li>
 *   <li>JAX-RS / Vert.x collaborators are container-free stubs (see {@link McpBenchStubs}).</li>
 * </ul>
 *
 * <p>What remains under measurement is the pure controller logic: exposition lookup, modern
 * (SEP-2243) pre-dispatch validation ladder, session/protocol-mode resolution, ScopedValue
 * binding, method dispatch, Jackson params conversion, tool resolution and result shaping.</p>
 *
 * <p>Benchmark axes:</p>
 * <ul>
 *   <li>{@code protocolMode}: {@code LEGACY} (session-bound, {@code MCP-Session-Id} header,
 *       protocol 2025-06-18) vs {@code MODERN} (stateless, {@code MCP-Protocol-Version} 2026-07-28
 *       + mirror headers + {@code params._meta} envelope);</li>
 *   <li>{@code payloadSize}: size of the {@code tools/call} arguments ({@code SMALL} ≈ 3 scalars,
 *       {@code MEDIUM} ≈ 20 args w/ nested maps, {@code LARGE} ≈ 200 args w/ nested maps and a
 *       2 KiB string);</li>
 *   <li>{@code headerCount}: number of extra HTTP headers on the incoming request
 *       ({@code SMALL} = 4, {@code MEDIUM} = 16, {@code LARGE} = 64);</li>
 *   <li>{@code audit}: whether audit is enabled on the exposition configuration. With {@code true},
 *       the controller-side audit overhead is measured: synchronous capture of request-scoped
 *       values, virtual-thread spawn, and the async re-serialization / params re-conversion cost.
 *       The {@code AuditLogger} itself is a no-op (OTEL unresolved), so the OTEL emission cost is
 *       <b>not</b> included. JMH's GC profiler accounts for all JVM threads, so allocations made on
 *       the spawned virtual thread <b>are</b> included in {@code gc.alloc.rate.norm}; the reported
 *       time however only reflects the request thread (the async CPU runs on carrier threads);</li>
 *   <li>{@code controllerImpl}: controller implementation under test (see {@link ControllerFactory}).</li>
 * </ul>
 *
 * <p>Run with {@code -prof gc} to also capture per-op allocations.</p>
 *
 * @author laurent
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = { "--enable-preview", "-Xms1g", "-Xmx1g" })
public class McpControllerToolsCallBenchmark {

   private static final String EXPOSITION_ID = "expo-mcp-1";
   private static final String TOOL_NAME = "benchTool";

   /** Legacy (session-bound) protocol version pinned in the session. */
   private static final String LEGACY_PROTOCOL_VERSION = "2025-06-18";

   /** Shared per-trial state: fully wired controller and pre-built request/headers/stubs. */
   @State(Scope.Benchmark)
   public static class BenchState {

      @Param({ "LEGACY", "MODERN" })
      public String protocolMode;

      @Param({ "SMALL", "MEDIUM", "LARGE" })
      public String payloadSize;

      @Param({ "SMALL", "MEDIUM", "LARGE" })
      public String headerCount;

      @Param({ "false", "true" })
      public boolean audit;

      @Param({ "current", "optimized" })
      public String controllerImpl;

      McpController controller;
      McpSchema.JSONRPCRequest request;
      HttpHeaders httpHeaders;
      HttpServerRequest serverRequest;
      ContainerRequestContext requestContext;

      @Setup(Level.Trial)
      public void setupTrial() {
         // --- Wire the controller graph outside of any CDI container. ---
         GatewayRegistry registry = new GatewayRegistry();
         WorkCache workCache = new WorkCache(1000);
         ProxyService proxyStub = new NaiveProxyService();
         SessionStore sessionStore = new SessionStore(McpBenchStubs.basicCache());
         AuditLogger auditLogger = new AuditLogger(McpBenchStubs.emptyOpenTelemetryInstance());

         // The naive converter short-circuits any parsing/proxying: only controller logic is measured.
         McpToolConverter naiveConverter = new NaiveMcpToolConverter();
         ToolCallExecutor toolCallExecutor = new ToolCallExecutor(registry, null, null,
               workCache, proxyStub, null) {
            @Override
            public McpToolConverter buildMcpToolConverter(ExpositionEntry exposition) {
               return naiveConverter;
            }
         };

         controller = ControllerFactory.forName(controllerImpl).create(registry, sessionStore, workCache,
               proxyStub, toolCallExecutor, auditLogger);

         // --- Register the benchmarked exposition (audit driven by the benchmark axis). ---
         OperationEntry operation = new OperationEntry(TOOL_NAME, "GET", null, null, null);
         ServiceEntry service = new ServiceEntry("svc-mcp-1", "reshapr", "Bench Service", "1.0",
               "REST", List.of(operation));
         ConfigurationEntry configuration = new ConfigurationEntry("cfg-mcp-1", "bench-default",
               "http://localhost:1/api", 1000L, List.of(), List.of(), null, null, null, audit);
         ExpositionEntry exposition = new ExpositionEntry(EXPOSITION_ID, "bench-exposition", service,
               configuration, null, List.of());
         registry.addExposition(exposition);

         // --- Build the incoming request + headers according to the benchmark axes. ---
         Map<String, List<String>> headers = buildBaseHeaders(headerCount);
         Map<String, Object> params = new HashMap<>();
         params.put("name", TOOL_NAME);
         params.put("arguments", buildArguments(payloadSize));

         if ("LEGACY".equals(protocolMode)) {
            // Legacy mode: a server-side session pins the protocol version; the client sends its id.
            String sessionId = sessionStore.initializeSession(service.id(), LEGACY_PROTOCOL_VERSION);
            headers.put(McpSchema.HEADER_SESSION_ID, List.of(sessionId));
         } else {
            // Modern (stateless) mode: protocol version header + full mirror-header contract
            // + params._meta envelope, so the whole modern validation ladder is exercised.
            headers.put(McpSchema.HEADER_PROTOCOL_VERSION, List.of(McpSchema.PROTOCOL_VERSION_STATELESS));
            headers.put(McpSchema.HEADER_METHOD, List.of(McpSchema.METHOD_TOOLS_CALL));
            headers.put(McpSchema.HEADER_NAME, List.of(TOOL_NAME));
            params.put("_meta", Map.of(McpSchema.META_KEY_PROTOCOL_VERSION,
                  McpSchema.PROTOCOL_VERSION_STATELESS));
         }

         request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
               McpSchema.METHOD_TOOLS_CALL, 1, params);
         httpHeaders = new McpBenchStubs.BenchHttpHeaders(headers);
         serverRequest = McpBenchStubs.httpServerRequest(headers);
         requestContext = McpBenchStubs.containerRequestContext("bench-user", "https://bench.issuer");

         // --- Sanity check: one full call must succeed before measuring. ---
         Response response = controller.handleHttpStreamable(EXPOSITION_ID, request, httpHeaders,
               serverRequest, requestContext);
         if (response.getStatus() != 200) {
            throw new IllegalStateException("Sanity check failed: HTTP " + response.getStatus()
                  + " — " + response.getEntity());
         }
         if (response.getEntity() instanceof McpSchema.JSONRPCResponse jsonRpcResponse
               && jsonRpcResponse.error() != null) {
            throw new IllegalStateException("Sanity check failed: JSON-RPC error "
                  + jsonRpcResponse.error());
         }
         System.out.printf("%n>>> Sanity OK [impl=%s, mode=%s, payload=%s, headers=%s, audit=%s] -> %s%n",
               controllerImpl, protocolMode, payloadSize, headerCount, audit, response.getEntity());
      }

      /** Build the base HTTP headers with the axis-driven number of extra filler headers. */
      private static Map<String, List<String>> buildBaseHeaders(String headerCount) {
         Map<String, List<String>> headers = new HashMap<>();
         headers.put("Accept", List.of("application/json"));
         headers.put("Content-Type", List.of("application/json"));
         headers.put("User-Agent", List.of("reshapr-bench/1.0"));
         headers.put("Authorization", List.of("Bearer bench-token"));

         int extra = switch (headerCount) {
            case "SMALL" -> 4;
            case "MEDIUM" -> 16;
            case "LARGE" -> 64;
            default -> throw new IllegalArgumentException("Unknown headerCount: " + headerCount);
         };
         for (int i = 0; i < extra; i++) {
            headers.put("X-Bench-Header-" + i, List.of("value-" + i));
         }
         return headers;
      }

      /** Build the tools/call arguments with the axis-driven payload size. */
      private static Map<String, Object> buildArguments(String payloadSize) {
         return switch (payloadSize) {
            case "SMALL" -> {
               Map<String, Object> args = new HashMap<>();
               args.put("id", "42");
               args.put("lorem", "ipsum");
               args.put("verbose", true);
               yield args;
            }
            case "MEDIUM" -> buildStructuredArguments(20, 3, 5, 64);
            case "LARGE" -> buildStructuredArguments(200, 10, 20, 2048);
            default -> throw new IllegalArgumentException("Unknown payloadSize: " + payloadSize);
         };
      }

      /** Build a payload with {@code scalars} scalar args, {@code nested} maps of {@code nestedSize} entries and one blob. */
      private static Map<String, Object> buildStructuredArguments(int scalars, int nested,
            int nestedSize, int blobSize) {
         Map<String, Object> args = new HashMap<>();
         for (int i = 0; i < scalars; i++) {
            args.put("param" + i, (i % 3 == 0) ? i : "value-" + i);
         }
         for (int i = 0; i < nested; i++) {
            Map<String, Object> nestedMap = new HashMap<>();
            for (int j = 0; j < nestedSize; j++) {
               nestedMap.put("field" + j, "nested-value-" + i + "-" + j);
            }
            args.put("nested" + i, nestedMap);
         }
         args.put("blob", "x".repeat(blobSize));
         return args;
      }
   }

   /**
    * Full {@code tools/call} round trip through the controller public endpoint (exposition-id
    * routing), everything below the converter being canned.
    */
   @Benchmark
   public Response toolsCall(BenchState state) {
      return state.controller.handleHttpStreamable(EXPOSITION_ID, state.request, state.httpHeaders,
            state.serverRequest, state.requestContext);
   }
}


