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
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

import java.util.Map;

/**
 * A group of gateways that can be managed together in the Reshapr control plane.
 * @author laurent
 */
@Entity
@Table(name = "gateway_groups")
public class GatewayGroup extends BaseEntity {

   /** Id of the default gateway group provisioned by Flyway (see V1.0.1__data.sql). */
   public static final String DEFAULT_GATEWAY_GROUP_ID = "1";

   public String name;

   @Column(name = "organization_id", nullable = false)
   public String organizationId;

   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB")
   public Map<String, String> labels;

   // Solution belows creates a join table with a single column for labels.
//   @ElementCollection(fetch=EAGER)
//   @CollectionTable(name = "gateway_groups_labels",
//         joinColumns = {@JoinColumn(name = "gateway_group_id", referencedColumnName = "id")})
//   @MapKeyColumn(name = "key")
//   @Column(name = "value")
//   public Map<String, String> labels;
}
