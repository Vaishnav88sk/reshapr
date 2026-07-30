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
 * {@link McpProtocolDialect} for protocol versions {@code >= 2026-07-28}
 * ({@link McpSchema#PROTOCOL_VERSION_STATELESS}). Produces the modern result records carrying the
 * mandatory {@code resultType} discriminator and the client-cache hints {@code ttlMs} / {@code cacheScope}.
 * @author laurent
 */
final class ModernProtocolDialect implements McpProtocolDialect {

   /** Discriminator value carried by a complete (non-paginated / final page) list result. */
   static final String RESULT_TYPE_COMPLETE = "complete";

   /**
    * Assembles a modern cacheable {@code *List} record from the union of properties collected by
    * {@link ModernListBuilder}. Implementations bake in the {@code resultType} discriminator.
    * @param <I> The items collection type (e.g. {@code List<McpSchema.Tool>}).
    * @param <R> The version-specific result type.
    */
   @FunctionalInterface
   private interface ModernListAssembler<I, R> {
      R assemble(I items, String nextCursor, Long ttlMs, String cacheScope);
   }

   /**
    * Generic modern builder for every cacheable {@code *List} result. Holds the shared {@code items},
    * {@code nextCursor} and the client-cache hints {@code ttlMs} / {@code cacheScope}, then delegates record
    * creation to the supplied {@code assembler}.
    * @param <I> The items collection type (e.g. {@code List<McpSchema.Tool>}).
    * @param <R> The version-specific result type produced by {@link #build()}.
    */
   private static final class ModernListBuilder<I, R> implements ListResultBuilder<R> {
      private final I items;
      private final ModernListAssembler<I, R> assembler;
      private String nextCursor;
      private Long ttlMs;
      private String cacheScope;

      private ModernListBuilder(I items, ModernListAssembler<I, R> assembler) {
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
         this.ttlMs = ttlMs;
         return this;
      }

      @Override
      public ListResultBuilder<R> cacheScope(String cacheScope) {
         this.cacheScope = cacheScope;
         return this;
      }

      @Override
      public R build() {
         return assembler.assemble(items, nextCursor, ttlMs, cacheScope);
      }
   }

   @Override
   public ListResultBuilder<McpSchema.ListToolsResult> newListToolsResult(List<McpSchema.Tool> tools) {
      return new ModernListBuilder<>(tools,
            (items, nextCursor, ttlMs, cacheScope) ->
                  new McpSchema.ListToolsResult.Modern(RESULT_TYPE_COMPLETE, items, nextCursor, ttlMs, cacheScope));
   }

   @Override
   public ListResultBuilder<McpSchema.ListResourcesResult> newListResourcesResult(List<McpSchema.Resource> resources) {
      return new ModernListBuilder<>(resources,
            (items, nextCursor, ttlMs, cacheScope) ->
                  new McpSchema.ListResourcesResult.Modern(RESULT_TYPE_COMPLETE, items, nextCursor, ttlMs, cacheScope));
   }

   @Override
   public ListResultBuilder<McpSchema.ListPromptsResult> newListPromptsResult(List<McpSchema.Prompt> prompts) {
      return new ModernListBuilder<>(prompts,
            (items, nextCursor, ttlMs, cacheScope) ->
                  new McpSchema.ListPromptsResult.Modern(RESULT_TYPE_COMPLETE, items, nextCursor, ttlMs, cacheScope));
   }

   @Override
   public ListResultBuilder<McpSchema.ListResourceTemplatesResult> newListResourceTemplatesResult(
         List<McpSchema.ResourceTemplate> resourceTemplates) {
      return new ModernListBuilder<>(resourceTemplates,
            (items, nextCursor, ttlMs, cacheScope) ->
                  new McpSchema.ListResourceTemplatesResult.Modern(RESULT_TYPE_COMPLETE, items, nextCursor, ttlMs, cacheScope));
   }

   @Override
   public CallToolResultBuilder newCallToolResult(List<McpSchema.Content> content) {
      return new CallToolBuilder(content);
   }

   /** Builds a {@link McpSchema.CallToolResult.Modern}, honoring {@code structuredContent} and {@code _meta}. */
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
         return new McpSchema.CallToolResult.Modern(RESULT_TYPE_COMPLETE, content, isError);
      }
   }
}


