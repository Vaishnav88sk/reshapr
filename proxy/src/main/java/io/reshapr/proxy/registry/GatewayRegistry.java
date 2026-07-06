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

import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for model (services, artifacts and configuration plans) entries in the Reshapr Gateway.
 *
 * <p>The registry is keyed by <b>exposition</b>: an {@link ExpositionEntry} is the ready-to-serve,
 * pre-computed aggregate of a service, its configuration and its (already filtered) artifacts. This lifts
 * the previous "one entry per service" limitation, which used to make two configuration plans of the same
 * service overwrite each other. Several indexes point to the very same {@code ExpositionEntry} instance so
 * every hot-path lookup is O(1) and requires a single dereference — no per-request scan/filter/join.</p>
 *
 * <p><b>Concurrency:</b> all indexes are {@link ConcurrentHashMap} holding immutable values; readers never
 * synchronize. Writes (add/remove) recompute the small per-service election set — an O(k) operation where
 * k is the number of expositions of a single service — which is acceptable off the request hot path.</p>
 *
 * <p><b>Legacy service-keyed reads:</b> until MCP request routing becomes exposition-aware (a later step),
 * the service-keyed read methods ({@link #getService}, {@link #getConfiguration}, {@link #getMainArtifact},
 * {@link #getAttachedArtifacts}) resolve to the <i>elected</i> exposition of the service, i.e. the most
 * recent one (highest TSID). This preserves the current behavior (serve the last configuration plan) while
 * the write side is now correctly de-duplicated per exposition.</p>
 *
 * @author laurent
 */
@ApplicationScoped
public class GatewayRegistry {

   private long lastUpdateTimestamp = System.currentTimeMillis();

   /** Source of truth: every exposition indexed by its id. O(1) hot path (endpoint by exposition id). */
   private final ConcurrentHashMap<String, ExpositionEntry> expositionsById = new ConcurrentHashMap<>();

   /** Named expositions indexed by (organizationId, name). O(1) hot path (endpoint by exposition name). */
   private final ConcurrentHashMap<OrgName, ExpositionEntry> expositionsByName = new ConcurrentHashMap<>();

   /** Elected exposition per service coordinates. O(1) hot path (legacy 3-segment endpoint). */
   private final ConcurrentHashMap<ServiceCoordinates, ExpositionEntry> electedByServiceCoordinates = new ConcurrentHashMap<>();

   /** Elected exposition per serviceId. O(1) hot path (legacy 1-segment endpoint + service-keyed reads). */
   private final ConcurrentHashMap<String, ExpositionEntry> electedByServiceId = new ConcurrentHashMap<>();

   /** serviceId -> set of its exposition ids. Used for election/removal only, never on the read hot path. */
   private final ConcurrentHashMap<String, Set<String>> expositionIdsByServiceId = new ConcurrentHashMap<>();

   private final ConcurrentHashMap<ToolEntry, ResourceEntry> resourcesByTool = new ConcurrentHashMap<>();

   /** Coordinates for a service entry, used for efficient retrieval. */
   private record ServiceCoordinates(String organizationId, String serviceName, String version) {}

   /** Organization-scoped exposition name key. */
   private record OrgName(String organizationId, String name) {}

   /**
    * Retrieves the timestamp of the last update to the registry.
    * @return The last update timestamp in milliseconds.
    */
   public long getLastUpdateTimestamp() {
      return lastUpdateTimestamp;
   }

   // ----------------------------------------------------------------------------------------------------
   // Exposition-keyed write API (source of truth).
   // ----------------------------------------------------------------------------------------------------

   /**
    * Adds (or replaces) an exposition and atomically refreshes all indexes pointing to it. The per-service
    * election (used by the legacy service-keyed reads and the 3-segment endpoint) is recomputed.
    * @param entry The ready-to-serve exposition entry to register.
    */
   public void addExposition(ExpositionEntry entry) {
      ServiceEntry service = entry.service();

      // If an entry with the same id already existed, drop its stale name index first.
      ExpositionEntry previous = expositionsById.put(entry.id(), entry);
      if (previous != null && hasName(previous) && !sameName(previous, entry)) {
         expositionsByName.remove(new OrgName(previous.service().organizationId(), previous.name()), previous);
      }

      if (hasName(entry)) {
         expositionsByName.put(new OrgName(service.organizationId(), entry.name()), entry);
      }

      expositionIdsByServiceId.compute(service.id(), (serviceId, ids) -> {
         Set<String> set = (ids == null) ? ConcurrentHashMap.newKeySet() : ids;
         set.add(entry.id());
         return set;
      });

      reelectService(service);
      lastUpdateTimestamp = System.currentTimeMillis();
   }

   /**
    * Removes an exposition by its id and re-elects the service coordinates (O(1) legacy resolution) from the
    * remaining expositions of the service. Other expositions of the same service are left untouched.
    * @param expositionId The id of the exposition to remove.
    */
   public void removeExposition(String expositionId) {
      ExpositionEntry entry = expositionsById.remove(expositionId);
      if (entry == null) {
         return;
      }
      ServiceEntry service = entry.service();

      if (hasName(entry)) {
         // Only drop the name index if it still points to this exact instance.
         expositionsByName.remove(new OrgName(service.organizationId(), entry.name()), entry);
      }

      Set<String> ids = expositionIdsByServiceId.get(service.id());
      if (ids != null) {
         ids.remove(expositionId);
         if (ids.isEmpty()) {
            expositionIdsByServiceId.remove(service.id());
            electedByServiceId.remove(service.id());
            electedByServiceCoordinates.remove(coordinatesOf(service));
         } else {
            reelectService(service);
         }
      }
      lastUpdateTimestamp = System.currentTimeMillis();
   }

   /**
    * Recomputes the elected exposition for a service (the one with the highest TSID, i.e. the most recent /
    * last configuration plan) and refreshes the service-keyed indexes. Called on every add/remove; O(k) in
    * the number of expositions of the service, off the read hot path.
    * @param service The service to re-elect for.
    */
   private void reelectService(ServiceEntry service) {
      Set<String> ids = expositionIdsByServiceId.get(service.id());
      if (ids == null || ids.isEmpty()) {
         return;
      }
      ExpositionEntry elected = null;
      for (String id : ids) {
         ExpositionEntry candidate = expositionsById.get(id);
         if (candidate != null && (elected == null || candidate.id().compareTo(elected.id()) > 0)) {
            elected = candidate;
         }
      }
      if (elected != null) {
         electedByServiceId.put(service.id(), elected);
         electedByServiceCoordinates.put(coordinatesOf(service), elected);
      }
   }

   // ----------------------------------------------------------------------------------------------------
   // Exposition-keyed read API (O(1) hot path) — used by the exposition-aware routing.
   // ----------------------------------------------------------------------------------------------------

   /**
    * Retrieves an exposition by its id. O(1) hot path.
    * @param expositionId The exposition id.
    * @return The exposition entry, or null if not found.
    */
   public ExpositionEntry getExpositionById(String expositionId) {
      return expositionsById.get(expositionId);
   }

   /**
    * Retrieves a named exposition by organization id and name. O(1) hot path.
    * @param organizationId The organization id.
    * @param name The exposition name.
    * @return The exposition entry, or null if not found.
    */
   public ExpositionEntry getExpositionByName(String organizationId, String name) {
      return expositionsByName.get(new OrgName(organizationId, name));
   }

   /**
    * Retrieves the elected exposition (last configuration plan) for the given service coordinates. O(1).
    * @param organizationId The organization id.
    * @param serviceName The service name.
    * @param version The service version.
    * @return The elected exposition entry, or null if not found.
    */
   public ExpositionEntry getElectedExpositionByServiceCoordinates(String organizationId, String serviceName, String version) {
      return electedByServiceCoordinates.get(new ServiceCoordinates(organizationId, serviceName, version));
   }

   /**
    * Retrieves the elected exposition (last configuration plan) for the given service id. O(1).
    * @param serviceId The service id.
    * @return The elected exposition entry, or null if not found.
    */
   public ExpositionEntry getElectedExpositionByServiceId(String serviceId) {
      return electedByServiceId.get(serviceId);
   }

   /**
    * Retrieves all registered expositions.
    * @return An immutable snapshot list of all exposition entries.
    */
   public List<ExpositionEntry> getAllExpositions() {
      return List.copyOf(expositionsById.values());
   }

   // ----------------------------------------------------------------------------------------------------
   // Legacy service-keyed read API (resolves to the elected exposition). Kept O(1) and backward compatible.
   // ----------------------------------------------------------------------------------------------------

   /**
    * Retrieves all elected service entries (one per service).
    * @return A list of all elected service entries.
    */
   public List<ServiceEntry> getAllServices() {
      return electedByServiceId.values().stream().map(ExpositionEntry::service).toList();
   }

   /**
    * Retrieves the elected service entry by its ID.
    * @param serviceId The ID of the service to retrieve.
    * @return The service entry, or null if not found.
    */
   public ServiceEntry getService(String serviceId) {
      ExpositionEntry entry = electedByServiceId.get(serviceId);
      return (entry == null) ? null : entry.service();
   }

   /**
    * Retrieves the elected service entry by its organization ID, service name, and version.
    * @param organizationId The ID of the organization.
    * @param serviceName The name of the service.
    * @param version The version of the service.
    * @return The service entry, or null if not found.
    */
   public ServiceEntry getService(String organizationId, String serviceName, String version) {
      ExpositionEntry entry = electedByServiceCoordinates.get(new ServiceCoordinates(organizationId, serviceName, version));
      return (entry == null) ? null : entry.service();
   }

   /**
    * Retrieves the configuration entry of the elected exposition for a given service.
    * @param service The service entry to retrieve the configuration for.
    * @return The configuration entry, or null if not found.
    */
   public ConfigurationEntry getConfiguration(ServiceEntry service) {
      ExpositionEntry entry = electedByServiceId.get(service.id());
      return (entry == null) ? null : entry.configuration();
   }

   /**
    * Retrieves the main artifact of the elected exposition for a given service.
    * @param service The service entry to retrieve the main artifact for.
    * @return The main artifact entry, or null if not found.
    */
   public ArtifactEntry getMainArtifact(ServiceEntry service) {
      ExpositionEntry entry = electedByServiceId.get(service.id());
      return (entry == null) ? null : entry.mainArtifact();
   }

   /**
    * Checks if the elected exposition for a given service has attached artifacts.
    * @param service The service entry to check for attached artifacts.
    * @return True if there are attached artifacts, false otherwise.
    */
   public boolean hasAttachedArtifacts(ServiceEntry service) {
      ExpositionEntry entry = electedByServiceId.get(service.id());
      return entry != null && !entry.attachedArtifacts().isEmpty();
   }

   /**
    * Retrieves the attached artifacts of the elected exposition for a given service. Returns {@code null}
    * (not an empty list) when there is no elected exposition or it has no attached artifact, preserving the
    * historical contract expected by the callers.
    * @param service The service entry to retrieve the attached artifacts for.
    * @return The list of attached artifact entries, or null if none.
    */
   @Nullable
   public List<ArtifactEntry> getAttachedArtifacts(ServiceEntry service) {
      ExpositionEntry entry = electedByServiceId.get(service.id());
      if (entry == null || entry.attachedArtifacts().isEmpty()) {
         return null;
      }
      return entry.attachedArtifacts();
   }

   // ----------------------------------------------------------------------------------------------------
   // Tool -> Resource association (unchanged).
   // ----------------------------------------------------------------------------------------------------

   /**
    * Adds a resource entry for a given tool.
    * @param tool The tool entry this resource is associated with.
    * @param resource The resource entry to add.
    */
   public void addResourceForTool(ToolEntry tool, ResourceEntry resource) {
      resourcesByTool.put(tool, resource);
   }

   /**
    * Checks if there is a resource entry for a given tool.
    * @param tool The tool entry to check for a resource.
    * @return True if there is a resource entry, false otherwise.
    */
   public boolean hasResourceForTool(ToolEntry tool) {
      return resourcesByTool.containsKey(tool);
   }

   /**
    * Retrieves the resource entry for a given tool.
    * @param tool The tool entry to retrieve the resource for.
    * @return The resource entry, or null if not found.
    */
   public ResourceEntry getResourceForTool(ToolEntry tool) {
      return resourcesByTool.get(tool);
   }

   // ----------------------------------------------------------------------------------------------------
   // Helpers.
   // ----------------------------------------------------------------------------------------------------

   private static ServiceCoordinates coordinatesOf(ServiceEntry service) {
      return new ServiceCoordinates(service.organizationId(), service.name(), service.version());
   }

   private static boolean hasName(ExpositionEntry entry) {
      return entry.name() != null && !entry.name().isBlank();
   }

   private static boolean sameName(ExpositionEntry a, ExpositionEntry b) {
      return hasName(a) && hasName(b) && a.name().equals(b.name());
   }
}
