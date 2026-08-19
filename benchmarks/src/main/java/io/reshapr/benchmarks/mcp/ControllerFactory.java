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
package io.reshapr.benchmarks.mcp;

import io.reshapr.proxy.audit.AuditLogger;
import io.reshapr.proxy.mcp.McpController;
import io.reshapr.proxy.mcp.ToolCallExecutor;
import io.reshapr.proxy.mcp.WorkCache;
import io.reshapr.proxy.mcp.state.SessionStore;
import io.reshapr.proxy.proxy.ProxyService;
import io.reshapr.proxy.registry.GatewayRegistry;

import java.util.Map;

/**
 * Factory abstraction over the {@code McpController} implementation under benchmark.
 *
 * <p>To evaluate an alternative/optimized implementation, make it extend {@link McpController}
 * (overriding the public {@code handleHttpStreamable} entry points) and expose the same
 * 6-arg constructor, then register it in {@link #FACTORIES} with a new key and add that key to
 * the {@code controllerImpl} {@code @Param} of {@code McpControllerToolsCallBenchmark}. Both
 * implementations will then be measured side by side under the exact same conditions.</p>
 *
 * @author laurent
 */
public interface ControllerFactory {

   McpController create(GatewayRegistry gatewayRegistry, SessionStore sessionStore, WorkCache workCache,
                        ProxyService proxyService, ToolCallExecutor toolCallExecutor, AuditLogger auditLogger);

   /** Registry of controller implementations under benchmark, keyed by the {@code controllerImpl} param. */
   Map<String, ControllerFactory> FACTORIES = Map.of(
         "current", McpController::new,
         "optimized", OptimizedMcpController::new
   );

   static ControllerFactory forName(String name) {
      ControllerFactory factory = FACTORIES.get(name);
      if (factory == null) {
         throw new IllegalArgumentException("Unknown controller implementation: " + name);
      }
      return factory;
   }
}

