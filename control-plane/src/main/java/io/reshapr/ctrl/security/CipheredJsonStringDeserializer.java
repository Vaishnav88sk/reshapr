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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import io.quarkus.arc.Arc;

import java.io.IOException;

/**
 * Jackson deserializer counterpart of {@link CipheredJsonStringSerializer}: decrypts a single string
 * property read back from a JSON(B) column using {@link CipherService}.
 * <p>
 * Values that are not prefixed by a known key id (for example a property still stored in clear because
 * it predates its encryption) are returned as-is, so existing data keeps working and gets converted on
 * the next write or during a key rotation.
 * @author laurent
 */
public class CipheredJsonStringDeserializer extends JsonDeserializer<String> {

   @Override
   public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
      String value = parser.getValueAsString();
      if (value == null) {
         return null;
      }
      CipherService cipherService = Arc.container().instance(CipherService.class).get();
      return cipherService.isEncrypted(value) ? cipherService.decrypt(value) : value;
   }
}
