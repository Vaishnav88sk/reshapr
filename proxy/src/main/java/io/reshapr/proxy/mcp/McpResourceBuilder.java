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

import io.reshapr.proxy.registry.ConfigurationEntry;

import jakarta.annotation.Nullable;

import java.util.List;

/**
 * Interface defining a builder for MCP Resources.
 * @author laurent
 */
public interface McpResourceBuilder {

   /**
    * List all available resources.
    * @return A list of MCP Resource objects
    */
   List<McpSchema.Resource> listResources();

   /**
    * List all available resource templates.
    * @return A list of MCP Resource Template objects
    */
   List<McpSchema.ResourceTemplate> listResourceTemplates();

   /**
    * Read a specific resource by its name.
    * @param request The read resource request containing the resource name
    * @param configuration The MCP endpoint configuration entry if we need to access backend.
    * @return The MCP Resource Contents objects, or {@code null} if no resource matches the requested URI
    */
   @Nullable
   List<McpSchema.ResourceContents> readResource(McpSchema.ReadResourceRequest request, ConfigurationEntry configuration);
}
