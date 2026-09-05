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

import io.reshapr.health.gateway.v1.GatewayHealthResponse;
import io.reshapr.health.gateway.v1.GatewayHealthServiceGrpc;
import io.reshapr.health.gateway.v1.GatewayRequest;
import io.reshapr.proxy.ReshaprGatewayApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HealthAdvertiser}.
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class HealthAdvertiserTest {

    @Mock
    GatewayHealthServiceGrpc.GatewayHealthServiceBlockingStub healthService;

    @Mock
    ReshaprGatewayApp reshaprGatewayApp;

    HealthAdvertiser healthAdvertiser;

    @BeforeEach
    void setUp() {
        healthAdvertiser = new HealthAdvertiser(healthService, reshaprGatewayApp);
        healthAdvertiser.gatewayId = "test-gateway-123";
    }

    @Test
    void advertiseHealthSuccess() {
        GatewayHealthResponse response = GatewayHealthResponse.newBuilder().setAcknowledged(true).build();
        when(healthService.advertHealthy(any(GatewayRequest.class))).thenReturn(response);

        healthAdvertiser.advertiseHealth();

        verify(healthService).advertHealthy(any(GatewayRequest.class));
        verify(reshaprGatewayApp, never()).registerAndDiscoverExpositions();
    }

    @Test
    void advertiseHealthNotAcknowledgedTriggersRegistration() {
        GatewayHealthResponse response = GatewayHealthResponse.newBuilder().setAcknowledged(false).build();
        when(healthService.advertHealthy(any(GatewayRequest.class))).thenReturn(response);

        healthAdvertiser.advertiseHealth();

        verify(healthService).advertHealthy(any(GatewayRequest.class));
        verify(reshaprGatewayApp).registerAndDiscoverExpositions();
    }

    @Test
    void advertiseHealthExceptionIgnored() {
        when(healthService.advertHealthy(any(GatewayRequest.class))).thenThrow(new RuntimeException("Network error"));

        healthAdvertiser.advertiseHealth();

        verify(healthService).advertHealthy(any(GatewayRequest.class));
        // exception is caught and logged, not propagated
        verify(reshaprGatewayApp, never()).registerAndDiscoverExpositions();
    }

    @Test
    void advertiseShutdownSuccess() {
        GatewayHealthResponse response = GatewayHealthResponse.newBuilder().setAcknowledged(true).build();
        when(healthService.advertShutdown(any(GatewayRequest.class))).thenReturn(response);

        healthAdvertiser.advertiseShutdown();

        verify(healthService).advertShutdown(any(GatewayRequest.class));
    }

    @Test
    void advertiseShutdownExceptionIgnored() {
        when(healthService.advertShutdown(any(GatewayRequest.class))).thenThrow(new RuntimeException("Connection closed"));

        healthAdvertiser.advertiseShutdown();

        verify(healthService).advertShutdown(any(GatewayRequest.class));
    }
}
