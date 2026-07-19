const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

const TRANSLATE_RESPONSE = {
    setId: 'test-set-123',
    items: [
        { english: 'Cat', hint: 'g···', spanish: 'gato' },
        { english: 'Dog', hint: 'p····', spanish: 'perro' },
    ],
};

const CHECK_RESPONSE = {
    results: [
        { english: 'Cat', spanish: 'gato', correct: true, fullHint: false },
        { english: 'Dog', spanish: 'perro', correct: false, fullHint: false },
    ],
};

test('Comprobar button is hidden after checking answers in 字 mode', async ({ page }) => {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );
    await page.route('**/api/spanish/words/translate', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(TRANSLATE_RESPONSE) })
    );
    await page.route('**/api/spanish/words/check', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(CHECK_RESPONSE) })
    );

    await page.goto('/');

    // Switch to Spanish 字 (words) mode by clicking the 字 button
    await page.click('button.mode-btn[data-mode="words"]');

    // Wait for the 字 setup message
    await expect(page.locator('.message.assistant').first()).toBeVisible();

    // Type words and submit to trigger the word quiz
    await page.fill('#chatInput', 'gato, perro');
    await page.click('#sendButton');

    // Wait for the Comprobar button to appear
    const comprobar = page.locator('button.topic-button', { hasText: 'Comprobar' });
    await expect(comprobar).toBeVisible();

    // Click Comprobar to submit answers
    await comprobar.click();

    // After results are shown, the Comprobar button should be hidden
    await expect(comprobar).not.toBeVisible();
});
