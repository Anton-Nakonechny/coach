#!/bin/bash
# Start the Coach web app (REST API + UI). The port is configured in
# coach-web/src/main/resources/application.yml (9999).
#
# The Anthropic API key is loaded by Spring itself, via spring.config.import in
# application.yml (the gitignored .env.key / .env).
set -euo pipefail
cd "$(dirname "$0")"

echo "Starting Coach web app ..."
exec mvn -pl coach-web -am spring-boot:run
