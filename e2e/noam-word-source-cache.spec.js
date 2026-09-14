const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

// Covers two review findings against the T10 noam-seeded 字 quiz:
// homograph collision in the client-side noamWordSource cache, and the
// cache being wiped by a "practicar en 語" detour that should survive it.

async function routeDefaults(page) {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );
}

test('a missed homograph pair reseeds both distinct lexemeIds, not the same one twice', async ({ page }) => {
    await routeDefaults(page);

    const seedRequests = [];
    await page.route('**/api/spanish/words/seed', async route => {
        const body = JSON.parse(route.request().postData());
        seedRequests.push(body.items);
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({
                setId: `set-${seedRequests.length}`,
                items: body.items.map(it => ({ english: it.english, hint: it.spanish[0], spanish: it.spanish })),
            }),
        });
    });
    await page.route('**/api/spanish/words/check', async route => {
        const { answers } = JSON.parse(route.request().postData());
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({
                results: [
                    { english: 'wine', spanish: 'vino', correct: false, fullHint: false },
                    { english: 'he came', spanish: 'vino', correct: false, fullHint: false },
                ],
            }),
        });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Seed the initial quiz directly the way the Documentos grid hand-off does:
    // two study items that share the spanish surface form "vino" but come from
    // different noam lexemes.
    await page.evaluate(() => {
        window.startWordQuizFromNoam([
            { lexemeId: 'lex-vino-wine', spanish: 'vino', english: 'wine' },
            { lexemeId: 'lex-vino-came', spanish: 'vino', english: 'he came' },
        ]);
    });

    await expect(page.locator('.word-answer').first()).toBeVisible();
    await page.locator('.word-answer').first().fill('wrong');
    await page.locator('.word-answer').nth(1).fill('wrong');
    await page.click('.word-check button:has-text("Comprobar")');

    // "De nuevo 字" reseeds via /seed (not /translate) with both lexemeIds intact.
    await page.click('button:has-text("De nuevo")');

    await expect.poll(() => seedRequests.length).toBe(2);
    const retryLexemeIds = seedRequests[1].map(it => it.lexemeId).sort();
    expect(retryLexemeIds).toEqual(['lex-vino-came', 'lex-vino-wine']);
});

test('practicar en 語 then back to 字 still reseeds via noam, not the LLM translate path', async ({ page }) => {
    await routeDefaults(page);

    const seedRequests = [];
    await page.route('**/api/spanish/words/seed', async route => {
        const body = JSON.parse(route.request().postData());
        seedRequests.push(body.items);
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({
                setId: `set-${seedRequests.length}`,
                items: body.items.map(it => ({ english: it.english, hint: it.spanish[0], spanish: it.spanish })),
            }),
        });
    });
    await page.route('**/api/spanish/words/check', async route => {
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({
                results: [{ english: 'shrewd', spanish: 'astutos', correct: false, fullHint: false }],
            }),
        });
    });
    let translateCalled = false;
    await page.route('**/api/spanish/words/translate', async route => {
        translateCalled = true;
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({ setId: 'llm-set', items: [{ english: 'shrewd', hint: 'a', spanish: 'astutos' }] }),
        });
    });
    await page.route('**/api/chat', async route => {
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ conversationId: 'convo-1' }) });
    });
    await page.route('**/api/conversations/convo-1', async route => {
        await route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify([
                { role: 'user', content: 'astutos' },
                {
                    role: 'assistant',
                    content: 'Los piratas eran astutos.',
                    sentences: [{ hint: 'astutos', sentence: 'Los piratas eran astutos.' }],
                },
            ]),
        });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await page.evaluate(() => {
        window.startWordQuizFromNoam([{ lexemeId: 'lex-astutos', spanish: 'astutos', english: 'shrewd' }]);
    });
    await expect(page.locator('.word-answer').first()).toBeVisible();
    await page.locator('.word-answer').first().fill('wrong');
    await page.click('.word-check button:has-text("Comprobar")');

    // "Practicar ... 語" — detour into sentence practice with the same missed word.
    await page.click('.word-actions button:has-text("語")');
    await expect(page.locator('.sentence-cards')).toBeVisible();

    // Back to 字 — must still resolve lex-astutos via noam, not fall back to /translate.
    await page.click('button.mode-btn[data-mode="words"]');

    await expect.poll(() => seedRequests.length).toBe(2);
    expect(seedRequests[1]).toEqual([{ lexemeId: 'lex-astutos', spanish: 'astutos', english: 'shrewd' }]);
    expect(translateCalled).toBe(false);
});
