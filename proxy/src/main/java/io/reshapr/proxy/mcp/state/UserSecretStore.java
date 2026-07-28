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
package io.reshapr.proxy.mcp.state;

import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.infinispan.commons.api.BasicCache;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Store for per-user elicited secrets used in the stateless MCP mode (protocol {@code >= 2026-07-28}).
 * <p>
 * Since there is no session in stateless mode, an elicited backend secret is bound to a stable user
 * identity rather than to a session. The store is backed by the replicated {@code user-secret-store}
 * Infinispan cache (shared across gateway replicas), keyed by a composite of the user key and the
 * secret reference.
 * <ul>
 *   <li>{@code userKey} — a stable, unique-per-user identity composed of the JWT {@code iss} and
 *       {@code sub} claims (built by the caller, see plan Step 8) to avoid cross-IdP collisions.</li>
 *   <li>{@code secretRef} — a stable reference to the backend secret, typically
 *       {@code organizationId + '/' + secret.name()} (built by the caller).</li>
 * </ul>
 * The per-entry lifespan should be aligned on the OAuth2 token {@code expires_in} or equivalent when known;
 * when it expires, the tool call transparently triggers a fresh elicitation.
 * @author laurent
 */
@ApplicationScoped
public class UserSecretStore {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** Separator between the user key and the secret reference in the composite cache key. */
   private static final String KEY_SEPARATOR = "::";

   private final BasicCache<String, String> userSecretCache;

   /**
    * Create a new UserSecretStore backed by the replicated user-secret cache.
    * @param userSecretCache The {@code user-secret-store} cache (produced by {@link CacheProducer}).
    */
   public UserSecretStore(BasicCache<String, String> userSecretCache) {
      this.userSecretCache = userSecretCache;
   }

   /**
    * Retrieve the elicited secret value for a given user and secret reference.
    * @param userKey The stable user identity (JWT {@code iss + sub}).
    * @param secretRef The stable secret reference (e.g. {@code organizationId + '/' + secret.name()}).
    * @return The stored secret value, or {@code null} if none is present (or it expired).
    */
   @Nullable
   public String getSecret(String userKey, String secretRef) {
      String key = composeKey(userKey, secretRef);
      logger.tracef("Retrieving user secret for key '%s'", key);
      return userSecretCache.get(key);
   }

   /**
    * Store the elicited secret value for a given user and secret reference, using the cache default
    * lifespan (configured in {@code infinispan.xml}).
    * @param userKey The stable user identity (JWT {@code iss + sub}).
    * @param secretRef The stable secret reference.
    * @param value The elicited secret value (token).
    */
   public void putSecret(String userKey, String secretRef, String value) {
      String key = composeKey(userKey, secretRef);
      logger.debugf("Storing user secret for key '%s'", key);
      userSecretCache.put(key, value);
   }

   /**
    * Store the elicited secret value for a given user and secret reference with an explicit lifespan,
    * meant to be aligned on the OAuth2 token {@code expires_in}. A non-positive or {@code null}
    * lifespan falls back to the cache default lifespan.
    * @param userKey The stable user identity (JWT {@code iss + sub}).
    * @param secretRef The stable secret reference.
    * @param value The elicited secret value (token).
    * @param lifespan The lifespan of the entry (typically the token remaining validity), or {@code null}.
    */
   public void putSecret(String userKey, String secretRef, String value, @Nullable Duration lifespan) {
      if (lifespan == null || lifespan.isZero() || lifespan.isNegative()) {
         putSecret(userKey, secretRef, value);
         return;
      }
      String key = composeKey(userKey, secretRef);
      logger.debugf("Storing user secret for key '%s' with lifespan %s", key, lifespan);
      userSecretCache.put(key, value, lifespan.toMillis(), TimeUnit.MILLISECONDS);
   }

   /**
    * Remove the elicited secret value for a given user and secret reference.
    * @param userKey The stable user identity (JWT {@code iss + sub}).
    * @param secretRef The stable secret reference.
    */
   public void removeSecret(String userKey, String secretRef) {
      String key = composeKey(userKey, secretRef);
      logger.debugf("Removing user secret for key '%s'", key);
      userSecretCache.remove(key);
   }

   /** Build the composite cache key from the user key and the secret reference. */
   private static String composeKey(String userKey, String secretRef) {
      return userKey + KEY_SEPARATOR + secretRef;
   }
}

