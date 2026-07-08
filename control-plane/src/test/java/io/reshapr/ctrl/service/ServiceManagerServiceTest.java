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
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.model.ServiceType;
import io.reshapr.ctrl.repository.ArtifactRepository;
import io.reshapr.ctrl.security.ReshaprTenantContext;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link ServiceManagerService} artifact attachment, focusing on capability
 * extraction and the upsert behaviour on re-attachment (which must not violate the unique
 * {@code (service_id, name)} constraint).
 * @author laurent
 */
@QuarkusTest
@ActivateRequestContext
class ServiceManagerServiceTest {

   private static final String ATTACHED_NAME = "prompts.yaml";

   @Inject
   ServiceManagerService serviceManagerService;

   @Inject
   ArtifactRepository artifactRepository;

   @Test
   void testAttachPersistsCapabilities() throws Exception {
      seedPastryService();

      Artifact attached = serviceManagerService.attachArtifactFile(
            new AttachmentArtifactInfo(ATTACHED_NAME, fixture("prompts-valid.yaml")));

      assertEquals(ArtifactType.RESHAPR_PROMPTS, attached.type);
      assertEquals(List.of("list_pastries", "get_pastry"), attached.capabilities);

      // Reloaded from the store, the capabilities are persisted.
      Artifact reloaded = loadById(attached.id);
      assertNotNull(reloaded);
      assertEquals(List.of("list_pastries", "get_pastry"), reloaded.capabilities);
   }

   @Test
   void testReattachUpdatesInPlaceAndRecomputesCapabilities() throws Exception {
      String serviceId = seedPastryService();

      Artifact first = serviceManagerService.attachArtifactFile(
            new AttachmentArtifactInfo(ATTACHED_NAME, fixture("prompts-valid.yaml")));
      String firstId = first.id;
      assertEquals(List.of("list_pastries", "get_pastry"), first.capabilities);

      // Re-attaching the same artifact name for the same service must update in place (no duplicate row,
      // no unique constraint violation) and recompute the capabilities from the new content.
      Artifact second = serviceManagerService.attachArtifactFile(
            new AttachmentArtifactInfo(ATTACHED_NAME, fixture("prompts-valid-v2.yaml")));

      assertEquals(firstId, second.id, "re-attachment must reuse the existing artifact");
      assertEquals(List.of("search_pastries"), second.capabilities);

      // Only one artifact with that name remains for the service.
      long count = QuarkusTransaction.requiringNew().call(() ->
            artifactRepository.findByServiceId(serviceId).stream()
                  .filter(a -> ATTACHED_NAME.equals(a.name))
                  .count());
      assertEquals(1L, count);
   }

   private String seedPastryService() {
      ReshaprTenantContext.setCurrentTenant("test-org-" + UUID.randomUUID());
      return QuarkusTransaction.requiringNew().call(() -> {
         Service service = new Service();
         service.name = "Pastry API";
         service.version = "2.0.0";
         service.type = ServiceType.REST;
         service.createdOn = OffsetDateTime.now();
         service.persist();
         return service.id;
      });
   }

   private Artifact loadById(String id) {
      return QuarkusTransaction.requiringNew().call(() -> artifactRepository.findById(id));
   }

   private File fixture(String name) {
      return new File(getClass().getResource("/io/reshapr/ctrl/artifacts/" + name).getFile());
   }
}

