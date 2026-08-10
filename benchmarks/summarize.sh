# Summarize the results in a human-readable table format
set -euo pipefail
FILE="${1:-results.json}"
command -v jq >/dev/null || { echo "jq is required (brew install jq)" >&2; exit 1; }
[[ -f "$FILE" ]] || { echo "file '$FILE' not found" >&2; exit 1; }
jq -e 'type == "array" and length > 0' "$FILE" >/dev/null 2>&1 \
  || { echo "file '$FILE' is empty or not a JMH JSON result (interrupted run?)" >&2; exit 1; }

# Header: BENCHMARK + one column per @Param found in the file + metrics.
HEADER=$(jq -r '
  (map(.params // {} | keys) | add // [] | unique) as $keys
  | (["BENCHMARK"] + ($keys | map(ascii_upcase)) + ["TIME(us/op)", "ERR(±)", "ALLOC(B/op)"]) | @tsv
' "$FILE")

# One row per benchmark/params combination.
ROWS=$(jq -r '
  (map(.params // {} | keys) | add // [] | unique) as $keys
  | .[]
  | ([(.benchmark | split(".") | last)]
     + (. as $e | $keys | map($e.params[.] // "-"))
     + [(.primaryMetric.score * 100 | round / 100),
        ((.primaryMetric.scoreError // 0) | if type == "number" then (. * 100 | round / 100) else "-" end),
        ((.secondaryMetrics["gc.alloc.rate.norm"].score
          // .secondaryMetrics["\u00b7gc.alloc.rate.norm"].score
          // null) | if . == null then "-" else round end)])
  | @tsv
' "$FILE" | sort -t$'\t')

{ echo "$HEADER"; echo "$ROWS"; } | column -t -s$'\t'

