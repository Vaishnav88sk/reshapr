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

import io.reshapr.json.ObjectMapperFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates synthetic OpenAPI 3.0 specifications (JSON) with a controllable number of
 * operations, schema complexity, operation path depth and reference style (inline vs
 * external YAML file), so that benchmark scenarios are reproducible.
 *
 * <p>The generated spec always contains a benchmarked GET operation with path, query
 * (scalar + array with enum) and header parameters — see {@link GeneratedSpec#benchmarkedOperation()}.</p>
 *
 * @author laurent
 */
public class OpenAPISpecGenerator {

   /** Schema complexity presets driving the byte-size of the generated document. */
   public enum Complexity {
      SMALL(5, 3, 40),
      MEDIUM(20, 12, 160),
      LARGE(40, 40, 400);

      final int propsPerSchema;
      final int componentCount;
      final int descriptionLength;

      Complexity(int propsPerSchema, int componentCount, int descriptionLength) {
         this.propsPerSchema = propsPerSchema;
         this.componentCount = componentCount;
         this.descriptionLength = descriptionLength;
      }
   }

   /** Depth of the operation paths (number of segments / path parameters). */
   public enum PathDepth {
      /** {@code /resourcesN/{id}} — 2 segments, 1 path param. */
      SHALLOW,
      /** {@code /resourcesN/{id}/subresources/{subId}/details/{detailId}/items} — 7 segments, 3 path params. */
      DEEP
   }

   /** Where parameter/schema definitions live. */
   public enum RefStyle {
      /** Everything defined inline / via local {@code #/components/...} refs in the main document. */
      INLINE,
      /** Parameters and request body schemas referenced from an attached external YAML file. */
      EXTERNAL
   }

   /** Name (and path) of the attached external components file used with {@link RefStyle#EXTERNAL}. */
   public static final String EXTERNAL_FILE_NAME = "common-components.yaml";

   /**
    * Result of a generation: the main document, the optional external artifact and the
    * metadata needed by the benchmark (operation name, path parameter names).
    */
   public record GeneratedSpec(
         String content,
         String externalArtifactName,
         String externalArtifactContent,
         String benchmarkedOperation,
         List<String> pathParamNames) {

      public boolean hasExternalArtifact() {
         return externalArtifactContent != null;
      }
   }

   private final ObjectMapper mapper = new ObjectMapper();

   /**
    * Generate an OpenAPI spec.
    * @param operationCount Total number of operations (2 operations per resource: GET + POST).
    * @param complexity Schema complexity preset.
    * @param pathDepth Depth of the operation paths.
    * @param refStyle Inline components or external YAML file references.
    * @return The generated spec plus benchmark metadata.
    */
   public GeneratedSpec generate(int operationCount, Complexity complexity, PathDepth pathDepth, RefStyle refStyle) {
      ObjectNode root = mapper.createObjectNode();
      root.put("openapi", "3.0.3");

      ObjectNode info = root.putObject("info");
      info.put("title", "Synthetic API " + operationCount + "ops-" + complexity + "-" + pathDepth + "-" + refStyle);
      info.put("version", "1.0.0");
      info.put("description", lorem(complexity.descriptionLength));

      ObjectNode paths = root.putObject("paths");
      int resourceCount = Math.max(1, operationCount / 2);
      for (int i = 0; i < resourceCount; i++) {
         addGetOperation(paths, i, complexity, pathDepth, refStyle);
         addPostOperation(paths, i, complexity, refStyle);
      }

      // Component schemas always live in the main document too, so that nested local
      // '#/components/schemas/...' refs stay resolvable whatever the ref style.
      ObjectNode components = root.putObject("components");
      ObjectNode schemas = components.putObject("schemas");
      for (int i = 0; i < complexity.componentCount; i++) {
         schemas.set("Resource" + i, buildComponentSchema(i, complexity));
      }

      String externalContent = null;
      if (refStyle == RefStyle.EXTERNAL) {
         externalContent = buildExternalComponentsYaml(complexity, pathDepth);
      }

      try {
         return new GeneratedSpec(mapper.writeValueAsString(root), EXTERNAL_FILE_NAME, externalContent,
               "GET " + resourcePath(0, pathDepth), pathParamNames(pathDepth));
      } catch (Exception e) {
         throw new IllegalStateException("Unable to serialize generated OpenAPI spec", e);
      }
   }

   /** The names of the path parameters of the benchmarked GET operation. */
   public static List<String> pathParamNames(PathDepth pathDepth) {
      return pathDepth == PathDepth.DEEP ? List.of("id", "subId", "detailId") : List.of("id");
   }

   private static String resourcePath(int index, PathDepth pathDepth) {
      return pathDepth == PathDepth.DEEP
            ? "/resources" + index + "/{id}/subresources/{subId}/details/{detailId}/items"
            : "/resources" + index + "/{id}";
   }

   private void addGetOperation(ObjectNode paths, int index, Complexity complexity, PathDepth pathDepth, RefStyle refStyle) {
      ObjectNode path = paths.putObject(resourcePath(index, pathDepth));
      ObjectNode get = path.putObject("get");
      get.put("operationId", "getResource" + index);
      get.put("summary", "Get resource " + index);
      get.put("description", lorem(complexity.descriptionLength));

      ArrayNode parameters = get.putArray("parameters");
      if (refStyle == RefStyle.EXTERNAL) {
         for (String paramKey : externalParameterKeys(pathDepth)) {
            ObjectNode ref = mapper.createObjectNode();
            ref.put("$ref", EXTERNAL_FILE_NAME + "#/components/parameters/" + paramKey);
            parameters.add(ref);
         }
      } else {
         for (ObjectNode parameter : inlineParameters(pathDepth)) {
            parameters.add(parameter);
         }
      }

      addResponses(get, index, complexity);
   }

   private void addPostOperation(ObjectNode paths, int index, Complexity complexity, RefStyle refStyle) {
      ObjectNode path = paths.putObject("/resources" + index);
      ObjectNode post = path.putObject("post");
      post.put("operationId", "createResource" + index);
      post.put("summary", "Create resource " + index);
      post.put("description", lorem(complexity.descriptionLength));

      ObjectNode requestBody = post.putObject("requestBody");
      requestBody.put("required", true);
      ObjectNode content = requestBody.putObject("content");
      ObjectNode json = content.putObject("application/json");
      ObjectNode schema = json.putObject("schema");
      String schemaName = "Resource" + (index % complexity.componentCount);
      if (refStyle == RefStyle.EXTERNAL) {
         schema.put("$ref", EXTERNAL_FILE_NAME + "#/components/schemas/" + schemaName);
      } else {
         schema.put("$ref", "#/components/schemas/" + schemaName);
      }

      addResponses(post, index, complexity);
   }

   private void addResponses(ObjectNode operation, int index, Complexity complexity) {
      ObjectNode responses = operation.putObject("responses");
      ObjectNode ok = responses.putObject("200");
      ok.put("description", "Successful response for resource " + index);
      ObjectNode content = ok.putObject("content");
      ObjectNode json = content.putObject("application/json");
      ObjectNode schema = json.putObject("schema");
      schema.put("$ref", "#/components/schemas/Resource" + (index % complexity.componentCount));
   }

   /** Keys of the parameters in the external components file, in declaration order. */
   private static List<String> externalParameterKeys(PathDepth pathDepth) {
      return pathDepth == PathDepth.DEEP
            ? List.of("IdParam", "SubIdParam", "DetailIdParam", "LimitParam", "VerboseParam", "TagsParam", "TraceIdParam")
            : List.of("IdParam", "LimitParam", "VerboseParam", "TagsParam", "TraceIdParam");
   }

   /** Build the inline parameter objects of a GET operation. */
   private List<ObjectNode> inlineParameters(PathDepth pathDepth) {
      List<ObjectNode> parameters = new ArrayList<>();
      parameters.add(param("id", "path", true, scalarSchema("string")));
      if (pathDepth == PathDepth.DEEP) {
         parameters.add(param("subId", "path", true, scalarSchema("string")));
         parameters.add(param("detailId", "path", true, scalarSchema("string")));
      }
      parameters.add(param("limit", "query", false, scalarSchema("integer")));
      parameters.add(param("verbose", "query", false, scalarSchema("boolean")));
      parameters.add(param("tags", "query", false, tagsArraySchema()));
      parameters.add(param("x-trace-id", "header", false, scalarSchema("string")));
      return parameters;
   }

   /** Build the external components document (parameters + schemas) serialized as YAML. */
   private String buildExternalComponentsYaml(Complexity complexity, PathDepth pathDepth) {
      ObjectNode root = mapper.createObjectNode();
      ObjectNode components = root.putObject("components");

      ObjectNode parameters = components.putObject("parameters");
      parameters.set("IdParam", param("id", "path", true, scalarSchema("string")));
      if (pathDepth == PathDepth.DEEP) {
         parameters.set("SubIdParam", param("subId", "path", true, scalarSchema("string")));
         parameters.set("DetailIdParam", param("detailId", "path", true, scalarSchema("string")));
      }
      parameters.set("LimitParam", param("limit", "query", false, scalarSchema("integer")));
      parameters.set("VerboseParam", param("verbose", "query", false, scalarSchema("boolean")));
      parameters.set("TagsParam", param("tags", "query", false, tagsArraySchema()));
      parameters.set("TraceIdParam", param("x-trace-id", "header", false, scalarSchema("string")));

      ObjectNode schemas = components.putObject("schemas");
      for (int i = 0; i < complexity.componentCount; i++) {
         schemas.set("Resource" + i, buildComponentSchema(i, complexity));
      }

      try {
         return ObjectMapperFactory.getYamlObjectMapper().writeValueAsString(root);
      } catch (Exception e) {
         throw new IllegalStateException("Unable to serialize external components file", e);
      }
   }

   private ObjectNode tagsArraySchema() {
      ObjectNode arraySchema = mapper.createObjectNode();
      arraySchema.put("type", "array");
      ObjectNode items = arraySchema.putObject("items");
      items.put("type", "string");
      ArrayNode enumValues = items.putArray("enum");
      enumValues.add("alpha").add("beta").add("gamma");
      return arraySchema;
   }

   private ObjectNode buildComponentSchema(int index, Complexity complexity) {
      ObjectNode schema = mapper.createObjectNode();
      schema.put("type", "object");
      schema.put("description", lorem(complexity.descriptionLength));
      ObjectNode properties = schema.putObject("properties");
      ArrayNode required = schema.putArray("required");

      for (int p = 0; p < complexity.propsPerSchema; p++) {
         ObjectNode property = properties.putObject("prop" + p);
         switch (p % 4) {
            case 0 -> property.put("type", "string");
            case 1 -> property.put("type", "integer");
            case 2 -> property.put("type", "boolean");
            default -> {
               property.put("type", "array");
               property.putObject("items").put("type", "string");
            }
         }
         property.put("description", lorem(complexity.descriptionLength / 2));
         if (p < 2) {
            required.add("prop" + p);
         }
      }

      // Chain a reference to the next component to create nested $refs to resolve.
      if (complexity != Complexity.SMALL && complexity.componentCount > 1) {
         ObjectNode nested = properties.putObject("nested");
         nested.put("$ref", "#/components/schemas/Resource" + ((index + 1) % complexity.componentCount));
      }
      return schema;
   }

   private ObjectNode param(String name, String in, boolean required, ObjectNode schema) {
      ObjectNode param = mapper.createObjectNode();
      param.put("name", name);
      param.put("in", in);
      param.put("required", required);
      param.put("description", "Parameter " + name);
      param.set("schema", schema);
      return param;
   }

   private ObjectNode scalarSchema(String type) {
      ObjectNode schema = mapper.createObjectNode();
      schema.put("type", type);
      return schema;
   }

   private static String lorem(int length) {
      final String base = "Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore. ";
      StringBuilder sb = new StringBuilder(length + base.length());
      while (sb.length() < length) {
         sb.append(base);
      }
      sb.setLength(length);
      return sb.toString();
   }
}

