import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';

// Security fix minimums:
// - GHSA-27r3-jxmh-p828: body-parser >= 2.4.0
// - GHSA-mrhx-c7xc-m3w5: zod >= 4.6.6
const MIN_BODY_PARSER = [2, 4, 0];
const MIN_ZOD = [4, 6, 6];

const lockfile = JSON.parse(
  readFileSync(new URL('../package-lock.json', import.meta.url), 'utf8'),
);

function toVersionParts(version) {
  return version.split('.').map((part) => Number.parseInt(part, 10));
}

function gte(left, right) {
  for (let i = 0; i < Math.max(left.length, right.length); i += 1) {
    const l = left[i] ?? 0;
    const r = right[i] ?? 0;
    if (l > r) return true;
    if (l < r) return false;
  }
  return true;
}

test('body-parser resolves to the patched version (GHSA-27r3-jxmh-p828)', () => {
  const bodyParser = lockfile.packages['node_modules/body-parser'];
  assert.ok(bodyParser, 'body-parser is missing from the lockfile');
  assert.ok(
    gte(toVersionParts(bodyParser.version), MIN_BODY_PARSER),
    `body-parser ${bodyParser.version} is below the patched 2.4.0`,
  );
});

test('zod resolves to the patched version (GHSA-mrhx-c7xc-m3w5)', () => {
  const zod = lockfile.packages['node_modules/zod'];
  assert.ok(zod, 'zod is missing from the lockfile');
  assert.ok(
    gte(toVersionParts(zod.version), MIN_ZOD),
    `zod ${zod.version} is below the patched 4.6.6`,
  );
});
