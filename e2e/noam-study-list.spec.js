const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

const NOAM_BASE = 'http://noam.test';
const DOC_ID = 'doc-1';

function studyItems(count, startIndex = 0) {
    return {
        items: Array.from({ length: count }, (_, i) => ({
            lexeme: { id: `lex-${startIndex + i}`, displayText: `palabra${startIndex + i}` },
            translation: `word${startIndex + i}`,
        })),
    };
}

// The Cola tab's study queue is the same row shape without the `items` envelope:
// the endpoint answers with a bare array (no offset, no paging).
function queueItems(count, startIndex = 0) {
    return studyItems(count, startIndex).items;
}

// Routes everything the 文 mode needs to reach either study-item list: the config
// probe, the availability probe + Documentos grid, the paged study-items endpoint
// and the Cola tab's study queue. `pages` is consulted per offset so a test can
// hand out a full page first and a short page second. The queue is stubbed even
// for tests that never open Cola — clicking that tab now fetches, and an unrouted
// endpoint would put the click on the real network.
async function routeNoam(page, { pages, onLexemeStates, studyItemsHandler, onOffset, onSeed, queue, queueHandler } = {}) {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) }));
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) }));
    await page.route('**/api/noam/config', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify({ baseUrl: NOAM_BASE, profileId: 'p1' }) }));
    await page.route(`${NOAM_BASE}/documents?language=es`, route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([
            { id: DOC_ID, title: 'Mi documento', language: 'es', status: 'EXTRACTED', createdAt: '2026-09-01T10:00:00Z' },
        ]) }));
    await page.route(`${NOAM_BASE}/documents/${DOC_ID}/study-items**`, async route => {
        if (studyItemsHandler) return studyItemsHandler(route);
        const offset = Number(new URL(route.request().url()).searchParams.get('offset'));
        onOffset && onOffset(offset);
        const body = (pages && pages[offset]) || { items: [] };
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    });
    await page.route(`${NOAM_BASE}/profiles/*/study-queue**`, async route => {
        if (queueHandler) return queueHandler(route);
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(queue || []) });
    });
    await page.route('**/api/noam/lexeme-states', async route => {
        if (onLexemeStates) return onLexemeStates(route);
        await route.fulfill({ status: 204, body: '' });
    });
    // T10 hands checked study items to POST /api/spanish/words/seed to mint the 字
    // quiz; without a stub here the test hits the real (unmocked) webServer.
    await page.route('**/api/spanish/words/seed', async route => {
        if (onSeed) return onSeed(route);
        const { items } = JSON.parse(route.request().postData());
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify({
            setId: 'set-1',
            items: items.map(it => ({ english: it.english, hint: it.spanish[0], spanish: it.spanish })),
        }) });
    });
}

async function openDocument(page) {
    await enterNoam(page);
    await page.click('.noam-doc-row');
    await expect(page.locator('.noam-study-row').first()).toBeVisible();
}

async function enterNoam(page) {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.click('button.mode-btn[data-mode="documents"]');
}

test('Continuar hands checked items off to the 字 quiz', async ({ page }) => {
    await routeNoam(page, { pages: { 0: studyItems(3) } });
    await openDocument(page);

    await page.locator('.noam-study-row').first().locator('.noam-study-check').check();
    await page.click('.noam-study-proceed');

    await expect(page.locator('.word-check')).toBeVisible();
});

// Untranslated study items are dropped before /seed is called (a blank english
// would 400 the whole batch). When that drops everything, Continuar has nothing
// to hand off — and must say so rather than looking dead.
test('Continuar reports a selection with no translations instead of doing nothing', async ({ page }) => {
    let seedCalled = false;
    await routeNoam(page, {
        pages: { 0: { items: [{ lexeme: { id: 'lex-0', displayText: 'palabra0' }, translation: '' }] } },
        onSeed: async route => { seedCalled = true; await route.fulfill({ status: 500, body: '' }); },
    });
    await openDocument(page);

    await page.locator('.noam-study-row').first().locator('.noam-study-check').check();
    await page.click('.noam-study-proceed');

    await expect(page.locator('.noam-inline-error')).toBeVisible();
    await expect(page.locator('.noam-inline-error')).toContainText('traducción');
    expect(seedCalled).toBe(false);
    // The list stays up so the user can pick different items and retry.
    await expect(page.locator('.noam-study-proceed')).toBeEnabled();
});

test('Continuar loads the next page after flushed rows leave the list short', async ({ page }) => {
    // Paging is scroll-driven, so rows removed by a flush are never replaced:
    // enough Continuar rounds and the list stops overflowing, which kills the
    // only trigger that could ever load page 2. Marking the top rows only (no
    // scrolling) is what the stranding path looks like in miniature.
    const offsets = [];
    await routeNoam(page, {
        pages: { 0: studyItems(50), 50: studyItems(4, 50) },
        studyItemsHandler: null,
        onOffset: offset => offsets.push(offset),
    });
    await openDocument(page);
    expect(offsets).toEqual([0]);

    for (const row of (await page.locator('.noam-study-row').all()).slice(0, 3))
        await row.locator('.noam-mark-known').click();
    await page.click('.noam-study-proceed');

    await expect(page.locator('.noam-study-row')).toHaveCount(51);
    expect(offsets).toEqual([0, 50]);
});

test('a study-item load failure is reported in Spanish', async ({ page }) => {
    await routeNoam(page, { studyItemsHandler: route => route.abort('failed') });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.click('button.mode-btn[data-mode="documents"]');
    await page.click('.noam-doc-row');

    await expect(page.locator('.noam-error')).toContainText('No se pudo conectar con noam');
});

// Back fires the flush without awaiting it, so the assertion has to poll rather
// than assume the POST has landed by the time Documentos is back on screen.
test('going back flushes marks instead of dropping them', async ({ page }) => {
    const flushed = [];
    await routeNoam(page, {
        pages: { 0: studyItems(3) },
        onLexemeStates: async route => {
            flushed.push(JSON.parse(route.request().postData()));
            await route.fulfill({ status: 204, body: '' });
        },
    });
    await openDocument(page);

    await page.locator('.noam-study-row').first().locator('.noam-mark-known').click();
    await page.click('.noam-study-back');

    await expect(page.locator('.noam-doc-row')).toBeVisible();
    await expect.poll(() => flushed).toEqual([{ lexemeIds: ['lex-0'], state: 'KNOWN' }]);
});

// The Documentos/Cola tab buttons stay clickable while a study list is open and,
// unlike Back, don't go through onBack — activateNoamTab has to flush on their
// behalf or a tab switch silently drops the same marks Back already protects.
test('switching tabs flushes marks instead of dropping them', async ({ page }) => {
    const flushed = [];
    await routeNoam(page, {
        pages: { 0: studyItems(3) },
        onLexemeStates: async route => {
            flushed.push(JSON.parse(route.request().postData()));
            await route.fulfill({ status: 204, body: '' });
        },
    });
    await openDocument(page);

    await page.locator('.noam-study-row').first().locator('.noam-mark-known').click();
    await page.click('.noam-tab[data-tab="cola"]');

    await expect.poll(() => flushed).toEqual([{ lexemeIds: ['lex-0'], state: 'KNOWN' }]);
});

// Re-clicking Cola is the natural "refresh" gesture, and it runs the background
// marks flush and the queue fetch back to back. noam derives the queue from the
// very lexeme states that flush is writing, so the read has to land after the
// write — otherwise the word just marked conocida comes straight back, unmarked.
test('re-entering Cola waits for the marks flush before reloading the queue', async ({ page }) => {
    const marked = new Set();
    await routeNoam(page, {
        queueHandler: async route => {
            const due = queueItems(2).filter(it => !marked.has(it.lexeme.id));
            await route.fulfill({ contentType: 'application/json', body: JSON.stringify(due) });
        },
        onLexemeStates: async route => {
            // The POST detours through coach-web while the GET goes browser-direct
            // to noam, so in the real app the write is the slower of the two.
            await new Promise(resolve => setTimeout(resolve, 300));
            JSON.parse(route.request().postData()).lexemeIds.forEach(id => marked.add(id));
            await route.fulfill({ status: 204, body: '' });
        },
    });
    await enterNoam(page);
    await page.click('.noam-tab[data-tab="cola"]');
    await expect(page.locator('.noam-study-row')).toHaveCount(2);

    await page.locator('.noam-study-row').first().locator('.noam-mark-known').click();
    await page.click('.noam-tab[data-tab="cola"]');

    await expect(page.locator('.noam-study-row')).toHaveCount(1);
    await expect(page.locator('.noam-study-row')).toContainText('palabra1');
});

// Leaving a tab mid-load fails silently when it fails: the stale page lands, sees
// its own state still installed, and throws on the entries map the flush nulled —
// inside loadNextStudyPage's own try/catch, painting into a list already detached
// from the DOM. There is nothing on screen to assert, so assert the invariant that
// decides whether it runs at all: the two globals are set and cleared together.
test('leaving a tab mid-load does not strand the study-list globals', async ({ page }) => {
    let releaseQueue;
    await routeNoam(page, {
        queueHandler: async route => {
            await new Promise(resolve => { releaseQueue = resolve; });
            await route.fulfill({ contentType: 'application/json', body: JSON.stringify(queueItems(2)) });
        },
    });
    await enterNoam(page);
    await page.click('.noam-tab[data-tab="cola"]');
    await expect(page.locator('.noam-study-list')).toContainText('Cargando');

    await page.click('.noam-tab[data-tab="documentos"]');
    await expect(page.locator('.noam-doc-row')).toBeVisible();
    await expect.poll(() => Boolean(releaseQueue)).toBe(true);
    releaseQueue();

    await expect.poll(() => page.evaluate(() => noamStudyEntries === null && noamStudyState === null)).toBe(true);
});

// Tab switches run the marks flush and the queue read back to back, and the read
// waits on whatever flush the last switch left behind. A freshly rendered list has
// no marks, so its flush sends nothing and settles at once — if that replaced the
// promise instead of joining it, an earlier flush still on the wire stopped being
// awaited and the queue GET could once more outrun the write it depends on.
test('a tab switch with nothing to flush still waits for the flush in flight', async ({ page }) => {
    const marked = new Set();
    let releasePost;
    await routeNoam(page, {
        queueHandler: async route => {
            const due = queueItems(2).filter(it => !marked.has(it.lexeme.id));
            await route.fulfill({ contentType: 'application/json', body: JSON.stringify(due) });
        },
        onLexemeStates: async route => {
            await new Promise(resolve => { releasePost = resolve; });
            JSON.parse(route.request().postData()).lexemeIds.forEach(id => marked.add(id));
            await route.fulfill({ status: 204, body: '' });
        },
    });
    await enterNoam(page);
    await page.click('.noam-tab[data-tab="cola"]');
    await expect(page.locator('.noam-study-row')).toHaveCount(2);

    await page.locator('.noam-study-row').first().locator('.noam-mark-known').click();
    // Leaving sends the mark; the POST stays on the wire for the rest of the test.
    await page.click('.noam-tab[data-tab="documentos"]');
    await expect.poll(() => Boolean(releasePost)).toBe(true);
    // Re-entering Cola builds a mark-free list, and leaving it flushes nothing.
    await page.click('.noam-tab[data-tab="cola"]');
    await page.click('.noam-tab[data-tab="documentos"]');
    await page.click('.noam-tab[data-tab="cola"]');

    // Long enough that a read no longer chained to the pending POST has fetched.
    await page.waitForTimeout(300);
    releasePost();

    await expect(page.locator('.noam-study-row')).toHaveCount(1);
    await expect(page.locator('.noam-study-row')).toContainText('palabra1');
});
