#!/usr/bin/env bash
#
# Samples the RSS (resident set size) of a process at a fixed interval and appends one value in
# KiB per line to an output file. Used by run-e2e-rest-bench.sh to track the proxy memory
# consumption while k6 drives the load.
#
# Usage: sample-memory.sh <pid> <outfile> [interval-seconds (default 1)]
set -euo pipefail

PID="${1:?usage: sample-memory.sh <pid> <outfile> [interval]}"
OUTFILE="${2:?usage: sample-memory.sh <pid> <outfile> [interval]}"
INTERVAL="${3:-1}"

: > "$OUTFILE"
while kill -0 "$PID" 2>/dev/null; do
  RSS_KB=$(ps -o rss= -p "$PID" 2>/dev/null | tr -d ' ') || break
  [ -n "$RSS_KB" ] && echo "$RSS_KB" >> "$OUTFILE"
  sleep "$INTERVAL"
done
