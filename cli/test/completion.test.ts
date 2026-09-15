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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { configureShellCompletion } from '../src/completion.js';

describe('shell completion', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('generates shell setup and completes commands and flags', async () => {
    const program = new Command('reshapr');
    const serviceCommand = program.command('service').description('Manage services');
    serviceCommand.command('list')
      .description('List services')
      .option('-o, --output <format>', 'Output format');

    const completion = configureShellCompletion(program);
    const visibleCommands = program.createHelp().visibleCommands(program).map(command => command.name());

    expect(visibleCommands).toContain('completion');
    expect(visibleCommands).not.toContain('complete');
    expect(completion.commands.get('service list')?.options.has('output')).toBe(true);

    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});

    await program.parseAsync(['completion', 'zsh'], { from: 'user' });
    expect(logSpy.mock.calls.flat().join('\n')).toContain('#compdef reshapr');

    logSpy.mockClear();
    await program.parseAsync(['complete', '--', 'service', ''], { from: 'user' });
    expect(logSpy).toHaveBeenCalledWith('list\tList services');

    logSpy.mockClear();
    await program.parseAsync(['complete', '--', 'service', 'list', '--'], { from: 'user' });
    expect(logSpy).toHaveBeenCalledWith('--output\tOutput format');
  });
});