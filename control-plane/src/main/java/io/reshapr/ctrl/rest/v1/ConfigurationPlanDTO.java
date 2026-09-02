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
package io.reshapr.ctrl.rest.v1;

import io.reshapr.json.HtmlEncodedStringDeserializer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Data Transfer Object (DTO) for a configuration plan in the Reshapr control plane.
 * @author laurent
 */
@RegisterForReflection
public class ConfigurationPlanDTO {

   protected String id;
   protected String organizationId;
   @Size(max = 255, message = "Name must not exceed 255 characters")
   @JsonDeserialize(using = HtmlEncodedStringDeserializer.class)
   protected String name;
   @Size(max = 255, message = "Description must not exceed 255 characters")
   @JsonDeserialize(using = HtmlEncodedStringDeserializer.class)
   protected String description;
   protected String serviceId;
   protected String backendEndpoint;
   protected Long backendTimeout;
   protected List<String> excludedOperations;
   protected List<String> includedOperations;
   protected List<String> includedArtifacts;
   protected String backendSecretId;
   protected String apiKey;
   protected OAuth2ConfigurationDTO oauth2Configuration;
    // Indicates whether to use the internal identity provider for OAuth2 authentication.
    protected String initialAccessToken;
    protected boolean audit;
   protected CachePolicyDTO cachePolicy;

   public String getId() {
      return id;
   }

   public void setId(String id) {
      this.id = id;
   }

   public String getOrganizationId() {
      return organizationId;
   }

   public void setOrganizationId(String organizationId) {
      this.organizationId = organizationId;
   }

   public String getName() {
      return name;
   }

   public void setName(String name) {
      this.name = name;
   }

   public String getDescription() {
      return description;
   }

   public void setDescription(String description) {
      this.description = description;
   }

   public String getServiceId() {
      return serviceId;
   }

   public void setServiceId(String serviceId) {
      this.serviceId = serviceId;
   }

   public String getBackendEndpoint() {
      return backendEndpoint;
   }

   public void setBackendEndpoint(String backendEndpoint) {
      this.backendEndpoint = backendEndpoint;
   }

   public List<String> getExcludedOperations() {
      return excludedOperations;
   }

   public void setExcludedOperations(List<String> excludedOperations) {
      this.excludedOperations = excludedOperations;
   }

   public List<String> getIncludedOperations() {
      return includedOperations;
   }

   public void setIncludedOperations(List<String> includedOperations) {
      this.includedOperations = includedOperations;
   }

   public List<String> getIncludedArtifacts() {
      return includedArtifacts;
   }

   public void setIncludedArtifacts(List<String> includedArtifacts) {
      this.includedArtifacts = includedArtifacts;
   }

   public String getBackendSecretId() {
      return backendSecretId;
   }

   public void setBackendSecretId(String backendSecretId) {
      this.backendSecretId = backendSecretId;
   }

   public String getApiKey() {
      return apiKey;
   }

   public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
   }

   public OAuth2ConfigurationDTO getOauth2Configuration() {
      return oauth2Configuration;
   }

   public void setOauth2Configuration(OAuth2ConfigurationDTO oauth2Configuration) {
      this.oauth2Configuration = oauth2Configuration;
   }

   public String getInitialAccessToken() {
      return initialAccessToken;
   }

   public void setInitialAccessToken(String initialAccessToken) {
      this.initialAccessToken = initialAccessToken;
   }

   public Long getBackendTimeout() {
      return backendTimeout;
   }

    public void setBackendTimeout(Long backendTimeout) {
       this.backendTimeout = backendTimeout;
    }

    public boolean isAudit() {
       return audit;
    }

    public void setAudit(boolean audit) {
       this.audit = audit;
    }

   public CachePolicyDTO getCachePolicy() {
      return cachePolicy;
   }

   public void setCachePolicy(CachePolicyDTO cachePolicy) {
      this.cachePolicy = cachePolicy;
   }

   /**
    * DTO for the caching configuration of a {@code ConfigurationPlan}.
    * Both fields are optional; when absent the proxy falls back to its built-in defaults.
    */
   @RegisterForReflection
   public static class CachePolicyDTO {
      /** Time-to-live in milliseconds for client-side MCP caching. */
      private Long ttlMs;
      /** Cache scope to advertise (e.g. "public" or "private"). */
      private String cacheScope;

      public Long getTtlMs() { return ttlMs; }
      public void setTtlMs(Long ttlMs) { this.ttlMs = ttlMs; }

      public String getCacheScope() { return cacheScope; }
      public void setCacheScope(String cacheScope) { this.cacheScope = cacheScope; }
   }
}
