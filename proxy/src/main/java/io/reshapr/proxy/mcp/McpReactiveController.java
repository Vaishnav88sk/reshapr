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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/mcp-r")
public class McpReactiveController {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final GatewayRegistry gatewayRegistry;

   private final ObjectMapper mapper = new ObjectMapper();

   public McpReactiveController(GatewayRegistry gatewayRegistry) {
      this.gatewayRegistry = gatewayRegistry;
   }

   @POST
   @Path("/{serviceId}")
   @Produces(MediaType.APPLICATION_JSON)
   public Uni<Response> handleHttpStreamable(@PathParam("serviceId") String serviceId,
                                             McpSchema.JSONRPCRequest request, HttpHeaders headers) {

      logger.infof("Handling a Mcp Http streamable call on service: %s", serviceId);

      ServiceEntry serviceEntry = gatewayRegistry.getService(serviceId);
      if (serviceEntry == null) {
         logger.errorf("Service with id %s not found", serviceId);
         return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build());
      }

      return handleMcpRequest(serviceEntry, request, headers)
            .onItem().transform(response -> {
               if (response.error() != null) {
                  return Response.status(Response.Status.BAD_REQUEST).entity(response.error()).build();
               } else {
                  return Response.ok(response).build();
               }
            });
   }

   @POST
   @Path("/{organizationId}/{service}/{version}")
   @Produces(MediaType.APPLICATION_JSON)
   public Uni<Response> handleHttpStreamable(@PathParam("organizationId") String organizationId,
                                        @PathParam("service") String service, @PathParam("version") String version,
                                        McpSchema.JSONRPCRequest request, HttpHeaders headers) {

      logger.infof("Handling a Mcp Http streamable call on service: %s, version: %s in organization: %s", service, version, organizationId);

      // If serviceName was encoded with '+' instead of '%20', replace them.
      if (service.contains("+")) {
         service = service.replace('+', ' ');
      }

      ServiceEntry serviceEntry = gatewayRegistry.getService(organizationId, service, version);
      if (serviceEntry == null) {
         logger.errorf("Service %s, version: %s in organization: %s not found", service, version, organizationId);
         return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build());
      }

      return handleMcpRequest(serviceEntry, request, headers)
            .onItem().transform(response -> {
               if (response.error() != null) {
                  return Response.status(Response.Status.BAD_REQUEST).entity(response.error()).build();
               } else {
                  return Response.ok(response).build();
               }
            });
   }

   /**
    * Handle the MCP request and return a JSONRPCResponse.
    * @param service The service entry for which the request is made.
    * @param request The JSONRPCRequest to handle.
    * @param headers The HTTP headers associated with the request.
    * @return A JSONRPCResponse containing the result of the request handling.
    */
   private Uni<McpSchema.JSONRPCResponse> handleMcpRequest(ServiceEntry service, McpSchema.JSONRPCRequest request, HttpHeaders headers) {
      Uni<Object> result = null;
      switch (request.method()) {
         case McpSchema.METHOD_INITIALIZE -> {
            result = handleInitializeRequest(request, service);
         }
      }

      if (result != null) {
         return result.map(res -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), res, null));
      }

      return Uni.createFrom().item(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
            new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
                  "Unsupported method: " + request.method(), null)));
   }

   /** Handle the MCP initialize request. */
   private Uni<Object> handleInitializeRequest(McpSchema.JSONRPCRequest request, ServiceEntry service) {
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

         return Uni.createFrom().item(
               new McpSchema.InitializeResult(initializeRequest.protocolVersion(), serverCapabilities,
                     new McpSchema.Implementation(service.name() + " MCP server", service.version()), null));
      }
      return Uni.createFrom().item(new McpError("Unsupported protocol version: " + initializeRequest.protocolVersion()));
   }
}
