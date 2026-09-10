// ── 文 documents mode (noam vocabulary platform) ─────────────────
// Entry point + empty tab shell only. T07 fills the Documentos tab, T08 fills
// the Cola tab. Plain script (no modules) loaded after script.js: it reuses
// script.js's top-level `function`/`let` bindings (API_URL, chatMessages,
// spanishModeToggle, resetToSetup, setCoachRadio, activeSetup, GLYPH_LABELS, …)
// and registers its own DOMContentLoaded listener, which runs after script.js's
// handler has assigned the shared DOM variables (that handler is `async` and
// yields at its first `await` right after those assignments).

let noamConfig = null;      // {baseUrl, profileId} from GET /api/noam/config, cached once
let noamAvailable = false;  // true only after a successful availability probe

const NOAM_UNAVAILABLE_TOOLTIP = 'noam no está disponible';

function documentsModeButton() {
    const toggle = spanishModeToggle || document.getElementById('spanishModeToggle');
    return toggle ? toggle.querySelector('.mode-btn[data-mode="documents"]') : null;
}

function disableDocumentsMode(reason) {
    noamAvailable = false;
    const btn = documentsModeButton();
    if (!btn) return;
    btn.disabled = true;
    btn.classList.add('mode-btn-unavailable');
    btn.title = reason;
    btn.setAttribute('data-tooltip', reason);
}

function enableDocumentsMode() {
    noamAvailable = true;
    const btn = documentsModeButton();
    if (!btn) return;
    btn.disabled = false;
    btn.classList.remove('mode-btn-unavailable');
    btn.removeAttribute('title');
    btn.setAttribute('data-tooltip', GLYPH_LABELS['文']);
}

// Availability probe: never allowed to throw/reject past this function, and
// never allowed to block 語/字 — it only ever flips the 文 button's own state.
async function probeNoamAvailability() {
    try {
        const configResp = await fetch(`${API_URL}/noam/config`);
        if (!configResp.ok) { disableDocumentsMode(NOAM_UNAVAILABLE_TOOLTIP); return; }
        const config = await configResp.json();
        if (!config || !config.profileId) { disableDocumentsMode(NOAM_UNAVAILABLE_TOOLTIP); return; }
        noamConfig = config;

        const probeResp = await fetch(`${config.baseUrl}/documents?language=es`);
        if (!probeResp.ok) { disableDocumentsMode(NOAM_UNAVAILABLE_TOOLTIP); return; }
        enableDocumentsMode();
    } catch {
        disableDocumentsMode(NOAM_UNAVAILABLE_TOOLTIP);
    }
}

// Mode shell: clears the chat pane and renders the Documentos/Cola tab bar with
// an empty #noamPanel content area below it. T07/T08 fill in the actual content
// per tab; this task only wires the shell and the tab-switch bookkeeping.
function enterNoamSetup() {
    resetToSetup();
    activeSetup = 'noam';

    const shell = document.createElement('div');
    shell.className = 'noam-shell';

    const tabs = document.createElement('div');
    tabs.className = 'noam-tabs';

    const panel = document.createElement('div');
    panel.id = 'noamPanel';
    panel.className = 'noam-panel';

    const docsTab = noamTabButton('Documentos', 'documentos', panel, tabs);
    const queueTab = noamTabButton('Cola', 'cola', panel, tabs);
    docsTab.classList.add('active');
    panel.dataset.activeTab = 'documentos';

    tabs.appendChild(docsTab);
    tabs.appendChild(queueTab);
    shell.appendChild(tabs);
    shell.appendChild(panel);
    chatMessages.appendChild(shell);
}

function noamTabButton(label, tabId, panel, tabs) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'noam-tab';
    btn.dataset.tab = tabId;
    btn.textContent = label;
    btn.addEventListener('click', () => {
        tabs.querySelectorAll('.noam-tab').forEach(b => b.classList.toggle('active', b === btn));
        panel.dataset.activeTab = tabId;
    });
    return btn;
}

document.addEventListener('DOMContentLoaded', () => {
    // Fire-and-forget: probeNoamAvailability already catches everything itself,
    // but this extra .catch is a belt-and-braces guard against an uncaught
    // rejection ever reaching the page (e.g. from a future edit to that body).
    probeNoamAvailability().catch(() => disableDocumentsMode(NOAM_UNAVAILABLE_TOOLTIP));
});
