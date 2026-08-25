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
package io.reshapr.proxy.mcp;

import io.reshapr.proxy.proxy.BackendResponse;
import io.reshapr.proxy.proxy.HeadersUtil;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ResponseAttributes}, focused on the merge policies used to aggregate
 * response metadata across multiple backend calls (typically triggered by custom-tool scripts).
 * @author laurent
 */
class ResponseAttributesTest {

   @Test
   void testFromBackendKeepsAllowListedHeadersOnly() {
      Map<String, List<String>> backendHeaders = Map.of(
            "Content-Type", List.of("application/json"),
            HeadersUtil.UPSTREAM_SERVICE_TIME, List.of("120")
      );

      ResponseAttributes attrs = ResponseAttributes.fromBackend(backendHeaders);

      Map<String, List<String>> http = attrs.toHttpHeaders();
      assertEquals(List.of("120"), http.get(HeadersUtil.UPSTREAM_SERVICE_TIME));
      assertNull(http.get("Content-Type"), "non allow-listed headers must not leak through");
   }

   @Test
   void testFromBackendIsCaseInsensitive() {
      Map<String, List<String>> backendHeaders = Map.of(
            "x-reshapr-upstream-service-time", List.of("42")
      );

      ResponseAttributes attrs = ResponseAttributes.fromBackend(backendHeaders);

      assertEquals(List.of("42"), attrs.toHttpHeaders().get(HeadersUtil.UPSTREAM_SERVICE_TIME));
   }

   @Test
   void testFromBackendResponseReadsTypedUpstreamServiceTime() {
      BackendResponse br = new BackendResponse(200, new byte[0], Map.of(), 87L);
      ResponseAttributes attrs = ResponseAttributes.fromBackend(br);
      assertEquals(List.of("87"), attrs.toHttpHeaders().get(HeadersUtil.UPSTREAM_SERVICE_TIME));
   }

   @Test
   void testFromBackendResponseSkipsNegativeUpstreamServiceTime() {
      BackendResponse br = new BackendResponse(500, new byte[0], Map.of(), -1L);
      ResponseAttributes attrs = ResponseAttributes.fromBackend(br);
      assertTrue(attrs.isEmpty(), "a negative upstream time (not measured) must not populate the carrier");
   }

   @Test
   void testMergeAppliesMaxLongPolicyForUpstreamServiceTime() {
      ResponseAttributes a = ResponseAttributes.fromBackend(
            Map.of(HeadersUtil.UPSTREAM_SERVICE_TIME, List.of("120")));
      ResponseAttributes b = ResponseAttributes.fromBackend(
            Map.of(HeadersUtil.UPSTREAM_SERVICE_TIME, List.of("300")));
      ResponseAttributes c = ResponseAttributes.fromBackend(
            Map.of(HeadersUtil.UPSTREAM_SERVICE_TIME, List.of("42")));

      a.merge(b);
      a.merge(c);

      assertEquals(List.of("300"), a.toHttpHeaders().get(HeadersUtil.UPSTREAM_SERVICE_TIME),
            "MAX_LONG policy must keep the highest upstream service time across backends");
   }

   @Test
   void testMergeIgnoresUnknownHeaders() {
      ResponseAttributes a = ResponseAttributes.empty();
      a.set("X-Not-Tracked", "anything");
      assertTrue(a.isEmpty(), "unknown headers must be silently dropped");
   }

   @Test
   void testMergeToleratesNullOtherAndEmptyBackendHeaders() {
      ResponseAttributes a = ResponseAttributes.fromBackend(
            Map.of(HeadersUtil.UPSTREAM_SERVICE_TIME, List.of("55")));
      a.merge(null);
      a.merge(ResponseAttributes.fromBackend((Map<String, List<String>>) null));
      a.merge(ResponseAttributes.fromBackend(Map.of()));
      assertEquals(List.of("55"), a.toHttpHeaders().get(HeadersUtil.UPSTREAM_SERVICE_TIME));
   }
}
