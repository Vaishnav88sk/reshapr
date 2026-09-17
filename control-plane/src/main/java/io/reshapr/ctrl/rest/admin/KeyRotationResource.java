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
package io.reshapr.ctrl.rest.admin;

import io.reshapr.ctrl.security.AdminAuthenticated;
import io.reshapr.ctrl.security.CipherService;
import io.reshapr.ctrl.security.KeyRotationService;
import io.reshapr.ctrl.security.KeyRotationService.RotationReport;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Admin endpoints to inspect and rotate the database encryption keyset.
 * @author laurent
 */
@RunOnVirtualThread
@Path("/api/admin/encryption")
@AdminAuthenticated
public class KeyRotationResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final CipherService cipherService;
   private final KeyRotationService keyRotationService;

   /**
    * Build a KeyRotationResource with required dependencies.
    * @param cipherService The cipher service exposing the active key id.
    * @param keyRotationService The service performing the re-encryption.
    */
   public KeyRotationResource(CipherService cipherService, KeyRotationService keyRotationService) {
      this.cipherService = cipherService;
      this.keyRotationService = keyRotationService;
   }

   /**
    * Returns the id (kid) of the key currently used for new encryption operations.
    * @return A 200 response holding the active key id.
    */
   @POST
   @Path("/status")
   public Response status() {
      logger.debug("Getting encryption status");
      return Response.ok(new EncryptionStatus(cipherService.activeKid())).build();
   }

   /**
    * Re-encrypts every sensitive column with the active key. Idempotent: values already tagged with
    * the active key id are skipped.
    * @return A 200 response holding the rotation report.
    */
   @POST
   @Path("/rotate")
   public Response rotate() {
      logger.infof("Starting encryption key rotation to active kid '%s'", cipherService.activeKid());
      RotationReport report = keyRotationService.rotateAll();
      logger.infof("Encryption key rotation completed: %s", report);
      return Response.ok(report).build();
   }

   /**
    * The current encryption status.
    * @param activeKid The id of the key used for new encryption operations.
    */
   public record EncryptionStatus(String activeKid) {
   }
}
