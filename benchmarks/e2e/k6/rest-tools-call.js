/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * End-to-end MCP `tools/call` load script against a running reshapr proxy (REST backend).
 *
 * The call uses the modern stateless MCP mode (protocol 2026-07-28: `MCP-Protocol-Version`
 * header + `Mcp-Method`/`Mcp-Name` mirror headers + `params._meta` envelope) so that no
 * session management is needed in the injector: every request is self-contained, exactly like
 * a stateless MCP client.
 *
 * One phase per k6 run (selected with `-e PHASE=...`) so that every reported metric covers
 * exactly one workload:
 *   - warmup:      closed model, constant VUs. JIT + WorkCache warmup, metrics discarded.
 *   - latency:     open model (constant-arrival-rate). Fixed request rate, decoupled from
 *                  response times: avg/p95 latencies free of coordinated omission.
 *   - throughput:  closed model, constant VUs at saturation: max req/s the proxy sustains.
 *
 * Environment variables:
 *   BASE_URL  proxy base URL                       (default http://localhost:7777)
 *   PAYLOAD   small | medium | large               (default small) -> exposition bench-rest-<PAYLOAD>
 *   PHASE     warmup | latency | throughput        (default latency)
 *   RATE      arrival rate in req/s (latency)      (default 150)
 *   VUS       virtual users (throughput)           (default 64)
 *   DURATION  phase duration                       (default 30s, warmup 15s)
 */
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:7777';
const PAYLOAD = (__ENV.PAYLOAD || 'small').toLowerCase();
const PHASE = (__ENV.PHASE || 'latency').toLowerCase();
const RATE = parseInt(__ENV.RATE || '150', 10);
const VUS = parseInt(__ENV.VUS || '64', 10);
const DURATION = __ENV.DURATION || (PHASE === 'warmup' ? '15s' : '30s');

const SCENARIOS = {
  warmup: {
    executor: 'constant-vus',
    vus: Math.min(VUS, 16),
    duration: DURATION,
  },
  latency: {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.max(50, RATE),
    maxVUs: Math.max(200, RATE * 2),
  },
  throughput: {
    executor: 'constant-vus',
    vus: VUS,
    duration: DURATION,
  },
};

export const options = {
  scenarios: { [PHASE]: SCENARIOS[PHASE] },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate>0.99'],
  },
  // The proxy responses are deterministic: skip response decompression-side work where possible.
  discardResponseBodies: false,
};

const MCP_ENDPOINT = `${BASE_URL}/mcp/bench-rest-${PAYLOAD}`;

// tools/call against the benchmarked operation of the synthetic OpenAPI spec
// ('GET /resources0/{id}' -> tool 'get_resources0_id', see E2EBenchEnvironment).
const BODY = JSON.stringify({
  jsonrpc: '2.0',
  id: 1,
  method: 'tools/call',
  params: {
    name: 'get_resources0_id',
    arguments: { id: '42', limit: 25, verbose: true, tags: ['alpha', 'gamma'] },
    _meta: { 'io.modelcontextprotocol/protocolVersion': '2026-07-28' },
  },
});

const PARAMS = {
  headers: {
    'Content-Type': 'application/json',
    Accept: 'application/json',
    'MCP-Protocol-Version': '2026-07-28',
    'Mcp-Method': 'tools/call',
    'Mcp-Name': 'get_resources0_id',
    'User-Agent': 'reshapr-e2e-bench/k6',
  },
};

export default function () {
  const res = http.post(MCP_ENDPOINT, BODY, PARAMS);
  check(res, {
    'status is 200': (r) => r.status === 200,
    'jsonrpc result': (r) => r.body != null && r.body.indexOf('"result"') !== -1
        && r.body.indexOf('"isError":false') !== -1,
  });
}
