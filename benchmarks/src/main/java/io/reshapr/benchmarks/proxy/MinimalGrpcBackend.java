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
package io.reshapr.benchmarks.proxy;

import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * An ultra-minimal in-JVM gRPC backend used as the counterpart of the
 * {@link GrpcProxyServiceCallBackendBenchmark}.
 *
 * <p>It is deliberately as fast as possible so that it never becomes the bottleneck of the
 * measurement: it binds on a loopback port and responds to <em>any</em> unary RPC call with a
 * pre-serialized canned Protobuf response byte array. Incoming request bytes are discarded
 * immediately — only the response matters for benchmarking proxy throughput.</p>
 *
 * <p>The raw-byte transport mirrors the approach used by {@link io.reshapr.proxy.util.GrpcUtil}:
 * a generic {@code MethodDescriptor<byte[], byte[]>} handles the framing so no generated stubs
 * are required and the backend stays completely schema-agnostic.</p>
 *
 * @author laurent
 */
public final class MinimalGrpcBackend implements AutoCloseable {

   /** Generic byte-array marshaller — avoids any Protobuf dependency in the server stub. */
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
   private final byte[] cannedResponse;

   /**
    * Start a minimal gRPC server on an ephemeral loopback port.
    *
    * @param serviceName   The fully-qualified service name (e.g. {@code "io.github.microcks.grpc.hello.v1.HelloService"}).
    * @param methodName    The simple method name (e.g. {@code "greeting"}).
    * @param cannedResponse The pre-serialized Protobuf response returned for every call.
    * @throws IOException If the server cannot be bound.
    */
   public MinimalGrpcBackend(String serviceName, String methodName, byte[] cannedResponse)
         throws IOException {
      this.cannedResponse = cannedResponse;

      // Build a generic unary method descriptor that mirrors GrpcUtil.buildGenericUnaryMethodDescriptor.
      MethodDescriptor<byte[], byte[]> methodDescriptor = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
            .setRequestMarshaller(BYTES_MARSHALLER)
            .setResponseMarshaller(BYTES_MARSHALLER)
            .build();

      // Build a service definition that handles calls with the canned response.
      ServerMethodDefinition<byte[], byte[]> methodDef = ServerMethodDefinition.create(
            methodDescriptor,
            ServerCalls.asyncUnaryCall(
                  (request, responseObserver) -> {
                     // Discard request bytes — return the canned response.
                     responseObserver.onNext(this.cannedResponse);
                     responseObserver.onCompleted();
                  }
            ));

      ServerServiceDefinition serviceDef = ServerServiceDefinition.builder(serviceName)
            .addMethod(methodDef)
            .build();

      // Bind on ephemeral port 0 (OS assigns a free port) on the loopback interface only.
      server = ServerBuilder.forPort(0)
            .addService(serviceDef)
            .directExecutor()           // no thread-pool overhead: callers drive I/O directly
            .build()
            .start();
   }

   /** @return The ephemeral port the gRPC server is listening on. */
   public int port() {
      return server.getPort();
   }

   @Override
   public void close() {
      server.shutdownNow();
   }
}
