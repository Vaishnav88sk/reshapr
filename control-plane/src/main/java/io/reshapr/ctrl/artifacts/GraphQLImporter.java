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
package io.reshapr.ctrl.artifacts;

import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ArtifactType;
import io.reshapr.ctrl.model.Operation;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.parser.Parser;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An implementation of ArtifactImporter that deals with GraphQL Schema documents.
 * @author laurent
 */
public class GraphQLImporter implements ArtifactImporter {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** The list of valid operation types. */
   public static final List<String> VALID_OPERATION_TYPES = Arrays.asList("query", "mutation");

   private final String specContent;
   private final Document graphqlSchema;

   protected String serviceName;
   protected String serviceVersion;

   /**
    * Build a new importer.
    * @param graphqlFilePath The path to local GraphQL schema file
    * @throws IOException if project file cannot be found or read.
    */
   public GraphQLImporter(String graphqlFilePath) throws IOException {
      try {
         // Read spec bytes.
         byte[] bytes = Files.readAllBytes(Paths.get(graphqlFilePath));
         specContent = new String(bytes, StandardCharsets.UTF_8);

         // Parse schema file to a dom.
         graphqlSchema = Parser.parse(specContent);
      } catch (Exception e) {
         logger.error("Exception while parsing GraphQL schema file " + graphqlFilePath, e);
         throw new IOException("GraphQL schema file parsing error");
      }
   }

   @Override
   public void setServiceName(String serviceName) {
      this.serviceName = serviceName;
   }

   @Override
   public void setServiceVersion(String serviceVersion) {
      this.serviceVersion = serviceVersion;
   }

   @Override
   public List<Service> getServiceDefinitions() throws ArtifactImportException {
      Service service = new Service();
      service.type = ServiceType.GRAPHQL;

      // 1st thing: set service name and version.
      if (serviceName != null && serviceVersion != null) {
         service.name = serviceName;
         service.version = serviceVersion;
      } else {
         logger.errorf("Service name and version are mandatory for GraphQLImporter");
         throw new ArtifactImportException("Service name and version are mandatory for GraphQLImporter");
      }

      // We found a service, build its operations.
      service.operations = extractOperations();

      return List.of(service);
   }

   @Override
   public List<Artifact> getArtifactDefinitions(Service service) throws ArtifactImportException {
      // Just one resource: The GraphQL schema file.
      Artifact schema = new Artifact();
      schema.name = service.name + "-" + service.version + ".graphql";
      schema.type = ArtifactType.GRAPHQL_SCHEMA;
      schema.content = specContent;

      return List.of(schema);
   }

   /**
    * Extract the operations from GraphQL schema document.
    */
   private List<Operation> extractOperations() {
      List<Operation> results = new ArrayList<>();

      for (Definition definition : graphqlSchema.getDefinitions()) {
         if (definition instanceof ObjectTypeDefinition typeDefinition
                  && VALID_OPERATION_TYPES.contains(typeDefinition.getName().toLowerCase())) {
               List<Operation> operations = extractOperations(typeDefinition);
               results.addAll(operations);
         }
      }
      return results;
   }

   private List<Operation> extractOperations(ObjectTypeDefinition typeDef) {
      List<Operation> results = new ArrayList<>();

      for (FieldDefinition fieldDef : typeDef.getFieldDefinitions()) {
         Operation operation = new Operation();
         operation.name = fieldDef.getName();
         operation.method = typeDef.getName().toUpperCase();

         // Deal with input names if any.
         if (fieldDef.getInputValueDefinitions() != null && !fieldDef.getInputValueDefinitions().isEmpty()) {
            operation.inputName = getInputNames(fieldDef.getInputValueDefinitions());
         }
         // Deal with output names if any.
         if (fieldDef.getType() != null) {
            operation.outputName = getTypeName(fieldDef.getType());
         }

         results.add(operation);
      }
      return results;
   }

   /** Build a string representing comma separated inputs (eg. 'arg1, arg2'). */
   private String getInputNames(List<InputValueDefinition> inputsDef) {
      StringBuilder builder = new StringBuilder();

      for (InputValueDefinition inputDef : inputsDef) {
         builder.append(getTypeName(inputDef.getType())).append(", ");
      }
      return builder.substring(0, builder.length() - 2);
   }

   /** Get the short string representation of a type. eg. 'Film', '[Films]' or 'Film!'. */
   private String getTypeName(Type type) {
      if (type instanceof ListType listType) {
         return "[" + getTypeName(listType.getType()) + "]";
      } else if (type instanceof NonNullType nonNullType) {
         return getTypeName(nonNullType.getType()) + "!";
      } else if (type instanceof TypeName typeName) {
         return typeName.getName();
      }
      return type.toString();
   }
}
