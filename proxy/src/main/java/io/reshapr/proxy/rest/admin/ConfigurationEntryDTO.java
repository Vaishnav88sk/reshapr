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
package io.reshapr.proxy.rest.admin;

/**
 * Data Transfer Object representing a configuration entry.
 * @author laurent
 */
public record ConfigurationEntryDTO(
      String id,
      String name,
      String backendEndpoint,
      Long backendTimeout,
      java.util.List<String> allowedRequestHeaders,
      java.util.List<String> deniedRequestHeaders,
      java.util.List<String> allowedResponseHeaders,
      java.util.List<String> deniedResponseHeaders) {
}
