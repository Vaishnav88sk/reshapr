#!/usr/bin/env bash
# Pre-requisites:
# - Build proxy first: cd .. && ./mvnw install -DskipTests -pl proxy -am
# - Package the benchmark jar: mvn clean package
# - (optional) install jq for result summarization: sudo apt install jq

set -e

# Run the benchmark and save the results to a JSON file
java --enable-preview -jar target/benchmarks.jar GrpcGetCallResponseBenchmark \
     -prof gc -rf json -rff results-grpc-converter.json

# Tip: to limit the number of scenario and parameters, you can use the -p option to specify parameters. For example:
#java --enable-preview -jar target/benchmarks.jar "GrpcGetCallResponseBenchmark.warmCacheFreshConverter" \
#    -p refStyle=EXTERNAL -p specSize=SMALL -prof gc -rf json -rff results-grpc-converter.json

# Available parameters for this benchmark:
# - operationCount: 6,30,80
# - specSize: SMALL,MEDIUM,LARGE
# - refStyle: EXTERNAL,INLINE

# Summarize the results in a human-readable table format
echo ""
echo "-------------------------------------"
echo "gRPC Converter Benchmark Results:"
echo "-------------------------------------"
./summarize.sh results-grpc-converter.json
