const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

// The System Design coach: a 19-case grid with 🎲 Random (cases only) and a separate
// "Fundamentals drill" button. The drill reuses the Java coach's hint/reveal/next row,
// which is gated on the *conversation being a drill*, not on the coach slug — so an
// interview chat (same slug) must never get it.

const CASES = [
    'Scale from zero to millions of users', 'Rate limiter', 'Consistent hashing', 'Key-value store',
    'Unique ID generator', 'URL shortener', 'Web crawler', 'Notification system', 'News feed',
    'Chat system', 'Search autocomplete', 'YouTube', 'Google Drive', 'Twitter home timeline',
    'Transactions and isolation', 'Coordination and leader election',
    'Batch pipelines and search indexes', 'CDC, event sourcing and stream joins',
    'Unbundled databases and dataflow',
];
const DRILL = 'Fundamentals drill';
const CONVERSATION_ID = 'sd-1';

/**
 * Fake backend. The listing and transcript are built from the last started chat so
 * the sidebar row, conversationCoach and the reopened pane all agree, like the server.
 */
async function setup(page) {
    const state = { posts: [], preview: null };
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) })
    );
    await page.route('**/api/coaches/system-design/topics', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(CASES) })
    );
    await page.route('**/api/conversations', route =>
        route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify(state.preview
                ? [{ conversationId: CONVERSATION_ID, coachType: 'system-design', preview: state.preview }]
                : []),
        })
    );
    await page.route(`**/api/conversations/${CONVERSATION_ID}`, route =>
        route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify([
                { role: 'user', content: 'Start the interview.' },
                { role: 'assistant', content: 'opening for ' + state.preview },
            ]),
        })
    );
    await page.route('**/api/chat', route => {
        const body = JSON.parse(route.request().postData());
        state.posts.push(body);
        if (body.coachType === 'system-design')
            state.preview = 'System Design · ' + body.topic;
        return route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({ conversationId: CONVERSATION_ID, answer: 'answer to ' + (body.message || body.topic) }),
        });
    });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    return state;
}

async function openSetup(page) {
    await page.click('input[name="coach"][value="system-design"]');
    await expect(page.locator('#systemDesignTopicGrid')).toBeVisible();
}

test('the grid has the 19 cases, a Random button and a Fundamentals drill button', async ({ page }) => {
    await setup(page);
    await openSetup(page);

    const grid = page.locator('#systemDesignTopicGrid');
    await expect(grid.locator('.topic-button:not(.random-topic-button):not(.drill-topic-button)')).toHaveCount(19);
    await expect(grid.locator('.topic-button').first()).toHaveText(CASES[0]);
    await expect(grid.locator('.random-topic-button')).toHaveCount(1);
    await expect(grid.locator('.drill-topic-button')).toHaveText(DRILL);
});

test('Random starts one of the cases and never the drill', async ({ page }) => {
    const state = await setup(page);
    for (let i = 0; i < 6; i++) {
        state.posts.length = 0;
        // The radio stays checked after a start, and re-clicking a checked radio fires
        // no change event — go back to None so each round re-enters the setup screen.
        await page.click('input[name="coach"][value="none"]');
        await openSetup(page);
        await page.click('#systemDesignTopicGrid .random-topic-button');
        await expect(page.locator('.message.assistant').last()).toContainText('opening for System Design');
        expect(state.posts).toHaveLength(1);
        expect(state.posts[0].coachType).toBe('system-design');
        expect(CASES).toContain(state.posts[0].topic);
    }
});

test('an interview chat has no hint / reveal / next row', async ({ page }) => {
    const state = await setup(page);
    await openSetup(page);
    await page.click('#systemDesignTopicGrid .topic-button >> text=Rate limiter');

    await expect(page.locator('.message.assistant').last()).toContainText('System Design · Rate limiter');
    expect(state.posts[0].topic).toBe('Rate limiter');
    await expect(page.locator('.java-controls-row')).toHaveCount(0);
    await expect(page.locator('#chatInput')).toBeEnabled();
});

test('a drill chat shows the hint / reveal / next row', async ({ page }) => {
    const state = await setup(page);
    await openSetup(page);
    await page.click('#systemDesignTopicGrid .drill-topic-button');

    await expect(page.locator('.java-controls-row')).toHaveCount(1);
    await expect(page.locator('.java-controls-row .java-hint')).toBeVisible();
    await expect(page.locator('.java-controls-row .java-reveal')).toBeVisible();
    expect(state.posts[0]).toMatchObject({ coachType: 'system-design', topic: DRILL, message: '' });

    await page.locator('.java-controls-row .next-question').click();
    await expect(page.locator('.message.assistant').last()).toContainText('answer to Next question.');
    await expect(page.locator('.java-controls-row')).toHaveCount(2);
});

test('reopening a drill from the sidebar leaves the controls absent', async ({ page }) => {
    await setup(page);
    await openSetup(page);
    await page.click('#systemDesignTopicGrid .drill-topic-button');
    await expect(page.locator('.java-controls-row')).toHaveCount(1);

    await page.click('input[name="coach"][value="none"]');
    await page.click('.conversation-item');

    await expect(page.locator('.message.assistant').last()).toContainText('opening for');
    await expect(page.locator('.java-controls-row')).toHaveCount(0);
});

test('the sidebar item carries the database icon, the case name and the system-design-chat class', async ({ page }) => {
    await setup(page);
    await openSetup(page);
    await page.click('#systemDesignTopicGrid .topic-button >> text=Rate limiter');
    await expect(page.locator('.message.assistant').last()).toContainText('System Design · Rate limiter');

    const item = page.locator('.conversation-item');
    await expect(item).toHaveClass(/system-design-chat/);
    await expect(item.locator('svg.system-design-logo-icon')).toHaveCount(1);
    await expect(item).toHaveText('Rate limiter');
    await expect(item).toHaveCSS('color', 'rgb(212, 220, 116)');
});

test('the composer is blocked until a case is picked', async ({ page }) => {
    await setup(page);
    await openSetup(page);

    await page.fill('#chatInput', 'hello');
    await page.press('#chatInput', 'Enter');

    await expect(page.locator('#composerError')).toHaveText('Select a topic first');
});
