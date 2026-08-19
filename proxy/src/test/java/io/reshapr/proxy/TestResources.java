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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Small helper to load classpath test resources (e.g. the base64-encoded Protobuf
 * FileDescriptorSet shared by the gRPC test cases).
 *
 * @author laurent
 */
public final class TestResources {

   private TestResources() {
   }

   /** Read a classpath resource as a UTF-8 string (whitespace trimmed). */
   public static String readString(String resourcePath) {
      try (InputStream in = TestResources.class.getClassLoader().getResourceAsStream(resourcePath)) {
         if (in == null) {
            throw new IllegalArgumentException("Test resource not found on classpath: " + resourcePath);
         }
         return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      } catch (IOException e) {
         throw new UncheckedIOException("Unable to read test resource: " + resourcePath, e);
      }
   }
}
