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
package io.reshapr.ctrl.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;
import java.util.Optional;

/**
 * Configuration mapping for encryption keys.
 * <p>
 * Keys are provided as Base64-encoded 32-byte values (AES-256) sourced from an external
 * secret manager (Vault) and injected via environment variables.
 */
@ConfigMapping(prefix = "reshapr.encryption")
public interface EncryptionConfig {

   /** Identifier of the key used for all new encryption operations. */
   @WithDefault("v1")
   String activeKid();

   /**
    * All keys available for decryption, keyed by their identifier (kid).
    * The primary key MUST be present in this map.
    * Values are Base64-encoded 32-byte keys (AES-256).
    */
   Map<String, String> keys();

   /**
    * Optional legacy key used to decrypt data written by the previous ECB-based implementation.
    * Once all data has been re-encrypted, this key should be removed.
    */
   Optional<String> legacyKey();
}