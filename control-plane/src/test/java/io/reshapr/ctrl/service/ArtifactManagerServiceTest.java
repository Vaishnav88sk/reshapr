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
import io.reshapr.ctrl.model.ArtifactType;
import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.ctrl.repository.ConfigurationPlanRepository;
import io.reshapr.ctrl.security.ReshaprTenantContext;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ArtifactManagerService}, focusing on artifact deletion and its cascade on
 * the {@code includedArtifacts} selection of the configuration plans that reference the artifact by name.
 * @author laurent
 */
@QuarkusTest
@ActivateRequestContext
class ArtifactManagerServiceTest {

   private static final String MAIN_ARTIFACT = "openapi.yaml";
   private static final String PROMPTS_ARTIFACT = "prompts.yaml";
   private static final String TOOLS_ARTIFACT = "custom-tools.yaml";

   @Inject
   ArtifactManagerService artifactManagerService;

   @Inject
   ConfigurationPlanManagerService configurationPlanManagerService;

   @Inject
   ArtifactRepository artifactRepository;

   @Inject
   ConfigurationPlanRepository configurationPlanRepository;

   @Test
   void testDeleteUnknownArtifactThrows() {
      ReshaprTenantContext.setCurrentTenant("test-org-" + UUID.randomUUID());
      assertThrows(DependencyNotFoundException.class,
            () -> artifactManagerService.deleteArtifact("does-not-exist"));
   }

   @Test
   void testDeletionImpactPreviewDoesNotMutate() throws Exception {
      String serviceId = seedServiceWithArtifacts();
      createPlan(serviceId, "impact-plan", List.of(PROMPTS_ARTIFACT, TOOLS_ARTIFACT));
      String promptsId = artifactId(serviceId, PROMPTS_ARTIFACT);

      ArtifactDeletionImpact impact = artifactManagerService.getArtifactDeletionImpact(promptsId);

      assertEquals(PROMPTS_ARTIFACT, impact.artifactName());
      assertFalse(impact.mainArtifact());
      assertEquals(1, impact.impactedPlans().size());
      assertFalse(impact.impactedPlans().getFirst().fallsBackToAll());

      // Preview must not have removed the artifact nor mutated the plan selection.
      assertTrue(artifactRepository.findById(promptsId) != null);
   }

   @Test
   void testDeleteCleansUpReferencingPlans() throws Exception {
      String serviceId = seedServiceWithArtifacts();
      ConfigurationPlan plan = createPlan(serviceId, "cleanup-plan", List.of(PROMPTS_ARTIFACT, TOOLS_ARTIFACT));
      String promptsId = artifactId(serviceId, PROMPTS_ARTIFACT);

      ArtifactDeletionImpact impact = artifactManagerService.deleteArtifact(promptsId);

      assertEquals(1, impact.impactedPlans().size());
      assertNull(artifactRepository.findById(promptsId), "artifact should be deleted");
      // The plan selection no longer references the deleted artifact but keeps the remaining one.
      assertEquals(List.of(TOOLS_ARTIFACT), loadPlan(plan.id).includedArtifacts);
   }

   @Test
   void testDeleteMakesPlanFallBackToAll() throws Exception {
      String serviceId = seedServiceWithArtifacts();
      ConfigurationPlan plan = createPlan(serviceId, "fallback-plan", List.of(PROMPTS_ARTIFACT));
      String promptsId = artifactId(serviceId, PROMPTS_ARTIFACT);

      ArtifactDeletionImpact impact = artifactManagerService.deleteArtifact(promptsId);

      assertEquals(1, impact.impactedPlans().size());
      assertTrue(impact.impactedPlans().getFirst().fallsBackToAll());
      // Empty selection means "all attached artifacts apply".
      assertTrue(loadPlan(plan.id).includedArtifacts.isEmpty());
   }

   @Test
   void testDeleteWithNoReferencingPlanLeavesOthersUntouched() throws Exception {
      String serviceId = seedServiceWithArtifacts();
      ConfigurationPlan plan = createPlan(serviceId, "untouched-plan", List.of(TOOLS_ARTIFACT));
      String promptsId = artifactId(serviceId, PROMPTS_ARTIFACT);

      ArtifactDeletionImpact impact = artifactManagerService.deleteArtifact(promptsId);

      assertTrue(impact.impactedPlans().isEmpty());
      assertNull(artifactRepository.findById(promptsId));
      assertEquals(List.of(TOOLS_ARTIFACT), loadPlan(plan.id).includedArtifacts);
   }

   private ConfigurationPlan createPlan(String serviceId, String name, List<String> includedArtifacts) throws Exception {
      ConfigurationPlan plan = new ConfigurationPlan();
      plan.name = name;
      plan.backendEndpoint = "https://backend.example.com";
      plan.includedArtifacts = includedArtifacts;
      return configurationPlanManagerService.createConfigurationPlan(plan, serviceId, null, false);
   }

   private String artifactId(String serviceId, String name) {
      return QuarkusTransaction.requiringNew().call(() ->
            artifactRepository.findByServiceId(serviceId).stream()
                  .filter(artifact -> name.equals(artifact.name))
                  .findFirst()
                  .map(artifact -> artifact.id)
                  .orElseThrow());
   }

   private String seedServiceWithArtifacts() {
      ReshaprTenantContext.setCurrentTenant("test-org-" + UUID.randomUUID());
      return QuarkusTransaction.requiringNew().call(() -> {
         Service service = new Service();
         service.name = "Test API";
         service.version = "1.0.0";
         service.type = ServiceType.REST;
         service.createdOn = OffsetDateTime.now();
         service.persist();

         persistArtifact(service, MAIN_ARTIFACT, ArtifactType.OPEN_API_SPEC, true);
         persistArtifact(service, PROMPTS_ARTIFACT, ArtifactType.RESHAPR_PROMPTS, false);
         persistArtifact(service, TOOLS_ARTIFACT, ArtifactType.RESHAPR_CUSTOM_TOOLS, false);
         return service.id;
      });
   }

   private void persistArtifact(Service service, String name, ArtifactType type, boolean mainArtifact) {
      Artifact artifact = new Artifact();
      artifact.name = name;
      artifact.type = type;
      artifact.mainArtifact = mainArtifact;
      artifact.content = "# content of " + name;
      artifact.sourceArtifact = name;
      artifact.service = service;
      artifact.persist();
   }

   private ConfigurationPlan loadPlan(String id) {
      return QuarkusTransaction.requiringNew().call(() -> configurationPlanRepository.findById(id));
   }
}

