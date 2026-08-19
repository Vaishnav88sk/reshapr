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

import io.reshapr.discovery.exposition.v1.Artifact;
import io.reshapr.discovery.exposition.v1.ArtifactsRequest;
import io.reshapr.discovery.exposition.v1.ArtifactsResponse;
import io.reshapr.discovery.exposition.v1.Exposition;
import io.reshapr.discovery.exposition.v1.ExpositionChangeEvent;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryRequest;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryResponse;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryServiceGrpc;
import io.reshapr.discovery.exposition.v1.ExpositionFetchRequest;
import io.reshapr.health.gateway.v1.GatewayHealthResponse;
import io.reshapr.health.gateway.v1.GatewayHealthServiceGrpc;
import io.reshapr.health.gateway.v1.GatewayRequest;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.Map;

/**
 * Reusable <b>stub control-plane</b> shared by every end-to-end proxy benchmark environment
 * (REST — {@link E2EBenchEnvironment}, GraphQL — {@link E2EGraphQLBenchEnvironment}, gRPC —
 * {@link E2EGrpcBenchEnvironment}).
 *
 * <p>It is a plain gRPC server implementing exactly what the proxy under test needs to discover
 * and serve a static set of expositions:</p>
 * <ul>
 *   <li>the <b>Exposition Discovery Service</b> ({@code eds-v1.proto}) — returns the benchmarked
 *       expositions on discovery, serves their artifacts, and keeps the change stream open and
 *       silent (the topology is static during a run, so the proxy never enters its retry loop);</li>
 *   <li>the <b>Gateway Health Service</b> ({@code ghs-v1.proto}) — acknowledges every health
 *       advertisement.</li>
 * </ul>
 *
 * <p>Auth metadata is ignored: the benchmarked MCP endpoints are unauthenticated so the injector
 * measures the proxying path without token management.</p>
 *
 * @author laurent
 */
public final class E2EStubControlPlane {

   /** Organization used by every benchmarked exposition. */
   public static final String ORGANIZATION_ID = "reshapr";

   private E2EStubControlPlane() {
   }

   /**
    * Build and start the stub control-plane (EDS + GHS) serving the given static expositions.
    *
    * @param grpcPort             The loopback port the gRPC server binds on.
    * @param expositionsById      The benchmarked expositions keyed by their id.
    * @param artifactsByServiceId The (single) artifact of each service keyed by its service id.
    * @return The started gRPC {@link Server}.
    * @throws IOException If the server cannot be bound.
    */
   public static Server start(int grpcPort, Map<String, Exposition> expositionsById,
                              Map<String, Artifact> artifactsByServiceId) throws IOException {
      Server grpcServer = ServerBuilder.forPort(grpcPort)
            .addService(new StubExpositionDiscoveryService(expositionsById, artifactsByServiceId))
            .addService(new StubGatewayHealthService())
            .build()
            .start();
      System.out.printf("Stub control-plane (EDS + GHS) listening on port %d with %d expositions: %s%n",
            grpcPort, expositionsById.size(), expositionsById.keySet());
      return grpcServer;
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
