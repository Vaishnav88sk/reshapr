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
import { afterAll, describe, expect, test } from 'vitest';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { getTempHome, login, runCli, runCliExpectSuccess, runCliJson } from '../helpers/cli-runner.js';
import { deleteServiceIfPresent, deleteServicesByNameVersion } from '../helpers/cleanup.js';
import { callMcpTool, initializeMcp, mcpUrl, waitForMcpTools } from '../helpers/mcp-client.js';

const OPEN_METEO_SPEC = path.resolve(import.meta.dirname, '../../../dev/open-meteo-openapi.yml');
const OPEN_METEO_SERVICE_NAME = 'Open-Meteo APIs';
const OPEN_METEO_SERVICE_VERSION = '1.0';
const OPEN_METEO_BACKEND = 'https://api.open-meteo.com';
const OPEN_METEO_EXPECTED_TOOLS = 1;
const OPEN_METEO_TOOL_NAME = 'get_v1_forecast';

interface Service {
  id: string;
  name: string;
  version: string;
  type: string;
  operations?: unknown[];
}

interface ImportResult {
  service: Service;
}

interface Exposition {
  service?: Service;
  configurationPlan?: {
    backendEndpoint?: string;
  };
}

describe.sequential('Scenario: Import and expose an OpenAPI service via MCP', () => {
  let serviceId: string | undefined;
  let service: Service | undefined;

  function requireServiceId(): string {
    if (!serviceId) {
      throw new Error('Open-Meteo service was not imported');
    }
    return serviceId;
  }

  function requireService(): Service {
    if (!service) {
      throw new Error('Open-Meteo service metadata was not loaded');
    }
    return service;
  }

  afterAll(async () => {
    await deleteServiceIfPresent(serviceId);
  });

  test('logs in and removes stale Open-Meteo service', async () => {
    await login();
    await deleteServicesByNameVersion(OPEN_METEO_SERVICE_NAME, OPEN_METEO_SERVICE_VERSION);
  });

  test('imports Open-Meteo with backend endpoint', async () => {
    const imported = await runCliJson<ImportResult>(
      'import',
      '-f', OPEN_METEO_SPEC,
      '--be', OPEN_METEO_BACKEND,
    );

    const importedServiceId = imported.service.id;
    serviceId = importedServiceId;
    expect(imported.service.name).toBe(OPEN_METEO_SERVICE_NAME);
    expect(importedServiceId).toBeTruthy();
  });

  test('validates imported service metadata and operations', async () => {
    const importedServiceId = requireServiceId();
    service = await runCliJson<Service>('service', 'get', importedServiceId);

    expect(service.id).toBe(importedServiceId);
    expect(service.name).toBe(OPEN_METEO_SERVICE_NAME);
    expect(service.version).toBe(OPEN_METEO_SERVICE_VERSION);
    expect(service.type).toBe('REST');
    expect(service.operations ?? []).toHaveLength(OPEN_METEO_EXPECTED_TOOLS);
  });

  test('validates active exposition backend configuration', async () => {
    const importedServiceId = requireServiceId();
    const expositions = await runCliJson<Exposition[]>('expo', 'list');
    const exposition = expositions.find(item => item.service?.id === importedServiceId);

    expect(exposition).toBeDefined();
    expect(exposition?.configurationPlan?.backendEndpoint).toBe(OPEN_METEO_BACKEND);
  });

  test('initializes MCP endpoint through gateway', async () => {
    const endpoint = mcpUrl(OPEN_METEO_SERVICE_NAME, OPEN_METEO_SERVICE_VERSION);
    const initialized = await initializeMcp(endpoint);

    expect(initialized.body.result?.serverInfo).toBeDefined();
  });

  test('lists Open-Meteo MCP tool', async () => {
    const importedService = requireService();
    const endpoint = mcpUrl(OPEN_METEO_SERVICE_NAME, OPEN_METEO_SERVICE_VERSION);
    const tools = await waitForMcpTools(endpoint, {
      exact: OPEN_METEO_EXPECTED_TOOLS,
      include: [OPEN_METEO_TOOL_NAME],
    });

    expect(tools).toHaveLength(importedService.operations?.length ?? 0);
  });

  test('calls Open-Meteo forecast tool through MCP', async () => {
    const endpoint = mcpUrl(OPEN_METEO_SERVICE_NAME, OPEN_METEO_SERVICE_VERSION);
    const toolCall = await callMcpTool(endpoint, OPEN_METEO_TOOL_NAME, {
      latitude: 48.8566,
      longitude: 2.3522,
      current_weather: true,
      timezone: 'Europe/Paris',
    });

    expect(toolCall.error).toBeUndefined();
    expect(toolCall.result?.isError).not.toBe(true);
    expect(toolCall.result?.content?.[0]?.text).toEqual(expect.any(String));

    const backendPayload = JSON.parse(toolCall.result.content[0].text);
    expect(backendPayload.latitude).toEqual(expect.any(Number));
    expect(backendPayload.longitude).toEqual(expect.any(Number));
  });

  test('deletes imported Open-Meteo service before logout', async () => {
    const importedServiceId = requireServiceId();

    await runCliExpectSuccess('service', 'delete', importedServiceId, '-f');
    serviceId = undefined;
    service = undefined;

    const deletedService = await runCli('service', 'get', importedServiceId, '-o', 'json');
    expect(deletedService.exitCode).not.toBe(0);
  });

  test('logs out and removes CLI config', async () => {
    const configPath = path.join(getTempHome(), '.reshapr', 'config');

    await expect(fs.promises.access(configPath)).resolves.toBeUndefined();

    const result = await runCliExpectSuccess('logout');
    expect(result.stdout + result.stderr).toContain('logged out successfully');

    const exists = await fs.promises.access(configPath).then(() => true).catch(() => false);
    expect(exists).toBe(false);
  });
});
