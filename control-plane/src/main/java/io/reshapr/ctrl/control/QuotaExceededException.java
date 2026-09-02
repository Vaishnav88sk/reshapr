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
package io.reshapr.ctrl.control;

/**
 * Business exception thrown when an organization has reached the limit of a quota metric and the
 * requested operation would exceed it. This is a transport-agnostic exception: the calling layer
 * (REST, gRPC, ...) is responsible for translating it into the appropriate protocol error (e.g. a
 * gRPC {@code RESOURCE_EXHAUSTED} status or an HTTP {@code 429 Too Many Requests}). It is a checked
 * exception on purpose, so that every method able to raise it declares it in its signature and each
 * call site has to decide explicitly where the quota rejection is handled.
 * @author laurent
 */
public class QuotaExceededException extends Exception {

   private final String metric;
   private final String organizationId;

   public QuotaExceededException(String metric, String organizationId) {
      super("Quota limit reached for metric '" + metric + "' on organization '" + organizationId + "'");
      this.metric = metric;
      this.organizationId = organizationId;
   }

   /** @return the quota metric that has been exhausted. */
   public String getMetric() {
      return metric;
   }

   /** @return the organization owning the exhausted quota. */
   public String getOrganizationId() {
      return organizationId;
   }
}
