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

import io.reshapr.benchmarks.grpc.GrpcSpecGenerator;
import io.reshapr.benchmarks.proxy.MinimalGrpcBackend;
import io.reshapr.discovery.exposition.v1.Artifact;
import io.reshapr.discovery.exposition.v1.ArtifactType;
import io.reshapr.discovery.exposition.v1.Configuration;
import io.reshapr.discovery.exposition.v1.Exposition;
import io.reshapr.discovery.exposition.v1.Operation;
import io.reshapr.discovery.exposition.v1.Service;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.Server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.reshapr.proxy.util.GrpcUtil.findMethodDescriptor;

/**
 * Standalone environment for the <b>end-to-end gRPC proxy benchmark</b>: everything the proxy under
 * test needs, except the proxy itself. It mirrors {@link E2EBenchEnvironment} (REST) but exposes a
 * gRPC service, so the reported metrics are directly comparable.
 *
 * <p>It hosts in a single JVM:</p>
 * <ul>
 *   <li>A <b>stub control-plane</b> ({@link E2EStubControlPlane}: EDS + GHS) serving one gRPC
 *       exposition per response-payload size ({@code bench-grpc-small}, {@code bench-grpc-medium},
 *       {@code bench-grpc-large}). Every exposition advertises a synthetic Protobuf
 *       {@code FileDescriptorSet} (base64-encoded, same generator as the gRPC micro-benchmark:
 *       {@link GrpcSpecGenerator}) as its main {@link ArtifactType#PROTOBUF_DESCRIPTOR} artifact,
 *       and {@code Method0} as the benchmarked unary operation.</li>
 *   <li>Three <b>minimal gRPC backends</b> ({@link MinimalGrpcBackend}, reused from the gRPC
 *       micro-benchmark), one per payload size. Each answers every unary call with a pre-serialized
 *       canned {@code ResponseMessage0} whose single string field is padded to ~5 KiB / ~50 KiB /
 *       ~500 KiB, so the backend never becomes the bottleneck. The proxy re-encodes that Protobuf
 *       response as JSON, so the response magnitude drives the measured latency/throughput exactly
 *       like the REST and GraphQL benchmarks.</li>
 * </ul>
 *
 * <p>The proxy is then started as a regular Quarkus application pointing at this stub
 * ({@code RESHAPR_CTRL_HOST/PORT}), and a load injector (k6) drives its MCP endpoint — see
 * {@code run-e2e-grpc-bench.sh}.</p>
 *
 * <p>Configuration via system properties (all optional):
 * {@code -De2e.grpc.port=15555}, {@code -De2e.spec.operationCount=30},
 * {@code -De2e.spec.complexity=SMALL}, {@code -De2e.payload.small.bytes=5120},
 * {@code -De2e.payload.medium.bytes=51200}, {@code -De2e.payload.large.bytes=524288}
 * (approximate response body sizes), {@code -De2e.audit.enabled=false}.</p>
 *
 * <p>Prints {@code E2E-ENV READY} on stdout once every socket is bound, so that orchestration
 * scripts can wait deterministically.</p>
 *
 * @author laurent
 */
public final class E2EGrpcBenchEnvironment {

   private E2EGrpcBenchEnvironment() {
   }

   /** One benchmarked exposition: a payload size (as a response byte target) bound to a backend port. */
   private record BenchExposition(String suffix, int payloadBytes) {
      String expositionId() {
         return "bench-grpc-" + suffix;
      }

      String serviceId() {
         return "svc-grpc-" + suffix;
      }
   }

   public static void main(String[] args) throws Exception {
      int grpcPort = Integer.getInteger("e2e.grpc.port", 15555);
      int operationCount = Integer.getInteger("e2e.spec.operationCount", 30);
      GrpcSpecGenerator.Complexity complexity =
            GrpcSpecGenerator.Complexity.valueOf(System.getProperty("e2e.spec.complexity", "SMALL"));
      boolean auditEnabled = Boolean.getBoolean("e2e.audit.enabled");

      // Response body sizes calibrated for ~5 KiB / ~50 KiB / ~500 KiB of JSON once re-encoded.
      List<BenchExposition> benchExpositions = List.of(
            new BenchExposition("small", Integer.getInteger("e2e.payload.small.bytes", 5_120)),
            new BenchExposition("medium", Integer.getInteger("e2e.payload.medium.bytes", 51_200)),
            new BenchExposition("large", Integer.getInteger("e2e.payload.large.bytes", 524_288)));

      // Single synthetic Protobuf descriptor shared by the three expositions (INLINE: one file).
      GrpcSpecGenerator.GeneratedSpec spec = new GrpcSpecGenerator().generate(operationCount,
            complexity, GrpcSpecGenerator.RefStyle.INLINE);
      String descriptor = spec.base64DescriptorSet();
      System.out.printf("Generated gRPC descriptor: %d operations, complexity %s, %.1f KiB, "
                  + "service '%s', benchmarked operation '%s'%n",
            operationCount, complexity, descriptor.length() / 1024.0,
            spec.serviceName(), spec.benchmarkedOperation());

      // Resolve the output message descriptor of the benchmarked method to build canned responses.
      Descriptors.MethodDescriptor benchMethod =
            findMethodDescriptor(descriptor, spec.serviceName(), spec.benchmarkedOperation());
      Descriptors.Descriptor responseType = benchMethod.getOutputType();
      Descriptors.FieldDescriptor paddingField = responseType.findFieldByName("field_1");

      // Start the minimal gRPC backends, one per payload size, each serving a canned response.
      List<MinimalGrpcBackend> backends = new ArrayList<>();
      Map<String, Integer> backendPorts = new LinkedHashMap<>();
      for (BenchExposition bench : benchExpositions) {
         byte[] canned = buildCannedResponse(responseType, paddingField, bench.payloadBytes());
         MinimalGrpcBackend backend = new MinimalGrpcBackend(spec.serviceName(),
               spec.benchmarkedOperation(), canned);
         backends.add(backend);
         backendPorts.put(bench.suffix(), backend.port());
         System.out.printf("Backend '%s' listening on 127.0.0.1:%d (canned response %.1f KiB Protobuf)%n",
               bench.suffix(), backend.port(), canned.length / 1024.0);
      }

      // Build the protobuf expositions + artifacts served by the stub control-plane.
      Map<String, Exposition> expositionsById = new LinkedHashMap<>();
      Map<String, Artifact> artifactsByServiceId = new LinkedHashMap<>();
      for (BenchExposition bench : benchExpositions) {
         int backendPort = backendPorts.get(bench.suffix());
         Service service = Service.newBuilder()
               .setId(bench.serviceId())
               .setOrganizationId(E2EStubControlPlane.ORGANIZATION_ID)
               // The service name must be the fully-qualified gRPC service name: the proxy resolves
               // the ServiceDescriptor from the descriptor artifact using it (GrpcUtil).
               .setName(spec.serviceName())
               .setVersion("1.0.0")
               .setType("GRPC")
               .addOperations(Operation.newBuilder()
                     .setName(spec.benchmarkedOperation())
                     .setMethod(spec.benchmarkedOperation())
                     .build())
               .build();
         // No apiKey and no oauth2Configuration: the MCP endpoint is unauthenticated, so the
         // injector measures the proxying path without token management.
         Configuration configuration = Configuration.newBuilder()
               .setId("cfg-grpc-" + bench.suffix())
               .setName("bench-default")
               .setBackendEndpoint("http://127.0.0.1:" + backendPort)
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
               .setId("artifact-grpc-" + bench.suffix())
               .setName("synthetic-descriptor.pb")
               .setType(ArtifactType.PROTOBUF_DESCRIPTOR)
               // An empty path marks the root artifact: required for the proxy to register it as main.
               .setMainArtifact(true)
               .setContent(descriptor)
               .build());
      }

      Server grpcServer = E2EStubControlPlane.start(grpcPort, expositionsById, artifactsByServiceId);
      System.out.printf("gRPC benchmark expositions ready (audit %s)%n",
            auditEnabled ? "ENABLED" : "disabled");

      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
         grpcServer.shutdownNow();
         backends.forEach(MinimalGrpcBackend::close);
      }));

      System.out.println("E2E-ENV READY");
      grpcServer.awaitTermination();
   }

   /**
    * Build a pre-serialized canned {@code ResponseMessage0} whose {@code field_1} string is padded so
    * that the serialized message reaches roughly {@code targetBytes}. The proxy re-encodes this
    * message as JSON ({@code {"field_1":"..."}}), so the response magnitude is preserved end-to-end.
    */
   private static byte[] buildCannedResponse(Descriptors.Descriptor responseType,
                                             Descriptors.FieldDescriptor paddingField, int targetBytes) {
      // A few bytes of Protobuf/JSON framing overhead around the string value; keep it non-negative.
      int paddingLength = Math.max(1, targetBytes - 8);
      StringBuilder sb = new StringBuilder(paddingLength);
      // Deterministic printable-ASCII padding (cheap to build, minimal JSON escaping downstream).
      for (int i = 0; i < paddingLength; i++) {
         sb.append((char) ('a' + (i % 26)));
      }
      return DynamicMessage.newBuilder(responseType)
            .setField(paddingField, sb.toString())
            .build()
            .toByteArray();
   }
}
