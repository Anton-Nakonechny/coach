const { test, expect } = require('@playwright/test');

const MODELS_RESPONSE = {
    models: [{ key: 'sonnet-4-6', id: 'claude-sonnet-4-6', label: 'Sonnet 4.6', supportsEffort: true, adaptiveThinking: true }],
    effortLevels: ['low', 'medium', 'high', 'max'],
    defaultModel: 'sonnet-4-6',
    defaultEffort: 'medium',
};

const TRANSLATE_RESPONSE = {
    setId: 'test-set-id',
    items: [
        { english: 'To go unnoticed', hint: 'pasa·················', spanish: 'pasar desapercibido' },
        { english: 'Right place',     hint: 'el s·',               spanish: 'el sitio' },
    ],
};

const MAX_SPANISH_LEN = Math.max(...TRANSLATE_RESPONSE.items.map(i => i.spanish.length)); // 19

test('字 word quiz input fields are wide enough to show the longest Spanish answer', async ({ page }) => {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );
    await page.route('**/api/spanish/words/translate', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(TRANSLATE_RESPONSE) })
    );

    await page.goto('/');

    // Select Español coach
    await page.locator('input[name="coach"][value="spanish"]').click();

    // Switch to 字 (words) mode
    await page.locator('.mode-btn[data-mode="words"]').click();

    // Type words and submit
    await page.locator('#chatInput').fill('pasar desapercibido, el sitio');
    await page.locator('#sendButton').click();

    // Wait for quiz inputs to appear
    const inputs = page.locator('.word-answer');
    await expect(inputs).toHaveCount(2);

    // Each input must have an inline min-width set (in ch) >= max spanish word length
    const inputWidths = await inputs.evaluateAll(els =>
        els.map(el => el.style.minWidth)
    );

    for (const w of inputWidths) {
        expect(parseInt(w, 10)).toBeGreaterThanOrEqual(MAX_SPANISH_LEN);
    }
});
