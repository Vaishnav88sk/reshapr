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
import java.util.function.BiFunction;

/**
 * {@link McpProtocolDialect} for protocol versions strictly before {@link McpSchema#PROTOCOL_VERSION_STATELESS}
 * ({@code < 2026-07-28}). Produces the "nude" result records without the modern {@code resultType} /
 * {@code ttlMs} / {@code cacheScope} fields.
 * @author laurent
 */
final class LegacyProtocolDialect implements McpProtocolDialect {

   /**
    * Generic legacy builder for every cacheable {@code *List} result. Holds the shared {@code items} and
    * {@code nextCursor}, silently drops the modern-only client-cache hints, and delegates record creation to
    * the supplied {@code assembler}.
    * @param <I> The items collection type (e.g. {@code List<McpSchema.Tool>}).
    * @param <R> The version-specific result type produced by {@link #build()}.
    */
   private static final class LegacyListBuilder<I, R> implements ListResultBuilder<R> {
      private final I items;
      private final BiFunction<I, String, R> assembler;
      private String nextCursor;

      private LegacyListBuilder(I items, BiFunction<I, String, R> assembler) {
         this.items = items;
         this.assembler = assembler;
      }

      @Override
      public ListResultBuilder<R> nextCursor(String nextCursor) {
         this.nextCursor = nextCursor;
         return this;
      }

      @Override
      public ListResultBuilder<R> ttlMs(Long ttlMs) {
         // Not part of the legacy wire shape — intentionally ignored.
         return this;
      }

      @Override
      public ListResultBuilder<R> cacheScope(String cacheScope) {
         // Not part of the legacy wire shape — intentionally ignored.
         return this;
      }

      @Override
      public R build() {
         return assembler.apply(items, nextCursor);
      }
   }

   @Override
   public ListResultBuilder<McpSchema.ListToolsResult> newListToolsResult(List<McpSchema.Tool> tools) {
      return new LegacyListBuilder<>(tools, McpSchema.ListToolsResult.Legacy::new);
   }

   @Override
   public ListResultBuilder<McpSchema.ListResourcesResult> newListResourcesResult(List<McpSchema.Resource> resources) {
      return new LegacyListBuilder<>(resources, McpSchema.ListResourcesResult.Legacy::new);
   }

   @Override
   public ListResultBuilder<McpSchema.ListPromptsResult> newListPromptsResult(List<McpSchema.Prompt> prompts) {
      return new LegacyListBuilder<>(prompts, McpSchema.ListPromptsResult.Legacy::new);
   }

   @Override
   public ListResultBuilder<McpSchema.ListResourceTemplatesResult> newListResourceTemplatesResult(
         List<McpSchema.ResourceTemplate> resourceTemplates) {
      return new LegacyListBuilder<>(resourceTemplates, McpSchema.ListResourceTemplatesResult.Legacy::new);
   }

   @Override
   public CallToolResultBuilder newCallToolResult(List<McpSchema.Content> content) {
      return new CallToolBuilder(content);
   }

   /** Builds a {@link McpSchema.CallToolResult.Legacy}; modern-only fields are accepted but dropped. */
   private static final class CallToolBuilder implements CallToolResultBuilder {
      private final List<McpSchema.Content> content;
      private Boolean isError;

      private CallToolBuilder(List<McpSchema.Content> content) {
         this.content = content;
      }

      @Override
      public CallToolResultBuilder isError(Boolean isError) {
         this.isError = isError;
         return this;
      }

      @Override
      public McpSchema.CallToolResult build() {
         return new McpSchema.CallToolResult.Legacy(content, isError);
      }
   }
}


