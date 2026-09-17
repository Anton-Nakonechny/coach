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
        // A half-open socket — the phone moved from WiFi to cellular and nothing
        // ever comes back. Not an abort: the request simply never settles.
        if (state.hang) return new Promise(() => {});
        const body = JSON.parse(route.request().postData());
        state.posts.push(body);
        // The window can close again mid-drain: after this many posts the host is
        // unreachable once more, so the rest of the queue stays queued.
        if (state.offlineAfter && state.posts.length >= state.offlineAfter) state.offline = true;
        if (state.status && state.status !== 200)
            return route.fulfill({
                status: state.status,
                contentType: 'application/json',
                body: JSON.stringify({ message: state.errorMessage || 'Rejected' }),
            });
        // ChatController.handle refuses coachType next to a conversationId
        // ("coachType can only be set when starting a new chat") — a replay that
        // keeps both has to fail here the way it fails against the real server.
        if (body.coachType && body.conversationId)
            return route.fulfill({
                status: 400,
                contentType: 'application/json',
                body: JSON.stringify({ message: 'coachType can only be set when starting a new chat' }),
            });
        const conversationId = body.conversationId || `conv-${++state.minted}`;
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({ conversationId, answer: `respuesta a ${body.message}`, ...state.extra }),
        });
    });
}

async function routeSpanishTopics(page) {
    await page.route('**/api/coaches/spanish/topics', route =>
        route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify([{ level: 'A1', topics: ['viajes'] }]),
        })
    );
}

/** Open the Español setup screen and pick its one topic, so real turns can be sent. */
async function pickSpanishTopic(page) {
    await page.click('input[name="coach"][value="spanish"]');
    await page.click('#topicGrid .topic-button');
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
    // A replay can reach a server that already took the turn — the connection can
    // drop after the request lands and before the answer comes back — so every
    // turn carries its own id for the server to recognise it by.
    expect(state.posts[0].clientTurnId).toBeTruthy();
    expect(state.posts[1].clientTurnId).not.toBe(state.posts[0].clientTurnId);
    // The drain proved the server is reachable, so the banner comes down with it.
    await expect(page.locator('#offlineBanner')).toBeHidden();
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

test('a reload between two offline sends keeps both turns in one conversation', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    // The tab is discarded and re-navigated while still offline — the very case
    // this feature exists for. The chat key that ties turn 2 to turn 1's mint
    // lives in module state, so it only survives if the draft carries it.
    await page.evaluate(() => window.saveDraftNow());
    await page.reload();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    await send(page, 'segunda');
    await expect(page.locator('.message.user.pending')).toHaveCount(2);

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    await expect.poll(() => state.posts.length).toBe(2);
    expect(state.minted).toBe(1);
    expect(state.posts[1].conversationId).toBe('conv-1');
});

test('replayed answers land under their own turn', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await send(page, 'segunda');
    await expect(page.locator('.message.user.pending')).toHaveCount(2);

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    // Both pending bubbles are already mounted when the replay starts, so an
    // answer appended to the end of the pane lands under the wrong turn — and
    // the next snapshot bakes that order in.
    await expect(page.locator('.message.assistant')).toHaveCount(3);
    const texts = await page.locator('.message .message-content').allTextContents();
    expect(texts.slice(-4).map(t => t.trim())).toEqual([
        'primera', 'respuesta a primera', 'segunda', 'respuesta a segunda',
    ]);
});

test('a render failure on a replayed answer does not strand the rest of the queue', async ({ page }) => {
    // Every answer carries a question with no options, so settleQueued's
    // buildQuizBlock throws a TypeError from inside the same try that wraps the
    // fetch — after the turn was delivered and dropped from the queue.
    const state = chatState({ extra: { question: { stem: 'Sin opciones?' } } });
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await send(page, 'segunda');
    await expect(page.locator('.message.user.pending')).toHaveCount(2);

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    // A rendering failure is not a network failure: the first item was delivered
    // and dropped, so the drain must carry on to the second instead of breaking
    // out and stranding it in the queue.
    await expect.poll(() => state.posts.length).toBe(2);
    await expect.poll(() => outboxSize(page)).toBe(0);
});

test('a rejected replay does not overwrite a draft typed while it waited', async ({ page }) => {
    const state = chatState({ status: 400, errorMessage: 'Model no longer available' });
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);
    // The composer is free while a turn waits in the queue, so the user starts
    // the next message. Neither text may be thrown away by the rejection.
    await page.fill('#chatInput', 'otra cosa');

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    await expect(page.locator('.message.assistant').last()).toContainText('Model no longer available');
    await expect(page.locator('#chatInput')).toHaveValue('primera\n\notra cosa');
});

test('a successful direct send clears the banner and drains what was queued', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await expect(page.locator('#offlineBanner')).toContainText('Offline');

    // A phone that leaves the server's Wi-Fi but keeps cellular never fires
    // `online` — navigator.onLine stays true — so nothing but a send that
    // succeeds can prove the server came back. It has to do the whole job: clear
    // the banner and move the queue. A turn left sitting there would mint a
    // second conversation for this same chat whenever it finally went out.
    state.offline = false;
    await send(page, 'segunda');

    await expect.poll(() => state.posts.length).toBe(2);
    expect(state.minted).toBe(1);
    expect(state.posts[1].message).toBe('primera');
    expect(state.posts[1].conversationId).toBe('conv-1');
    await expect.poll(() => outboxSize(page)).toBe(0);
    await expect(page.locator('#offlineBanner')).toBeHidden();
});

test('a replay rejected while its own chat is off screen does not land in another one', async ({ page }) => {
    const state = chatState({ status: 400, errorMessage: 'Model no longer available' });
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    // The drain awaits a full round-trip per item and runs on `online` /
    // `visibilitychange` — exactly when a user is moving around — so a rejection
    // can arrive with a different chat on screen. That chat must not be offered
    // someone else's text under a "press Enter to send again".
    await page.click('#newChatButton');
    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    await expect(page.locator('#offlineBanner')).toContainText('rejected');
    await expect(page.locator('#chatInput')).toHaveValue('');
    await expect(page.locator('.message.assistant').last()).toContainText('New chat');

    // Misplacing it is not the only failure available: it must not be destroyed
    // either. It stays queued — never sent again — until the chat it came from
    // is back on screen, which is what a reload brings.
    await page.reload();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#chatInput')).toHaveValue('primera');
    await expect(page.locator('.message.assistant').last()).toContainText('Model no longer available');
    await expect.poll(() => outboxSize(page)).toBe(0);
    expect(state.posts).toHaveLength(1);
});

test('a second queued turn of an unminted coach chat joins the chat the first one made', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);
    await routeSpanishTopics(page);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Español is the one setup that sends real turns from its own screen, and
    // activeSetup only clears when an answer arrives — so both of these queue
    // with coachType/topic and no conversationId.
    await pickSpanishTopic(page);
    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);
    await send(page, 'segunda');
    await expect(page.locator('.message.user.pending')).toHaveCount(2);

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    await expect.poll(() => state.posts.length).toBe(2);
    expect(state.minted).toBe(1);
    // Inheriting the id is not enough: the request that asked to *start* the chat
    // already succeeded, so coachType/topic must come off with it.
    expect(state.posts[1].conversationId).toBe('conv-1');
    expect(state.posts[1].coachType).toBeUndefined();
    expect(state.posts[1].topic).toBeUndefined();
    await expect(page.locator('.message.user.pending')).toHaveCount(0);
});

test('the message after a replayed coach-first turn stays in the same conversation', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);
    await routeSpanishTopics(page);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await pickSpanishTopic(page);
    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());
    await expect(page.locator('.message.user.pending')).toHaveCount(0);

    // The replay landed the opening turn, so the setup screen is spent — exactly
    // as it would be had the send succeeded first time. Typing again must not
    // re-ask for a new coach chat on top of the id the replay just adopted.
    await send(page, 'segunda');

    await expect.poll(() => state.posts.length).toBe(2);
    expect(state.posts[1].conversationId).toBe('conv-1');
    expect(state.posts[1].coachType).toBeUndefined();
    await expect(page.locator('.message.assistant').last()).toContainText('respuesta a segunda');
});

test('a conversation minted mid-drain survives the drain stopping', async ({ page }) => {
    const state = chatState({ offlineAfter: 1 });
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await send(page, 'segunda');
    await expect(page.locator('.message.user.pending')).toHaveCount(2);

    // The window shuts again right after turn 1 lands: the drain breaks with turn
    // 2 still queued, and the id turn 1 minted is only in this drain's own Map.
    state.offline = false;
    await page.evaluate(() => window.flushOutbox());
    await expect.poll(() => state.posts.length).toBe(1);
    await expect.poll(() => outboxSize(page)).toBe(1);

    state.offlineAfter = 0;
    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    await expect.poll(() => state.posts.length).toBe(2);
    // One chat the user never left may not become two server conversations.
    expect(state.minted).toBe(1);
    expect(state.posts[1].conversationId).toBe('conv-1');
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

test('a turn queued in a second chat does not bury the first chat\'s queued one', async ({ page }) => {
    const state = chatState({ status: 400, errorMessage: 'Model no longer available' });
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);
    // A second chat started while still offline queues a turn of its own, and
    // there is only one draft slot. It already holds the only copy of 'primera''s
    // pending bubble — the thing settleQueued and failQueued both find the turn
    // by — so a snapshot of this pane must not be allowed to take its place.
    await page.click('#newChatButton');
    await send(page, 'segunda');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    state.offline = false;
    await page.evaluate(() => window.flushOutbox());

    // 'segunda' is on screen, so its rejection comes straight back to the composer.
    await expect(page.locator('#chatInput')).toHaveValue('segunda');
    // 'primera' was not, so it stays queued until its own chat is back.
    await expect.poll(() => outboxSize(page)).toBe(1);

    // And the draft is the only way that chat can come back. Lose it and
    // 'primera' can never be drawn again — so it can never leave the outbox
    // either, which freezes every later draft save behind the same guard and
    // leaves the banner stuck on 'rejected' for good.
    await page.reload();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#chatInput')).toHaveValue('primera');
    await expect.poll(() => outboxSize(page)).toBe(0);
    await expect(page.locator('#offlineBanner')).toBeHidden();
    expect(state.posts.map(p => p.message)).toEqual(['primera', 'segunda']);
});

test('a replay that never comes back does not wedge the queue for the session', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    // The real limit is minutes, because a chat turn holds the connection open
    // for the whole generation window. The seam is the same one sendMessage uses,
    // shrunk so the test can watch the timer fire instead of waiting it out.
    await page.evaluate(() => { window.turnTimeoutMs = () => 100; });
    await page.waitForLoadState('networkidle');

    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    // The server is "back", but this replay's socket is half-open: no response,
    // no rejection, nothing. `flushing` is held for the whole await, so without a
    // timeout every later drain — `online`, `visibilitychange`, the next
    // successful send — short-circuits on it for the rest of the session.
    state.offline = false;
    state.hang = true;
    await page.evaluate(() => window.flushOutbox());

    await expect(page.locator('#offlineBanner')).toContainText('Offline');
    await expect.poll(() => outboxSize(page)).toBe(1);

    state.hang = false;
    await page.evaluate(() => window.flushOutbox());

    await expect.poll(() => state.posts.length).toBe(1);
    await expect.poll(() => outboxSize(page)).toBe(0);
    await expect(page.locator('.message.user.pending')).toHaveCount(0);
});

test('a turn the queue could not take comes back to the composer', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // localStorage.setItem throws on a full quota and in some private-storage
    // modes. The queue is the only copy a pending turn has — its text left the
    // composer the moment it was sent — so a write that did not land must not be
    // reported back as "queued for replay".
    await page.evaluate(() => {
        const real = localStorage.setItem.bind(localStorage);
        localStorage.setItem = (key, value) => {
            if (key === 'coach.outbox') throw new DOMException('quota', 'QuotaExceededError');
            real(key, value);
        };
    });

    await send(page, 'primera');

    // The older fallback path is exactly right for this: nothing can replay the
    // turn, so the text goes back where the user can send it again.
    await expect(page.locator('#chatInput')).toHaveValue('primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(0);
    await expect(page.locator('.message.assistant').last()).toContainText('restored');
    await expect.poll(() => outboxSize(page)).toBe(0);
});

test('an error body from /api/models is not cached and does not stop the boot', async ({ page }) => {
    const state = chatState();
    await routeDefaults(page);
    await routeChat(page, state);

    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await send(page, 'primera');
    await expect(page.locator('.message.user.pending')).toHaveCount(1);

    // Chat is reachable again, but /api/models answers 500 — and ApiExceptionHandler
    // serialises that as perfectly good JSON, so response.json() resolves and the
    // offline catch never runs. The boot chain is loadModels → loadConversations →
    // restoreDraft → flushOutbox with nothing guarding it, so a throw in the first
    // link takes the replay this PR exists for down with it.
    await page.route('**/api/models', route => route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'boom' }),
    }));
    state.offline = false;
    await page.reload();
    await page.waitForLoadState('networkidle');

    await expect.poll(() => state.posts.length).toBe(1);
    await expect.poll(() => outboxSize(page)).toBe(0);
    // The rail still comes up, because the last good payload was left in place.
    await expect(page.locator('#modelButtons .model-button').first()).toBeVisible();
    // And that is what a genuinely offline cold start would read next.
    const cached = await page.evaluate(() => JSON.parse(localStorage.getItem('coach.models')));
    expect(Array.isArray(cached.models)).toBe(true);
});
