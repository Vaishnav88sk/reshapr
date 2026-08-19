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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAdminCommand } from '../src/commands/admin/index.js';
import {
  AdminCommandError,
  parseStringArray,
  resolveAdminConnection
} from '../src/commands/admin/utils.js';
import { parseQuotas } from '../src/commands/admin/quota.js';
import { ConfigUtil } from '../src/utils/config.js';
import { Context } from '../src/utils/context.js';
import { Logger } from '../src/utils/logger.js';

describe('admin commands', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    ConfigUtil.config = {
      username: 'user',
      server: 'https://saved.example',
      token: 'user-token'
    };
    Context.clear();
    delete process.env.RESHAPR_ADMIN_API_KEY;
    process.exitCode = undefined;
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    delete process.env.RESHAPR_ADMIN_API_KEY;
    process.exitCode = undefined;
  });

  it('uses admin flags ahead of environment and saved configuration', async () => {
    process.env.RESHAPR_ADMIN_API_KEY = 'environment-key';
    fetchMock.mockResolvedValue(jsonResponse({
      username: 'jdoe',
      email: 'jdoe@example.com'
    }, 201));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'flag-key',
      '--server', 'https://flag.example/base/',
      'user', 'create', 'jdoe',
      '--email', 'jdoe@example.com',
      '--password', 'secret',
      '--firstname', 'John',
      '--lastname', 'Doe'
    ], { from: 'user' });

    expect(fetchMock).toHaveBeenCalledWith(
      'https://flag.example/base/api/admin/users',
      expect.objectContaining({
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-reshapr-api-key': 'flag-key'
        },
        body: JSON.stringify({
          username: 'jdoe',
          email: 'jdoe@example.com',
          password: 'secret',
          firstname: 'John',
          lastname: 'Doe'
        })
      })
    );
    expect(Context.get('user')).toEqual({
      username: 'jdoe',
      email: 'jdoe@example.com'
    });
  });

  it('creates an owned organization using an encoded owner path', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ name: 'acme' }, 201));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'organization', 'create', 'acme',
      '--owner', 'owner/name',
      '--description', 'Acme organization'
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/users/owner%2Fname/organization',
      'POST',
      {
        name: 'acme',
        description: 'Acme organization'
      }
    );
  });

  it('assigns validated quotas using the organization endpoint', async () => {
    const quotas = [{ metric: 'gateway.count', enabled: true, limit: 3 }];
    fetchMock.mockResolvedValue(jsonResponse(quotas));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'quota', 'assign', 'acme/dev',
      '--quotas', JSON.stringify(quotas)
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/quotas/organization/acme%2Fdev',
      'POST',
      quotas
    );
    expect(Context.get('quotas')).toEqual(quotas);
  });

  it('replaces memberships with the supplied organization list', async () => {
    fetchMock.mockResolvedValue(jsonResponse(['acme', 'shared']));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'membership', 'set', 'jdoe',
      '--organizations', '["acme","shared"]'
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/users/jdoe/memberships',
      'PUT',
      ['acme', 'shared']
    );
  });

  it('creates a service account with numeric validity days', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 'sa-id', name: 'operator' }, 201));

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'service-account', 'create', 'operator',
      '--k8s-subject', 'reshapr-system:operator',
      '--allowed-organizations', '["*"]',
      '--validity-days', '90'
    ], { from: 'user' });

    expectRequest(
      'https://saved.example/api/admin/serviceAccounts',
      'POST',
      {
        name: 'operator',
        k8sSubject: 'reshapr-system:operator',
        allowedOrganizations: ['*'],
        validityDays: 90
      }
    );
  });

  it('reports API errors without treating them as successful output', async () => {
    fetchMock.mockResolvedValue(new Response('User already exists', {
      status: 409,
      statusText: 'Conflict'
    }));
    const errorSpy = vi.spyOn(Logger, 'error').mockImplementation(() => {});

    await createAdminCommand().parseAsync([
      '--admin-api-key', 'key',
      'user', 'create', 'jdoe',
      '--email', 'jdoe@example.com'
    ], { from: 'user' });

    expect(process.exitCode).toBe(1);
    expect(errorSpy).toHaveBeenCalledWith(
      'Admin request failed (409 Conflict): User already exists'
    );
    expect(Context.isEmpty()).toBe(true);
  });

  function expectRequest(url: string, method: string, body: unknown): void {
    expect(fetchMock).toHaveBeenCalledWith(
      url,
      expect.objectContaining({
        method,
        body: JSON.stringify(body)
      })
    );
  }
});

describe('admin input validation', () => {
  afterEach(() => {
    delete process.env.RESHAPR_ADMIN_API_KEY;
  });

  it('requires an admin API key', () => {
    ConfigUtil.config = {
      username: 'user',
      server: 'https://saved.example',
      token: 'user-token'
    };

    expect(() => resolveAdminConnection({})).toThrowError(
      new AdminCommandError(
        'An admin API key is required. Use --admin-api-key or set RESHAPR_ADMIN_API_KEY.'
      )
    );
  });

  it('uses the environment key and saved server when flags are absent', () => {
    process.env.RESHAPR_ADMIN_API_KEY = 'environment-key';
    ConfigUtil.config = {
      username: 'user',
      server: 'https://saved.example/',
      token: 'user-token'
    };

    expect(resolveAdminConnection({})).toEqual({
      adminApiKey: 'environment-key',
      server: 'https://saved.example'
    });
  });

  it('requires a server from a flag or saved login configuration', () => {
    process.env.RESHAPR_ADMIN_API_KEY = 'environment-key';
    ConfigUtil.config = {
      username: '',
      server: '',
      token: ''
    };

    expect(() => resolveAdminConnection({})).toThrow(
      'A control-plane server is required. Use --server or login to save a server.'
    );
  });

  it('rejects malformed structured options', () => {
    expect(() => parseStringArray('["acme",3]', '--organizations'))
      .toThrow('--organizations must be a JSON array of strings.');
    expect(() => parseQuotas('[{"metric":"gateway.count","enabled":"yes","limit":3}]'))
      .toThrow('--quotas must be a JSON array of objects');
  });
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json'
    }
  });
}
