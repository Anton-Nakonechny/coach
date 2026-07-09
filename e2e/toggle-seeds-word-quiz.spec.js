const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

const TRANSLATE_RESPONSE = {
    setId: 'test-set-42',
    items: [
        { english: 'Put a remedy', hint: 'p···…', spanish: 'poner remedio' },
        { english: 'Bay', hint: 'b····', spanish: 'bahía' },
        { english: 'Shrewd', hint: 'a······', spanish: 'astutos' },
        { english: 'In fact', hint: 'd·…', spanish: 'de hecho' },
    ],
};

async function routeDefaults(page) {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );
}

test('toggle from 語 chat with sentence cards seeds the 字 quiz', async ({ page }) => {
    await routeDefaults(page);

    let capturedWords;
    await page.route('**/api/spanish/words/translate', async route => {
        const body = JSON.parse(route.request().postData());
        capturedWords = body.words;
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(TRANSLATE_RESPONSE) });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Inject assistant message with sentence cards: comma-grouped hint + duplicate
    await page.evaluate(() => {
        window.addMessage('Practice these sentences.', 'assistant', null, [
            { hint: 'poner remedio', sentence: 'No hay manera de poner remedio a esto.' },
            { hint: 'bahía, astutos', sentence: 'Los piratas de la bahía eran astutos.' },
            { hint: 'de hecho', sentence: 'De hecho, tenía razón.' },
            { hint: 'poner remedio', sentence: 'Trataron de poner remedio al problema.' },
        ], null);
    });

    // Click 字 mode button to switch
    await page.click('button.mode-btn[data-mode="words"]');

    // Quiz rows should render from the translate response
    await expect(page.locator('.word-answer').first()).toBeVisible();

    // Words sent: split+deduped, preserving first-seen order, no duplicates
    expect(capturedWords).toBe('poner remedio, bahía, astutos, de hecho');
});

test('only the latest sentence block is used when toggling to 字', async ({ page }) => {
    await routeDefaults(page);

    let capturedWords;
    await page.route('**/api/spanish/words/translate', async route => {
        const body = JSON.parse(route.request().postData());
        capturedWords = body.words;
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(TRANSLATE_RESPONSE) });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await page.evaluate(() => {
        window.addMessage('First block.', 'assistant', null, [
            { hint: 'primer', sentence: 'El primer bloque.' },
        ], null);
        window.addMessage('Second block.', 'assistant', null, [
            { hint: 'segundo', sentence: 'El segundo bloque.' },
        ], null);
    });

    await page.click('button.mode-btn[data-mode="words"]');

    await expect(page.locator('.word-answer').first()).toBeVisible();
    expect(capturedWords).toBe('segundo');
});

test('fallback to setup screen when no sentence cards present', async ({ page }) => {
    await routeDefaults(page);

    let translateCalled = false;
    await page.route('**/api/spanish/words/translate', async route => {
        translateCalled = true;
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(TRANSLATE_RESPONSE) });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');
    // No addMessage calls — no sentence cards in chatMessages

    await page.click('button.mode-btn[data-mode="words"]');

    // Should show the 字 setup message, not trigger translate
    await expect(page.locator('.message.assistant').first()).toBeVisible();
    expect(translateCalled).toBe(false);
});
