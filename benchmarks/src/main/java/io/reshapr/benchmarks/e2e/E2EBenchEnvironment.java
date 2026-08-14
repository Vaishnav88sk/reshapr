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

import io.reshapr.benchmarks.openapi.OpenAPISpecGenerator;
import io.reshapr.benchmarks.proxy.ProxyBenchPayloads;
import io.reshapr.discovery.exposition.v1.Artifact;
import io.reshapr.discovery.exposition.v1.ArtifactType;
import io.reshapr.discovery.exposition.v1.ArtifactsRequest;
import io.reshapr.discovery.exposition.v1.ArtifactsResponse;
import io.reshapr.discovery.exposition.v1.Configuration;
import io.reshapr.discovery.exposition.v1.Exposition;
import io.reshapr.discovery.exposition.v1.ExpositionChangeEvent;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryRequest;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryResponse;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryServiceGrpc;
import io.reshapr.discovery.exposition.v1.ExpositionFetchRequest;
import io.reshapr.discovery.exposition.v1.Operation;
import io.reshapr.discovery.exposition.v1.Service;
import io.reshapr.health.gateway.v1.GatewayHealthResponse;
import io.reshapr.health.gateway.v1.GatewayHealthServiceGrpc;
import io.reshapr.health.gateway.v1.GatewayRequest;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone environment for the <b>end-to-end proxy benchmark</b>: everything the proxy under
 * test needs, except the proxy itself.
 *
 * <p>It hosts in a single JVM:</p>
 * <ul>
 *   <li>A <b>stub control-plane</b>: a plain gRPC server implementing the Exposition Discovery
 *       Service ({@code eds-v1.proto}) and the Gateway Health Service ({@code ghs-v1.proto}),
 *       serving one REST exposition per response-payload size ({@code bench-rest-small},
 *       {@code bench-rest-medium}, {@code bench-rest-large}). The exposed API is a synthetic
 *       OpenAPI spec generated reproducibly by {@link OpenAPISpecGenerator}. The change stream
 *       stays open and silent (topology is static during a run). Auth metadata is ignored.</li>
 *   <li>Three <b>canned REST backends</b> ({@link CannedHttpBackend}), one per payload size
 *       (defaults ~5 KiB / ~50 KiB / ~500 KiB of JSON), designed to never be the bottleneck so
 *       that the measured latency is dominated by the proxy.</li>
 * </ul>
 *
 * <p>The proxy is then started as a regular Quarkus application pointing at this stub
 * ({@code RESHAPR_CTRL_HOST/PORT}), and a load injector (k6) drives its MCP endpoint — see
 * {@code run-e2e-rest-bench.sh}.</p>
 *
 * <p>Configuration via system properties (all optional):
 * {@code -De2e.grpc.port=15555}, {@code -De2e.backend.small.port=19901},
 * {@code -De2e.backend.medium.port=19902}, {@code -De2e.backend.large.port=19903},
 * {@code -De2e.payload.small.items=38}, {@code -De2e.payload.medium.items=380},
 * {@code -De2e.payload.large.items=3700} (~130 B of JSON per item),
 * {@code -De2e.spec.operationCount=30}, {@code -De2e.spec.complexity=MEDIUM},
 * {@code -De2e.audit.enabled=false} (sets the {@code audit} flag on every exposition
 * configuration, so the proxy exercises its full audit path on each call).</p>
 *
 * <p>Prints {@code E2E-ENV READY} on stdout once every socket is bound, so that orchestration
 * scripts can wait deterministically.</p>
 *
 * @author laurent
 */
public final class E2EBenchEnvironment {

   /** Organization used by every benchmarked exposition. */
   public static final String ORGANIZATION_ID = "reshapr";

   private E2EBenchEnvironment() {
   }

   /** One benchmarked exposition: a payload size (as a JSON item count) bound to a backend port. */
   private record BenchExposition(String suffix, int payloadItems, int backendPort) {
      String expositionId() {
         return "bench-rest-" + suffix;
      }

      String serviceId() {
         return "svc-rest-" + suffix;
      }
   }

   public static void main(String[] args) throws Exception {
      int grpcPort = Integer.getInteger("e2e.grpc.port", 15555);
      int operationCount = Integer.getInteger("e2e.spec.operationCount", 30);
      OpenAPISpecGenerator.Complexity complexity =
            OpenAPISpecGenerator.Complexity.valueOf(System.getProperty("e2e.spec.complexity", "MEDIUM"));
      boolean auditEnabled = Boolean.getBoolean("e2e.audit.enabled");

      // Item counts calibrated for ~5 KiB / ~50 KiB / ~500 KiB of JSON (~130 B per item).
      List<BenchExposition> benchExpositions = List.of(
            new BenchExposition("small", Integer.getInteger("e2e.payload.small.items", 38),
                  Integer.getInteger("e2e.backend.small.port", 19901)),
            new BenchExposition("medium", Integer.getInteger("e2e.payload.medium.items", 380),
                  Integer.getInteger("e2e.backend.medium.port", 19902)),
            new BenchExposition("large", Integer.getInteger("e2e.payload.large.items", 3_700),
                  Integer.getInteger("e2e.backend.large.port", 19903)));

      // Single synthetic OpenAPI spec shared by the three expositions (INLINE refs: one artifact).
      OpenAPISpecGenerator.GeneratedSpec spec = new OpenAPISpecGenerator().generate(operationCount,
            complexity, OpenAPISpecGenerator.PathDepth.SHALLOW, OpenAPISpecGenerator.RefStyle.INLINE);
      System.out.printf("Generated OpenAPI spec: %d operations, complexity %s, %.1f KiB, benchmarked operation '%s'%n",
            operationCount, complexity, spec.content().getBytes(StandardCharsets.UTF_8).length / 1024.0,
            spec.benchmarkedOperation());

      // Start the canned backends, one per payload size.
      List<CannedHttpBackend> backends = new ArrayList<>();
      for (BenchExposition bench : benchExpositions) {
         String body = ProxyBenchPayloads.buildJson(bench.payloadItems());
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
               .setOrganizationId(ORGANIZATION_ID)
               .setName("Bench REST API " + bench.suffix())
               .setVersion("1.0.0")
               .setType("REST")
               .addOperations(Operation.newBuilder()
                     .setName(spec.benchmarkedOperation())
                     .setMethod("GET")
                     .build())
               .build();
         // No apiKey and no oauth2Configuration: the MCP endpoint is unauthenticated, so the
         // injector measures the proxying path without token management.
         Configuration configuration = Configuration.newBuilder()
               .setId("cfg-rest-" + bench.suffix())
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
               .setId("artifact-rest-" + bench.suffix())
               .setName("synthetic-openapi.json")
               .setType(ArtifactType.OPEN_API_SPEC)
               // An empty path marks the root artifact: required for the proxy to register it as main.
               .setMainArtifact(true)
               .setContent(spec.content())
               .build());
      }

      Server grpcServer = ServerBuilder.forPort(grpcPort)
            .addService(new StubExpositionDiscoveryService(expositionsById, artifactsByServiceId))
            .addService(new StubGatewayHealthService())
            .build()
            .start();
      System.out.printf("Stub control-plane (EDS + GHS) listening on port %d with %d expositions: %s (audit %s)%n",
            grpcPort, expositionsById.size(), expositionsById.keySet(), auditEnabled ? "ENABLED" : "disabled");

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

   /** Stub EDS: serves the static benchmarked expositions; the change stream stays open and silent. */
   private static final class StubExpositionDiscoveryService
         extends ExpositionDiscoveryServiceGrpc.ExpositionDiscoveryServiceImplBase {

      private final Map<String, Exposition> expositionsById;
      private final Map<String, Artifact> artifactsByServiceId;

      private StubExpositionDiscoveryService(Map<String, Exposition> expositionsById,
                                             Map<String, Artifact> artifactsByServiceId) {
         this.expositionsById = expositionsById;
         this.artifactsByServiceId = artifactsByServiceId;
      }

      @Override
      public void discoverExpositions(ExpositionDiscoveryRequest request,
                                      StreamObserver<ExpositionDiscoveryResponse> responseObserver) {
         System.out.printf("Gateway '%s' (version %s) discovered %d expositions%n",
               request.getGatewayId(), request.getVersion(), expositionsById.size());
         responseObserver.onNext(ExpositionDiscoveryResponse.newBuilder()
               .addAllExpositions(expositionsById.values())
               .build());
         responseObserver.onCompleted();
      }

      @Override
      public void streamExpositionChanges(ExpositionDiscoveryRequest request,
                                          StreamObserver<ExpositionChangeEvent> responseObserver) {
         // Static topology: keep the stream open without emitting anything so the proxy does not
         // enter its retry loop during the measurement.
      }

      @Override
      public void fetchExposition(ExpositionFetchRequest request,
                                  StreamObserver<Exposition> responseObserver) {
         Exposition exposition = expositionsById.get(request.getExpositionId());
         if (exposition == null) {
            responseObserver.onError(Status.NOT_FOUND
                  .withDescription("Unknown exposition: " + request.getExpositionId())
                  .asRuntimeException());
            return;
         }
         responseObserver.onNext(exposition);
         responseObserver.onCompleted();
      }

      @Override
      public void fetchArtifacts(ArtifactsRequest request,
                                 StreamObserver<ArtifactsResponse> responseObserver) {
         Artifact artifact = artifactsByServiceId.get(request.getServiceId());
         if (artifact == null) {
            responseObserver.onError(Status.NOT_FOUND
                  .withDescription("Unknown service: " + request.getServiceId())
                  .asRuntimeException());
            return;
         }
         responseObserver.onNext(ArtifactsResponse.newBuilder()
               .setServiceId(request.getServiceId())
               .addArtifacts(artifact)
               .build());
         responseObserver.onCompleted();
      }
   }

   /** Stub GHS: acknowledges every health advertisement. */
   private static final class StubGatewayHealthService
         extends GatewayHealthServiceGrpc.GatewayHealthServiceImplBase {

      private static final GatewayHealthResponse ACK =
            GatewayHealthResponse.newBuilder().setAcknowledged(true).build();

      @Override
      public void advertHealthy(GatewayRequest request, StreamObserver<GatewayHealthResponse> responseObserver) {
         responseObserver.onNext(ACK);
         responseObserver.onCompleted();
      }

      @Override
      public void advertShutdown(GatewayRequest request, StreamObserver<GatewayHealthResponse> responseObserver) {
         responseObserver.onNext(ACK);
         responseObserver.onCompleted();
      }
   }
}
