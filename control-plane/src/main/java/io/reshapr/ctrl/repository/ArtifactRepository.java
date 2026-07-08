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
package io.reshapr.ctrl.repository;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ArtifactType;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Repository for managing artifacts in the Reshapr control plane.
 * @author laurent
 */
@ApplicationScoped
public class ArtifactRepository implements PanacheRepositoryBase<Artifact, String> {

   public List<Artifact> findByServiceIdAndSourceArtifact(String serviceId, String sourceArtifact) {
      return find("service.id = ?1 and sourcePath = ?2", serviceId, sourceArtifact).list();
   }

   public PanacheQuery<Artifact> findByServiceId(String serviceId) {
      return find("service.id", Sort.ascending("name"), serviceId);
   }

   /**
    * Finds the artifact of a service by its name. Names are unique within a service (enforced by the
    * {@code ux_artifacts_service_name} unique index), so this returns at most one artifact.
    */
   public Artifact findByServiceIdAndName(String serviceId, String name) {
      return find("service.id = ?1 and name = ?2", serviceId, name).firstResult();
   }

   /**
    * Lists the attached artifacts of a service, i.e. the ones a ConfigurationPlan can select via
    * {@code includedArtifacts} (Prompts, Resources, CustomTools, OutputFilters). The service main
    * artifact and the derived protobuf schema are excluded, mirroring the proxy registration logic.
    */
   public List<Artifact> findAttachedByServiceId(String serviceId) {
      return find("service.id = ?1 and mainArtifact = false and type <> ?2",
            Sort.ascending("name"), serviceId, ArtifactType.PROTOBUF_SCHEMA).list();
   }

   /**
    * Returns the set of attached artifact names for a service, used to validate a plan's
    * {@code includedArtifacts} selection.
    */
   public Set<String> findAttachedNamesByServiceId(String serviceId) {
      return findAttachedByServiceId(serviceId).stream()
            .map(artifact -> artifact.name)
            .collect(Collectors.toSet());
   }
}
