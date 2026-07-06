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
package io.reshapr.ctrl.service;

import java.util.List;

/**
 * Describes the impact of deleting an artifact on the configuration plans of its service. Used both as a
 * non-mutating preview (before deletion) and as the applied result (after deletion). Each impacted plan is a
 * plan whose {@code includedArtifacts} selection references the artifact by name; when that reference is the
 * only remaining selection, the plan falls back to "all attached artifacts".
 * @param artifactId The id of the artifact being (or to be) deleted.
 * @param artifactName The name of the artifact being (or to be) deleted.
 * @param mainArtifact Whether the artifact is the service main artifact.
 * @param impactedPlans The configuration plans referencing the artifact in their selection.
 * @author laurent
 */
public record ArtifactDeletionImpact(
      String artifactId,
      String artifactName,
      boolean mainArtifact,
      List<ImpactedPlan> impactedPlans) {

   /**
    * A configuration plan impacted by an artifact deletion.
    * @param id The id of the configuration plan.
    * @param name The name of the configuration plan.
    * @param fallsBackToAll Whether removing the artifact name empties the plan selection, making it fall
    *                       back to "all attached artifacts of the service".
    */
   public record ImpactedPlan(String id, String name, boolean fallsBackToAll) {
   }
}

