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

import io.reshapr.proxy.registry.SecretEntry;

import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import org.infinispan.commons.api.BasicCache;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Store for managing elicitation information within the Reshapr MCP.
 * Backed by the replicated {@code elicitation-store} Infinispan cache so in-flight elicitations are
 * shared across gateway replicas (the OAuth2 callback may hit a different replica than the tool call).
 * @author laurent
 */
@ApplicationScoped
public class ElicitationStore {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final BasicCache<String, ElicitationInfo> elicitationCache;

   /**
    * Create a new ElicitationStore with a replicated cache for elicitation information.
    * @param elicitationCache The cache to use for storing elicitation information (produced by {@link CacheProducer}).
    */
   public ElicitationStore(BasicCache<String, ElicitationInfo> elicitationCache) {
      this.elicitationCache = elicitationCache;
   }

   /**
    * Initialize a new session-bound elicitation (legacy mode &lt; 2026-07-28).
    * @param sessionId The bound session id
    * @param organizationId The organization id
    * @param backendEndpoint The backend endpoint the secret is elicited for
    * @param secretEntry The secret entry being elicited
    * @return The newly created elicitation id
    */
   public String initializeElicitation(String sessionId, String organizationId, String backendEndpoint, SecretEntry secretEntry) {
      String elicitationId = UUID.randomUUID().toString();
      logger.debugf("Initializing new session-bound elicitation with id '%s' for session '%s'", elicitationId, sessionId);
      elicitationCache.put(elicitationId,
            ElicitationInfo.forSession(elicitationId, sessionId, organizationId, backendEndpoint, secretEntry));
      return elicitationId;
   }

   /**
    * Initialize a new user-bound elicitation (stateless mode &gt;= 2026-07-28).
    * @param userKey The bound user key ({@code iss + sub})
    * @param organizationId The organization id
    * @param backendEndpoint The backend endpoint the secret is elicited for
    * @param secretEntry The secret entry being elicited
    * @param requestState The opaque resume token bound to the paused request (URL Mode OAuth), or {@code null}
    * @return The newly created elicitation id
    */
   public String initializeUserElicitation(String userKey, String organizationId, String backendEndpoint,
                                           SecretEntry secretEntry, String requestState) {
      String elicitationId = UUID.randomUUID().toString();
      logger.debugf("Initializing new user-bound elicitation with id '%s' for user key '%s'", elicitationId, userKey);
      elicitationCache.put(elicitationId,
            ElicitationInfo.forUser(elicitationId, userKey, organizationId, backendEndpoint, secretEntry, requestState));
      return elicitationId;
   }

   /**
    * Retrieve elicitation information for a given elicitation id.
    * @param elicitationId The elicitation id to retrieve information for
    * @return the value to which the specified key is mapped, or null if this cache does not contain a mapping for the key
    */
   @Nullable
   public ElicitationInfo getElicitationInfo(String elicitationId) {
      logger.tracef("Retrieving elicitation information for elicitation id '%s'", elicitationId);
      return elicitationCache.get(elicitationId);
   }

   /**
    * Remove elicitation information for a given elicitation id.
    * @param elicitationId The elicitation id to remove information for
    */
   public void removeElicitationInfo(String elicitationId) {
      logger.debugf("Removing elicitation information for elicitation id '%s'", elicitationId);
      elicitationCache.remove(elicitationId);
   }
}
