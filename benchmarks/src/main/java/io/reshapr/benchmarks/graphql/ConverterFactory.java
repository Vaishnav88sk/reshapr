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
package io.reshapr.benchmarks.graphql;

import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.converters.GraphQLMcpToolConverter;
import io.reshapr.proxy.mcp.converters.McpToolConverter;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.ExpositionEntry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Factory abstraction over the GraphQL converter implementation under benchmark.
 *
 * <p>To evaluate an alternative/optimized implementation, register it in {@link #FACTORIES}
 * with a new key and add that key to the {@code converterImpl} {@code @Param} of
 * {@code GraphQLGetCallResponseBenchmark}. Both implementations will then be measured side by side.</p>
 *
 * @author laurent
 */
public interface ConverterFactory {

   McpToolConverter create(ExpositionEntry exposition, WorkCache workCache, ObjectMapper mapper, ProxyService proxyService);

   /** Registry of converter implementations under benchmark, keyed by the {@code converterImpl} param. */
   Map<String, ConverterFactory> FACTORIES = Map.of(
         "current", GraphQLMcpToolConverter::new,
         "optimized", OptimizedGraphQLMcpToolConverter::new
   );

   static ConverterFactory forName(String name) {
      ConverterFactory factory = FACTORIES.get(name);
      if (factory == null) {
         throw new IllegalArgumentException("Unknown converter implementation: " + name);
      }
      return factory;
   }
}

