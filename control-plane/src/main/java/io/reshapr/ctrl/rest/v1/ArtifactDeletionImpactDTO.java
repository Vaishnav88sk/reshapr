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

import java.util.List;

/**
 * Data Transfer Object describing the impact of deleting an artifact on the configuration plans of its
 * service. Returned as a non-mutating preview by {@code GET /artifacts/{id}/deletion-impact} and as the
 * applied result by {@code DELETE /artifacts/{id}}.
 * @param artifactId The id of the artifact.
 * @param artifactName The name of the artifact.
 * @param mainArtifact Whether the artifact is the service main artifact.
 * @param impactedPlans The configuration plans referencing the artifact in their selection.
 * @author laurent
 */
@RegisterForReflection
public record ArtifactDeletionImpactDTO(
      String artifactId,
      String artifactName,
      boolean mainArtifact,
      List<ImpactedPlanDTO> impactedPlans) {

   /**
    * A configuration plan impacted by an artifact deletion.
    * @param id The id of the configuration plan.
    * @param name The name of the configuration plan.
    * @param fallsBackToAll Whether the plan selection becomes empty (falls back to all attached artifacts).
    */
   public record ImpactedPlanDTO(String id, String name, boolean fallsBackToAll) {
   }
}

