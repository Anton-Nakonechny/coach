#!/bin/bash
# Start the Coach web app (REST API + UI). The port is configured in
# coach-web/src/main/resources/application.yml (9999).
#
# The Anthropic API key is loaded by Spring itself, via spring.config.import in
# application.yml (the gitignored .env.key / .env).
set -euo pipefail
cd "$(dirname "$0")"

echo "Starting Coach web app ..."
# `spring-boot:run` is a direct CLI goal, so `-am` would also run it on coach-parent /
# coach-core (no main class) and fail before the web app boots. Build the reactor once
# with `-am install`, then run ONLY coach-web without `-am`.
mvn -pl coach-web -am -DskipTests clean install
exec mvn -pl coach-web spring-boot:run
