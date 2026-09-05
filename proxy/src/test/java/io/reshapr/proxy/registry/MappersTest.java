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

package io.reshapr.proxy.registry;

import io.reshapr.discovery.exposition.v1.Artifact;
import io.reshapr.discovery.exposition.v1.ArtifactType;
import io.reshapr.discovery.exposition.v1.CachePolicy;
import io.reshapr.discovery.exposition.v1.Configuration;
import io.reshapr.discovery.exposition.v1.OAuth2Configuration;
import io.reshapr.discovery.exposition.v1.Secret;
import io.reshapr.discovery.exposition.v1.Service;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MappersTest {

    private final Mappers mappers = new MappersImpl();

    @Test
    void shouldMapServiceToServiceEntry() {
        Service service = Service.newBuilder()
                .setId("service-id")
                .setName("Test Service")
                .build();

        ServiceEntry entry = mappers.toServiceEntry(service);

        assertNotNull(entry);
        assertEquals("service-id", entry.id());
        assertEquals("Test Service", entry.name());
        assertEquals(0, entry.operations().size());
    }

    @Test
    void shouldMapConfigurationToConfigurationEntry() {
        Configuration configuration = Configuration.newBuilder()
                .setId("config-id")
                .setBackendEndpoint("https://backend.example.com")
                .setBackendTimeout(5000L)
                .addIncludedOperations("op1")
                .addExcludedOperations("op2")
                .build();

        ConfigurationEntry entry = mappers.toConfigurationEntry(configuration);

        assertNotNull(entry);
        assertEquals("config-id", entry.id());
        assertEquals("https://backend.example.com", entry.backendEndpoint());
        assertEquals(5000L, entry.backendTimeout());
        assertEquals(1, entry.includedOperations().size());
        assertEquals("op1", entry.includedOperations().get(0));
        assertEquals(1, entry.excludedOperations().size());
        assertEquals("op2", entry.excludedOperations().get(0));
        assertNull(entry.cachePolicy());
    }

    @Test
    void shouldMapConfigurationWithCachePolicy() {
        CachePolicy cachePolicy = CachePolicy.newBuilder()
                .setTtlMs(10000L)
                .setCacheScope("global")
                .build();

        Configuration configuration = Configuration.newBuilder()
                .setId("config-id")
                .setBackendEndpoint("https://backend.example.com")
                .setCachePolicy(cachePolicy)
                .build();

        ConfigurationEntry entry = mappers.toConfigurationEntry(configuration);

        assertNotNull(entry);
        assertNotNull(entry.cachePolicy());
        assertEquals(10000L, entry.cachePolicy().ttlMs());
        assertEquals("global", entry.cachePolicy().cacheScope());
    }

    @Test
    void shouldMapOAuth2ConfigurationToEntry() {
        OAuth2Configuration configuration = OAuth2Configuration.newBuilder()
                .addAuthorizationServers("https://auth.example.com")
                .addScopes("read")
                .addScopes("write")
                .build();

        OAuth2ConfigurationEntry entry = mappers.toOAuth2ConfigurationEntry(configuration);

        assertNotNull(entry);
        assertEquals(1, entry.authorizationServers().size());
        assertEquals("https://auth.example.com", entry.authorizationServers().get(0));
        assertEquals(2, entry.scopes().size());
        assertTrue(entry.scopes().contains("read"));
    }

    @Test
    void shouldMapSecretToSecretEntry() {
        Secret secret = Secret.newBuilder()
                .setName("secret-name")
                .setToken("my-token")
                .setUseElicitation(true)
                .build();

        SecretEntry entry = mappers.toSecret(secret);

        assertNotNull(entry);
        assertEquals("secret-name", entry.name());
        assertEquals("my-token", entry.token());
        assertTrue(entry.useElicitation());
    }

    @Test
    void shouldMapArtifactToArtifactEntry() {
        Artifact artifact = Artifact.newBuilder()
                .setId("artifact-id")
                .setType(ArtifactType.OPEN_API_SPEC)
                .setContent("artifact content")
                .build();

        ArtifactEntry entry = mappers.toArtifactEntry(artifact);

        assertNotNull(entry);
        assertEquals("artifact-id", entry.id());
        assertEquals(ArtifactEntryType.OPEN_API_SPEC, entry.type());
        assertEquals("artifact content", entry.content());
    }
}
