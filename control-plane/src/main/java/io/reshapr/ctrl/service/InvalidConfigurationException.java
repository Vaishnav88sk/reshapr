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
package io.reshapr.ctrl.service;

/**
 * Exception thrown when a submitted configuration violates a business/consistency rule (as opposed to a
 * missing dependency). It maps to an HTTP 400 (Bad Request) at the REST layer. A typical case is an
 * exposition that relies on OAuth2 elicitation but is not protected by an inbound OAuth2 configuration:
 * in stateless MCP mode ({@code 2026-07-28}) the elicited secret must be bound to a stable, authenticated
 * identity, which requires the exposition to be OAuth-protected.
 * @author laurent
 */
public class InvalidConfigurationException extends Exception {

   public InvalidConfigurationException(String message) {
      super(message);
   }
}

