---
paths:
  - "coach-core/src/main/java/com/coach/{coach,word}/**"
  - "coach-web/src/main/java/com/coach/{noam,web}/**"
  - "coach-web/src/main/resources/static/*.js"
  - "coach-web/src/test/java/com/coach/noam/**"
  - "coach-web/src/test/java/com/coach/ChatApiTest.java"
  - "e2e/**"
---

# Español (語 / 字 / 文) and the noam integration

Everything specific to the Español coach and to **noam**, the sibling
vocabulary-platform repo (REST API at `http://localhost:8080/api/v1` in dev, checked
out at `../noam` with the authoritative wire contract in `contracts/openapi.yaml`).
The rest of the coach flows — personas, CLAUDE_ARCHITECT, the MCP prompts — are in
the root `CLAUDE.md` and do not need any of this.

Three Español modes share one `SPANISH` coach type: **語** sentence practice (the only
persisted one), **字** word quizzes, and **文** documents sourced from noam.

## Modes

### 字 word mode (ephemeral)

`word/WordSetStore` stores translated pairs in a
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
writes JSONL or meta.json. `/translate` also backfills each pair's `WordPair.lexemeId`:
`SpanishWordController.withLexemeIds` upserts the whole batch in noam via
`NoamGateway.createLexemes` before the shuffle (so pairs and drafts stay positionally
aligned), skipped entirely — not even an HTTP call — when `noamGateway.isAvailable()` is
false. This is why a **hand-typed** 字 quiz now reports grades to noam on `/check` just
like a 文-seeded one; `lexemeId` is no longer "null for a hand-typed list". The "practice
missed" button (and the 語/字 toggle) POST
`/api/chat {coachType:'spanish', message:words}` with no topic, seeding a persisted
語 conversation with `OPENING_WITH_WORDS_NO_TOPIC`; "De nuevo 字" restarts a 字 quiz
over all words.
### 語 verdict reporting (noam-gated)

when `NoamGateway.isAvailable()`, every turn's
system prompt for a `SPANISH` conversation gets `spanishVerdicts=true`
(`ChatController` → `CoachService.systemPrompt(meta, true)`), which appends
`SPANISH_VERDICT_INSTRUCTION` to the persona — instructing the tutor, on a *correction*
reply only (never a new-sentences reply), to end with a line-for-line
`===EVALUACIÓN===` block: one `(hint) CORRECTO|PARCIAL|INCORRECTO` line per corrected
sentence. `coach/VerdictParser.parse()` (coach-core) splits the reply at that marker
line, mapping `CORRECTO`→`GOOD`, `PARCIAL`→`HARD`, `INCORRECTO`→`AGAIN`; a missing or
malformed block degrades to "no verdicts" rather than a broken reply, and a
verdict-only reply (nothing left after stripping) falls back to a placeholder
("Revisión completada.") since persisting empty content would brick the next
Anthropic turn. `ChatController` persists and returns only the stripped answer — the
block itself never reaches storage or the client, which is why T17 needed no frontend
change. Stripped verdicts go to `noam/SpanishReviewReporter`: each verdict's
comma-separated hint words are split and deduped across the whole turn by
`Text.normalizeKey`, the *worst* grade winning a repeat (`AGAIN` < `HARD` < `GOOD`);
the surviving words are upserted via `NoamGateway.createLexemes` and one review is
posted per resulting lexeme id via `recordReview` (`source: EXAM`) — a noam failure is
logged and swallowed per word, the same pattern as `SpanishWordController`'s own
review posting. With noam unconfigured or unreachable, 語 behaves exactly as it did
before T17, system prompt included.
### 文 documents mode (noam-sourced, ephemeral)

a third Español mode that studies
vocabulary from **noam**, a sibling vocabulary-platform repo (REST API at
`http://localhost:8080/api/v1` in dev). Flow: the 文 screen (Documentos tab — noam's
documents, plus upload; Cola tab — the profile's spaced-repetition study queue) →
a per-document/per-queue study-item list with study/known/ignored triage → Proceed
flushes the triage marks to noam, then seeds a 字 quiz straight from the checked
items' own noam translations via `POST /api/spanish/words/seed` (no LLM call —
`SpanishWordController.seed` builds `WordPair`s from client-supplied
`{lexemeId, spanish, english}` triples) → grades post back to noam on `/check` → a
topic screen (the same grid `enterTopicSetup` uses) → 語 sentence practice on the
missed words only. `WordPair` carries a nullable `lexemeId` (noam's lexeme id — null
only when a hand-typed list's own backfill above also came up empty, e.g. noam
unavailable); ids live only in `WordSetStore` — 文 mode writes no JSONL
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

## The noam boundary

- **`noam/NoamGateway`** — the only class in `coach-web` that talks to noam (write-backs
  only; reads are browser-direct). `isAvailable()` — a config check plus a 60s-cached
  reachability probe of `GET {baseUrl}/documents?language=es` (2s timeout, never throws)
  — is the single gate for every noam side-effect, the Spanish system prompt's verdict
  instruction included, and the one documented exception to "reads are browser-direct".
  It leaks no `userId`: a blank config short-circuits to `false` with no HTTP call, and
  any probe failure (including an unchecked `IllegalArgumentException` from a malformed
  or scheme-less `coach.noam.base-url`) is caught and cached as `false`. `createLexemes(List<LexemeDraft>)` batch-upserts words via
  `POST /lexemes`, chunked at 25 drafts per request (each item costs one sidecar call in
  noam), and returns lexeme ids positionally aligned with the input — `null` for any
  draft it can't confidently place. Contract facts worth keeping: `POST /lexemes` is an
  idempotent upsert keyed on `(language, type, canonicalKey)`, so callers never persist
  ids to dedupe; `refLanguage` is `"en"` exactly when a draft carries an English gloss and
  omitted otherwise, because noam rejects an item with a `translation` but no
  `refLanguage` — `SpanishWordController`'s drafts carry one, `SpanishReviewReporter`'s
  verdict drafts never do, so noam auto-fills their gloss with a cheap LLM call under its
  own default ref language; `contextSentence` is omitted, which is not free — noam feeds
  it to that auto-fill to disambiguate the sense, so verdict words get a context-free
  gloss. Per-item rejections never fail the request — they come back in `failed[]` with a
  200/201 — so a whole chunk is nulled only on a non-2xx (`400` for an empty or >100-item
  batch, neither reachable from here; `502` when nothing was created and a dependency was
  down), where `createLexemeChunk` never reaches alignment at all. On a 2xx, `lexemes[]`
  holds the successes in request order and every other request item appears in `failed[]`
  by surface, so alignment walks the chunk taking ids in order and skipping the surfaces
  listed as failed; a response whose counts don't add up to the chunk size is discarded
  wholesale (all `null`) rather than partially matched — mis-pairing here would report
  one word's SRS grade against a different lexeme.

## Design decisions

- **Lexeme registration (語/字 → noam) is best-effort and invisible** — with noam
  unconfigured or unreachable, both modes behave exactly as they did before T14–T17,
  system prompt included: no verdict instruction is added, `/translate` skips the
  backfill without making an HTTP call, and a mid-stream noam failure degrades to
  "no id for this word" rather than surfacing an error to the user.
## Testing

`NoamGateway`'s default `isAvailable() == false` (Mockito's stock boolean answer,
unstubbed) is what keeps the pre-existing Spanish tests in `ChatApiTest` on their old,
noam-free paths; a test exercising T14–T17 behavior must
`when(noamGateway.isAvailable()).thenReturn(true)` explicitly.

`noam/NoamGatewayTest` is Mockito-free — an in-process
`com.sun.net.httpserver.HttpServer`, mirroring `DocFetchGatewayTest`'s pattern.

文 mode's Playwright specs are `noam-documents-flow.spec.js`, `noam-study-list.spec.js`,
and `noam-word-source-cache.spec.js`. A change to `script.js` or `noam.js` is not
verified until `npx playwright test` has been run, even if `mvn test` and
`node --check` both pass.
