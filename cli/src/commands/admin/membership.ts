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
import { adminRequest, parseStringArray } from './utils.js';

export function createAdminMembershipCommand(): Command {
  const membershipCommand = new Command('membership')
    .description('Manage user memberships');

  membershipCommand.command('set <username>')
    .description('Replace all organization memberships for a user')
    .requiredOption('--organizations <json>', 'JSON array of organization names')
    .option('-o, --output <format>', 'Output format (json, yaml)')
    .action(async (username, options, command) => runAdminAction(async () => {
      const organizations = parseStringArray(options.organizations, '--organizations');
      const result = await adminRequest<string[]>(
        `users/${encodeURIComponent(username)}/memberships`,
        adminOptions(command),
        'PUT',
        organizations
      );
      Context.put('memberships', result);
      Logger.success(`Memberships updated for user '${username}'.`);
    }));

  return membershipCommand;
}
