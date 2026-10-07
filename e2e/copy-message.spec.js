const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

test('copy button writes message text to clipboard', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);

    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await page.evaluate(() => window.addMessage('draw me an owl', 'user'));

    const btn = page.locator('.message.user .copy-button');
    await expect(btn).toBeVisible();

    await btn.click();
    const text = await page.evaluate(() => navigator.clipboard.readText());
    expect(text).toBe('draw me an owl');
});

test('copy button works for assistant markdown messages', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);

    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    const md = '**bold** and _italic_';
    await page.evaluate((content) => {
        document.getElementById('chatMessages').innerHTML = '';
        window.addMessage(content, 'assistant');
    }, md);

    const btn = page.locator('.message.assistant .copy-button');
    await expect(btn).toBeVisible();

    await btn.click();
    const text = await page.evaluate(() => navigator.clipboard.readText());
    expect(text).toBe(md);
});

// The confirmation is reset by a timer, and a second click lands while the first
// click's timer is still pending: unless that one is cancelled, it fires mid-window
// and wipes the checkmark the second click just set.
test('copy button keeps its confirmation when clicked twice', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);

    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );

    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => window.addMessage('draw me an owl', 'user'));

    const btn = page.locator('.message.user .copy-button');
    await btn.click();
    await page.waitForTimeout(800);
    await btn.click();
    await page.waitForTimeout(600);   // 1400ms after the first click, 600ms after the second

    await expect(btn.locator('svg polyline')).toBeVisible();
});
