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
exec mvn -pl coach-mcp -am spring-boot:run
