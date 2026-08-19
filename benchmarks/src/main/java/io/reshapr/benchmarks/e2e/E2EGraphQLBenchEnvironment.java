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
package io.reshapr.benchmarks.e2e;

import io.reshapr.benchmarks.proxy.ProxyBenchPayloads;
import io.reshapr.discovery.exposition.v1.Artifact;
import io.reshapr.discovery.exposition.v1.ArtifactType;
import io.reshapr.discovery.exposition.v1.Configuration;
import io.reshapr.discovery.exposition.v1.Exposition;
import io.reshapr.discovery.exposition.v1.Operation;
import io.reshapr.discovery.exposition.v1.Service;

import io.grpc.Server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone environment for the <b>end-to-end GraphQL proxy benchmark</b>: everything the proxy
 * under test needs, except the proxy itself. It mirrors {@link E2EBenchEnvironment} (REST) but
 * exposes a GraphQL service, so the reported metrics are directly comparable.
 *
 * <p>It hosts in a single JVM:</p>
 * <ul>
 *   <li>A <b>stub control-plane</b> ({@link E2EStubControlPlane}: EDS + GHS) serving one GraphQL
 *       exposition per response-payload size ({@code bench-graphql-small},
 *       {@code bench-graphql-medium}, {@code bench-graphql-large}). Every exposition advertises the
 *       real-world <b>GitHub GraphQL schema</b> ({@code github-api.graphql}, ~1.4 MiB, bundled in
 *       {@code src/main/resources} — the same schema used by the GraphQL micro-benchmark) as its
 *       main artifact, and the {@code user} query as the benchmarked operation.</li>
 *   <li>Three <b>canned GraphQL/HTTP backends</b> ({@link CannedHttpBackend}), one per payload size
 *       (defaults ~5 KiB / ~50 KiB / ~500 KiB of JSON). GraphQL-over-HTTP is a plain {@code POST}
 *       with a {@code {"variables": ..., "query": ...}} body whose JSON response the proxy returns
 *       verbatim, so the schema-agnostic canned backend fits unchanged and never becomes the
 *       bottleneck.</li>
 * </ul>
 *
 * <p>The proxy is then started as a regular Quarkus application pointing at this stub
 * ({@code RESHAPR_CTRL_HOST/PORT}), and a load injector (k6) drives its MCP endpoint — see
 * {@code run-e2e-graphql-bench.sh}. The proxy raises the graphql-java anti-DoS parser limits
 * globally (see {@code graphql.parser.max-characters} in the proxy {@code application.properties}),
 * so the 1.4 MiB GitHub schema is parsed without tuning here.</p>
 *
 * <p>Configuration via system properties (all optional):
 * {@code -De2e.grpc.port=15555}, {@code -De2e.backend.small.port=19911},
 * {@code -De2e.backend.medium.port=19912}, {@code -De2e.backend.large.port=19913},
 * {@code -De2e.payload.small.items=38}, {@code -De2e.payload.medium.items=380},
 * {@code -De2e.payload.large.items=3700} (~130 B of JSON per item),
 * {@code -De2e.graphql.schema=github-api.graphql} (classpath resource or filesystem path),
 * {@code -De2e.graphql.operation=user}, {@code -De2e.audit.enabled=false}.</p>
 *
 * <p>Prints {@code E2E-ENV READY} on stdout once every socket is bound, so that orchestration
 * scripts can wait deterministically.</p>
 *
 * @author laurent
 */
public final class E2EGraphQLBenchEnvironment {

   private E2EGraphQLBenchEnvironment() {
   }

   /** One benchmarked exposition: a payload size (as a JSON item count) bound to a backend port. */
   private record BenchExposition(String suffix, int payloadItems, int backendPort) {
      String expositionId() {
         return "bench-graphql-" + suffix;
      }

      String serviceId() {
         return "svc-graphql-" + suffix;
      }
   }

   public static void main(String[] args) throws Exception {
      int grpcPort = Integer.getInteger("e2e.grpc.port", 15555);
      boolean auditEnabled = Boolean.getBoolean("e2e.audit.enabled");
      String schemaLocation = System.getProperty("e2e.graphql.schema", "github-api.graphql");
      String operationName = System.getProperty("e2e.graphql.operation", "user");

      // Item counts calibrated for ~5 KiB / ~50 KiB / ~500 KiB of JSON (~130 B per item).
      List<BenchExposition> benchExpositions = List.of(
            new BenchExposition("small", Integer.getInteger("e2e.payload.small.items", 38),
                  Integer.getInteger("e2e.backend.small.port", 19911)),
            new BenchExposition("medium", Integer.getInteger("e2e.payload.medium.items", 380),
                  Integer.getInteger("e2e.backend.medium.port", 19912)),
            new BenchExposition("large", Integer.getInteger("e2e.payload.large.items", 3_700),
                  Integer.getInteger("e2e.backend.large.port", 19913)));

      // Load the GraphQL schema (classpath first, then filesystem) shared by the three expositions.
      String schema = loadSchema(schemaLocation);
      System.out.printf("Loaded GraphQL schema '%s' (%.1f KiB), benchmarked operation '%s'%n",
            schemaLocation, schema.getBytes(StandardCharsets.UTF_8).length / 1024.0, operationName);

      // Start the canned GraphQL/HTTP backends, one per payload size.
      List<CannedHttpBackend> backends = new ArrayList<>();
      for (BenchExposition bench : benchExpositions) {
         String body = buildGraphQLResponse(operationName, bench.payloadItems());
         backends.add(new CannedHttpBackend(bench.backendPort(), body.getBytes(StandardCharsets.UTF_8)));
         System.out.printf("Backend '%s' listening on 127.0.0.1:%d (response body %.1f KiB)%n",
               bench.suffix(), bench.backendPort(), body.length() / 1024.0);
      }

      // Build the protobuf expositions + artifacts served by the stub control-plane.
      Map<String, Exposition> expositionsById = new LinkedHashMap<>();
      Map<String, Artifact> artifactsByServiceId = new LinkedHashMap<>();
      for (BenchExposition bench : benchExpositions) {
         Service service = Service.newBuilder()
               .setId(bench.serviceId())
               .setOrganizationId(E2EStubControlPlane.ORGANIZATION_ID)
               .setName("Bench GraphQL API " + bench.suffix())
               .setVersion("1.0.0")
               .setType("GRAPHQL")
               .addOperations(Operation.newBuilder()
                     .setName(operationName)
                     .setMethod("QUERY")
                     .build())
               .build();
         // No apiKey and no oauth2Configuration: the MCP endpoint is unauthenticated, so the
         // injector measures the proxying path without token management.
         Configuration configuration = Configuration.newBuilder()
               .setId("cfg-graphql-" + bench.suffix())
               .setName("bench-default")
               .setBackendEndpoint("http://127.0.0.1:" + bench.backendPort())
               .setAudit(auditEnabled)
               .build();
         Exposition exposition = Exposition.newBuilder()
               .setId(bench.expositionId())
               .setName(bench.expositionId())
               .setService(service)
               .setConfiguration(configuration)
               .build();
         expositionsById.put(bench.expositionId(), exposition);
         artifactsByServiceId.put(bench.serviceId(), Artifact.newBuilder()
               .setId("artifact-graphql-" + bench.suffix())
               .setName("github-api.graphql")
               .setType(ArtifactType.GRAPHQL_SCHEMA)
               // An empty path marks the root artifact: required for the proxy to register it as main.
               .setMainArtifact(true)
               .setContent(schema)
               .build());
      }

      Server grpcServer = E2EStubControlPlane.start(grpcPort, expositionsById, artifactsByServiceId);
      System.out.printf("GraphQL benchmark expositions ready (audit %s)%n",
            auditEnabled ? "ENABLED" : "disabled");

      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
         grpcServer.shutdownNow();
         backends.forEach(backend -> {
            try {
               backend.close();
            } catch (Exception e) {
               // Best effort on shutdown.
            }
         });
      }));

      System.out.println("E2E-ENV READY");
      grpcServer.awaitTermination();
   }

   /**
    * Build a canned GraphQL-over-HTTP JSON response of the target magnitude: a
    * {@code {"data": {"<operation>": {"kind": "bench", "items": [...] }}}} envelope wrapping the
    * shared synthetic payload. The proxy returns this body verbatim, so its size drives the
    * measured latency/throughput exactly like the REST benchmark.
    */
   private static String buildGraphQLResponse(String operationName, int items) {
      return "{\"data\":{\"" + operationName + "\":" + ProxyBenchPayloads.buildJson(items) + "}}";
   }

   /** Resolve the schema on the classpath first (bundled jar), then as a filesystem path. */
   private static String loadSchema(String location) throws IOException {
      try (InputStream stream = E2EGraphQLBenchEnvironment.class.getClassLoader().getResourceAsStream(location)) {
         if (stream != null) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
         }
      }
      java.nio.file.Path path = java.nio.file.Path.of(location);
      if (java.nio.file.Files.exists(path)) {
         return java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
      }
      throw new IOException("GraphQL schema '" + location + "' found neither on classpath nor on filesystem");
   }
}
