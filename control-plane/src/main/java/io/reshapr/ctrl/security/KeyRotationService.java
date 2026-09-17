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

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Re-encrypts sensitive columns with the current active key. Run this after a key rotation
 * (a new active key introduced via {@code reshapr.encryption.active-key-id}) or after enabling the
 * AES-GCM scheme for the first time, to convert values still encrypted with an older or legacy key.
 * <p>
 * The work is done at the raw column level with native SQL: only rows whose ciphered column is NOT
 * already tagged with the active {@code kid} are read, decrypted, re-encrypted with the active key
 * and written back. This deliberately bypasses the JPA {@link CipheredAttributeConverter} (which
 * would hide the raw ciphertext and defeat Hibernate dirty checking) and the tenant filter, so that
 * every tenant's rows are rotated. The operation is idempotent and can be safely re-run.
 * @author laurent
 */
@ApplicationScoped
public class KeyRotationService {

   /** Get a JBoss logging logger. */
   private static final Logger logger = Logger.getLogger(KeyRotationService.class);

   private static final int BATCH_SIZE = 200;

   /** The ciphered columns to rotate, as (table, column) pairs. */
   private static final List<CipheredColumn> CIPHERED_COLUMNS = List.of(
         new CipheredColumn("secrets", "password"),
         new CipheredColumn("secrets", "token"),
         new CipheredColumn("configuration_plans", "api_key"));

   /**
    * Ciphered properties nested inside a JSON(B) column, as (table, column, jsonKey) triples. Only the
    * targeted property is encrypted; the rest of the JSON document stays in clear.
    */
   private static final List<CipheredJsonColumn> CIPHERED_JSON_COLUMNS = List.of(
         new CipheredJsonColumn("secrets", "oauth2_client_configuration", "clientSecret"));

   private final CipherService cipherService;
   private final EntityManager entityManager;

   /**
    * Build a KeyRotationService with required dependencies.
    * @param cipherService The cipher service used to decrypt/re-encrypt values.
    * @param entityManager The entity manager used to run native queries.
    */
   public KeyRotationService(CipherService cipherService, EntityManager entityManager) {
      this.cipherService = cipherService;
      this.entityManager = entityManager;
   }

   /**
    * Re-encrypts every sensitive column with the active key.
    * @return A report of how many values were re-encrypted per table.
    */
   public RotationReport rotateAll() {
      RotationReport report = new RotationReport();
      for (CipheredColumn column : CIPHERED_COLUMNS) {
         long rotated = rotateColumn(column);
         report.add(column.table(), rotated);
      }
      for (CipheredJsonColumn column : CIPHERED_JSON_COLUMNS) {
         long rotated = rotateJsonColumn(column);
         report.add(column.table(), rotated);
      }
      logger.infof("Key rotation to active kid '%s' completed: %s", cipherService.activeKid(), report);
      return report;
   }

   private long rotateColumn(CipheredColumn column) {
      String activePrefix = cipherService.activeKid() + ":";
      long total = 0;
      while (true) {
         long rotated = rotateBatch(column, activePrefix);
         total += rotated;
         if (rotated < BATCH_SIZE) {
            break;
         }
      }
      if (total > 0) {
         logger.debugf("Re-encrypted %d value(s) in %s.%s", total, column.table(), column.column());
      }
      return total;
   }

   private long rotateBatch(CipheredColumn column, String activePrefix) {
      return QuarkusTransaction.requiringNew().call(() -> {
         @SuppressWarnings("unchecked")
         List<Object[]> rows = entityManager.createNativeQuery(
                     "SELECT id, " + column.column() + " FROM " + column.table()
                           + " WHERE " + column.column() + " IS NOT NULL"
                           + " AND " + column.column() + " NOT LIKE ?1"
                           + " LIMIT ?2")
               .setParameter(1, activePrefix + "%")
               .setParameter(2, BATCH_SIZE)
               .getResultList();

         for (Object[] row : rows) {
            String id = (String) row[0];
            String current = (String) row[1];
            String reEncrypted = cipherService.encrypt(cipherService.decrypt(current));
            entityManager.createNativeQuery(
                        "UPDATE " + column.table() + " SET " + column.column() + " = ?1 WHERE id = ?2")
                  .setParameter(1, reEncrypted)
                  .setParameter(2, id)
                  .executeUpdate();
         }
         return (long) rows.size();
      });
   }

   private record CipheredColumn(String table, String column) {
   }

   private long rotateJsonColumn(CipheredJsonColumn column) {
      String activePrefix = cipherService.activeKid() + ":";
      long total = 0;
      while (true) {
         long rotated = rotateJsonBatch(column, activePrefix);
         total += rotated;
         if (rotated < BATCH_SIZE) {
            break;
         }
      }
      if (total > 0) {
         logger.debugf("Re-encrypted %d value(s) in %s.%s->%s", total, column.table(), column.column(), column.jsonKey());
      }
      return total;
   }

   private long rotateJsonBatch(CipheredJsonColumn column, String activePrefix) {
      // The json key and column names come from a controlled constant list, never from user input,
      // so they are safe to inline in the SQL. Only values are bound as parameters. Note: the jsonb
      // "?" existence operator cannot be used in a native query (it clashes with the JDBC placeholder),
      // hence jsonb_exists().
      String selectSql = "SELECT id, " + column.column() + " ->> '" + column.jsonKey() + "'"
            + " FROM " + column.table()
            + " WHERE jsonb_exists(" + column.column() + ", '" + column.jsonKey() + "')"
            + " AND " + column.column() + " ->> '" + column.jsonKey() + "' NOT LIKE ?1"
            + " LIMIT ?2";
      String updateSql = "UPDATE " + column.table()
            + " SET " + column.column() + " = jsonb_set(" + column.column()
            + ", '{" + column.jsonKey() + "}', to_jsonb(?1::text))"
            + " WHERE id = ?2";

      return QuarkusTransaction.requiringNew().call(() -> {
         @SuppressWarnings("unchecked")
         List<Object[]> rows = entityManager.createNativeQuery(selectSql)
               .setParameter(1, activePrefix + "%")
               .setParameter(2, BATCH_SIZE)
               .getResultList();

         for (Object[] row : rows) {
            String id = (String) row[0];
            String current = (String) row[1];
            // Existing rows may still hold the property in clear (it predates encryption): only
            // decrypt values that already carry a known key id, otherwise encrypt as-is.
            String reEncrypted = cipherService.isEncrypted(current)
                  ? cipherService.encrypt(cipherService.decrypt(current))
                  : cipherService.encrypt(current);
            entityManager.createNativeQuery(updateSql)
                  .setParameter(1, reEncrypted)
                  .setParameter(2, id)
                  .executeUpdate();
         }
         return (long) rows.size();
      });
   }

   private record CipheredJsonColumn(String table, String column, String jsonKey) {
   }

   /**
    * Report of a key rotation run: number of values re-encrypted per table.
    */
   public static final class RotationReport {

      private long secretsRotated;
      private long configurationPlansRotated;

      private void add(String table, long count) {
         switch (table) {
            case "secrets" -> secretsRotated += count;
            case "configuration_plans" -> configurationPlansRotated += count;
            default -> throw new IllegalArgumentException("Unknown ciphered table: " + table);
         }
      }

      public long getSecretsRotated() {
         return secretsRotated;
      }

      public long getConfigurationPlansRotated() {
         return configurationPlansRotated;
      }

      @Override
      public String toString() {
         return "secrets=" + secretsRotated + ", configurationPlans=" + configurationPlansRotated;
      }
   }
}
