# Claude Coach

A thin Claude chat client built with Spring Boot 3.5 / Java 21 and the official Anthropic Java SDK. Wraps the Anthropic Messages API, persists conversations as JSON Lines, and serves a static vanilla-JS UI.

## Features

- **Chat Interface** — Real-time conversations with Claude using the Messages API
- **Coach Personas** — Role-based coaching scenarios (COO, CFO, Therapist, etc.) with randomized scenario selection
- **Claude Architect Quiz** — Interactive exam mode with automatic question parsing and topic grounding from official Claude documentation
- **Spanish Vocabulary Practice** — Learn and drill Spanish vocabulary with hint masking and accuracy checking
- **Conversation Persistence** — All conversations stored as JSON Lines with soft-delete archiving
- **Model Selection** — Support for multiple Claude models with adaptive thinking and effort-based output control
- **Sticky State** — UI preserves coach selection and conversation history across browser sessions

## Quick Start

### Prerequisites

- Maven 3.8.4
- Java 21

### Running the app

```bash
# Set up your API key
echo "ANTHROPIC_API_KEY=sk-..." > .env.key

# Run the application (builds the reactor, then runs only coach-web)
./run.sh
# or, equivalently:
mvn -pl coach-web -am -DskipTests install && mvn -pl coach-web spring-boot:run
```

The app will start on the port configured in `coach-web/src/main/resources/application.yml` and serve the UI at `http://localhost:9999`.

### Running tests

```bash
mvn test
# Run a specific test
mvn test -Dtest=ChatApiTest#chatMintsConversationAndReturnsAnswer
```

## Documentation

See `CLAUDE.md` for:
- Detailed architecture and component descriptions
- Security guidelines (API key management)
- Testing approach (TDD with E2E integration tests)
- Development guidelines

## Architecture Overview

The request flow follows: **UI** (`static/script.js`) → **Controller** (`POST /api/chat`) → **Claude Client** (Anthropic Java SDK) → **Persistence** (JSON Lines) → **UI**.

Key components:
- **`ChatController`** — HTTP request handling and conversation routing
- **`ClaudeClient`** — Request shaping for model-specific parameters
- **`SdkAnthropicGateway`** — Anthropic API integration
- **`ConversationStore`** — JSON Lines persistence and archiving
- **`CoachService`** — Coach persona selection and scenario management

## License

Internal project.
