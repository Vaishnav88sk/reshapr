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

import io.reshapr.proxy.mcp.state.ElicitationInfo;
import io.reshapr.proxy.mcp.state.ElicitationStore;
import io.reshapr.proxy.mcp.state.UserSecretStore;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.state.SessionStore;
import io.reshapr.proxy.registry.SecretEntry;
import io.reshapr.proxy.secret.SecretReferenceResolver;
import io.reshapr.proxy.util.WebUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.reshapr.security.AuthenticationException;
import io.reshapr.security.OidcUtils;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Path("/elicitation")
public class ElicitationController {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final ElicitationStore elicitationStore;
   private final SessionStore sessionStore;
   private final UserSecretStore userSecretStore;
   private final SecretReferenceResolver secretResolver;

   @ConfigProperty(name = "reshapr.gateway.fqdns", defaultValue = "localhost:7777")
   List<String> fqdns;

   /**
    * Creates a new ElicitationController with required stores.
    * @param elicitationStore The elicitations store
    * @param sessionStore The sessions store (legacy, session-bound secrets)
    * @param userSecretStore The per-user secret store (stateless mode)
    * @param secretResolver The resolver used to resolve secret references locally on the gateway
    */
   public ElicitationController(ElicitationStore elicitationStore, SessionStore sessionStore,
                                UserSecretStore userSecretStore, SecretReferenceResolver secretResolver) {
      this.elicitationStore = elicitationStore;
      this.sessionStore = sessionStore;
      this.userSecretStore = userSecretStore;
      this.secretResolver = secretResolver;
   }

   @CheckedTemplate
   public static class Templates {
      public static native TemplateInstance form(ElicitationInfo elicitationInfo);
      public static native TemplateInstance error(String elicitationId);
      public static native TemplateInstance complete(String elicitationId);
      public static native TemplateInstance configError(String backendEndpoint);
      public static native TemplateInstance tokenError(String message);
   }

   @GET
   @Path("/form")
   public TemplateInstance accessUI(@QueryParam("elicitationId") String elicitationId) {
      ElicitationInfo elicitationInformation = elicitationStore.getElicitationInfo(elicitationId);
      if (elicitationInformation == null) {
         logger.warnf("Elicitation information not found for elicitation id '%s'", elicitationId);
         return ElicitationController.Templates.error(elicitationId);
      }

      return ElicitationController.Templates.form(elicitationInformation);
   }

   @POST
   @Path("/complete")
   public TemplateInstance completeForm(@FormParam("elicitationId") String elicitationId,
                                @FormParam("token") String token) {

      ElicitationInfo elicitationInformation = elicitationStore.getElicitationInfo(elicitationId);
      if (elicitationInformation == null) {
         logger.warnf("Elicitation information not found for elicitation id '%s'", elicitationId);
         return ElicitationController.Templates.error(elicitationId);
      }

      // Store the token according to the elicitation mode (legacy session vs stateless user).
      if (!storeElicitedSecret(elicitationInformation, token)) {
         return ElicitationController.Templates.error(elicitationId);
      }

      // Remove the elicitation information so no one can reuse it.
      elicitationStore.removeElicitationInfo(elicitationId);

      return ElicitationController.Templates.complete(elicitationId);
   }

   /**
    * Persist an elicited secret value according to the elicitation mode.
    * <ul>
    *   <li><b>legacy</b> ({@link ElicitationInfo#getSessionId()} bound) ⇒ store it in the session
    *       ({@code sessionInfo.setSecretValue} + {@code sessionStore.updateSessionInfo});</li>
    *   <li><b>stateless</b> ({@link ElicitationInfo#getUserKey()} bound) ⇒ store it in the replicated
    *       {@code user-secret-store} keyed by {@code (userKey, secretRef)}.</li>
    * </ul>
    * @return {@code true} if the secret was stored, {@code false} if the binding (session/user) is missing.
    */
   private boolean storeElicitedSecret(ElicitationInfo elicitationInformation, String token) {
      if (elicitationInformation.isStateless()) {
         String userKey = elicitationInformation.getUserKey();
         if (userKey == null) {
            logger.warnf("User key missing for stateless elicitation id '%s'", elicitationInformation.getId());
            return false;
         }
         String secretRef = secretRef(elicitationInformation);
         userSecretStore.putSecret(userKey, secretRef, token);
         return true;
      }

      // Legacy: store the token in the session information for the correct secret entry.
      SessionInfo sessionInformation = sessionStore.getSessionInfo(elicitationInformation.getSessionId());
      if (sessionInformation == null) {
         logger.warnf("Session information not found for session id '%s'", elicitationInformation.getSessionId());
         return false;
      }
      sessionInformation.setSecretValue(elicitationInformation.getSecretEntry(), token);
      sessionStore.updateSessionInfo(sessionInformation.getId(), sessionInformation);
      return true;
   }

   /** Build the stable per-user secret reference ({@code organizationId + '/' + secret.name()}). */
   private static String secretRef(ElicitationInfo elicitationInformation) {
      return elicitationInformation.getOrganizationId() + '/' + elicitationInformation.getSecretEntry().name();
   }

   @GET
   @Path("/connect")
   public Response oauth2Connect(@QueryParam("elicitationId") String elicitationId) {

      ElicitationInfo elicitationInformation = elicitationStore.getElicitationInfo(elicitationId);
      if (elicitationInformation == null) {
         logger.warnf("Elicitation information not found for elicitation id '%s'", elicitationId);
         return Response.status(Response.Status.BAD_REQUEST)
               .entity(ElicitationController.Templates.error(elicitationId).render())
               .type(MediaType.TEXT_HTML_TYPE)
               .build();
      }

      // Check the elicitation binding is still valid (legacy session or stateless user identity).
      if (!hasValidBinding(elicitationInformation)) {
         return Response.status(Response.Status.BAD_REQUEST)
               .entity(ElicitationController.Templates.error(elicitationId).render())
               .type(MediaType.TEXT_HTML_TYPE)
               .build();
      }

      SecretEntry secret = elicitationInformation.getSecretEntry();
      if (!secret.useElicitation() || secret.oauth2ClientConfiguration() == null
            || secret.oauth2ClientConfiguration().clientId() == null
            || secret.oauth2ClientConfiguration().authorizationEndpoint() == null
            || secret.oauth2ClientConfiguration().tokenEndpoint() == null ) {
         logger.warnf("Secret entry not configured for OAuth2 elicitation for elicitation id '%s'", elicitationId);
         return Response.status(Response.Status.BAD_REQUEST)
               .entity(ElicitationController.Templates.configError(elicitationInformation.getBackendEndpoint()).render())
               .type(MediaType.TEXT_HTML_TYPE)
               .build();
      }

      String authorizationEndpoint = secret.oauth2ClientConfiguration().authorizationEndpoint();
      String redirectUri = WebUtils.getHTTPScheme(fqdns.getFirst()) + fqdns.getFirst() + "/elicitation/callback?elicitationId=" + elicitationId;
      if (!authorizationEndpoint.contains("?")) {
         authorizationEndpoint += "?";
      } else {
         authorizationEndpoint += "&";
      }
      authorizationEndpoint += "client_id=" + secret.oauth2ClientConfiguration().clientId();
      authorizationEndpoint += "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
      authorizationEndpoint += "&response_type=code";
      // Stateless URL Mode OAuth: carry the opaque requestState as the OAuth `state` parameter so the
      // callback (which has no session) can correlate/validate the resumed flow.
      if (elicitationInformation.getRequestState() != null) {
         authorizationEndpoint += "&state=" + URLEncoder.encode(elicitationInformation.getRequestState(), StandardCharsets.UTF_8);
      }

      logger.debugf("Redirecting to OAuth2 authorization endpoint: '%s'", authorizationEndpoint);
      return Response.seeOther(URI.create(authorizationEndpoint)).build();
   }

   @GET
   @Path("/callback")
   public TemplateInstance oauth2Callback(@QueryParam("elicitationId") String elicitationId,
                                  @QueryParam("code") String authorizationCode,
                                  @QueryParam("state") String state) {
      ElicitationInfo elicitationInformation = elicitationStore.getElicitationInfo(elicitationId);
      if (elicitationInformation == null) {
         logger.warnf("Elicitation information not found for elicitation id '%s'", elicitationId);
         return ElicitationController.Templates.error(elicitationId);
      }

      // Check the elicitation binding is still valid (legacy session or stateless user identity).
      if (!hasValidBinding(elicitationInformation)) {
         return ElicitationController.Templates.error(elicitationId);
      }

      // Stateless URL Mode OAuth: the returned `state` must match the opaque requestState we emitted.
      if (elicitationInformation.getRequestState() != null
            && !elicitationInformation.getRequestState().equals(state)) {
         logger.warnf("OAuth2 state mismatch for elicitation id '%s'", elicitationId);
         return ElicitationController.Templates.error(elicitationId);
      }

      SecretEntry secret = elicitationInformation.getSecretEntry();
      if (!secret.useElicitation() || secret.oauth2ClientConfiguration() == null
            || secret.oauth2ClientConfiguration().clientId() == null
            || secret.oauth2ClientConfiguration().authorizationEndpoint() == null
            || secret.oauth2ClientConfiguration().tokenEndpoint() == null ) {
         logger.warnf("Secret entry not configured for OAuth2 elicitation for elicitation id '%s'", elicitationId);
         return ElicitationController.Templates.configError(elicitationInformation.getBackendEndpoint());
      }

      String redirectUri = WebUtils.getHTTPScheme(fqdns.getFirst()) + fqdns.getFirst() + "/elicitation/callback?elicitationId=" + elicitationId;

      // Now exchange the authorization code for an access token calling the token endpoint.
      String accessToken = null;
      try {
         // Resolve the client secret reference (e.g. ${env:VAR}) locally on the gateway if needed.
         String clientSecret = secretResolver.resolve(secret.oauth2ClientConfiguration().clientSecret());
         accessToken = OidcUtils.exchangeAuthorizationCode(
               new OidcUtils.OidcEndpointConfig(secret.oauth2ClientConfiguration().tokenEndpoint(),
                     secret.oauth2ClientConfiguration().clientId(), clientSecret),
               new ObjectMapper(), authorizationCode, redirectUri);
      } catch (AuthenticationException e) {
         logger.errorf("OAuth2 token exchange fails with '%s' for elicitation id '%s'", e.getMessage(), elicitationId);
         return ElicitationController.Templates.tokenError(e.getMessage());
      }

      // Save the access token according to the elicitation mode (legacy session vs stateless user).
      if (!storeElicitedSecret(elicitationInformation, accessToken)) {
         return ElicitationController.Templates.error(elicitationId);
      }

      // Remove the elicitation information so no one can reuse it.
      elicitationStore.removeElicitationInfo(elicitationId);

      return ElicitationController.Templates.complete(elicitationId);
   }

   /**
    * Whether the elicitation still has a valid binding to persist the secret against: the authenticated user
    * identity in stateless mode, or the MCP session in legacy mode.
    */
   private boolean hasValidBinding(ElicitationInfo elicitationInformation) {
      if (elicitationInformation.isStateless()) {
         if (elicitationInformation.getUserKey() == null) {
            logger.warnf("User key missing for stateless elicitation id '%s'", elicitationInformation.getId());
            return false;
         }
         return true;
      }
      SessionInfo sessionInformation = sessionStore.getSessionInfo(elicitationInformation.getSessionId());
      if (sessionInformation == null) {
         logger.warnf("Session information not found for session id '%s'", elicitationInformation.getSessionId());
         return false;
      }
      return true;
   }
}
