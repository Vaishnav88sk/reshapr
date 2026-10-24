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
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

// This suite guards the CVE dependency pins:
//   - dompurify >= 3.4.17 (GHSA-cg2g-xh64-v4q7, GHSA-mrv2-3pqr-j5jr)
//   - vite >= 8.3.3 (GHSA-587h-7wmj-h5pm)
// The lockfiles below are the source of truth for what is installed.

const repoRoot = new URL('../../', import.meta.url);

function resolvedVersion(pkg: string, name: string): string {
  const lock = JSON.parse(
    readFileSync(new URL(`${pkg}/package-lock.json`, repoRoot), 'utf8')
  ) as { packages?: Record<string, { version?: string } | undefined> };
  const entry = lock.packages?.[`node_modules/${name}`];
  if (!entry?.version) {
    throw new Error(`Could not find node_modules/${name} in ${pkg}/package-lock.json`);
  }
  return entry.version;
}

function parseVersion(version: string): number[] {
  return version.split('.').map((part) => Number.parseInt(part, 10));
}

function atLeast(actual: string, minimum: string): boolean {
  const a = parseVersion(actual);
  const b = parseVersion(minimum);
  for (let i = 0; i < b.length; i++) {
    const aPart = a[i] ?? 0;
    if (aPart > b[i]) return true;
    if (aPart < b[i]) return false;
  }
  return true;
}

describe('CVE dependency pins', () => {
  it('web-ui resolves dompurify >= 3.4.17', () => {
    expect(atLeast(resolvedVersion('web-ui', 'dompurify'), '3.4.17')).toBe(true);
  });

  it('web-ui resolves vite >= 8.3.3', () => {
    expect(atLeast(resolvedVersion('web-ui', 'vite'), '8.3.3')).toBe(true);
  });

  it('github-user-mcp-app resolves vite >= 8.3.3', () => {
    expect(
      atLeast(resolvedVersion('dev/github-user-mcp-app', 'vite'), '8.3.3')
    ).toBe(true);
  });
});
