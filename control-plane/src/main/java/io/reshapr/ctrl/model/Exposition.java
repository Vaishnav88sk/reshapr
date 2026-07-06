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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.time.OffsetDateTime;
/**
 * An exposition that defines how a service is exposed via Reshapr gateways.
 * @author laurent
 */
@Entity
@Table(name = "expositions", uniqueConstraints = {
      @UniqueConstraint(columnNames = {"service_id", "gateway_group_id", "configuration_plan_id"}),
      @UniqueConstraint(columnNames = {"organization_id", "name"})
})
public class Exposition extends TenantAwareEntity {

   @Column(name = "created_on", columnDefinition = "TIMESTAMP WITH TIME ZONE")
   public OffsetDateTime createdOn;

   /**
    * A human-friendly, organization-unique name for this exposition. Optional (nullable) to avoid a
    * breaking change: when not provided at creation time, a slug is auto-generated from the triple
    * {@code service_name + service_version + configuration_plan_name}. Uniqueness within an
    * organization is enforced by a partial unique index (see Flyway migration).
    */
   @Column(name = "name")
   public String name;

   @ManyToOne(fetch = FetchType.EAGER)
   @Fetch(FetchMode.JOIN)
   @JoinColumn(name="service_id", nullable=false)
   public Service service;

   @ManyToOne(fetch = FetchType.EAGER)
   @Fetch(FetchMode.JOIN)
   @JoinColumn(name="configuration_plan_id", nullable=false)
   public ConfigurationPlan configurationPlan;

   @ManyToOne(fetch = FetchType.EAGER)
   @JoinColumn(name="gateway_group_id", nullable=false)
   public GatewayGroup gatewayGroup;
}
