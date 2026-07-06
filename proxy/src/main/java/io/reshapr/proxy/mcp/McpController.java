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

import io.opentelemetry.api.trace.Span;
import io.reshapr.proxy.audit.AuditEvent;
import io.reshapr.proxy.audit.AuditLogger;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.state.SessionStore;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.context.MethodHandlingInfo;
import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.security.SecureEndpoint;
import io.reshapr.proxy.security.SecureEndpointFilter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.instrumentation.annotations.AddingSpanAttributes;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

@RunOnVirtualThread
@Path("/mcp")
public class McpController {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** Response header advertising the deterministic per-exposition endpoint for a legacy service call. */
   public static final String HEADER_PREFERRED_ENDPOINT = "X-Reshapr-Preferred-Endpoint";

   private final GatewayRegistry gatewayRegistry;
   private final SessionStore sessionStore;
   private final WorkCache workCache;
   private final ProxyService proxyService;
   private final ToolCallExecutor toolCallExecutor;
   private final AuditLogger auditLogger;

   private final ObjectMapper mapper = new ObjectMapper();

   /**
    * Build a McpController with required dependencies.
    * @param gatewayRegistry The registry to access services and configurations.
    * @param sessionStore The store for managing MCP sessions.
    * @param workCache The work cache for temporary data storage.
    * @param proxyService   The proxy service for handling HTTP proxying.
    * @param toolCallExecutor The executor centralizing tool call resolution and invocation.
    * @param auditLogger The audit logger for emitting structured audit events.
    */
   public McpController(GatewayRegistry gatewayRegistry, SessionStore sessionStore,
                        WorkCache workCache, ProxyService proxyService, ToolCallExecutor toolCallExecutor,
                        AuditLogger auditLogger) {
      this.gatewayRegistry = gatewayRegistry;
      this.sessionStore = sessionStore;
      this.workCache = workCache;
      this.proxyService = proxyService;
      this.toolCallExecutor = toolCallExecutor;
      this.auditLogger = auditLogger;
   }

   @POST
   @Path("/{expositionId}")
   @Produces(MediaType.APPLICATION_JSON)
   @SecureEndpoint
   public Response handleHttpStreamable(@PathParam("expositionId") String expositionId,
                                        McpSchema.JSONRPCRequest request, HttpHeaders headers, HttpServerRequest serverRequest,
                                        @Context ContainerRequestContext requestContext) {

      ExpositionEntry exposition = gatewayRegistry.getExpositionById(expositionId);
      if (exposition == null) {
         String errorMsg = String.format("Exposition with id '%s' not found", expositionId);
         logger.warn(errorMsg);
         return Response.status(Response.Status.NOT_FOUND).entity(errorMsg).build();
      }

      return handleMcpRequest(exposition, request, headers, serverRequest, requestContext, null);
   }

   @POST
   @Path("/{organizationId}/{expositionName}")
   @Produces(MediaType.APPLICATION_JSON)
   @SecureEndpoint
   @AddingSpanAttributes
   public Response handleHttpStreamableByName(@SpanAttribute("organizationId") @PathParam("organizationId") String organizationId,
                                              @SpanAttribute("expositionName") @PathParam("expositionName") String expositionName,
                                              McpSchema.JSONRPCRequest request, HttpHeaders headers, HttpServerRequest serverRequest,
                                              @Context ContainerRequestContext requestContext) {

      ExpositionEntry exposition = gatewayRegistry.getExpositionByName(organizationId, expositionName);
      if (exposition == null) {
         String errorMsg = String.format("Exposition '%s' in organization: '%s' not found", expositionName, organizationId);
         logger.warn(errorMsg);
         return Response.status(Response.Status.NOT_FOUND).entity(errorMsg).build();
      }

      return handleMcpRequest(exposition, request, headers, serverRequest, requestContext, null);
   }

   @POST
   @Path("/{organizationId}/{service}/{version}")
   @Produces(MediaType.APPLICATION_JSON)
   @SecureEndpoint
   @AddingSpanAttributes
   public Response handleHttpStreamable(@SpanAttribute("organizationId") @PathParam("organizationId") String organizationId,
                                        @SpanAttribute("service") @PathParam("service") String service,
                                        @SpanAttribute("version") @PathParam("version") String version,
                                        McpSchema.JSONRPCRequest request, HttpHeaders headers, HttpServerRequest serverRequest,
                                        @Context ContainerRequestContext requestContext) {

      // If serviceName was encoded with '+' instead of '%20', remove them.
      if (service.contains("+")) {
         service = service.replace('+', ' ');
      }

      // Legacy endpoint: resolve the elected exposition (last configuration plan) of the service.
      ExpositionEntry exposition = gatewayRegistry.getElectedExpositionByServiceCoordinates(organizationId, service, version);
      if (exposition == null) {
         String errorMsg = String.format("Service '%s', version: '%s' in organization: '%s' not found", service, version, organizationId);
         logger.warn(errorMsg);
         return Response.status(Response.Status.NOT_FOUND).entity(errorMsg).build();
      }

      // Advertise the deterministic per-exposition endpoint that resolves this exact configuration plan.
      return handleMcpRequest(exposition, request, headers, serverRequest, requestContext, buildPreferredEndpoint(exposition));
   }

   /** Build the deterministic endpoint path advertised for a legacy service call (by name when available). */
   private String buildPreferredEndpoint(ExpositionEntry exposition) {
      if (exposition.name() != null && !exposition.name().isBlank()) {
         return "/mcp/" + exposition.service().organizationId() + "/" + exposition.name();
      }
      return "/mcp/" + exposition.id();
   }

   private Response handleMcpRequest(ExpositionEntry exposition, McpSchema.JSONRPCRequest request,
                                     HttpHeaders headers, HttpServerRequest serverRequest,
                                     ContainerRequestContext requestContext, @Nullable String preferredEndpoint) {
      ServiceEntry service = exposition.service();
      if (logger.isDebugEnabled()) {
         logger.debugf("Handling a Mcp Http call on exposition: %s (service %s)", exposition.id(), service.id());
         logger.debugf("Request body: %s", request);
         logger.debugf("Request headers: %s", headers.getRequestHeaders());
      }

      // Extract userId from request context (set by SecureEndpointFilter after OAuth2 validation).
      String userId = (String) requestContext.getProperty(SecureEndpointFilter.USER_ID_PROPERTY);

      AtomicReference<McpHandlerResult> resultRef = new AtomicReference<>();
      long startNanos = System.nanoTime();
      try {
         // Scope the call with call + session info for those who need it.
         MethodHandlingInfo handlingInfo = new MethodHandlingInfo(
               serverRequest.remoteAddress().host(),
               getSessionInfo(headers),
               userId);
         ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, handlingInfo).run(() -> {
            resultRef.set(handleMcpRequest(exposition, request, headers));
         });

         // Compose a Response based on result.
         McpHandlerResult result = resultRef.get();

         // Emit audit log if enabled for this configuration.
         emitAuditEvent(exposition, request, result, startNanos, serverRequest, userId);

         Response.ResponseBuilder responseBuilder = Response.ok(result.message());
         if (result.headers() != null) {
            result.headers().forEach((key, value) -> value.forEach(
                  headerValue -> responseBuilder.header(key, headerValue)
            ));
         }

         // Advertise the deterministic per-exposition endpoint when serving a legacy service call.
         if (preferredEndpoint != null) {
            responseBuilder.header(HEADER_PREFERRED_ENDPOINT, preferredEndpoint);
         }

         // Now add the mandatory MCP headers bound to session.
         if (getSessionInfo(headers) != null) {
            SessionInfo sessionInfo = getSessionInfo(headers);
            if (sessionInfo != null) {
               logger.debugf("Adding MCP session headers for session id: %s", sessionInfo.getId());
               responseBuilder
                     .header(McpSchema.HEADER_SESSION_ID, sessionInfo.getId())
                     .header(McpSchema.HEADER_PROTOCOL_VERSION, sessionInfo.getProtocolVersion());
            }
         }

         return responseBuilder.build();
      } catch (McpError e) {
         return Response.status(Response.Status.BAD_REQUEST).entity(e).build();
      }
   }

   @Nullable
   private SessionInfo getSessionInfo(HttpHeaders headers) {
      if (headers.getRequestHeader(McpSchema.HEADER_SESSION_ID) != null &&
            !headers.getRequestHeader(McpSchema.HEADER_SESSION_ID).isEmpty()) {
         String sessionId = headers.getRequestHeader(McpSchema.HEADER_SESSION_ID).getFirst();
         return sessionStore.getSessionInfo(sessionId);
      }
      return null;
   }

   /**
    * Handle the MCP request and return a JSONRPCResponse.
    * @param exposition The exposition for which the request is made.
    * @param request The JSONRPCRequest to handle.
    * @param headers The HTTP headers associated with the request.
    * @return A JSONRPCMessage representing the result of the request handling.
    */
   private McpHandlerResult handleMcpRequest(ExpositionEntry exposition, McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      McpHandlerResult result = null;
      switch (request.method()) {
         case McpSchema.METHOD_INITIALIZE ->
            result = handleInitializeRequest(request, exposition);

         case McpSchema.METHOD_PROMPTS_LIST ->
            result = handlePromptListRequest(request, exposition);

         case McpSchema.METHOD_PROMPTS_GET ->
            result = handlePromptGetRequest(request, exposition);

         case McpSchema.METHOD_RESOURCES_LIST ->
            result = handleResourceListRequest(request, exposition);

         case McpSchema.METHOD_RESOURCES_TEMPLATES_LIST ->
            result = handleResourceTemplateListRequest(request, exposition);

         case McpSchema.METHOD_RESOURCES_READ ->
            result = handleResourceReadRequest(request, exposition);

         case McpSchema.METHOD_TOOLS_LIST ->
            result = handleToolsListRequest(request, exposition);

         case McpSchema.METHOD_TOOLS_CALL ->
            result = handleToolsCallRequest(request, headers.getRequestHeaders(), exposition);
      }

      if (result == null) {
         // No result means method not found JSONRPCError.
         logger.warnf("Unsupported MCP method: %s", request.method());
         return new McpHandlerResult(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
               new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
                     "Unsupported method: " + request.method(), null)), null);
      }
      return result;
   }

   private record McpHandlerResult(
         McpSchema.JSONRPCMessage message,
         @Nullable Map<String, List<String>> headers
   ) {
      public boolean isJSONRPCRequest() {
         return message instanceof McpSchema.JSONRPCRequest;
      }
      public boolean isJSONRPCResponse() {
         return message instanceof McpSchema.JSONRPCResponse;
      }
   }

   /** Handle the MCP initialize request. */
   private McpHandlerResult handleInitializeRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      ServiceEntry service = exposition.service();
      McpSchema.InitializeRequest initializeRequest = mapper.convertValue(request.params(),
            new TypeReference<McpSchema.InitializeRequest>() {
            });

      if (McpSchema.SUPPORTED_PROTOCOL_VERSIONS.contains(initializeRequest.protocolVersion())) {
         McpSchema.ClientCapabilities clientCapabilities = initializeRequest.capabilities();
         McpSchema.Implementation clientInfo = initializeRequest.clientInfo();

         McpSchema.ServerCapabilities serverCapabilities = new McpSchema.ServerCapabilities(null, null,
               new McpSchema.ServerCapabilities.PromptCapabilities(false),
               new McpSchema.ServerCapabilities.ResourceCapabilities(false, false),
               new McpSchema.ServerCapabilities.ToolCapabilities(false));

         McpSchema.JSONRPCResponse response = buildJSONRPCResponse(request,
               new McpSchema.InitializeResult(initializeRequest.protocolVersion(), serverCapabilities,
                     new McpSchema.Implementation(service.name() + " MCP server", service.version()), null));

         // Initialize session and return session ID in headers.
         String sessionId = sessionStore.initializeSession(service.id(), initializeRequest.protocolVersion());
         Map<String, List<String>> responseHeaders = Map.of(McpSchema.HEADER_SESSION_ID,
               List.of(sessionId));

         return new McpHandlerResult(response, responseHeaders);
      }
      // Return error for unsupported protocol version.
      return new McpHandlerResult(buildJSONRPCError(request, McpSchema.ErrorCodes.INVALID_PARAMS,
            "Unsupported protocol version: " + initializeRequest.protocolVersion(),
            Map.of("supported", McpSchema.SUPPORTED_PROTOCOL_VERSIONS, "requested", initializeRequest.protocolVersion())), null);
   }

   /** Handle the MCP prompt/list request. */
   private McpHandlerResult handlePromptListRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      // Build a MCP Prompt Builder based on available elements in registry.
      McpPromptBuilder builder = buildMcpPromptBuilder(exposition);

      return toMcpHandlerResult(request, new McpSchema.ListPromptsResult(builder.listPrompts(), null));
   }

   /** Handle the MCP prompt/get request. */
   private McpHandlerResult handlePromptGetRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      McpSchema.SimpleRequest promptGetRequest = mapper.convertValue(request.params(),
            new TypeReference<McpSchema.SimpleRequest>() {
            });

      // Build a MCP Prompt Builder based on available elements in registry.
      McpPromptBuilder builder = buildMcpPromptBuilder(exposition);

      McpSchema.PromptMessage prompt = builder.getPrompt(promptGetRequest);

      return toMcpHandlerResult(request, new McpSchema.GetPromptResult(null, List.of(prompt)));
   }

   /** Handle the MCP resource/list request. */
   private McpHandlerResult handleResourceListRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      // Build a MCP Resource Builder based on available elements in registry.
      McpResourceBuilder builder = buildMcpResourceBuilder(exposition);

      return toMcpHandlerResult(request, new McpSchema.ListResourcesResult(builder.listResources(), null));
   }

   /** Handle the MCP resource/templates/list request. */
   private McpHandlerResult handleResourceTemplateListRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      // Build a MCP Resource Builder based on available elements in registry.
      McpResourceBuilder builder = buildMcpResourceBuilder(exposition);

      return toMcpHandlerResult(request, new McpSchema.ListResourceTemplatesResult(builder.listResourceTemplates(), null));
   }

   /** Handle the MCP resource/read request. */
   private McpHandlerResult handleResourceReadRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      McpSchema.ReadResourceRequest resourceReadRequest = mapper.convertValue(request.params(),
            new TypeReference<McpSchema.ReadResourceRequest>() {
            });

      // Get configuration plan from exposition.
      ConfigurationEntry configuration = exposition.configuration();

      // Build a MCP Resource Builder based on available elements in registry.
      McpResourceBuilder builder = buildMcpResourceBuilder(exposition);

      return toMcpHandlerResult(request, new McpSchema.ReadResourceResult(builder.readResource(resourceReadRequest, configuration)));
   }

   /** Handle the MCP tools/list request. */
   private McpHandlerResult handleToolsListRequest(McpSchema.JSONRPCRequest request, ExpositionEntry exposition) {
      ServiceEntry service = exposition.service();
      // Get configuration plan from exposition.
      ConfigurationEntry configuration = exposition.configuration();

      // Build converter based on service type.
      McpToolConverter converter = toolCallExecutor.buildMcpToolConverter(exposition);

      List<McpSchema.Tool> tools = converter.getAvailableOperations(service).stream()
            .filter(operation -> ToolCallExecutor.isExposedOperation(configuration, operation))
            .map(operation -> new McpSchema.Tool(converter.getToolName(operation),
                  converter.getToolDescription(operation), converter.getInputSchema(operation),
                  converter.getToolMetadata(gatewayRegistry, service, operation)))
            .toList();

      return toMcpHandlerResult(request, new McpSchema.ListToolsResult(tools, null));
   }

   /** Handle the MCP tools/call request. */
   private McpHandlerResult handleToolsCallRequest(McpSchema.JSONRPCRequest request, Map<String, List<String>> headers,
         ExpositionEntry exposition) {
      McpSchema.SimpleRequest toolRequest = mapper.convertValue(request.params(),
            new TypeReference<McpSchema.SimpleRequest>() {
            });

      // Delegate the whole tool call resolution and invocation to the executor.
      ToolCallExecutor.ToolCallOutcome outcome = toolCallExecutor.execute(exposition, toolRequest.name(),
            toolRequest.arguments(), headers);

      return switch (outcome) {
         case ToolCallExecutor.Success success ->
               toMcpHandlerResult(request, new McpSchema.CallToolResult(
                     List.of(new McpSchema.TextContent(success.content())), success.isFault()));
         case ToolCallExecutor.ElicitationRequired elicitationRequired ->
               toMcpHandlerResult(request, McpSchema.buildURLElicitationRequiredError(elicitationRequired.elicitations()));
         case ToolCallExecutor.Failure failure ->
               toMcpHandlerResult(request, failure.code(), failure.message(), failure.data());
      };
   }

   private static McpHandlerResult toMcpHandlerResult(McpSchema.JSONRPCRequest request, Object result) {
      return new McpHandlerResult(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null),
            null);
   }

   private static McpHandlerResult toMcpHandlerResult(McpSchema.JSONRPCRequest request, int code, String message, Object data) {
      return new McpHandlerResult(
            buildJSONRPCError(request, code, message, data),
            null);
   }

   private static McpHandlerResult toMcpHandlerResult(McpSchema.JSONRPCRequest request, McpSchema.JSONRPCResponse.JSONRPCError error) {
      return new McpHandlerResult(
            new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null, error),
            null);
   }

   private static McpSchema.JSONRPCResponse buildJSONRPCResponse(McpSchema.JSONRPCRequest request, Object result) {
      return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null);
   }

   private static McpSchema.JSONRPCResponse buildJSONRPCError(McpSchema.JSONRPCRequest request, int code, String message, Object data) {
      return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
         new McpSchema.JSONRPCResponse.JSONRPCError(code, message, data));
   }

   private McpPromptBuilder buildMcpPromptBuilder(ExpositionEntry exposition) {
      return new ReshaprPromptsMcpPromptBuilder(exposition.service(),
            exposition.attachedArtifacts(), workCache, mapper);
   }

   private McpResourceBuilder buildMcpResourceBuilder(ExpositionEntry exposition) {
      return new ReshaprResourcesMcpResourceBuilder(exposition.service(),
            exposition.attachedArtifacts(), workCache, mapper, proxyService);
   }


   /**
    * Emit an audit event asynchronously if audit logging is enabled for this exposition's configuration.
    * Runs on a virtual thread to avoid impacting the request response time.
    */
   private void emitAuditEvent(ExpositionEntry exposition, McpSchema.JSONRPCRequest request,
                               McpHandlerResult result, long startNanos,
                               HttpServerRequest serverRequest, @Nullable String userId) {
      ServiceEntry service = exposition.service();
      ConfigurationEntry configuration = exposition.configuration();
      if (configuration == null || !configuration.audit()) {
         logger.debugf("Audit logging is not enabled for config on service '%s'", service.id());
         return;
      }

      logger.debugf("Audit logging is enabled for config on service '%s', emitting audit event", service.id());
      // Capture duration now (before async handoff) so it reflects actual processing time.
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

      // Capture trace context now — the span is bound to the current thread and won't be
      // available on the virtual thread used for async emission.
      Span currentSpan = Span.current();
      String traceId = currentSpan.getSpanContext().isValid() ? currentSpan.getSpanContext().getTraceId() : null;

      // Capture all request-scoped values synchronously before async handoff.
      // serverRequest is backed by a Vert.x connection context that is recycled after the HTTP
      // response completes — accessing remoteAddress() or getHeader() on a virtual thread
      // scheduled after that point causes intermittent IllegalStateException and silent audit loss.
      String method = request.method();
      Object requestId = request.id();
      Object requestParams = request.params();
      String serviceName = service.name();
      String serviceVersion = service.version();
      String organizationId = service.organizationId();
      String sourceIp = serverRequest.remoteAddress() != null ? serverRequest.remoteAddress().host() : null;
      SessionInfo sessionInfo = getSessionInfo(serverRequest);
      String sessionId = sessionInfo != null ? sessionInfo.getId() : null;

      // Execute audit event sending asynchronously.
      Thread.startVirtualThread(() -> {
         // Determine outcome and error code from the result.
         String outcome = AuditEvent.OUTCOME_SUCCESS;
         Integer errorCode = null;
         if (result.isJSONRPCResponse()
               && result.message() instanceof McpSchema.JSONRPCResponse response
                  && (response.error() != null    // We have a JSONRPCError.
                     || (response.result() != null   // Or we have a result that may hold and error (such as CallToolResult)
                           && response.result() instanceof McpSchema.CallToolResult callToolResult && callToolResult.isError()))) {
            outcome = AuditEvent.OUTCOME_FAILURE;
            if (response.error() != null) {
               errorCode = response.error().code();
            }
         }

         // Compute response content size.
         long responseSize = 0;
         if (result.isJSONRPCResponse() && result.message() instanceof McpSchema.JSONRPCResponse response
               && response.result() != null) {
            try {
               responseSize = mapper.writeValueAsString(response.result()).length();
            } catch (Exception _) {
               // Serialization failed, keep 0.
            }
         }

         // Extract target name from request params for tools/call and prompts/get.
         String targetName = null;
         if (requestParams != null) {
            try {
               McpSchema.SimpleRequest simpleRequest = mapper.convertValue(requestParams,
                     new TypeReference<McpSchema.SimpleRequest>() {});
               targetName = simpleRequest.name();
            } catch (Exception _) {
               // Not a SimpleRequest, ignore — targetName stays null.
            }
         }

         AuditEvent event = new AuditEvent(
               method, targetName, outcome, errorCode, durationMs,
               serviceName, serviceVersion, organizationId,
               requestId, sessionId, sourceIp, userId,
               responseSize, traceId
         );
         auditLogger.logMcpCall(event);
      });
   }

   @Nullable
   private SessionInfo getSessionInfo(HttpServerRequest serverRequest) {
      String sessionIdHeader = serverRequest.getHeader(McpSchema.HEADER_SESSION_ID);
      if (sessionIdHeader != null && !sessionIdHeader.isEmpty()) {
         return sessionStore.getSessionInfo(sessionIdHeader);
      }
      return null;
   }
}
