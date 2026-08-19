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
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.proxy.GrpcProxyService;
import io.reshapr.proxy.registry.ArtifactEntry;
import io.reshapr.proxy.registry.ArtifactEntryType;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.OperationEntry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This is a test case for GrpcMcpToolConverter.
 * @author laurent
 */
class GrpcMcpToolConverterTest {

   private static final String FIRESTORE_PBB_BASE64 = io.reshapr.proxy.TestResources.readString("io/reshapr/proxy/artifacts/firestore.pbb.base64");

   @Test
   void testFirestoreProtoConversion() throws JsonProcessingException {
      ServiceEntry serviceEntry = new ServiceEntry("1", "reshapr", "google.firestore.v1.Firestore",
            "v1", "GRPC", List.of(
            new OperationEntry("CreateDocument", null, null, null, null),
            new OperationEntry("UpdateDocument", null, null, null, null),
            new OperationEntry("DeleteDocument", null, null, null, null)));
      ArtifactEntry artifactEntry = new ArtifactEntry("1", "google.firestore.v1.Firestore-v1.pbb",
            "GRPC", ArtifactEntryType.PROTOBUF_DESCRIPTOR, true, FIRESTORE_PBB_BASE64);

      ConfigurationEntry configurationEntry = new ConfigurationEntry("1", "google.firestore.v1.Firestore-default",
               null, null, null, null, null, null, null);
      ExpositionEntry exposition = new ExpositionEntry("1", "google.firestore.v1.Firestore-default", serviceEntry,  configurationEntry,
               artifactEntry, List.of());

      ObjectMapper objectMapper = new ObjectMapper();
      GrpcMcpToolConverter converter = new GrpcMcpToolConverter(exposition, new WorkCache(100),
            objectMapper, new GrpcProxyService(new SecretReferenceResolver(java.util.List.of()), new UserSecretStore(null)));

      McpSchema.JsonSchema schema = converter.getInputSchema(new OperationEntry("CreateDocument", null, null, null, null));

      String expectedCreateDocumentSchema = """
            {
              "type" : "object",
              "properties" : {
                "parent" : {
                  "type" : "string"
                },
                "collection_id" : {
                  "type" : "string"
                },
                "document_id" : {
                  "type" : "string"
                },
                "document" : {
                  "type" : "object",
                  "properties" : {
                    "name" : {
                      "type" : "string"
                    },
                    "fields" : {
                      "type" : "array",
                      "items" : {
                        "type" : "object",
                        "properties" : {
                          "key" : {
                            "type" : "string"
                          },
                          "value" : {
                            "type" : "object",
                            "properties" : {
                              "null_value" : {
                                "type" : "string",
                                "enum" : [ "NULL_VALUE" ]
                              },
                              "boolean_value" : {
                                "type" : "boolean"
                              },
                              "integer_value" : {
                                "type" : "number"
                              },
                              "double_value" : {
                                "type" : "number"
                              },
                              "timestamp_value" : {
                                "type" : "string",
                                "description" : "Timestamp in RFC3339 UTC Zulu format, with nanosecond resolution and up to nine fractional digits",
                                "format" : "date-time"
                              },
                              "string_value" : {
                                "type" : "string"
                              },
                              "reference_value" : {
                                "type" : "string"
                              },
                              "geo_point_value" : {
                                "type" : "object",
                                "properties" : {
                                  "latitude" : {
                                    "type" : "number"
                                  },
                                  "longitude" : {
                                    "type" : "number"
                                  }
                                },
                                "required" : [ ],
                                "additionalProperties" : false
                              },
                              "array_value" : {
                                "type" : "object",
                                "properties" : {
                                  "values" : {
                                    "type" : "array",
                                    "items" : {
                                      "type" : "object",
                                      "properties" : { }
                                    }
                                  }
                                },
                                "required" : [ ],
                                "additionalProperties" : false
                              },
                              "map_value" : {
                                "type" : "object",
                                "properties" : {
                                  "fields" : {
                                    "type" : "array",
                                    "items" : {
                                      "type" : "object",
                                      "properties" : {
                                        "key" : {
                                          "type" : "string"
                                        },
                                        "value" : {
                                          "type" : "object",
                                          "properties" : { },
                                          "required" : [ ],
                                          "additionalProperties" : false
                                        }
                                      }
                                    }
                                  }
                                },
                                "required" : [ ],
                                "additionalProperties" : false
                              }
                            },
                            "required" : [ ],
                            "additionalProperties" : false
                          }
                        }
                      }
                    },
                    "create_time" : {
                      "type" : "string",
                      "description" : "Timestamp in RFC3339 UTC Zulu format, with nanosecond resolution and up to nine fractional digits",
                      "format" : "date-time"
                    },
                    "update_time" : {
                      "type" : "string",
                      "description" : "Timestamp in RFC3339 UTC Zulu format, with nanosecond resolution and up to nine fractional digits",
                      "format" : "date-time"
                    }
                  },
                  "required" : [ ],
                  "additionalProperties" : false
                },
                "mask" : {
                  "type" : "object",
                  "properties" : {
                    "field_paths" : {
                      "type" : "array",
                      "items" : {
                        "type" : "string"
                      }
                    }
                  },
                  "required" : [ ],
                  "additionalProperties" : false
                }
              },
              "required" : [ "parent", "collection_id", "document" ],
              "additionalProperties" : false
            }""";

      // 3 important things to check here:
      // - no infinite recursion on document fields map definition
      // - fields annnotated with google.api.FieldBehavior.REQUIRED are marked as required in the schema
      // - well-known types are correctly represented (timestamp is a string and not an object with seconds + nanos).
      assertEquals(expectedCreateDocumentSchema,
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema));
   }
}
