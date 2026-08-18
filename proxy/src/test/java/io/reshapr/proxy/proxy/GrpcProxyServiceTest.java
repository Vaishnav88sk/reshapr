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
package io.reshapr.proxy.proxy;

import io.reshapr.proxy.TestResources;
import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.context.MethodHandlingInfo;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;
import io.reshapr.proxy.util.GrpcUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit test for {@link GrpcProxyService#callBackend} focused on the Protobuf → JSON response
 * conversion (the "piste A" streaming {@code appendTo} optimisation), guarding against any
 * regression in how a gRPC backend response is interpreted and rendered back to the caller.
 *
 * <p>It reuses the {@code google.firestore.v1.Firestore} descriptor and the {@code GetDocument}
 * method (returns a {@code Document}) from {@code GrpcMcpToolConverterTest}, driving a real
 * in-JVM gRPC backend that returns a canned {@code Document} message. The proxy call is then
 * asserted to produce the exact same UTF-8 JSON bytes as the legacy
 * {@code printer.print(msg).getBytes(UTF_8)} path, plus a semantic check of the JSON content.</p>
 *
 * @author laurent
 */
class GrpcProxyServiceTest {

   private static final String FIRESTORE_PBB_BASE64 =
         TestResources.readString("io/reshapr/proxy/artifacts/firestore.pbb.base64");

   private static final String SERVICE_NAME = "google.firestore.v1.Firestore";
   private static final String METHOD_NAME = "GetDocument";

   @Test
   void testGrpcResponseIsConvertedToExpectedJson() throws Exception {
      // Resolve the GetDocument method descriptor (output type: google.firestore.v1.Document).
      Descriptors.MethodDescriptor md =
            GrpcUtil.findMethodDescriptor(FIRESTORE_PBB_BASE64, SERVICE_NAME, METHOD_NAME);
      TypeRegistry registry = GrpcUtil.buildTypeRegistry(FIRESTORE_PBB_BASE64);

      // Build a canned Document response covering: a plain string field and two well-known
      // Timestamp fields (which must render as RFC3339 strings, not nested seconds/nanos objects).
      String cannedDocumentJson = """
            {
              "name": "projects/demo/databases/(default)/documents/users/alice",
              "createTime": "2024-01-15T10:30:00Z",
              "updateTime": "2024-01-16T08:00:00Z"
            }""";
      DynamicMessage.Builder docBuilder = DynamicMessage.newBuilder(md.getOutputType());
      JsonFormat.parser().usingTypeRegistry(registry).merge(cannedDocumentJson, docBuilder);
      byte[] cannedResponseBytes = docBuilder.build().toByteArray();

      try (TestGrpcBackend backend = new TestGrpcBackend(SERVICE_NAME, METHOD_NAME, cannedResponseBytes)) {
         GrpcProxyService proxy =
               new GrpcProxyService(new SecretReferenceResolver(List.of()), new UserSecretStore(null));
         injectDefaultTimeout(proxy, 5_000L);

         ConfigurationEntry configuration = new ConfigurationEntry("cfg-1", "firestore-test",
               "http://localhost:" + backend.port(), 5_000L, null, null, null, null, null);

         // Request body is irrelevant (backend ignores it); "{}" parses into any input type.
         // callBackend reads the request context (remote address, org) via a bound ScopedValue,
         // exactly as the MCP layer sets it up before invoking a gRPC-backed tool.
         MethodHandlingInfo handlingInfo =
               new MethodHandlingInfo("127.0.0.1", null, null, null, "org-test");
         BackendResponse response = ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, handlingInfo)
               .call(() -> proxy.callBackend(configuration, md, new java.util.HashMap<>(), "{}"));

         // gRPC success maps to Status.Code.OK.value() == 0 (not HTTP 200).
         assertEquals(0, response.status(), "expected gRPC OK status code");

         String json = new String(response.content(), StandardCharsets.UTF_8);

         // 1. Byte-for-byte identical to the legacy print(msg).getBytes(UTF_8) path: this is the
         //    core guard that the streaming appendTo() optimisation did not alter the output.
         DynamicMessage roundTripped = DynamicMessage.parseFrom(md.getOutputType(), cannedResponseBytes);
         byte[] legacyBytes = JsonFormat.printer().omittingInsignificantWhitespace()
               .print(roundTripped).getBytes(StandardCharsets.UTF_8);
         assertArrayEquals(legacyBytes, response.content(),
               "streamed JSON bytes must equal the legacy print()+getBytes() output");

         // 2. Compact output (no insignificant whitespace / newlines).
         assertFalse(json.contains("\n"), "response JSON must be compact (no newlines)");

         // 3. Semantic content: well-known types rendered correctly, field names in camelCase.
         ObjectMapper mapper = new ObjectMapper();
         JsonNode actual = mapper.readTree(json);
         JsonNode expected = mapper.readTree("""
               {
                 "name": "projects/demo/databases/(default)/documents/users/alice",
                 "createTime": "2024-01-15T10:30:00Z",
                 "updateTime": "2024-01-16T08:00:00Z"
               }""");
         assertEquals(expected, actual, "converted JSON must match the expected Document");

         proxy.shutdown();
      }
   }

   /** Inject the CDI @ConfigProperty default timeout on a non-CDI instance via reflection. */
   private static void injectDefaultTimeout(GrpcProxyService service, long timeoutMs) throws Exception {
      Field field = GrpcProxyService.class.getDeclaredField("defaultBackendTimeout");
      field.setAccessible(true);
      field.set(service, timeoutMs);
   }

   /**
    * Ultra-minimal in-JVM gRPC backend (mirrors the benchmarks' MinimalGrpcBackend): binds on an
    * ephemeral loopback port and answers any unary call with a pre-serialized canned response.
    */
   private static final class TestGrpcBackend implements AutoCloseable {

      private static final MethodDescriptor.Marshaller<byte[]> BYTES_MARSHALLER =
            new MethodDescriptor.Marshaller<>() {
               @Override
               public InputStream stream(byte[] value) {
                  return new ByteArrayInputStream(value);
               }

               @Override
               public byte[] parse(InputStream stream) {
                  try {
                     return stream.readAllBytes();
                  } catch (IOException e) {
                     throw new RuntimeException("Failed to read gRPC request bytes", e);
                  }
               }
            };

      private final Server server;

      TestGrpcBackend(String serviceName, String methodName, byte[] cannedResponse) throws IOException {
         MethodDescriptor<byte[], byte[]> methodDescriptor = MethodDescriptor.<byte[], byte[]>newBuilder()
               .setType(MethodDescriptor.MethodType.UNARY)
               .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
               .setRequestMarshaller(BYTES_MARSHALLER)
               .setResponseMarshaller(BYTES_MARSHALLER)
               .build();

         ServerMethodDefinition<byte[], byte[]> methodDef = ServerMethodDefinition.create(
               methodDescriptor,
               ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                  responseObserver.onNext(cannedResponse);
                  responseObserver.onCompleted();
               }));

         ServerServiceDefinition serviceDef = ServerServiceDefinition.builder(serviceName)
               .addMethod(methodDef)
               .build();

         server = ServerBuilder.forPort(0)
               .addService(serviceDef)
               .directExecutor()
               .build()
               .start();
      }

      int port() {
         return server.getPort();
      }

      @Override
      public void close() {
         server.shutdownNow();
      }
   }
}
