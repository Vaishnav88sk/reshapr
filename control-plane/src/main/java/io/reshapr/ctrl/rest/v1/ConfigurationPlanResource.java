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

import io.quarkus.security.Authenticated;
import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.repository.ConfigurationPlanRepository;
import io.reshapr.ctrl.service.ConfigurationPlanManagerService;
import io.reshapr.ctrl.service.DependencyNotFoundException;
import io.reshapr.ctrl.service.InvalidConfigurationException;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * ConfigurationPlanResource provides REST endpoints for managing configuration plans.
 * @author laurent
 */
@RunOnVirtualThread
@Path("/api/v1/configurationPlans")
public class ConfigurationPlanResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final ConfigurationPlanManagerService managerService;
   private final ConfigurationPlanRepository configurationPlanRepository;
   private final Mappers v1Mappers;

   public ConfigurationPlanResource(ConfigurationPlanManagerService managerService, ConfigurationPlanRepository configurationPlanRepository, Mappers v1Mappers) {
      this.managerService = managerService;
      this.configurationPlanRepository = configurationPlanRepository;
      this.v1Mappers = v1Mappers;
   }

   @GET
   @Authenticated
   public List<ConfigurationPlanDTO> getConfigurationPlans(@QueryParam("serviceId") String serviceId) {
      logger.debug("Retrieving all configuration plans");
      return v1Mappers.toCPResources(managerService.getConfigurationPlans(serviceId));
   }

   @POST
   @Authenticated
   public Response createConfigurationPlan(@Valid ConfigurationPlanDTO configurationPlanDTO) {
      logger.infof("Creating a new configuration plan named '%s' for service with id %s", configurationPlanDTO.name, configurationPlanDTO.serviceId);

      ConfigurationPlan configurationPlan = v1Mappers.fromResource(configurationPlanDTO);
      try {
         configurationPlan = managerService.createConfigurationPlan(configurationPlan, configurationPlanDTO.serviceId,
               configurationPlanDTO.backendSecretId, configurationPlanDTO.apiKey != null);
      } catch (DependencyNotFoundException e) {
         logger.errorf("Failed to create configuration plan: %s", e.getMessage());
         return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
      } catch (InvalidConfigurationException e) {
         logger.errorf("Rejected configuration plan: %s", e.getMessage());
         return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
      }

      // On creation, be sure to show the API key if it was requested. It will not be shown again after this.
      ConfigurationPlanDTO result = v1Mappers.toResource(configurationPlan);
      if (configurationPlan.apiKey != null) {
         result.apiKey = configurationPlan.apiKey;
      }
      if (configurationPlan.initialAccessToken != null) {
         result.initialAccessToken = configurationPlan.initialAccessToken;
      }
      return Response.status(Response.Status.CREATED).entity(result).build();
   }

   @POST
   @Authenticated
   @Path("/{id}/duplicate")
   @Produces(MediaType.APPLICATION_JSON)
   public Response duplicateConfigurationPlan(@PathParam("id") String id, @QueryParam("name") String newName) {
      logger.infof("Duplicating configuration plan with id '%s' to new name '%s'", id, newName);

      if (newName == null || newName.trim().isEmpty()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("New name must be provided").build();
      }

      ConfigurationPlan newPlan;
      try {
         newPlan = managerService.duplicateConfigurationPlan(id, newName);
      } catch (IllegalArgumentException e) {
         logger.errorf("Failed to duplicate configuration plan: %s", e.getMessage());
         return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
      }

      // On creation, be sure to show the API key if it was requested. It will not be shown again after this.
      ConfigurationPlanDTO result = v1Mappers.toResource(newPlan);
      if (newPlan.apiKey != null) {
         result.apiKey = newPlan.apiKey;
      }
      if (newPlan.initialAccessToken != null) {
         result.initialAccessToken = newPlan.initialAccessToken;
      }
      return Response.status(Response.Status.CREATED).entity(result).build();
   }

   @GET
   @Authenticated
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getConfigurationPlan(@PathParam("id") String id) {
      logger.debugf("Retrieving configuration plan with id %s", id);
      ConfigurationPlan configurationPlan = configurationPlanRepository.findById(id);
      if (configurationPlan == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      return Response.ok(v1Mappers.toResource(configurationPlan)).build();
   }

   @PUT
   @Authenticated
   @Path("/{id}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response updateConfigurationPlan(@PathParam("id") String id, @Valid ConfigurationPlanDTO configurationPlanDTO) {
      logger.debugf("Updating configuration plan with id %s", id);

      ConfigurationPlan configurationPlan = configurationPlanRepository.findById(id);
      if (configurationPlan == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      configurationPlan = v1Mappers.fromResource(configurationPlanDTO);
      try {
         configurationPlan = managerService.updateConfigurationPlan(configurationPlan, configurationPlanDTO.backendSecretId);
      } catch (DependencyNotFoundException e) {
         logger.errorf("Failed to update configuration plan: %s", e.getMessage());
         return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
      } catch (InvalidConfigurationException e) {
         logger.errorf("Rejected configuration plan update: %s", e.getMessage());
         return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
      }
      return Response.ok(v1Mappers.toResource(configurationPlan)).build();
   }

   @PUT
   @Authenticated
   @Path("/{id}/renewApiKey")
   @Produces(MediaType.APPLICATION_JSON)
   public Response updateConfigurationPlanApiKey(@PathParam("id") String id) {
      logger.debugf("Renewing Api Key for configuration plan with id %s", id);
      ConfigurationPlan configurationPlan = configurationPlanRepository.findById(id);
      if (configurationPlan == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }

      configurationPlan = managerService.renewApiKey(configurationPlan);
      // On renewal, be sure to show the API key. It will not be shown again after this.
      ConfigurationPlanDTO result = v1Mappers.toResource(configurationPlan);
      result.apiKey = configurationPlan.apiKey;
      return Response.ok().entity(result).build();
   }

   @DELETE
   @Authenticated
   @Path("/{id}")
   public Response deleteConfigurationPlan(@PathParam("id") String id) {
      logger.debugf("Deleting configuration plan with id %s", id);
      ConfigurationPlan configurationPlan = configurationPlanRepository.findById(id);
      if (configurationPlan == null) {
         return Response.status(Response.Status.NOT_FOUND).build();
      }
      managerService.deleteConfigurationPlan(configurationPlan);
      return Response.noContent().build();
   }
}
