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

import io.reshapr.ctrl.model.ApiToken;
import io.reshapr.ctrl.repository.ApiTokenRepository;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.jboss.logging.Logger;

/**
 * Identity provider for API token authentication.
 * @author laurent
 */
@Priority(1)
@ApplicationScoped
public class ApiTokenIdentityProvider implements IdentityProvider<ApiTokenAuthenticationRequest> {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final ApiTokenRepository tokenRepository;

   public ApiTokenIdentityProvider(ApiTokenRepository tokenRepository) {
      this.tokenRepository = tokenRepository;
   }

   @Override
   public Class<ApiTokenAuthenticationRequest> getRequestType() {
      return ApiTokenAuthenticationRequest.class;
   }

   @Override
   @ActivateRequestContext
   public Uni<SecurityIdentity> authenticate(ApiTokenAuthenticationRequest request, AuthenticationRequestContext context) {
      logger.tracef("authenticate() called with token: %s", request.getToken().getToken());

      int dashIndex = request.getToken().getToken().indexOf("-");
      if (dashIndex == -1) {
         logger.warnf("authenticate(): Invalid token format, missing organizationId: %s", request.getToken().getToken());
         return Uni.createFrom().nullItem();
      }
      String organizationId = request.getToken().getToken().substring(0, dashIndex);
      String rawToken = request.getToken().getToken().substring(dashIndex + 1);

      logger.debugf("authenticate(): Extracted organizationId: %s", organizationId);
      logger.tracef("authenticate(): Extracted rawToken: %s", rawToken);

      // Set the current tenant context for the organization.
      ReshaprTenantContext.setCurrentTenant(organizationId);

      ApiToken apiToken = tokenRepository.findByToken(rawToken);
      // The organizationId is taken from the caller-supplied token prefix and is therefore untrusted: it must match
      // the organization the token actually belongs to. Otherwise a valid token could be replayed under an arbitrary
      // organization prefix, breaking tenant isolation.
      if (apiToken != null && apiToken.isValid() && apiToken.organizationId.equals(organizationId)) {
         logger.debugf("authenticate(): Found valid API token: %s", apiToken.token);

         return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
               .setPrincipal(new QuarkusPrincipal(apiToken.user.username))
               .addCredential(request.getToken())
               .build());
      }
      // Authentication failed: unknown/expired token or organization mismatch.
      logger.warnf("authenticate(): Invalid or expired API token, or organization mismatch: %s", request.getToken().getToken());
      return Uni.createFrom().nullItem();
   }
}
