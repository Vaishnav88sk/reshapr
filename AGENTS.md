# AGENTS.md

## Project Overview

reShapr is a no-code MCP (Model Context Protocol) Server that transforms REST/GraphQL/gRPC APIs into LLM-friendly tools. It solves "Context Overload" by filtering and slimming API payloads before they reach LLMs.

## Architecture

Three core runtime services communicate via gRPC, plus a web UI and a CLI:

- **Control Plane** (`control-plane/`) — Quarkus service on port `5555`. Manages services, expositions, organizations, users, gateways. Uses PostgreSQL + Flyway migrations + Hibernate ORM Panache with multi-tenant DISCRIMINATOR strategy. Hazelcast for caching.
- **Proxy/Gateway** (`proxy/`) — Quarkus service on port `7777`. Receives MCP requests, discovers expositions via gRPC from the control plane (`eds-v1.proto`, `ghs-v1.proto`), proxies calls to backend APIs. Supports REST, GraphQL, gRPC backends.
- **CLI** (`cli/`) — TypeScript/Node.js CLI (`@reshapr/reshapr-cli`) built with Commander.js. Manages login, import, service lifecycle, and local Docker-based platform via `reshapr run`.
- **Web UI** (`web-ui/`) — SvelteKit 5 app (`@reshapr/reshapr-web-ui`, Svelte 5 runes + TailwindCSS 4 + bits-ui) deployed with `adapter-node` (SSR). Standalone, **not** a Maven module. Talks to the control-plane admin API server-side via `RESHAPR_ADMIN_API_KEY` (see `web-ui/src/lib/server/proxy.ts`, `auth.ts`). Runs on `5173` in dev (Vite), `3333` in the container.

Shared modules:
- `api/` — Protobuf definitions (`eds-v1.proto` for Exposition Discovery, `ghs-v1.proto` for Gateway Health)
- `commons/` — Shared Java utilities
- `mcp-commons/` — MCP protocol shared logic

## Build & Dev Commands

```bash
# Full Maven build (Java 25 + preview features required)
./mvnw clean install -DskipTests

# Run control-plane in dev mode (starts PostgreSQL devservice automatically)
cd control-plane && ../mvnw quarkus:dev

# Run proxy in dev mode (requires control-plane running)
cd proxy && ../mvnw quarkus:dev

# CLI development
cd cli && npm install && npm run dev  # watch mode
npm link                               # makes `reshapr` binary available

# CLI tests
cd cli && npm test                     # unit tests (vitest)
cd cli && npm run test:e2e             # e2e tests

# Web UI development (requires control-plane reachable + RESHAPR_ADMIN_API_KEY in web-ui/.env)
cd web-ui && npm install && npm run dev # Vite dev server on http://localhost:5173
cd web-ui && npm run check              # svelte-check type checking

# Native image build
./mvnw package -Pnative
```

## Key Conventions

- **Java 25 with `--enable-preview`** — all Java modules use preview features
- **MapStruct** for DTO mapping (annotation processor configured in compiler plugin)
- **Flyway migrations** in `control-plane/src/main/resources/db/migration/` — versioned as `V{major}.{minor}.{patch}__description.sql`
- **Quarkus profiles**: `%dev` auto-enables debug logging + dev data; `%prod` uses separate config; `%otel` for observability
- **Conventional Commits** required on PR titles: `feat:`, `fix:`, `docs:`, `chore:`, `test:`, `refactor:` (append `!` for breaking)
- **TSID** (Time-Sorted Identifiers) for entity IDs via `hypersistence-tsid`
- **Multi-tenancy** via Hibernate DISCRIMINATOR — entities extend `TenantAwareEntity`

## Project Structure Patterns

```
control-plane/src/main/java/io/reshapr/ctrl/
├── model/          # JPA entities (BaseEntity, TenantAwareEntity)
├── repository/     # Panache repositories
├── service/        # Business logic
├── rest/           # JAX-RS resources (v1/ for public API, admin/ for admin API)
├── security/       # JWT auth, token management
├── mcp/            # MCP protocol handling
└── config/         # Application configuration beans

proxy/src/main/java/io/reshapr/proxy/
├── proxy/          # Core proxying logic (REST/GraphQL/gRPC dispatch)
├── mcp/            # MCP server endpoint
├── registry/       # Service registry (synced from control-plane via gRPC)
├── security/       # Gateway token validation
└── audit/          # Request auditing
```

## Integration Points

- **Control Plane ↔ Proxy**: gRPC (Exposition Discovery Service + Gateway Health Service). Proxy authenticates with `RESHAPR_CTRL_TOKEN` (prefixed with org name).
- **Control Plane ↔ PostgreSQL**: JDBC, Flyway-managed schema
- **Proxy ↔ Backend APIs**: HTTP (REST/GraphQL) and gRPC, configurable timeouts via `reshapr.gateway.backend.http.default-timeout`
- **CLI ↔ Control Plane**: REST API on port 5555, authenticated via JWT tokens stored in `~/.reshapr/` config
- **Web UI ↔ Control Plane**: server-side calls to the admin API (`RESHAPR_CTRL_URL`, default `http://localhost:5555`) authenticated with `RESHAPR_ADMIN_API_KEY`. Add the UI to a local stack with `install/docker-compose-ui-addon.yml`.

## Testing

- Java: JUnit 5 + REST Assured + Quarkus `@QuarkusTest` (dev services auto-provision PostgreSQL)
- CLI: Vitest for unit tests; e2e tests in `cli/e2e/` require running platform (see `cli/e2e/global-setup.ts`)
- Docker Compose in `install/` for full local stack: `docker-compose-all-in-one.yml`

## OpenAPI Specs

Three API specifications in project root describe the public interfaces:
- `reshapr-public-openapi-v0.1.yaml` — public-facing API
- `reshapr-admin-ctrl-openapi-v0.1.yaml` — admin control plane API  
- `reshapr-authentication-openapi-v0.1.yaml` — authentication endpoints

