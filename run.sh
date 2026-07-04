#!/bin/bash
# Start the Coach chat client. The port is configured in src/main/resources/application.yml.
#
# The Anthropic API key is loaded by Spring itself, via spring.config.import in
# application.yml (the gitignored .env.key / .env).
set -euo pipefail
cd "$(dirname "$0")"

echo "Starting Coach ..."
exec mvn spring-boot:run
