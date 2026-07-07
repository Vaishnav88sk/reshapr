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
package io.reshapr.proxy.mcp.converters;

import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.GrpcProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.util.GrpcUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.FieldBehaviorProto;
import com.google.api.FieldBehavior;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.reshapr.json.JsonSchemaElements.*;
/**
 * Implementation of McpToolConverter for Grpc services.
 * @author laurent
 */
public class GrpcMcpToolConverter extends McpToolConverter {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final String CACHE_KEYS_PREFIX = "grpcmcptc-";

   private static final String FIELD_BEHAVIOR_MARKER = String.valueOf(FieldBehaviorProto.FIELD_BEHAVIOR_FIELD_NUMBER);

   private static final ExtensionRegistry registry = ExtensionRegistry.newInstance();

   private static final Map<String, WellKnownTypesDescriptor> wellKnownTypesDescriptors = buildWellKnownTypesDescriptors();

   static {
      // Register the FieldBehaviorProto extension to be able to read field behaviors.
      FieldBehaviorProto.registerAllExtensions(registry);
   }

   private final ServiceEntry service;
   private final ArtifactEntry artifact;
   private final WorkCache workCache;
   private final GrpcProxyService proxyService;

   private final ObjectMapper mapper;

   public GrpcMcpToolConverter(ExpositionEntry exposition, WorkCache workCache,
                               ObjectMapper mapper, GrpcProxyService proxyService) {
      this.service = exposition.service();
      this.artifact = exposition.mainArtifact();
      this.workCache = workCache;
      this.mapper = mapper;
      this.proxyService = proxyService;
   }

   @Override
   public String getToolDescription(OperationEntry operation) {
      return null;
   }

   @Override
   public McpSchema.JsonSchema getInputSchema(OperationEntry operation) {
      ObjectNode inputSchemaNode = mapper.createObjectNode();
      try {
         inputSchemaNode = getInputSchemaNode(operation);
      } catch (Exception e) {
         logger.error("Exception while trying to get input schema", e);
      }
      return mapper.convertValue(inputSchemaNode, McpSchema.JsonSchema.class);
   }

   @Override
   public Response getCallResponse(OperationEntry operation, ConfigurationEntry configuration, McpSchema.SimpleRequest request, Map<String, List<String>> headers) {

      try {
         Descriptors.ServiceDescriptor sd = getServiceDescriptor();
         Descriptors.MethodDescriptor md = sd.findMethodByName(operation.name());

         // Serialize the request arguments as the request body.
         String body = mapper.writeValueAsString(request.arguments());

         // Execute the proxy service and return response.
         headers = sanitizeHttpHeaders(headers);
         BackendResponse backResponse = proxyService.callBackend(configuration, md, headers, body);

         // Build a MCP Response from the result.
         return new Response(extractResponseContent(backResponse), backResponse.status() >= 400);
      } catch (Exception e) {
         logger.error("Exception while processing the MCP call invocation", e);
      }
      return null;
   }

   @Override
   public Uni<Response> getCallResponseUni(OperationEntry operation, McpSchema.SimpleRequest request, Map<String, List<String>> headers) {
      return null;
   }

   /** */
   private Descriptors.ServiceDescriptor getServiceDescriptor() {
      String major = artifact.id();
      String minor = CACHE_KEYS_PREFIX + "sd";
      Object value = workCache.get(major, minor);
      if (value instanceof Descriptors.ServiceDescriptor serviceDescriptor) {
         logger.debugf("Got a cached value of ServiceDescriptor for service '%s'", service.id());
         return serviceDescriptor;
      }
      // Compute new value to cache.
      logger.debugf("Need to build the ServiceDescriptor for service '%s'", service.id());

      Descriptors.ServiceDescriptor sd = null;
      try {
         sd = GrpcUtil.findServiceDescriptor(artifact.content(), service.name());
         workCache.set(major, minor, sd);
      } catch (Exception e) {
         logger.errorf("Exception while trying to get service descriptor for service '%s'", service.id(), e);
      }
      return sd;
   }

   /** */
   private ObjectNode getInputSchemaNode(OperationEntry operation) {
      String major = artifact.id();
      String minor = CACHE_KEYS_PREFIX + operation.hashCode() + "-schema";
      Object value = workCache.get(major, minor);
      if (value instanceof ObjectNode inputSchemaNode) {
         logger.debugf("Got a cached value of InputSchemaNode for service '%s' and operation '%s'", service.id(), operation.name());
         return inputSchemaNode;
      }

      // Compute new value to cache.
      logger.debugf("Need to build the InputSchemaNode for service '%s' and operation '%s'", service.id(), operation.name());
      ObjectNode inputSchemaNode = mapper.createObjectNode();
      ObjectNode schemaPropertiesNode = mapper.createObjectNode();
      ArrayNode requiredPropertiesNode = mapper.createArrayNode();

      // Initialize input schema with empty object.
      inputSchemaNode.put("type", "object");
      inputSchemaNode.set(JSON_SCHEMA_PROPERTIES_ELEMENT, schemaPropertiesNode);
      inputSchemaNode.set(JSON_SCHEMA_REQUIRED_ELEMENT, requiredPropertiesNode);
      inputSchemaNode.put(JSON_SCHEMA_ADD_PROPERTIES_ELEMENT, false);
      try {
         Descriptors.ServiceDescriptor sd = getServiceDescriptor();

         Descriptors.MethodDescriptor md = sd.findMethodByName(operation.name());
         if (md.getInputType() != null) {
            Set<String> resolvingTypes = new HashSet<>();

            // Visit the input type descriptor.
            visitDescriptor(md.getInputType(), schemaPropertiesNode, requiredPropertiesNode, resolvingTypes);
         }
      } catch (Exception e) {
         logger.error("Exception while trying to get input schema", e);
      }
      workCache.set(major, minor, inputSchemaNode);
      return inputSchemaNode;
   }

   /** Visit a protobuf message descriptor and extract its properties. */
   private void visitDescriptor(Descriptors.Descriptor inputType, ObjectNode propertiesNode,
                                ArrayNode requiredPropertiesNode, Set<String> resolvingTypes) {

      if (resolvingTypes.contains(inputType.getFullName())) {
         // We have already visited this type, so we can skip it to avoid infinite recursion.
         logger.debugf("Skipping already visited type '%s'", inputType.getFullName());
         return;
      }
      // Add the current type to the set of resolving types.
      resolvingTypes.add(inputType.getFullName());

      WellKnownTypesDescriptor specialTypeDescriptor = wellKnownTypesDescriptors.get(inputType.getFullName());
      if (specialTypeDescriptor != null) {
         // This is a well-known type that we must handle in a special way.
         specialTypeDescriptor.describe(propertiesNode);
         return;
      }

      // If standard way, then visit each field of the message.
      for (Descriptors.FieldDescriptor fd : inputType.getFields()) {
         // Get the field name and type.
         String fieldName = fd.getName();

         // Check if the field is required.
         if (fd.isRequired()) {
            requiredPropertiesNode.add(fieldName);
         } else if (fd.getOptions().toString().contains(FIELD_BEHAVIOR_MARKER)) {
            // This is the marker of a [(google.api.field_behavior)] annotated field.
            if (isAnnotatedRequired(fd)) {
               // If it is annotated as required, we must add it to the required properties.
               requiredPropertiesNode.add(fieldName);
            }
         }

         // Check if the field is an array/a repeated field.
         if (fd.isRepeated()) {
            // We must convert to a type array.
            ObjectNode arraySchemaNode = mapper.createObjectNode();

            arraySchemaNode.put("type", "array");
            propertiesNode.set(fieldName, arraySchemaNode);

            if (isMessageType(fd.getType())) {
               ObjectNode subschemaNode = mapper.createObjectNode();
               ObjectNode subitemsNode = mapper.createObjectNode();
               ArrayNode requiredSubitemsPropertiesNode = mapper.createArrayNode();

               visitDescriptor(fd.getMessageType(), subitemsNode, requiredSubitemsPropertiesNode, resolvingTypes);

               // Add the required properties to the subschema.
               subschemaNode.put("type", "object");
               subschemaNode.set(JSON_SCHEMA_PROPERTIES_ELEMENT, subitemsNode);

               // Add the items definition to the current property.
               arraySchemaNode.set(JSON_SCHEMA_ITEMS_ELEMENT, subschemaNode);
               propertiesNode.set(fieldName, arraySchemaNode);
            } else if (isEnumType(fd.getType())) {
               arraySchemaNode.set(JSON_SCHEMA_ITEMS_ELEMENT, buildEnumTypeNode(fd));
            } else if (isScalarType(fd.getType())) {
               arraySchemaNode.set(JSON_SCHEMA_ITEMS_ELEMENT, buildScalarTypeNode(fd));
            }
         } else {
            // Check if the field is an object/message type.
            if (isMessageType(fd.getType())) {
               // Initialize a new subschema node we must visit to resolve message fields.
               ObjectNode subschemaNode = mapper.createObjectNode();

               // Check if there's a special handling if it's a well-known type.
               specialTypeDescriptor = wellKnownTypesDescriptors.get(fd.getMessageType().getFullName());
               if (specialTypeDescriptor != null) {
                  specialTypeDescriptor.describe(subschemaNode);
                  propertiesNode.set(fieldName, subschemaNode);
               } else {
                  // This is a standard message type, we must visit it.
                  ObjectNode subpropertiesNode = mapper.createObjectNode();
                  ArrayNode requiredSubpropertiesNode = mapper.createArrayNode();

                  subschemaNode.put("type", "object");
                  subschemaNode.set(JSON_SCHEMA_PROPERTIES_ELEMENT, subpropertiesNode);
                  subschemaNode.set(JSON_SCHEMA_REQUIRED_ELEMENT, requiredSubpropertiesNode);
                  subschemaNode.put(JSON_SCHEMA_ADD_PROPERTIES_ELEMENT, false);
                  propertiesNode.set(fieldName, subschemaNode);

                  visitDescriptor(fd.getMessageType(), subpropertiesNode, requiredSubpropertiesNode, resolvingTypes);
               }
            } else if (isEnumType(fd.getType())) {
               propertiesNode.set(fieldName, buildEnumTypeNode(fd));
            } else if (isScalarType(fd.getType())) {
               propertiesNode.set(fieldName, buildScalarTypeNode(fd));
            }
         }
      }

      // Remove the current type from the set of resolving types.
      resolvingTypes.remove(inputType.getFullName());
   }

   /** Check <code>[(google.api.field_behavior) = REQUIRED];</code> annotation on field. */
   private boolean isAnnotatedRequired(Descriptors.FieldDescriptor fd) {
      // Check if the field is required.
      try {
         DescriptorProtos.FieldOptions parsedOptions = parsedOptions = DescriptorProtos.FieldOptions.parseFrom(
               fd.toProto().getOptions().toByteString(), registry
         );
         var fieldBehaviors = parsedOptions.getExtension(FieldBehaviorProto.fieldBehavior);
         for (FieldBehavior behavior : fieldBehaviors) {
            if (behavior == FieldBehavior.REQUIRED) {
               return true;
            }
         }
      } catch (InvalidProtocolBufferException e) {
         logger.warnf("Failed to parse field options for field %s" + fd.getName(), e);
      }
      return false;
   }

   /** Defines is a protobuf message field type is an message type. */
   private static boolean isMessageType(Descriptors.FieldDescriptor.Type fieldType) {
      return fieldType == Descriptors.FieldDescriptor.Type.MESSAGE
            || fieldType == Descriptors.FieldDescriptor.Type.GROUP;
   }

   /** Defines is a protobuf message field type is an enum type. */
   private static boolean isEnumType(Descriptors.FieldDescriptor.Type fieldType) {
      return fieldType == Descriptors.FieldDescriptor.Type.ENUM;
   }

   /** Defines is a protobuf message field type is a scalar type. */
   private static boolean isScalarType(Descriptors.FieldDescriptor.Type fieldType) {
      return fieldType != Descriptors.FieldDescriptor.Type.MESSAGE
            && fieldType != Descriptors.FieldDescriptor.Type.GROUP
            && fieldType != Descriptors.FieldDescriptor.Type.BYTES;
   }

   /** Build a leaf node that is an enum type. */
   private ObjectNode buildEnumTypeNode(Descriptors.FieldDescriptor fd) {
      ObjectNode subschemaNode = mapper.createObjectNode();
      ArrayNode enumsArrayNode = mapper.createArrayNode();

      subschemaNode.put("type", toMcpJsonType(fd.getType()));
      subschemaNode.set("enum", enumsArrayNode);

      // Put the enum values in enum array.
      for (Descriptors.EnumValueDescriptor evd : fd.getEnumType().getValues()) {
         enumsArrayNode.add(evd.getName());
      }
      return subschemaNode;
   }

   /** Build a leaf node that is a scalar type. */
   private ObjectNode buildScalarTypeNode(Descriptors.FieldDescriptor fd) {
      ObjectNode subschemaNode = mapper.createObjectNode();
      subschemaNode.put("type", toMcpJsonType(fd.getType()));
      return subschemaNode;
   }

   /** Convert a scalar Field Protobuf type into a MCP Json compatible one. */
   private static String toMcpJsonType(Descriptors.FieldDescriptor.Type fieldType) {
      switch (fieldType) {
         case DOUBLE:
         case FLOAT:
         case INT64:
         case UINT64:
         case INT32:
         case FIXED64:
         case FIXED32:
            return "number";
         case BOOL:
            return "boolean";
         case STRING:
         case BYTES:
         default:
            return "string";
      }
   }

   private interface WellKnownTypesDescriptor {
      void describe(ObjectNode propertyNode);
   }

   private static Map<String, WellKnownTypesDescriptor> buildWellKnownTypesDescriptors() {
      return Map.of(
            "google.protobuf.Timestamp", (propertyNode) -> {
               propertyNode.put("type", "string");
               propertyNode.put("description", "Timestamp in RFC3339 UTC Zulu format, with nanosecond resolution and up to nine fractional digits");
               propertyNode.put("format", "date-time");
            },
            "google.protobuf.Duration", (propertyNode) -> {
               propertyNode.put("type", "string");
               propertyNode.put("description", "A String that ends in s to indicate seconds and is preceded by the number of seconds, with nanoseconds expressed as fractional seconds.");
               propertyNode.put("pattern", "^-?([0-9]+\\.[0-9]{1,9}|[0-9]+)s$");
            }
      );
   }
}
