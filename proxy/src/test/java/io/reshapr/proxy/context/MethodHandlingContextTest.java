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
package io.reshapr.proxy.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link MethodHandlingContext} and {@link MethodHandlingInfo}.
 * @author vaishnav
 */
class MethodHandlingContextTest {

    @Test
    void testGettersViaScoped() {
        MethodHandlingInfo info = new MethodHandlingInfo(
                "192.168.1.1",
                new SessionInfo("session-abc", "svc-1", "2024-11-05"),
                "user-1",
                "https://issuer.example.com",
                "org-1"
        );

        ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, info).run(() -> {
            assertEquals("192.168.1.1", MethodHandlingContext.getRemoteAddress());
            assertEquals("user-1", MethodHandlingContext.getUserId());
            assertEquals("https://issuer.example.com", MethodHandlingContext.getIssuer());
            assertEquals("org-1", MethodHandlingContext.getOrganizationId());
            assertEquals("https://issuer.example.com|user-1", MethodHandlingContext.getUserKey());
            assertFalse(MethodHandlingContext.isStateless());
        });
    }

    @Test
    void testGetUserKeyNullWhenMissingIssuerOrSubject() {
        MethodHandlingInfo noIssuer = new MethodHandlingInfo("1.2.3.4", null, "user-1", null, "org-1");
        ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, noIssuer).run(() ->
                assertNull(MethodHandlingContext.getUserKey())
        );

        MethodHandlingInfo noUser = new MethodHandlingInfo("1.2.3.4", null, null, "https://issuer.example.com", "org-1");
        ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, noUser).run(() ->
                assertNull(MethodHandlingContext.getUserKey())
        );
    }

    @Test
    void testIsStatelessWhenNoSession() {
        MethodHandlingInfo info = new MethodHandlingInfo("1.2.3.4", null, null, null, null);
        ScopedValue.where(MethodHandlingContext.METHOD_HANDLING_INFO, info).run(() ->
                assertTrue(MethodHandlingContext.isStateless())
        );
    }
}
