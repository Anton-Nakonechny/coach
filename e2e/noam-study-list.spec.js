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

// Routes everything the 文 mode needs to reach a document's study-item list:
// the config probe, the availability probe + Documentos grid, and the paged
// study-items endpoint. `pages` is consulted per offset so a test can hand out
// a full page first and a short page second.
async function routeNoam(page, { pages, onLexemeStates, studyItemsHandler, onOffset } = {}) {
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
    await page.route('**/api/noam/lexeme-states', async route => {
        if (onLexemeStates) return onLexemeStates(route);
        await route.fulfill({ status: 204, body: '' });
    });
}

async function openDocument(page) {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.click('button.mode-btn[data-mode="documents"]');
    await page.click('.noam-doc-row');
    await expect(page.locator('.noam-study-row').first()).toBeVisible();
}

test('Continuar stays clickable after handing checked items to the quiz', async ({ page }) => {
    await routeNoam(page, { pages: { 0: studyItems(3) } });
    await openDocument(page);

    await page.locator('.noam-study-row').first().locator('.noam-study-check').check();
    await page.click('.noam-study-proceed');

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
