# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

A thin Claude chat client built with Spring Boot 3.5 / Java 21 and the official
Anthropic Java SDK. Wraps the Anthropic Messages API, persists conversations as
JSON Lines, and serves a static vanilla-JS UI.

## Commands

Maven (3.8.4) + Java 21. Dependencies resolve against Maven Central.

```bash
mvn test           # run the JUnit 5 suite
mvn spring-boot:run # run the app (port set in src/main/resources/application.yml)
./run.sh            # same; Spring loads the key from .env.key
mvn test -Dtest=ChatApiTest#chatMintsConversationAndReturnsAnswer  # single test
```

The app reads `ANTHROPIC_API_KEY` from the environment; the test suite does not
(the SDK gateway is faked).

## Security

Never commit `.env` or `.env.key` — both are gitignored and hold secrets. Use
`.env.key.example` as the committed template. Spring loads the key from `.env.key`
(then `.env`, then the same two in the parent dir) via `spring.config.import` in
`application.yml` — plain unquoted `KEY=value`, parsed as a `.properties` file. Real
environment variables and system properties still take precedence over the file.
`ApiKeyStartupCheck` logs a startup warning when no key is present (the UI and
`/api/models` still work; only `/api/chat` needs one).

## Architecture

Request flow: `static/script.js` → `POST /api/chat` → `ChatController` persists the
user turn, calls `ClaudeClient.generate()`, persists the assistant turn, returns
the answer. The Anthropic API is stateless, so **every turn resends the full
history** rebuilt from disk.

- **`model/ModelsConfig`** — source of truth. The ordered `MODELS` list drives both
  the `/api/models` UI payload and per-model request shaping; `supportsEffort` /
  `adaptiveThinking` flags encode the "ignored if not applicable" rule. Adding,
  removing, or reordering a model is a single-file change here.
- **`anthropic/ClaudeClient`** — request shaping. Adds `thinking:{type:adaptive}`
  and `output_config:{effort}` only for models whose flags allow them, then
  delegates to **`SdkAnthropicGateway`**. Returns only concatenated `text` blocks.
- **`anthropic/SdkAnthropicGateway`** — sends one Messages request per turn.
  `thinking`/`output_config` go in via `putAdditionalBodyProperty` (raw JSON),
  the Java analog of Python's `extra_body`. Tests replace it with a `@MockitoBean`.
- **`store/ConversationStore`** — JSON-Lines persistence: one
  `<conversations-dir>/<id>.jsonl` per conversation. Soft-delete archives one file
  to gzip or all of them to a single timestamped zip under `archive/`. A coach
  conversation also has a `<id>.meta.json` sidecar (coachType + promptFile),
  removed on archive/delete; the sidebar listing derives coach conversations'
  `preview` ("COO · kpi okr") and `coachType` from it instead of the first message.
- **`coach/CoachService`** — coach personas. A new chat with `coachType` picks a
  random scenario `.md` under `<coaches-dir>/<coach folder>` (READMEs/dotfiles
  excluded), persists the pick via the sidecar, and every turn resends
  persona + whole scenario file as the `system` prompt. The first user turn is the
  synthetic `OPENING_INSTRUCTION`, stored and displayed like any message. SPANISH
  is enum + UI only (400 until prompts exist).
- **`web/ChatController`** + `ApiExceptionHandler` — the seven route handlers (`/api/chat`
  has JSON + multipart overloads); the handler maps
  errors to `{"message": ...}` (FastAPI used `{"detail": ...}`) with idiomatic Spring
  codes (400 / 404 / 500 — Bean Validation failures return 400, where FastAPI returned 422).
- **`config/AppConfig`** — `@ConfigurationProperties(coach.*)`: api key, `maxTokens`
  (16000), `conversationsDir`.

### Design decisions

- **JSON is camelCase** (`conversationId`, `supportsEffort`, `effortLevels`…), not
  snake_case. The copied `script.js` was updated to match.
- **Effort is not defaulted server-side** — a missing `effort` is simply not sent;
  the frontend seeds its own default from `/api/models` (`defaultEffort`).

## Testing approach (TDD)

`src/test/java/com/coach/ChatApiTest.java` is a black-box E2E suite
(`@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`) running the whole owned stack
for real, with JSONL persistence redirected to a JUnit temp dir. Only the two
Anthropic boundaries are faked via `@MockitoBean` (`SdkAnthropicGateway` and
`SdkFileUploadGateway`), driven with `doAnswer`/`doThrow` inline. Follow Red →
Green → Refactor: write the failing test first, get sign-off, then implement.
Prefer extending this E2E style over unit tests with heavy mocking.

## Pull requests

Use the original commit message(s) verbatim as the PR title and body. Do not
paraphrase or rewrite them. If the PR covers multiple commits, use the first
commit's subject as the title and concatenate all commit messages (subject +
body) in the PR description.
