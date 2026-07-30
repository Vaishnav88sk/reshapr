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
package io.reshapr.proxy.mcp;

import java.util.List;

/**
 * Strategy that builds the version-specific wire shape of MCP result payloads for a given negotiated
 * protocol version. This centralizes the "which record for which version?" decision so the
 * {@link McpController} handlers stay agnostic of the protocol revision.
 * <p>
 * The dialect is resolved once per request from the negotiated protocol version (see
 * {@link #forVersion(String)}) and only applies to post-{@code initialize} methods — the handshake
 * methods ({@code server/discover}, {@code initialize}) run before any version is pinned.
 * <p>
 * The cacheable {@code *List} results ({@code tools/list}, {@code resources/list}, {@code prompts/list}) share
 * a single generic {@link ListResultBuilder}; {@code tools/call} has its own {@link CallToolResultBuilder}. The
 * remaining {@code *Result} payloads should be added to this same interface as the migration progresses.
 * @author laurent
 */
public interface McpProtocolDialect {

   /**
    * Start building a {@code tools/list} result for the negotiated protocol version. Callers may set any
    * property — including the modern-only client-cache hints ({@code ttlMs}, {@code cacheScope}) — on the
    * returned builder: each dialect keeps the properties its wire shape supports and silently drops the
    * others. This lets a single call site populate the modern fields without knowing which version is
    * active.
    * @param tools The exposed tools.
    * @return A {@link ListResultBuilder} producing the version-specific record.
    */
   ListResultBuilder<McpSchema.ListToolsResult> newListToolsResult(List<McpSchema.Tool> tools);

   /**
    * Fluent builder shared by every cacheable {@code *List} result ({@code tools/list}, {@code resources/list},
    * {@code prompts/list}, …). It exposes the union of properties across all protocol versions — the common
    * {@code nextCursor} plus the modern-only client-cache hints {@code ttlMs} / {@code cacheScope}. The concrete
    * {@link McpProtocolDialect} implementation decides which of them make it into the final record (the
    * {@code Legacy} or {@code Modern} variant of {@code R}).
    * @param <R> The version-specific result type produced by {@link #build()}.
    */
   interface ListResultBuilder<R> {
      /** Pagination cursor (all versions). */
      ListResultBuilder<R> nextCursor(String nextCursor);

      /** Client-cache TTL hint — honored only by modern dialects ({@code >= 2026-07-28}). */
      ListResultBuilder<R> ttlMs(Long ttlMs);

      /** Client-cache scope hint — honored only by modern dialects ({@code >= 2026-07-28}). */
      ListResultBuilder<R> cacheScope(String cacheScope);

      /** Build the version-specific result record. */
      R build();
   }

   /**
    * Start building a {@code tools/call} result for the negotiated protocol version. As with
    * {@link #newListToolsResult(List)}, callers may set every property — including the modern-only
    * {@code structuredContent} and {@code _meta} — on the returned builder: each dialect keeps the
    * properties its wire shape supports and silently drops the others.
    * @param content The unstructured tool call content blocks.
    * @return A {@link CallToolResultBuilder} producing the version-specific record.
    */
   CallToolResultBuilder newCallToolResult(List<McpSchema.Content> content);

   /**
    * Builder for a {@code tools/call} result. Exposes every property across all protocol versions; the
    * concrete {@link McpProtocolDialect} implementation decides which ones make it into the final record
    * ({@link McpSchema.CallToolResult.Legacy} or {@link McpSchema.CallToolResult.Modern}).
    */
   interface CallToolResultBuilder {
      /** Whether the tool call ended in an error (all versions). */
      CallToolResultBuilder isError(Boolean isError);

      /** Build the version-specific result record. */
      McpSchema.CallToolResult build();
   }

   /**
    * Start building a {@code resources/list} result for the negotiated protocol version. As with
    * {@link #newListToolsResult(List)}, callers may set any property — including the modern-only client-cache
    * hints ({@code ttlMs}, {@code cacheScope}) — on the returned builder.
    * @param resources The exposed resources.
    * @return A {@link ListResultBuilder} producing the version-specific record.
    */
   ListResultBuilder<McpSchema.ListResourcesResult> newListResourcesResult(List<McpSchema.Resource> resources);

   /**
    * Start building a {@code prompts/list} result for the negotiated protocol version. As with
    * {@link #newListToolsResult(List)}, callers may set any property — including the modern-only client-cache
    * hints ({@code ttlMs}, {@code cacheScope}) — on the returned builder.
    * @param prompts The exposed prompts.
    * @return A {@link ListResultBuilder} producing the version-specific record.
    */
   ListResultBuilder<McpSchema.ListPromptsResult> newListPromptsResult(List<McpSchema.Prompt> prompts);

   /**
    * Start building a {@code resources/templates/list} result for the negotiated protocol version. As with
    * {@link #newListToolsResult(List)}, callers may set any property — including the modern-only client-cache
    * hints ({@code ttlMs}, {@code cacheScope}) — on the returned builder.
    * @param resourceTemplates The exposed resource templates.
    * @return A {@link ListResultBuilder} producing the version-specific record.
    */
   ListResultBuilder<McpSchema.ListResourceTemplatesResult> newListResourceTemplatesResult(
         List<McpSchema.ResourceTemplate> resourceTemplates);

   /**
    * Resolve the dialect matching the negotiated protocol version. Versions {@code >= 2026-07-28}
    * (see {@link McpSchema#PROTOCOL_VERSION_STATELESS}) get the modern shape; anything older — including
    * unknown/{@code null} versions — falls back to legacy.
    * @param protocolVersion The negotiated protocol version, or {@code null}.
    * @return The matching {@link McpProtocolDialect}.
    */
   static McpProtocolDialect forVersion(String protocolVersion) {
      return McpSchema.isAtLeast(protocolVersion, McpSchema.PROTOCOL_VERSION_STATELESS)
            ? new ModernProtocolDialect()
            : new LegacyProtocolDialect();
   }
}


