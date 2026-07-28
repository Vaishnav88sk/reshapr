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
package io.reshapr.proxy.context;

import io.reshapr.proxy.registry.SecretEntry;

import org.infinispan.protostream.annotations.ProtoField;

/**
 * A serializable pair binding an elicited secret value to the {@link SecretEntry} it was collected for.
 * This replaces the former {@code Map<SecretEntry, String>} of {@link SessionInfo} with a Protostream
 * friendly representation (Protostream cannot marshal a map keyed by a complex message type).
 * @param secret The secret entry the value was elicited for (kept as key to preserve equality-based lookup).
 * @param value The elicited secret value (token) collected for this secret.
 * @author laurent
 */
public record SecretValueEntry(
      @ProtoField(1) SecretEntry secret,
      @ProtoField(2) String value) {
}

