const { test, expect } = require('@playwright/test');
const { MODELS_RESPONSE } = require('./fixtures');

// Deleting a noam document from the 文 mode: the per-card dustbin on the Documentos
// grid and the one beside the title on a document's study list. Both go through the
// same confirmation modal, and neither fires DELETE until it is confirmed. noam is
// stubbed throughout (different origin), so nothing here needs noam running.

const NOAM_BASE = 'http://noam.test';
const DOC_ID = 'doc-extracted';

async function routeBase(page) {
    await page.route('**/api/models', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(MODELS_RESPONSE) }));
    await page.route('**/api/conversations', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify([]) }));
    await page.route('**/api/noam/config', route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify({ baseUrl: NOAM_BASE, profileId: 'p-1' }) }));
}

// The list is served from a mutable array so a confirmed DELETE can actually remove
// the row from noam's answer, and a later refresh can't resurrect what was deleted.
function routeDocuments(page, docs) {
    return page.route(`${NOAM_BASE}/documents?language=es`, route =>
        route.fulfill({ contentType: 'application/json', body: JSON.stringify(docs) }));
}

const DOCS = () => ([
    { id: DOC_ID, title: 'Un cuento', language: 'es', status: 'EXTRACTED', createdAt: '2026-09-10T10:00:00Z' },
    { id: 'doc-uploaded', title: 'Subiendo todavía', language: 'es', status: 'UPLOADED', createdAt: '2026-09-11T10:00:00Z' },
]);

async function enterNoam(page) {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.click('button.mode-btn[data-mode="documents"]');
    await expect(page.locator('.noam-doc-row')).toHaveCount(2);
}

function card(page, title) {
    return page.locator('.noam-doc-card', { has: page.locator('.noam-doc-title', { hasText: title }) });
}

test.describe('deleting a document from the Documentos grid', () => {
    test('cancelling the confirmation sends no DELETE', async ({ page }) => {
        await routeBase(page);
        await routeDocuments(page, DOCS());
        let deleteCalls = 0;
        await page.route(`${NOAM_BASE}/documents/${DOC_ID}`, async route => {
            deleteCalls++;
            await route.fulfill({ status: 204, body: '' });
        });

        await enterNoam(page);
        await card(page, 'Un cuento').locator('.noam-doc-delete').click();

        // The modal names the document, so a mis-click is visible before confirming.
        await expect(page.locator('.noam-confirm-modal')).toContainText('Un cuento');
        await page.locator('.noam-confirm-modal button:has-text("Cancelar")').click();

        await expect(page.locator('.noam-confirm-modal')).toHaveCount(0);
        await expect(page.locator('.noam-doc-row')).toHaveCount(2);
        expect(deleteCalls).toBe(0);
    });

    test('confirming deletes the document and drops its card', async ({ page }) => {
        await routeBase(page);
        const docs = DOCS();
        await routeDocuments(page, docs);
        let deletedUrl = null;
        await page.route(`${NOAM_BASE}/documents/${DOC_ID}`, async route => {
            deletedUrl = route.request().url();
            expect(route.request().method()).toBe('DELETE');
            docs.splice(0, 1);
            await route.fulfill({ status: 204, body: '' });
        });

        await enterNoam(page);
        await card(page, 'Un cuento').locator('.noam-doc-delete').click();
        await page.locator('.noam-confirm-modal button:has-text("Eliminar")').click();

        await expect(page.locator('.noam-confirm-modal')).toHaveCount(0);
        await expect(page.locator('.noam-doc-row')).toHaveCount(1);
        await expect(card(page, 'Un cuento')).toHaveCount(0);
        expect(deletedUrl).toBe(`${NOAM_BASE}/documents/${DOC_ID}`);
    });

    // A still-processing or failed document is exactly the kind worth dropping, and its
    // card button is disabled — the dustbin must not inherit that.
    test('a non-EXTRACTED document can still be deleted', async ({ page }) => {
        await routeBase(page);
        const docs = DOCS();
        await routeDocuments(page, docs);
        let deleted = false;
        await page.route(`${NOAM_BASE}/documents/doc-uploaded`, async route => {
            deleted = true;
            docs.splice(1, 1);
            await route.fulfill({ status: 204, body: '' });
        });

        await enterNoam(page);
        await expect(card(page, 'Subiendo todavía').locator('.noam-doc-row')).toBeDisabled();
        await expect(card(page, 'Subiendo todavía').locator('.noam-doc-delete')).toBeEnabled();

        await card(page, 'Subiendo todavía').locator('.noam-doc-delete').click();
        await page.locator('.noam-confirm-modal button:has-text("Eliminar")').click();

        await expect(page.locator('.noam-doc-row')).toHaveCount(1);
        expect(deleted).toBe(true);
    });

    // noam refuses with 409 while ingestion is still running. The modal has to stay up
    // with the reason on it: silently closing would read as a delete that worked.
    test('a refused delete keeps the modal open with an error and keeps the card', async ({ page }) => {
        await routeBase(page);
        await routeDocuments(page, DOCS());
        await page.route(`${NOAM_BASE}/documents/doc-uploaded`, route =>
            route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ error: 'Ingestion still running' }) }));

        await enterNoam(page);
        await card(page, 'Subiendo todavía').locator('.noam-doc-delete').click();
        await page.locator('.noam-confirm-modal button:has-text("Eliminar")').click();

        await expect(page.locator('.noam-confirm-modal .noam-modal-error')).toBeVisible();
        await expect(page.locator('.noam-confirm-modal')).toBeVisible();

        await page.locator('.noam-confirm-modal button:has-text("Cancelar")').click();
        await expect(page.locator('.noam-doc-row')).toHaveCount(2);
    });
});

test.describe('deleting a document from its study list', () => {
    test('the dustbin beside the title deletes and returns to the grid', async ({ page }) => {
        await routeBase(page);
        const docs = DOCS();
        await routeDocuments(page, docs);
        await page.route(`${NOAM_BASE}/documents/${DOC_ID}/study-items**`, route =>
            route.fulfill({ contentType: 'application/json', body: JSON.stringify({
                items: [{ lexeme: { id: 'lex-0', displayText: 'palabra0' }, translation: 'word0' }],
            }) }));
        let deleted = false;
        await page.route(`${NOAM_BASE}/documents/${DOC_ID}`, async route => {
            deleted = true;
            docs.splice(0, 1);
            await route.fulfill({ status: 204, body: '' });
        });

        await enterNoam(page);
        await card(page, 'Un cuento').locator('.noam-doc-row').click();
        await expect(page.locator('.noam-study-row')).toHaveCount(1);

        await page.locator('.noam-study-delete').click();
        await expect(page.locator('.noam-confirm-modal')).toContainText('Un cuento');
        await page.locator('.noam-confirm-modal button:has-text("Eliminar")').click();

        // Back on the Documentos grid, without the deleted document.
        await expect(page.locator('.noam-doc-row')).toHaveCount(1);
        await expect(card(page, 'Un cuento')).toHaveCount(0);
        expect(deleted).toBe(true);
    });

    // The Cola tab's list is the profile's study queue, not a document — there is
    // nothing to delete, so the header must not offer a dustbin at all.
    test('the Cola tab has no delete button', async ({ page }) => {
        await routeBase(page);
        await routeDocuments(page, DOCS());
        await page.route(`${NOAM_BASE}/profiles/p-1/study-queue**`, route =>
            route.fulfill({ contentType: 'application/json', body: JSON.stringify([
                { lexeme: { id: 'lex-9', displayText: 'cola0' }, translation: 'queue0' },
            ]) }));

        await enterNoam(page);
        await page.click('.noam-tab[data-tab="cola"]');
        await expect(page.locator('.noam-study-row')).toHaveCount(1);
        await expect(page.locator('.noam-study-delete')).toHaveCount(0);
    });
});
