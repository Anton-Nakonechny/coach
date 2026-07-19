const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

test('Spanish history item shows gualda color', async ({ page }) => {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify([
                { conversationId: 'spanish-1', preview: 'Español · Ser y estar', coachType: 'spanish' },
                { conversationId: 'coo-1',     preview: 'COO · crisis',          coachType: 'chief-operating-officer' },
                { conversationId: 'plain-1',   preview: 'Hello world',           coachType: null },
            ]),
        })
    );

    await page.goto('/');

    const spanishItem = page.locator('.conversation-item.spanish-chat');
    await expect(spanishItem).toBeVisible();

    // gualda = Pantone 116 = #f1bf00 = rgb(241, 191, 0)
    const color = await spanishItem.evaluate(el => getComputedStyle(el).color);
    expect(color).toBe('rgb(241, 191, 0)');

    // COO item should still use the darkorange coach-chat class, not gualda
    const cooItem = page.locator('.conversation-item.coach-chat');
    await expect(cooItem).toBeVisible();
    const cooColor = await cooItem.evaluate(el => getComputedStyle(el).color);
    expect(cooColor).not.toBe('rgb(241, 191, 0)');

    // Plain item should have neither class
    await expect(page.locator('.conversation-item:not(.spanish-chat):not(.coach-chat)')).toBeVisible();
});
