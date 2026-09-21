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

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * A store for pending OIDC login states, which are used to correlate the initial login request with the
 * callback from the OIDC provider.
 * @author laurent
 */
@ApplicationScoped
public class OidcLoginStateStore {

   private static final Duration LOGIN_TTL = Duration.ofMinutes(5);
   private static final Duration ONBOARDING_TTL = Duration.ofMinutes(15);

   private final IMap<String, PendingOidcLogin> loginStates;
   private final IMap<String, PendingOidcOnboarding> onboardingStates;

   public OidcLoginStateStore(HazelcastInstance hazelcast) {
      loginStates = hazelcast.getMap("oidc-login-states");
      onboardingStates = hazelcast.getMap("oidc-onboarding-states");
   }

   public void createLogin(String id, PendingOidcLogin state) {
      loginStates.set(id, state, LOGIN_TTL.toSeconds(), TimeUnit.SECONDS);
   }

   public PendingOidcLogin consumeLogin(String id) {
      return loginStates.remove(id);
   }

   public void createOnboarding(String id, PendingOidcOnboarding state) {
      onboardingStates.set(
            id, state, ONBOARDING_TTL.toSeconds(), TimeUnit.SECONDS);
   }

   public PendingOidcOnboarding consumeOnboarding(String id) {
      return onboardingStates.remove(id);
   }

   /**
    * A pending OIDC login state, which is stored in the {@link OidcLoginStateStore} until the user completes
    * the login flow.
    */
   public record PendingOidcLogin(
         String returnUri,
         String nonce,
         Instant createdAt) implements Serializable {
   }

   /**
    * A pending OIDC onboarding state, which is stored in the {@link OidcLoginStateStore} until the user completes
    * the onboarding flow.
    */
   public record PendingOidcOnboarding(
         String issuer,
         String subject,
         String username,
         String email,
         String firstName,
         String lastName,
         String returnUri,
         Instant createdAt) implements Serializable {
   }
}
