const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

test('opening an Español conversation from history resets the glyph to 語', async ({ page }) => {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify([
                { conversationId: 'spanish-1', preview: 'Español · Ser y estar', coachType: 'spanish' },
            ]),
        })
    );
    await page.route('**/api/conversations/spanish-1', route =>
        route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify([
                { role: 'user', content: 'Ask me the first question.' },
                { role: 'assistant', content: 'Traduce: The cat sleeps.' },
            ]),
        })
    );

    await page.goto('/');

    // Switch the glyph to 文 (documents mode) before touching history — this is
    // client-only state, unrelated to any stored conversation.
    await page.click('button.mode-btn[data-mode="documents"]');
    await expect(page.locator('button.mode-btn[data-mode="documents"]')).toHaveClass(/active/);

    await page.click('.conversation-item.spanish-chat');

    await expect(page.locator('button.mode-btn[data-mode="language"]')).toHaveClass(/active/);
    await expect(page.locator('button.mode-btn[data-mode="documents"]')).not.toHaveClass(/active/);
});
