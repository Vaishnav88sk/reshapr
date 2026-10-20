/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Shared model types mirroring the control plane's public OpenAPI contract
 * (reshapr-public-openapi.yaml) for ConfigurationPlan, Exposition and Secret. `id` and
 * `organizationId` are kept optional so the same types can describe both request payloads built
 * by the CLI (before the control plane assigns them) and responses returned by the API.
 */

/** Caching configuration of a ConfigurationPlan. When absent the proxy falls back to its defaults. */
export interface CachePolicy {
  ttlMs?: number;
  cacheScope?: string;
}

/** A single header rename directive; removes the "from" header and sets its value on "to". */
export interface HeaderRename {
  from: string;
  to: string;
}

/** A set of allow/deny/rename directives applied to headers in a single direction. */
export interface HeaderRules {
  allow?: string[];
  deny?: string[];
  rename?: HeaderRename[];
}

/** Header propagation policy of a ConfigurationPlan. Only the request direction is enforced today. */
export interface HeaderPolicy {
  request?: HeaderRules;
  response?: HeaderRules;
}

/** OAuth2 resource server settings enforced on a ConfigurationPlan's MCP endpoint. */
export interface OAuth2Configuration {
  authorizationServers: string[];
  jwksUri: string;
  scopes?: string[];
  disableAudienceValidation?: boolean;
  staticAudiences?: string[];
}

/** How a ConfigurationPlan advertises its service operations to MCP clients. */
export type ToolExposureMode = 'TOOLS' | 'CODE' | 'HYBRID';

/** A ConfigurationPlan in the reShapr control plane. */
export interface ConfigurationPlan {
  id?: string;
  organizationId?: string;
  name: string;
  serviceId: string;
  description?: string;
  backendEndpoint: string;
  backendSecretId?: string;
  backendTimeout?: number;
  includedOperations?: string[];
  excludedOperations?: string[];
  includedArtifacts?: string[];
  apiKey?: string;
  initialAccessToken?: string;
  audit?: boolean;
  cachePolicy?: CachePolicy;
  headerPolicy?: HeaderPolicy;
  oauth2Configuration?: OAuth2Configuration;
  toolExposureMode?: ToolExposureMode;
}

/** Minimal Service information as nested in an Exposition or ActiveExposition. */
export interface ServiceSummary {
  id: string;
  name: string;
  version: string;
  type: string;
}

/** Minimal GatewayGroup information as nested in an Exposition. */
export interface GatewayGroupSummary {
  id: string;
  name: string;
  labels: Record<string, string>;
}

/** A Gateway currently serving an ActiveExposition. */
export interface GatewayEndpoint {
  id: string;
  name: string;
  fqdns: string[];
}

/** An Exposition in the reShapr control plane. */
export interface Exposition {
  id: string;
  organizationId: string;
  name?: string;
  createdOn: string;
  service: ServiceSummary;
  configurationPlan: ConfigurationPlan;
  gatewayGroup: GatewayGroupSummary;
}

/** An Exposition that is currently active, i.e. served by at least one Gateway. */
export interface ActiveExposition {
  id: string;
  organizationId: string;
  name?: string;
  createdOn: string;
  service: ServiceSummary;
  configurationPlan: ConfigurationPlan;
  gateways: GatewayEndpoint[];
}

/** The supported types of Secret in reShapr. */
export type SecretType = 'ARTIFACT' | 'ENDPOINT';

/** The explicit authentication method a Secret carries when used to authenticate against a backend endpoint. */
export type SecretAuthMethod = 'BASIC' | 'BEARER_TOKEN' | 'OAUTH2_AUTHORIZATION_CODE' | 'OAUTH2_CLIENT_CREDENTIALS';

/** OAuth2 client configuration, used for the Authorization Code (with elicitation) or Client Credentials flows. */
export interface OAuth2ClientConfiguration {
  clientId: string;
  authorizationEndpoint?: string;
  tokenEndpoint: string;
  clientSecret?: string;
  scopes?: string[];
}

/** A Secret in the reShapr control plane. */
export interface Secret {
  id?: string;
  organizationId?: string;
  name: string;
  description?: string;
  type?: SecretType;
  authMethod?: SecretAuthMethod;
  username?: string;
  password?: string;
  token?: string;
  tokenHeader?: string;
  certPem?: string;
  useElicitation?: boolean;
  oauth2ClientConfiguration?: OAuth2ClientConfiguration;
}
