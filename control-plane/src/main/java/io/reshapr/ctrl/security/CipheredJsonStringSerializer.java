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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.quarkus.arc.Arc;

import java.io.IOException;

/**
 * Jackson serializer that encrypts a single string property with the active key before it is written
 * to a JSON(B) column, using {@link CipherService}. This provides field-level encryption at rest for
 * a sensitive property nested inside an otherwise plain JSON document (for example the OAuth2
 * {@code clientSecret}), without encrypting the whole column.
 * <p>
 * Because REST payloads are mapped through dedicated DTOs rather than the persisted entity types,
 * annotating an entity's JSON record component with this serializer only affects the persistence
 * (JSONB) representation, not the API representation.
 * @author laurent
 */
public class CipheredJsonStringSerializer extends JsonSerializer<String> {

   @Override
   public void serialize(String value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
      if (value == null) {
         generator.writeNull();
         return;
      }
      CipherService cipherService = Arc.container().instance(CipherService.class).get();
      // Guard against double-encryption if the value already carries a known key id prefix.
      generator.writeString(cipherService.isEncrypted(value) ? value : cipherService.encrypt(value));
   }
}
