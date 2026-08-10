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
package io.reshapr.benchmarks.openapi;

import io.reshapr.json.JsonNodeConverter;
import io.reshapr.json.ObjectMapperFactory;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.util.URIBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.reshapr.json.JsonSchemaElements.*;

/**
 * Optimized variant of {@code OpenAPIMcpToolConverter}.
 *
 * <p><b>Only difference with the original:</b> the parsed content of attached (external) artifacts
 * is stored in the shared {@link WorkCache} — keyed by {@code attachedArtifact.id()} — instead of a
 * per-instance {@code attachedArtifactsContent} map. Since production code rebuilds a converter on
 * every tool call ({@code ToolCallExecutor.buildMcpToolConverter()}), the per-instance map caused the
 * external YAML/JSON file to be re-parsed on every single call. With this variant it is parsed once
 * per artifact and shared across converter instances and expositions, exactly like the main artifact.</p>
 *
 * <p>Everything else is kept strictly identical to the original implementation.</p>
 *
 * @author laurent
 */
public class OptimizedOpenAPIMcpToolConverter extends McpToolConverter {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final String CACHE_KEYS_PREFIX = "oapimcptc-";
   private static final String OPEN_API_PATHS_ELEMENT = "/paths/";

   private final ServiceEntry service;
   private final ArtifactEntry artifact;
   private final WorkCache workCache;
   private final ProxyService proxyService;
   private final ObjectMapper mapper;

   private final List<ArtifactEntry> attachedArtifacts;

   private ObjectMapper yamlMapper;

   public OptimizedOpenAPIMcpToolConverter(ExpositionEntry exposition, WorkCache workCache, ObjectMapper mapper, ProxyService proxyService) {
      this.service = exposition.service();
      this.artifact = exposition.mainArtifact();
      this.attachedArtifacts = exposition.attachedArtifacts();
      this.workCache = workCache;
      this.mapper = mapper;
      this.proxyService = proxyService;
   }

   @Override
   public String getToolName(OperationEntry operation) {
      // Anthropic Claude tools must respect ^[a-zA-Z0-9_-]{1,64}$ regular expression that doesn't match with our URI.
      return operation.name().replace(" ", "_").replace("/", "_").replace("{", "").replace("}", "")
            .replace("__", "_").toLowerCase();
   }

   @Override
   public String getToolDescription(OperationEntry operation) {
      try {
         JsonNode schemaNode = getSchemaNode();

         // Extract JsonNode corresponding to operation.
         JsonNode operationNode = getOperationNode(schemaNode, operation);
         if (operationNode != null) {
            operationNode = followRefIfAny(schemaNode, operationNode);

            if (operationNode.has("description")) {
               return operationNode.path("description").asText();
            }
            if (operationNode.has("summary")) {
               return operationNode.path("summary").asText();
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
   public Response getCallResponse(OperationEntry operation, ConfigurationEntry configuration, McpSchema.SimpleRequest request,
                                   Map<String, List<String>> headers) {

      String queryString = "";
      String resourcePath = operation.name().split(" ")[1].trim();
      Map<String, String> pathParams = new HashMap<>();
      Map<String, String> queryParams = new HashMap<>();

      // Unwrap the request parameters and headers and remove them from request.
      try {
         JsonNode schemaNode = getSchemaNode();

         // Extract JsonNode corresponding to request parameters.
         JsonNode paramsNode = getParametersNode(schemaNode, operation);
         if (paramsNode != null && !paramsNode.isMissingNode()) {
            paramsNode = followRefIfAny(schemaNode, paramsNode);

            Iterator<JsonNode> parameters = paramsNode.elements();
            while (parameters.hasNext()) {
               JsonNode parameter = followRefIfAny(schemaNode, parameters.next());
               String paramName = parameter.path("name").asText();
               String paramIn = parameter.path("in").asText();

               if (request.arguments().containsKey(paramName)) {
                  Object param = request.arguments().remove(paramName);
                  String paramValue = param.toString();
                  if ("path".equals(paramIn)) {
                     pathParams.put(paramName, paramValue);
                  } else if ("query".equals(paramIn)) {

                     String paramType = parameter.path("schema").path(JSON_SCHEMA_TYPE_ELEMENT).asText();
                     // If parameter is an array, we expect a JSON array in input.
                     if (JSON_SCHEMA_ARRAY_TYPE.equals(paramType)) {
                        // We need to convert it to comma separated values for the backend call.
                        List<String> values = mapper.convertValue(param, new TypeReference<List<String>>() {});
                        paramValue = String.join(",", values);
                     }
                     queryParams.put(paramName, paramValue);
                  } else if ("header".equals(paramIn)) {
                     headers.put(paramName, List.of(paramValue));
                  }
               }
            }
         }
      } catch (Exception e) {
         logger.error("Exception while extracting URI parameters from arguments", e);
      }

      String backendUrl = configuration.backendEndpoint();

      // Re-build the resource path with parameters if needed.
      if (operation.name().contains("{")) {
         resourcePath = URIBuilder.buildURIFromPattern(resourcePath, pathParams);
      }
      backendUrl = backendUrl.replaceFirst("/$", "") + resourcePath;

      // Re-build the query string with parameters if needed.
      if (!queryParams.isEmpty()) {
         queryString = URIBuilder.buildURIFromPattern("", queryParams);
         if (queryString.startsWith("?")) {
            queryString = queryString.substring(1);
         }
         backendUrl += "?" + queryString;
      }

      try {
         // Serialize remaining arguments as the request body.
         String body = mapper.writeValueAsString(request.arguments());

         // Execute the proxy service and return response.
         headers = sanitizeHttpHeaders(headers);
         BackendResponse backResponse = proxyService.callBackend(configuration, URI.create(backendUrl),
               operation.method(), headers, body);

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

   /** Get the root schema node for the OpenAPI artifact. */
   private JsonNode getSchemaNode() throws Exception {
      String major = artifact.id();
      String minor = CACHE_KEYS_PREFIX + "schema";
      Object value = workCache.get(major, minor);
      if (value instanceof JsonNode schemaNode) {
         logger.tracef("Got a cached value of SchemaNode for service '%s'", service.id());
         return schemaNode;
      }

      // Compute new value to cache.
      logger.debugf("Need to build the SchemaNode for service '%s'", service.id());
      JsonNode schemaNode = JsonNodeConverter.getJsonNode(artifact.content());
      workCache.set(major, minor, schemaNode);
      return schemaNode;
   }

   /** Get the operation node for this operation. */
   private JsonNode getOperationNode(JsonNode schemaNode, OperationEntry operation) throws Exception {
      String verb = operation.name().split(" ")[0].toLowerCase();
      String path = operation.name().split(" ")[1].trim();

      String operationPointer = OPEN_API_PATHS_ELEMENT + path.replace("/", "~1") + "/" + verb;
      JsonNode operationNode = schemaNode.at(operationPointer);
      if (!operationNode.isMissingNode()) {
         return followRefIfAny(schemaNode, operationNode);
      }

      // Worst case: follow all segment independently, starting from paths.
      return traversePath(schemaNode, schemaNode.get("paths"), List.of(path, verb));
   }

   /** */
   private ObjectNode getInputSchemaNode(OperationEntry operation) {
      String major = artifact.id();
      String minor = CACHE_KEYS_PREFIX + operation.hashCode() + "-schema";
      Object value = workCache.get(major, minor);
      if (value instanceof ObjectNode inputSchemaNode) {
         logger.tracef("Got a cached value of InputSchemaNode for service '%s' and operation '%s'", service.id(), operation.name());
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
         JsonNode schemaNode = getSchemaNode();

         // Extract JsonNode corresponding to request body.
         JsonNode requestSchemaNode = getRequestBodySchemaNode(schemaNode, operation);
         if (requestSchemaNode != null && !requestSchemaNode.isMissingNode()) {
            requestSchemaNode = followRefIfAny(schemaNode, requestSchemaNode);

            // May be null if not resolved.
            if (requestSchemaNode != null) {
               Set<String> resolvingTypes = new HashSet<>();
               visitNodeWithProperties(schemaNode, requestSchemaNode, schemaPropertiesNode, requiredPropertiesNode, resolvingTypes);
            }
         }

         // Add parameters to input schema.
         JsonNode paramsNode = getParametersNode(schemaNode, operation);
         if (paramsNode != null && !paramsNode.isMissingNode()) {
            paramsNode = followRefIfAny(schemaNode, paramsNode);

            Iterator<JsonNode> parameters = paramsNode.elements();
            while (parameters.hasNext()) {
               JsonNode parameter = followRefIfAny(schemaNode, parameters.next());
               String paramName = parameter.path("name").asText();

               // Create a property node for the parameter.
               ObjectNode propertyNode = mapper.createObjectNode();
               schemaPropertiesNode.set(paramName, propertyNode);
               if (parameter.path(JSON_SCHEMA_REQUIRED_ELEMENT).asBoolean(false)) {
                  requiredPropertiesNode.add(paramName);
               }

               // Check the parameter type and default to string.
               JsonNode paramSchema = parameter.get("schema");
               if (paramSchema == null) {
                  propertyNode.put(JSON_SCHEMA_TYPE_ELEMENT, "string");
               } else {
                  String paramType = paramSchema.path(JSON_SCHEMA_TYPE_ELEMENT).asText();
                  propertyNode.put(JSON_SCHEMA_TYPE_ELEMENT, paramType);
                  // Recopy enum if any, as it is not inherited by the parameter node from the schema node.
                  if (paramSchema.has("enum")) {
                     propertyNode.set("enum", paramSchema.get("enum"));
                  }
                  //
                  if (JSON_SCHEMA_ARRAY_TYPE.equals(paramType) && paramSchema.has(JSON_SCHEMA_ITEMS_ELEMENT)) {
                     propertyNode.set(JSON_SCHEMA_ITEMS_ELEMENT,
                           dereferencedNode(schemaNode, paramSchema.get(JSON_SCHEMA_ITEMS_ELEMENT), new HashSet<>()));
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

   /** Get the requestBody schema node for an operation. */
   private JsonNode getRequestBodySchemaNode(JsonNode schemaNode, OperationEntry operation) throws Exception {
      String verb = operation.name().split(" ")[0].toLowerCase();
      String path = operation.name().split(" ")[1].trim();

      // Most common case first: whole path is in same document.
      String schemaPointer = OPEN_API_PATHS_ELEMENT + path.replace("/", "~1") + "/" + verb
            + "/requestBody/content/application~1json/schema";
      JsonNode requestSchemaNode = schemaNode.at(schemaPointer);
      if (!requestSchemaNode.isMissingNode()) {
         return followRefIfAny(schemaNode, requestSchemaNode);
      }

      // Worst case: follow all segment independently, starting from paths.
      return traversePath(schemaNode, schemaNode.get("paths"),
            List.of(path, verb, "requestBody", "content", "application/json", "schema"));
   }

   /** Get the parameters node for an operation. */
   private JsonNode getParametersNode(JsonNode schemaNode, OperationEntry operation) throws Exception {
      String verb = operation.name().split(" ")[0].toLowerCase();
      String path = operation.name().split(" ")[1].trim();

      // Most common case first: whole path is in same document.
      String paramsPointer = OPEN_API_PATHS_ELEMENT + path.replace("/", "~1") + "/" + verb + "/parameters";
      JsonNode paramsNode = schemaNode.at(paramsPointer);
      if (!paramsNode.isMissingNode()) {
         return followRefIfAny(schemaNode, paramsNode);
      }

      // Worst case: follow all segments independently, starting from paths.
      return traversePath(schemaNode, schemaNode.get("paths"), List.of(path, verb, "parameters"));
   }

   /** Traverse a path using the provided segments. */
   private JsonNode traversePath(JsonNode schemaNode, JsonNode currentNode, List<String> segments) throws Exception {
      int i = 0;
      while (currentNode != null && !currentNode.isMissingNode() && i < segments.size()) {
         currentNode = followRefIfAny(schemaNode, currentNode);

         String segment = segments.get(i);
         currentNode = currentNode.get(segment);
         i++;
      }
      // If we've been to the last segment and found a node, return it.
      if (i == segments.size() && currentNode != null && !currentNode.isMissingNode()) {
         return currentNode;
      }
      return null;
   }

   /** Follow the $ref if we have one. Otherwise, return given node. */
   private JsonNode followRefIfAny(JsonNode schemaNode, JsonNode referencableNode) throws Exception {
      if (referencableNode.has("$ref")) {
         String ref = referencableNode.path("$ref").asText();
         return getNodeForRef(schemaNode, ref);
      }
      return referencableNode;
   }

   /** Get the JsonNode for reference within the specification. */
   private JsonNode getNodeForRef(JsonNode schemaNode, String reference) {
      if (reference.startsWith("#/")) {
         return schemaNode.at(reference.substring(1));
      }
      if (attachedArtifacts != null && !attachedArtifacts.isEmpty()) {
         return getNodeForExternalRef(reference);
      }
      return null;
   }

   /** Get the JsonNode for reference that is localed in external resource. */
   private JsonNode getNodeForExternalRef(String externalReference) {
      String attachedArtifactName = externalReference;

      // We may have a Json pointer to a specific place in external reference.
      String pointerInFile = null;
      if (externalReference.contains("#/")) {
         attachedArtifactName = externalReference.substring(0, externalReference.indexOf("#/"));
         pointerInFile = externalReference.substring(externalReference.indexOf("#/"));
      }

      for (ArtifactEntry attachedArtifact : attachedArtifacts) {
         // Path direct equality is for absolute ref ("http://raw.githubusercontent.com/...")
         // Path equality with resource name if for relative refs that have been re-normalized ("Service name+Service version+...))
         if (attachedArtifactName.equals(attachedArtifact.path())
               || attachedArtifactName.equals(URLEncoder.encode(attachedArtifact.name(), StandardCharsets.UTF_8))) {
            JsonNode artifactNode = getAttachedArtifactNode(attachedArtifact, externalReference);
            if (pointerInFile != null) {
               return artifactNode.at(pointerInFile.substring(1));
            }
            return artifactNode;
         }
      }

      logger.warnf("Found no resource for reference '%s'", externalReference);
      return null;
   }

   /**
    * Get the parsed root node of an attached artifact, using the shared work cache.
    * <p>OPTIMIZATION: this replaces the original per-instance {@code attachedArtifactsContent} map.
    * The parsed content is cached in the {@link WorkCache} under the attached artifact id, so it
    * survives converter re-creation (production rebuilds a converter on every tool call) and is
    * shared across expositions referencing the same artifact.</p>
    */
   private JsonNode getAttachedArtifactNode(ArtifactEntry attachedArtifact, String externalReference) {
      String major = attachedArtifact.id();
      String minor = CACHE_KEYS_PREFIX + "attached-schema";
      Object value = workCache.get(major, minor);
      if (value instanceof JsonNode artifactNode) {
         logger.tracef("Got a cached value of attached artifact node for artifact '%s'", attachedArtifact.id());
         return artifactNode;
      }

      // Compute new value to cache.
      logger.debugf("Need to parse the attached artifact '%s'", attachedArtifact.id());
      JsonNode artifactNode;
      try {
         // We have to guess if content is JSON or YAML.
         if (attachedArtifact.content().startsWith("{") || attachedArtifact.content().startsWith("[")) {
            artifactNode = mapper.readTree(attachedArtifact.content());
         } else {
            // yamlMapper is lazily initialized.
            if (yamlMapper == null) {
               yamlMapper = ObjectMapperFactory.getYamlObjectMapper();
            }
            artifactNode = yamlMapper.readTree(attachedArtifact.content());
         }
      } catch (JsonProcessingException e) {
         throw new RuntimeException("Get a JSON processing exception on " + externalReference, e);
      }
      workCache.set(major, minor, artifactNode);
      return artifactNode;
   }

   /** Visit a node and extract its properties. */
   private void visitNodeWithProperties(JsonNode schemaNode, JsonNode node, ObjectNode propertiesNode,
                                        ArrayNode requiredPropertiesNode, Set<String> resolvingTypes) throws Exception {

      JsonNode requiredNode = node.path(JSON_SCHEMA_REQUIRED_ELEMENT);
      Iterator<Map.Entry<String, JsonNode>> propertiesList = node.path(JSON_SCHEMA_PROPERTIES_ELEMENT).fields();

      while (propertiesList.hasNext()) {
         Map.Entry<String, JsonNode> property = propertiesList.next();
         String propertyName = property.getKey();
         JsonNode propertyValue = followRefIfAny(schemaNode, property.getValue());

         if (propertyValue.has(JSON_SCHEMA_PROPERTIES_ELEMENT)) {
            // Initialize a new subschema node we must visit to resolve all possible references.
            ObjectNode subschemaNode = mapper.createObjectNode();
            ObjectNode subpropertiesNode = mapper.createObjectNode();
            ArrayNode requiredSubpropertiesNode = mapper.createArrayNode();

            subschemaNode.put("type", "object");
            subschemaNode.set(JSON_SCHEMA_PROPERTIES_ELEMENT, subpropertiesNode);
            subschemaNode.set(JSON_SCHEMA_REQUIRED_ELEMENT, requiredSubpropertiesNode);
            subschemaNode.put(JSON_SCHEMA_ADD_PROPERTIES_ELEMENT, false);
            propertiesNode.set(propertyName, subschemaNode);

            visitNodeWithProperties(schemaNode, propertyValue, subpropertiesNode, requiredSubpropertiesNode, resolvingTypes);
         } else {
            propertiesNode.set(propertyName, dereferencedNode(schemaNode, propertyValue, resolvingTypes));
            if (!requiredNode.isMissingNode() && requiredNode.isArray()
                  && arrayNodeContains((ArrayNode) requiredNode, property.getKey())) {
               requiredPropertiesNode.add(property.getKey());
            }
         }
      }
   }

   /** Dereference a node. */
   private JsonNode dereferencedNode(JsonNode schemaNode, JsonNode node, Set<String> resolvingTypes) throws Exception {
      if (node.isObject()) {
         Iterator<String> fieldNames = node.fieldNames();
         while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            JsonNode fieldValue = node.get(fieldName);
            if (fieldValue.has("$ref")) {
               // Check for recursion.
               String typeRef = fieldValue.get("$ref").asText();
               if (resolvingTypes.contains(typeRef)) {
                  // We have already visited this type, so we can skip it to avoid infinite recursion.
                  logger.debugf("Skipping already visited type '%s'", typeRef);
                  // Remove the field to avoid infinite recursion.
                  ((ObjectNode) node).remove(fieldName);
               } else {
                  // Add the current type to the set of resolving types.
                  resolvingTypes.add(typeRef);

                  JsonNode target = followRefIfAny(schemaNode, fieldValue);
                  if (target != null) {
                     // Replace the field value with the dereferenced node.
                     ((ObjectNode) node).replace(fieldName, dereferencedNode(schemaNode, target, resolvingTypes));
                  } else {
                     // If the target is null, remove the field.
                     ((ObjectNode) node).remove(fieldName);
                  }
                  // Remove the current type from the set of resolving types.
                  resolvingTypes.remove(typeRef);
               }
            } else if (fieldValue.isObject() || fieldValue.isArray()) {
               // Recursively process nested objects or arrays.
               dereferencedNode(schemaNode, fieldValue, resolvingTypes);
            }
         }
      } else if (node.isArray()) {
         for (int i = 0; i < node.size(); i++) {
            JsonNode arrayElement = node.get(i);
            if (arrayElement.has("$ref")) {
               // Check for recursion.
               String typeRef = arrayElement.get("$ref").asText();
               if (resolvingTypes.contains(typeRef)) {
                  // We have already visited this type, so we can skip it to avoid infinite recursion.
                  logger.debugf("Skipping already visited type '%s'", typeRef);
                  // Remove the field to avoid infinite recursion.
                  ((ArrayNode) node).remove(i);
               } else {
                  // Add the current type to the set of resolving types.
                  resolvingTypes.add(typeRef);

                  JsonNode target = followRefIfAny(schemaNode, arrayElement);
                  if (target != null) {
                     JsonNode dereferencedTarget = dereferencedNode(schemaNode, target, resolvingTypes);
                     // Replace the array element with the dereferenced node.
                     ((ArrayNode) node).set(i, dereferencedTarget);
                  } else {
                     // If the target is null, remove the array element.
                     ((ArrayNode) node).remove(i);
                  }
                  // Remove the current type from the set of resolving types.
                  resolvingTypes.remove(typeRef);
               }
            } else if (arrayElement.isObject() || arrayElement.isArray()) {
               // Recursively process nested objects or arrays.
               dereferencedNode(schemaNode, arrayElement, resolvingTypes);
            }
         }
      }
      return node;
   }

   /** Check if the arrayNode contains the given value. */
   private boolean arrayNodeContains(ArrayNode arrayNode, String value) {
      // Iterate over arrayNode elements and check if any element matches the value.
      for (JsonNode element : arrayNode) {
         if (element.asText().equals(value)) {
            return true;
         }
      }
      return false;
   }
}

