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

interface EncryptionStatus {
  activeKid: string;
}

interface RotationReport {
  secretsRotated: number;
  configurationPlansRotated: number;
}

export function createAdminEncryptionCommand(): Command {
  const encryptionCommand = new Command('encryption')
    .description('Inspect and rotate the database encryption keyset');

  encryptionCommand.command('status')
    .description('Show the id (kid) of the key currently used for new encryption')
    .option('-o, --output <format>', 'Output format (json, yaml)')
    .action(async (options, command) => runAdminAction(async () => {
      const result = await adminRequest<EncryptionStatus>(
        'encryption/status',
        adminOptions(command),
        'POST',
        {}
      );
      Context.put('encryption', result);
      Logger.success(`Active encryption key id: ${result.activeKid}`);
    }));

  encryptionCommand.command('rotate')
    .description('Re-encrypt every sensitive column with the active key. Idempotent: values '
      + 'already encrypted with the active key are skipped.')
    .option('-y, --yes', 'Skip the confirmation prompt')
    .option('-o, --output <format>', 'Output format (json, yaml)')
    .action(async (options, command) => runAdminAction(async () => {
      if (!options.yes) {
        Logger.warn('This will re-encrypt every sensitive column in the control-plane database.');
        Logger.warn('Re-run with --yes to confirm.');
        return;
      }
      const report = await adminRequest<RotationReport>(
        'encryption/rotate',
        adminOptions(command),
        'POST',
        {}
      );
      Context.put('rotation', report);
      Logger.success(
        `Rotation complete — secrets: ${report.secretsRotated}, `
        + `configurationPlans: ${report.configurationPlansRotated}`
      );
    }));

  return encryptionCommand;
}
