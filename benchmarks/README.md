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

Convenience scripts are provided: `run-openapi-converter-bench.sh`, `run-graphql-converter-bench.sh` and 
`run-mcp-controller-bench.sh` run the full 
campaign then print a readable summary table (time + allocations per scenario/implementation) via `summarize.sh` (requires `jq`).

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

## Methodology notes

- The `coldCache` scenario relies on `@Setup(Level.Invocation)`: the cache invalidation is
  excluded from the measurement, but for very short operations (< 1 µs) a slight overestimation
  is possible (documented JMH limitation). The measured times here (>10 µs) make this bias negligible.
- `gc.alloc.rate.norm` (B/op) is the reference memory metric: bytes allocated per call.

