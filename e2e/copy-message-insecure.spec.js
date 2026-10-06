const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

// A phone can never reach the dev server on `localhost` — localhost on a phone is
// the phone. It reaches this Mac by mDNS name (http://anakon.local:9999) or by LAN
// IP (http://192.168.0.13:9999). Neither is one of Chromium/WebKit's *privileged
// origins* (only `localhost`, `*.localhost`, 127.0.0.0/8 and [::1] are), so the
// page is denied a secure context and the browser removes `navigator.clipboard`
// outright. `localhost` is the sole reason the copy button works on the dev's own
// machine, and `copy-message.spec.js` only ever tests that origin.
//
// Reproduce the phone's condition by resolving a non-privileged hostname to the
// same local server: same app, same port, insecure origin.
const port = process.env.TEST_PORT || 9999;
const INSECURE_ORIGIN = `http://coach.test:${port}`;
const SECURE_ORIGIN   = `http://localhost:${port}`;

test.use({
    launchOptions: { args: [`--host-resolver-rules=MAP coach.test 127.0.0.1`] },
});

async function stubApi(page) {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) })
    );
}

// Guards the reproduction itself: if this fails, --host-resolver-rules stopped
// working and the three tests below are no longer exercising the phone's context.
test('the phone origin is insecure and has no Clipboard API', async ({ page }) => {
    await stubApi(page);
    await page.goto(`${INSECURE_ORIGIN}/`);
    await page.waitForLoadState('networkidle');

    const env = await page.evaluate(() => ({
        origin: location.origin,
        isSecureContext: window.isSecureContext,
        hasClipboard: !!navigator.clipboard,
    }));

    expect(env.origin).toBe(INSECURE_ORIGIN);
    expect(env.isSecureContext).toBe(false);
    expect(env.hasClipboard).toBe(false);
});

test('copy button copies message text on an insecure origin', async ({ page, context }) => {
    // readText() is unavailable on the insecure origin, so the clipboard is read
    // back from the secure one afterwards — the clipboard itself is browser-wide.
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: SECURE_ORIGIN });

    await stubApi(page);
    await page.goto(`${INSECURE_ORIGIN}/`);
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => window.addMessage('draw me an owl', 'user'));

    await page.locator('.message.user .copy-button').click();

    await page.goto(`${SECURE_ORIGIN}/`);
    const text = await page.evaluate(() => navigator.clipboard.readText());
    expect(text).toBe('draw me an owl');
});

test('copy button confirms the copy on an insecure origin', async ({ page }) => {
    // The silent `if (!navigator.clipboard) return;` is why this reads as flaky
    // rather than broken: the tap lands, and absolutely nothing happens on screen.
    await stubApi(page);
    await page.goto(`${INSECURE_ORIGIN}/`);
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => window.addMessage('draw me an owl', 'user'));

    const btn = page.locator('.message.user .copy-button');
    await btn.click();

    await expect(btn.locator('svg polyline')).toBeVisible();
});

// Distinct failure mode, reachable even over HTTPS: `navigator.clipboard` exists
// but the write is rejected — a denied permission, or an iOS in-app WKWebView
// (opening the link from Telegram/Slack/Gmail). `.catch(() => {})` swallows it.
test('copy button falls back when writeText rejects', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: SECURE_ORIGIN });

    await stubApi(page);
    await page.addInitScript(() => {
        const real = navigator.clipboard;   // keep a real reader for the assertion
        Object.defineProperty(navigator, 'clipboard', {
            configurable: true,
            value: {
                writeText: () => Promise.reject(new DOMException('denied', 'NotAllowedError')),
                readText: () => real.readText(),
            },
        });
    });
    await page.goto(`${SECURE_ORIGIN}/`);
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => window.addMessage('draw me an owl', 'user'));

    await page.locator('.message.user .copy-button').click();

    const text = await page.evaluate(() => navigator.clipboard.readText());
    expect(text).toBe('draw me an owl');
});
