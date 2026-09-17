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
package io.reshapr.ctrl.model;

import io.quarkus.panache.common.Parameters;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.QueryHint;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A quota that defines limits on the usage of a specific metric for an organization.
 * @author laurent
 */
@Entity
@Cacheable
@Table(name = "quotas", uniqueConstraints = {
      @UniqueConstraint(columnNames = {"metric", "organization_id"})
})
@NamedQueries({
      @NamedQuery(name = "Quota.getByMetric", query = "from Quota where metric = ?1 and organizationId = ?2",
            hints = @QueryHint(name = "org.hibernate.cacheable", value = "true") ),
      @NamedQuery(name = "Quota.decrementRemaining", query = "update Quota q set q.remaining = q.remaining - 1 where metric = ?1 and q.organizationId = ?2 and q.remaining > 0")
})
public class Quota extends BaseEntity {

   @Column(name = "organization_id")
   public String organizationId;
   public String metric;
   public boolean enabled;

   @Column(name = "m_limit")
   public Long limit;
   public Long remaining;

   public static Quota getByMetricAndOrganization(String metric, String organizationId) {
      return find("#Quota.getByMetric", metric, organizationId).firstResult();
   }

   /**
    * Same as {@link #getByMetricAndOrganization(String, String)} but acquires a pessimistic write
    * lock on the row and bypasses the query cache. Use for read-modify-write updates to avoid
    * lost updates against concurrent {@link #decrementRemaining(String, String)} calls.
    */
   public static Quota getByMetricAndOrganizationForUpdate(String metric, String organizationId) {
      return find("metric = :metric and organizationId = :orgId",
            Parameters.with("metric", metric).and("orgId", organizationId))
            .withLock(LockModeType.PESSIMISTIC_WRITE)
            .firstResult();
   }

   public static int decrementRemaining(String metric, String organizationId) {
      return update("#Quota.decrementRemaining", metric, organizationId);
   }

   public static int incrementRemaining(String metric, String organizationId) {
      Quota quota = getByMetricAndOrganization(metric, organizationId);
      if (quota != null && quota.enabled) {
         quota.remaining = Math.min(quota.limit, quota.remaining + 1);
         quota.persist();
         return 1;
      }
      return -1;
   }
}
