const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

const NOAM_BASE = 'http://noam.test';

// The draft snapshot has several writers and no owner, so ordinary navigation
// can quietly destroy state that an undelivered turn still depends on. These
// cover the three ways that happens.

async function routeDefaults(page) {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );
}

/** /api/chat that is unreachable until `state.offline` is cleared. */
async function routeChat(page, state) {
    await page.route('**/api/chat', async route => {
        if (state.offline) return route.abort('failed');
        const body = JSON.parse(route.request().postData());
        state.posts.push(body);
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({
                conversationId: body.conversationId || 'conv-1',
                answer: `respuesta a ${body.message}`,
            }),
        });
    });
}

const readDraft = page => page.evaluate(() => {
    try { return JSON.parse(localStorage.getItem('coach.draft')); } catch { return null; }
});

async function send(page, text) {
    await page.fill('#chatInput', text);
    await page.press('#chatInput', 'Enter');
}

test('a brand new chat leaves no draft behind', async ({ page }) => {
    await routeDefaults(page);
    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // startNewChat clears the draft and then posts its welcome bubble, whose own
    // deferred save rewrites the key 300 ms later — so the clear has to outlive
    // that, or the invariant it implies simply doesn't hold.
    await page.click('#newChatButton');
    await page.waitForTimeout(500);

    expect(await readDraft(page)).toBeNull();
});

test('entering a setup screen keeps an undelivered turn recoverable', async ({ page }) => {
    const state = { offline: true, posts: [] };
    await routeDefaults(page);
    await routeChat(page, state);
    // The 文 button starts disabled until probeNoamAvailability() resolves; stub
    // both calls it makes so the probe succeeds and the button is clickable.
    await page.route('**/api/noam/config', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify({ baseUrl: NOAM_BASE, profileId: 'p1' }) })
    );
    await page.route(`${NOAM_BASE}/documents?language=es`, route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    // 文 empties the chat pane for the documents shell. The queued turn survives
    // in the outbox, so its bubble snapshot must survive too — without it the
    // next flush has nothing to settle the answer onto and drops it silently.
    await page.click('button.mode-btn[data-mode="documents"]');
    await page.evaluate(() => window.saveDraftNow());

    const draft = await readDraft(page);
    expect(draft).not.toBeNull();
    expect(draft.messages.some(m => m.content === 'primera' && m.outboxId)).toBe(true);

    // Reload offline: the pending bubble comes back and the replayed answer lands.
    await page.reload();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    await expect(page.locator('.message.assistant').last()).toContainText('respuesta a primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(0);
});

test('a restored 字 screen can still route a pasted word list', async ({ page }) => {
    await routeDefaults(page);
    let translateWords = null;
    await page.route('**/api/spanish/words/translate', async route => {
        translateWords = JSON.parse(route.request().postData()).words;
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({ setId: 'set-1', items: [{ english: 'wine', hint: 'v···', spanish: 'vino' }] }),
        });
    });
    await page.route('**/api/chat', route => route.abort('failed'));

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // 字's whole UI is one addMessage bubble, so it is in the snapshot — unlike a
    // topic grid. Type into it without sending, then reload.
    await page.click('button.mode-btn[data-mode="words"]');
    await expect(page.locator('.message.assistant').last()).toContainText('字');
    await page.fill('#chatInput', 'vino');
    await page.evaluate(() => window.saveDraftNow());

    await page.reload();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.message.assistant').last()).toContainText('字');
    await expect(page.locator('#chatInput')).toHaveValue('vino');

    // The restored prompt asks for a word list, so sending one must mint a quiz —
    // not post prose to /api/chat with no way back except toggling 語 first.
    await page.press('#chatInput', 'Enter');

    await expect(page.locator('.word-answer').first()).toBeVisible();
    expect(translateWords).toBe('vino');
});

test('a restored topic grid falls back to a fresh chat rather than an orphaned welcome', async ({ page }) => {
    await routeDefaults(page);
    await page.route('**/api/coaches/spanish/topics', route =>
        route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify([{ level: 'A1', topics: ['viajes'] }]),
        })
    );
    await page.route('**/api/chat', route => route.abort('failed'));

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // A topic grid renders outside addMessage, so only its welcome bubble is in
    // the snapshot — restoring that alone would show an instruction with no grid.
    await page.click('input[name="coach"][value="spanish"]');
    await expect(page.locator('#topicGrid .topic-button').first()).toBeVisible();
    await page.fill('#chatInput', 'hola');
    await page.evaluate(() => window.saveDraftNow());

    await page.reload();
    await page.waitForLoadState('networkidle');

    await expect(page.locator('.message.assistant').last()).toContainText('New chat');
});
