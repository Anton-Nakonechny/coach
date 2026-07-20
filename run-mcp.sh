#!/bin/bash
# Start the Coach MCP server (Streamable HTTP at POST /mcp). The port is configured in
# coach-mcp/src/main/resources/application.yml (9998). No Anthropic API key is needed —
# the MCP host owns the LLM call.
#
# Register with Claude Code:
#   claude mcp add --transport http coach http://localhost:9998/mcp
set -euo pipefail
cd "$(dirname "$0")"

echo "Starting Coach MCP server ..."
# `spring-boot:run` is a direct CLI goal, so `-am` would also run it on coach-parent /
# coach-core (no main class) and fail before the MCP app boots. Build the reactor once
# with `-am install`, then run ONLY coach-mcp without `-am`.
mvn -pl coach-mcp -am -DskipTests install
exec mvn -pl coach-mcp spring-boot:run
