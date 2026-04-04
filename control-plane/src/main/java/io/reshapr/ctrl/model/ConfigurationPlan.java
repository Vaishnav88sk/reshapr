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
package io.reshapr.ctrl.model;

import io.reshapr.ctrl.security.CipheredAttributeConverter;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

import java.util.List;

import static jakarta.persistence.FetchType.EAGER;

/**
 * An exposition configuration that defines how a service is exposed via Reshapr gateways.
 * @author laurent
 */
@Entity
@Table(name = "configuration_plans")
public class ConfigurationPlan extends TenantAwareEntity {

   @Column(nullable = false)
   public String name;
   public String description;

   @ManyToOne(fetch = EAGER)
   public Service service;

   @Column(name = "backend_endpoint", nullable = false)
   public String backendEndpoint;

   @Column(name = "backend_endpoint_timeout")
   public Long backendEndpointTimeout;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "excluded_operations")
   public List<String> excludedOperations;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "included_operations")
   public List<String> includedOperations;

   // Solution belows creates a join table with a single column for operations.
//   @ElementCollection(fetch = EAGER)
//   @CollectionTable(name = "config_plans_exclusions", joinColumns = @JoinColumn(name = "id"))
//   @Column(name = "excluded_operations")
//   public List<String> excludedOperations;

   @Column(name = "api_key")
   @Convert(converter = CipheredAttributeConverter.class)
   public String apiKey;

   @Column(name = "initial_access_token")
   @Convert(converter = CipheredAttributeConverter.class)
   public String initialAccessToken;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "oauth2_configuration")
   public OAuth2Configuration oauth2Configuration;

   @ManyToOne(fetch = EAGER)
   @JoinColumn(name = "backend_secret_id")
   public Secret backendSecret;

   public record OAuth2Configuration(
         List<String> authorizationServers,
         String jwksUri,
         List<String> scopes
   ) {
   }
}
