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
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;

/**
 * Holds information about an elicitation process. An elicitation is bound to <b>either</b> a session
 * ({@code sessionId}, legacy mode &lt; 2026-07-28) <b>or</b> to a user ({@code userKey} = JWT
 * {@code iss + sub}, stateless mode &gt;= 2026-07-28) — never both. {@link #isStateless()} tells which.
 * @author laurent
 */
public class ElicitationInfo {

   private final String id;
   private final String sessionId;
   private final String userKey;
   private final String organizationId;
   private final String backendEndpoint;
   private final SecretEntry secretEntry;
   private final String requestState;

   /**
    * Full constructor (used by Protostream). Prefer {@link #forSession} / {@link #forUser}.
    * @param id The elicitation id
    * @param sessionId The bound session id (legacy mode), or {@code null}
    * @param userKey The bound user key {@code iss + sub} (stateless mode), or {@code null}
    * @param organizationId The organization id
    * @param backendEndpoint The backend endpoint the secret is elicited for
    * @param secretEntry The secret entry being elicited
    * @param requestState The opaque resume token bound to the paused request (stateless URL Mode OAuth), or {@code null}
    */
   @ProtoFactory
   public ElicitationInfo(String id, @Nullable String sessionId, @Nullable String userKey,
                          String organizationId, String backendEndpoint, SecretEntry secretEntry,
                          @Nullable String requestState) {
      this.id = id;
      this.sessionId = sessionId;
      this.userKey = userKey;
      this.organizationId = organizationId;
      this.backendEndpoint = backendEndpoint;
      this.secretEntry = secretEntry;
      this.requestState = requestState;
   }

   /** Create a session-bound elicitation (legacy mode). */
   public static ElicitationInfo forSession(String id, String sessionId, String organizationId,
                                            String backendEndpoint, SecretEntry secretEntry) {
      return new ElicitationInfo(id, sessionId, null, organizationId, backendEndpoint, secretEntry, null);
   }

   /** Create a user-bound elicitation (stateless mode), carrying the opaque {@code requestState} resume token. */
   public static ElicitationInfo forUser(String id, String userKey, String organizationId,
                                         String backendEndpoint, SecretEntry secretEntry,
                                         @Nullable String requestState) {
      return new ElicitationInfo(id, null, userKey, organizationId, backendEndpoint, secretEntry, requestState);
   }

   @ProtoField(1)
   public String getId() {
      return id;
   }
   @ProtoField(2)
   @Nullable
   public String getSessionId() {
      return sessionId;
   }
   @ProtoField(3)
   @Nullable
   public String getUserKey() {
      return userKey;
   }
   @ProtoField(4)
   public String getOrganizationId() {
      return organizationId;
   }
   @ProtoField(5)
   public String getBackendEndpoint() {
      return backendEndpoint;
   }
   @ProtoField(6)
   public SecretEntry getSecretEntry() {
      return secretEntry;
   }
   @ProtoField(7)
   @Nullable
   public String getRequestState() {
      return requestState;
   }

   /** Whether this elicitation is bound to a user (stateless mode) rather than a session (legacy). */
   public boolean isStateless() {
      return userKey != null;
   }
}
