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
package io.reshapr.proxy.rest.admin;

import io.reshapr.proxy.registry.ConfigurationEntry;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.ServiceEntry;
import io.reshapr.proxy.security.GatewayAdminAuthenticated;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static java.util.stream.Collectors.groupingBy;

@RunOnVirtualThread
@Path("/api/admin/registry")
@GatewayAdminAuthenticated
public class RegistryResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final GatewayRegistry gatewayRegistry;
   private final Mappers adminMappers;

   /**
    * Build a RegistryResource with required dependencies.
    * @param gatewayRegistry The GatewayRegistry service
    * @param adminMappers The admin mappers
    */
   public RegistryResource(GatewayRegistry gatewayRegistry, Mappers adminMappers) {
      this.gatewayRegistry = gatewayRegistry;
      this.adminMappers = adminMappers;
   }

   @GET
   public Response getContentSummary() {
      logger.info("Getting registry content summary");
      List<ServiceEntry> services = gatewayRegistry.getAllServices();
      long lastUpdateTimestamp = gatewayRegistry.getLastUpdateTimestamp();
      String[] organizationIds = services.stream().collect(groupingBy(ServiceEntry::organizationId))
            .keySet()
            .toArray(String[]::new);
      return Response.ok(new RegistryContentSummary(new Date(lastUpdateTimestamp), services.size(), organizationIds)).build();
   }
   @GET
   @Path("/services")
   public Response getServices() {
      logger.info("Getting full list of registry services");
      List<ServiceEntryDTO> services = gatewayRegistry.getAllServices()
            .stream()
            .map(this::toServiceEntryDTO)
            .toList();
      return Response.ok(services).build();
   }

   @GET
   @Path("/services/{organizationId}")
   public Response getServicesForOrganization(@PathParam("organizationId") String organizationId) {
      logger.infof("Getting registry services for organization: %s", organizationId);
      List<ServiceEntryDTO> services = gatewayRegistry.getAllServices()
            .stream()
            .filter(service -> service.organizationId().equals(organizationId))
            .map(this::toServiceEntryDTO)
            .toList();
      return Response.ok(services).build();
   }

   private record RegistryContentSummary(Date lastUpdate, int totalServices, String[] organizationIds) {

      @Override
      public boolean equals(Object o) {
         if (this == o) return true;
         if (!(o instanceof RegistryContentSummary other)) return false;
         return totalServices == other.totalServices
               && Objects.equals(lastUpdate, other.lastUpdate)
               && Arrays.equals(organizationIds, other.organizationIds);
      }

      @Override
      public int hashCode() {
         int result = Objects.hash(lastUpdate, totalServices);
         result = 31 * result + Arrays.hashCode(organizationIds);
         return result;
      }

      @Override
      public String toString() {
         return "RegistryContentSummary[lastUpdate=" + lastUpdate
               + ", totalServices=" + totalServices
               + ", organizationIds=" + Arrays.toString(organizationIds) + "]";
      }
   }

   private ServiceEntryDTO toServiceEntryDTO(ServiceEntry serviceEntry) {
      ConfigurationEntry configurationEntry = gatewayRegistry.getConfiguration(serviceEntry);
      ConfigurationEntryDTO configurationEntryDTO = adminMappers.toConfigurationEntryDTO(configurationEntry);
      ServiceEntryDTO serviceEntryDTO = adminMappers.toServiceEntryDTO(serviceEntry);
      serviceEntryDTO.setConfiguration(configurationEntryDTO);
      return serviceEntryDTO;
   }
}
