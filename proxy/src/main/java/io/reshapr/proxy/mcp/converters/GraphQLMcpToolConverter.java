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
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graphql.language.Description;
import graphql.language.Document;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.Type;
import graphql.parser.Parser;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.TypeInfo;
import graphql.schema.idl.TypeUtil;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.reshapr.json.JsonSchemaElements.*;

/**
 * Implementation of McpToolConverter for GraphQL services.
 * @author laurent
 */
public class GraphQLMcpToolConverter extends McpToolConverter {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final String CACHE_KEYS_PREFIX = "graphmcptc-";
   private static final String QUERY_OPERATION = "query";
   private static final String MUTATION_OPERATION = "mutation";
   private static final List<String> VALID_OPERATION_TYPES = Arrays.asList(QUERY_OPERATION, MUTATION_OPERATION);

   private static final String RELATION_PREFIX = "__relation_";

   private final ServiceEntry service;
   private final ArtifactEntry artifact;
   private final WorkCache workCache;
   private final ProxyService proxyService;

   private final ObjectMapper mapper;

   public GraphQLMcpToolConverter(ExpositionEntry exposition, WorkCache workCache,
                                  ObjectMapper mapper, ProxyService proxyService) {
      this.service = exposition.service();
      this.artifact = exposition.mainArtifact();
      this.workCache = workCache;
      this.mapper = mapper;
      this.proxyService = proxyService;
   }

   @Override
   public String getToolDescription(OperationEntry operation) {
      try {
         Document document = getDocument();
         FieldDefinition operationDefinition = getOperationDefinition(document, operation.name());

         if (operationDefinition != null) {
            // #1 Look for a description in the operation definition.
            if (operationDefinition.getDescription() != null) {
               return operationDefinition.getDescription().content.trim();
            } else if (operationDefinition.getComments() != null && !operationDefinition.getComments().isEmpty()) {
               // #2 Look for comments in the operation definition.
               StringBuilder result = new StringBuilder();
               operationDefinition.getComments().forEach(comment -> { result.append(comment.getContent()); });
               return result.toString().trim();
            }
         }
      } catch (Exception e) {
         logger.error("Exception while trying to get tool description", e);
      }
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
         String queryString = null;
         if (MUTATION_OPERATION.equalsIgnoreCase(operation.method())) {
            queryString = buildCompleteGraphQLMutation(operation, request).replace("\"", "\\\"");
         } else {
            queryString = buildCompleteGraphQLQuery(operation, request).replace("\"", "\\\"");
         }

         // De-serialize remaining arguments as the body variables.
         String body = "{\"variables\": " + mapper.writeValueAsString(request.arguments()) + ", \"query\": \"" + queryString + "\" }";

         // Execute the proxy service and return response.
         headers = sanitizeHttpHeaders(headers);
         BackendResponse backResponse = proxyService.callBackend(configuration, URI.create(configuration.backendEndpoint()),
               "POST", headers, body);

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
   private Document getDocument() throws Exception {
      String major = artifact.id();
      String minor = CACHE_KEYS_PREFIX + "document";
      Object value = workCache.get(major, minor);
      if (value instanceof Document document) {
         logger.debugf("Got a cached value of Document for service '%s'", service.id());
         return document;
      }

      // Compute new value to cache.
      logger.debugf("Need to build the Document for service '%s'", service.id());
      Document document = new Parser().parseDocument(artifact.content());
      workCache.set(major, minor, document);
      return document;
   }

   /** */
   private ObjectNode getInputSchemaNode(OperationEntry operation) {
      String major = artifact.id();
      String minor = CACHE_KEYS_PREFIX + operation.hashCode() + "-schema";
      Object value = workCache.get(major, minor);
      if (value instanceof ObjectNode inputSchemaNode) {
         logger.debugf("Got a cached value of InputSchemaNode for service '%s' and operation '%s", service.id(), operation.name());
         return inputSchemaNode;
      }

      // Compute new value to cache.
      logger.debugf("Need to build the InputSchemaNode for service '%s' and operation '%s'", service.id(), operation.name());
      ObjectNode inputSchemaNode = mapper.createObjectNode();
      ObjectNode schemaPropertiesNode = mapper.createObjectNode();
      ArrayNode requiredPropertiesNode = mapper.createArrayNode();

      // Initialize input schema with empty object.
      inputSchemaNode.put(JSON_SCHEMA_TYPE_ELEMENT, JSON_SCHEMA_OBJECT_TYPE);
      inputSchemaNode.set(JSON_SCHEMA_PROPERTIES_ELEMENT, schemaPropertiesNode);
      inputSchemaNode.set(JSON_SCHEMA_REQUIRED_ELEMENT, requiredPropertiesNode);
      inputSchemaNode.put(JSON_SCHEMA_ADD_PROPERTIES_ELEMENT, false);
      try {
         Document document = getDocument();
         FieldDefinition operationDefinition = getOperationDefinition(document, operation.name());

         if (operationDefinition != null && operationDefinition.getInputValueDefinitions() != null
               && !operationDefinition.getInputValueDefinitions().isEmpty()) {
            for (InputValueDefinition inputValueDef : operationDefinition.getInputValueDefinitions()) {
               visitProperty(document, inputValueDef.getName(), inputValueDef.getType(), inputValueDef.getDescription(),
                     schemaPropertiesNode, requiredPropertiesNode);
            }

            // We should also visit the output type to allow fetching relations.
            TypeInfo outputTypeInfo = TypeInfo.typeInfo(operationDefinition.getType());
            ObjectTypeDefinition typeDef = getTypeDefinition(document, outputTypeInfo.getName());
            if (typeDef != null) {
               for (FieldDefinition fd : typeDef.getFieldDefinitions()) {
                  // We only want to fetch relation => !(argument-less scalar or enum types).
                  if (!fd.getInputValueDefinitions().isEmpty()) {
                     visitRelationProperty(document, fd, schemaPropertiesNode, requiredPropertiesNode);
                  }
               }
            }
         }
      } catch (Exception e) {
         logger.error("Exception while trying to get input schema", e);
      }
      workCache.set(major, minor, inputSchemaNode);
      return inputSchemaNode;
   }

   /** Retrieve the correct operation definition in GraphQL schema document. */
   private FieldDefinition getOperationDefinition(Document document, String operationName) {
      for (ObjectTypeDefinition typeDefinition : document.getDefinitionsOfType(ObjectTypeDefinition.class)) {
         if (VALID_OPERATION_TYPES.contains(typeDefinition.getName().toLowerCase())) {
            for (FieldDefinition fieldDef : typeDefinition.getFieldDefinitions()) {
               if (fieldDef.getName().equals(operationName)) {
                  return fieldDef;
               }
            }
         }
      }
      return null;
   }

   /** Visit a property and add it to the input schema elements (properties and required properties). */
   private void visitProperty(Document graphqlDocument, String propertyName, Type propertyType, Description propertyDescription,
                              ObjectNode propertiesNode, ArrayNode requiredPropertiesNode) {
      if (TypeUtil.isNonNull(propertyType)) {
         requiredPropertiesNode.add(propertyName);
         propertyType = TypeUtil.unwrapOne(propertyType);
      }

      TypeInfo propertyTypeInfo = TypeInfo.typeInfo(propertyType);

      // Check if the field is a scalar.
      if (isScalarType(graphqlDocument, propertyTypeInfo.getName())) {
         ObjectNode propertySchemaNode = mapper.createObjectNode();
         switch (propertyTypeInfo.getName()) {
            case "Int" -> propertySchemaNode.put(JSON_SCHEMA_TYPE_ELEMENT, "integer");
            case "Float" -> propertySchemaNode.put(JSON_SCHEMA_TYPE_ELEMENT, "number");
            case "Boolean" -> propertySchemaNode.put(JSON_SCHEMA_TYPE_ELEMENT, "boolean");
            default -> propertySchemaNode.put(JSON_SCHEMA_TYPE_ELEMENT, "string");
         }

         if (propertyName != null) {
            propertiesNode.set(propertyName, propertySchemaNode);
         } else {
            // We may have no name if inside an array items.
            propertiesNode.setAll(propertySchemaNode);
         }

         if (propertyDescription != null) {
            propertySchemaNode.put("description", propertyDescription.content);
         }

      } else if (TypeUtil.isList(propertyType)) {
         // We must convert to a type array.
         ObjectNode arraySchemaNode = mapper.createObjectNode();
         ObjectNode subitemsNode = mapper.createObjectNode();

         arraySchemaNode.put(JSON_SCHEMA_TYPE_ELEMENT, JSON_SCHEMA_ARRAY_TYPE);
         arraySchemaNode.set(JSON_SCHEMA_ITEMS_ELEMENT, subitemsNode);
         propertiesNode.set(propertyName, arraySchemaNode);

         visitProperty(graphqlDocument, null, TypeUtil.unwrapAll(propertyType), null, subitemsNode, requiredPropertiesNode);
      } else {
         // We must check if it's an enum type.
         EnumTypeDefinition enumTypeDefinition = getEnumTypeDefinition(graphqlDocument, propertyTypeInfo.getName());
         if (enumTypeDefinition != null) {
            ObjectNode enumSchemaNode = mapper.createObjectNode();
            enumSchemaNode.put(JSON_SCHEMA_TYPE_ELEMENT, "string");
            ArrayNode enumValuesNode = mapper.createArrayNode();
            for (EnumValueDefinition valueDef : enumTypeDefinition.getEnumValueDefinitions()) {
               enumValuesNode.add(valueDef.getName());
            }
            enumSchemaNode.set(JSON_SCHEMA_ENUM_TYPE, enumValuesNode);
            if (propertyName != null) {
               propertiesNode.set(propertyName, enumSchemaNode);
            } else {
               // We may have no name if inside an array items.
               propertiesNode.setAll(enumSchemaNode);
            }
         } else {
            // So finally, it should be an object type.
            // Initialize a new subschema node we must visit to resolve all possible references.
            ObjectNode subschemaNode = mapper.createObjectNode();
            ObjectNode subpropertiesNode = mapper.createObjectNode();
            ArrayNode requiredSubpropertiesNode = mapper.createArrayNode();

            subschemaNode.put(JSON_SCHEMA_TYPE_ELEMENT, JSON_SCHEMA_OBJECT_TYPE);
            subschemaNode.set(JSON_SCHEMA_PROPERTIES_ELEMENT, subpropertiesNode);
            subschemaNode.set(JSON_SCHEMA_REQUIRED_ELEMENT, requiredSubpropertiesNode);
            subschemaNode.put(JSON_SCHEMA_ADD_PROPERTIES_ELEMENT, false);
            if (propertyName != null) {
               propertiesNode.set(propertyName, subschemaNode);
            } else {
               // We may have no name if inside an array items.
               propertiesNode.setAll(subschemaNode);
            }

            ObjectTypeDefinition typeDef = getTypeDefinition(graphqlDocument, propertyTypeInfo.getName());
            if (typeDef != null) {
               visitObjectTypeDefinition(graphqlDocument, typeDef, subpropertiesNode, requiredSubpropertiesNode);
            } else {
               // It could be an input type definition.
               InputObjectTypeDefinition inputTypeDef = getInputValueDefinition(graphqlDocument, propertyTypeInfo.getName());
               if (inputTypeDef != null) {
                  visitInputObjectTypeDefinition(graphqlDocument, inputTypeDef, subpropertiesNode, requiredSubpropertiesNode);
               } else {
                  logger.warnf("Cannot find type definition for '%s'", propertyTypeInfo.getName());
               }
            }
         }
      }
   }

   /** Visit a relation property and add it to the input schema elements (properties and required properties). */
   private void visitRelationProperty(Document graphqlDocument, FieldDefinition relationDef,
                                      ObjectNode propertiesNode, ArrayNode requiredPropertiesNode) {

      // Prepare its properties schema nodes.
      ObjectNode propertySchemaNode = mapper.createObjectNode();
      ObjectNode subpropertiesNode = mapper.createObjectNode();
      ArrayNode requiredSubpropertiesNode = mapper.createArrayNode();

      // Add this relation property with a special prefix.
      propertiesNode.set(RELATION_PREFIX + relationDef.getName(), propertySchemaNode);

      // Now take care of its properties
      propertySchemaNode.put(JSON_SCHEMA_TYPE_ELEMENT, "object");
      propertySchemaNode.set(JSON_SCHEMA_PROPERTIES_ELEMENT, subpropertiesNode);
      propertySchemaNode.set(JSON_SCHEMA_REQUIRED_ELEMENT, requiredSubpropertiesNode);
      propertySchemaNode.put(JSON_SCHEMA_ADD_PROPERTIES_ELEMENT, false);
      if (relationDef.getDescription() != null) {
         propertySchemaNode.put("description", relationDef.getDescription().content);
      }

      for (InputValueDefinition inputValueDefinition : relationDef.getInputValueDefinitions()) {
         TypeInfo inputTypeInfo = TypeInfo.typeInfo(inputValueDefinition.getType());
         // We only want to fetch argument-less scalar or enum types.
         if (isScalarType(graphqlDocument, inputTypeInfo.getName())) {
            visitProperty(graphqlDocument, inputValueDefinition.getName(), inputValueDefinition.getType(),
                  inputValueDefinition.getDescription(), subpropertiesNode, requiredSubpropertiesNode);
         }
      }
   }

   /** Check if the type is a scalar type. */
   private boolean isScalarType(Document graphqlDocument, String typeName) {
      return ScalarInfo.isGraphqlSpecifiedScalar(typeName)
            || graphqlDocument.getDefinitionsOfType(ScalarTypeDefinition.class).stream()
            .anyMatch(scalarTypeDefinition -> scalarTypeDefinition.getName().equals(typeName));
   }

   /** Retrieve the correct type definition in GraphQL schema document. */
   private ObjectTypeDefinition getTypeDefinition(Document graphqlDocument, String typeName) {
      for (ObjectTypeDefinition typeDefinition : graphqlDocument.getDefinitionsOfType(ObjectTypeDefinition.class)) {
         if (typeDefinition.getName().equals(typeName)) {
            return typeDefinition;
         }
      }
      return null;
   }

   /** Retrieve the correct enum type definition in GraphQL schema document. */
   private EnumTypeDefinition getEnumTypeDefinition(Document graphqlDocument, String typeName) {
      for (EnumTypeDefinition enumTypeDefinition : graphqlDocument.getDefinitionsOfType(EnumTypeDefinition.class)) {
         if (enumTypeDefinition.getName().equals(typeName)) {
            return enumTypeDefinition;
         }
      }
      return null;
   }

   /** Retrieve the correct input value definition in GraphQL schema document. */
   private InputObjectTypeDefinition getInputValueDefinition(Document graphqlDocument, String typeName) {
      for (InputObjectTypeDefinition inputObjectTypeDefinition : graphqlDocument
            .getDefinitionsOfType(InputObjectTypeDefinition.class)) {
         if (inputObjectTypeDefinition.getName().equals(typeName)) {
            return inputObjectTypeDefinition;
         }
      }
      return null;
   }

   /** Retrieve the correct field definition in Object type definition. */
   private FieldDefinition getFieldDefinition(ObjectTypeDefinition objectTypeDef, String typeName) {
      for (FieldDefinition fd : objectTypeDef.getFieldDefinitions()) {
         if (fd.getName().equals(typeName)) {
            return fd;
         }
      }
      return null;
   }

   /** Visit a GraphQL object type definition and add its properties to the input schema. */
   private void visitObjectTypeDefinition(Document graphqlDocument, ObjectTypeDefinition typeDefinition, ObjectNode propertiesNode,
                                          ArrayNode requiredPropertiesNode) {
      for (FieldDefinition fieldDef : typeDefinition.getFieldDefinitions()) {
         visitProperty(graphqlDocument, fieldDef.getName(), fieldDef.getType(), fieldDef.getDescription(), propertiesNode, requiredPropertiesNode);
      }
   }

   /** Visit a GraphQL input object type definition and add its properties to the input schema. */
   private void visitInputObjectTypeDefinition(Document graphqlDocument, InputObjectTypeDefinition typeDefinition, ObjectNode propertiesNode,
                                               ArrayNode requiredPropertiesNode) {
      for (InputValueDefinition fieldDef : typeDefinition.getInputValueDefinitions()) {
         visitProperty(graphqlDocument, fieldDef.getName(), fieldDef.getType(), fieldDef.getDescription(), propertiesNode, requiredPropertiesNode);
      }
   }

   /** Build a complete GraphQL query with all the fields to fetch. */
   private String buildCompleteGraphQLQuery(OperationEntry operation, McpSchema.SimpleRequest request) throws Exception {
      // Extract the type information from the operation.
      Document document = getDocument();
      FieldDefinition operationDefinition = getOperationDefinition(document, operation.name());

      StringBuilder queryBuilder = new StringBuilder();
      queryBuilder.append("query {").append("  ").append(operation.name());

      // Append the arguments to the operation.
      addInputArguments(document, operationDefinition, request.arguments(), queryBuilder);

      queryBuilder.append("{\\n");

      // Manage output fields to fetch.
      addOutputFieldsToFetch(document, operationDefinition, request, queryBuilder);

      // Finalize query before returning it.
      queryBuilder.append("}}");
      return queryBuilder.toString();
   }

   /** Build a complete GraphQL mutation with all the fields to fetch. */
   private String buildCompleteGraphQLMutation(OperationEntry operation, McpSchema.SimpleRequest request) throws Exception {
      // Extract the type information from the operation.
      Document document = getDocument();
      FieldDefinition operationDefinition = getOperationDefinition(document, operation.name());

      StringBuilder mutationBuilder = new StringBuilder();
      mutationBuilder.append("mutation {").append("  ").append(operation.name());

      // Append the arguments to the operation.
      addInputArguments(document, operationDefinition, request.arguments(), mutationBuilder);

      mutationBuilder.append("{\\n");

      // Manage output fields to fetch.
      addOutputFieldsToFetch(document, operationDefinition, request, mutationBuilder);

      // Finalize query before returning it.
      mutationBuilder.append("}}");
      return mutationBuilder.toString();
   }

   /** Add input arguments to the operation if any. */
   private void addInputArguments(Document graphqlDocument, FieldDefinition operationDefinition, Map<String, Object> arguments, StringBuilder builder) {
      List<InputValueDefinition> inputValueDefinitions = operationDefinition.getInputValueDefinitions();

      if (!inputValueDefinitions.isEmpty() && !arguments.isEmpty()) {
         builder.append("(");
         String result = inputValueDefinitions.stream()
               .filter(input -> arguments.containsKey(input.getName()))
               .map(input -> input.getName() + ": " + serializeGraphQLArgument(
                     graphqlDocument,
                     arguments.get(input.getName()),
                     TypeInfo.typeInfo(input.getType())
               ))
               .collect(Collectors.joining(", ", "", ""));
         builder.append(result).append(")");
      }
   }

   /** Serialize a GraphQL argument value according to its type info. */
   private String serializeGraphQLArgument(Document graphqlDocument, Object argumentValue, TypeInfo typeInfo) {
      StringBuilder sb = new StringBuilder();

      if (argumentValue instanceof Map<?, ?> map) {
         sb.append("{");
         InputObjectTypeDefinition typeDefinition = getInputValueDefinition(graphqlDocument, typeInfo.getName());
         String result = typeDefinition.getInputValueDefinitions().stream()
               .filter(ivd -> map.containsKey(ivd.getName()))
               .map(ivd ->
                     ivd.getName() + ": " + serializeGraphQLArgument(graphqlDocument, map.get(ivd.getName()), TypeInfo.typeInfo(ivd.getType()))
               )
               .collect(Collectors.joining(", ", "", ""));
         sb.append(result).append("}");

      } else if (argumentValue instanceof List<?> list) {
         sb.append("[");
         TypeInfo elementType = TypeInfo.typeInfo(TypeUtil.unwrapAll(typeInfo.getRawType()));
         String result = list.stream()
               .map(item -> serializeGraphQLArgument(graphqlDocument, item, elementType))
               .collect(Collectors.joining(", ", "", ""));
         sb.append(result).append("]");
      } else if (argumentValue instanceof String) {
         // We must check if it's an enum type.
         EnumTypeDefinition enumTypeDefinition = getEnumTypeDefinition(graphqlDocument, typeInfo.getName());
         if (enumTypeDefinition != null) {
            sb.append(argumentValue);
         } else {
            switch (typeInfo.getName()) {
               case "Int", "Float", "Boolean" -> sb.append(argumentValue);
               default -> sb.append("\"").append(argumentValue).append("\"");
            }
         }
      } else {
         sb.append(argumentValue);
      }
      return sb.toString();
   }

   /** Add output fields to fetch in the operation. */
   private void addOutputFieldsToFetch(Document graphqlDocument, FieldDefinition operationDefinition,
                                       McpSchema.SimpleRequest request, StringBuilder builder) {
      Type operationOutputType = operationDefinition.getType();
      TypeInfo operationOutputTypeInfo = TypeInfo.typeInfo(operationOutputType);
      ObjectTypeDefinition typeDef = getTypeDefinition(graphqlDocument, operationOutputTypeInfo.getName());

      // Fetch all argument-less fields.
      if (typeDef != null) {
         for (FieldDefinition fd : typeDef.getFieldDefinitions()) {
            addFieldDefinitionPropertiesToFetch(graphqlDocument, fd, builder, new HashSet<>());
         }
      }

      // Also add relation fields if any.
      for (String requestField : request.arguments().keySet()) {
         if (requestField.startsWith(RELATION_PREFIX)) {
            // Extract relation field name and get its definition.
            String relationFieldName = requestField.substring(RELATION_PREFIX.length());
            FieldDefinition relationFieldDef = getFieldDefinition(typeDef, relationFieldName);

            if (relationFieldDef != null) {
               // Examine this relation arguments.
               Object relationArgs = request.arguments().get(requestField);
               if (relationArgs instanceof Map<?, ?> inputMap) {

                  builder.append(relationFieldName).append("(");
                  String result = relationFieldDef.getInputValueDefinitions().stream()
                        .filter(input -> inputMap.containsKey(input.getName()))
                        .map(input -> input.getName() + ": " + serializeGraphQLArgument(
                              graphqlDocument,
                              inputMap.get(input.getName()),
                              TypeInfo.typeInfo(input.getType())
                        ))
                        .collect(Collectors.joining(", ", "", ""));

                  builder.append(result).append(")");
               }

               TypeInfo relationFieldTypeInfo = TypeInfo.typeInfo(relationFieldDef.getType());
               ObjectTypeDefinition relationTypeDef = getTypeDefinition(graphqlDocument, relationFieldTypeInfo.getName());
               if (relationTypeDef != null) {
                  // Start fetching its properties and initialize a set to track and avoid cycles.
                  Set<String> fetchingTypes = new HashSet<>();
                  fetchingTypes.add(relationTypeDef.getName());

                  builder.append(" {\\n");
                  for (FieldDefinition fd : relationTypeDef.getFieldDefinitions()) {
                     addFieldDefinitionPropertiesToFetch(graphqlDocument, fd, builder, fetchingTypes);
                  }
                  builder.append("}\\n");
               }
            }
         }
      }
   }

   /** Add field definition properties to fetch in the operation - only if it's an argument-less one. */
   private void addFieldDefinitionPropertiesToFetch(Document graphqlDocument, FieldDefinition fd,
                                                    StringBuilder builder, Set<String> fetchingTypes) {
      TypeInfo fdTypeInfo = TypeInfo.typeInfo(fd.getType());

      // We only want to fetch argument-less types.
      if (fd.getInputValueDefinitions().isEmpty()) {
         if (isScalarType(graphqlDocument, fdTypeInfo.getName())) {
            builder.append(fd.getName()).append("\\n");
         }
         else {
            // We must check if it's an enum type.
            EnumTypeDefinition enumTypeDefinition = getEnumTypeDefinition(graphqlDocument, fdTypeInfo.getName());
            if (enumTypeDefinition != null) {
               builder.append(fd.getName()).append("\\n");
            } else {
               // So finally, it should be an array or an object type (both are working the same way here).
               tryAddingObjectDefinitionPropertiesToFetch(graphqlDocument, fd, builder, fetchingTypes);
            }
         }
      }
   }

   /** Try adding object definition properties to fetch in the operation, avoiding infinite recursion. */
   private void tryAddingObjectDefinitionPropertiesToFetch(Document graphqlDocument, FieldDefinition objectFieldDef,
                                                StringBuilder builder, Set<String> fetchingTypes) {

      TypeInfo objectTypeInfo = TypeInfo.typeInfo(objectFieldDef.getType());

      if (fetchingTypes.contains(objectTypeInfo.getName())) {
         // We have already fetched this type, so we can skip it to avoid infinite recursion.
         logger.tracef("Skipping already fetched type '%s'", objectTypeInfo.getName());
      } else {
         // Add the new object type to the set of resolving types.
         fetchingTypes.add(objectTypeInfo.getName());

         ObjectTypeDefinition typeDef = getTypeDefinition(graphqlDocument, objectTypeInfo.getName());
         if (typeDef != null) {
            builder.append(objectFieldDef.getName()).append("{\\n");
            for (FieldDefinition subFd : typeDef.getFieldDefinitions()) {
               addFieldDefinitionPropertiesToFetch(graphqlDocument, subFd, builder, fetchingTypes);
            }
            builder.append("}\\n");
         }

         // Remove the current type from the set of resolving types.
         fetchingTypes.remove(objectTypeInfo.getName());
      }
   }
}
