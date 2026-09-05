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
package io.reshapr.proxy;

import io.reshapr.discovery.exposition.v1.ArtifactsRequest;
import io.reshapr.discovery.exposition.v1.ArtifactsResponse;
import io.reshapr.discovery.exposition.v1.ChangeType;
import io.reshapr.discovery.exposition.v1.Exposition;
import io.reshapr.discovery.exposition.v1.ExpositionChangeEvent;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryRequest;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryResponse;
import io.reshapr.discovery.exposition.v1.ExpositionDiscoveryServiceGrpc.ExpositionDiscoveryServiceBlockingStub;
import io.reshapr.discovery.exposition.v1.MutinyExpositionDiscoveryServiceGrpc.MutinyExpositionDiscoveryServiceStub;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.registry.GatewayRegistry;
import io.reshapr.proxy.registry.Mappers;

import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

/**
 * Unit tests for {@link ReshaprGatewayApp}.
 * @author vaishnav
 */
@ExtendWith(MockitoExtension.class)
class ReshaprGatewayAppTest {

    @Mock
    ExpositionDiscoveryServiceBlockingStub discoveryService;

    @Mock
    MutinyExpositionDiscoveryServiceStub asyncDiscoveryService;

    @Mock
    GatewayRegistry gatewayRegistry;

    @Mock
    Mappers registryMappers;

    @Mock
    WorkCache workCache;

    ReshaprGatewayApp app;

    @BeforeEach
    void setUp() {
        app = new ReshaprGatewayApp(discoveryService, asyncDiscoveryService, gatewayRegistry, registryMappers, workCache);
        app.gatewayId = "test-gateway";
        app.labels = Map.of("env", "test");
        app.fqdns = List.of("test.local");
        app.applicationVersion = "1.0.0";
    }

    @Test
    void validateConfigurationThrowsExceptionWhenNoFqdn() {
        app.fqdns = List.of();
        assertThrows(IllegalStateException.class, () -> app.validateConfiguration());

        app.fqdns = null;
        assertThrows(IllegalStateException.class, () -> app.validateConfiguration());
        
        app.fqdns = List.of("", " ");
        assertThrows(IllegalStateException.class, () -> app.validateConfiguration());
    }

    @Test
    void validateConfigurationSucceedsWithFqdn() {
        app.validateConfiguration(); // Should not throw
    }

    @Test
    void registerAndDiscoverExpositions() {
        Exposition exposition = Exposition.newBuilder()
                .setId("exp1")
                .setService(io.reshapr.discovery.exposition.v1.Service.newBuilder().setId("svc1").build())
                .setConfiguration(io.reshapr.discovery.exposition.v1.Configuration.newBuilder().build())
                .build();
        ExpositionDiscoveryResponse response = ExpositionDiscoveryResponse.newBuilder()
                .addExpositions(exposition)
                .build();

        when(discoveryService.discoverExpositions(any(ExpositionDiscoveryRequest.class))).thenReturn(response);

        // For fetchExposition
        io.reshapr.proxy.registry.ServiceEntry serviceEntry = mock(io.reshapr.proxy.registry.ServiceEntry.class);
        when(registryMappers.toServiceEntry(any())).thenReturn(serviceEntry);
        io.reshapr.proxy.registry.ConfigurationEntry configEntry = mock(io.reshapr.proxy.registry.ConfigurationEntry.class);
        when(registryMappers.toConfigurationEntry(any())).thenReturn(configEntry);

        ArtifactsResponse artifactsResponse = ArtifactsResponse.newBuilder().build();
        when(discoveryService.fetchArtifacts(any(ArtifactsRequest.class))).thenReturn(artifactsResponse);

        ExpositionDiscoveryResponse result = app.registerAndDiscoverExpositions();

        assertTrue(app.hasConnectedToControlPlane());
        verify(gatewayRegistry).addExposition(any(io.reshapr.proxy.registry.ExpositionEntry.class));
    }
    @Test
    void propagateExpositionChangeEvent() {
        Exposition exposition = Exposition.newBuilder()
                .setId("exp1")
                .setService(io.reshapr.discovery.exposition.v1.Service.newBuilder().setId("svc1").build())
                .setConfiguration(io.reshapr.discovery.exposition.v1.Configuration.newBuilder().build())
                .build();

        // CREATED
        ExpositionChangeEvent createdEvent = ExpositionChangeEvent.newBuilder()
                .setChangeType(ChangeType.CREATED)
                .setExposition(exposition)
                .build();
        
        io.reshapr.proxy.registry.ServiceEntry serviceEntry = mock(io.reshapr.proxy.registry.ServiceEntry.class);
        when(registryMappers.toServiceEntry(any())).thenReturn(serviceEntry);
        io.reshapr.proxy.registry.ConfigurationEntry configEntry = mock(io.reshapr.proxy.registry.ConfigurationEntry.class);
        when(registryMappers.toConfigurationEntry(any())).thenReturn(configEntry);
        ArtifactsResponse artifactsResponse = ArtifactsResponse.newBuilder().build();
        when(discoveryService.fetchArtifacts(any(ArtifactsRequest.class))).thenReturn(artifactsResponse);

        app.propagateExpositionChangeEvent(createdEvent);
        verify(gatewayRegistry, times(1)).addExposition(any());

        // UPDATED
        ExpositionChangeEvent updatedEvent = ExpositionChangeEvent.newBuilder()
                .setChangeType(ChangeType.UPDATED)
                .setExposition(exposition)
                .build();
        app.propagateExpositionChangeEvent(updatedEvent);
        verify(gatewayRegistry, times(2)).addExposition(any());

        // DELETED
        ExpositionChangeEvent deletedEvent = ExpositionChangeEvent.newBuilder()
                .setChangeType(ChangeType.DELETED)
                .setExposition(exposition)
                .build();
        app.propagateExpositionChangeEvent(deletedEvent);
        verify(gatewayRegistry, times(1)).removeExposition("exp1");
    }

    @Test
    void fetchExpositionWithUIResources() {
        Exposition exposition = Exposition.newBuilder()
                .setId("exp1")
                .setService(io.reshapr.discovery.exposition.v1.Service.newBuilder().setId("svc1").build())
                .setConfiguration(io.reshapr.discovery.exposition.v1.Configuration.newBuilder().build())
                .build();

        io.reshapr.proxy.registry.ServiceEntry serviceEntry = mock(io.reshapr.proxy.registry.ServiceEntry.class);
        when(serviceEntry.id()).thenReturn("svc1");
        when(registryMappers.toServiceEntry(any())).thenReturn(serviceEntry);
        io.reshapr.proxy.registry.ConfigurationEntry configEntry = mock(io.reshapr.proxy.registry.ConfigurationEntry.class);
        when(registryMappers.toConfigurationEntry(any())).thenReturn(configEntry);

        String yamlContent = "kind: Resources\n" +
                "apiVersion: reshapr.io/v1alpha1\n" +
                "resources:\n" +
                "  ui://test-resource:\n" +
                "    tools:\n" +
                "      - \"test-tool\"\n" +
                "      - \"test-tool-2\":\n" +
                "          visibility: [\"app\"]\n";
        
        io.reshapr.discovery.exposition.v1.Artifact attachedArtifact = io.reshapr.discovery.exposition.v1.Artifact.newBuilder()
                .setId("art1")
                .setContent(yamlContent)
                .build();

        ArtifactsResponse artifactsResponse = ArtifactsResponse.newBuilder()
                .addArtifacts(attachedArtifact)
                .build();
        when(discoveryService.fetchArtifacts(any(ArtifactsRequest.class))).thenReturn(artifactsResponse);

        io.reshapr.proxy.registry.ArtifactEntry artifactEntry = new io.reshapr.proxy.registry.ArtifactEntry(
                "art1", "svc1", "test.yaml", io.reshapr.proxy.registry.ArtifactEntryType.RESHAPR_RESOURCES, false, yamlContent
        );
        when(registryMappers.toArtifactEntry(any())).thenReturn(artifactEntry);

        app.fetchExposition(exposition);

        verify(gatewayRegistry, times(1)).addExposition(any());
        verify(gatewayRegistry, times(2)).addResourceForTool(any(io.reshapr.proxy.registry.ToolEntry.class), any(io.reshapr.proxy.registry.ResourceEntry.class));
    }
}
