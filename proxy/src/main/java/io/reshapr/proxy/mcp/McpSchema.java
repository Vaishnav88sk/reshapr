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
package io.reshapr.proxy.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Based on the <a href="http://www.jsonrpc.org/specification">JSON-RPC 2.0 specification</a> and the
 * <a href= "https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2025-06-18/schema.ts">Model Context
 * Protocol Schema</a>.
 * @author laurent
 */
public class McpSchema {

   public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
         "2024-11-05",
         "2025-03-26",
         "2025-06-18",
         "2025-11-25"
   );

   public static final String JSONRPC_VERSION = "2.0";

   // ---------------------------
   // Http Header Names
   // ---------------------------

   public static final String HEADER_SESSION_ID = "MCP-Session-Id";
   public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

   // ---------------------------
   // Method Names
   // ---------------------------

   // Lifecycle Methods
   public static final String METHOD_INITIALIZE = "initialize";
   public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";
   public static final String METHOD_PING = "ping";
   public static final String METHOD_SERVER_DISCOVER = "server/discover";

   // Tool Methods
   public static final String METHOD_TOOLS_LIST = "tools/list";
   public static final String METHOD_TOOLS_CALL = "tools/call";
   public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

   // Resources Methods
   public static final String METHOD_RESOURCES_LIST = "resources/list";
   public static final String METHOD_RESOURCES_READ = "resources/read";
   public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";
   public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";
   public static final String METHOD_RESOURCES_SUBSCRIBE = "resources/subscribe";
   public static final String METHOD_RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";

   // Prompt Methods
   public static final String METHOD_PROMPTS_LIST = "prompts/list";
   public static final String METHOD_PROMPTS_GET = "prompts/get";
   public static final String METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";

   // Logging Methods
   public static final String METHOD_LOGGING_SET_LEVEL = "logging/setLevel";
   public static final String METHOD_NOTIFICATION_MESSAGE = "notifications/message";

   // Roots Methods
   public static final String METHOD_ROOTS_LIST = "roots/list";
   public static final String METHOD_NOTIFICATION_ROOTS_LIST_CHANGED = "notifications/roots/list_changed";

   // Sampling Methods
   public static final String METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage";

   // Elicitation Methods
   public static final String METHOD_ELICITATION_CREATE = "elicitation/create";
   public static final String METHOD_ELICITATION_COMPLETE = "elicitation/complete";

   private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


   // ---------------------------
   // JSON-RPC Error Codes
   // ---------------------------
   /**
    * Standard error codes used in MCP JSON-RPC responses.
    */
   public static final class ErrorCodes {

      /** Invalid JSON was received by the server. */
      public static final int PARSE_ERROR = -32700;

      /** The JSON sent is not a valid Request object. */
      public static final int INVALID_REQUEST = -32600;

      /** The method does not exist / is not available. */
      public static final int METHOD_NOT_FOUND = -32601;

      /** Invalid method parameter(s). */
      public static final int INVALID_PARAMS = -32602;

      /** Internal JSON-RPC error. */
      public static final int INTERNAL_ERROR = -32603;

      // Implementation-specific JSON-RPC error codes [-32000, -32099]

      /** URL Elicitation is required */
      public static final int URL_ELICITATION_REQUIRED = -32042;
   }

   /** Base interface for MCP objects that include optional metadata in the `_meta` field. */
   public interface Meta {
      Map<String, Object> meta();
   }

   /** JSON-RPC Message Types. */
   public sealed interface JSONRPCMessage permits JSONRPCRequest, JSONRPCNotification, JSONRPCResponse {
      String jsonrpc();
   }

   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record JSONRPCRequest(
         @JsonProperty("jsonrpc") String jsonrpc,
         @JsonProperty("method") String method,
         @JsonProperty("id") Object id,
         @JsonProperty("params") Object params) implements JSONRPCMessage {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record JSONRPCNotification(
         @JsonProperty("jsonrpc") String jsonrpc,
         @JsonProperty("method") String method,
         @JsonProperty("params") Map<String, Object> params) implements JSONRPCMessage {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record JSONRPCResponse(
         @JsonProperty("jsonrpc") String jsonrpc,
         @JsonProperty("id") Object id,
         @JsonProperty("result") Object result,
         @JsonProperty("error") JSONRPCError error) implements JSONRPCMessage {

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      public record JSONRPCError(
            @JsonProperty("code") int code,
            @JsonProperty("message") String message,
            @JsonProperty("data") Object data) {
      }
   }
   // spotless:on

   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Implementation(
         @JsonProperty("name") String name,
         @JsonProperty("version") String version) {
   }

   public enum Role {
      @JsonProperty("user") USER,
      @JsonProperty("assistant") ASSISTANT
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ClientCapabilities(
         @JsonProperty("experimental") Map<String, Object> experimental,
         @JsonProperty("roots") RootCapabilities roots,
         @JsonProperty("sampling") Sampling sampling,
         @JsonProperty("elicitation") Elicitation elicitation) {

      public ClientCapabilities(Map<String, Object> experimental, RootCapabilities roots, Sampling sampling) {
         this(experimental, roots, sampling, null);
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      public record RootCapabilities(
            @JsonProperty("listChanged") Boolean listChanged) {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      public record Sampling() {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      @JsonIgnoreProperties(ignoreUnknown = true)
      public record Elicitation() {
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ServerCapabilities(
         @JsonProperty("experimental") Map<String, Object> experimental,
         @JsonProperty("logging") LoggingCapabilities logging,
         @JsonProperty("prompts") PromptCapabilities prompts,
         @JsonProperty("resources") ResourceCapabilities resources,
         @JsonProperty("tools") ToolCapabilities tools) {


      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      public record LoggingCapabilities() {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      public record PromptCapabilities(
            @JsonProperty("listChanged") Boolean listChanged) {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      public record ResourceCapabilities(
            @JsonProperty("subscribe") Boolean subscribe,
            @JsonProperty("listChanged") Boolean listChanged) {
      }

      @JsonInclude(JsonInclude.Include.NON_ABSENT)
      public record ToolCapabilities(
            @JsonProperty("listChanged") Boolean listChanged) {
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ModelPreferences(
         @JsonProperty("hints") List<ModelHint> hints,
         @JsonProperty("costPriority") Double costPriority,
         @JsonProperty("speedPriority") Double speedPriority,
         @JsonProperty("intelligencePriority") Double intelligencePriority) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ModelHint(@JsonProperty("name") String name) {
      public static ModelHint of(String name) {
         return new ModelHint(name);
      }
   }
   
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record SamplingMessage(
         @JsonProperty("role") Role role,
         @JsonProperty("content") Content content) {
   }
   // spotless:on

   /** MCP Request Types. */
   public sealed interface Request permits InitializeRequest, SimpleRequest {

   }

   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record InitializeRequest(
         @JsonProperty("protocolVersion") String protocolVersion,
         @JsonProperty("capabilities") ClientCapabilities capabilities,
         @JsonProperty("clientInfo") Implementation clientInfo) implements Request {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record InitializeResult(
        @JsonProperty("protocolVersion") String protocolVersion,
        @JsonProperty("capabilities") ServerCapabilities capabilities,
        @JsonProperty("serverInfo") Implementation serverInfo,
        @JsonProperty("instructions") String instructions) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record DiscoverResult(
         @JsonProperty("resultType") String resultType,
         @JsonProperty("supportedVersions") List<String> supportedVersions,
         @JsonProperty("capabilities") ServerCapabilities capabilities,
         @JsonProperty("_meta") Map<String, Object> meta,
         @JsonProperty("instructions") String instructions,
         @JsonProperty("ttlMs") Long ttlMs,
         @JsonProperty("cacheScope") String cacheScope) implements Meta {

      public DiscoverResult(List<String> supportedVersions, ServerCapabilities capabilities, Map<String, Object> meta, String instructions) {
         this("complete", supportedVersions, capabilities, meta, instructions, null, null);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record SimpleRequest(
         @JsonProperty("name") String name,
         @JsonProperty("arguments") Map<String, Object> arguments) implements Request {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ListPromptsResult(
         @JsonProperty("prompts") List<Prompt> tools,
         @JsonProperty("nextCursor") String nextCursor) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record GetPromptResult(
         @JsonProperty("description") String description,
         @JsonProperty("messages") List<PromptMessage> messages) {

      public GetPromptResult(List<PromptMessage> messages) {
         this(null, messages);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ListResourcesResult(
         @JsonProperty("resources") List<Resource> resources,
         @JsonProperty("nextCursor") String nextCursor) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ListResourceTemplatesResult(
         @JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
         @JsonProperty("nextCursor") String nextCursor) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ReadResourceRequest(
         @JsonProperty("uri") String uri) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ReadResourceResult(
         @JsonProperty("contents") List<ResourceContents> contents) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ListToolsResult(
         @JsonProperty("tools") List<Tool> tools,
         @JsonProperty("nextCursor") String nextCursor) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record CallToolResult(
         @JsonProperty("content") List<Content> content,
         @JsonProperty("isError") Boolean isError) {
   }
   // spotless:on

   /** Prompts model. */
   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Prompt(
         @JsonProperty("name") String name,
         @JsonProperty("title") String title,
         @JsonProperty("description") String description,
         @JsonProperty("arguments") List<PromptArgument> arguments) {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record PromptArgument(
         @JsonProperty("name") String name,
         @JsonProperty("title") String title,
         @JsonProperty("description") String description,
         @JsonProperty("required") Boolean required) {

      public PromptArgument(String name, String description, Boolean required) {
         this(name, null, description, required);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record PromptMessage(
         @JsonProperty("role") Role role,
         @JsonProperty("content") Content content) {
   }
   // spotless:on

   /** Elicitation model. */
   // spotless:off
   public enum ElicitationMode {
      @JsonProperty("for") FOR,
      @JsonProperty("url") URL
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record URLElicitation(
         @JsonProperty("mode") ElicitationMode mode,
         @JsonProperty("elicitationId") String elicitationId,
         @JsonProperty("url") String url,
         @JsonProperty("message") String message) {

      public URLElicitation(String elicitationId, String url, String message) {
         this(ElicitationMode.URL, elicitationId, url, message);
      }
   }

   public static JSONRPCRequest buildElicitationCreateRequest(URLElicitation elicitation) {
      return new JSONRPCRequest(JSONRPC_VERSION, METHOD_ELICITATION_CREATE,
            elicitation.elicitationId,
            elicitation);
   }

   public static JSONRPCResponse.JSONRPCError buildURLElicitationRequiredError(List<URLElicitation> elicitations) {
      return new JSONRPCResponse.JSONRPCError(
            ErrorCodes.URL_ELICITATION_REQUIRED,
            "This request requires more information.",
            Map.of("elicitations", elicitations)
      );
   }
   // spotless:on

   /** Resources model. */
   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Annotations(
         @JsonProperty("audience") List<Role> audience,
         @JsonProperty("priority") Double priority,
         @JsonProperty("lastModified") String lastModified) {

      public Annotations(List<Role> audience, Double priority) {
         this(audience, priority, null);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Resource(
         @JsonProperty("uri") String uri,
         @JsonProperty("name") String name,
         @JsonProperty("title") String title,
         @JsonProperty("description") String description,
         @JsonProperty("mimeType") String mimeType,
         @JsonProperty("size") Long size,
         @JsonProperty("annotations") Annotations annotations,
         @JsonProperty("_meta") Map<String, Object> meta) implements Meta {

      public Resource(String uri, String name, String title,String description, String mimeType, Long size) {
         this(uri, name, title, description, mimeType, size, null, null);
      }

      public Resource(String uri, String name, String title,String description, String mimeType, Long size, Annotations annotations) {
         this(uri, name, title, description, mimeType, size, annotations, null);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ResourceTemplate(
         @JsonProperty("uriTemplate") String uri,
         @JsonProperty("name") String name,
         @JsonProperty("title") String title,
         @JsonProperty("description") String description,
         @JsonProperty("mimeType") String mimeType,
         @JsonProperty("annotations") Annotations annotations) {

      public ResourceTemplate(String uri, String name, String title,String description, String mimeType) {
         this(uri, name, title, description, mimeType, null);
      }
   }

   /** Tools model. */
   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record Tool(
         @JsonProperty("name") String name,
         @JsonProperty("description") String description,
         @JsonProperty("inputSchema") JsonSchema inputSchema,
         @JsonProperty("_meta") Map<String, Object> meta) implements Meta {

      public Tool(String name, String description, String schema) {
         this(name, description, parseSchema(schema), null);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record JsonSchema(
         @JsonProperty("type") String type,
         @JsonProperty("properties") Map<String, Object> properties,
         @JsonProperty("required") List<String> required,
         @JsonProperty("additionalProperties") Boolean additionalProperties) {
   }

   private static JsonSchema parseSchema(String schema) {
      try {
         return OBJECT_MAPPER.readValue(schema, JsonSchema.class);
      }
      catch (IOException e) {
         throw new IllegalArgumentException("Invalid schema: " + schema, e);
      }
   }
   // spotless:on

   /** Result Content Types. */
   @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
   @JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = "text"),
         @JsonSubTypes.Type(value = ImageContent.class, name = "image"),
         @JsonSubTypes.Type(value = EmbeddedResource.class, name = "resource") })
   public sealed interface Content permits TextContent, ImageContent, EmbeddedResource {

      default String type() {
         if (this instanceof TextContent) {
            return "text";
         } else if (this instanceof ImageContent) {
            return "image";
         } else if (this instanceof EmbeddedResource) {
            return "resource";
         }
         throw new IllegalArgumentException("Unknown content type: " + this);
      }
   }

   // spotless:off
   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record TextContent(
         @JsonProperty("audience") List<Role> audience,
         @JsonProperty("priority") Double priority,
         @JsonProperty("text") String text) implements Content {

      public TextContent(String content) {
         this(null, null, content);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record ImageContent(
         @JsonProperty("audience") List<Role> audience,
         @JsonProperty("priority") Double priority,
         @JsonProperty("data") String data,
         @JsonProperty("mimeType") String mimeType) implements Content {
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record EmbeddedResource(
        @JsonProperty("audience") List<Role> audience,
        @JsonProperty("priority") Double priority,
        @JsonProperty("resource") ResourceContents resource) implements Content {
   }

   @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, include = JsonTypeInfo.As.PROPERTY)
   @JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class, name = "text"),
         @JsonSubTypes.Type(value = BlobResourceContents.class, name = "blob") })
   public sealed interface ResourceContents permits TextResourceContents, BlobResourceContents {
      String uri();
      String mimeType();
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record TextResourceContents(
         @JsonProperty("uri") String uri,
         @JsonProperty("mimeType") String mimeType,
         @JsonProperty("text") String text,
         @JsonProperty("_meta") Map<String, Object> meta) implements ResourceContents, Meta {

      public TextResourceContents(String uri, String mimeType, String text) {
         this(uri, mimeType, text, null);
      }
   }

   @JsonInclude(JsonInclude.Include.NON_ABSENT)
   @JsonIgnoreProperties(ignoreUnknown = true)
   public record BlobResourceContents(
         @JsonProperty("uri") String uri,
         @JsonProperty("mimeType") String mimeType,
         @JsonProperty("blob") String blob) implements ResourceContents {
   }
   // spotless:on
}
