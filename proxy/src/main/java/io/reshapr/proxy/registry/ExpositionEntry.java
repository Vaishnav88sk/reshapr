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

import jakarta.annotation.Nullable;

import java.util.List;

/**
 * Immutable, ready-to-serve aggregate of everything the gateway needs to handle an MCP request for a
 * single exposition: the backing {@link ServiceEntry}, its {@link ConfigurationEntry}, the (already
 * resolved) main {@link ArtifactEntry} and the list of attached artifacts (already filtered by the
 * exposition's {@code includedArtifacts} selection at fetch time).
 *
 * <p>This record is the write-time pre-computed unit stored by the {@code GatewayRegistry}. Multiple
 * registry indexes point to the very same instance so that a single dereference brings the whole request
 * context (no per-request joins, no per-request filtering). Instances are immutable (defensive copy of the
 * attached artifacts) so that concurrent readers never need to synchronize.</p>
 *
 * @param id The exposition id (a globally unique TSID).
 * @param name The optional, organization-unique exposition name (may be {@code null} or blank when unnamed).
 * @param service The backing service entry.
 * @param configuration The configuration plan entry to apply.
 * @param mainArtifact The main artifact (may be {@code null} for service types that don't define one).
 * @param attachedArtifacts The attached artifacts already filtered for this exposition (never {@code null}).
 * @author laurent
 */
public record ExpositionEntry(
      String id,
      @Nullable String name,
      ServiceEntry service,
      ConfigurationEntry configuration,
      @Nullable ArtifactEntry mainArtifact,
      List<ArtifactEntry> attachedArtifacts) {

   /** Compact constructor enforcing an immutable, non-null attached artifacts list. */
   public ExpositionEntry {
      attachedArtifacts = (attachedArtifacts == null) ? List.of() : List.copyOf(attachedArtifacts);
   }
}

