# Pre-requisites:
# - package the benchmark jar (mvn clean package)
# - install jq (brew install jq)

# The full parameter matrix (3x3x3x3 params x 2 impls x 4 concurrency levels = 648 benchmarks)
# would take hours. This baseline explores each axis independently around a nominal point
# (SMALL payloads, SMALL headers, no secret, no concurrency) in 3 focused runs, then merges
# the results into a single JSON file.

set -e

# Run 1 - payload axes: request x response payload sizes, sequential, no secret.
java --enable-preview -jar target/benchmarks.jar "ProxyServiceCallBackendBenchmark.callBackend_noConcurrency" \
     -p requestPayload=SMALL,MEDIUM,LARGE -p responsePayload=SMALL,MEDIUM,LARGE \
     -p headerCount=SMALL -p secretMode=NONE -p proxyImpl=current \
     -prof gc -rf json -rff results-proxy-payloads.json

# Run 2 - headers & secrets axes: sequential, small payloads.
java --enable-preview -jar target/benchmarks.jar "ProxyServiceCallBackendBenchmark.callBackend_noConcurrency" \
     -p requestPayload=SMALL -p responsePayload=SMALL \
     -p headerCount=SMALL,MEDIUM,LARGE -p secretMode=NONE,TOKEN,BASIC -p proxyImpl=current \
     -prof gc -rf json -rff results-proxy-headers-secrets.json

# Run 3 - concurrency axis: 1 / 4 / 16 / 64 threads on the nominal point.
java --enable-preview -jar target/benchmarks.jar "ProxyServiceCallBackendBenchmark.callBackend_.*" \
     -p requestPayload=SMALL -p responsePayload=SMALL \
     -p headerCount=SMALL -p secretMode=NONE -p proxyImpl=current \
     -prof gc -rf json -rff results-proxy-concurrency.json

# Merge all runs into a single result file.
jq -s 'add' results-proxy-payloads.json results-proxy-headers-secrets.json results-proxy-concurrency.json \
   > results-proxy.json

# Tip: to compare the current and optimized implementations on a given scenario, run e.g.:
#java --enable-preview -jar target/benchmarks.jar "ProxyServiceCallBackendBenchmark.callBackend_mediumConcurrency" \
#     -p requestPayload=MEDIUM -p responsePayload=MEDIUM -p headerCount=MEDIUM -p secretMode=TOKEN \
#     -p proxyImpl=current,optimized -prof gc -rf json -rff results-proxy.json

# Available parameters for this benchmark:
# - requestPayload: SMALL,MEDIUM,LARGE     (~100 B / ~10 KiB / ~500 KiB request body)
# - headerCount: SMALL,MEDIUM,LARGE        (3 / 12 / 40 incoming headers)
# - secretMode: NONE,TOKEN,BASIC           (backend authentication secret)
# - responsePayload: SMALL,MEDIUM,LARGE    (~100 B / ~10 KiB / ~500 KiB response body)
# - proxyImpl: current,optimized
# Concurrency is selected via the method name: callBackend_noConcurrency (1 thread),
# callBackend_lowConcurrency (4), callBackend_mediumConcurrency (16), callBackend_highConcurrency (64).

# Summarize the results in a human-readable table format (score is throughput in ops/s)
echo "\n--------------------------------"
echo "ProxyService Benchmark Results:"
echo "--------------------------------"
./summarize.sh results-proxy.json

