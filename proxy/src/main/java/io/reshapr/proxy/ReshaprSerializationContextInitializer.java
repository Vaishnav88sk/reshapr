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

import io.reshapr.proxy.context.SecretValueEntry;
import io.reshapr.proxy.context.SessionInfo;
import io.reshapr.proxy.mcp.state.ElicitationInfo;
import io.reshapr.proxy.registry.OAuth2ClientConfigurationEntry;
import io.reshapr.proxy.registry.SecretEntry;

import org.infinispan.protostream.GeneratedSchema;
import org.infinispan.protostream.annotations.ProtoSchema;

/**
 * Protostream serialization context initializer for the replicated MCP state stores.
 * <p>
 * It generates, at compile time, the marshallers and the {@code .proto} schema for every state
 * object stored in the embedded Infinispan caches ({@code session-store}, {@code elicitation-store}
 * and {@code user-secret-store}). Generating at compile time (rather than using runtime reflection)
 * keeps the marshalling compatible with GraalVM native image.
 * <p>
 * The generated implementation is registered on the {@code EmbeddedCacheManager} global
 * serialization configuration by the cache producer (see plan Step 4).
 * @author laurent
 */
@ProtoSchema(
      includeClasses = {
            SessionInfo.class,
            SecretValueEntry.class,
            ElicitationInfo.class,
            SecretEntry.class,
            OAuth2ClientConfigurationEntry.class
      },
      schemaFileName = "reshapr-proxy-state.proto",
      schemaFilePath = "proto",
      schemaPackageName = "reshapr.proxy.state")
public interface ReshaprSerializationContextInitializer extends GeneratedSchema {
}

