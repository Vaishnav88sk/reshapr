#!/usr/bin/env bash
# Summarize the E2E benchmark results (produced by run-e2e-rest-bench.sh) in 3 tables:
# latency under nominal load, max throughput, and proxy memory (RSS).
set -euo pipefail
DIR="${1:-results-e2e-rest}"
command -v jq >/dev/null || { echo "jq is required (brew install jq)" >&2; exit 1; }
[[ -d "$DIR" ]] || { echo "directory '$DIR' not found" >&2; exit 1; }

# Payloads are auto-detected from the latency-<payload>.json files, kept in size order.
PAYLOADS=()
for p in small medium large; do
  [[ -f "$DIR/latency-$p.json" ]] && PAYLOADS+=("$p")
done
[[ ${#PAYLOADS[@]} -gt 0 ]] || { echo "no latency-*.json files found in '$DIR'" >&2; exit 1; }

trend() { # trend <file> -> avg med p90 p95 p99 max (ms, 2 decimals)
  jq -r '.metrics.http_req_duration
    | [.avg, .med, ."p(90)", ."p(95)", ."p(99)", .max]
    | map(. * 100 | round / 100) | @tsv' "$1"
}

echo "== Latency under nominal load (open model, constant arrival rate) =="
{
  printf 'PAYLOAD\tAVG(ms)\tMED\tP90\tP95\tP99\tMAX\n'
  for p in "${PAYLOADS[@]}"; do
    printf '%s\t%s\n' "$p" "$(trend "$DIR/latency-$p.json")"
  done
} | column -t -s$'\t'
echo

echo "== Max throughput (closed model, constant VUs) =="
{
  printf 'PAYLOAD\tREQ/S\tAVG(ms)\tMED\tP90\tP95\tP99\tMAX\tRECV(MiB/s)\n'
  for p in "${PAYLOADS[@]}"; do
    f="$DIR/throughput-$p.json"
    printf '%s\t%s\t%s\t%s\n' "$p" \
      "$(jq -r '.metrics.http_reqs.rate | round' "$f")" \
      "$(trend "$f")" \
      "$(jq -r '.metrics.data_received.rate / 1048576 * 100 | round / 100' "$f")"
  done
} | column -t -s$'\t'
echo

echo "== Proxy memory (RSS sampled during measured phases) =="
{
  printf 'PAYLOAD\tAVG(MiB)\tMAX(MiB)\tSAMPLES\n'
  for p in "${PAYLOADS[@]}"; do
    f="$DIR/memory-$p.txt"
    if [[ -s "$f" ]]; then
      awk -v p="$p" '{s+=$1; if($1>m)m=$1; n++}
        END{printf "%s\t%.0f\t%.0f\t%d\n", p, s/n/1024, m/1024, n}' "$f"
    else
      printf '%s\t-\t-\t0\n' "$p"
    fi
  done
} | column -t -s$'\t'
echo

# Sanity: report any failed requests or failed checks across all phases.
FAILURES=$(jq -rs '[.[] | .metrics.http_req_failed.value // 0] | add' "$DIR"/latency-*.json "$DIR"/throughput-*.json)
if [[ "$FAILURES" != "0" ]]; then
  echo "WARNING: some requests failed (http_req_failed sum = $FAILURES) — check $DIR/proxy.log" >&2
else
  echo "All requests succeeded (0 failed) across latency + throughput phases."
fi
