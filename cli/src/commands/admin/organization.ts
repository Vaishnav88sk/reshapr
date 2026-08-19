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
import { adminRequest } from './utils.js';

interface Organization {
  name: string;
  description?: string;
  icon?: string;
}

export function createAdminOrganizationCommand(): Command {
  const organizationCommand = new Command('organization')
    .description('Manage organizations');

  organizationCommand.command('create <name>')
    .description('Create an organization')
    .option('-d, --description <description>', 'Organization description')
    .option('-i, --icon <icon>', 'Organization icon URL or base64 value')
    .option('--owner <username>', 'Create the organization owned by this user')
    .option('-o, --output <format>', 'Output format (json, yaml)')
    .action(async (name, options, command) => runAdminAction(async () => {
      const path = options.owner
        ? `users/${encodeURIComponent(options.owner)}/organization`
        : 'organizations';
      const organization = await adminRequest<Organization>(
        path,
        adminOptions(command),
        'POST',
        {
          name,
          description: options.description,
          icon: options.icon
        }
      );
      Context.put('organization', organization);
      Logger.success(`Organization '${name}' created successfully.`);
    }));

  return organizationCommand;
}
