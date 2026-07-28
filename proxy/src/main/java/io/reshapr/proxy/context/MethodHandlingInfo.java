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

import jakarta.annotation.Nullable;

/**
 * Allow transferring method execution information on incoming MCP request.
 * @param remoteAddress The remote address of the client making the request.
 * @param mcpSessionInfo THe information on current MCP session if any.
 * @param userId The authenticated user ID (JWT subject), if available.
 * @param issuer The authenticated token issuer (JWT {@code iss} claim), if available.
 * @param organizationId The organization id owning the exposition being called, if available.
 * @author laurent
 */
public record MethodHandlingInfo(
      String remoteAddress,
      @Nullable SessionInfo mcpSessionInfo,
      @Nullable String userId,
      @Nullable String issuer,
      @Nullable String organizationId) {
}
