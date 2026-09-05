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
package io.reshapr.proxy.health;

import io.reshapr.proxy.ReshaprGatewayApp;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConnectOnStartupHealthCheck}.
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class ConnectOnStartupHealthCheckTest {

    @Mock
    ReshaprGatewayApp reshaprGatewayApp;

    @Test
    void returnsUpWhenConnected() {
        when(reshaprGatewayApp.hasConnectedToControlPlane()).thenReturn(true);
        ConnectOnStartupHealthCheck check = new ConnectOnStartupHealthCheck(reshaprGatewayApp);

        HealthCheckResponse response = check.call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("ConnectOnStartupHealthCheck", response.getName());
    }

    @Test
    void returnsDownWhenNotConnected() {
        when(reshaprGatewayApp.hasConnectedToControlPlane()).thenReturn(false);
        ConnectOnStartupHealthCheck check = new ConnectOnStartupHealthCheck(reshaprGatewayApp);

        HealthCheckResponse response = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }
}
