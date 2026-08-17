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

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;

import java.util.Base64;
import java.util.List;

/**
 * Generates synthetic gRPC Protobuf specifications (FileDescriptorSet in base64) with a controllable
 * number of operations, schema complexity, and reference style (inline vs external file), so that
 * benchmark scenarios are reproducible.
 *
 * <p>The generated spec always contains a benchmarked unary operation 
 * {@link GeneratedSpec#benchmarkedOperation()} (e.g. "Method0").</p>
 *
 * @author laurent
 */
public class GrpcSpecGenerator {

   /** Schema complexity presets driving the byte-size of the generated document. */
   public enum Complexity {
      SMALL(5),
      MEDIUM(20),
      LARGE(50);

      final int fieldsPerMessage;

      Complexity(int fieldsPerMessage) {
         this.fieldsPerMessage = fieldsPerMessage;
      }
   }

   /** Where parameter/schema definitions live. */
   public enum RefStyle {
      /** Everything defined in the same .proto file (single FileDescriptorProto). */
      INLINE,
      /** Shared messages are defined in an imported external .proto file (multiple FileDescriptorProtos). */
      EXTERNAL
   }

   /**
    * Result of a generation: the main FileDescriptorSet encoded in base64, and the metadata needed
    * by the benchmark (operation name).
    */
   public record GeneratedSpec(
         String base64DescriptorSet,
         String benchmarkedOperation,
         String serviceName) {
   }

   public static final String PACKAGE_NAME = "io.reshapr.benchmarks.synthetic";
   public static final String SERVICE_NAME = "SyntheticService";
   private static final String MAIN_FILE_NAME = "synthetic_main.proto";
   private static final String IMPORT_FILE_NAME = "synthetic_common.proto";

   /**
    * Generate a gRPC Protobuf spec.
    * @param operationCount Total number of operations (methods) in the service.
    * @param complexity Schema complexity preset (number of fields per message).
    * @param refStyle Inline messages or external file references.
    * @return The generated spec plus benchmark metadata.
    */
   public GeneratedSpec generate(int operationCount, Complexity complexity, RefStyle refStyle) {
      FileDescriptorSet.Builder fdsBuilder = FileDescriptorSet.newBuilder();

      FileDescriptorProto.Builder mainFileBuilder = FileDescriptorProto.newBuilder()
            .setName(MAIN_FILE_NAME)
            .setPackage(PACKAGE_NAME)
            .setSyntax("proto3");

      FileDescriptorProto.Builder importFileBuilder = null;
      if (refStyle == RefStyle.EXTERNAL) {
         importFileBuilder = FileDescriptorProto.newBuilder()
               .setName(IMPORT_FILE_NAME)
               .setPackage(PACKAGE_NAME)
               .setSyntax("proto3");
         mainFileBuilder.addDependency(IMPORT_FILE_NAME);
      }

      ServiceDescriptorProto.Builder serviceBuilder = ServiceDescriptorProto.newBuilder()
            .setName(SERVICE_NAME);

      for (int i = 0; i < operationCount; i++) {
         String reqName = "RequestMessage" + i;
         String resName = "ResponseMessage" + i;
         String methodName = "Method" + i;

         // For EXTERNAL, put the Request and Response messages in the imported file
         FileDescriptorProto.Builder targetFileBuilder = (refStyle == RefStyle.EXTERNAL) ? importFileBuilder : mainFileBuilder;

         targetFileBuilder.addMessageType(buildMessage(reqName, complexity));
         targetFileBuilder.addMessageType(buildMessage(resName, complexity));

         serviceBuilder.addMethod(MethodDescriptorProto.newBuilder()
               .setName(methodName)
               .setInputType("." + PACKAGE_NAME + "." + reqName)
               .setOutputType("." + PACKAGE_NAME + "." + resName)
               .build());
      }

      mainFileBuilder.addService(serviceBuilder);

      // Add the files to the descriptor set. If EXTERNAL, add the imported one first.
      if (refStyle == RefStyle.EXTERNAL) {
         fdsBuilder.addFile(importFileBuilder.build());
      }
      fdsBuilder.addFile(mainFileBuilder.build());

      String base64DescriptorSet = Base64.getEncoder().encodeToString(fdsBuilder.build().toByteArray());

      // Method0 is always present since operationCount >= 1. We benchmark Method0.
      return new GeneratedSpec(base64DescriptorSet, "Method0", PACKAGE_NAME + "." + SERVICE_NAME);
   }

   private DescriptorProto buildMessage(String name, Complexity complexity) {
      DescriptorProto.Builder msgBuilder = DescriptorProto.newBuilder().setName(name);
      for (int i = 1; i <= complexity.fieldsPerMessage; i++) {
         msgBuilder.addField(FieldDescriptorProto.newBuilder()
               .setName("field_" + i)
               .setNumber(i)
               .setType(FieldDescriptorProto.Type.TYPE_STRING)
               .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
               .setJsonName("field_" + i)
               .build());
      }
      return msgBuilder.build();
   }
}
