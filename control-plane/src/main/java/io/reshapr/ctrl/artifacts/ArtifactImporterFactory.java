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

import io.reshapr.util.ReferenceResolver;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Factory for building/retrieving artifact importer implementations.
 * @author laurent
 */
public class ArtifactImporterFactory {

   /** Get a JBoss logging logger. */
   private static final Logger log = Logger.getLogger(ArtifactImporterFactory.class);

   /** Keyword that identifies an OpenAPI spec key - matched as a simple case-insensitive string. */
   static final String OPENAPI_3_PREFIX = "openapi";
   /** Version prefix that must follow the openapi key to be considered an OpenAPI 3.x spec. */
   static final String OPENAPI_3_VERSION_PREFIX = "3.";

   private ArtifactImporterFactory() {
      // Private constructor to hide the implicit one as it's a utility class.
   }

   /**
    * Create the right ArtifactImporter implementation depending on repository type.
    * @param artifactFile    The file representing the artifact
    * @param referenceResolver The Resolver to be used during import (may be null).
    * @return An instance of ArtifactImporter implementation
    * @throws IOException in case of file access
    */
   public static ArtifactImporter getArtifactImporter(File artifactFile, ReferenceResolver referenceResolver) throws IOException {
      ArtifactImporter importer = null;

      // Analyse first lines of file content to guess repository type.
      String line = null;
      try (BufferedReader reader = Files.newBufferedReader(artifactFile.toPath(), StandardCharsets.UTF_8)) {
         while ((line = reader.readLine()) != null && importer == null) {
            line = line.trim();
            // Try OpenAPI related one...
            importer = checkOpenAPIImporters(line, artifactFile, referenceResolver);
            // Then try any other else.
            if (importer == null) {
               importer = checkOtherImporters(line, artifactFile, referenceResolver);
            }
         }
      }
      return importer;
   }

   private static ArtifactImporter checkOpenAPIImporters(String line, File artifactFile,
                                                         ReferenceResolver referenceResolver) throws IOException {
      // Use simple string operations instead of a regex to avoid catastrophic backtracking (ReDoS).
      // The line has already been trimmed by the caller.
      int colonIdx = line.indexOf(':');
      if (colonIdx > 0) {
         String key = line.substring(0, colonIdx).replace("'", "").replace("\"", "").strip();
         String value = line.substring(colonIdx + 1).replace("'", "").replace("\"", "").strip();
         if (OPENAPI_3_PREFIX.equalsIgnoreCase(key) && value.startsWith(OPENAPI_3_VERSION_PREFIX)) {
            log.info("Found an openapi: 3 pragma in file so assuming it's an OpenAPI spec to import");
            return new OpenAPIImporter(artifactFile.getPath(), referenceResolver);
         }
      }
      return null;
   }

   private static ArtifactImporter checkOtherImporters(String line, File artifactFile, ReferenceResolver referenceResolver) throws IOException {
      if (line.startsWith("syntax = \"proto3\";") || line.startsWith("syntax=\"proto3\";")) {
         log.info("Found a syntax = proto3 pragma in file so assuming it's a GRPC Protobuf spec to import");
         return new ProtobufImporter(artifactFile.getPath(), referenceResolver);
      } else if (line.startsWith("schema {") || line.contains("type Query {")
            || line.contains("type Query implements ") || line.contains("type Mutation {")
            || line.startsWith("# microcksId:")) {
         log.info("Found query, mutation or microcksId: pragma in file so assuming it's a GraphQL schema to import");
         return new GraphQLImporter(artifactFile.getPath());
      }
      return null;
   }
}
