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

import io.reshapr.ctrl.control.QuotaExceededException;
import io.reshapr.ctrl.model.Quota;
import io.reshapr.ctrl.model.QuotaMetric;
import io.reshapr.ctrl.repository.GatewayRepository;
import io.reshapr.ctrl.security.ReshaprTenantContext;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link GatewayManagerService} gateway lifecycle, focusing on the
 * {@link QuotaMetric#GATEWAY_COUNT} quota accounting: a new registration consumes a unit, a first-time
 * registration is rejected once the quota is exhausted, and both the explicit shutdown and the expired
 * registration cleaner release the consumed unit back to the owning organization.
 * @author laurent
 */
@QuarkusTest
@ActivateRequestContext
class GatewayManagerServiceTest {

   private static final String METRIC = QuotaMetric.GATEWAY_COUNT.toString();

   @Inject
   GatewayManagerService gatewayManagerService;

   @Inject
   GatewayRepository gatewayRepository;

   @Test
   void testNewRegistrationConsumesQuotaAndHeartbeatDoesNot() throws QuotaExceededException {
      String org = seedGatewayQuota(2, true);

      gatewayManagerService.registerGateway("gw-a", List.of(), List.of("a.example.com"), Map.of(), "1.0.0");
      assertEquals(1L, remaining(org), "a new registration must consume one gateway quota unit");

      // Re-registering the same gateway (heartbeat refresh) must not consume the quota again.
      gatewayManagerService.registerGateway("gw-a", List.of(), List.of("a.example.com"), Map.of(), "1.0.1");
      assertEquals(1L, remaining(org), "refreshing an existing registration must not consume quota");
   }

   @Test
   void testRegistrationRejectedWhenQuotaExhausted() throws QuotaExceededException {
      String org = seedGatewayQuota(1, true);

      gatewayManagerService.registerGateway("gw-a", List.of(), List.of(), Map.of(), "1.0.0");
      assertEquals(0L, remaining(org));

      QuotaExceededException thrown = assertThrows(QuotaExceededException.class, () ->
            gatewayManagerService.registerGateway("gw-b", List.of(), List.of(), Map.of(), "1.0.0"));
      assertEquals(QuotaMetric.GATEWAY_COUNT.toString(), thrown.getMetric());
      assertEquals(org, thrown.getOrganizationId());

      // The rejected gateway must not have been persisted (transaction rolled back).
      assertNull(findByName(org, "gw-b"), "a rejected registration must not persist the gateway");
      assertEquals(0L, remaining(org));
   }

   @Test
   void testUnregisterReleasesQuota() throws QuotaExceededException {
      String org = seedGatewayQuota(1, true);

      gatewayManagerService.registerGateway("gw-a", List.of(), List.of(), Map.of(), "1.0.0");
      assertEquals(0L, remaining(org));

      gatewayManagerService.unregisterGateway("gw-a");
      assertEquals(1L, remaining(org), "shutdown must release the consumed quota unit");
   }

   @Test
   void testCleanerReleasesQuotaForEachOwningOrganization() throws QuotaExceededException {
      String org = seedGatewayQuota(1, true);

      gatewayManagerService.registerGateway("gw-a", List.of(), List.of(), Map.of(), "1.0.0");
      assertEquals(0L, remaining(org));

      // The cleaner runs under the root tenant, spanning organizations.
      ReshaprTenantContext.setCurrentTenant("reshapr");
      gatewayManagerService.cleanExpiredRegistrations(OffsetDateTime.now().plusMinutes(1));

      assertEquals(1L, remaining(org), "the cleaner must release quota for the gateway's own organization");
   }

   @Test
   void testDisabledQuotaIsNeitherEnforcedNorTracked() throws QuotaExceededException {
      String org = seedGatewayQuota(0, false);

      // With a disabled quota, registration is unlimited and remaining stays untouched.
      gatewayManagerService.registerGateway("gw-a", List.of(), List.of(), Map.of(), "1.0.0");
      gatewayManagerService.registerGateway("gw-b", List.of(), List.of(), Map.of(), "1.0.0");
      assertEquals(0L, remaining(org));
      assertTrue(findByName(org, "gw-a") != null && findByName(org, "gw-b") != null);
   }

   private String seedGatewayQuota(long limit, boolean enabled) {
      String org = "test-org-" + UUID.randomUUID();
      ReshaprTenantContext.setCurrentTenant(org);
      QuarkusTransaction.requiringNew().run(() -> {
         Quota quota = new Quota();
         quota.organizationId = org;
         quota.metric = METRIC;
         quota.enabled = enabled;
         quota.limit = limit;
         quota.remaining = limit;
         quota.persist();
      });
      return org;
   }

   private long remaining(String org) {
      return QuarkusTransaction.requiringNew().call(() ->
            Quota.getByMetricAndOrganization(METRIC, org).remaining);
   }

   private Object findByName(String org, String name) {
      ReshaprTenantContext.setCurrentTenant(org);
      return QuarkusTransaction.requiringNew().call(() ->
            gatewayRepository.findByName(name).orElse(null));
   }
}
