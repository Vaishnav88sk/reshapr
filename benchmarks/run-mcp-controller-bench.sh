# Pre-requisites:
# - package the benchmark jar (mvn clean package)
# - install jq (brew install jq)

# Run the benchmark and save the results to a JSON file
java --enable-preview -jar target/benchmarks.jar McpControllerToolsCallBenchmark \
     -prof gc -rf json -rff results-mcp-controller.json

# Tip: to limit the number of scenario and parameters, you can use the -p option to specify parameters. For example:
#java --enable-preview -jar target/benchmarks.jar "McpControllerToolsCallBenchmark.toolsCall" \
#    -p protocolMode=MODERN -p payloadSize=SMALL,LARGE -p controllerImpl=current,optimized \
#    -prof gc -rf json -rff results-mcp-controller.json

# Available parameters for this benchmark:
# - protocolMode: LEGACY,MODERN
# - payloadSize: SMALL,MEDIUM,LARGE
# - headerCount: SMALL,MEDIUM,LARGE
# - audit: false,true (controller-side audit overhead; the OTEL emission itself is stubbed out)
# - controllerImpl: current,optimized (register more implementations in ControllerFactory.FACTORIES)

# Summarize the results in a human-readable table format
echo "\n---------------------------------"
echo "McpController Benchmark Results:"
echo "\n---------------------------------"
./summarize.sh results-mcp-controller.json

