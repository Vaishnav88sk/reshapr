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
package io.reshapr.ctrl.security;

import io.reshapr.ctrl.config.EncryptionConfig;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for encrypting and decrypting sensitive data stored in the database.
 * <p>
 * New values are encrypted with {@code AES/GCM/NoPadding} (AEAD: confidentiality + integrity) using
 * one of several configured keys, each identified by a key id ({@code kid}). The resulting ciphertext
 * is self-describing: {@code <kid>:<base64(iv || ciphertext || tag)>}. Storing the {@code kid} alongside
 * the ciphertext allows several keys to coexist, so a new "active" key can be introduced for future
 * writes while older keys remain available to decrypt values they previously encrypted, enabling
 * key rotation without a big-bang re-encryption of the whole database.
 * <p>
 * For backward compatibility, values encrypted before this migration (plain Base64, no {@code kid:}
 * prefix, produced by the legacy {@code AES/ECB/PKCS5Padding} scheme) are still decrypted using a
 * configured legacy key.
 * @author laurent
 */
@Unremovable
@ApplicationScoped
public class CipherService {

   /** Get a JBoss logging logger. */
   private static final Logger logger = Logger.getLogger(CipherService.class);

   private static final String AEAD_ALGORITHM = "AES/GCM/NoPadding";
   private static final String LEGACY_ALGORITHM = "AES/ECB/PKCS5Padding";
   private static final int[] AES_KEYSIZES = { 16, 24, 32 };
   private static final int GCM_IV_LENGTH_BYTES = 12;
   private static final int GCM_TAG_LENGTH_BITS = 128;
   private static final int AES_256_KEY_LENGTH = 32;

   private final SecureRandom secureRandom = new SecureRandom();

   private final EncryptionConfig config;

   private Map<String, SecretKeySpec> keys;
   private String activeKid;
   private SecretKeySpec legacyKey;

   /**
    * Creates a new instance of the CipherService with the given encryption configuration.
    * @param config The encryption configuration.
    */
   public CipherService(EncryptionConfig config) {
      this.config = config;
   }

   @PostConstruct
   void initialize() {
      if (config.keys() == null || config.keys().isEmpty()) {
         throw new IllegalStateException("At least one encryption key must be configured under reshapr.encryption.keys");
      }

      Map<String, SecretKeySpec> parsed = new HashMap<>();
      for (Map.Entry<String, String> entry : config.keys().entrySet()) {
         String kid = entry.getKey();
         String b64 = entry.getValue();
         if (b64 == null || b64.isBlank()) {
            continue;
         }
         byte[] raw = Base64.getDecoder().decode(b64);
         if (raw.length != AES_256_KEY_LENGTH) {
            throw new IllegalStateException("Encryption key '" + kid
                  + "' must be Base64-encoded 32 bytes (AES-256), got " + raw.length + " bytes");
         }
         parsed.put(kid, new SecretKeySpec(raw, "AES"));
      }

      if (!parsed.containsKey(config.activeKid())) {
         throw new IllegalStateException("Active encryption key id '" + config.activeKid() + "' not found in reshapr.encryption.keys");
      }

      this.keys = Map.copyOf(parsed);
      this.activeKid = config.activeKid();

      config.legacyKey().filter(s -> !s.isBlank()).ifPresent(k -> {
         byte[] raw = k.getBytes(StandardCharsets.UTF_8);
         if (!isKeySizeValid(raw.length)) {
            throw new IllegalStateException("Legacy encryption key must be 16, 24 or 32 characters long");
         }
         this.legacyKey = new SecretKeySpec(raw, "AES");
      });
   }

   /**
    * Forces this service to be instantiated eagerly at application startup so that any encryption
    * misconfiguration (missing, malformed or wrong-sized keys — see {@link #initialize()}) fails the
    * boot immediately, instead of surfacing lazily on the first encrypt/decrypt call — which happens
    * only through Arc lookups from JPA converters and Jackson (de)serializers.
    * @param event The Quarkus startup event.
    */
   void onStart(@Observes StartupEvent event) {
      logger.infof("CipherService ready: active key id '%s', %d key(s) configured, legacy key %s",
            activeKid, keys.size(), legacyKey != null ? "present" : "absent");
   }

   /**
    * Returns the id (kid) of the key currently used for new encryption operations.
    * @return The active key id.
    */
   public String activeKid() {
      return activeKid;
   }

   /**
    * Tells whether the given value looks like a ciphertext produced by {@link #encrypt(String)}, i.e.
    * whether it is prefixed by a {@code <kid>:} whose kid belongs to the configured keyset. This is
    * used to distinguish already-encrypted values from values still stored in clear (for example a
    * nested JSON property that predates its encryption), so that reads and re-encryption can handle
    * both transparently.
    * @param value The value to inspect.
    * @return {@code true} if the value is prefixed by a known key id, {@code false} otherwise.
    */
   public boolean isEncrypted(String value) {
      if (value == null) {
         return false;
      }
      int separatorIndex = value.indexOf(':');
      return separatorIndex > 0 && keys.containsKey(value.substring(0, separatorIndex));
   }

   /**
    * Encrypts the given plaintext with the active key, using AES/GCM with a random IV.
    * @param data The plaintext to encrypt.
    * @return The self-describing ciphertext, in the form {@code <kid>:<base64(iv || ciphertext || tag)>}.
    */
   public String encrypt(String data) {
      try {
         byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
         secureRandom.nextBytes(iv);

         Cipher cipher = Cipher.getInstance(AEAD_ALGORITHM);
         cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKid), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
         byte[] ciphertext = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));

         ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
         buffer.put(iv).put(ciphertext);
         return activeKid + ":" + Base64.getEncoder().encodeToString(buffer.array());
      } catch (GeneralSecurityException e) {
         throw new RuntimeException("Unable to encrypt data", e);
      }
   }

   /**
    * Decrypts a value produced by {@link #encrypt(String)}, or a legacy AES/ECB value predating
    * the key-rotation scheme.
    * @param encryptedData The ciphertext to decrypt.
    * @return The decrypted plaintext.
    */
   public String decrypt(String encryptedData) {
      int separatorIndex = encryptedData.indexOf(':');
      if (separatorIndex > 0) {
         String kid = encryptedData.substring(0, separatorIndex);
         SecretKeySpec key = keys.get(kid);
         if (key == null) {
            throw new IllegalStateException("Unable to decrypt value: unknown encryption key id '" + kid + "'");
         }
         return decryptGcm(key, encryptedData.substring(separatorIndex + 1));
      }
      if (legacyKey == null) {
         throw new IllegalStateException("Unable to decrypt legacy value: no reshapr.encryption.legacy-key configured");
      }
      return decryptLegacy(encryptedData);
   }

   private String decryptGcm(SecretKeySpec key, String payload) {
      try {
         byte[] raw = Base64.getDecoder().decode(payload);
         byte[] iv = Arrays.copyOfRange(raw, 0, GCM_IV_LENGTH_BYTES);
         byte[] ciphertext = Arrays.copyOfRange(raw, GCM_IV_LENGTH_BYTES, raw.length);

         Cipher cipher = Cipher.getInstance(AEAD_ALGORITHM);
         cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
         return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
      } catch (GeneralSecurityException e) {
         throw new RuntimeException("Unable to decrypt data", e);
      }
   }

   private String decryptLegacy(String encryptedData) {
      try {
         Cipher cipher = Cipher.getInstance(LEGACY_ALGORITHM);
         cipher.init(Cipher.DECRYPT_MODE, legacyKey);
         return new String(cipher.doFinal(Base64.getDecoder().decode(encryptedData)), StandardCharsets.UTF_8);
      } catch (GeneralSecurityException e) {
         throw new RuntimeException("Unable to decrypt data", e);
      }
   }

   private static boolean isKeySizeValid(int len) {
      for (int aesKeysize : AES_KEYSIZES) {
         if (len == aesKeysize) {
            return true;
         }
      }
      return false;
   }
}
