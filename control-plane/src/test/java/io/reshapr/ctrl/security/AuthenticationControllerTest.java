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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests for the JWT parsing / scope building helpers of {@link AuthenticationController}.
 * The controller is instantiated with a hand-rolled config and {@code null} for other collaborators:
 * the tested helpers only rely on {@link AuthenticationIdentityProviderConfig} and Jackson.
 * @author laurent
 */
class AuthenticationControllerTest {

   private static final ObjectMapper MAPPER = new ObjectMapper();

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
      return new AuthenticationController(config, null, null, null, null, MAPPER);
   }

   private static JsonNode jwt(String json) throws Exception {
      return MAPPER.readTree(json);
   }

   /** Build an in-memory config exposing exactly the guardrail properties under test. */
   private static AuthenticationIdentityProviderConfig config(
         List<String> scopes,
         String guardGroup, String guardClaim,
         String defaultOrgClaim, String defaultOrgGroupPrefix, String defaultOrgValue) {

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
         @Override public GuardAccess guardAccess() { return guard; }
         @Override public DefaultOrganization defaultOrganization() { return defaultOrg; }
      };
   }
}
