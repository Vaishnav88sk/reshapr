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
export function formatEndpoint(fqdn: string, organizationId: string, serviceName: string, serviceVersion: string): string {
  return `${fqdn}/mcp/${organizationId}/${encodeUrl(serviceName)}/${encodeUrl(serviceVersion)}`;
}

/**
 * Deterministic MCP endpoint addressing an exposition by its id (always available).
 */
export function formatExpositionIdEndpoint(fqdn: string, expositionId: string): string {
  return `${fqdn}/mcp/${expositionId}`;
}

/**
 * Deterministic MCP endpoint addressing an exposition by its organization-unique name. Returns
 * `undefined` when the exposition has no name (the by-name endpoint is then not exposed).
 */
export function formatExpositionEndpoint(fqdn: string, organizationId: string, expositionName?: string | null): string | undefined {
  if (!expositionName || expositionName.trim() === '') {
    return undefined;
  }
  return `${fqdn}/mcp/${organizationId}/${encodeUrl(expositionName)}`;
}

/**
 * Build the list of deterministic MCP endpoints for an exposition on a given fqdn: the by-id endpoint
 * (always present) plus the by-name endpoint when the exposition is named.
 */
export function formatExpositionEndpoints(fqdn: string, exposition: { id: string; organizationId: string; name?: string | null }): string[] {
  const urls = [formatExpositionIdEndpoint(fqdn, exposition.id)];
  const byName = formatExpositionEndpoint(fqdn, exposition.organizationId, exposition.name ?? undefined);
  if (byName) {
    urls.push(byName);
  }
  return urls;
}

/**
 * Turn an arbitrary label into a URL/DNS-friendly slug (lower-case, non-alphanumeric collapsed to `-`).
 */
export function slugify(value: string): string {
  return value
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '');
}

/**
 * Default exposition name proposed at creation time: a slug of `service-version-plan` (decision #4).
 */
export function buildExpositionSlug(serviceName: string, serviceVersion: string, planName: string): string {
  return slugify(`${serviceName}-${serviceVersion}-${planName}`);
}

function encodeUrl(url: string): string {
  return url.replace(/\s/g, '+');
}
