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

test('clicking last sentence card does not scroll it out of the chat-messages view', async ({ page }) => {
    await page.setViewportSize({ width: 900, height: 500 });

    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    const sentences = [
        { hint: 'E·····…', sentence: 'Every time he travels abroad, he blows all his money on souvenirs.' },
        { hint: 'S·····…', sentence: 'She never misses an opportunity to learn something new and exciting.' },
        { hint: 'T·····…', sentence: 'They argued about the best way to spend their upcoming summer vacation.' },
        { hint: 'H·····…', sentence: 'He decided to take a completely different route home to avoid the traffic.' },
        { hint: 'W·····…', sentence: 'We often forget how incredibly lucky we are to have access to clean water.' },
        { hint: 'I·····…', sentence: 'It is extremely important to stay well hydrated when exercising in the heat.' },
    ];

    await page.evaluate((sentences) => {
        window.addMessage('Aquí tienes algunas oraciones de práctica:', 'assistant', null, sentences, null);
    }, sentences);

    // Pre-populate textarea (guarantees separator newline on click → textarea growth) and scroll to bottom
    await page.evaluate(() => {
        const input = document.querySelector('#chatInput');
        input.value = 'Yo quiero practicar.';
        input.dispatchEvent(new Event('input'));
        const chatMessages = document.querySelector('.chat-messages');
        chatMessages.scrollTop = chatMessages.scrollHeight;
    });

    const lastCard = page.locator('.sentence-card').last();
    await expect(lastCard).toBeVisible();

    await lastCard.click();

    const afterCardBox = await lastCard.boundingBox();
    const afterChatBox = await page.locator('.chat-messages').boundingBox();

    // The last card must still be within the chat-messages visible area (not clipped below the fold)
    expect(afterCardBox.y + afterCardBox.height).toBeLessThanOrEqual(afterChatBox.y + afterChatBox.height + 1);
});
