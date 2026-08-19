# Pre-requisites:
# - package the benchmark jar (mvn clean package)
# - install jq (brew install jq)

# Run the benchmark and save the results to a JSON file
java --enable-preview -jar target/benchmarks.jar GraphQLGetCallResponseBenchmark \
     -prof gc -rf json -rff results-graphql.json

# Tip: to limit the number of scenario and parameters, you can use the -p option to specify parameters. For example:
#java --enable-preview -jar target/benchmarks.jar "GraphQLGetCallResponseBenchmark.warmCacheFreshConverter" \
#    -p converterImpl=current,optimized -prof gc -rf json -rff results-graphql.json

# Available parameters for this benchmark:
# - specPath: GraphQL schema, classpath resource or filesystem path (default: github-api.graphql, bundled in src/main/resources)
# - converterImpl: current,optimized

# Summarize the results in a human-readable table format
echo "\n-------------------------"
echo "GraphQL Benchmark Results:"
echo "\n-------------------------"
./summarize.sh results-graphql.json

