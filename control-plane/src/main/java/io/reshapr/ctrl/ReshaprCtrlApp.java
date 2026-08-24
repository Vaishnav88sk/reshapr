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
package io.reshapr.ctrl;

import io.reshapr.ctrl.model.ApiToken;
import io.reshapr.ctrl.model.GatewayGroup;
import io.reshapr.ctrl.model.Organization;
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.model.UserStatus;
import io.reshapr.ctrl.repository.ApiTokenRepository;
import io.reshapr.ctrl.repository.GatewayGroupRepository;
import io.reshapr.ctrl.repository.OrganizationRepository;
import io.reshapr.ctrl.repository.UserRepository;
import io.reshapr.ctrl.security.ReshaprTenantContext;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.reshapr.ctrl.security.ReshaprTenantResolver.ROOT_TENANT_ID;

/**
 * Main application class for the Reshapr control plane.
 * @author laurent
 */
@ApplicationScoped
public class ReshaprCtrlApp {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final UserRepository userRepository;
   private final OrganizationRepository organizationRepository;
   private final ApiTokenRepository apiTokenRepository;
   private final GatewayGroupRepository gatewayGroupRepository;

   @ConfigProperty(name = "reshapr.admin.name")
   Optional<String> adminName;

   @ConfigProperty(name = "reshapr.admin.email")
   Optional<String> adminEmail;

   @ConfigProperty(name = "reshapr.admin.password")
   Optional<String> adminPassword;

   @ConfigProperty(name = "reshapr.default-gateway-tokens")
   Optional<List<String>> defaultGatewayTokens;

   @ConfigProperty(name = "reshapr.default-gateway-labels")
   Optional<Map<String, String>> defaultGatewayLabels;

   /**
    * Build a ReshaprCtrlApp with required dependencies.
    * @param userRepository The User repository
    * @param organizationRepository The Organization repository
    * @param apiTokenRepository The API token repository
    */
   public ReshaprCtrlApp(UserRepository userRepository, OrganizationRepository organizationRepository,
                         ApiTokenRepository apiTokenRepository, GatewayGroupRepository gatewayGroupRepository) {
      this.userRepository = userRepository;
      this.organizationRepository = organizationRepository;
      this.apiTokenRepository = apiTokenRepository;
      this.gatewayGroupRepository = gatewayGroupRepository;
   }

   /** Application startup method. */
   @Transactional
   void onStart(@Observes StartupEvent ev) {
      logger.info("reShapr Control Plane is starting...");

      // Set the tenant context to "reshapr" for initialization.
      // This ensures that the default organization and admin user are created in the "reshapr" tenant.
      ReshaprTenantContext.setCurrentTenant(ROOT_TENANT_ID);

      User admin = initializeAdminAccount();
      initializeDefaultGatewayProperties(admin);

      logger.info("reShapr Control Plane startup complete.");
   }

   /** Initialize the admin account if configured. */
   private User initializeAdminAccount() {
      if (adminName != null && adminName.isPresent() && adminEmail != null && adminEmail.isPresent()
            && adminPassword != null && adminPassword.isPresent()) {
         logger.info("Admin user is configured. Checking if it exists and creating it if not...");

         // Check if admin already exists.
         User admin = userRepository.findByUsername(adminName.get());
         if (admin == null) {
            // Create and persist admin.
            admin = new User();
            admin.username = adminName.get();
            admin.email = adminEmail.get();
            admin.password = BcryptUtil.bcryptHash(adminPassword.get());
            admin.status = UserStatus.REGISTERED;
            userRepository.persistAndFlush(admin);

            logger.infof("Admin user '%s' created with email '%s'", admin.username, admin.email);

            // Update the "reshapr" organization to be owned by the admin and add it to the admin's organizations.
            Organization reshaprOrg = organizationRepository.findByName(ROOT_TENANT_ID);
            if (reshaprOrg != null) {
               reshaprOrg.owner = admin;
               organizationRepository.persistAndFlush(reshaprOrg);

               admin.organizations = List.of(reshaprOrg);
               admin.defaultOrganization = reshaprOrg;
               userRepository.persistAndFlush(admin);

               logger.infof("Admin user '%s' is now the owner of the 'reshapr' organization", admin.username);
            }
         }
         return admin;
      } else {
         logger.warn("Admin user is not configured. Please set 'reshapr.admin.name', 'reshapr.admin.email' and " +
               "'reshapr.admin.password' in the configuration or initialize it externally with the create-admin script.");
      }
      return null;
   }

   /** Initialize default gateway properties if configured. */
   private void initializeDefaultGatewayProperties(User admin) {
      if (defaultGatewayTokens != null && defaultGatewayTokens.isPresent()) {
         logger.info("Default gateway tokens are configured. Initializing them...");
         DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

         int i = 1;
         for (String token : defaultGatewayTokens.get()) {
            if (token != null && !token.isBlank()) {
               String tokenName = "default-gateway-token-" + i++;
               // Check token existence and create it if it doesn't exist.
               ApiToken apiToken = apiTokenRepository.findByNameAndOrganizationId(tokenName, ROOT_TENANT_ID);
               if (apiToken == null) {
                  logger.debugf("Default gateway token '%s' doesn't exists. Create it.", tokenName);
                  apiToken = new ApiToken();
                  apiToken.name = tokenName;
                  apiToken.token = token;
                  apiToken.organizationId = ROOT_TENANT_ID;
                  apiToken.validUntil = LocalDateTime.parse("2027-12-24 23:59:59", formatter).atOffset(ZoneOffset.UTC);
                  if (admin != null) {
                     apiToken.user = admin;
                  }
               } else {
                  logger.warnf("Default gateway token '%s' already exists. Update it.", tokenName);
                  logger.warnf("Be sure to update the configuration of the proxies that use it.");
                  apiToken.token = token;
               }
               apiTokenRepository.persist(apiToken);
            }
         }
      } else {
         logger.warn("Default gateway tokens are not configured. Please set 'reshapr.default-gateway-tokens' " +
               "in the configuration to initialize them.");
      }

      if (defaultGatewayLabels != null && defaultGatewayLabels.isPresent()) {
         logger.info("Default gateway group labels are configured. Updating them...");

         GatewayGroup defaultGatewayGroup = gatewayGroupRepository.findById(GatewayGroup.DEFAULT_GATEWAY_GROUP_ID);
         defaultGatewayGroup.labels = defaultGatewayLabels.get();
         gatewayGroupRepository.persist(defaultGatewayGroup);
      } else {
         logger.warn("Default gateway group labels are not configured. Please set 'reshapr.default-gateway-labels' " +
               "in the configuration to initialize them.");
      }
   }
}
