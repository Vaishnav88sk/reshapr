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
package io.reshapr.ctrl.service;

import io.reshapr.ctrl.model.ApiToken;
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.repository.ApiTokenRepository;

import io.reshapr.ctrl.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

@ApplicationScoped
public class TokenManagerService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** Shared, thread-safe SecureRandom — reused to avoid repeated entropy-pool drain. */
   private static final SecureRandom SECURE_RANDOM = new SecureRandom();

   private final ApiTokenRepository apiTokenRepository;
   private final UserRepository userRepository;

   /**
    * Build a new TokenManagerService with the required dependencies.
    * @param apiTokenRepository The repository to access API tokens.
    * @param userRepository The repository to access user data.
    */
   public TokenManagerService(ApiTokenRepository apiTokenRepository, UserRepository userRepository) {
      this.apiTokenRepository = apiTokenRepository;
      this.userRepository = userRepository;
   }

   public List<ApiToken> getApiTokens(String organizationId) {
      logger.debugf("Retrieving API tokens for organization %s", organizationId);
      return apiTokenRepository.findByOrganizationId(organizationId);
   }

   @Transactional
   public ApiToken generateApiToken(String name, String organizationId, int validityDays, String username) throws DependencyNotFoundException {
      logger.debugf("Generating new API token with name %s for organization %s", name, organizationId);

      // Check prerequisites: user must exist.
      User user = userRepository.findByUsername(username);
      if (user == null) {
         logger.errorf("User with name %s not found", username);
         throw new DependencyNotFoundException("User with name " + username + " not found");
      }

      // Generate a random token string - 32 chars length in base64 URL-safe encoding.
      String tokenValue = generateRandomBase64Token(32);

      ApiToken token = new ApiToken();
      token.name = name;
      token.token = tokenValue;
      token.organizationId = organizationId;
      token.user = user;
      // Set the validity date.
      token.validUntil = OffsetDateTime.ofInstant(
            Instant.ofEpochMilli(System.currentTimeMillis() + validityDays * 24L * 60L * 60L * 1000L),
            ZoneId.systemDefault());
      apiTokenRepository.persist(token);
      return token;
   }

   @Transactional
   public void revokeApiToken(ApiToken token) {
      apiTokenRepository.delete(token);
   }

   private static String generateRandomBase64Token(int byteLength) {
      byte[] token = new byte[byteLength];
      SECURE_RANDOM.nextBytes(token);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
   }
}
