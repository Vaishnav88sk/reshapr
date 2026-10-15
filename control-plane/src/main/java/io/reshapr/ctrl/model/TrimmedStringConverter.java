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
package io.reshapr.ctrl.model;

import jakarta.persistence.AttributeConverter;

/**
 * JPA attribute converter truncating informational string attributes to the maximum length of a VarChar (255).
 * @author laurent
 */
public class TrimmedStringConverter implements AttributeConverter<String, String> {

   private static final int STRING_MAX_LENGTH = 255;

   @Override
   public String convertToDatabaseColumn(String attribute) {
      if (attribute != null && attribute.length() > STRING_MAX_LENGTH) {
         return attribute.substring(0, STRING_MAX_LENGTH);
      }
      return attribute;
   }

   @Override
   public String convertToEntityAttribute(String dbData) {
      return dbData;
   }
}
