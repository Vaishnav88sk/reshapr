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
package io.reshapr.ctrl.config;

import io.smallrye.config.ConfigMapping;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "reshapr.authentication.idp")
public interface AuthenticationIdentityProviderConfig {
   boolean enabled();
   String url();
   String tokenUrl();
   String clientId();
   String clientSecret();

   /** Additional OAuth2 scopes to request on top of the built-in {@code openid profile email}. */
   Optional<List<String>> scopes();

   /** Access guard configuration: further restrict which IDP users can access Reshapr. */
   GuardAccess guardAccess();

   /** Default organization configuration: skip onboarding and attach the user to an existing organization. */
   DefaultOrganization defaultOrganization();

   /** Nested configuration for access guard. Both, either, or none of {@link #group()} and {@link #claim()} may be set. */
   interface GuardAccess {
      /** Only allow access if the JWT {@code groups} claim contains this group name. */
      Optional<String> group();
      /** Only allow access if the JWT token has a claim matching this {@code name=value} expression. */
      Optional<String> claim();
   }

   /** Nested configuration for default organization resolution during OIDC login. */
   interface DefaultOrganization {
      /** Name of a JWT claim whose value is the organization the user must be attached to. */
      Optional<String> claim();
      /** Group name prefix: the first JWT {@code groups} entry starting with this prefix identifies the organization (prefix stripped, optional {@code -} or {@code /} separator dropped). */
      Optional<String> groupPrefix();
      /** Fixed fallback organization name to attach any new user to. */
      Optional<String> value();
   }
}
