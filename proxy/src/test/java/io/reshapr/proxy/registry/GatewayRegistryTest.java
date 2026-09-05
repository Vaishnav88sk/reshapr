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
package io.reshapr.proxy.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GatewayRegistry}, focusing on the exposition-keyed indexing: multiple expositions
 * of the same service coexist (no overwrite), the service-keyed reads resolve to the elected (most recent)
 * exposition, name-based resolution works, and removal re-elects deterministically.
 * @author laurent
 */
class GatewayRegistryTest {

   private static ServiceEntry service() {
      return new ServiceEntry("svc-1", "acme", "Orders API", "1.0.0", "REST", List.of());
   }

   private static ConfigurationEntry config(String id) {
      return new ConfigurationEntry(id, "cfg-" + id, "http://backend/" + id, null,
            List.of(), List.of(), null, null, null);
   }

   private static ExpositionEntry exposition(String id, String name, ServiceEntry service, ConfigurationEntry config,
                                             List<ArtifactEntry> attached) {
      return new ExpositionEntry(id, name, service, config, null, attached);
   }

   @Test
   void twoExpositionsOfSameServiceCoexist() {
      GatewayRegistry registry = new GatewayRegistry();
      ServiceEntry service = service();

      registry.addExposition(exposition("e1", "prod", service, config("c1"), List.of()));
      registry.addExposition(exposition("e2", "staging", service, config("c2"), List.of()));

      // Both expositions are retained (the old service-keyed registry used to overwrite).
      assertEquals(2, registry.getAllExpositions().size());
      assertNotNull(registry.getExpositionById("e1"));
      assertNotNull(registry.getExpositionById("e2"));
      // A single service is elected.
      assertEquals(1, registry.getAllServices().size());
   }

   @Test
   void serviceKeyedReadsResolveToElectedExposition() {
      GatewayRegistry registry = new GatewayRegistry();
      ServiceEntry service = service();

      registry.addExposition(exposition("e1", null, service, config("c1"), List.of()));
      registry.addExposition(exposition("e2", null, service, config("c2"), List.of()));

      // Elected = highest TSID (here "e2"); service-keyed reads must return its configuration.
      assertEquals("c2", registry.getConfiguration(service).id());
      assertEquals("c2", registry.getElectedExpositionByServiceId("svc-1").configuration().id());
      assertEquals("c2", registry.getElectedExpositionByServiceCoordinates("acme", "Orders API", "1.0.0")
            .configuration().id());
      // The same service entry is shared by both indexes.
      assertSame(registry.getService("svc-1"), registry.getService("acme", "Orders API", "1.0.0"));
   }

   @Test
   void nameIndexResolvesExposition() {
      GatewayRegistry registry = new GatewayRegistry();
      ServiceEntry service = service();
      registry.addExposition(exposition("e1", "prod", service, config("c1"), List.of()));

      assertEquals("e1", registry.getExpositionByName("acme", "prod").id());
      assertNull(registry.getExpositionByName("acme", "does-not-exist"));
   }

   @Test
   void removingElectedReElectsToRemaining() {
      GatewayRegistry registry = new GatewayRegistry();
      ServiceEntry service = service();
      registry.addExposition(exposition("e1", null, service, config("c1"), List.of()));
      registry.addExposition(exposition("e2", null, service, config("c2"), List.of()));

      // Remove the elected one (e2) -> the service-keyed reads must fall back to e1.
      registry.removeExposition("e2");
      assertNull(registry.getExpositionById("e2"));
      assertEquals("c1", registry.getConfiguration(service).id());

      // Remove the last one -> the service disappears from the service-keyed indexes.
      registry.removeExposition("e1");
      assertNull(registry.getService("svc-1"));
      assertNull(registry.getConfiguration(service));
      assertTrue(registry.getAllServices().isEmpty());
      assertTrue(registry.getAllExpositions().isEmpty());
   }

   @Test
   void getAttachedArtifactsReturnsNullWhenEmpty() {
      GatewayRegistry registry = new GatewayRegistry();
      ServiceEntry service = service();
      registry.addExposition(exposition("e1", null, service, config("c1"), List.of()));

      // Historical contract: null (not an empty list) when there is no attached artifact.
      assertNull(registry.getAttachedArtifacts(service));

      ArtifactEntry attached = new ArtifactEntry("a1", "prompts.yaml", null,
            ArtifactEntryType.RESHAPR_PROMPTS, false, "content");
      registry.addExposition(exposition("e2", null, service, config("c2"), List.of(attached)));
      // Elected is now e2 which has one attached artifact.
      assertEquals(1, registry.getAttachedArtifacts(service).size());
      assertTrue(registry.hasAttachedArtifacts(service));
   }

   @Test
   void resourceForTool() {
      GatewayRegistry registry = new GatewayRegistry();
      ToolEntry tool = new ToolEntry("svc1", "org1", "tool1");
      ResourceEntry resource = new ResourceEntry("ui", "ui://test", new String[]{"app"});

      assertFalse(registry.hasResourceForTool(tool));
      registry.addResourceForTool(tool, resource);
      assertTrue(registry.hasResourceForTool(tool));
      assertEquals(resource, registry.getResourceForTool(tool));
   }
}

