# Reshapr Micro-benchmarks

**Standalone** Maven module (outside the reactor — the main build is left untouched) containing
JMH micro-benchmarks for the proxy/gateway.

## Available benchmarks

### `OpenAPIGetCallResponseBenchmark`

Measures `OpenAPIMcpToolConverter.getCallResponse()` (average time + allocations via the GC profiler), isolating the 
conversion logic only: the `ProxyService` is replaced by `NaiveProxyService`, which always returns the same canned 
response without any I/O.

Measurement axes:

| Axis | Values |
|---|---|
| `operationCount` | 6, 30, 80 operations in the spec |
| `specSize` | `SMALL` / `MEDIUM` / `LARGE` (schema complexity → document byte size) |
| `pathDepth` | `SHALLOW` (`/resources0/{id}`) / `DEEP` (`/resources0/{id}/subresources/{subId}/details/{detailId}/items`) |
| `refStyle` | `INLINE` (local `#/components/...` refs) / `EXTERNAL` (parameters + schemas in an attached YAML file referenced via `$ref`) |
| Cache | `coldCache` (WorkCache invalidated + converter recreated on every call) vs `warmCache` (spec already parsed and cached, converter reused) vs `warmCacheFreshConverter` (warm WorkCache but a fresh converter per call, exactly what `ToolCallExecutor.buildMcpToolConverter()` does in production) |
| `converterImpl` | implementation under test (see below) |

OpenAPI specifications are generated synthetically and reproducibly by `OpenAPISpecGenerator`.

### `GraphQLGetCallResponseBenchmark`

Measures `GraphQLMcpToolConverter.getCallResponse()` against the real-world GitHub GraphQL schema  (`github-api.graphql`, 
~1.38 MiB, bundled in `src/main/resources` — a copy of `dev/github-api.graphql`), on the `user` tool, with the following
MCP request:

```json
{ "login": "octocat", "__relation_avatarUrl": { "size": 32 }, "__relation_followers": { "last": 10 } }
```

Same cache scenarios as the OpenAPI benchmark (`coldCache`, `warmCache`, `warmCacheFreshConverter`) and same 
pluggable-implementation mechanism via the `ConverterFactory` of the `graphql` package (`converterImpl` key).

> ⚠️ The benchmark setup raises the graphql-java anti-DoS parser limits
> (`ParserOptions.setDefaultParserOptions(getDefaultSdlParserOptions())`) because the GitHub
> schema exceeds the default 1 MiB cap — a limit that `GraphQLMcpToolConverter.getDocument()`
> would also hit in production with this schema.

```bash
java --enable-preview -jar target/benchmarks.jar GraphQLGetCallResponseBenchmark \
     -prof gc -rf json -rff results-graphql.json
```

If the setup fails (`Sanity check failed: unexpected response null`), keep in mind the converter swallows exceptions: 
use `GraphQLSanityCheck` to get the actual stack trace outside JMH (see its javadoc). It also verifies that every 
registered implementation generates a backend request body identical to the reference one:

```bash
java --enable-preview -cp target/benchmarks.jar io.reshapr.benchmarks.graphql.GraphQLSanityCheck
```

### `McpControllerToolsCallBenchmark`

Measures the **pure `McpController` request-handling cost** for a `tools/call`: exposition lookup, modern
(SEP-2243) pre-dispatch validation ladder, session/protocol-mode resolution, `ScopedValue` binding,
method dispatch, Jackson params conversion, tool resolution and result shaping.

Everything below the controller is stubbed so no I/O and no real conversion cost is measured:
the `McpToolConverter` is replaced by `NaiveMcpToolConverter` (canned response, injected by overriding
`ToolCallExecutor.buildMcpToolConverter()`), audit is disabled on the configuration, OTEL is unresolved
(no-op `AuditLogger`), and JAX-RS / Vert.x / Infinispan collaborators are container-free stubs
(`McpBenchStubs`).

Measurement axes:

| Axis | Values |
|---|---|
| `protocolMode` | `LEGACY` (session-bound, `MCP-Session-Id` header, protocol 2025-06-18) / `MODERN` (stateless, `MCP-Protocol-Version: 2026-07-28` + mirror headers + `params._meta` envelope, exercising the whole modern validation ladder) |
| `payloadSize` | `SMALL` (3 scalar args) / `MEDIUM` (20 scalars + 3 nested maps + 64 B blob) / `LARGE` (200 scalars + 10 nested maps + 2 KiB blob) |
| `headerCount` | `SMALL` (4 base + 4 extra headers) / `MEDIUM` (+16) / `LARGE` (+64) |
| `audit` | `false` / `true` — controller-side audit overhead (synchronous capture, virtual-thread spawn, async re-serialization). The OTEL emission itself is stubbed out. `gc.alloc.rate.norm` includes the virtual-thread allocations; the reported time only reflects the request thread |
| `controllerImpl` | implementation under test (see the `ControllerFactory` of the `mcp` package — same pluggable-implementation mechanism as the converter benchmarks) |

```bash
java --enable-preview -jar target/benchmarks.jar McpControllerToolsCallBenchmark \
     -prof gc -rf json -rff results-mcp-controller.json
```

Convenience script: `run-mcp-controller-bench.sh`.

### `ProxyServiceCallBackendBenchmark`

Measures the **pure proxying performance** of `ProxyService.callBackend()` (throughput in ops/s +
allocations via the GC profiler), end-to-end over loopback. The backend is `MinimalHttpBackend`,
an ultra-minimal raw-socket HTTP/1.1 server (virtual threads, keep-alive, single pre-serialized
canned response) designed to never be the bottleneck. Every call runs inside a bound
`MethodHandlingContext` scoped value, exactly as the MCP layer does in production.

Since `ProxyService` is a highly shared component (static `HttpClient` + connection pool),
**concurrency is a first-class axis**, selected via the benchmark method name.

Measurement axes:

| Axis | Values |
|---|---|
| `requestPayload` | `SMALL` / `MEDIUM` / `LARGE` incoming request body (~100 B / ~10 KiB / ~500 KiB JSON) |
| `headerCount` | `SMALL` / `MEDIUM` / `LARGE` incoming header set (3 / 12 / 40 headers, incl. restricted ones to filter) |
| `secretMode` | `NONE` / `TOKEN` (Bearer via `SecretReferenceResolver`) / `BASIC` (username+password, Base64) |
| `responsePayload` | `SMALL` / `MEDIUM` / `LARGE` backend response body |
| Concurrency | `callBackend_noConcurrency` (1 thread) / `callBackend_lowConcurrency` (4) / `callBackend_mediumConcurrency` (16) / `callBackend_highConcurrency` (64) |
| `proxyImpl` | implementation under test (see the `ProxyFactory` of the `proxy` package: `current` = `ProxyService`, `optimized` = `OptimizedProxyService`) |

The full matrix (648 benchmarks) would take hours: the convenience script
`run-proxy-service-bench.sh` runs a representative baseline instead (each axis explored
independently around a nominal point) and merges the results into `results-proxy.json`.

```bash
java --enable-preview -jar target/benchmarks.jar "ProxyServiceCallBackendBenchmark.callBackend_mediumConcurrency" \
     -p requestPayload=MEDIUM -p responsePayload=MEDIUM -p secretMode=TOKEN \
     -p proxyImpl=current,optimized -prof gc -rf json -rff results-proxy.json
```

## End-to-end benchmark (`run-e2e-rest-bench.sh`)

The JMH benchmarks above isolate single components in-JVM. The **end-to-end benchmark** measures
the proxy **deployed as a real Quarkus application** (production fast-jar, prod profile, gRPC
discovery, HTTP stack, JSON [de]serialization, MCP controller, converter, proxying — everything),
and reports, per response payload size:

- **response times**: avg, p95, p99 (ms);
- **throughput**: sustained req/s at saturation;
- **memory**: RSS of the proxy process (avg / max), sampled every second under load.

### Why not JMH here?

JMH measures code *inside* the benchmark JVM. Here the system under test is a separate process,
so JMH would (1) profile the *client* instead of the proxy (the GC profiler becomes meaningless),
(2) drive the load with a closed model whose latency percentiles suffer from coordinated omission
as soon as the proxy saturates, and (3) say nothing about the target process memory. A proper
load injector is used instead: **k6**, whose `constant-arrival-rate` executor implements an open
model (fixed request rate decoupled from response times → honest p95/p99), while a separate
`constant-vus` phase measures the maximum sustained throughput.

### Topology

Everything runs on loopback, so no network noise is measured:

```
k6 (injector) ──HTTP/MCP──> proxy (Quarkus fast-jar, :7777) ──HTTP──> CannedHttpBackend x3 (:19901-19903)
                               │
                               └────gRPC────> E2EBenchEnvironment (stub control-plane, :15555)
```

- `io.reshapr.benchmarks.e2e.E2EBenchEnvironment` hosts a **stub control-plane** (plain gRPC
  server implementing `eds-v1.proto` + `ghs-v1.proto`) serving three REST expositions
  (`bench-rest-small|medium|large`) whose main artifact is a synthetic OpenAPI spec generated by
  `OpenAPISpecGenerator`, plus three **canned backends** (`CannedHttpBackend`, the fixed-port
  variant of `MinimalHttpBackend`) answering with ~5 KiB / ~50 KiB / ~500 KiB JSON bodies
  (configurable item counts, see below) — fast enough to never be the bottleneck.
- The proxy is the **unmodified production artifact** started with container-like JVM flags
  (`--enable-preview`, `-XX:+UseCompactObjectHeaders`, fixed heap — `PROXY_HEAP`, default 512m)
  and discovers the expositions through the stub exactly as in production.
- k6 drives `tools/call` requests in the **modern stateless MCP mode** (protocol `2026-07-28`:
  `MCP-Protocol-Version` + `Mcp-Method`/`Mcp-Name` mirror headers + `params._meta` envelope), so
  every request is self-contained (no session handshake to manage in the injector).

### Run

```bash
# Prerequisites: JDK 25, jq, k6 (native binary, or docker as automatic fallback).
./run-e2e-rest-bench.sh                    # all three payload sizes
./run-e2e-rest-bench.sh small large        # a subset

# Tunables:
LATENCY_RATE=300 THROUGHPUT_VUS=128 PROXY_HEAP=1g ./run-e2e-rest-bench.sh

# Payload sizes (JSON items per backend response, ~130 B each; defaults 38/380/3700
# i.e. ~5 KiB / ~50 KiB / ~500 KiB):
PAYLOAD_SMALL_ITEMS=75 PAYLOAD_MEDIUM_ITEMS=750 ./run-e2e-rest-bench.sh

# Audit path (default disabled). Enables the `audit` flag on every exposition configuration:
# the proxy then builds and dispatches an audit event per call (synchronous context capture +
# virtual-thread handoff + response re-serialization for the size attribute). The OTEL SDK
# stays disabled so the final emission is a no-op — this measures the audit-path overhead only.
AUDIT_ENABLED=true ./run-e2e-rest-bench.sh
```

Per payload size, three k6 phases run in sequence (`e2e/k6/rest-tools-call.js`):

| Phase | Model | Purpose |
|---|---|---|
| `warmup` | closed, few VUs | JIT + WorkCache warmup, metrics discarded |
| `latency` | **open**, `constant-arrival-rate` at `LATENCY_RATE` req/s (default 150) | avg/p95/p99 latency free of coordinated omission |
| `throughput` | closed, `constant-vus` at `THROUGHPUT_VUS` (default 64) | max sustained req/s |

The script prints a summary table and leaves the raw k6 JSON summaries, RSS samples and
proxy/environment logs in `results-e2e-rest/`. The results can be (re-)summarized at any time
as 3 tables (latency under nominal load, max throughput, proxy RSS) with:

```bash
./summarize-e2e.sh                # reads results-e2e-rest/ by default
./summarize-e2e.sh some-other-dir # or any directory with latency-*/throughput-*/memory-* files
```

The environment can also be started standalone (e.g. to point another injector or a profiler at
the proxy):

```bash
java --enable-preview -cp target/benchmarks.jar io.reshapr.benchmarks.e2e.E2EBenchEnvironment
# then start the proxy against it:
cd ../proxy && RESHAPR_CTRL_HOST=localhost RESHAPR_CTRL_PORT=15555 RESHAPR_CTRL_TOKEN=reshapr-bench-token \
  QUARKUS_OTEL_SDK_DISABLED=true java --enable-preview -Dreshapr.infinispan.stack=reshapr-local \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager -jar target/quarkus-app/quarkus-run.jar
```

> GraphQL and gRPC backends follow the same pattern (add expositions and stub backends to
> `E2EBenchEnvironment`, plus a k6 script per protocol) and are not covered yet.

## Build and run

```bash
# 1. Install the project artifacts into the local Maven repository
./mvnw install -DskipTests -pl proxy -am

# 2. Build the benchmark jar
cd benchmarks && ../mvnw package -q

# 3. Run (time + memory)
java --enable-preview -jar target/benchmarks.jar OpenAPIGetCallResponseBenchmark \
     -prof gc -rf json -rff results.json
```

Convenience scripts are provided: `run-openapi-converter-bench.sh`, `run-graphql-converter-bench.sh`, 
`run-mcp-controller-bench.sh` and `run-proxy-service-bench.sh` run the full 
campaign then print a readable summary table (time + allocations per scenario/implementation) via `summarize.sh` (requires `jq`).
`run-e2e-rest-bench.sh` runs the end-to-end campaign (see above).

Useful options:

```bash
# Run only a subset of combinations
java --enable-preview -jar target/benchmarks.jar OpenAPIGetCallResponseBenchmark \
     -p operationCount=80 -p specSize=LARGE

# More iterations for more stable numbers
java --enable-preview -jar target/benchmarks.jar OpenAPIGetCallResponseBenchmark -wi 5 -i 10 -f 2
```

## Comparing an alternative implementation

**Converters** (OpenAPI / GraphQL benchmarks):

1. Write the new implementation (it must extend `McpToolConverter` and expose the same
   `(ExpositionEntry, WorkCache, ObjectMapper, ProxyService)` constructor).
2. Register it in the relevant `ConverterFactory.FACTORIES` with a new key (e.g. `"optimized"`).
3. Add the key to the `converterImpl` `@Param` of the corresponding benchmark class.
4. Re-run: both implementations are measured side by side under the exact same conditions.

**Controller** (`McpControllerToolsCallBenchmark`):

1. Write the new implementation: it must extend `McpController`, override the public
   `handleHttpStreamable(...)` entry points and expose the same
   `(GatewayRegistry, SessionStore, WorkCache, ProxyService, ToolCallExecutor, AuditLogger)` constructor.
2. Register it in `io.reshapr.benchmarks.mcp.ControllerFactory.FACTORIES` with a new key (e.g. `"optimized"`).
3. Add the key to the `controllerImpl` `@Param` of `McpControllerToolsCallBenchmark`.
4. Re-run: both implementations are measured side by side under the exact same conditions
   (same wired collaborators, same stubs, same requests).

**Proxy** (`ProxyServiceCallBackendBenchmark`):

1. Write the new implementation in `io.reshapr.benchmarks.proxy.OptimizedProxyService` (or a new
   class extending `ProxyService` with the same `(SecretReferenceResolver, UserSecretStore)`
   constructor), overriding `callBackend()` and/or `doCallBackend()`.
2. Register it in `io.reshapr.benchmarks.proxy.ProxyFactory.FACTORIES` with a new key if needed.
3. Add the key to the `proxyImpl` `@Param` of `ProxyServiceCallBackendBenchmark`.
4. Re-run with `-p proxyImpl=current,optimized`: both implementations hit the same
   `MinimalHttpBackend` under the exact same conditions.

## Methodology notes

- The `coldCache` scenario relies on `@Setup(Level.Invocation)`: the cache invalidation is
  excluded from the measurement, but for very short operations (< 1 µs) a slight overestimation
  is possible (documented JMH limitation). The measured times here (>10 µs) make this bias negligible.
- `gc.alloc.rate.norm` (B/op) is the reference memory metric: bytes allocated per call.

