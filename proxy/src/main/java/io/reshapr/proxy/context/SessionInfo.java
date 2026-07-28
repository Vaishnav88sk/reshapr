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
package io.reshapr.proxy.context;

import io.reshapr.proxy.registry.SecretEntry;

import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;

import java.util.ArrayList;
import java.util.List;
/**
 * Holds information about an MCP session.
 * @author laurent
 */
public class SessionInfo {

   private final String id;
   private final String serviceId;
   private final String protocolVersion;

   /**
    * Elicited secret values collected during this session, stored as a list of pairs so the whole
    * {@link SessionInfo} is marshalable with Protostream (a {@code Map} keyed by a complex message
    * type is not supported). Lookup keeps the former equality-based semantics on {@link SecretEntry}.
    */
   private final List<SecretValueEntry> elicitationSecretValues;

   /**
    * Create a new SessionInformation instance with mandatory fields.
    * @param id The session unique identifier
    * @param serviceId The service identifier associated with the session
    * @param protocolVersion The protocol version used in the session
    */
   public SessionInfo(String id, String serviceId, String protocolVersion) {
      this(id, serviceId, protocolVersion, new ArrayList<>());
   }

   /**
    * Full constructor used by Protostream to rebuild a marshaled session.
    * @param id The session unique identifier
    * @param serviceId The service identifier associated with the session
    * @param protocolVersion The protocol version used in the session
    * @param elicitationSecretValues The elicited secret values collected so far (may be null)
    */
   @ProtoFactory
   public SessionInfo(String id, String serviceId, String protocolVersion, List<SecretValueEntry> elicitationSecretValues) {
      this.id = id;
      this.serviceId = serviceId;
      this.protocolVersion = protocolVersion;
      this.elicitationSecretValues = elicitationSecretValues != null ? new ArrayList<>(elicitationSecretValues) : new ArrayList<>();
   }

   @ProtoField(1)
   public String getId() {
      return id;
   }
   @ProtoField(2)
   public String getServiceId() {
      return serviceId;
   }
   @ProtoField(3)
   public String getProtocolVersion() {
      return protocolVersion;
   }

   @ProtoField(number = 4, collectionImplementation = ArrayList.class)
   public List<SecretValueEntry> getElicitationSecretValues() {
      return elicitationSecretValues;
   }

   public void setSecretValue(SecretEntry secretEntry, String value) {
      removeSecretValue(secretEntry);
      elicitationSecretValues.add(new SecretValueEntry(secretEntry, value));
   }
   public String getSecretValue(SecretEntry secretEntry) {
      return elicitationSecretValues.stream()
            .filter(entry -> entry.secret().equals(secretEntry))
            .map(SecretValueEntry::value)
            .findFirst()
            .orElse(null);
   }

   public void removeSecretValue(SecretEntry secretEntry) {
      elicitationSecretValues.removeIf(entry -> entry.secret().equals(secretEntry));
   }
}
