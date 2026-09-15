const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

// The offline outbox is a replay queue, and these cover the three ways it can
// still get a turn wrong: losing the conversation it belongs to, sending it
// twice, and destroying it on a rejection. Plus one admission bug — a failure
// raised while rendering a delivered answer must not be mistaken for a network
// failure and queued.

async function routeDefaults(page) {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );
}

/**
 * One /api/chat handler whose behaviour a test flips mid-run, the way a phone
 * leaving and rejoining the network does. `state.offline` aborts the request the
 * way an unreachable host does (fetch rejects with a TypeError); otherwise the
 * body is recorded and a conversation is minted for any turn that arrives
 * without one.
 */
async function routeChat(page, state) {
    await page.route('**/api/chat', async route => {
        if (state.offline) return route.abort('failed');
        const body = JSON.parse(route.request().postData());
        state.posts.push(body);
        if (state.status && state.status !== 200)
            return route.fulfill({
                status: state.status,
                contentType: 'application/json',
                body: JSON.stringify({ message: state.errorMessage || 'Rejected' }),
            });
        const conversationId = body.conversationId || `conv-${++state.minted}`;
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({ conversationId, answer: `respuesta a ${body.message}`, ...state.extra }),
        });
    });
}

function chatState(overrides = {}) {
    return { offline: true, posts: [], minted: 0, ...overrides };
}

async function send(page, text) {
    await page.fill('#chatInput', text);
    await page.press('#chatInput', 'Enter');
}

const outboxSize = page => page.evaluate(() => {
    try { return JSON.parse(localStorage.getItem('coach.outbox') || '[]').length; } catch { return -1; }
});

test('two turns queued before any mint replay into one conversation', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Both sends happen in a fresh chat, so both freeze conversationId: null.
    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);
    await send(page, 'segunda');
    await expect(page.locator('.message.user.pending')).toHaveCount(2);

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    await expect.poll(() => state.posts.length).toBe(2);
    // The second turn must inherit the id the first one minted — otherwise the
    // server mints a second conversation and answers it with no memory of turn 1.
    expect(state.minted).toBe(1);
    expect(state.posts[1].conversationId).toBe('conv-1');
    await expect(page.locator('.message.user.pending')).toHaveCount(0);
});

test('a new chat started offline does not merge into the previous chat', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);
    // Deliberately start over while still offline: this turn belongs to its own
    // conversation even though it also carries conversationId: null.
    await page.click('#newChatButton');
    await send(page, 'segunda');
    // The new chat wiped the first bubble, so this is the second turn's own —
    // and waiting for it is what guarantees it reached the queue before the flush.
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    await expect.poll(() => state.posts.length).toBe(2);
    expect(state.minted).toBe(2);
});

test('overlapping flushes send each queued turn exactly once', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    // A phone waking on the home network fires `online` and `visibilitychange`
    // back to back; both drains read the queue before either removes anything.
    state.offline = false;
    await page.evaluate(() => { window.flushOutbox(); window.flushOutbox(); });

    await expect.poll(() => state.posts.length).toBeGreaterThan(0);
    await expect.poll(() => outboxSize(page)).toBe(0);
    expect(state.posts).toHaveLength(1);
});

test('a queued turn rejected on replay gives its text back', async ({ page }) => {
    const state = chatState({ status: 400, errorMessage: 'Model no longer available' });
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    await expect(page.locator('.message.assistant').last()).toContainText('Model no longer available');
    // Nothing may be left holding the text hostage: no permanent dashed bubble,
    // no queue entry that will be retried forever, and the text is editable again.
    await expect(page.locator('.message.user.pending')).toHaveCount(0);
    await expect.poll(() => outboxSize(page)).toBe(0);
    await expect(page.locator('#chatInput')).toHaveValue('primera');
});

test('a render failure after a delivered answer is not queued for replay', async ({ page }) => {
    // A 200 whose question has no options makes buildQuizBlock throw a TypeError
    // from inside the same try that wraps the fetch. The turn is already
    // persisted server-side, so replaying it would double-post it.
    const state = chatState({ offline: false, extra: { question: { stem: 'Sin opciones?' } } });
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');

    await expect.poll(() => state.posts.length).toBe(1);
    await expect.poll(() => outboxSize(page)).toBe(0);
    await expect(page.locator('.message.user.pending')).toHaveCount(0);
});
