#!/bin/bash

# Single standalone gateway (mono-node): the embedded Infinispan replicated MCP state runs as a
# single-node cluster using the image default JGroups stack "reshapr-local" (set in Dockerfile.jvm).
docker run -it --rm -p 7777:7777 -e RESHAPR_CTRL_HOST=host.docker.internal \
  -e RESHAPR_CTRL_PORT=5555 \
  -e RESHAPR_CTRL_TOKEN=reshapr-my-super-secret-token-xyz \
  --add-host=host.docker.internal:host-gateway \
  registry.reshapr.io/reshapr/reshapr-proxy:nightly