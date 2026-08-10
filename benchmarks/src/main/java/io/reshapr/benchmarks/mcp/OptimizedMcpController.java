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
package io.reshapr.benchmarks.mcp;

import io.reshapr.proxy.audit.AuditEvent;
import io.reshapr.proxy.audit.AuditLogger;
import io.reshapr.proxy.context.MethodHandlingContext;
import io.reshapr.proxy.context.MethodHandlingInfo;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.McpController;
import io.reshapr.proxy.mcp.McpError;
import io.reshapr.proxy.mcp.McpProtocolDialect;
import io.reshapr.proxy.mcp.McpSchema;
import io.reshapr.proxy.mcp.ToolCallExecutor;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.state.SessionStore;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.security.SecureEndpointFilter;

import io.opentelemetry.api.trace.Span;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optimized {@link McpController} candidate integrating:
 *
 * <p><b>#1 — No double Jackson conversion of the {@code tools/call} params.</b> The current
 * implementation runs {@code mapper.convertValue(request.params(), SimpleRequest)}, a deep
 * recursive re-deserialization of the whole {@code arguments} tree (every nested map and string is
 * re-created) although {@code params} is already a fully deserialized {@code Map} produced by the
 * JAX-RS layer. The optimized version extracts {@code name} / {@code arguments} with direct
 * pattern-matching casts (exactly like the current {@code getRequestTargetName()} already does) and
 * only makes a <i>shallow</i> defensive copy of the top-level arguments map — preserving the
 * "converters may mutate the top-level arguments" contract at O(top-level entries) cost instead of
 * a full deep copy.</p>
 *
 * <p><b>Audit #1 & #2 — cheap audit capture.</b> When audit is enabled, the current implementation
 * re-runs the same deep {@code convertValue} on the async virtual thread just to read
 * {@code params.name}, and re-serializes the whole response with Jackson just to measure its
 * length. The optimized version extracts the target name with a direct cast (synchronously) and
 * estimates the response size from the {@code CallToolResult} text content already in hand (see
 * {@link #emitAuditEvent}).</p>
 *
 * <p>Everything else is a verbatim copy of the current {@code tools/call} path (modern validation
 * ladder, session/mode guard, ScopedValue binding, audit structure, response composition) so the
 * A/B comparison isolates those changes. Every method other than {@code tools/call} is delegated
 * to the current implementation ({@code super}).</p>
 *
 * @author laurent
 */
public class OptimizedMcpController extends McpController {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** Verbatim copy of the removed-methods set (private in the parent). */
   private static final Set<String> REMOVED_MODERN_METHODS = Set.of(
         McpSchema.METHOD_INITIALIZE,
         McpSchema.METHOD_PING,
         McpSchema.METHOD_LOGGING_SET_LEVEL,
         McpSchema.METHOD_RESOURCES_SUBSCRIBE,
         McpSchema.METHOD_RESOURCES_UNSUBSCRIBE);

   private final GatewayRegistry gatewayRegistry;
   private final SessionStore sessionStore;
   private final ToolCallExecutor toolCallExecutor;
   private final AuditLogger auditLogger;

   public OptimizedMcpController(GatewayRegistry gatewayRegistry, SessionStore sessionStore,
                                 WorkCache workCache, ProxyService proxyService, ToolCallExecutor toolCallExecutor,
                                 AuditLogger auditLogger) {
      super(gatewayRegistry, sessionStore, workCache, proxyService, toolCallExecutor, auditLogger);
      this.gatewayRegistry = gatewayRegistry;
      this.sessionStore = sessionStore;
      this.toolCallExecutor = toolCallExecutor;
      this.auditLogger = auditLogger;
   }

   // ----------------------------------------------------------------------------------------------------
   // Public endpoints: tools/call takes the optimized path, everything else delegates to the current impl.
   // ----------------------------------------------------------------------------------------------------

   @Override
   public Response handleHttpStreamable(String expositionId, McpSchema.JSONRPCRequest request, HttpHeaders headers,
                                        HttpServerRequest serverRequest, ContainerRequestContext requestContext) {
      if (!McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
         return super.handleHttpStreamable(expositionId, request, headers, serverRequest, requestContext);
      }
      ExpositionEntry exposition = gatewayRegistry.getExpositionById(expositionId);
      if (exposition == null) {
         String errorMsg = String.format("Exposition with id '%s' not found", expositionId);
         logger.warn(errorMsg);
         return Response.status(Response.Status.NOT_FOUND).entity(errorMsg).build();
      }
      return handleMcpRequest(exposition, request, headers, serverRequest, requestContext, null);
   }

   @Override
   public Response handleHttpStreamableByName(String organizationId, String expositionName,
                                              McpSchema.JSONRPCRequest request, HttpHeaders headers, HttpServerRequest serverRequest,
                                              ContainerRequestContext requestContext) {
      if (!McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
         return super.handleHttpStreamableByName(organizationId, expositionName, request, headers, serverRequest,
               requestContext);
      }
      ExpositionEntry exposition = gatewayRegistry.getExpositionByName(organizationId, expositionName);
      if (exposition == null) {
         String errorMsg = String.format("Exposition '%s' in organization: '%s' not found", expositionName, organizationId);
         logger.warn(errorMsg);
         return Response.status(Response.Status.NOT_FOUND).entity(errorMsg).build();
      }
      return handleMcpRequest(exposition, request, headers, serverRequest, requestContext, null);
   }

   @Override
   public Response handleHttpStreamable(String organizationId, String service, String version,
                                        McpSchema.JSONRPCRequest request, HttpHeaders headers, HttpServerRequest serverRequest,
                                        ContainerRequestContext requestContext) {
      if (!McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
         return super.handleHttpStreamable(organizationId, service, version, request, headers, serverRequest,
               requestContext);
      }
      // If serviceName was encoded with '+' instead of '%20', remove them.
      if (service.contains("+")) {
         service = service.replace('+', ' ');
      }
      ExpositionEntry exposition = gatewayRegistry.getElectedExpositionByServiceCoordinates(organizationId, service, version);
      if (exposition == null) {
         String errorMsg = String.format("Service '%s', version: '%s' in organization: '%s' not found", service, version, organizationId);
         logger.warn(errorMsg);
         return Response.status(Response.Status.NOT_FOUND).entity(errorMsg).build();
      }
      return handleMcpRequest(exposition, request, headers, serverRequest, requestContext, buildPreferredEndpoint(exposition));
   }

   /** Verbatim copy (private in the parent). */
   private String buildPreferredEndpoint(ExpositionEntry exposition) {
      if (exposition.name() != null && !exposition.name().isBlank()) {
         return "/mcp/" + exposition.service().organizationId() + "/" + exposition.name();
      }
      return "/mcp/" + exposition.id();
   }

   // ----------------------------------------------------------------------------------------------------
   // Verbatim copy of the request-handling pipeline (only handleToolsCallRequest carries the change).
   // ----------------------------------------------------------------------------------------------------

   private Response handleMcpRequest(ExpositionEntry exposition, McpSchema.JSONRPCRequest request,
                                     HttpHeaders headers, HttpServerRequest serverRequest,
                                     ContainerRequestContext requestContext, @Nullable String preferredEndpoint) {
      ServiceEntry service = exposition.service();
      if (logger.isDebugEnabled()) {
         logger.debugf("Handling a Mcp Http call on exposition: %s (service %s)", exposition.id(), service.id());
         logger.debugf("Request body: %s", request);
         logger.debugf("Request headers: %s", headers.getRequestHeaders());
      }

      Response modernRejection = validateModernRequest(request, headers);
      if (modernRejection != null) {
         return modernRejection;
      }

      if (!hasSessionHeader(headers)) {
         String protocolVersion = getProtocolVersionHeader(headers);
         if (!McpSchema.PROTOCOL_VERSION_STATELESS.equals(protocolVersion)) {
            logger.warnf("Rejecting MCP call without session id and without stateless protocol version (got '%s')",
                  protocolVersion);
            return Response.ok(buildJSONRPCError(request, McpSchema.ErrorCodes.INVALID_REQUEST,
                  "Missing MCP session: provide a valid '" + McpSchema.HEADER_SESSION_ID
                        + "' header (legacy) or set '" + McpSchema.HEADER_PROTOCOL_VERSION + "' to '"
                        + McpSchema.PROTOCOL_VERSION_STATELESS + "' (stateless).",
                  Map.of("requiredProtocolVersion", McpSchema.PROTOCOL_VERSION_STATELESS,
                        "receivedProtocolVersion", protocolVersion == null ? "" : protocolVersion))).build();
         }
      }

      String userId = (String) requestContext.getProperty(SecureEndpointFilter.USER_ID_PROPERTY);
      String issuer = (String) requestContext.getProperty(SecureEndpointFilter.ISSUER_PROPERTY);

      AtomicReference<McpHandlerResult> resultRef = new AtomicReference<>();
      long startNanos = System.nanoTime();
      try {
         MethodHandlingInfo handlingInfo = new MethodHandlingInfo(
               serverRequest.remoteAddress().host(),
               getSessionInfo(headers),
               userId,
               issuer,
               service.organizationId());
         ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, handlingInfo).run(() -> {
            resultRef.set(handleMcpRequest(exposition, request, headers));
         });

         McpHandlerResult result = resultRef.get();

         emitAuditEvent(exposition, request, result, startNanos, serverRequest, userId);

         Response.ResponseBuilder responseBuilder = Response.status(httpStatusForMessage(result.message()))
               .entity(result.message());
         if (result.headers() != null) {
            result.headers().forEach((key, value) -> value.forEach(
                  headerValue -> responseBuilder.header(key, headerValue)
            ));
         }

         if (preferredEndpoint != null) {
            responseBuilder.header(HEADER_PREFERRED_ENDPOINT, preferredEndpoint);
         }

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

   /**
    * Dispatch copy: only {@code tools/call} ever reaches the optimized pipeline (every other method
    * is delegated to the current implementation before entering it), so the parent's method switch
    * reduces to the single tools/call arm.
    */
   private McpHandlerResult handleMcpRequest(ExpositionEntry exposition, McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      return handleToolsCallRequest(request, headers.getRequestHeaders(), exposition,
            McpProtocolDialect.forVersion(resolveProtocolVersion(headers)));
   }

   /**
    * Handle the MCP tools/call request — <b>the single functional change of this candidate</b>.
    *
    * <p>{@code request.params()} is already a deserialized {@code Map<String, Object>} produced by
    * the JAX-RS/Jackson layer: {@code name} and {@code arguments} are extracted with direct
    * pattern-matching casts instead of a deep {@code mapper.convertValue()} re-deserialization of
    * the whole arguments tree. A shallow copy of the top-level arguments map preserves the
    * "converter may mutate the arguments" contract the deep conversion used to provide.</p>
    */
   @SuppressWarnings("unchecked")
   private McpHandlerResult handleToolsCallRequest(McpSchema.JSONRPCRequest request, Map<String, List<String>> headers,
         ExpositionEntry exposition, McpProtocolDialect dialect) {
      String toolName = null;
      Map<String, Object> arguments = null;
      if (request.params() instanceof Map<?, ?> paramsMap) {
         if (paramsMap.get("name") instanceof String name) {
            toolName = name;
         }
         if (paramsMap.get("arguments") instanceof Map<?, ?> args) {
            // Shallow defensive copy: converters may add/remove top-level entries.
            arguments = new HashMap<>((Map<String, Object>) args);
         }
      }

      // Delegate the whole tool call resolution and invocation to the executor.
      ToolCallExecutor.ToolCallOutcome outcome = toolCallExecutor.execute(exposition, toolName, arguments, headers);

      return switch (outcome) {
         case ToolCallExecutor.Success success ->
               toMcpHandlerResult(request, dialect.newCallToolResult(
                     List.<McpSchema.Content>of(new McpSchema.TextContent(success.content())))
                           .isError(success.isFault())
                           .build());
         case ToolCallExecutor.ElicitationRequired elicitationRequired ->
               buildElicitationResult(request, elicitationRequired);
         case ToolCallExecutor.Failure failure ->
               toMcpHandlerResult(request, failure.code(), failure.message(), failure.data());
      };
   }

   // ----------------------------------------------------------------------------------------------------
   // Everything below is a verbatim copy of the parent's private helpers used on the tools/call path.
   // ----------------------------------------------------------------------------------------------------

   private McpHandlerResult buildElicitationResult(McpSchema.JSONRPCRequest request,
         ToolCallExecutor.ElicitationRequired elicitationRequired) {
      if (MethodHandlingContext.isStateless()) {
         return toMcpHandlerResult(request, McpSchema.buildInputRequiredResult(
               elicitationRequired.elicitations(), elicitationRequired.requestState()));
      }
      return toMcpHandlerResult(request,
            McpSchema.buildURLElicitationRequiredError(elicitationRequired.elicitations()));
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

   private boolean hasSessionHeader(HttpHeaders headers) {
      List<String> values = headers.getRequestHeader(McpSchema.HEADER_SESSION_ID);
      return values != null && !values.isEmpty()
            && values.getFirst() != null && !values.getFirst().isBlank();
   }

   @Nullable
   private String getProtocolVersionHeader(HttpHeaders headers) {
      return getHeader(headers, McpSchema.HEADER_PROTOCOL_VERSION);
   }

   @Nullable
   private String getHeader(HttpHeaders headers, String name) {
      List<String> values = headers.getRequestHeader(name);
      return (values != null && !values.isEmpty()) ? values.getFirst() : null;
   }

   @Nullable
   private Response validateModernRequest(McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      Response headerMismatch = validateModernHeaders(request, headers);
      if (headerMismatch != null) {
         return headerMismatch;
      }
      Response unsupportedVersion = validateModernProtocolVersion(request);
      if (unsupportedVersion != null) {
         return unsupportedVersion;
      }
      return rejectRemovedModernMethod(request, headers);
   }

   @Nullable
   private Response validateModernHeaders(McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      String envelopeVersion = getEnvelopeProtocolVersion(request);
      if (envelopeVersion == null || !McpSchema.isAtLeast(envelopeVersion, McpSchema.PROTOCOL_VERSION_STATELESS)) {
         return null;
      }

      String methodHeader = getHeader(headers, McpSchema.HEADER_METHOD);
      if (methodHeader != null && !methodHeader.equals(request.method())) {
         return buildHeaderMismatchResponse(request, McpSchema.HEADER_METHOD, request.method(), methodHeader);
      }

      String nameHeader = getHeader(headers, McpSchema.HEADER_NAME);
      if (nameHeader != null) {
         String target = getRequestTargetName(request);
         if (!nameHeader.equals(target)) {
            return buildHeaderMismatchResponse(request, McpSchema.HEADER_NAME, target, nameHeader);
         }
      }

      String versionHeader = getProtocolVersionHeader(headers);
      if (versionHeader != null && !versionHeader.equals(envelopeVersion)) {
         return buildHeaderMismatchResponse(request, McpSchema.HEADER_PROTOCOL_VERSION, envelopeVersion, versionHeader);
      }

      return null;
   }

   private Response buildHeaderMismatchResponse(McpSchema.JSONRPCRequest request, String header,
         @Nullable String expected, String received) {
      logger.warnf("Rejecting modern MCP call: header '%s'='%s' disagrees with request body value '%s'",
            header, received, expected);
      return buildErrorResponse(request, McpSchema.ErrorCodes.HEADER_MISMATCH,
            "Header '" + header + "' does not match the request body",
            Map.of("header", header, "expected", expected == null ? "" : expected, "received", received));
   }

   @Nullable
   private Response rejectRemovedModernMethod(McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      if (!isModernRequest(request, headers) || !REMOVED_MODERN_METHODS.contains(request.method())) {
         return null;
      }
      logger.warnf("Rejecting method '%s' removed by the 2026-07-28 MCP revision (modern/stateless mode)",
            request.method());
      return Response.status(Response.Status.NOT_FOUND)
            .entity(buildJSONRPCError(request, McpSchema.ErrorCodes.METHOD_NOT_FOUND,
                  "Method '" + request.method() + "' was removed in protocol " + McpSchema.PROTOCOL_VERSION_STATELESS,
                  null))
            .build();
   }

   private boolean isModernRequest(McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      String envelopeVersion = getEnvelopeProtocolVersion(request);
      if (envelopeVersion != null) {
         return McpSchema.isAtLeast(envelopeVersion, McpSchema.PROTOCOL_VERSION_STATELESS);
      }
      String headerVersion = getProtocolVersionHeader(headers);
      return headerVersion != null && McpSchema.isAtLeast(headerVersion, McpSchema.PROTOCOL_VERSION_STATELESS);
   }

   @Nullable
   private Response validateModernProtocolVersion(McpSchema.JSONRPCRequest request) {
      String declaredVersion = getEnvelopeProtocolVersion(request);
      if (declaredVersion == null || McpSchema.SUPPORTED_PROTOCOL_VERSIONS.contains(declaredVersion)) {
         return null;
      }
      logger.warnf("Rejecting modern MCP call declaring unsupported protocol version '%s'", declaredVersion);
      return buildErrorResponse(request, McpSchema.ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION,
            "Unsupported protocol version: " + declaredVersion,
            Map.of("supported", McpSchema.SUPPORTED_PROTOCOL_VERSIONS, "requested", declaredVersion));
   }

   @Nullable
   private String getEnvelopeProtocolVersion(McpSchema.JSONRPCRequest request) {
      if (!(request.params() instanceof Map<?, ?> paramsMap)) {
         return null;
      }
      if (!(paramsMap.get("_meta") instanceof Map<?, ?> metaMap)) {
         return null;
      }
      return metaMap.get(McpSchema.META_KEY_PROTOCOL_VERSION) instanceof String version ? version : null;
   }

   @Nullable
   private String getRequestTargetName(McpSchema.JSONRPCRequest request) {
      if (!(request.params() instanceof Map<?, ?> paramsMap)) {
         return null;
      }
      if (paramsMap.get("name") instanceof String name) {
         return name;
      }
      return paramsMap.get("uri") instanceof String uri ? uri : null;
   }

   @Nullable
   private String resolveProtocolVersion(HttpHeaders headers) {
      SessionInfo sessionInfo = getSessionInfo(headers);
      if (sessionInfo != null && sessionInfo.getProtocolVersion() != null) {
         return sessionInfo.getProtocolVersion();
      }
      return getProtocolVersionHeader(headers);
   }

   private record McpHandlerResult(
         McpSchema.JSONRPCMessage message,
         @Nullable Map<String, List<String>> headers
   ) {
      public boolean isJSONRPCResponse() {
         return message instanceof McpSchema.JSONRPCResponse;
      }
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

   private static McpSchema.JSONRPCResponse buildJSONRPCError(McpSchema.JSONRPCRequest request, int code, String message, Object data) {
      return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
         new McpSchema.JSONRPCResponse.JSONRPCError(code, message, data));
   }

   private static Response.Status httpStatusForErrorCode(int code) {
      return switch (code) {
         case McpSchema.ErrorCodes.HEADER_MISMATCH,
              McpSchema.ErrorCodes.MISSING_CLIENT_CAPABILITY,
              McpSchema.ErrorCodes.UNSUPPORTED_PROTOCOL_VERSION -> Response.Status.BAD_REQUEST;
         default -> Response.Status.OK;
      };
   }

   private static Response.Status httpStatusForMessage(McpSchema.JSONRPCMessage message) {
      if (message instanceof McpSchema.JSONRPCResponse response && response.error() != null) {
         return httpStatusForErrorCode(response.error().code());
      }
      return Response.Status.OK;
   }

   private Response buildErrorResponse(McpSchema.JSONRPCRequest request, int code, String message, Object data) {
      return Response.status(httpStatusForErrorCode(code))
            .entity(buildJSONRPCError(request, code, message, data))
            .build();
   }

   /**
    * Audit emission carrying <b>audit optimizations #1 and #2</b> (the structure — synchronous
    * capture + one virtual thread per request — is kept as-is):
    * <ul>
    *   <li><b>#1 — targetName by direct cast.</b> The current implementation re-runs a deep
    *       {@code mapper.convertValue(requestParams, SimpleRequest)} on the virtual thread just to
    *       read {@code params.name}; the optimized version reuses {@link #getRequestTargetName}
    *       (direct pattern-matching cast on the already-deserialized params map), synchronously and
    *       at O(1) cost.</li>
    *   <li><b>#2 — responseSize without re-serialization.</b> The current implementation runs
    *       {@code mapper.writeValueAsString(response.result())} just to measure a length; the
    *       optimized version sums the text lengths of the {@code CallToolResult} content already in
    *       hand — an approximation (JSON envelope excluded) considered acceptable for an audit
    *       metric.</li>
    * </ul>
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
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

      Span currentSpan = Span.current();
      String traceId = currentSpan.getSpanContext().isValid() ? currentSpan.getSpanContext().getTraceId() : null;

      String method = request.method();
      Object requestId = request.id();
      String serviceName = service.name();
      String serviceVersion = service.version();
      String organizationId = service.organizationId();
      String sourceIp = serverRequest.remoteAddress() != null ? serverRequest.remoteAddress().host() : null;
      SessionInfo sessionInfo = getSessionInfo(serverRequest);
      String sessionId = sessionInfo != null ? sessionInfo.getId() : null;

      // Optimization #1: extract the target name synchronously with a direct cast on the
      // already-deserialized params map — no deep Jackson re-conversion on the virtual thread.
      String targetName = getRequestTargetName(request);

      Thread.startVirtualThread(() -> {
         String outcome = AuditEvent.OUTCOME_SUCCESS;
         Integer errorCode = null;
         if (result.isJSONRPCResponse()
               && result.message() instanceof McpSchema.JSONRPCResponse response
                  && (response.error() != null
                     || (response.result() != null
                           && response.result() instanceof McpSchema.CallToolResult callToolResult && callToolResult.isError()))) {
            outcome = AuditEvent.OUTCOME_FAILURE;
            if (response.error() != null) {
               errorCode = response.error().code();
            }
         }

         // Optimization #2: estimate the response content size from the CallToolResult text
         // content already in hand instead of re-serializing the whole result with Jackson.
         long responseSize = 0;
         if (result.isJSONRPCResponse() && result.message() instanceof McpSchema.JSONRPCResponse response
               && response.result() instanceof McpSchema.CallToolResult callToolResult
               && callToolResult.content() != null) {
            for (McpSchema.Content content : callToolResult.content()) {
               if (content instanceof McpSchema.TextContent textContent && textContent.text() != null) {
                  responseSize += textContent.text().length();
               }
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

