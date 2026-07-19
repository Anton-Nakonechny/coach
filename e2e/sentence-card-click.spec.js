const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

test('clicking first sentence card fills input without leading newline', async ({ page }) => {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await page.evaluate(() => {
        window.addMessage(
            'Every time he travels abroad, he blows all his money on souvenirs.',
            'assistant',
            null,
            [{ hint: 'C·····…', sentence: 'Every time he travels abroad, he blows all his money on souvenirs.' }],
            null
        );
    });

    const firstCard = page.locator('.sentence-card').first();
    await expect(firstCard).toBeVisible();
    await firstCard.click();

    const inputValue = await page.locator('#chatInput').inputValue();
    expect(inputValue).not.toMatch(/^\n/);
    expect(inputValue).toContain('Every time he travels abroad');
});
