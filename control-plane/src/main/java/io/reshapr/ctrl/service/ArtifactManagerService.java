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

import io.reshapr.ctrl.model.Artifact;
import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.ctrl.repository.ConfigurationPlanRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing individual artifacts lifecycle in the Reshapr control plane, notably their deletion
 * with cascade cleanup on the {@link ConfigurationPlan#includedArtifacts} selections that reference them by
 * name, and propagation of the change to the gateways.
 * @author laurent
 */
@ApplicationScoped
public class ArtifactManagerService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final ArtifactRepository artifactRepository;
   private final ConfigurationPlanRepository configurationPlanRepository;
   private final ExpositionManagerService expositionManagerService;

   /**
    * Build a new ArtifactManagerService with the required dependencies.
    * @param artifactRepository The repository for managing artifacts.
    * @param configurationPlanRepository The repository for managing configuration plans.
    * @param expositionManagerService The service used to propagate changes to the gateways.
    */
   public ArtifactManagerService(ArtifactRepository artifactRepository,
                                 ConfigurationPlanRepository configurationPlanRepository,
                                 ExpositionManagerService expositionManagerService) {
      this.artifactRepository = artifactRepository;
      this.configurationPlanRepository = configurationPlanRepository;
      this.expositionManagerService = expositionManagerService;
   }

   /**
    * Computes, without mutating anything, the impact of deleting the given artifact: the configuration plans
    * of its service whose {@code includedArtifacts} selection references the artifact by name, and whether
    * each such plan would fall back to "all attached artifacts" once the reference is removed. Intended to
    * back a confirmation prompt on the CLI/Web UI before the actual deletion.
    * @param artifactId The id of the artifact.
    * @return The deletion impact preview.
    * @throws DependencyNotFoundException if no artifact matches the given id.
    */
   @Transactional
   public ArtifactDeletionImpact getArtifactDeletionImpact(String artifactId) throws DependencyNotFoundException {
      Artifact artifact = artifactRepository.findById(artifactId);
      if (artifact == null) {
         logger.errorf("Artifact with id %s not found", artifactId);
         throw new DependencyNotFoundException("Artifact with id " + artifactId + " not found");
      }
      return computeImpact(artifact);
   }

   /**
    * Deletes the given artifact and cascades the change on the configuration plans of its service: the
    * artifact name is removed from every {@code includedArtifacts} selection that references it. A plan whose
    * selection becomes empty falls back to "all attached artifacts". The change is then propagated to the
    * gateways (exposition update events, triggering the proxy WorkCache invalidation and re-parse).
    * @param artifactId The id of the artifact to delete.
    * @return The applied deletion impact (impacted plans and whether they fell back to "all").
    * @throws DependencyNotFoundException if no artifact matches the given id.
    */
   @Transactional
   public ArtifactDeletionImpact deleteArtifact(String artifactId) throws DependencyNotFoundException {
      Artifact artifact = artifactRepository.findById(artifactId);
      if (artifact == null) {
         logger.errorf("Artifact with id %s not found", artifactId);
         throw new DependencyNotFoundException("Artifact with id " + artifactId + " not found");
      }
      Service service = artifact.service;
      String artifactName = artifact.name;
      logger.infof("Deleting artifact '%s' (id %s) of service '%s'", artifactName, artifactId,
            service != null ? service.id : null);

      ArtifactDeletionImpact impact = computeImpact(artifact);

      // Cascade: remove the artifact name from the selection of every referencing plan.
      if (service != null) {
         for (ConfigurationPlan plan : configurationPlanRepository.findByServiceId(service.id)) {
            if (plan.includedArtifacts != null && plan.includedArtifacts.contains(artifactName)) {
               // Reassign a fresh list so the JSONB attribute is detected as dirty and persisted.
               List<String> updated = new ArrayList<>(plan.includedArtifacts);
               updated.remove(artifactName);
               plan.includedArtifacts = updated;
               configurationPlanRepository.persist(plan);
               logger.debugf("Removed artifact '%s' from includedArtifacts of plan '%s'", artifactName, plan.id);
            }
         }
      }

      // Delete the artifact itself.
      artifactRepository.delete(artifact);

      // Propagate the change to the gateways serving the service's expositions.
      if (service != null) {
         expositionManagerService.propagateServiceChanges(service);
      }
      return impact;
   }

   /**
    * Computes the impacted plans of a service for the given artifact, without mutating anything.
    * @param artifact The artifact under consideration.
    * @return The deletion impact.
    */
   private ArtifactDeletionImpact computeImpact(Artifact artifact) {
      List<ArtifactDeletionImpact.ImpactedPlan> impactedPlans = new ArrayList<>();
      Service service = artifact.service;
      if (service != null) {
         for (ConfigurationPlan plan : configurationPlanRepository.findByServiceId(service.id)) {
            List<String> included = plan.includedArtifacts;
            if (included != null && included.contains(artifact.name)) {
               boolean fallsBackToAll = included.size() == 1;
               impactedPlans.add(new ArtifactDeletionImpact.ImpactedPlan(plan.id, plan.name, fallsBackToAll));
            }
         }
      }
      return new ArtifactDeletionImpact(artifact.id, artifact.name, artifact.mainArtifact, impactedPlans);
   }
}

