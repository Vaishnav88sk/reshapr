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
package io.reshapr.ctrl.security;

import io.reshapr.ctrl.config.AuthenticationIdentityProviderConfig;
import io.reshapr.ctrl.model.Organization;
import io.reshapr.ctrl.model.ServiceAccount;
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.repository.OrganizationRepository;
import io.reshapr.ctrl.repository.ServiceAccountRepository;
import io.reshapr.ctrl.repository.UserRepository;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.EntityAlreadyExistException;
import io.reshapr.ctrl.service.OnboardingService;
import io.reshapr.security.AuthenticationException;
import io.reshapr.security.OidcUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.jwt.build.Jwt;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Controller for handling authentication and user profile related requests.
 * @author laurent
 */
@RunOnVirtualThread
@Path("/auth")
public class AuthenticationController {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private static final String RESHAPR_ONBOARDING_COOKIE = "reshapr-onboarding";
   private static final int CLI_REDIRECT_MIN_PORT = 5556;
   private static final int CLI_REDIRECT_MAX_PORT = 5599;

   public static final String RESHAPR_IDENTITY_PROVIDER = "reshapr";

   private final AuthenticationIdentityProviderConfig oidcIdentityProviderConfig;
   private final OnboardingService onboardingService;

   private final UserRepository userRepository;
   private final OrganizationRepository organizationRepository;
   private final ServiceAccountRepository serviceAccountRepository;

   private final OidcLoginStateStore oidcLoginStateStore;

   private final ObjectMapper objectMapper;
   private final SecureRandom secureRandom;

   private KubernetesTokenVerifier kubernetesTokenVerifier;

   @ConfigProperty(name = "reshapr.ctrl.public-url")
   String reshaprCtrlPublicUrl;

   /**
    * Creates a new AuthenticationController with required dependencies.
    * @param oidcIdentityProviderConfig The configuration for the authentication identity provider.
    * @param onboardingService The service to onboard new users and organization
    * @param userRepository The repository to access user data.
    * @param organizationRepository The repository to access organization data
    * @param serviceAccountRepository The repository to access service account data.
    * @param oidcLoginStateStore The store for pending OIDC login states.
    * @param objectMapper The ObjectMapper for JSON processing.
    */
   public AuthenticationController(AuthenticationIdentityProviderConfig oidcIdentityProviderConfig, OnboardingService onboardingService,
                                   UserRepository userRepository, OrganizationRepository organizationRepository, ServiceAccountRepository serviceAccountRepository,
                                   OidcLoginStateStore oidcLoginStateStore, ObjectMapper objectMapper) {
      this.oidcIdentityProviderConfig = oidcIdentityProviderConfig;
      this.onboardingService = onboardingService;
      this.userRepository = userRepository;
      this.organizationRepository = organizationRepository;
      this.serviceAccountRepository = serviceAccountRepository;
      this.oidcLoginStateStore = oidcLoginStateStore;
      this.objectMapper = objectMapper;
      this.secureRandom = new SecureRandom();
   }

   @CheckedTemplate
   public static class Templates {
      public static native TemplateInstance onboardingForm(String username);
      public static native TemplateInstance onboardingError(String username, String organizationName, String message);
   }

   @POST
   @Path("/login/reshapr")
   public Response loginWithReshapr(LoginRequest loginRequest) {
      logger.infof("loginWithReshapr() called with username: %s", loginRequest.username);

      // Validate the user credentials.
      var user = userRepository.findByUsername(loginRequest.username);
      if (user == null || !user.verifyPassword(loginRequest.password)) {
         logger.warnf("Authentication failed for user: %s", loginRequest.username);
         return Response.status(Response.Status.UNAUTHORIZED).build();
      }

      // Generate a token for the authenticated user
      String token = resolveOrganizationAndGenerateToken(RESHAPR_IDENTITY_PROVIDER, user);

      return Response.ok(token).build();
   }

   @GET
   @Path("/login/oidc")
   public Response loginWithOidc(@QueryParam("redirect_uri") String redirectUri) {
      logger.infof("loginWithOidc() called with redirectUri: %s", redirectUri);

      if (!oidcIdentityProviderConfig.enabled()) {
         logger.warnf("loginWithOidc() called with disabled configuration");
         return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
      }

      // Redirect uri for the OIDC provider is control plane callback.
      String ctrlPlaneRedirectUri = reshaprCtrlPublicUrl + "/auth/callback/oidc";

      URI validatedRedirectUri;
      try {
         validatedRedirectUri = validateRedirectUri(redirectUri);
      } catch (IllegalArgumentException e) {
         logger.warnf("Rejected OIDC redirect URI: %s", e.getMessage());
         return Response.status(Response.Status.BAD_REQUEST).entity("Invalid or unauthorized redirect_uri").build();
      }

      // Compute and store a random state and nonce to correlate the login request with the callback.
      String state = randomBase64Url(32);
      String nonce = randomBase64Url(32);
      oidcLoginStateStore.createLogin(state,
         new OidcLoginStateStore.PendingOidcLogin(validatedRedirectUri.toASCIIString(), nonce, Instant.now()));

      String oidcEndpoint = oidcIdentityProviderConfig.url();
      oidcEndpoint += "?client_id=" + oidcIdentityProviderConfig.clientId();
      oidcEndpoint += "&redirect_uri=" + URLEncoder.encode(ctrlPlaneRedirectUri, StandardCharsets.UTF_8);
      oidcEndpoint += "&state=" + state;
      oidcEndpoint += "&nonce=" + nonce;
      oidcEndpoint += "&scope=" + URLEncoder.encode(buildScopeParam(), StandardCharsets.UTF_8);
      oidcEndpoint += "&response_type=code";

      logger.debugf("Redirecting to OIDC authentication provider: '%s'", oidcIdentityProviderConfig.url());
      return Response.seeOther(URI.create(oidcEndpoint)).build();
   }

   @GET
   @Path("/callback/oidc")
   @Produces(MediaType.TEXT_HTML)
   public Response callbackFromOidc(@QueryParam("code") String authorizationCode, @QueryParam("state") String state) {
      logger.debugf("callbackFromOidc() called with code: %s and state: %s", authorizationCode, state);

      if (!oidcIdentityProviderConfig.enabled()) {
         logger.warnf("callbackFromOidc() called with disabled configuration");
         return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
      }

      if (authorizationCode == null || authorizationCode.isBlank() || state == null || state.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("Missing authorization code or OIDC state").build();
      }

      OidcLoginStateStore.PendingOidcLogin pendingLogin = oidcLoginStateStore.consumeLogin(state);
      if (pendingLogin == null) {
         return Response.status(Response.Status.BAD_REQUEST).entity("Invalid, expired, or already used OIDC state").build();
      }
      String redirectUri = pendingLogin.returnUri();

      // Redirect uri for the OIDC provider is control plane callback.
      String ctrlPlaneRedirectUri = reshaprCtrlPublicUrl + "/auth/callback/oidc";

      // Exchange authorization code for access and ID tokens.
      OidcUtils.OidcAuthorizationCodeTokens oidcTokens;
      try {
         oidcTokens = OidcUtils.exchangeOidcAuthorizationCode(
               new OidcUtils.OidcEndpointConfig(oidcIdentityProviderConfig.tokenUrl(), oidcIdentityProviderConfig.clientId(),
                     oidcIdentityProviderConfig.clientSecret()),
               objectMapper, authorizationCode, ctrlPlaneRedirectUri);

      } catch (AuthenticationException e) {
         logger.errorf("OAuth2 token exchange fails with '%s'", e.getMessage());
         return Response.status(Response.Status.UNAUTHORIZED).entity("Failed to exchange authorization code for access token").build();
      }

      User user = null;
      try {
         // Validate token lifetime and bind the ID token to the nonce generated for this login.
         OidcUtils.validateJwtClaims(oidcTokens.idToken(), objectMapper, Instant.now(), pendingLogin.nonce());
         JsonNode jwtPayloadNode = OidcUtils.validateJwtClaims(oidcTokens.accessToken(), objectMapper, Instant.now(), null);
         String username = jwtPayloadNode.get("preferred_username").asText();

         // Enforce optional access guard (group / claim) before doing anything else.
         if (!isAccessAllowed(jwtPayloadNode)) {
            logger.warnf("Access denied for user '%s' by IDP guard-access configuration", username);
            String errorRedirectUri = redirectUri + (redirectUri.contains("?") ? "&" : "?") + "error=access_denied";
            return Response.seeOther(URI.create(errorRedirectUri)).build();
         }

         // Check user already exists in database.
         user = userRepository.findByUsername(username);
         if (user == null) {
            // See if we can skip onboarding by attaching the user to an existing or default organization resolved
            // from JWT claims (claim > groupPrefix > fixed value).
            String defaultOrgName = resolveDefaultOrganizationFromClaims(jwtPayloadNode);
            if (defaultOrgName != null) {
               Organization defaultOrg = organizationRepository.findByName(defaultOrgName);
               if (defaultOrg != null) {
                  try {
                     // Now we need extra user information from the JWT.
                     String email = jwtPayloadNode.path("email").asText(null);
                     String firstname = jwtPayloadNode.path("given_name").asText(null);
                     String lastname = jwtPayloadNode.path("family_name").asText(null);

                     user = onboardingService.createUserAndAttachToOrganization(
                           new OnboardingService.UserInfo(username, email, null, firstname, lastname),
                           defaultOrgName);
                     logger.infof("User '%s' attached to default organization '%s' (onboarding skipped)", username, defaultOrgName);
                  } catch (EntityAlreadyExistException | DependencyNotFoundException e) {
                     logger.warnf(e, "Failed to attach user '%s' to organization '%s', falling back to onboarding", username, defaultOrgName);
                     user = null;
                  }
               } else {
                  logger.warnf("Resolved default organization '%s' does not exist, falling back to onboarding", defaultOrgName);
               }
            }

            if (user == null) {
               // Either no default organization settings or attach fails...
               // Redirect to onboarding page if user does not exist in database, to allow creating his organization.
               String onboardingId = randomBase64Url(32);
               oidcLoginStateStore.createOnboarding(onboardingId,
                  new OidcLoginStateStore.PendingOidcOnboarding(
                     jwtPayloadNode.path("iss").asText(null),
                     jwtPayloadNode.path("sub").asText(null),
                     username,
                     jwtPayloadNode.path("email").asText(null),
                     jwtPayloadNode.path("given_name").asText(null),
                     jwtPayloadNode.path("family_name").asText(null),
                     redirectUri,
                     Instant.now()));

               NewCookie cookie = new NewCookie.Builder(RESHAPR_ONBOARDING_COOKIE)
                  .value(onboardingId)
                  .path("/auth/onboarding/oidc")
                  .sameSite(NewCookie.SameSite.STRICT)
                  .maxAge((int) Duration.ofMinutes(15).toSeconds())
                  .httpOnly(true)
                  .secure(true)
                  .build();

               logger.infof("User '%s' does not exist in database, rendering the onboarding page", username);
               TemplateInstance page = Templates.onboardingForm(username);
               return Response.ok(page.render()).cookie(cookie).build();
            }
         }
      } catch (AuthenticationException e) {
         logger.warnf("OIDC token validation failed: %s", e.getMessage());
         return Response.status(Response.Status.UNAUTHORIZED).entity("Failed to validate OIDC tokens").build();
      } catch (Exception e) {
         logger.errorf(e, "Failed to validate OIDC tokens");
         return Response.status(Response.Status.UNAUTHORIZED).entity("Failed to validate OIDC tokens").build();
      }

      // Generate a token for the authenticated user
      String token = resolveOrganizationAndGenerateToken(RESHAPR_IDENTITY_PROVIDER, user);

      return Response.seeOther(URI.create(redirectUri + "?token=" + token)).build();
   }

   @POST
   @Path("/onboarding/oidc")
   @Produces(MediaType.TEXT_HTML)
   @Transactional
   public Response completeOnboarding(@CookieParam(RESHAPR_ONBOARDING_COOKIE) String onboardingId,
                                      @FormParam("organizationName") String organizationName) {
      if (onboardingId == null || onboardingId.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("Missing onboarding state").build();
      }

      OidcLoginStateStore.PendingOidcOnboarding pendingOnboarding = oidcLoginStateStore.consumeOnboarding(onboardingId);
      if (pendingOnboarding == null) {
         return Response.status(Response.Status.BAD_REQUEST).entity("Invalid, expired, or already used onboarding state").build();
      }

      String username = pendingOnboarding.username();
      String redirectUri = pendingOnboarding.returnUri();
      User user = null;

      // Check if organization already exists.
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization != null) {
         logger.warnf("Organization with name %s already exists", organizationName);
         oidcLoginStateStore.createOnboarding(onboardingId, pendingOnboarding);
         TemplateInstance instance = Templates.onboardingError(username, organizationName, "Organization already exists");
         return Response.ok(instance.render()).build();
      }

      try {
         logger.infof("completeOnboarding() called with username: %s", username);

         // 1. Create and persist user.
         user = onboardingService.createUser(new OnboardingService.UserInfo(
               username,
               pendingOnboarding.email(),
               null,
               pendingOnboarding.firstName(),
               pendingOnboarding.lastName()));

         // 2. Create and persist organization.
         onboardingService.createOrganization(username, new OnboardingService.OrganizationInfo(organizationName, "Organization for " + username, null));

         // 3. Assign onboarding quotas.
         onboardingService.initializeOnboardingQuotas(organizationName);
      } catch (EntityAlreadyExistException eaee) {
         logger.warnf("Similar entity already exists", eaee);
         oidcLoginStateStore.createOnboarding(onboardingId, pendingOnboarding);
         TemplateInstance instance = Templates.onboardingError(username, organizationName, eaee.getMessage());
         return Response.ok(instance.render()).build();
      } catch (DependencyNotFoundException dnfe) {
         logger.warnf("A required dependency cannot be found", dnfe);
         oidcLoginStateStore.createOnboarding(onboardingId, pendingOnboarding);
         TemplateInstance instance = Templates.onboardingError(username, organizationName, dnfe.getMessage());
         return Response.ok(instance.render()).build();
      }  catch (Exception e) {
         logger.errorf(e, "Failed to create User/Organization/Quotas");
         oidcLoginStateStore.createOnboarding(onboardingId, pendingOnboarding);
         TemplateInstance instance = Templates.onboardingError(username, organizationName, e.getMessage());
         return Response.ok(instance.render()).build();
      }

      // Generate a token for the authenticated user.
      String token;
      try {
         token = resolveOrganizationAndGenerateToken(RESHAPR_IDENTITY_PROVIDER, user);
      } catch (WebApplicationException e) {
         oidcLoginStateStore.createOnboarding(onboardingId, pendingOnboarding);
         TemplateInstance instance = Templates.onboardingError(username, organizationName, e.getMessage());
         return Response.ok(instance.render()).build();
      }

      // Reset onboarding cookie to prevent replaying the onboarding.
      NewCookie cookie = new NewCookie.Builder(RESHAPR_ONBOARDING_COOKIE)
         .value("reset")
         .path("/auth/onboarding/oidc")
         .sameSite(NewCookie.SameSite.STRICT)
         .maxAge(0)
         .httpOnly(true)
         .secure(true)
         .build();

      return Response.seeOther(URI.create(redirectUri + "?token=" + token)).cookie(cookie).build();
   }

   @POST
   @Path("/login/token")
   @Produces(MediaType.TEXT_PLAIN)
   @AdminAuthenticated
   public Response generateLoginToken(DelegatedLoginRequest loginRequest) {
      logger.infof("Delegated login token requested for user: %s", loginRequest.username);

      // Find user by username.
      User user = userRepository.findByUsername(loginRequest.username);
      if (user == null) {
         logger.warnf("User with username %s not found", loginRequest.username);
         return Response.status(Response.Status.NOT_FOUND).entity("User not found").build();
      }

      // Generate a JWT token for the user.
      String token = resolveOrganizationAndGenerateToken("delegated", user);

      logger.infof("Delegated login token generated for user: %s", user.username);
      return Response.ok(token).build();
   }

   @POST
   @Produces(MediaType.TEXT_PLAIN)
   @Path("/login/token/service-account")
   public Response generateServiceAccountToken(
         @HeaderParam("Authorization") String authorizationHeader,
         @HeaderParam("x-reshapr-organization") String targetOrganization) {
      logger.infof("Service account login token requested for organization: %s", targetOrganization);

      // Check lazy initialization of KubernetesTokenVerifier.
      if (kubernetesTokenVerifier == null) {
         kubernetesTokenVerifier = KubernetesTokenVerifier.create();
      }

      // 0. Validate the Authorization header.
      if (authorizationHeader == null) {
         logger.warn("Missing Authorization header");
         return Response.status(Response.Status.UNAUTHORIZED).build();
      }

      // 1. Extract the service account name from the Authorization header.
      String k8sToken = authorizationHeader.substring("Bearer ".length());
      var k8sIdentity = kubernetesTokenVerifier.verify(k8sToken)
            .orElse(null);
      if (k8sIdentity == null) {
         logger.warnf("Invalid Kubernetes token: '%s'", k8sToken);
         return Response.status(Response.Status.UNAUTHORIZED).build();
      }

      // 2. Check if the service account is valid.
      String k8sSubject = k8sIdentity.namespace() + ":" + k8sIdentity.serviceAccountName();
      ServiceAccount sa  = serviceAccountRepository.findByK8sSubject(k8sSubject);
      if (sa == null || !sa.isValid()) {
         logger.warnf("Unknown or inactive service account: '%s'", k8sSubject);
         return Response.status(Response.Status.FORBIDDEN)
               .entity("Unknown or inactive service account: " + k8sSubject).build();
      }

      // 3. Check if the service account has access to the target organization.
      Organization org = organizationRepository.findByName(targetOrganization);
      if (org == null) {
         logger.warnf("Requested organization '%s' not found", targetOrganization);
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      if (!sa.allowedOrganizations.contains("*") && !sa.allowedOrganizations.contains(targetOrganization)) {
         logger.warnf("Service account '%s' has not access to the request organization", sa.name, targetOrganization);
         return Response.status(Response.Status.FORBIDDEN)
               .entity("Service account does not have access to the requested organization").build();
      }

      // 4. Generate a JWT token for the service account.
      String token = Jwt.issuer("https://app.reshapr.io")
            .subject("sa:" + sa.name)
            .upn("sa:" + sa.name)
            .groups("service-account")
            .expiresIn(Duration.ofMinutes(5))
            .claim("org", targetOrganization)
            .claim("sa", true)
            .claim("k8s_ns", k8sIdentity.namespace())
            .sign();

      return Response.ok(token).build();
   }

   @POST
   @Authenticated
   @Path("/switchOrganization/{organizationId}")
   public Response switchOrganization(@Context SecurityIdentity securityIdentity,
         @PathParam("organizationId") String organizationId) {
      logger.infof("switchOrganization() called with organizationId: %s", organizationId);

      // Get the current user from the security context.
      String username = securityIdentity.getPrincipal().getName();
      User currentUser = userRepository.findByUsername(username);

      if (currentUser == null) {
         logger.warn("No authenticated user found.");
         return Response.status(Response.Status.UNAUTHORIZED).build();
      }

      // Check if the user is part of the requested organization.
      Organization organization = currentUser.organizations.stream()
            .filter(org -> org.name.equals(organizationId))
            .findFirst()
            .orElse(null);
      if (organization == null) {
         logger.warnf("User %s is not part of the requested organization %s", username, organizationId);
         return Response.status(Response.Status.FORBIDDEN).entity("User is not part of the requested organization").build();
      }

      // Generate a new token for the user with the updated organization
      String token = generateTokenForUser(RESHAPR_IDENTITY_PROVIDER, currentUser, organizationId);
      logger.infof("Switched organization for user: %s to %s", currentUser.username, organizationId);

      return Response.ok(token).build();
   }

   public record LoginRequest(String username, String password) {}

   public record DelegatedLoginRequest(String username) {}

   private String generateTokenForUser(String authorityId, User user, String organizationId) {
      // Generate a Jwt with user information.
      String token = Jwt.issuer("https://app.reshapr.io")
            .subject(user.username)
            .upn(user.username)
            .groups("user")
            .expiresIn(Duration.ofHours(2))
            .claim("org", organizationId)
            .claim("email", user.email)
            .sign();

      return token;
   }

   /**
    * Build the {@code scope} query parameter for the initial OIDC authorization request.
    * Always includes {@code openid profile email} and appends any additional scopes declared
    * via {@code reshapr.authentication.idp.scopes}. Blanks and built-in scopes are ignored.
    */
   String buildScopeParam() {
      StringBuilder sb = new StringBuilder("openid profile email");
      oidcIdentityProviderConfig.scopes().ifPresent(extra -> {
         for (String scope : extra) {
            if (scope == null) continue;
            String trimmed = scope.trim();
            if (trimmed.isEmpty()) continue;
            if ("openid".equals(trimmed) || "profile".equals(trimmed) || "email".equals(trimmed)) continue;
            sb.append(' ').append(trimmed);
         }
      });
      return sb.toString();
   }

   private String randomBase64Url(int byteCount) {
      byte[] bytes = new byte[byteCount];
      secureRandom.nextBytes(bytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
   }

   private URI validateRedirectUri(String redirectUri) {
      if (redirectUri == null || redirectUri.isBlank()) {
         throw new IllegalArgumentException("redirect_uri is required");
      }

      final URI candidate;
      try {
         candidate = URI.create(redirectUri);
      } catch (IllegalArgumentException e) {
         throw new IllegalArgumentException("redirect_uri is malformed", e);
      }

      if (!candidate.isAbsolute() || candidate.getHost() == null || candidate.getUserInfo() != null
            || candidate.getQuery() != null || candidate.getFragment() != null) {
         throw new IllegalArgumentException("redirect_uri must be an absolute URI without userinfo, query, or fragment");
      }

      if (isAllowedCliRedirectUri(candidate) || isConfiguredRedirectUri(candidate)) {
         return candidate;
      }
      throw new IllegalArgumentException("redirect_uri is not allowed");
   }

   private boolean isAllowedCliRedirectUri(URI redirectUri) {
      if (!oidcIdentityProviderConfig.allowCliLoopbackRedirect()
            || !"http".equalsIgnoreCase(redirectUri.getScheme())
            || !isLoopbackHost(redirectUri.getHost())
            || (redirectUri.getPath() != null && !redirectUri.getPath().isEmpty() && !"/".equals(redirectUri.getPath()))) {
         return false;
      }

      int port = redirectUri.getPort();
      return port >= CLI_REDIRECT_MIN_PORT && port <= CLI_REDIRECT_MAX_PORT;
   }

   private boolean isConfiguredRedirectUri(URI redirectUri) {
      return oidcIdentityProviderConfig.allowedRedirectUris()
            .orElseGet(List::of)
            .stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(this::parseConfiguredRedirectUri)
            .anyMatch(redirectUri::equals);
   }

   private URI parseConfiguredRedirectUri(String configuredRedirectUri) {
      final URI allowedUri;
      try {
         allowedUri = URI.create(configuredRedirectUri);
      } catch (IllegalArgumentException e) {
         throw new IllegalArgumentException("Configured OIDC redirect URI is malformed", e);
      }

      if (!allowedUri.isAbsolute() || allowedUri.getHost() == null || allowedUri.getUserInfo() != null
            || allowedUri.getQuery() != null || allowedUri.getFragment() != null
            || (!"https".equalsIgnoreCase(allowedUri.getScheme())
                  && !("http".equalsIgnoreCase(allowedUri.getScheme()) && isLoopbackHost(allowedUri.getHost())))) {
         throw new IllegalArgumentException("Configured OIDC redirect URI must use HTTPS or HTTP loopback without userinfo, query, or fragment");
      }
      return allowedUri;
   }

   private boolean isLoopbackHost(String host) {
      return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
            || "::1".equals(host) || "[::1]".equals(host);
   }

   private Organization resolveDefaultOrganization(User user) {
      if (user.defaultOrganization != null) {
         return user.defaultOrganization;
      }
      if (user.organizations != null && !user.organizations.isEmpty()) {
         // Fallback to the first available organization
         return user.organizations.get(0);
      }
      return null;
   }

   private String resolveOrganizationAndGenerateToken(String authorityId, User user) {
      Organization defaultOrg = resolveDefaultOrganization(user);
      if (defaultOrg == null) {
         logger.warnf("User %s has no organizations assigned", user.username);
         throw new WebApplicationException("User has no organizations assigned", Response.Status.FORBIDDEN);
      }

      String token = generateTokenForUser(authorityId, user, defaultOrg.name);
      logger.infof("Authentication successful for user: %s (org: %s)", user.username, defaultOrg.name);
      
      return token;
   }

   /**
    * Enforce the optional {@code reshapr.authentication.idp.guard-access.*} policy.
    * If both a group and a claim expression are configured, both must match (AND). If none are configured, access is allowed.
    * The group check uses the standard JWT {@code groups} array claim.
    * The claim check parses the {@code name=value} configuration and compares against the token claim value (as text).
    */
   boolean isAccessAllowed(JsonNode jwtPayloadNode) {
      var guard = oidcIdentityProviderConfig.guardAccess();
      if (guard == null) {
         return true;
      }

      // Check if a specific group is required to access the application.
      if (guard.group().isPresent()) {
         String requiredGroup = guard.group().get();
         if (!jwtHasGroup(jwtPayloadNode, requiredGroup)) {
            logger.debugf("JWT groups claim does not contain required group '%s'", requiredGroup);
            return false;
         }
      }
      // Check if a specific claim is required to access the application.
      if (guard.claim().isPresent()) {
         String expr = guard.claim().get();
         int eq = expr.indexOf('=');
         if (eq <= 0) {
            logger.warnf("Invalid guard-access.claim expression '%s': expected 'name=value'", expr);
            return false;
         }
         String claimName = expr.substring(0, eq).trim();
         String expectedValue = expr.substring(eq + 1).trim();
         JsonNode node = jwtPayloadNode.get(claimName);
         if (node == null || node.isNull() || !expectedValue.equals(node.asText())) {
            logger.debugf("JWT claim '%s' does not match expected value '%s'", claimName, expectedValue);
            return false;
         }
      }
      return true;
   }

   /**
    * Resolve the target organization name for a new user from JWT claims, based on {@code reshapr.authentication.idp.default-organization.*}.
    * Resolution order: explicit claim > group prefix > fixed value. Returns {@code null} when no rule applies.
    */
   String resolveDefaultOrganizationFromClaims(JsonNode jwtPayloadNode) {
      var defaultOrg = oidcIdentityProviderConfig.defaultOrganization();
      if (defaultOrg == null) {
         return null;
      }

      // Check if a specific claim is configured to resolve the default organization.
      if (defaultOrg.claim().isPresent()) {
         JsonNode node = jwtPayloadNode.get(defaultOrg.claim().get());
         if (node != null && !node.isNull()) {
            String value = node.asText(null);
            if (value != null && !value.isBlank()) {
               return value;
            }
         }
      }
      // Check if a group prefix is configured to resolve the default organization.
      if (defaultOrg.groupPrefix().isPresent()) {
         String prefix = defaultOrg.groupPrefix().get();
         JsonNode groupsNode = jwtPayloadNode.get("groups");
         if (groupsNode != null && groupsNode.isArray()) {
            for (JsonNode g : groupsNode) {
               String group = g.asText();
               if (group != null && group.startsWith(prefix)) {
                  String remainder = group.substring(prefix.length());
                  if (!remainder.isEmpty() && (remainder.charAt(0) == '-' || remainder.charAt(0) == '/' || remainder.charAt(0) == ':')) {
                     remainder = remainder.substring(1);
                  }
                  if (!remainder.isBlank()) {
                     return remainder;
                  }
               }
            }
         }
      }
      // Return a fixed value if configured.
      if (defaultOrg.value().isPresent()) {
         String value = defaultOrg.value().get();
         if (!value.isBlank()) {
            return value;
         }
      }
      return null;
   }

   private boolean jwtHasGroup(JsonNode jwtPayloadNode, String requiredGroup) {
      JsonNode groupsNode = jwtPayloadNode.get("groups");
      if (groupsNode != null && groupsNode.isArray()) {
         for (JsonNode g : groupsNode) {
            String value = g.asText();
            // Keycloak may prefix realm groups with '/'; accept both forms.
            if (requiredGroup.equals(value) || ("/" + requiredGroup).equals(value)) {
               return true;
            }
         }
      }
      return false;
   }
}
