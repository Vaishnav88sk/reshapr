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

import io.reshapr.ctrl.model.ArtifactType;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Data Transfer Object (DTO) for an artifact in the Reshapr control plane.
 * @param id Unique identifier of the artifact
 * @param organizationId Organization identifier
 * @param name Artifact name
 * @param content Artifact content
 * @param path Artifact path
 * @param mainArtifact Indicates if this is the main artifact
 * @param sourceArtifact Source artifact identifier
 * @param type Artifact type
 * @param capabilities Names of the capabilities declared by a custom artifact (empty for non-custom artifacts)
 */
@RegisterForReflection
public record ArtifactDTO(
      String id,
      String organizationId,
      String name,
      String content,
      String path,
      boolean mainArtifact,
      String sourceArtifact,
      ArtifactType type,
      List<String> capabilities) {
}
