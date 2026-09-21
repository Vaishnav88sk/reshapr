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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for the JWT parsing / scope building helpers of {@link AuthenticationController}.
 * The controller is instantiated with a hand-rolled config and {@code null} for other collaborators:
 * the tested helpers only rely on {@link AuthenticationIdentityProviderConfig} and Jackson.
 * @author laurent
 */
class AuthenticationControllerTest {

   private static final ObjectMapper MAPPER = new ObjectMapper();

   private OidcLoginStateStore stateStore;

   @BeforeEach
   void setUp() {
      stateStore = mock(OidcLoginStateStore.class);
   }

   // ---------------------------------------------------------------------
   //  OIDC state
   // ---------------------------------------------------------------------

   @Test
   void testLoginWithOidcStoresOpaqueStateAndNonce() {
      var controller = controllerWithConfig(configWithRedirectUris(
         List.of("https://app.example.com/api/auth/callback/oidc")), stateStore);
      controller.reshaprCtrlPublicUrl = "https://ctrl.example.com";

      try (Response response = controller.loginWithOidc("https://app.example.com/api/auth/callback/oidc")) {
         assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());

         ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
         ArgumentCaptor<OidcLoginStateStore.PendingOidcLogin> loginCaptor =
               ArgumentCaptor.forClass(OidcLoginStateStore.PendingOidcLogin.class);
         verify(stateStore).createLogin(stateCaptor.capture(), loginCaptor.capture());

         String state = stateCaptor.getValue();
         OidcLoginStateStore.PendingOidcLogin pendingLogin = loginCaptor.getValue();
         URI location = response.getLocation();

         assertNotNull(location);
         assertTrue(location.getRawQuery().contains("state=" + state));
         assertTrue(location.getRawQuery().contains("nonce=" + pendingLogin.nonce()));
         assertEquals("https://app.example.com/api/auth/callback/oidc", pendingLogin.returnUri());
         assertFalse(state.contains("app.example.com"));
      }
   }

   @Test
   void testLoginWithOidcAllowsCliLoopbackRedirect() {
      var controller = controllerWithConfig(config(null, null, null, null, null, null), stateStore);
      controller.reshaprCtrlPublicUrl = "https://ctrl.example.com";

      try (Response response = controller.loginWithOidc("http://localhost:5556")) {
         assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
      }
   }

   @Test
   void testLoginWithOidcRejectsUnauthorizedRedirect() {
      var controller = controllerWithConfig(configWithRedirectUris(
            List.of("https://app.example.com/api/auth/callback/oidc")), stateStore);
      controller.reshaprCtrlPublicUrl = "https://ctrl.example.com";

      try (Response response = controller.loginWithOidc("https://attacker.example.com/callback")) {
         assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
         assertEquals("Invalid or unauthorized redirect_uri", response.getEntity());
      }

      verifyNoInteractions(stateStore);
   }

   @Test
   void testLoginWithOidcRejectsLoopbackRedirectOutsideCliPortRange() {
      var controller = controllerWithConfig(config(null, null, null, null, null, null), stateStore);
      controller.reshaprCtrlPublicUrl = "https://ctrl.example.com";

      try (Response response = controller.loginWithOidc("http://localhost:8080")) {
         assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
      }
   }

   @Test
   void testCallbackRejectsUnknownStateBeforeTokenExchange() {
      var controller = controllerWithConfig(config(null, null, null, null, null, null), stateStore);
      controller.reshaprCtrlPublicUrl = "https://ctrl.example.com";
      when(stateStore.consumeLogin("unknown-state")).thenReturn(null);

      try (Response response = controller.callbackFromOidc("authorization-code", "unknown-state")) {
         assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
         assertEquals("Invalid, expired, or already used OIDC state", response.getEntity());
      }

      verify(stateStore).consumeLogin("unknown-state");
   }

   @Test
   void testOnboardingRejectsUnknownStateBeforeRepositoryAccess() {
      var controller = controllerWithConfig(config(null, null, null, null, null, null), stateStore);
      when(stateStore.consumeOnboarding("unknown-state")).thenReturn(null);

      try (Response response = controller.completeOnboarding("unknown-state", "organization")) {
         assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
         assertEquals("Invalid, expired, or already used onboarding state", response.getEntity());
      }

      verify(stateStore).consumeOnboarding("unknown-state");
   }

   // ---------------------------------------------------------------------
   //  buildScopeParam
   // ---------------------------------------------------------------------

   @Test
   void testBuildScopeParamWhenNoExtraScopeConfigured() {
      var controller = controllerWithConfig(config(null, null, null, null, null, null));

      assertEquals("openid profile email", controller.buildScopeParam());
   }

   @Test
   void testBuildScopeParamAppendsExtraScopes() {
      var controller = controllerWithConfig(config(List.of("my-scope-1", "my-scope-2"), null, null, null, null, null));

      assertEquals("openid profile email my-scope-1 my-scope-2", controller.buildScopeParam());
   }

   @Test
   void testBuildScopeParamDropsBlankAndBuiltinScopes() {
      var controller = controllerWithConfig(config(
            List.of("openid", "profile", "email", "", "  ", "custom"), null, null, null, null, null));

      assertEquals("openid profile email custom", controller.buildScopeParam());
   }

   @Test
   void testBuildScopeParamTrimsWhitespace() {
      var controller = controllerWithConfig(config(List.of("  offline_access  "), null, null, null, null, null));

      assertEquals("openid profile email offline_access", controller.buildScopeParam());
   }

   // ---------------------------------------------------------------------
   //  isAccessAllowed
   // ---------------------------------------------------------------------

   @Test
   void testIsAccessAllowedAllowsAllWhenNoGuardConfigured() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, null, null, null));

      assertTrue(controller.isAccessAllowed(jwt("{\"preferred_username\":\"alice\"}")));
   }

   @Test
   void testIsAccessAllowedAcceptsWhenGroupsClaimContainsRequiredGroup() throws Exception {
      var controller = controllerWithConfig(config(null, "reshapr-allowed", null, null, null, null));

      assertTrue(controller.isAccessAllowed(jwt(
            "{\"groups\":[\"other\",\"reshapr-allowed\"]}")));
   }

   @Test
   void testIsAccessAllowedAcceptsWhenGroupsClaimContainsSlashPrefixedGroup() throws Exception {
      // Keycloak may emit groups with a leading '/'; the check should be lenient about it.
      var controller = controllerWithConfig(config(null, "reshapr-allowed", null, null, null, null));

      assertTrue(controller.isAccessAllowed(jwt("{\"groups\":[\"/reshapr-allowed\"]}")));
   }

   @Test
   void testIsAccessAllowedRejectsWhenGroupsClaimMissing() throws Exception {
      var controller = controllerWithConfig(config(null, "reshapr-allowed", null, null, null, null));

      assertFalse(controller.isAccessAllowed(jwt("{\"preferred_username\":\"bob\"}")));
   }

   @Test
   void testIsAccessAllowedRejectsWhenGroupsClaimDoesNotContainRequiredGroup() throws Exception {
      var controller = controllerWithConfig(config(null, "reshapr-allowed", null, null, null, null));

      assertFalse(controller.isAccessAllowed(jwt("{\"groups\":[\"other\"]}")));
   }

   @Test
   void testIsAccessAllowedAcceptsWhenClaimExpressionMatches() throws Exception {
      var controller = controllerWithConfig(config(null, null, "reshaprAllowed=true", null, null, null));

      assertTrue(controller.isAccessAllowed(jwt("{\"reshaprAllowed\":\"true\"}")));
   }

   @Test
   void testIsAccessAllowedRejectsWhenClaimExpressionValueDiffers() throws Exception {
      var controller = controllerWithConfig(config(null, null, "reshaprAllowed=true", null, null, null));

      assertFalse(controller.isAccessAllowed(jwt("{\"reshaprAllowed\":\"false\"}")));
   }

   @Test
   void testIsAccessAllowedRejectsWhenClaimExpressionClaimMissing() throws Exception {
      var controller = controllerWithConfig(config(null, null, "reshaprAllowed=true", null, null, null));

      assertFalse(controller.isAccessAllowed(jwt("{\"other\":\"true\"}")));
   }

   @Test
   void testIsAccessAllowedRejectsWhenClaimExpressionInvalid() throws Exception {
      var controller = controllerWithConfig(config(null, null, "invalid-no-equals", null, null, null));

      assertFalse(controller.isAccessAllowed(jwt("{\"invalid-no-equals\":\"true\"}")));
   }

   @Test
   void testIsAccessAllowedRequiresBothWhenGroupAndClaimAreConfigured() throws Exception {
      var controller = controllerWithConfig(config(null, "reshapr-allowed", "reshaprAllowed=true", null, null, null));

      // Both present -> allowed.
      assertTrue(controller.isAccessAllowed(jwt(
            "{\"groups\":[\"reshapr-allowed\"],\"reshaprAllowed\":\"true\"}")));
      // Group only -> denied.
      assertFalse(controller.isAccessAllowed(jwt("{\"groups\":[\"reshapr-allowed\"]}")));
      // Claim only -> denied.
      assertFalse(controller.isAccessAllowed(jwt("{\"reshaprAllowed\":\"true\"}")));
   }

   // ---------------------------------------------------------------------
   //  resolveDefaultOrganizationFromClaims
   // ---------------------------------------------------------------------

   @Test
   void testResolveDefaultOrganizationReturnsNullWhenNothingConfigured() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, null, null, null));

      assertNull(controller.resolveDefaultOrganizationFromClaims(jwt(
            "{\"groups\":[\"reshapr-org-alpha\"],\"reshaprOrg\":\"beta\"}")));
   }

   @Test
   void testResolveDefaultOrganizationReadsFromClaimWhenConfigured() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, "reshaprOrg", null, null));

      assertEquals("beta", controller.resolveDefaultOrganizationFromClaims(jwt(
            "{\"reshaprOrg\":\"beta\"}")));
   }

   @Test
   void testResolveDefaultOrganizationReturnsNullWhenClaimAbsent() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, "reshaprOrg", null, null));

      assertNull(controller.resolveDefaultOrganizationFromClaims(jwt("{\"other\":\"beta\"}")));
   }

   @Test
   void testResolveDefaultOrganizationReadsFromGroupPrefixStripsHyphen() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, null, "reshapr-org", null));

      assertEquals("alpha", controller.resolveDefaultOrganizationFromClaims(jwt(
            "{\"groups\":[\"reshapr-allowed\",\"reshapr-org-alpha\"]}")));
   }

   @Test
   void testResolveDefaultOrganizationReadsFromGroupPrefixStripsSlash() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, null, "reshapr-org", null));

      assertEquals("gamma", controller.resolveDefaultOrganizationFromClaims(jwt(
            "{\"groups\":[\"reshapr-org/gamma\"]}")));
   }

   @Test
   void testResolveDefaultOrganizationReturnsNullWhenGroupPrefixHasNoMatch() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, null, "reshapr-org", null));

      assertNull(controller.resolveDefaultOrganizationFromClaims(jwt(
            "{\"groups\":[\"other\",\"reshapr-allowed\"]}")));
   }

   @Test
   void testResolveDefaultOrganizationReturnsFixedValueWhenConfigured() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, null, null, "default"));

      assertEquals("default", controller.resolveDefaultOrganizationFromClaims(jwt("{}")));
   }

   @Test
   void testResolveDefaultOrganizationClaimHasPriorityOverGroupPrefixAndValue() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, "reshaprOrg", "reshapr-org", "default"));

      assertEquals("beta", controller.resolveDefaultOrganizationFromClaims(jwt(
            "{\"reshaprOrg\":\"beta\",\"groups\":[\"reshapr-org-alpha\"]}")));
   }

   @Test
   void testResolveDefaultOrganizationGroupPrefixHasPriorityOverValue() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, "reshaprOrg", "reshapr-org", "default"));

      // Claim absent, group prefix matches -> alpha (not default).
      assertEquals("alpha", controller.resolveDefaultOrganizationFromClaims(jwt(
            "{\"groups\":[\"reshapr-org-alpha\"]}")));
   }

   @Test
   void testResolveDefaultOrganizationFallsBackToValueWhenNoClaimAndNoGroupMatch() throws Exception {
      var controller = controllerWithConfig(config(null, null, null, "reshaprOrg", "reshapr-org", "default"));

      assertEquals("default", controller.resolveDefaultOrganizationFromClaims(jwt(
            "{\"groups\":[\"other\"]}")));
   }

   // ---------------------------------------------------------------------
   //  Test helpers
   // ---------------------------------------------------------------------

   private static AuthenticationController controllerWithConfig(AuthenticationIdentityProviderConfig config) {
      return controllerWithConfig(config, null);
   }

   private static AuthenticationController controllerWithConfig(AuthenticationIdentityProviderConfig config,
                                                                  OidcLoginStateStore stateStore) {
      return new AuthenticationController(config, null, null, null, null, stateStore, MAPPER);
   }

   private static JsonNode jwt(String json) throws Exception {
      return MAPPER.readTree(json);
   }

   /** Build an in-memory config exposing exactly the guardrail properties under test. */
   private static AuthenticationIdentityProviderConfig config(
         List<String> scopes,
         String guardGroup, String guardClaim,
         String defaultOrgClaim, String defaultOrgGroupPrefix, String defaultOrgValue) {

      return config(scopes, guardGroup, guardClaim, defaultOrgClaim, defaultOrgGroupPrefix, defaultOrgValue, null);
      }

      private static AuthenticationIdentityProviderConfig configWithRedirectUris(List<String> allowedRedirectUris) {
      return config(null, null, null, null, null, null, allowedRedirectUris);
      }

      private static AuthenticationIdentityProviderConfig config(
         List<String> scopes,
         String guardGroup, String guardClaim,
         String defaultOrgClaim, String defaultOrgGroupPrefix, String defaultOrgValue,
         List<String> allowedRedirectUris) {

      var guard = new AuthenticationIdentityProviderConfig.GuardAccess() {
         @Override public Optional<String> group() { return Optional.ofNullable(guardGroup); }
         @Override public Optional<String> claim() { return Optional.ofNullable(guardClaim); }
      };
      var defaultOrg = new AuthenticationIdentityProviderConfig.DefaultOrganization() {
         @Override public Optional<String> claim() { return Optional.ofNullable(defaultOrgClaim); }
         @Override public Optional<String> groupPrefix() { return Optional.ofNullable(defaultOrgGroupPrefix); }
         @Override public Optional<String> value() { return Optional.ofNullable(defaultOrgValue); }
      };
      return new AuthenticationIdentityProviderConfig() {
         @Override public boolean enabled() { return true; }
         @Override public String url() { return "http://irrelevant/auth"; }
         @Override public String tokenUrl() { return "http://irrelevant/token"; }
         @Override public String clientId() { return "client"; }
         @Override public String clientSecret() { return "secret"; }
         @Override public Optional<List<String>> scopes() { return Optional.ofNullable(scopes); }
         @Override public Optional<List<String>> allowedRedirectUris() { return Optional.ofNullable(allowedRedirectUris); }
         @Override public boolean allowCliLoopbackRedirect() { return true; }
         @Override public GuardAccess guardAccess() { return guard; }
         @Override public DefaultOrganization defaultOrganization() { return defaultOrg; }
      };
   }
}
