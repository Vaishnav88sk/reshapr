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

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentLinkedQueue;

import static io.grpc.Metadata.ASCII_STRING_MARSHALLER;

/**
 * A variant of {@link MinimalGrpcBackend} that <em>captures</em> the {@code Authorization} metadata
 * of every incoming call in addition to returning a canned response.
 *
 * <p>It exists to prove — behaviourally — that a channel-pooling proxy still applies
 * <strong>per-call</strong> authorization credentials: two calls sharing the same pooled
 * {@code ManagedChannel} but carrying different {@code Authorization} tokens must be observed by the
 * backend with their respective tokens.</p>
 *
 * @author laurent
 */
public final class CapturingGrpcBackend implements AutoCloseable {

   /** gRPC lowercases metadata keys internally; use the canonical lowercase name here. */
   static final Metadata.Key<String> AUTHORIZATION_KEY =
         Metadata.Key.of("authorization", ASCII_STRING_MARSHALLER);

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
   private final ConcurrentLinkedQueue<String> capturedAuthorizations = new ConcurrentLinkedQueue<>();

   public CapturingGrpcBackend(String serviceName, String methodName, byte[] cannedResponse)
         throws IOException {
      this.cannedResponse = cannedResponse;

      MethodDescriptor<byte[], byte[]> methodDescriptor = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
            .setRequestMarshaller(BYTES_MARSHALLER)
            .setResponseMarshaller(BYTES_MARSHALLER)
            .build();

      ServerMethodDefinition<byte[], byte[]> methodDef = ServerMethodDefinition.create(
            methodDescriptor,
            ServerCalls.asyncUnaryCall(
                  (request, responseObserver) -> {
                     responseObserver.onNext(this.cannedResponse);
                     responseObserver.onCompleted();
                  }
            ));

      ServerServiceDefinition serviceDef = ServerServiceDefinition.builder(serviceName)
            .addMethod(methodDef)
            .build();

      server = ServerBuilder.forPort(0)
            .addService(serviceDef)
            .intercept(new AuthorizationCapturingInterceptor())
            .directExecutor()
            .build()
            .start();
   }

   /** @return The ephemeral port the gRPC server is listening on. */
   public int port() {
      return server.getPort();
   }

   /** @return The {@code Authorization} values observed so far, in call order. */
   public java.util.List<String> capturedAuthorizations() {
      return java.util.List.copyOf(capturedAuthorizations);
   }

   /** Clear the captured metadata history. */
   public void clearCaptured() {
      capturedAuthorizations.clear();
   }

   @Override
   public void close() {
      server.shutdownNow();
   }

   /** Records the {@code Authorization} header of every incoming call. */
   private final class AuthorizationCapturingInterceptor implements ServerInterceptor {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                   Metadata headers, ServerCallHandler<ReqT, RespT> next) {
         String authorization = headers.get(AUTHORIZATION_KEY);
         capturedAuthorizations.add(authorization == null ? "<none>" : authorization);
         return next.startCall(call, headers);
      }
   }
}
