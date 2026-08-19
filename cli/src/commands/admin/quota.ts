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
import { Command } from 'commander';
import { Context } from '../../utils/context.js';
import { Logger } from '../../utils/logger.js';
import { adminOptions, runAdminAction } from './shared.js';
import { AdminCommandError, adminRequest } from './utils.js';

export interface QuotaInput {
  metric: string;
  enabled: boolean;
  limit: number;
}

export function parseQuotas(value: string): QuotaInput[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new AdminCommandError('--quotas must be a valid JSON array.');
  }
  if (!Array.isArray(parsed) || parsed.some(item => !isQuotaInput(item))) {
    throw new AdminCommandError(
      '--quotas must be a JSON array of objects with string metric, boolean enabled, and integer limit.'
    );
  }
  return parsed;
}

function isQuotaInput(value: unknown): value is QuotaInput {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const quota = value as Record<string, unknown>;
  return typeof quota.metric === 'string'
    && quota.metric.length > 0
    && typeof quota.enabled === 'boolean'
    && typeof quota.limit === 'number'
    && Number.isSafeInteger(quota.limit)
    && quota.limit >= 0;
}

export function createAdminQuotaCommand(): Command {
  const quotaCommand = new Command('quota')
    .description('Manage organization quotas');

  quotaCommand.command('assign <organization>')
    .description('Assign quotas to an organization')
    .requiredOption('--quotas <json>', 'JSON array of quota definitions')
    .option('-o, --output <format>', 'Output format (json, yaml)')
    .action(async (organization, options, command) => runAdminAction(async () => {
      const quotas = parseQuotas(options.quotas);
      const result = await adminRequest<unknown[]>(
        `quotas/organization/${encodeURIComponent(organization)}`,
        adminOptions(command),
        'POST',
        quotas
      );
      Context.put('quotas', result);
      Logger.success(`Quotas assigned to organization '${organization}'.`);
    }));

  return quotaCommand;
}
