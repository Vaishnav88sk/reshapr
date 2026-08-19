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

interface User {
  username: string;
  email: string;
}

export function createAdminUserCommand(): Command {
  const userCommand = new Command('user')
    .description('Manage users');

  userCommand.command('create <username>')
    .description('Create a user')
    .requiredOption('-e, --email <email>', 'User email address')
    .option('-p, --password <password>', 'User password')
    .option('--firstname <firstname>', 'User first name')
    .option('--lastname <lastname>', 'User last name')
    .option('-o, --output <format>', 'Output format (json, yaml)')
    .action(async (username, options, command) => runAdminAction(async () => {
      const user = await adminRequest<User>(
        'users',
        adminOptions(command),
        'POST',
        {
          username,
          email: options.email,
          password: options.password,
          firstname: options.firstname,
          lastname: options.lastname
        }
      );
      Context.put('user', user);
      Logger.success(`User '${username}' created successfully.`);
    }));

  return userCommand;
}
