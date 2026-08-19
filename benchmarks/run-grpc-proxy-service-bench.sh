#!/usr/bin/env bash
# Pre-requisites:
# - Build proxy first: cd .. && ./mvnw install -DskipTests -pl proxy -am
# - Package the benchmark jar: mvn clean package
# - (optional) install jq for result summarization: sudo apt install jq

set -e

# Run 1 – sequential, vary request payload size.
java --enable-preview -jar target/benchmarks.jar "GrpcProxyServiceCallBackendBenchmark.callBackend_noConcurrency" \
     -p requestPayload=SMALL,MEDIUM,LARGE -p secretMode=NONE -p grpcProxyImpl=current \
     -prof gc -rf json -rff results-grpc-proxy-payloads.json

# Run 2 – sequential, with and without a Bearer token secret.
java --enable-preview -jar target/benchmarks.jar "GrpcProxyServiceCallBackendBenchmark.callBackend_noConcurrency" \
     -p requestPayload=SMALL -p secretMode=NONE,TOKEN -p grpcProxyImpl=current \
     -prof gc -rf json -rff results-grpc-proxy-secrets.json

# Run 3 – concurrency axis at the nominal point (small payload, no secret).
java --enable-preview -jar target/benchmarks.jar "GrpcProxyServiceCallBackendBenchmark.callBackend_.*" \
     -p requestPayload=SMALL -p secretMode=NONE -p grpcProxyImpl=current \
     -prof gc -rf json -rff results-grpc-proxy-concurrency.json

# Merge all runs into a single results file.
jq -s 'add' results-grpc-proxy-payloads.json results-grpc-proxy-secrets.json results-grpc-proxy-concurrency.json \
   > results-grpc-proxy.json

# Tip: to compare the current and optimized implementations on a given scenario, run e.g.:
#java --enable-preview -jar target/benchmarks.jar "GrpcProxyServiceCallBackendBenchmark.callBackend_mediumConcurrency" \
#     -p requestPayload=MEDIUM -p secretMode=TOKEN \
#     -p grpcProxyImpl=current,optimized -prof gc -rf json -rff results-grpc-proxy.json

# Available parameters:
# - requestPayload: SMALL,MEDIUM,LARGE  (~50 B / ~500 B / ~2 KiB JSON HelloRequest body)
# - grpcProxyImpl: current,optimized    (new channel per call vs. pooled channels)
# - secretMode: NONE,TOKEN              (no secret vs. Bearer token backend secret)
# Concurrency is selected via the method name suffix:
#   callBackend_noConcurrency (1 thread), callBackend_lowConcurrency (4),
#   callBackend_mediumConcurrency (16), callBackend_highConcurrency (64).

echo ""
echo "------------------------------"
echo "gRPC Proxy Benchmark Results:"
echo "------------------------------"
./summarize.sh results-grpc-proxy.json
