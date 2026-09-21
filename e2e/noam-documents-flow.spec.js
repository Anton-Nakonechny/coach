const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

// T12: the whole 文 chain, end to end — Documentos grid → study-item triage →
// noam-seeded 字 quiz (no LLM) → results → topic grid → 語 practice. Every call
// is stubbed (including noam itself, a different origin): none of this depends
// on noam actually running, on ANTHROPIC_API_KEY, or on persisted conversation
// state. Mirrors routeNoam's structure in noam-study-list.spec.js.

const NOAM_BASE = 'http://noam.test';
const DOC_ID = 'doc-extracted';

async function routeModelsAndConversations(page) {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) }));
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) }));
}

async function enterNoam(page) {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.click('button.mode-btn[data-mode="documents"]');
}

test.describe('noam documents → 字 → 語 happy path', () => {
    test('the whole 文 chain end to end', async ({ page }) => {
        await routeModelsAndConversations(page);
        await page.route('**/api/noam/config', route =>
            route.fulfill({ contentType: 'application/json', body: JSON.stringify({ baseUrl: NOAM_BASE, profileId: 'p-1' }) }));
        await page.route(`${NOAM_BASE}/documents?language=es`, route =>
            route.fulfill({ contentType: 'application/json', body: JSON.stringify([
                { id: DOC_ID, title: 'Un cuento', language: 'es', status: 'EXTRACTED', createdAt: '2026-09-10T10:00:00Z' },
                { id: 'doc-uploaded', title: 'Subiendo todavía', language: 'es', status: 'UPLOADED', createdAt: '2026-09-11T10:00:00Z' },
            ]) }));
        await page.route(`${NOAM_BASE}/documents/${DOC_ID}/study-items**`, route =>
            route.fulfill({ contentType: 'application/json', body: JSON.stringify({
                items: [
                    { lexeme: { id: 'lex-0', displayText: 'palabra0' }, translation: 'word0' },
                    { lexeme: { id: 'lex-1', displayText: 'palabra1' }, translation: 'word1' },
                    { lexeme: { id: 'lex-2', displayText: 'palabra2' }, translation: 'word2' },
                    { lexeme: { id: 'lex-3', displayText: 'palabra3' }, translation: 'word3' },
                ],
            }) }));

        let lexemeStatesBody;
        await page.route('**/api/noam/lexeme-states', async route => {
            lexemeStatesBody = JSON.parse(route.request().postData());
            await route.fulfill({ status: 204, body: '' });
        });

        let seedBody;
        await page.route('**/api/spanish/words/seed', async route => {
            seedBody = JSON.parse(route.request().postData());
            await route.fulfill({ contentType: 'application/json', body: JSON.stringify({
                setId: 'set-1',
                items: seedBody.items.map(it => ({ english: it.english, hint: `${it.spanish[0]}···`, spanish: it.spanish })),
            }) });
        });

        let translateCalled = false;
        await page.route('**/api/spanish/words/translate', async route => {
            translateCalled = true;
            await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ setId: 'x', items: [] }) });
        });

        await page.route('**/api/spanish/words/check', route =>
            route.fulfill({ contentType: 'application/json', body: JSON.stringify({
                results: [
                    { english: 'word1', spanish: 'palabra1', correct: true, fullHint: false },  // green
                    { english: 'word2', spanish: 'palabra2', correct: true, fullHint: true },    // yellow
                    { english: 'word3', spanish: 'palabra3', correct: false, fullHint: false },  // red
                ],
            }) }));

        await page.route('**/api/coaches/spanish/topics', route =>
            route.fulfill({ contentType: 'application/json', body: JSON.stringify([
                { level: 'A1', topics: ['viajes', 'comida'] },
            ]) }));

        let chatBody;
        await page.route('**/api/chat', async route => {
            chatBody = JSON.parse(route.request().postData());
            await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ conversationId: 'convo-1', answer: '' }) });
        });
        await page.route('**/api/conversations/convo-1', route =>
            route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) }));

        await enterNoam(page);

        // 1. UPLOADED is not clickable; EXTRACTED is.
        const uploadedRow = page.locator('.noam-doc-row', { has: page.locator('[data-status="UPLOADED"]') });
        const extractedRow = page.locator('.noam-doc-row', { has: page.locator('[data-status="EXTRACTED"]') });
        await expect(uploadedRow).toBeDisabled();
        await expect(extractedRow).toBeEnabled();

        await extractedRow.click();
        await expect(page.locator('.noam-study-row')).toHaveCount(4);

        // 2. Marking a row conocida unchecks its study checkbox.
        const row0 = page.locator('.noam-study-row').nth(0);
        await row0.locator('.noam-study-check').check();
        await expect(row0.locator('.noam-study-check')).toBeChecked();
        await row0.locator('.noam-mark-known').click();
        await expect(row0.locator('.noam-study-check')).not.toBeChecked();

        // Check the remaining three rows for the quiz.
        for (const i of [1, 2, 3])
            await page.locator('.noam-study-row').nth(i).locator('.noam-study-check').check();

        // 3. Proceed marks lex-0 KNOWN and seeds lex-1..lex-3 with their lexemeIds.
        await page.click('.noam-study-proceed');
        await expect.poll(() => lexemeStatesBody).toEqual({ lexemeIds: ['lex-0'], state: 'KNOWN' });
        await expect.poll(() => seedBody).toBeTruthy();
        expect(seedBody.items.map(it => it.lexemeId).sort()).toEqual(['lex-1', 'lex-2', 'lex-3']);

        // 4. The quiz renders masked hints; /translate (the LLM path) is never called.
        await expect(page.locator('.word-answer')).toHaveCount(3);
        await expect(page.locator('.hint-icon').first()).toHaveAttribute('data-tooltip', /···$/);
        expect(translateCalled).toBe(false);

        await page.locator('.word-answer').nth(0).fill('palabra1');
        await page.locator('.word-answer').nth(1).fill('palabra2');
        await page.locator('.word-answer').nth(2).fill('wrong');
        await page.click('.word-check button:has-text("Comprobar")');

        // 5. The 語 button leads to the topic grid, not straight into a chat.
        await page.click('.word-actions button:has-text("語")');
        await expect(page.locator('.topic-button', { hasText: 'viajes' })).toBeVisible();
        await expect(page.locator('.sentence-cards')).toHaveCount(0);

        // 6. Picking a tema posts /api/chat with coachType 'spanish', the topic, and
        // only the missed (red ∪ yellow) words — not the clean-correct one.
        await page.locator('.topic-button', { hasText: 'viajes' }).click();
        await expect.poll(() => chatBody).toBeTruthy();
        expect(chatBody.coachType).toBe('spanish');
        expect(chatBody.topic).toBe('viajes');
        expect(chatBody.message).toBe('palabra2, palabra3');
    });
});

test.describe('noam unavailable', () => {
    test('the 文 glyph is disabled and 語/字 still work', async ({ page }) => {
        await routeModelsAndConversations(page);
        // A blank profileId is what probeNoamAvailability treats as "not configured".
        await page.route('**/api/noam/config', route =>
            route.fulfill({ contentType: 'application/json', body: JSON.stringify({ baseUrl: NOAM_BASE, profileId: '' }) }));
        await page.route('**/api/coaches/spanish/topics', route =>
            route.fulfill({ contentType: 'application/json', body: JSON.stringify([
                { level: 'A1', topics: ['viajes'] },
            ]) }));

        await page.goto('/');
        await page.waitForLoadState('networkidle');

        await expect(page.locator('button.mode-btn[data-mode="documents"]')).toBeDisabled();

        // 字 still works: switching to it shows the paste-words setup screen.
        await page.click('button.mode-btn[data-mode="words"]');
        await expect(page.locator('.message.assistant').first()).toBeVisible();

        // 語 still works: switching back renders the Spanish topic grid.
        await page.click('button.mode-btn[data-mode="language"]');
        await expect(page.locator('.topic-button', { hasText: 'viajes' })).toBeVisible();
    });
});
