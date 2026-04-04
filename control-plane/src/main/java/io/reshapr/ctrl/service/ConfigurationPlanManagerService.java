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

import io.reshapr.ctrl.model.ConfigurationPlan;
import io.reshapr.ctrl.model.Exposition;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.Service;
import io.reshapr.ctrl.repository.ConfigurationPlanRepository;
import io.reshapr.ctrl.repository.ExpositionRepository;
import io.reshapr.ctrl.repository.GatewayGroupRepository;
import io.reshapr.ctrl.repository.SecretRepository;
import io.reshapr.ctrl.repository.ServiceRepository;

import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ConfigurationPlanManagerService {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final ConfigurationPlanRepository configurationPlanRepository;
   private final ServiceRepository serviceRepository;
   private final SecretRepository secretRepository;
   private final ExpositionManagerService expositionManagerService;
   private final GatewayManagerService gatewayManagerService;
   private final GatewayGroupRepository gatewayGroupRepository;
   private final ExpositionRepository expositionRepository;
   private final TokenManagerService tokenManagerService;


   /**
    * Build a new ConfigurationPlanManagerService with the required repositories.
    * @param configurationPlanRepository The repository for managing configuration plans.
    * @param serviceRepository The repository for managing services.
    * @param secretRepository The repository for managing secrets.
    * @param expositionManagerService The service for managing expositions.
    * @param gatewayManagerService The service for managing gateways.
    * @param gatewayGroupRepository The repository for managing gateway groups.
    * @param tokenManagerService The service for managing tokens.
    *
    */
   public ConfigurationPlanManagerService(ConfigurationPlanRepository configurationPlanRepository, ServiceRepository serviceRepository,
                                          SecretRepository secretRepository, ExpositionManagerService expositionManagerService, GatewayManagerService gatewayManagerService,
                                          GatewayGroupRepository gatewayGroupRepository, ExpositionRepository expositionRepository,
                                          TokenManagerService tokenManagerService) {
      this.configurationPlanRepository = configurationPlanRepository;
      this.serviceRepository = serviceRepository;
      this.secretRepository = secretRepository;
      this.expositionManagerService = expositionManagerService;
      this.gatewayManagerService = gatewayManagerService;
      this.gatewayGroupRepository = gatewayGroupRepository;
      this.expositionRepository = expositionRepository;
      this.tokenManagerService = tokenManagerService;
   }

   /**
    * Retrieves all configuration plans or those associated with a specific service ID.
    * @param serviceId The ID of the service for which to retrieve configuration plans, or null to retrieve all.
    * @return A list of configuration plans.
    */
   public List<ConfigurationPlan> getConfigurationPlans(@Nullable String serviceId) {
      if (serviceId == null) {
         logger.debug("Retrieving all configuration plans");
         return configurationPlanRepository.findAll().list();
      }
      logger.debugf("Retrieving configuration plans for service with id %s", serviceId);
      return configurationPlanRepository.findByServiceId(serviceId);
   }

   /**
    * Creates a new configuration plan.
    * @param configurationPlan the configuration plan to create
    * @param serviceId the ID of the service to associate with the configuration plan
    * @param backendSecretId the ID of the backend secret to associate with the configuration plan, can be null
    * @return the created configuration plan
    * @throws DependencyNotFoundException if the service or backend secret is not found
    */
   @Transactional
   public ConfigurationPlan createConfigurationPlan(ConfigurationPlan configurationPlan, String serviceId,
                                                    @Nullable String backendSecretId, boolean useApiKey)
         throws DependencyNotFoundException {
      logger.debugf("Creating configuration plan with name %s", configurationPlan.name);

      Service service = serviceRepository.findById(serviceId);
      if (service == null) {
         logger.errorf("Service with id %s not found", serviceId);
         throw new DependencyNotFoundException("Service with id " + serviceId + " not found");
      }
      configurationPlan.service = service;

      if (backendSecretId != null) {
         logger.debugf("Setting backend secret with id %s for configuration plan %s", backendSecretId, configurationPlan.name);
         configurationPlan.backendSecret = secretRepository.findById(backendSecretId);
         if (configurationPlan.backendSecret == null) {
            logger.errorf("Backend secret with id %s not found", backendSecretId);
            throw new DependencyNotFoundException("Backend secret with id " + backendSecretId + " not found");
         }
      }
      if (useApiKey) {
         logger.debugf("Generating API token for configuration plan %s", configurationPlan.name);
         configurationPlan.apiKey = UUID.randomUUID().toString();
      } else {
         configurationPlan.apiKey = null;
      }

      configurationPlanRepository.persistAndFlush(configurationPlan);
      return configurationPlan;
   }

   /**
    * Updates an existing configuration plan.
    * @param configurationPlan the configuration plan to update with fresh values
    * @param backendSecretId the ID of the backend secret to associate with the configuration plan, can be null
    * @return the updated configuration plan
    * @throws DependencyNotFoundException if the backend secret is not found
    */
   @Transactional
   public ConfigurationPlan updateConfigurationPlan(ConfigurationPlan configurationPlan, @Nullable String backendSecretId) throws DependencyNotFoundException {
      logger.debugf("Updating configuration plan with id %s", configurationPlan.id);
      ConfigurationPlan existingPlan = configurationPlanRepository.findById(configurationPlan.id);
      if (existingPlan == null) {
         logger.errorf("Configuration plan with id %s not found", configurationPlan.id);
         throw new IllegalArgumentException("Configuration plan with id " + configurationPlan.id + " not found");
      }
      existingPlan.name = configurationPlan.name;
      existingPlan.description = configurationPlan.description;
      existingPlan.backendEndpoint = configurationPlan.backendEndpoint;
      existingPlan.backendEndpointTimeout = configurationPlan.backendEndpointTimeout;
      existingPlan.includedOperations = configurationPlan.includedOperations;
      existingPlan.excludedOperations = configurationPlan.excludedOperations;
      if (backendSecretId != null) {
         logger.debugf("Setting backend secret with id %s for configuration plan %s", backendSecretId, existingPlan.name);
         existingPlan.backendSecret = secretRepository.findById(backendSecretId);
         if (existingPlan.backendSecret == null) {
            logger.errorf("Backend secret with id %s not found", backendSecretId);
            throw new DependencyNotFoundException("Backend secret with id " + backendSecretId + " not found");
         }
      } else {
         existingPlan.backendSecret = null;
      }
      existingPlan.oauth2Configuration = configurationPlan.oauth2Configuration;
      configurationPlanRepository.persistAndFlush(existingPlan);
      expositionManagerService.propagateConfigurationPlanChanges(existingPlan);
      return existingPlan;
   }

   /**
    * Renews the API key for a given configuration plan.
    * @param configurationPlan the configuration plan for which to renew the API key
    * @return the updated configuration plan with a new API key
    */
   @Transactional
   public ConfigurationPlan renewApiKey(ConfigurationPlan configurationPlan) {
      logger.debugf("Renewing API token for configuration plan with id %s", configurationPlan.id);
      ConfigurationPlan existingPlan = configurationPlanRepository.findById(configurationPlan.id);
      if (existingPlan == null) {
         logger.errorf("Configuration plan with id %s not found", configurationPlan.id);
         throw new IllegalArgumentException("Configuration plan with id " + configurationPlan.id + " not found");
      }
      existingPlan.apiKey = UUID.randomUUID().toString();
      configurationPlanRepository.persistAndFlush(existingPlan);
      expositionManagerService.propagateConfigurationPlanChanges(existingPlan);
      return existingPlan;
   }

   /**
    * Deletes a configuration plan and its related expositions.
    * @param configurationPlan the configuration plan to delete
    */
   @Transactional
   public void deleteConfigurationPlan(ConfigurationPlan configurationPlan) {
      // Delete related expositions first.
      expositionManagerService.removeConfigurationPlanExpositions(configurationPlan.id);
      // Then delete the configuration plan itself.
      configurationPlanRepository.delete(configurationPlan);
   }

   /**
    * @param gatewayId The ID of the gateway for which to find configuration plans
    * @param gatewayLabels A map of labels associated with the gateway
    * @return A list of configuration plans for the given gatewayId and labels
    */
   public List<ConfigurationPlan> getExpositionConfigurations(String gatewayId, Map<String, String> gatewayLabels, List<String> fqdns) {
      logger.debugf("Finding the assigned groups for gatewayId: %s with labels %s", gatewayId, gatewayLabels);

      List<Exposition> expositions = new ArrayList<>();
      List<GatewayGroup> matchingGroups = new ArrayList<>();

      for (GatewayGroup gatewayGroup : gatewayGroupRepository.findAll().list()) {
         if (gatewayGroup.labels != null && gatewayGroup.labels.entrySet().containsAll(gatewayLabels.entrySet())) {
            logger.debugf("Found matching group %s with id %s", gatewayGroup.name, gatewayGroup.id);
            matchingGroups.add(gatewayGroup);
            expositions.addAll(expositionRepository.findByGatewayGroupId(gatewayGroup.id));
         }
      }
      gatewayManagerService.registerGateway(gatewayId, matchingGroups, fqdns);

      logger.debugf("Found %d expositions for gatewayId: %s", expositions.size(), gatewayId);
      return expositions.stream()
            .map(exposition -> exposition.configurationPlan)
            .toList();
   }

   /**
    * @param expositionId The ID of the exposition for which to find the configuration
    * @return The configuration plan associated with the given expositionId, or null if not found
    */
   public ConfigurationPlan getExpositionConfiguration(String expositionId) {
      logger.debugf("Finding exposition configuration for expositionId: %s", expositionId);
      Exposition exposition = expositionRepository.findById(expositionId);
      if (exposition != null) {
         return exposition.configurationPlan;
      } else {
         logger.warnf("No exposition found for id: %s", expositionId);
         return null;
      }
   }
}
