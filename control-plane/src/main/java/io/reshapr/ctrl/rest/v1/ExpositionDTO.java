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

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.OffsetDateTime;

/**
 * Data Transfer Object (DTO) for an exposition in the Reshapr control plane.
 * @param id
 * @param organizationId
 * @param name the optional organization-unique name of the exposition (null when unnamed)
 * @param createdOn
 * @param gatewayGroup
 * @param configurationPlan
 * @param service
 * @author laurent
 */
@RegisterForReflection
public record ExpositionDTO(
      String id,
      String organizationId,
      String name,
      OffsetDateTime createdOn,
      GatewayGroupDTO gatewayGroup,
      ConfigurationPlanDTO configurationPlan,
      ServiceDTO service) {
}
