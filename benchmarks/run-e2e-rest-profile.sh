#!/usr/bin/env bash
# Profiled variant of the E2E REST benchmark: runs a single payload campaign with a
# Java Flight Recorder session attached to the proxy under test, then prints where the
# CPU time and allocations go (via `jfr print`).
#
# Usage: ./run-e2e-rest-profile.sh [payload]      (default: large)
# Tunables: same env vars as run-e2e-rest-bench.sh (LATENCY_RATE, THROUGHPUT_VUS, ...).
#
# Output: results-e2e-rest/proxy-<payload>.jfr plus the usual k6/memory files.
# Open the .jfr with JDK Mission Control (jmc) for flame graphs, or inspect with:
#   jfr print --events jdk.CPULoad,jdk.GCPhasePause proxy-large.jfr
#   jfr view allocation-by-class proxy-large.jfr
#   jfr view native-methods proxy-large.jfr
set -euo pipefail

cd "$(dirname "$0")"
PAYLOAD="${1:-large}"
RESULTS_DIR="$(pwd)/results-e2e-rest"
JFR_FILE="$RESULTS_DIR/proxy-$PAYLOAD.jfr"
mkdir -p "$RESULTS_DIR"
rm -f "$JFR_FILE"

# 'profile' settings: CPU sampling + allocation profiling + GC + thread parking events.
export PROXY_JVM_EXTRA_OPTS="-XX:StartFlightRecording=settings=profile,filename=$JFR_FILE,dumponexit=true \
-XX:FlightRecorderOptions=stackdepth=128"

./run-e2e-rest-bench.sh "$PAYLOAD"

echo
echo "=== JFR quick report ($JFR_FILE) ==="
# Resolve the same JDK 25 used by the main script.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jfr" ]; then
  JFR_BIN="$JAVA_HOME/bin/jfr"
elif [ -x "$HOME/.sdkman/candidates/java/25.0.1-tem/bin/jfr" ]; then
  JFR_BIN="$HOME/.sdkman/candidates/java/25.0.1-tem/bin/jfr"
else
  JFR_BIN="jfr"
fi

echo
echo "--- Top allocations by class ---"
"$JFR_BIN" view allocation-by-class "$JFR_FILE" 2>/dev/null | head -20 || true
echo
echo "--- GC pauses ---"
"$JFR_BIN" view gc-pauses "$JFR_FILE" 2>/dev/null | head -15 || true
echo
echo "--- Hottest methods (execution samples) ---"
"$JFR_BIN" view hot-methods "$JFR_FILE" 2>/dev/null | head -25 || true
echo
echo "Full analysis: open '$JFR_FILE' with JDK Mission Control (jmc) for flame graphs."
