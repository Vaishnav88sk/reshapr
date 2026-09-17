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
package io.reshapr.ctrl.rest.admin;

import io.reshapr.ctrl.model.Organization;
import io.reshapr.ctrl.model.Quota;
import io.reshapr.ctrl.repository.OrganizationRepository;
import io.reshapr.ctrl.repository.QuotaRepository;
import io.reshapr.ctrl.security.AdminAuthenticated;

import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

@RunOnVirtualThread
@Path("/api/admin/quotas")
@AdminAuthenticated
public class QuotaResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final OrganizationRepository organizationRepository;
   private final QuotaRepository quotaRepository;

   /**
    * Build a QuotaResource with required dependencies.
    * @param organizationRepository The Organization repository
    * @param quotaRepository The Quota repository
    */
   public QuotaResource(OrganizationRepository organizationRepository, QuotaRepository quotaRepository) {
      this.organizationRepository = organizationRepository;
      this.quotaRepository = quotaRepository;
   }

   @GET
   @Path("/organization/{organizationName}")
   public Response getOrganizationQuotas(@PathParam("organizationName") String organizationName) {
      logger.debugf("Getting quotas for organization: %s", organizationName);

      // Find organization by name.
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null) {
         logger.warnf("Organization with name %s not found", organizationName);
         return Response.status(Response.Status.NOT_FOUND).build();
      }

      // List quotas for the organization.
      List<Quota> quotas = Quota.list("organizationId", Sort.ascending("metric"), organizationName);
      return Response.status(Response.Status.OK).entity(quotas).build();
   }

   @POST
   @Path("/organization/{organizationName}")
   @Transactional
   public Response setOrganizationQuotas(@PathParam("organizationName") String organizationName,
                                         List<QuotaDTO> quotas) {
      logger.debugf("Setting quotas for organization: %s", organizationName);

      // Find organization by name.
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null) {
         logger.warnf("Organization with name %s not found", organizationName);
         return Response.status(Response.Status.NOT_FOUND).build();
      }

      // For each quota in the list, set or update the quota for the organization.
      for (QuotaDTO quotaOTD : quotas) {
         logger.debugf("Checking quota on metric '%s' for org '%s'", quotaOTD.metric(), organizationName);
         Quota quota = Quota.getByMetricAndOrganizationForUpdate(quotaOTD.metric(), organizationName);
         logger.debugf("Current quota for metric %s: %s", quotaOTD.metric(), quota);
         if (quota != null) {
            // Update existing quota, preserving already consumed units:
            // remaining follows the limit delta, clamped between 0 and the new limit.
            long consumed = quota.limit - quota.remaining;
            quota.enabled = quotaOTD.enabled();
            quota.limit = quotaOTD.limit();
            quota.remaining = Math.max(0, quota.limit - Math.max(0, consumed));
            quotaRepository.persist(quota);
            logger.debugf("Updated quota for metric %s for organization %s", quota.metric, organizationName);
         } else {
            // Create new quota.
            quota = new Quota();
            quota.organizationId = organizationName;
            quota.metric = quotaOTD.metric();
            quota.enabled = quotaOTD.enabled();
            quota.limit = quotaOTD.limit();
            quota.remaining = quotaOTD.limit();
            quota.persist();
            logger.debugf("Created new quota for metric %s for organization %s", quota.metric, organizationName);
         }
      }
      // Flush all changes to the database before listing results.
      Quota.flush();

      List<Quota> newQuotas = Quota.list("organizationId", Sort.ascending("metric"), organizationName);
      return Response.status(Response.Status.OK).entity(newQuotas).build();
   }

   @POST
   @Path("/organization/{organizationName}/force")
   @Transactional
   public Response forceOrganizationQuotas(@PathParam("organizationName") String organizationName,
                                         List<FullQuotaDTO> quotas) {
      logger.debugf("Forcing quotas for organization: %s", organizationName);

      // Find organization by name.
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null) {
         logger.warnf("Organization with name %s not found", organizationName);
         return Response.status(Response.Status.NOT_FOUND).build();
      }

      // For each quota in the list, set or update the quota for the organization.
      for (FullQuotaDTO quotaOTD : quotas) {
         logger.debugf("Checking quota on metric '%s' for org '%s'", quotaOTD.metric(), organizationName);
         Quota quota = Quota.getByMetricAndOrganizationForUpdate(quotaOTD.metric(), organizationName);
         logger.debugf("Current quota for metric %s: %s", quotaOTD.metric(), quota);
         if (quota != null) {
            // Update existing quota.
            quota.enabled = quotaOTD.enabled();
            quota.limit = quotaOTD.limit();
            // Force setting remaining to limit.
            quota.remaining = quotaOTD.remaining();
            quotaRepository.persist(quota);
            logger.debugf("Updated quota for metric %s for organization %s", quota.metric, organizationName);
         } else {
            // Create new quota.
            quota = new Quota();
            quota.organizationId = organizationName;
            quota.metric = quotaOTD.metric();
            quota.enabled = quotaOTD.enabled();
            quota.limit = quotaOTD.limit();
            quota.remaining = quotaOTD.remaining();
            quota.persist();
            logger.debugf("Created new quota for metric %s for organization %s", quota.metric, organizationName);
         }
      }
      // Flush all changes to the database before listing results.
      Quota.flush();

      List<Quota> newQuotas = Quota.list("organizationId", Sort.ascending("metric"), organizationName);
      return Response.status(Response.Status.OK).entity(newQuotas).build();
   }
}
