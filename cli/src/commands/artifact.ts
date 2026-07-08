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
import { program } from "commander";
import { highlight } from "cli-highlight";
import inquirer from "inquirer";

import { Logger } from "../utils/logger.js";
import { ConfigUtil } from "../utils/config.js";
import { Context } from "../utils/context.js";
import { CLI_LABEL } from '../constants.js';

export const artifactCommand = program.command('artifact')
  .description(`Manage artifacts in ${CLI_LABEL}`);

/** List all artifacts */
artifactCommand.command('list')
  .description('List all artifacts for a given service')
  .requiredOption('-s, --serviceId <id>', 'Filter by service ID')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (options) => {

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/artifacts/service/${options.serviceId}/refs`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching artifacts failed: ' + response.statusText);
      process.exit(1);
    }

    const data = await response.json().catch(err => {
      Logger.error('Error parsing artifacts response: ' + err.message);
    });

    if (data != null ) {
      if (data.length === 0) {
        Logger.info('No artifacts found.');
      } else {
        Context.put('artifacts', data);

        const longestName = longestArtifactName(data); // +1 for padding
        const longestType = longestArtifactType(data); // +1 for padding

        Logger.log(`${'ID'.padEnd(13, ' ')}  ${'NAME'.padEnd(longestName, ' ')} ${'TYPE'.padEnd(longestType, ' ')} ${'MAIN'.padEnd(5, ' ')} CAPS`);
        data.forEach((artifact: any) => {
          const capsCount = Array.isArray(artifact.capabilities) ? artifact.capabilities.length : 0;
          Logger.log(`${artifact.id}  ${artifact.name.padEnd(longestName, ' ')} ${artifact.type.padEnd(longestType, ' ')} ${(artifact.mainArtifact ? 'Yes' : 'No').padEnd(5, ' ')} ${capsCount}`);
        });
      }
    }
  });

function longestArtifactName(artifacts: any[]) {
  return artifacts.reduce((max, artifact) => {
    return Math.max(max, artifact.name.length + 1);
  }, 0);
}

function longestArtifactType(artifacts: any[]) {
    return artifacts.reduce((max, artifact) => {
        return Math.max(max, artifact.type.length + 1);
    }, 0);
}

/** Get artifact by ID */
artifactCommand.command('get <id>')
  .description('Get artifact details by ID')
  .option('-d, --display', 'Display artifact content')
  .option('-o, --output <format>', 'Output format (json, yaml)')
  .action(async (id, options) => {
    const response = await fetch(`${ConfigUtil.config.server}/api/v1/artifacts/${id}`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      Logger.error('Fetching artifact failed: ' + response.statusText);
      process.exit(1);
    }

    const artifact = await response.json();
    Context.put('artifact', artifact);

    Logger.info('Artifact details');
    Logger.log(`ID           : ${artifact.id}`);
    Logger.log(`Name         : ${artifact.name}`);
    Logger.log(`Organization : ${artifact.organizationId}`);
    Logger.log(`Type         : ${artifact.type}`);
    Logger.log(`Main Artifact: ${artifact.mainArtifact ? 'Yes' : 'No'}`);
    Logger.log(`Source       : ${artifact.sourceArtifact}`);
    Logger.log(`Path         : ${artifact.path ? artifact.path : 'N/A'}`);
    Logger.log(`Capabilities : ${formatCapabilities(artifact.capabilities)}`);

    if (options.display && !options.output) {
      Logger.bold('\nArtifact content');
      Logger.bold('-----------------');
      const language = getLanguageFromSourceArtifact(artifact.sourceArtifact);
      Logger.log(highlight(artifact.content, { language }));
    }
  });

/** Delete artifact by ID */
artifactCommand.command('delete <id>')
  .description('Delete an artifact by ID (cleans up referencing configuration plans)')
  .option('-f, --force', 'Skip confirmation prompt')
  .action(async (id, options) => {
    // Preview the impact unless confirmation is bypassed.
    if (!options.force) {
      const previewResponse = await fetch(`${ConfigUtil.config.server}/api/v1/artifacts/${id}/deletion-impact`, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${ConfigUtil.config.token}`
        }
      });

      if (!previewResponse.ok) {
        if (previewResponse.status === 404) {
          Logger.error(`Artifact ${id} not found.`);
        } else {
          Logger.error('Computing deletion impact failed: ' + previewResponse.statusText);
        }
        process.exit(1);
      }

      const preview = await previewResponse.json();
      printDeletionImpact(preview);

      const confirm = await inquirer.prompt({
        type: 'confirm',
        name: 'confirm',
        message: `Delete artifact '${preview.artifactName}'? This cannot be undone.`,
        default: false
      });
      if (!confirm.confirm) {
        Logger.info('Deletion cancelled.');
        return;
      }
    }

    const response = await fetch(`${ConfigUtil.config.server}/api/v1/artifacts/${id}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${ConfigUtil.config.token}`
      }
    });

    if (!response.ok) {
      if (response.status === 404) {
        Logger.error(`Artifact ${id} not found.`);
      } else {
        Logger.error('Deleting artifact failed: ' + response.statusText);
      }
      process.exit(1);
    }

    const impact = await response.json().catch(() => null);
    Logger.success(`Artifact ${id} deleted successfully.`);
    if (impact) {
      printDeletionImpact(impact);
    }
  });

function printDeletionImpact(impact: any) {
  const plans = Array.isArray(impact?.impactedPlans) ? impact.impactedPlans : [];
  if (plans.length === 0) {
    Logger.info('No configuration plan references this artifact.');
    return;
  }
  Logger.info(`${plans.length} configuration plan(s) reference this artifact and will be updated:`);
  plans.forEach((plan: any) => {
    const suffix = plan.fallsBackToAll
      ? ' (selection becomes empty → falls back to all attached artifacts)'
      : '';
    Logger.log(`  - ${plan.name} (${plan.id})${suffix}`);
  });
}

/** Format the capabilities of an artifact for display. */
function formatCapabilities(capabilities: unknown): string {
  if (!Array.isArray(capabilities) || capabilities.length === 0) {
    // Non-custom artifacts have none; custom artifacts attached before this feature are not computed yet.
    return 'N/A';
  }
  return capabilities.filter((c) => typeof c === 'string').join(', ');
}

function getLanguageFromSourceArtifact(sourceArtifact: string | undefined): string | undefined {
  if (!sourceArtifact) return 'yaml';
  const ext = sourceArtifact.split('.').pop()?.toLowerCase();
  switch (ext) {
    case 'json': return 'json';
    case 'proto': return 'protobuf';
    case 'graphql':
    case 'gql': return undefined;
    default: return 'yaml';
  }
}