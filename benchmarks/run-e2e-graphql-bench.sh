#!/usr/bin/env bash
#
# End-to-end benchmark of the reshapr proxy (GraphQL backend), measuring — per response payload
# size (SMALL / MEDIUM / LARGE) — the response times (avg, p95, p99), the sustained throughput
# (req/s) and the memory consumption (RSS) of a single proxy instance.
#
# It is the GraphQL counterpart of run-e2e-rest-bench.sh: same phases, same metrics and same
# summary tool (summarize-e2e.sh), so the three protocols (REST / GraphQL / gRPC) are comparable.
#
# Topology (everything on loopback):
#
#   k6 (injector) --> proxy (real Quarkus fast-jar, :7777) --> CannedHttpBackend x3 (:19911-19913)
#                        |
#                        +--gRPC--> E2EGraphQLBenchEnvironment stub control-plane (EDS + GHS, :15555)
#
# Each exposition serves the real-world GitHub GraphQL schema (github-api.graphql, ~1.4 MiB) and
# the 'user' query as the benchmarked tool (see e2e/k6/graphql-tools-call.js).
#
# Per payload size, three k6 phases are run:
#   1. warmup      - JIT + WorkCache warmup, metrics discarded
#   2. latency     - open model at a fixed arrival rate: avg/p95/p99 without coordinated omission
#   3. throughput  - closed model at saturation: max sustained req/s
#
# The proxy RSS is sampled every second during the measured phases (e2e/sample-memory.sh).
#
# Prerequisites: JDK 25, jq, and k6 (native binary, or docker as a fallback).
# The proxy fast-jar and the benchmarks jar are built automatically when missing.
#
# Usage: ./run-e2e-graphql-bench.sh [payloads...]   (default: small medium large)
# Tunables (env): LATENCY_RATE (default 150 req/s), THROUGHPUT_VUS (default 64),
#                 WARMUP_DURATION (default 20s), MEASURE_DURATION (default 30s),
#                 PROXY_HEAP (default 512m), PROXY_PORT (default 7777),
#                 PAYLOAD_SMALL_ITEMS/PAYLOAD_MEDIUM_ITEMS/PAYLOAD_LARGE_ITEMS
#                 (JSON items per backend response, ~130 B each; defaults 38/380/3700
#                  i.e. ~5 KiB / ~50 KiB / ~500 KiB),
#                 AUDIT_ENABLED (default false).
set -euo pipefail

cd "$(dirname "$0")"
BENCH_DIR="$(pwd)"
REPO_ROOT="$(cd .. && pwd)"

PAYLOADS=("${@:-small}")
[ $# -eq 0 ] && PAYLOADS=(small medium large)

LATENCY_RATE="${LATENCY_RATE:-150}"
THROUGHPUT_VUS="${THROUGHPUT_VUS:-64}"
WARMUP_DURATION="${WARMUP_DURATION:-20s}"
MEASURE_DURATION="${MEASURE_DURATION:-30s}"
PROXY_HEAP="${PROXY_HEAP:-512m}"
PROXY_PORT="${PROXY_PORT:-7777}"
GRPC_PORT="${GRPC_PORT:-15555}"
# Extra JVM options for the proxy under test (e.g. JFR recording).
PROXY_JVM_EXTRA_OPTS="${PROXY_JVM_EXTRA_OPTS:-}"
PAYLOAD_SMALL_ITEMS="${PAYLOAD_SMALL_ITEMS:-38}"
PAYLOAD_MEDIUM_ITEMS="${PAYLOAD_MEDIUM_ITEMS:-380}"
PAYLOAD_LARGE_ITEMS="${PAYLOAD_LARGE_ITEMS:-3700}"
AUDIT_ENABLED="${AUDIT_ENABLED:-false}"

RESULTS_DIR="$BENCH_DIR/results-e2e-graphql"
mkdir -p "$RESULTS_DIR"

# --- Tooling checks -------------------------------------------------------------------------
command -v jq >/dev/null || { echo "ERROR: jq is required"; exit 1; }

if command -v k6 >/dev/null; then
  K6_MODE="native"
elif command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  K6_MODE="docker"
  echo "k6 binary not found: falling back to docker (grafana/k6)."
else
  echo "ERROR: k6 is required (native binary or docker). See https://k6.io/docs/get-started/installation/"
  exit 1
fi

# JDK 25 resolution: JAVA_HOME first, then sdkman.
if [ -z "${JAVA_HOME:-}" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "25'; then
  SDKMAN_JDK=$(ls -d "$HOME"/.sdkman/candidates/java/25* 2>/dev/null | sort | tail -1 || true)
  if [ -n "$SDKMAN_JDK" ]; then
    export JAVA_HOME="$SDKMAN_JDK"
  fi
fi
"${JAVA_HOME:-/usr}/bin/java" -version 2>&1 | grep -q 'version "25' \
  || { echo "ERROR: JDK 25 is required (set JAVA_HOME)"; exit 1; }
JAVA="$JAVA_HOME/bin/java"
echo "Using JAVA_HOME=$JAVA_HOME"

# --- Build what is missing ------------------------------------------------------------------
if [ ! -f "$BENCH_DIR/target/benchmarks.jar" ]; then
  echo "Building benchmarks jar..."
  ./mvnw -q package -DskipTests
fi
if [ ! -f "$REPO_ROOT/proxy/target/quarkus-app/quarkus-run.jar" ]; then
  echo "Building proxy fast-jar..."
  (cd "$REPO_ROOT/proxy" && ./mvnw -q package -DskipTests)
fi

# --- Process management ---------------------------------------------------------------------
ENV_PID=""
PROXY_PID=""
SAMPLER_PID=""

cleanup() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" 2>/dev/null || true
  [ -n "$PROXY_PID" ] && kill "$PROXY_PID" 2>/dev/null || true
  [ -n "$ENV_PID" ] && kill "$ENV_PID" 2>/dev/null || true
}
trap cleanup EXIT

wait_for() {
  local what="$1" cmd="$2" retries="${3:-30}"
  for _ in $(seq 1 "$retries"); do
    if eval "$cmd" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "ERROR: timed out waiting for $what"
  exit 1
}

# --- 1. Start the E2E environment (stub control-plane + canned GraphQL backends) --------------
echo "Starting E2E environment (stub control-plane :$GRPC_PORT + 3 canned GraphQL backends)..."
"$JAVA" --enable-preview -De2e.grpc.port="$GRPC_PORT" \
  -De2e.payload.small.items="$PAYLOAD_SMALL_ITEMS" \
  -De2e.payload.medium.items="$PAYLOAD_MEDIUM_ITEMS" \
  -De2e.payload.large.items="$PAYLOAD_LARGE_ITEMS" \
  -De2e.audit.enabled="$AUDIT_ENABLED" \
  -cp "$BENCH_DIR/target/benchmarks.jar" io.reshapr.benchmarks.e2e.E2EGraphQLBenchEnvironment \
  > "$RESULTS_DIR/e2e-env.log" 2>&1 &
ENV_PID=$!
wait_for "E2E environment" "grep -q 'E2E-ENV READY' '$RESULTS_DIR/e2e-env.log'"

# --- 2. Start the proxy under test (production-like JVM flags, fixed heap) -------------------
echo "Starting proxy (:$PROXY_PORT, heap $PROXY_HEAP)..."
RESHAPR_CTRL_HOST=localhost RESHAPR_CTRL_PORT="$GRPC_PORT" RESHAPR_CTRL_TOKEN=reshapr-bench-token \
QUARKUS_OTEL_SDK_DISABLED=true QUARKUS_HTTP_PORT="$PROXY_PORT" \
"$JAVA" --enable-preview -XX:+UseCompactObjectHeaders -Xms"$PROXY_HEAP" -Xmx"$PROXY_HEAP" \
  ${PROXY_JVM_EXTRA_OPTS:+$PROXY_JVM_EXTRA_OPTS} \
  -Dreshapr.infinispan.stack=reshapr-local \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -jar "$REPO_ROOT/proxy/target/quarkus-app/quarkus-run.jar" \
  > "$RESULTS_DIR/proxy.log" 2>&1 &
PROXY_PID=$!
wait_for "proxy readiness" "curl -sf http://localhost:$PROXY_PORT/q/health/ready"
echo "Proxy ready (pid $PROXY_PID)."

# --- 3. k6 phases ----------------------------------------------------------------------------
run_k6() {
  local payload="$1" phase="$2" duration="$3" summary="$4"
  if [ "$K6_MODE" = "native" ]; then
    local extra=()
    [ -n "$summary" ] && extra+=(--summary-export "$summary")
    k6 run --quiet ${extra[@]+"${extra[@]}"} \
      -e BASE_URL="http://localhost:$PROXY_PORT" -e PAYLOAD="$payload" -e PHASE="$phase" \
      -e RATE="$LATENCY_RATE" -e VUS="$THROUGHPUT_VUS" -e DURATION="$duration" \
      "$BENCH_DIR/e2e/k6/graphql-tools-call.js"
  else
    local docker_summary=()
    [ -n "$summary" ] && docker_summary=(--summary-export "/results/$(basename "$summary")")
    docker run --rm -i --add-host=host.docker.internal:host-gateway \
      -v "$BENCH_DIR/e2e/k6:/scripts:ro" -v "$RESULTS_DIR:/results" \
      grafana/k6 run --quiet ${docker_summary[@]+"${docker_summary[@]}"} \
      -e BASE_URL="http://host.docker.internal:$PROXY_PORT" -e PAYLOAD="$payload" -e PHASE="$phase" \
      -e RATE="$LATENCY_RATE" -e VUS="$THROUGHPUT_VUS" -e DURATION="$duration" \
      /scripts/graphql-tools-call.js
  fi
}

for payload in "${PAYLOADS[@]}"; do
  echo
  echo "=== Payload: $payload ==="

  echo "--- warmup ($WARMUP_DURATION)"
  run_k6 "$payload" warmup "$WARMUP_DURATION" ""

  # Sample the proxy RSS during the two measured phases.
  MEM_FILE="$RESULTS_DIR/memory-$payload.txt"
  "$BENCH_DIR/e2e/sample-memory.sh" "$PROXY_PID" "$MEM_FILE" 1 &
  SAMPLER_PID=$!

  echo "--- latency (open model, $LATENCY_RATE req/s, $MEASURE_DURATION)"
  run_k6 "$payload" latency "$MEASURE_DURATION" "$RESULTS_DIR/latency-$payload.json"

  echo "--- throughput (closed model, $THROUGHPUT_VUS VUs, $MEASURE_DURATION)"
  run_k6 "$payload" throughput "$MEASURE_DURATION" "$RESULTS_DIR/throughput-$payload.json"

  kill "$SAMPLER_PID" 2>/dev/null || true
  wait "$SAMPLER_PID" 2>/dev/null || true
  SAMPLER_PID=""
done

# --- 4. Summary ------------------------------------------------------------------------------
echo
echo "================================================================================================"
printf "%-8s | %-30s | %-30s | %-20s\n" "payload" "latency @${LATENCY_RATE}rps (avg/p95/p99 ms)" "throughput @${THROUGHPUT_VUS}VUs (req/s, p95 ms)" "proxy RSS (avg/max MiB)"
echo "------------------------------------------------------------------------------------------------"
for payload in "${PAYLOADS[@]}"; do
  LAT_JSON="$RESULTS_DIR/latency-$payload.json"
  THR_JSON="$RESULTS_DIR/throughput-$payload.json"
  MEM_FILE="$RESULTS_DIR/memory-$payload.txt"

  LAT=$(jq -r '.metrics.http_req_duration | "\(.avg*100|round/100) / \(.["p(95)"]*100|round/100) / \(.["p(99)"]*100|round/100)"' "$LAT_JSON" 2>/dev/null || echo "n/a")
  LAT_ERR=$(jq -r '.metrics.checks | if .fails > 0 then " (FAILS: \(.fails))" else "" end' "$LAT_JSON" 2>/dev/null || echo "")
  THR=$(jq -r '"\(.metrics.http_reqs.rate|round) req/s, p95 \(.metrics.http_req_duration["p(95)"]*100|round/100)"' "$THR_JSON" 2>/dev/null || echo "n/a")
  THR_ERR=$(jq -r '.metrics.checks | if .fails > 0 then " (FAILS: \(.fails))" else "" end' "$THR_JSON" 2>/dev/null || echo "")
  MEM=$(awk '{ sum += $1; if ($1 > max) max = $1 } END { if (NR > 0) printf "%.0f / %.0f", sum/NR/1024, max/1024; else print "n/a" }' "$MEM_FILE" 2>/dev/null || echo "n/a")

  printf "%-8s | %-30s | %-30s | %-20s\n" "$payload" "$LAT$LAT_ERR" "$THR$THR_ERR" "$MEM"
done
echo "================================================================================================"
echo "Raw results in $RESULTS_DIR (k6 summaries, memory samples, proxy + environment logs)."
