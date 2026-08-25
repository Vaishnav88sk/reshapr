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

   @Override
   public String toString() {
      return "ConfigurationEntry[id=" + id + ", name= " + name + ", backendEndpoint=" + backendEndpoint
            + ", excludedOperations=" + excludedOperations + ", includedOperations=" + includedOperations
            + ", audit=" + audit + ", apiKey=" +  apiKeyString() + "]";
   }

   private String apiKeyString() {
      return (apiKey != null ? "*******" : "null");
   }

   /**
    * Caching directives forwarded from the ConfigurationPlan to MCP responses.
    * Both fields are optional; when null the proxy falls back to the defaults.
    *
    * @param ttlMs      Time-to-live in milliseconds.
    * @param cacheScope Cache scope (e.g. "public" or "private").
    */
   public record CachingConfigurationEntry(Long ttlMs, String cacheScope) {

      public static final long DEFAULT_TTL_MS = 30_000L;
      public static final String DEFAULT_CACHE_SCOPE = "public";

      /** Returns {@code ttlMs} if set, otherwise the default (30 000 ms). */
      public long effectiveTtlMs() {
         return ttlMs != null ? ttlMs : DEFAULT_TTL_MS;
      }

      /** Returns {@code cacheScope} if set, otherwise the default ("public"). */
      public String effectiveCacheScope() {
         return cacheScope != null ? cacheScope : DEFAULT_CACHE_SCOPE;
      }
   }
}
