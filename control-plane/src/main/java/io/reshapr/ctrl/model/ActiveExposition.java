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

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A comprehensive view of and exposition that defines how a service is exposed via Reshapr gateways.
 * @author laurent
 */
@Entity
@Immutable
@Table(name = "active_expositions")
public class ActiveExposition extends TenantAwareEntity {

   @Column(name = "created_on", columnDefinition = "TIMESTAMP WITH TIME ZONE")
   public OffsetDateTime createdOn;

   @Column(name = "exposition_name")
   public String name;

   @Embedded
   @AttributeOverride(name="id", column=@Column(name="service_id"))
   @AttributeOverride(name="name", column=@Column(name="service_name"))
   @AttributeOverride(name="version", column=@Column(name="service_version"))
   @AttributeOverride(name="type", column=@Column(name="service_type", columnDefinition="VARCHAR(255)"))
   public ServiceView service;

   @Embedded
   @AttributeOverride(name="id", column=@Column(name="config_id"))
   @AttributeOverride(name="backendEndpoint", column=@Column(name="config_backend_endpoint"))
   public ConfigurationPlanView configurationPlan;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB", name = "gateways")
   public List<GatewayView> gateways;

   @Embeddable
   public record ServiceView(
      String id,
      String name,
      String version,
      @Enumerated(EnumType.STRING)
      ServiceType type
   ) {
   }

   @Embeddable
   public record ConfigurationPlanView(
      String id,
      String backendEndpoint
   ) {
   }

   public record GatewayView(
      String id,
      String name,
      List<String> fqdns
   ) {
   }
}

