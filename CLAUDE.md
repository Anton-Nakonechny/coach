# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

A thin Claude chat client built with Spring Boot 3.5 / Java 21 and the official
Anthropic Java SDK. Wraps the Anthropic Messages API, persists conversations as
JSON Lines, and serves a static vanilla-JS UI.

It is a **multi-module Maven build** (parent `coach-parent`): a shared library
`coach-core` and two independently runnable Spring Boot apps — `coach-web` (REST
API + UI, port 9999) and `coach-mcp` (MCP server, port 9998) — each depending on
`coach-core`. Both apps keep the base package `com.coach`, so each one's
`@SpringBootApplication` component-scan picks up the `com.coach.*` beans from
`coach-core` on the classpath while never seeing the other app (it isn't a
dependency). That boundary is what keeps the Anthropic SDK out of `coach-mcp` and
the MCP stack out of `coach-web`.

## Commands

Maven (3.8.4) + Java 21. Dependencies resolve against Maven Central. Run these
from the repo root; the app modules configure `spring-boot:run` to work from the
root dir so relative `coaches/` / `conversations/` / `.env.key` resolve.

```bash
mvn test                           # run the whole reactor's JUnit 5 suites
npx playwright test                # run the static-UI e2e suite (separate from mvn test)
./run.sh                           # coach-web (9999); Spring loads the key from .env.key
./run-mcp.sh                       # coach-mcp (9998); no API key needed
mvn -pl coach-web test -Dtest=ChatApiTest#chatMintsConversationAndReturnsAnswer  # single test
```

`run.sh` / `run-mcp.sh` build the reactor once (`mvn -pl coach-X -am -DskipTests
install`) and then run only the leaf module (`mvn -pl coach-X spring-boot:run`, no
`-am`). Don't collapse that into `mvn -pl coach-X -am spring-boot:run`: `spring-boot:run`
is a direct CLI goal, so `-am` runs it on `coach-parent` / `coach-core` too — they have
no main class and the build fails before the app boots.

`coach-web` reads `ANTHROPIC_API_KEY` from the environment; `coach-mcp` never
makes an LLM call so needs no key; the test suites don't need one (the SDK gateway
is faked).

## Security

Never commit `.env` or `.env.key` — both are gitignored and hold secrets. Use
`.env.key.example` as the committed template. Spring loads the key from `.env.key`
(then `.env`, then the same two in the parent dir) via `spring.config.import` in
`application.yml` — plain unquoted `KEY=value`, parsed as a `.properties` file. Real
environment variables and system properties still take precedence over the file.
`ApiKeyStartupCheck` logs a startup warning when no key is present (the UI and
`/api/models` still work; only `/api/chat` needs one).

Never commit `coach-web/src/main/resources/application.yml` either — it's
gitignored so a developer's real `coach.noam.base-url` (e.g. a LAN address/hostname
when noam runs on another machine) never reaches the public repo. Use
`application.yml.example` (same directory) as the committed template: copy it to
`application.yml` before running coach-web, then edit `coach.noam.base-url`
locally. `coach.noam.profile-id`/`user-id` can stay as the example's placeholder
UUIDs unless your local noam instance requires real ones.

## Architecture

### Modules

- **`coach-core`** — shared library (plain jar, no `spring-boot-maven-plugin`
  repackage, no Anthropic SDK, no web controllers). Holds `coach/` (incl.
  `InvalidRequestException`), `docs/`, `model/`, `word/`, `config/AppConfig`, and
  the shared parsed-content records in `dto/` (`SentenceItem`, `TopicSection`,
  `QuizQuestion`, `QuizOption`). Needs `jackson-annotations` for the `@JsonValue`
  enums (`CoachType`, `ModelKey`); full Jackson comes from the app modules.
- **`coach-web`** — REST API + static UI (port 9999). `anthropic/`, `attach/`,
  `noam/` (`NoamGateway`, the only class here that talks to noam), `store/`,
  `web/` (controllers + request/response DTOs), `config/`
  (`AnthropicClientConfig`, `ApiKeyStartupCheck`), main `CoachWebApplication`.
- **`coach-mcp`** — MCP server (port 9998). Just `mcp/` + main
  `CoachMcpApplication`; reuses `coach-core` and never touches the Anthropic
  gateways or persistence.

Paths below name packages under `com.coach.*`; find them in the module that owns
the package per the list above.

Request flow (`coach-web`): `static/script.js` → `POST /api/chat` →
`ChatController` persists the user turn, calls `ClaudeClient.generate()`, persists
the assistant turn, returns the answer. The Anthropic API is stateless, so **every
turn resends the full history** rebuilt from disk.

Whenever a static JS file changes (`script.js`, `noam.js`), bump its own
cache-bust query string in `static/index.html` (`<script src="script.js?v=N">`,
increment `N`). Each file has its own independent `v`; only bump the one(s) you
touched. Without it, returning users keep the browser-cached old file and never
see the fix.

- **`model/ModelsConfig`** — source of truth. The ordered `MODELS` list drives both
  the `/api/models` UI payload and per-model request shaping; `supportsEffort` /
  `adaptiveThinking` flags encode the "ignored if not applicable" rule. Adding,
  removing, or reordering a model is a single-file change here.
- **`anthropic/ClaudeClient`** — request shaping. Adds `thinking:{type:adaptive}`
  and `output_config:{effort}` only for models whose flags allow them, then
  delegates to **`SdkAnthropicGateway`**. Returns only concatenated `text` blocks.
- **`anthropic/SdkAnthropicGateway`** — sends one Messages request per turn.
  `thinking`/`output_config` go in via `putAdditionalBodyProperty` (raw JSON),
  the Java analog of Python's `extra_body`. The system prompt is sent as a
  `cache_control: ephemeral` block so a conversation's follow-up turns hit the
  prompt cache. Tests replace it with a `@MockitoBean`; the params factory
  (`buildParams`) is package-private for the unit test beyond that boundary.
- **`docs/DocsService` + `docs/DocFetchGateway`** — official-doc grounding for
  CLAUDE_ARCHITECT. A topic file may start with `<!-- sources: -->` front-matter
  listing official doc URLs (platform.claude.com / code.claude.com serve pages as
  markdown; llms.txt is the index). Each turn the front-matter is stripped and the
  pages are appended to the system prompt as `=== OFFICIAL DOCUMENTATION … ===`
  sections (plus a source-of-truth grounding preamble), capped at
  `coach.docs.max-chars`. Pages are snapshotted under `coach.docs.cache-dir`
  (`coaches/Claude/docs/`, gitignored — Anthropic content, never commit) with
  `coach.docs.ttl` freshness; resolution order is fresh snapshot → fetch &
  snapshot → stale snapshot → skip, so the quiz degrades to blueprint-only
  instead of failing. `DocFetchGateway` is the third `@MockitoBean` boundary.
- **`mcp/McpServerConfig` + `mcp/ClaudeQuizPrompt` + `mcp/CoachTopicsPrompt`** (the whole `coach-mcp` app) —
  a standalone MCP server (Streamable HTTP, MCP Java SDK `mcp-core` +
  `mcp-json-jackson2`) at `POST /mcp` on **port 9998**, its own
  `CoachMcpApplication` + minimal `application.yml`. The transport is a plain
  servlet registered at the exact mapping. Exposes two MCP prompts — prompts only
  by design: no MCP tools, and no MCP resources so the gitignored exam PDF and doc
  snapshots stay unreachable:
  - `claude-architect-quiz(topic)` (topic argument completable from `claudeTopics()`):
    `prompts/get` returns the same `CLAUDE_PERSONA` + topic file + official-docs text
    `CoachService.systemPrompt()` builds for the web chat, followed by the same
    random-bullet opening instruction, as a single user-role message — the MCP host
    (e.g. Claude Code after
    `claude mcp add --transport http coach http://localhost:9998/mcp`) owns the
    conversation and the LLM call. This app depends only on `coach-core`: the
    Anthropic SDK and persistence aren't on its classpath at all, and `coach-web` no
    longer serves `/mcp`. The topic value is quote-stripped and resolved by unique
    case-insensitive fragment (Claude Code splits slash-command args on whitespace
    and passes only the first token); unknown/missing/ambiguous topic → JSON-RPC `-32602`.
  - `topics` (no arguments — appears as `/coach:topics`): lists the sorted Claude
    Architect blueprint stems via `CoachService.claudeTopics()` as a single user-role
    message, so users can discover available topics without starting a quiz.
  `McpApiTest` (in `coach-mcp`) covers both prompts black-box (initialize handshake
  mints an `mcp-session-id` header; later JSON-RPC responses arrive as SSE `data:`
  lines); it fakes only the `DocFetchGateway` boundary — there are no Anthropic
  gateways in this module to mock.
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
  The opening user turn is `claudeOpeningInstruction()`: the plain "Ask me the
  first exam question." plus one randomly chosen `- ` bullet from the topic file
  (plain instruction when the file has no bullets), so first questions spread
  across the blueprint instead of converging on the model's modal pick.
  `QuestionParser.parse()` (D4: ≥5 non-blank lines, last 4 are A–D options in order,
  no other option lines, ≥1 stem line, at least one stem line containing `?` so an A–D
  feedback recap isn't misread as a new question) populates the `question` field of
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
  **文 documents mode (noam-sourced, ephemeral):** a third Español mode that studies
  vocabulary from **noam**, a sibling vocabulary-platform repo (REST API at
  `http://localhost:8080/api/v1` in dev). Flow: the 文 screen (Documentos tab — noam's
  documents, plus upload; Cola tab — the profile's spaced-repetition study queue) →
  a per-document/per-queue study-item list with study/known/ignored triage → Proceed
  flushes the triage marks to noam, then seeds a 字 quiz straight from the checked
  items' own noam translations via `POST /api/spanish/words/seed` (no LLM call —
  `SpanishWordController.seed` builds `WordPair`s from client-supplied
  `{lexemeId, spanish, english}` triples) → grades post back to noam on `/check` → a
  topic screen (the same grid `enterTopicSetup` uses) → 語 sentence practice on the
  missed words only. `WordPair` gained a nullable `lexemeId` (noam's lexeme id, null
  for a hand-typed list); ids live only in `WordSetStore` — 文 mode writes no JSONL
  and no `.meta.json` sidecar, and nothing is persisted until a 語 chat is actually
  started afterwards. Grading (`SpanishWordController.grade()`): correct with no hint
  → `GOOD`, correct with the full hint revealed → `HARD`, wrong → `AGAIN`; every
  graded `/check` call posts one review per lexeme-bearing word to noam via
  `NoamGateway.recordReview` (`source: EXAM`), including re-quizzes — a noam outage
  there is swallowed per word (logged, not thrown), since the set is single-use and
  one failed post must not cost the grades of every word after it. Transport split:
  reads (documents, study-items, the study queue) go browser→noam directly against
  `coach.noam.base-url`; writes (lexeme-states, reviews) go browser→coach-web→noam
  through `noam/NoamGateway` so noam's `userId` never reaches the browser —
  `GET /api/noam/config` hands the client only `{baseUrl, profileId}`. Config:
  `AppConfig.Noam` binds `coach.noam.base-url` / `profile-id` / `user-id`; both ids
  are hardcoded for v1, pending a `GET /profiles/{id}` lookup in noam. Degradation:
  if noam is unreachable (`GET /api/noam/config` fails, or the initial documents
  probe does), the 文 glyph is disabled (`probeNoamAvailability` /
  `disableDocumentsMode` in `noam.js`); a failed marks-flush on Proceed blocks it
  (shows an error, keeps the list up) since a 502 must never silently drop triage.
  Frontend split: `static/noam.js` holds all 文-mode JS, loaded after `script.js` in
  `index.html` and reusing its top-level globals (`API_URL`, `chatMessages`,
  `resetToSetup`, …); `script.js` itself keeps only the 語/字 flows. coach-web uses
  only noam's pre-existing endpoints and enum values — noam also needs a CORS
  allowance for the coach origin, tracked in the noam repo, not here.
- **`web/ChatController`** + `ApiExceptionHandler` — the REST route handlers (`/api/chat`
  has JSON + multipart overloads; the three 字/文 word routes — `translate`, `seed`,
  `check` — live on `SpanishWordController`, and the two noam routes —
  `GET /api/noam/config`, `POST /api/noam/lexeme-states` — live on `web/NoamController`,
  which delegates writes to `noam/NoamGateway`); the handler maps
  errors to `{"message": ...}` (FastAPI used `{"detail": ...}`) with idiomatic Spring
  codes (400 / 404 / 500 — Bean Validation failures return 400, where FastAPI returned 422).
- **`web/TurnReplayGuard`** — both `/api/chat` overloads run through it. The browser's
  offline outbox replays turns whose answer was lost in transit (`fetch()` rejects with
  the same TypeError whether the request never left or the response did), so a turn may
  arrive twice; the optional `clientTurnId` on `ChatRequest` identifies it, and a repeat
  is served the first run's `ChatResponse` instead of appending the turn (or minting a
  conversation) again. The key is `clientTurnId` **plus `conversationId`**, not the id
  alone: the client rewrites a queued turn's `conversationId` when its chat is minted
  elsewhere (`adoptMintedConversation`), and that rewrite means "this turn belongs
  there now" — replaying the first run's answer would repoint the open chat at the
  conversation that run minted and split one chat in two. In-memory
  `ConcurrentHashMap` of `CompletableFuture`s, TTL 60 min,
  max 500 — same ephemeral shape as `WordSetStore`: a replay arriving while the first is
  still generating waits on it, a failed turn forgets its id so the retry goes through,
  and a restart forgets everything. A blank/absent id opts out.
- **`config/AppConfig`** — `@ConfigurationProperties(coach.*)`: api key, `maxTokens`
  (16000), `conversationsDir`.

### Design decisions

- **JSON is camelCase** (`conversationId`, `supportsEffort`, `effortLevels`…), not
  snake_case. The copied `script.js` was updated to match.
- **Effort is not defaulted server-side** — a missing `effort` is simply not sent;
  the frontend seeds its own default from `/api/models` (`defaultEffort`).
- **noam reads are browser-direct, writes go through `coach-web`** — a GET needs no
  server involvement, but every write that could leak noam's `userId`
  (lexeme-states, reviews) routes through `noam/NoamGateway` so the id never reaches
  the browser; `GET /api/noam/config` hands the client only `{baseUrl, profileId}`.

## Testing approach (TDD)

`coach-web/src/test/java/com/coach/ChatApiTest.java` is a black-box E2E suite
(`@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`) running the whole owned stack
for real, with JSONL persistence redirected to a JUnit temp dir. Four boundaries
are faked via `@MockitoBean` (`SdkAnthropicGateway`, `SdkFileUploadGateway`,
`DocFetchGateway`, `NoamGateway`), driven with `doAnswer`/`doThrow` inline. Follow
Red → Green → Refactor: write the failing test first, get sign-off, then implement.
Most coverage lives in this E2E suite; prefer extending it over unit tests with
heavy mocking. Exceptions are self-contained logic and boundary classes tested in
isolation — `coach-web` `attach/MediaTypesTest` (pure function),
`config/ApiKeyStartupCheckTest` (`ApplicationContextRunner`), `noam/NoamGatewayTest`
(Mockito-free, an in-process `com.sun.net.httpserver.HttpServer`, mirroring
`DocFetchGatewayTest`'s pattern), and the `coach-core`
`docs/` trio — `DocsServiceTest`, `DocsCacheTest`, `DocFetchGatewayTest` (Mockito,
`@TempDir`, in-process `com.sun.net.httpserver.HttpServer`). `coach-mcp`'s
`McpApiTest` is the MCP-app E2E suite. `mvn test` at the root runs every module's
suite; scope to one with `-pl coach-web` / `-pl coach-mcp` / `-pl coach-core`.

**`mvn test` does not cover the static UI's client-side JS** (`script.js`,
`noam.js`) — `e2e/*.spec.js` (Playwright, config at `playwright.config.js`) is a
second, separate suite for that, run with `npx playwright test`. It boots the
real `coach-web` app (`webServer` in the config) and drives the browser DOM
directly — dispatch logic that lives only in the client (mode toggles, setup-screen
state, the offline outbox) is exercised here, not in the Java suite; 文 mode's own
specs are `noam-documents-flow.spec.js`, `noam-study-list.spec.js`, and
`noam-word-source-cache.spec.js`. A change to either JS file is not verified until
this suite has been run, even if `mvn test` and `node --check` both pass.

## Pull requests

Use the original commit message(s) verbatim as the PR title and body. Do not
paraphrase or rewrite them. If the PR covers multiple commits, use the first
commit's subject as the title and concatenate all commit messages (subject +
body) in the PR description.
