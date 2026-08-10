# Pre-requisites:
# - package the benchmark jar (mvn clean package)
# - install jq (brew install jq)

# Run the benchmark and save the results to a JSON file
java --enable-preview -jar target/benchmarks.jar OpenAPIGetCallResponseBenchmark \
     -prof gc -rf json -rff results-openapi.json

# Tip: to limit the number of scenario and parameters, you can use the -p option to specify parameters. For example:
#java --enable-preview -jar target/benchmarks.jar "OpenAPIGetCallResponseBenchmark.warmCacheFreshConverter" \
#    -p refStyle=EXTERNAL -p converterImpl=current,optimized -prof gc -rf json -rff results-openapi.json

# Available parameters for this benchmark:
# - operationCount: 6,30,80
# - specSize: SMALL,MEDIUM,LARGE
# - pathDepth: SHALLOW,DEEP
# - refStyle: EXTERNAL,INTERNAL
# - converterImpl: current,optimized

# Summarize the results in a human-readable table format
echo "\n--------------------------"
echo "OpenAPI Benchmark Results:"
echo "\n--------------------------"
./summarize.sh results-openapi.json