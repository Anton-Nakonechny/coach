const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

// The Java coach's hint/reveal/next row is the one control row that is *not*
// rebuilt on every activateQuiz() call: it is appended once, to whichever
// assistant bubble arrives while javaAwaitingQuestion is set, and then lives on.
// A global one-shot token with one producer and five consumers (direct send
// success, direct send failure, settleQueued, restoreDraft, openConversation) is
// only correct if every consumer is the intended one — these cover the consumers
// that are not: the error bubble, a follow-up reply, and a replayed turn.

const CONVERSATION_ID = 'java-1';

async function routeDefaults(page) {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    // The sidebar listing is what teaches conversationCoach which coach owns the
    // chat, and activateQuiz dispatches on exactly that.
    await page.route('**/api/conversations', route =>
        route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify([
                { conversationId: CONVERSATION_ID, coachType: 'java', preview: 'Java · Java Core' },
            ]),
        })
    );
    await page.route('**/api/coaches/java/topics', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(['Java Core']) })
    );
}

/**
 * The transcript openConversation reloads after a coach chat is started. It grows
 * as turns are posted, so the reopened pane matches what the server would hold.
 */
async function routeTranscript(page, state) {
    await page.route(`**/api/conversations/${CONVERSATION_ID}`, route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(state.transcript) })
    );
}

/**
 * One /api/chat handler a test flips mid-run (same shape as outbox-replay.spec.js):
 * `offline` aborts the way an unreachable host does, `hang` never settles, `status`
 * rejects. Every accepted turn is appended to the transcript so a reopen replays it.
 */
async function routeChat(page, state) {
    await page.route('**/api/chat', async route => {
        if (state.offline) return route.abort('failed');
        // Slow, not stalled: long enough for a test to click while the composer is
        // disabled, short enough that the answer still arrives and can be inspected.
        if (state.holdMs) await new Promise(r => setTimeout(r, state.holdMs));
        const body = JSON.parse(route.request().postData());
        state.posts.push(body);
        if (state.status && state.status !== 200)
            return route.fulfill({
                status: state.status,
                contentType: 'application/json',
                body: JSON.stringify({ message: 'Rejected' }),
            });
        const answer = `answer to ${body.message || body.topic}`;
        state.transcript.push({ role: 'user', content: body.message || 'Ask me the first interview question.' });
        state.transcript.push({ role: 'assistant', content: answer });
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({ conversationId: CONVERSATION_ID, answer }),
        });
    });
}

function chatState(overrides = {}) {
    return { offline: false, posts: [], transcript: [], ...overrides };
}

async function setup(page, state) {
    await routeDefaults(page);
    await routeTranscript(page, state);
    await routeChat(page, state);
    await page.goto('/');
    await page.waitForLoadState('networkidle');
}

/** Pick the one Java topic, which starts the interview and asks the first question. */
async function startJavaTopic(page) {
    await page.click('input[name="coach"][value="java"]');
    await page.click('#javaTopicGrid .topic-button');
    await expect(page.locator('.java-controls-row')).toHaveCount(1);
}

const rows = page => page.locator('.java-controls-row');
const lastAssistant = page => page.locator('.message.assistant').last();

test('a failed "Next question" leaves the error bubble bare and the retry gets the controls', async ({ page }) => {
    const state = chatState();
    await setup(page, state);
    await startJavaTopic(page);

    state.status = 500;
    await rows(page).locator('.next-question').click();

    // The error is rendered as an assistant bubble like any other, and it must not
    // be handed the row the retry's real question is waiting for.
    await expect(lastAssistant(page)).toContainText('Error:');
    await expect(lastAssistant(page).locator('.java-controls-row')).toHaveCount(0);
    await expect(rows(page)).toHaveCount(1);

    state.status = 200;
    await page.press('#chatInput', 'Enter');

    await expect(lastAssistant(page)).toContainText('answer to Next question.');
    await expect(lastAssistant(page).locator('.java-controls-row')).toHaveCount(1);
});

test('clicking Next while a send is in flight sends nothing and moves no controls', async ({ page }) => {
    const state = chatState();
    await setup(page, state);
    await startJavaTopic(page);

    const postsBefore = state.posts.length;
    state.holdMs = 1500;
    await page.fill('#chatInput', 'my answer');
    await page.press('#chatInput', 'Enter');
    await expect(page.locator('#chatInput')).toBeDisabled();

    // sendQuizReply no-ops while the composer is disabled, so this click sends
    // nothing — and must not arm the flag the in-flight follow-up then consumes.
    await rows(page).locator('.next-question').click();

    await expect(lastAssistant(page)).toContainText('answer to my answer');
    expect(state.posts.length).toBe(postsBefore + 1);
    await expect(lastAssistant(page).locator('.java-controls-row')).toHaveCount(0);
    await expect(rows(page)).toHaveCount(1);
});

test('a follow-up reply never doubles the row on the question bubble', async ({ page }) => {
    const state = chatState();
    await setup(page, state);
    await startJavaTopic(page);

    // Pinned by its text, not by position: `last()` re-resolves at assertion time
    // and would follow the hint reply down the pane.
    const questionBubble = page.locator('.message.assistant').filter({ hasText: 'answer to Java Core' });
    await rows(page).locator('.java-hint').click();

    await expect(lastAssistant(page)).toContainText('answer to Give me a hint.');
    await expect(rows(page)).toHaveCount(1);
    await expect(questionBubble.locator('.java-controls-row')).toHaveCount(1);
});

test('moving to the next question deadens the previous question\'s controls', async ({ page }) => {
    const state = chatState();
    await setup(page, state);
    await startJavaTopic(page);

    await rows(page).locator('.next-question').click();
    await expect(rows(page)).toHaveCount(2);

    // Q1's row stays on screen under its own question, but a hint clicked there now
    // would be answered against Q2 — and would hand out a second hint for free.
    const stale = rows(page).first();
    await expect(stale.locator('.java-hint')).toBeDisabled();
    await expect(stale.locator('.java-reveal')).toBeDisabled();
    await expect(stale.locator('.next-question')).toBeDisabled();
});

test('a queued "Next question" keeps its controls through the replay', async ({ page }) => {
    const state = chatState();
    await setup(page, state);
    await startJavaTopic(page);

    state.offline = true;
    await rows(page).locator('.next-question').click();
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    // An unrelated turn sent before the drain must not be the one that consumes the
    // queued turn's controls — its own answer is not a question.
    state.offline = false;
    await page.fill('#chatInput', 'my answer');
    await page.press('#chatInput', 'Enter');

    await expect(page.locator('.message.user.pending')).toHaveCount(0);
    const answers = page.locator('.message.assistant');
    const direct = answers.filter({ hasText: 'answer to my answer' });
    const replayed = answers.filter({ hasText: 'answer to Next question.' });
    await expect(direct.locator('.java-controls-row')).toHaveCount(0);
    await expect(replayed.locator('.java-controls-row')).toHaveCount(1);
});
