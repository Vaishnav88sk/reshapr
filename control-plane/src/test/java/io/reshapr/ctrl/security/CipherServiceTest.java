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

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CipherService}: AES/GCM round-trip, key rotation and legacy AES/ECB
 * decryption fallback.
 * @author laurent
 */
class CipherServiceTest {

   /** Base64-encoded 32-byte AES-256 keys, as provided via configuration. */
   private static final String KEY_V1 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
   private static final String KEY_V2 = "AAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK+2vcTL0tk=";
   /** A 32-character passphrase reproducing the pre-migration legacy AES/ECB key. */
   private static final String LEGACY_KEY = "my-super-secret-key-32-char-long";

   private class EncryptionConfigImpl implements EncryptionConfig {
      private String activeKid;
      private Map<String, String> keys;
      private Optional<String> legacyKey;

      public EncryptionConfigImpl(String activeKid, Map<String, String> keys, Optional<String> legacyKey) {
         this.activeKid = activeKid;
         this.keys = keys;
         this.legacyKey = legacyKey;
      }

      @Override
      public String activeKid() {
         return activeKid;
      }

      @Override
      public Map<String, String> keys() {
         return keys;
      }

      @Override
      public Optional<String> legacyKey() {
         return legacyKey;
      }
   }

   // ---------------------------------------------------------------------
   //  Initialize validation
   // ---------------------------------------------------------------------

   @Test
   void testInitializeRejectsEmptyKeys() {
      CipherService service = new CipherService(new EncryptionConfigImpl("v1", Map.of(), Optional.empty()));
      assertThrows(IllegalStateException.class, service::initialize);
   }

   @Test
   void testInitializeRejectsUnknownActiveKeyId() {
      CipherService service = new CipherService(new EncryptionConfigImpl("v2", Map.of("v1", KEY_V1), Optional.empty()));
      assertThrows(IllegalStateException.class, service::initialize);
   }

   @Test
   void testInitializeRejectsInvalidKeySize() {
      CipherService service = new CipherService(new EncryptionConfigImpl("v1", Map.of("v1", "dG9vLXNob3J0"), Optional.empty()));
      assertThrows(IllegalStateException.class, service::initialize);
   }

   // ---------------------------------------------------------------------
   //  Encrypt / decrypt round-trip
   // ---------------------------------------------------------------------

   @Test
   void testEncryptThenDecryptReturnsOriginalValue() {
      var service = new CipherService(new EncryptionConfigImpl("v1", Map.of("v1", KEY_V1), Optional.empty()));
      service.initialize();

      String encrypted = service.encrypt("hello world");

      assertEquals("hello world", service.decrypt(encrypted));
   }

   @Test
   void testEncryptPrefixesCiphertextWithActiveKeyId() {
      var service = new CipherService(new EncryptionConfigImpl("v1", Map.of("v1", KEY_V1), Optional.empty()));
      service.initialize();

      assertTrue(service.encrypt("some-secret").startsWith("v1:"));
   }

   @Test
   void testEncryptIsNonDeterministic() {
      // Unlike the legacy AES/ECB scheme, GCM uses a random IV so encrypting the same plaintext
      // twice must not yield the same ciphertext.
      var service = new CipherService(new EncryptionConfigImpl("v1", Map.of("v1", KEY_V1), Optional.empty()));
      service.initialize();

      assertNotEquals(service.encrypt("same-value"), service.encrypt("same-value"));
   }

   @Test
   void testDecryptRejectsTamperedCiphertext() {
      // GCM is authenticated: flipping a byte in the payload must fail decryption rather than
      // silently returning corrupted plaintext.
      var service = new CipherService(new EncryptionConfigImpl("v1", Map.of("v1", KEY_V1), Optional.empty()));
      service.initialize();

      String encrypted = service.encrypt("hello world");
      String tampered = encrypted.substring(0, encrypted.length() - 4) + "abcd";

      assertThrows(RuntimeException.class, () -> service.decrypt(tampered));
   }

   // ---------------------------------------------------------------------
   //  Key rotation
   // ---------------------------------------------------------------------

   @Test
   void testDecryptUsesKeyIdEmbeddedInCiphertextAfterRotation() {
      var serviceV1 = new CipherService(new EncryptionConfigImpl("v1", Map.of("v1", KEY_V1), Optional.empty()));
      serviceV1.initialize();

      String encryptedWithV1 = serviceV1.encrypt("rotate-me");

      // After rotation, both keys are configured but "v2" is now active for new writes.
      var serviceV2 = new CipherService(new EncryptionConfigImpl("v2", Map.of("v1", KEY_V1, "v2", KEY_V2), Optional.empty()));
      serviceV2.initialize();

      // A value encrypted before rotation still decrypts using the retired "v1" key...
      assertEquals("rotate-me", serviceV2.decrypt(encryptedWithV1));
      // ...while new values are encrypted with the new active key.
      assertTrue(serviceV2.encrypt("rotate-me").startsWith("v2:"));
   }

   @Test
   void testDecryptRejectsUnknownKeyId() {
      var service = new CipherService(new EncryptionConfigImpl("v1", Map.of("v1", KEY_V1), Optional.empty()));
      service.initialize();

      assertThrows(IllegalStateException.class, () -> service.decrypt("unknown-kid:AAAA"));
   }

   @Test
   void testIsEncryptedRecognizesKnownKeyIdPrefix() {
      var service = new CipherService(new EncryptionConfigImpl("v1", Map.of("v1", KEY_V1, "v2", KEY_V2), Optional.empty()));
      service.initialize();

      // A value produced by encrypt() carries a known kid and must be recognized...
      assertTrue(service.isEncrypted(service.encrypt("secret")));
      // ...as well as a value tagged with any other configured kid.
      assertTrue(service.isEncrypted("v2:whatever"));
      // ...but not a plaintext value, even one that happens to contain a colon.
      assertFalse(service.isEncrypted("plain-secret"));
      assertFalse(service.isEncrypted("host:5555"));
      assertFalse(service.isEncrypted("unknown-kid:payload"));
      assertFalse(service.isEncrypted(null));
   }

   // ---------------------------------------------------------------------
   //  Legacy AES/ECB fallback
   // ---------------------------------------------------------------------

   @Test
   void testDecryptFallsBackToLegacyEcbWhenNoKidPrefix() throws Exception {
      String legacyEncrypted = legacyEcbEncrypt(LEGACY_KEY, "pre-existing-secret");
      var service = new CipherService(new EncryptionConfigImpl("v1", Map.of("v1", KEY_V1), Optional.of(LEGACY_KEY)));
      service.initialize();

      assertEquals("pre-existing-secret", service.decrypt(legacyEncrypted));
   }

   @Test
   void testDecryptLegacyValueFailsWithoutLegacyKeyConfigured() throws Exception {
      String legacyEncrypted = legacyEcbEncrypt(LEGACY_KEY, "pre-existing-secret");
      var service = new CipherService(new EncryptionConfigImpl("v1", Map.of("v1", KEY_V1), Optional.empty()));
      service.initialize();

      assertThrows(IllegalStateException.class, () -> service.decrypt(legacyEncrypted));
   }

   /** Reproduces the pre-migration {@code AES/ECB/PKCS5Padding} encryption scheme, for fallback testing. */
   private static String legacyEcbEncrypt(String key, String data) throws Exception {
      Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
      return Base64.getEncoder().encodeToString(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
   }
}
