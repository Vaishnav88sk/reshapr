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
package io.reshapr.proxy.registry;

import java.util.List;

/**
 * Represents a registry exposition configuration entry.
 * @author laurent
 */
public record ConfigurationEntry(
      String id,
      String name,
      String backendEndpoint,
      Long backendTimeout,
      List<String> excludedOperations,
      List<String> includedOperations,
      String apiKey,
      OAuth2ConfigurationEntry oauth2Configuration,
      SecretEntry backendSecret,
      boolean audit,
      CachingConfigurationEntry cachingConfiguration) {

   public ConfigurationEntry(String id, String name, String backendEndpoint, Long backendTimeout,
                             List<String> excludedOperations, List<String> includedOperations,
                             String apiKey, OAuth2ConfigurationEntry oauth2Configuration, SecretEntry backendSecret) {
      this(id, name, backendEndpoint, backendTimeout, excludedOperations, includedOperations, apiKey, oauth2Configuration, backendSecret, false, null);
   }

   public ConfigurationEntry(String id, String name, String backendEndpoint, Long backendTimeout,
                             List<String> excludedOperations, List<String> includedOperations,
                             String apiKey, OAuth2ConfigurationEntry oauth2Configuration, SecretEntry backendSecret, boolean audit) {
      this(id, name, backendEndpoint, backendTimeout, excludedOperations, includedOperations, apiKey, oauth2Configuration, backendSecret, audit, null);
   }

   public record CachingConfigurationEntry(Long ttlMs, String cacheScope) {}

   @Override
   public String toString() {
      return "ConfigurationEntry[id=" + id + ", name= " + name + ", backendEndpoint=" + backendEndpoint
            + ", excludedOperations=" + excludedOperations + ", includedOperations=" + includedOperations
            + ", audit=" + audit + ", apiKey=" +  apiKeyString() + "]";
   }

   private String apiKeyString() {
      return (apiKey != null ? "*******" : "null");
   }
}
