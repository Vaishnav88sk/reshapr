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
package io.reshapr.proxy.security;

import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.ExpositionEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.OAuth2ConfigurationEntry;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

@RunOnVirtualThread
@Path("/.well-known")
public class WellKnownController {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final GatewayRegistry gatewayRegistry;

   @ConfigProperty(name = "reshapr.gateway.fqdns", defaultValue = "[localhost:7777]")
   List<String> fqdns;

   /**
    * Build a new WellKnownController with the required dependencies.
    * @param gatewayRegistry The gateway registry to access services and their configurations.
    */
   public WellKnownController(GatewayRegistry gatewayRegistry) {
      this.gatewayRegistry = gatewayRegistry;
   }

   @GET
   @Path("/oauth-protected-resource/mcp/{expositionId}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getWellKnownOAuthProtectedResource(@PathParam("expositionId") String expositionId) {
      logger.infof("Handling a Mcp well-known OAuth protected resource call on exposition: %s", expositionId);

      ExpositionEntry exposition = gatewayRegistry.getExpositionById(expositionId);
      if (exposition == null) {
         logger.errorf("Exposition with id '%s' not found", expositionId);
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      return getWellKnownOAuthProtectedResource(exposition, "/mcp/" + expositionId);
   }

   @GET
   @Path("/oauth-protected-resource/mcp/{organizationId}/{expositionName}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getWellKnownOAuthProtectedResourceByName(@PathParam("organizationId") String organizationId,
                                                            @PathParam("expositionName") String expositionName) {
      logger.infof("Handling a Mcp well-known OAuth protected resource call on exposition '%s' in organization '%s'",
            expositionName, organizationId);

      ExpositionEntry exposition = gatewayRegistry.getExpositionByName(organizationId, expositionName);
      if (exposition == null) {
         logger.errorf("Exposition '%s' in organization '%s' not found", expositionName, organizationId);
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      return getWellKnownOAuthProtectedResource(exposition, "/mcp/" + organizationId + "/" + expositionName);
   }

   @GET
   @Path("/oauth-protected-resource/mcp/{organizationId}/{service}/{version}")
   public Response getWellKnownOAuthProtectedResource(@PathParam("organizationId") String organizationId,
                                                      @PathParam("service") String service, @PathParam("version") String version) {

      logger.infof("Handling a Mcp well-known OAuth protected resource call on service: '%s', version: '%s' in organization: '%s'", service, version, organizationId);

      // If serviceName was encoded with '+' instead of '%20', remove them.
      if (service.contains("+")) {
         service = service.replace('+', ' ');
      }

      ExpositionEntry exposition = gatewayRegistry.getElectedExpositionByServiceCoordinates(organizationId, service, version);
      if (exposition == null) {
         logger.errorf("Service '%s', version: '%s' in organization: '%s' not found", service, version, organizationId);
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      String resourcePath = "/mcp/" + organizationId + "/" + service.replace(' ', '+') + "/" + version;
      return getWellKnownOAuthProtectedResource(exposition, resourcePath);
   }

   /**
    * Get the well-known OAuth protected resource metadata for a given exposition according
    * https://datatracker.ietf.org/doc/html/rfc9728.
    * @param exposition The exposition for which the metadata is requested.
    * @param resourcePath The MCP resource path (relative to the gateway FQDN) matching the called endpoint.
    * @return A Response containing the OAuth protected resource metadata or an error response.
    */
   private Response getWellKnownOAuthProtectedResource(ExpositionEntry exposition, String resourcePath) {
      ConfigurationEntry configuration = exposition.configuration();

      if (configuration != null && configuration.oauth2Configuration() != null) {
         OAuth2ConfigurationEntry oauth2Config = configuration.oauth2Configuration();
         String resource = "https://" + fqdns.getFirst() + resourcePath;

         OAuth2ProtectedResourceMetadata metadata = new OAuth2ProtectedResourceMetadata(
               resource,
               oauth2Config.authorizationServers(),
               oauth2Config.jwksUri(),
               oauth2Config.scopes()
         );
         return Response.ok(metadata).build();
      }
      logger.errorf("Service '%s' is not configured for OAuth2", exposition.service().name());
      return Response.status(Response.Status.NOT_FOUND).build();
   }
}
