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
import { adminOptions, parsePositiveInteger, runAdminAction } from './shared.js';
import { adminRequest, parseStringArray } from './utils.js';

interface ServiceAccount {
  id: string;
  name: string;
}

export function createAdminServiceAccountCommand(): Command {
  const serviceAccountCommand = new Command('service-account')
    .description('Manage service accounts');

  serviceAccountCommand.command('create <name>')
    .description('Create a service account')
    .requiredOption('--k8s-subject <subject>', 'Kubernetes subject in namespace:name format')
    .requiredOption('--allowed-organizations <json>', 'JSON array of organization names, or ["*"]')
    .requiredOption('--validity-days <days>', 'Number of days the service account remains valid')
    .option('-d, --description <description>', 'Service account description')
    .option('-o, --output <format>', 'Output format (json, yaml)')
    .action(async (name, options, command) => runAdminAction(async () => {
      const validityDays = parsePositiveInteger(options.validityDays, '--validity-days');
      const allowedOrganizations = parseStringArray(
        options.allowedOrganizations,
        '--allowed-organizations'
      );
      const serviceAccount = await adminRequest<ServiceAccount>(
        'serviceAccounts',
        adminOptions(command),
        'POST',
        {
          name,
          description: options.description,
          k8sSubject: options.k8sSubject,
          allowedOrganizations,
          validityDays
        }
      );
      Context.put('serviceAccount', serviceAccount);
      Logger.success(`Service account '${name}' created successfully.`);
    }));

  return serviceAccountCommand;
}
