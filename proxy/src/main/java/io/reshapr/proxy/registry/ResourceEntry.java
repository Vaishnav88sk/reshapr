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

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a resource registry entry.
 * author @laurent
 */
public record ResourceEntry(
      String type,
      String resourceUri,
      String[] visibility) {

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ResourceEntry other)) return false;
      return Objects.equals(type, other.type)
            && Objects.equals(resourceUri, other.resourceUri)
            && Arrays.equals(visibility, other.visibility);
   }

   @Override
   public int hashCode() {
      int result = Objects.hash(type, resourceUri);
      result = 31 * result + Arrays.hashCode(visibility);
      return result;
   }

   @Override
   public String toString() {
      return "ResourceEntry[type=" + type
            + ", resourceUri=" + resourceUri
            + ", visibility=" + Arrays.toString(visibility) + "]";
   }
}
