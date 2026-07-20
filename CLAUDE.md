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
  is enum + UI only (400 until prompts exist). CLAUDE_ARCHITECT uses a topic-grid
  flow: `GET /api/coaches/claude-architect/topics` returns sorted `.md` stems from
  `coaches/Claude/`; clicking a topic POSTs with `coachType=claude-architect` and
  `topic=<stem>` (blank message), storing `CoachMeta(CLAUDE_ARCHITECT, stem+".md",
  null)`; every turn resends `CLAUDE_PERSONA + topic file` as the system prompt.
  `QuestionParser.parse()` (D4: ≥5 non-blank lines, last 4 are A–D options in order,
  no other option lines, ≥1 stem line) populates the `question` field of
  `ChatResponse` and `MessageItem` (derived at read time, never persisted). The PDF
  exam guide is gitignored (`coaches/Claude/*.pdf`) and must never be committed.
  **字 word mode (ephemeral):** `word/WordSetStore` stores translated pairs in a
  `ConcurrentHashMap` keyed by UUID id, TTL 60 min, max 500 entries. `coach/Text`
  provides `normalizeKey` (NFD + strip diacritics + lowercase + trim). SPANISH with
  blank/null topic → `startSpanish(null)` → `CoachMeta(SPANISH, null, null)`,
  system prompt = `SPANISH_PERSONA` only (no topic clause). `parseWordList` splits
  on commas/newlines (dash-comment stripped per line before the comma split), trims, then strips leading/trailing non-letter chars from each entry (so wrapping `( )`, quotes, or list numbering don't leak into the stored word or grading).
  `maskHint` reveals the first `ceil(len/4)` chars of each word, masks the rest with
  `·` (U+00B7), preserves spaces. `pairTranslations` calls `SentenceParser`
  on the LLM output and matches echoed español back to the original tokens via
  `normalizeKey` with positional fallback. `WORD_TRANSLATE_SYSTEM` drives the
  translate step. Routes: `POST /api/spanish/words/translate` → `SpanishWordController`
  (returns `{setId, items:[{english,hint,spanish}]}` — the full `spanish` ships so the
  client can reveal it when the user clicks the hint icon); `POST /api/spanish/words/check`
  takes `{setId,answers,hintsUsed}` and grades by index (case/accent-insensitive, no LLM),
  returning `{results:[{english,spanish,correct,fullHint}]}`. Client tri-state: green =
  correct & no hint, yellow = correct but full hint, red = wrong; the review set carried
  into the next practice = red ∪ yellow (only clean-correct words drop). Neither endpoint
  writes JSONL or meta.json. The "practice missed" button (and the 語/字 toggle) POST
  `/api/chat {coachType:'spanish', message:words}` with no topic, seeding a persisted
  語 conversation with `OPENING_WITH_WORDS_NO_TOPIC`; "De nuevo 字" restarts a 字 quiz
  over all words.
- **`web/ChatController`** + `ApiExceptionHandler` — the nine route handlers (`/api/chat`
  has JSON + multipart overloads, plus the two 字 word routes); the handler maps
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
Most coverage lives in this E2E suite; prefer extending it over unit tests with
heavy mocking. Exceptions are self-contained logic and boundary classes tested in
isolation — `attach/MediaTypesTest` (pure function), `config/ApiKeyStartupCheckTest`
(`ApplicationContextRunner`), and the `docs/` trio — `DocsServiceTest`,
`DocsCacheTest`, `DocFetchGatewayTest` (Mockito, `@TempDir`, in-process
`com.sun.net.httpserver.HttpServer`).

## Pull requests

Use the original commit message(s) verbatim as the PR title and body. Do not
paraphrase or rewrite them. If the PR covers multiple commits, use the first
commit's subject as the title and concatenate all commit messages (subject +
body) in the PR description.
