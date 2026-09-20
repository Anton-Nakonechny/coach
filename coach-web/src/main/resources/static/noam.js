// ── 文 documents mode (noam vocabulary platform) ─────────────────
// Mode shell (T06) + Documentos tab list/upload (T07) + Cola tab (T08). Plain
// script (no modules) loaded after script.js: it reuses
// script.js's top-level `function`/`let` bindings (API_URL, chatMessages,
// spanishModeToggle, resetToSetup, setCoachRadio, activeSetup, GLYPH_LABELS, …)
// and registers its own DOMContentLoaded listener, which runs after script.js's
// handler has assigned the shared DOM variables (that handler is `async` and
// yields at its first `await` right after those assignments).

let noamConfig = null;      // {baseUrl, profileId} from GET /api/noam/config, cached once
let noamProbe = null;       // in-flight probeNoamAvailability(), awaited by anything that needs noamConfig
let noamTabsEl = null;      // the Documentos/Cola tab bar, so a non-click switch (Cola's back arrow) can update it too
let noamLastActiveTab = 'documentos'; // survives leaving 文 mode entirely (e.g. into a quiz) and coming back

const NOAM_UNAVAILABLE_TOOLTIP = 'noam no está disponible';

function documentsModeButton() {
    const toggle = spanishModeToggle || document.getElementById('spanishModeToggle');
    return toggle ? toggle.querySelector('.mode-btn[data-mode="documents"]') : null;
}

function disableDocumentsMode(reason) {
    const btn = documentsModeButton();
    if (btn) {
        btn.disabled = true;
        btn.setAttribute('data-tooltip', reason);
    }
    // A slow-but-failing probe can lose a race with the user clicking 文 first:
    // spanishMode is already 'documents' by the time this runs. Route back to
    // 語 so the chip reflects a live mode instead of a disabled-and-active 文.
    if (spanishMode === 'documents') setSpanishMode('language');
}

function enableDocumentsMode() {
    const btn = documentsModeButton();
    if (!btn) return;
    btn.disabled = false;
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
        if (!config || !config.baseUrl || !config.profileId) { disableDocumentsMode(NOAM_UNAVAILABLE_TOOLTIP); return; }
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
    tabs.appendChild(docsTab);
    tabs.appendChild(queueTab);
    shell.appendChild(tabs);
    shell.appendChild(panel);
    chatMessages.appendChild(shell);

    noamTabsEl = tabs;
    switchNoamTab(noamLastActiveTab, panel);
}

function noamTabButton(label, tabId, panel, tabs) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'noam-tab';
    btn.dataset.tab = tabId;
    btn.textContent = label;
    btn.addEventListener('click', () => switchNoamTab(tabId, panel));
    return btn;
}

// Shared by tab-button clicks and Cola's back arrow (T08): both need to flip the
// active tab pixel, remember it for the next enterNoamSetup, and rebuild the panel.
function switchNoamTab(tabId, panel) {
    noamLastActiveTab = tabId;
    if (noamTabsEl) noamTabsEl.querySelectorAll('.noam-tab').forEach(b => b.classList.toggle('active', b.dataset.tab === tabId));
    panel.dataset.activeTab = tabId;
    activateNoamTab(tabId, panel);
}

// ── T07: Documentos tab — list + upload ──────────────────────────
// Everything below fills #noamPanel when the Documentos tab is active.
// openStudyItems() is a stub here — T09 implements its body.

const NOAM_MAX_FILE_SIZE = 32 * 1024 * 1024; // 33,554,432 bytes — mirrors noam's multipart limit (see T01)
const NOAM_STATUS_LABELS = { UPLOADED: 'procesando', PARSED: 'procesando', EXTRACTED: 'listo', FAILED: 'fallido' };

let noamDocuments = null;     // last fetched array of documents, or null before the first successful fetch
let noamDocContent = null;    // the swappable content area inside the Documentos tab (list / empty / error state)
let noamDropError = null;     // the live inline drop-error slot, replaced whenever the tab is rebuilt
let noamFetchSeq = 0;         // generation counter — only the newest list fetch is allowed to render
let noamModalBackdrop = null; // currently-open upload modal, or null
let noamUploadInFlight = false; // holds the upload modal open while its POST is pending

// Tab-switch dispatcher: called both on first entering the noam shell and on every
// Documentos/Cola click. Closes any open upload modal so switching tabs never leaves
// one stranded, flushes any pending study-list marks so switching tabs can't silently
// drop triage the same way Back already guards against, then rebuilds the panel for
// the newly active tab.
function activateNoamTab(tabId, panel) {
    closeUploadModal();
    flushStudyMarksInBackground();
    if (tabId === 'documentos') {
        renderDocumentosTab(panel);
    } else {
        noamDocContent = null;
        noamDropError = null;
        renderColaTab(panel);
    }
}

// Builds the Documentos tab: toolbar (Subir documento / Actualizar) + drop zone + list,
// then kicks off the first fetch. Rebuilt from scratch every time the tab is (re)entered.
function renderDocumentosTab(panel) {
    panel.innerHTML = '';

    const toolbar = document.createElement('div');
    toolbar.className = 'noam-toolbar';

    const uploadBtn = document.createElement('button');
    uploadBtn.type = 'button';
    uploadBtn.className = 'topic-button';
    uploadBtn.textContent = 'Subir documento';
    uploadBtn.addEventListener('click', openUploadModal);

    const refreshBtn = document.createElement('button');
    refreshBtn.type = 'button';
    refreshBtn.className = 'topic-button';
    refreshBtn.textContent = 'Actualizar';
    refreshBtn.addEventListener('click', () => fetchAndRenderDocuments());

    toolbar.appendChild(uploadBtn);
    toolbar.appendChild(refreshBtn);

    const dropError = document.createElement('div');
    dropError.className = 'noam-inline-error';
    dropError.hidden = true;
    noamDropError = dropError;

    const gridHost = document.createElement('div');
    gridHost.className = 'noam-doc-grid-host';

    const content = document.createElement('div');
    content.className = 'noam-doc-content';
    noamDocContent = content;
    gridHost.appendChild(content);

    panel.appendChild(toolbar);
    panel.appendChild(dropError);
    panel.appendChild(gridHost);

    setupNoamDropZone(gridHost);
    fetchAndRenderDocuments();
}

// Shared fetch: populates noamDocuments and re-renders, or shows the error+Reintentar
// state on any failure. `silent` marks a background refresh (the one fired right after a
// successful upload): it skips the loading placeholder AND leaves the last good render in
// place on failure, so a blip can't blank out the optimistic row it was meant to reconcile.
// Every call takes a generation number so a slow response can never overwrite a newer one.
async function fetchAndRenderDocuments({ silent = false } = {}) {
    if (!noamDocContent) return;
    if (!silent) noamDocContent.innerHTML = '<p class="muted">Cargando documentos…</p>';
    const seq = ++noamFetchSeq;
    // The 文 button starts out enabled, so the tab can be entered while the availability
    // probe is still in flight — wait for it rather than declaring noam down for good.
    await noamProbe;
    if (seq < noamFetchSeq) return;
    if (!noamConfig || !noamConfig.baseUrl) {
        if (!silent) renderDocError('noam no está disponible.');
        return;
    }
    try {
        const resp = await fetch(`${noamConfig.baseUrl}/documents?language=es`);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const docs = await resp.json();
        if (seq < noamFetchSeq) return;
        noamDocuments = Array.isArray(docs) ? docs : [];
        renderDocGrid();
    } catch {
        if (seq < noamFetchSeq) return;
        if (!silent) renderDocError();
    }
}

function renderDocError(message) {
    if (!noamDocContent) return;
    noamDocContent.innerHTML = '';
    const wrap = document.createElement('div');
    wrap.className = 'noam-error';
    const msg = document.createElement('p');
    msg.textContent = message || 'No se pudieron cargar los documentos.';
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'topic-button';
    retry.textContent = 'Reintentar';
    retry.addEventListener('click', () => fetchAndRenderDocuments());
    wrap.appendChild(msg);
    wrap.appendChild(retry);
    noamDocContent.appendChild(wrap);
}

function renderDocGrid() {
    if (!noamDocContent) return;
    noamDocContent.innerHTML = '';
    if (!noamDocuments || noamDocuments.length === 0) {
        const empty = document.createElement('p');
        empty.className = 'muted';
        empty.textContent = 'Todavía no hay documentos en español. Sube uno para empezar.';
        noamDocContent.appendChild(empty);
        return;
    }
    const sorted = [...noamDocuments].sort((a, b) => docTime(b) - docTime(a));
    const grid = document.createElement('div');
    grid.className = 'topic-grid noam-doc-grid';
    sorted.forEach(doc => grid.appendChild(buildDocRow(doc)));
    noamDocContent.appendChild(grid);
}

function buildDocRow(doc) {
    const isExtracted = doc.status === 'EXTRACTED';
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'topic-button noam-doc-row';
    if (!isExtracted) btn.classList.add('noam-doc-disabled');
    btn.disabled = !isExtracted;

    const title = document.createElement('span');
    title.className = 'noam-doc-title';
    title.textContent = doc.title || '(sin título)';

    const chip = document.createElement('span');
    chip.className = 'noam-status-chip';
    chip.dataset.status = doc.status;
    chip.textContent = NOAM_STATUS_LABELS[doc.status] || (doc.status || '').toLowerCase();

    const date = document.createElement('span');
    date.className = 'noam-doc-date muted';
    date.textContent = formatNoamDate(doc.createdAt);

    btn.appendChild(title);
    btn.appendChild(chip);
    btn.appendChild(date);

    if (isExtracted) btn.addEventListener('click', () => openStudyItems(doc.id, doc.title));
    return btn;
}

// A missing or unparseable createdAt would make the sort comparator return NaN, which
// leaves the whole array in an arbitrary order rather than just misplacing the bad row.
// Sorting such rows oldest (0) keeps the rest of the list reliably newest-first.
function docTime(doc) {
    const t = new Date(doc.createdAt).getTime();
    return Number.isNaN(t) ? 0 : t;
}

function formatNoamDate(iso) {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso || '';
    return d.toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

// ── T09: Study-item list (triage a document's unknown words) ─────
// Shared list component: renders checkbox/conocida/ignorar rows into #noamPanel
// and owns the Proceed flow. Paging belongs to the caller via `loadPage` — T08's
// Cola tab has no offset, so it reuses this with paging:false.

const NOAM_STUDY_LIMIT = 50;

let noamStudyEntries = null; // Map<lexemeId, {item, checked, mark, rowEl, checkboxEl, knownBtn, ignoreBtn}>
let noamStudyState = null;   // {loadPage, offset, exhausted, loading, paging} for the open list, or null
let noamMarksFlush = Promise.resolve(); // the most recent background marks flush; always settles, never rejects

function openStudyItems(documentId, title) {
    renderNoamItemList(document.getElementById('noamPanel'), {
        title,
        onBack: () => {
            flushStudyMarksInBackground();
            renderDocumentosTab(document.getElementById('noamPanel'));
        },
        paging: true,
        loadPage: (offset) => fetchDocumentStudyItems(documentId, offset),
    });
}

// Normalises noam's StudyItem shape into the {lexemeId, spanish, english} triple
// the row renderer, the marks flush and the 字 quiz hand-off all share.
async function fetchDocumentStudyItems(documentId, offset) {
    await noamProbe;
    if (!noamConfig || !noamConfig.baseUrl) throw new Error('noam no está disponible.');
    const url = `${noamConfig.baseUrl}/documents/${documentId}/study-items` +
        `?profileId=${noamConfig.profileId}&limit=${NOAM_STUDY_LIMIT}&offset=${offset}`;
    const resp = await fetch(url);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data = await resp.json();
    const items = Array.isArray(data.items) ? data.items : [];
    return items.map(it => ({ lexemeId: it.lexeme.id, spanish: it.lexeme.displayText, english: it.translation }));
}

function renderNoamItemList(panel, { title, onBack, loadPage, paging, emptyMessage }) {
    panel.innerHTML = '';
    noamStudyEntries = new Map();
    noamStudyState = {
        loadPage, offset: 0, exhausted: false, loading: false, paging: !!paging,
        emptyMessage: emptyMessage || 'No hay palabras nuevas.',
    };

    const header = document.createElement('div');
    header.className = 'noam-study-header';
    const backBtn = document.createElement('button');
    backBtn.type = 'button';
    backBtn.className = 'icon-button noam-study-back';
    backBtn.setAttribute('aria-label', 'Volver');
    backBtn.textContent = '←';
    backBtn.addEventListener('click', onBack);
    const heading = document.createElement('h3');
    heading.className = 'noam-study-title';
    heading.textContent = title || '';
    header.appendChild(backBtn);
    header.appendChild(heading);

    const toolbar = document.createElement('div');
    toolbar.className = 'noam-study-toolbar';
    const selectAllBtn = document.createElement('button');
    selectAllBtn.type = 'button';
    selectAllBtn.className = 'topic-button';
    selectAllBtn.textContent = 'Seleccionar todo';
    // A one-shot "check every untriaged row" action rather than a synced tri-state
    // checkbox — simpler, and marked rows are deliberately left alone so a mark
    // decision already made isn't silently overridden.
    selectAllBtn.addEventListener('click', () => {
        noamStudyEntries.forEach(entry => {
            if (entry.mark) return;
            entry.checked = true;
            entry.checkboxEl.checked = true;
        });
    });
    toolbar.appendChild(selectAllBtn);

    const list = document.createElement('div');
    list.className = 'noam-study-list';

    const errorEl = document.createElement('div');
    errorEl.className = 'noam-inline-error';
    errorEl.hidden = true;

    const proceedBtn = document.createElement('button');
    proceedBtn.type = 'button';
    proceedBtn.className = 'topic-button noam-study-proceed';
    proceedBtn.textContent = 'Continuar';
    proceedBtn.addEventListener('click', () => proceedStudyItems(proceedBtn, errorEl, list));

    panel.appendChild(header);
    panel.appendChild(toolbar);
    panel.appendChild(list);
    panel.appendChild(errorEl);
    panel.appendChild(proceedBtn);

    if (paging) {
        list.addEventListener('scroll', () => {
            if (list.scrollTop + list.clientHeight >= list.scrollHeight - 80) loadNextStudyPage(list);
        });
    }

    loadNextStudyPage(list);
}

// Fetches the next page and appends its rows. Serves the very first page too
// (offset starts at 0), so both the paging (documents) and single-shot (queue)
// callers drive through the same path. `state !== noamStudyState` after an await
// means renderNoamItemList ran again in the meantime (a different document, or a
// tab switch that goes through it) — the fetch landed for a list nobody shows
// any more, so its result must not touch the current DOM.
async function loadNextStudyPage(list) {
    const state = noamStudyState;
    if (!state || state.loading || state.exhausted) return;
    state.loading = true;
    if (state.offset === 0) list.innerHTML = '<p class="muted">Cargando palabras…</p>';
    try {
        // Both item sources are derived from the very lexeme states a tab switch
        // may have just flushed in the background, and that POST detours through
        // coach-web while these GETs go browser-direct to noam — so without this
        // the read wins the race and re-lists words the user already triaged. The
        // "Cargando…" placeholder is already up, so the wait costs no pixels.
        await noamMarksFlush;
        const items = await state.loadPage(state.offset);
        if (state !== noamStudyState) return;
        if (state.offset === 0) list.innerHTML = '';
        items.forEach(item => addStudyRow(list, item));
        state.offset += items.length;
        if (!state.paging || items.length < NOAM_STUDY_LIMIT) state.exhausted = true;
        updateStudyEmptyState(list);
    } catch (err) {
        if (state !== noamStudyState) return;
        // Stops automatic (scroll-triggered) retries; the button below retries
        // explicitly. On a first-page failure this replaces the "Cargando…"
        // placeholder; on a later page it's appended below the rows already
        // loaded, so a mid-scroll blip never wipes what's already on screen.
        state.exhausted = true;
        if (state.offset === 0) list.innerHTML = '';
        const wrap = document.createElement('div');
        wrap.className = 'noam-error';
        const msg = document.createElement('p');
        msg.textContent = err.message ? humanizeNoamError(err) : 'No se pudieron cargar las palabras.';
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'topic-button';
        retry.addEventListener('click', () => { wrap.remove(); state.exhausted = false; loadNextStudyPage(list); });
        retry.textContent = 'Reintentar';
        wrap.appendChild(msg);
        wrap.appendChild(retry);
        list.appendChild(wrap);
    } finally {
        state.loading = false;
    }
}

// Keeps the "no words" placeholder in sync with `noamStudyEntries`: removes any
// stale one before deciding whether to show a fresh one, so it can never linger
// after Proceed empties the list and a later page then repopulates it.
function updateStudyEmptyState(list) {
    const existing = list.querySelector('.noam-study-empty');
    if (existing) existing.remove();
    if (noamStudyEntries.size === 0) {
        const empty = document.createElement('p');
        empty.className = 'muted noam-study-empty';
        empty.textContent = (noamStudyState && noamStudyState.emptyMessage) || 'No hay palabras nuevas.';
        list.appendChild(empty);
    }
}

function addStudyRow(list, item) {
    const row = document.createElement('div');
    row.className = 'word-row noam-study-row';

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.className = 'noam-study-check';
    checkbox.setAttribute('aria-label', `Estudiar ${item.spanish}`);

    const spanish = document.createElement('span');
    spanish.className = 'noam-study-spanish';
    spanish.textContent = item.spanish;

    const english = document.createElement('span');
    english.className = 'word-english';
    english.textContent = item.english;

    const knownBtn = document.createElement('button');
    knownBtn.type = 'button';
    knownBtn.className = 'topic-button noam-mark-btn noam-mark-known';
    knownBtn.textContent = 'conocida';

    const ignoreBtn = document.createElement('button');
    ignoreBtn.type = 'button';
    ignoreBtn.className = 'topic-button noam-mark-btn noam-mark-ignore';
    ignoreBtn.textContent = 'ignorar';

    const entry = { item, checked: false, mark: null, rowEl: row, checkboxEl: checkbox, knownBtn, ignoreBtn };

    // The checkbox and the marks contradict each other: checking it after a mark
    // clears the mark, and marking (below) unchecks the box.
    checkbox.addEventListener('change', () => {
        entry.checked = checkbox.checked;
        if (entry.checked && entry.mark) setStudyMark(entry, null);
    });
    knownBtn.addEventListener('click', () => setStudyMark(entry, entry.mark === 'KNOWN' ? null : 'KNOWN'));
    ignoreBtn.addEventListener('click', () => setStudyMark(entry, entry.mark === 'IGNORED' ? null : 'IGNORED'));

    row.appendChild(checkbox);
    row.appendChild(spanish);
    row.appendChild(english);
    row.appendChild(knownBtn);
    row.appendChild(ignoreBtn);
    list.appendChild(row);

    noamStudyEntries.set(item.lexemeId, entry);
}

function setStudyMark(entry, mark) {
    entry.mark = mark;
    entry.knownBtn.classList.toggle('active', mark === 'KNOWN');
    entry.ignoreBtn.classList.toggle('active', mark === 'IGNORED');
    if (mark) {
        entry.checked = false;
        entry.checkboxEl.checked = false;
    }
}

// Proceed: flush marks first (so a 502 never silently drops triage), then hand the
// checked items to the 字 quiz. Marks stay in `noamStudyEntries` until the flush
// actually succeeds, so re-clicking after a failure retries the same payload.
async function proceedStudyItems(proceedBtn, errorEl, list) {
    errorEl.hidden = true;
    errorEl.innerHTML = '';

    const { known, ignored } = pendingStudyMarks();
    const checkedItems = [];
    noamStudyEntries.forEach(entry => { if (entry.checked) checkedItems.push(entry.item); });

    if (known.length === 0 && ignored.length === 0 && checkedItems.length === 0) return;

    proceedBtn.disabled = true;
    try {
        if (known.length > 0) await postNoamLexemeStates(known, 'KNOWN');
        if (ignored.length > 0) await postNoamLexemeStates(ignored, 'IGNORED');
    } catch (err) {
        showStudyListError(errorEl, humanizeNoamError(err),
            () => proceedStudyItems(proceedBtn, errorEl, list));
        return;
    } finally {
        // Restored on every exit, the quiz hand-off included: the stub (and later a
        // throwing T10) would otherwise leave Continuar dead with the list still up.
        proceedBtn.disabled = false;
    }

    noamStudyEntries.forEach((entry, lexemeId) => {
        if (entry.mark) {
            entry.rowEl.remove();
            noamStudyEntries.delete(lexemeId);
        }
    });

    if (checkedItems.length === 0) {
        updateStudyEmptyState(list);
        // Removing rows can leave the list too short to scroll, and the scroll is the
        // only thing that ever asks for another page — so top it up from here instead.
        loadNextStudyPage(list);
        return;
    }

    // /seed 400s the whole batch on a blank english, so untranslated items are
    // dropped before the request goes out. When that leaves nothing, say so here —
    // startWordQuizFromNoam has no error slot to report into and would just return,
    // leaving Continuar looking dead with the selection still checked.
    const translated = checkedItems.filter(it => it.english && it.english.trim());
    if (translated.length === 0) {
        showStudyListError(errorEl, 'Las palabras seleccionadas no tienen traducción — elige otras.');
        return;
    }

    startWordQuizFromNoam(translated);
}

// The marks waiting to be flushed, split by state. Shared by Proceed (which awaits
// them and only then drops the rows) and Back (which fires them off and leaves).
function pendingStudyMarks() {
    const known = [];
    const ignored = [];
    noamStudyEntries.forEach(entry => {
        if (entry.mark === 'KNOWN') known.push(entry.item.lexemeId);
        else if (entry.mark === 'IGNORED') ignored.push(entry.item.lexemeId);
    });
    return { known, ignored };
}

// Leaving the list must not block on the network, so the flush goes out unawaited:
// navigation stays instant at the cost of losing the marks if the POST fails. Clearing
// noamStudyEntries afterwards makes this idempotent: activateNoamTab now calls it on
// every tab switch, and a stale list from a prior visit must not get re-flushed each time.
// Both globals are cleared together: noamStudyState alone is what loadNextStudyPage's
// staleness guard compares against, so leaving it installed would wave a page that
// landed after the teardown straight through into the nulled entries map.
function flushStudyMarksInBackground() {
    if (!noamStudyEntries) return;
    const { known, ignored } = pendingStudyMarks();
    const sent = [];
    if (known.length > 0) sent.push(postNoamLexemeStates(known, 'KNOWN'));
    if (ignored.length > 0) sent.push(postNoamLexemeStates(ignored, 'IGNORED'));
    // Chained onto the flush before it rather than replacing it: a list rendered
    // since that flush has no marks of its own, so its sent[] is empty and settles
    // at once — a read waiting only on that would stop waiting for a POST still on
    // the wire. allSettled both handles the rejections (nothing else awaits these)
    // and gives the next study-item read something to wait on that can never reject.
    noamMarksFlush = Promise.allSettled([noamMarksFlush, ...sent]);
    noamStudyEntries = null;
    noamStudyState = null;
}

async function postNoamLexemeStates(lexemeIds, state) {
    const resp = await fetch(`${API_URL}/noam/lexeme-states`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ lexemeIds, state }),
    });
    if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        throw new Error(err.message || `Error al guardar (HTTP ${resp.status})`);
    }
}

// onRetry is optional: a failed network call gets a Reintentar button, but a
// selection that simply has nothing to send would only repeat itself, so that
// caller omits it and the message alone tells the user what to change.
function showStudyListError(errorEl, message, onRetry) {
    errorEl.innerHTML = '';
    errorEl.hidden = false;
    const p = document.createElement('p');
    p.textContent = message;
    errorEl.appendChild(p);
    if (!onRetry) return;
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'topic-button';
    retry.textContent = 'Reintentar';
    retry.addEventListener('click', onRetry);
    errorEl.appendChild(retry);
}

// ── T08: Cola tab — the profile's spaced-repetition study queue ──
// Second source of study items: instead of a document's unknown words, it's
// "words this profile is due to review", straight from noam's scheduler. Reuses
// T09's renderNoamItemList/loadPage seam with paging:false (the endpoint has no
// offset), so the row rendering, marks and Proceed hand-off are identical.

function renderColaTab(panel) {
    renderNoamItemList(panel, {
        title: 'Cola',
        onBack: () => switchNoamTab('documentos', panel),
        paging: false,
        loadPage: fetchQueueStudyItems,
        emptyMessage: 'No hay palabras pendientes.',
    });
}

// Normalises noam's study-queue entry ({lexeme, state, translation}) into the
// same {lexemeId, spanish, english} triple fetchDocumentStudyItems produces.
async function fetchQueueStudyItems() {
    await noamProbe;
    if (!noamConfig || !noamConfig.baseUrl) throw new Error('noam no está disponible.');
    const url = `${noamConfig.baseUrl}/profiles/${noamConfig.profileId}/study-queue?limit=${NOAM_STUDY_LIMIT}`;
    const resp = await fetch(url);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data = await resp.json();
    const items = Array.isArray(data) ? data : [];
    return items.map(it => ({ lexemeId: it.lexeme.id, spanish: it.lexeme.displayText, english: it.translation }));
}

// ── T10: seed the 字 quiz from the noam study-item selection ──────
// Mints a set via /seed (no LLM call) from the checked {lexemeId, spanish, english}
// triples, then hands off to the same buildWordCheck/checkWords the hand-typed 字
// quiz uses — a 文-sourced set is indistinguishable in the DOM.

// Map<responseSpanish, {lexemeId, spanish, english}[]> for the active 文-sourced set,
// or null when the open 字 quiz (if any) was hand-typed/LLM-translated. A multimap,
// not a single-valued map: two different lexemes can share a spanish surface form
// (vino, como, bajo, sobre — real Spanish homographs), so a plain Map keyed by
// spanish would let one overwrite the other's cache entry. Consulted (and drained
// per-lookup via shift()) by the patched retryMissedInWords below, and cleared by
// the patched setSpanishMode.
let noamWordSource = null;

async function startWordQuizFromNoam(items) {
    // /seed rejects the whole batch if any item has a blank english (noam study
    // items can be untranslated), and by the time that happens the checked
    // selection is already gone from the study list — so drop those items here,
    // before the request goes out, instead of losing the whole selection to a 400.
    items = (items || []).filter(it => it.english && it.english.trim());
    if (items.length === 0) return;
    chatInput.disabled = true;
    sendButton.disabled = true;
    attachButton.disabled = true;
    // The 語/字/文 glyphs aren't composer controls, but a click here would run
    // selectSpanishMode synchronously and then get stomped when this fetch resolves
    // (setSpanishMode('words') + resetToSetup()) — so disable them for the duration too.
    const glyphButtons = spanishModeToggle ? [...spanishModeToggle.querySelectorAll('.mode-btn')] : [];
    glyphButtons.forEach(b => { b.disabled = true; });

    const loadingMessage = createLoadingMessage();
    chatMessages.appendChild(loadingMessage);
    chatMessages.scrollTop = chatMessages.scrollHeight;

    try {
        const resp = await fetch(`${API_URL}/spanish/words/seed`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ items }),
        });
        const data = await resp.json().catch(() => ({}));
        if (!resp.ok) throw new Error(data.message || 'Error seeding word set');
        loadingMessage.remove();
        // The glyph toggle still reads 文 (documents mode) at this point — flip it to
        // 字 so the quiz looks and behaves like a normal one: without this, clicking 字
        // while this quiz is showing would treat it as a fresh mode switch instead of a
        // no-op and blow the quiz away into the paste-words screen.
        setSpanishMode('words');
        resetToSetup();
        activeSetup = null;
        cacheNoamWordSource(items, data.items);
        buildWordCheck(data.setId, data.items);
    } catch (err) {
        loadingMessage.remove();
        addError(err);
    } finally {
        chatInput.disabled = false;
        sendButton.disabled = false;
        attachButton.disabled = false;
        glyphButtons.forEach(b => { b.disabled = false; });
    }
}

// Mirrors Text.stripEdges (coach-core): trims leading/trailing non-letter characters
// so a request's spanish matches the /seed response's edge-stripped value.
function stripEdges(s) {
    return s.replace(/^[^\p{L}]+|[^\p{L}]+$/gu, '');
}

// The /seed response edge-strips each spanish value (Text.stripEdges), so it can
// differ from the raw noam displayText sent in. Key the cache by the RESPONSE's
// spanish — that's also what /check later echoes back in results[].spanish — and
// recover the matching lexemeId via the (locally edge-stripped) request spanish.
// Keying by spanish rather than english matters: two study items routinely share an
// english gloss (saber/conocer → "to know"), and a plain Map keyed by english would
// collapse them, silently reporting both duplicate-gloss words' grades to one lexeme.
// Both bySpanish and noamWordSource are multimaps (arrays per key), not single-valued
// Maps: two lexemes can also share the same SPANISH surface form (homographs), and a
// plain Map.set would let the second overwrite the first here too. Within one such
// key, position cannot be used to pair request to response: SpanishWordController.seed
// shuffles the pairs before responding, so the two are same-length but NOT same-order.
// English is what separates homograph twins ("wine" vs "he came" for vino) and /seed
// echoes it back untouched, so match on it and fall back to the oldest queued entry
// when it doesn't resolve — a wrong pairing here would report one lexeme's SRS grade
// to the other on the next re-seeded pass.
function cacheNoamWordSource(requestItems, responseItems) {
    const bySpanish = new Map();
    requestItems.forEach(it => {
        const key = stripEdges(it.spanish);
        if (!bySpanish.has(key)) bySpanish.set(key, []);
        bySpanish.get(key).push(it);
    });
    noamWordSource = new Map();
    responseItems.forEach(respItem => {
        const queue = bySpanish.get(respItem.spanish);
        if (!queue || queue.length === 0) return;
        const match = queue.findIndex(it => it.english === respItem.english);
        const [src] = queue.splice(match >= 0 ? match : 0, 1);
        if (!noamWordSource.has(respItem.spanish)) noamWordSource.set(respItem.spanish, []);
        noamWordSource.get(respItem.spanish).push({ lexemeId: src.lexemeId, spanish: respItem.spanish, english: respItem.english });
    });
}

// Hook #1: while a 文-sourced quiz is active, "De nuevo 字" and the missed-words
// "字" button (both call this) must re-seed via /seed instead of translateWords'
// LLM call, so every graded pass keeps reporting to noam. A hand-typed 字 quiz never
// populates noamWordSource, so it always falls through to the original behaviour.
const retryMissedInWordsViaLlm = retryMissedInWords;
retryMissedInWords = function (words) {
    if (noamWordSource) {
        // Require every missed word to be in the cache: a partial hit would silently
        // drop the cache-miss words from the redrill instead of falling back for them.
        // .shift() (not .get()) so that if the missed list contains the same surface
        // form twice (two homograph twins both missed), each occurrence drains a
        // different queued lexemeId instead of both resolving to the same one.
        const items = words.map(w => { const q = noamWordSource.get(w); return q && q.shift(); }).filter(Boolean);
        if (items.length === words.length) {
            setCoachRadio('spanish');
            setSpanishMode('words');
            startWordQuizFromNoam(items);
            return;
        }
    }
    retryMissedInWordsViaLlm(words);
};

// Hook #2: clear on leaving 字 mode entirely (→ 語 or 文). Guard on `mode !== 'words'`
// rather than clearing unconditionally: selectSpanishMode (script.js) always calls
// setSpanishMode('words') itself, synchronously, right before dispatching to
// retryMissedInWords whenever pendingMissedWords is still set and the glyph is
// re-clicked while already on 字 — an unconditional clear here would wipe the cache
// out from under that very call, before Hook #1 ever gets to read it (caught by hand:
// re-clicking 字 right after grading a noam quiz silently fell back to an LLM call).
// Staying in 'words' never needs a clear on its own — Hook #3 below clears whenever an
// LLM-backed quiz actually mints, which is the only event that makes the cache stale.
//
// The optional opts.preserveNoamSource escape hatch exists for exactly one caller:
// practiceMissed's "Practicar ... 語" detour (script.js), which sends the SAME missed
// words into a 語 sentence-practice conversation the user is expected to return from.
// Without it, openConversation's unconditional setSpanishMode('language') would wipe
// the cache before the round trip even completes, so the later 字 click could never
// report back to noam even though the words never actually changed. Every other
// caller (sidebar history clicks, the 文-unavailable fallback, etc.) omits opts and
// keeps clearing as before — only this one flow claims the words are still live.
const setSpanishModeBase = setSpanishMode;
setSpanishMode = function (mode, opts) {
    if (mode !== 'words' && !(opts && opts.preserveNoamSource)) noamWordSource = null;
    setSpanishModeBase(mode);
};

// Hook #3: translateWords is the sole entry point for an LLM-backed 字 quiz — the
// fresh hand-typed paste, and Hook #1's own fallback when a missed word isn't in the
// cache. Clearing right here (rather than trying to catch every possible "the user
// moved on" event) means the cache can only ever go stale between one noam quiz and
// the next real LLM mint, never in between.
const translateWordsViaLlm = translateWords;
translateWords = function (words) {
    noamWordSource = null;
    return translateWordsViaLlm(words);
};

// ── T11: topic screen between the 字 results and 語 practice ─────
// The normal 字→語 hand-off (practiceMissed) is topic-less, but a 文-sourced quiz's
// vocabulary should get drilled through a chosen grammar structure, so this inserts
// a topic pick between the results screen and the sentence-practice chat.

// Renders the shared Spanish topic grid (same fetch/cache/render enterSpanishSetup
// uses) into the chat pane, with a short explainer above it. Picking a topic starts
// the practice immediately — no separate confirm button.
async function chooseTopicThenPractice(words) {
    const topics = await enterTopicSetup({
        welcome: `Elige un tema para practicar las palabras falladas en modo ` +
            `<span data-tooltip="${GLYPH_LABELS['語']}">語</span>.`,
        setupName: 'spanish',
        endpoint: '/coaches/spanish/topics',
        gridId: 'topicGrid',
        cached: spanishTopics,
        render: renderSpanishTopicSections,
        // preserveNoamSource: startCoachChat's openConversation call would otherwise
        // clear noamWordSource (Hook #2 below) the moment the practice chat opens,
        // the same loss practiceMissed's own preserveNoamSource already guards
        // against on its topic-less route into 語.
        onPick: (topic) => startCoachChat({
            message: words.join(', '),
            model: currentModel,
            effort: currentEffort,
            coachType: 'spanish',
            topic,
        }, 'Failed to start practice', { preserveNoamSource: true }),
    });
    if (topics) spanishTopics = topics;
}

// Hook #4: practiceMissed is the one function both the results screen's "語" button
// and the glyph-toggle shortcut (selectSpanishMode) call to enter 語 — wrapping it
// once here routes both through the topic screen whenever the missed words came
// from noam, instead of patching each call site separately.
const practiceMissedTopicless = practiceMissed;
practiceMissed = function (words) {
    if (noamWordSource) { chooseTopicThenPractice(words); return; }
    practiceMissedTopicless(words);
};

// ── Drag-and-drop onto the grid ───────────────────────────────
// Mirrors setupDragAndDrop()'s visual idiom from script.js (dragCounter + .drop-overlay
// shown/hidden on dragenter/dragleave/drop) but scoped to the Documentos grid host
// instead of the chat composer, with its own overlay element and its own drop-error slot.

function setupNoamDropZone(gridHost) {
    let dragCounter = 0;
    const isFileDrag = e => e.dataTransfer?.types?.includes('Files');

    const overlay = document.createElement('div');
    overlay.className = 'drop-overlay';
    overlay.hidden = true;
    overlay.setAttribute('aria-hidden', 'true');
    overlay.innerHTML = '<span>⬇ Suelta el archivo para subirlo</span><span class="drop-hint">.txt · .pdf · .epub · .html</span>';
    gridHost.appendChild(overlay);

    const show = () => { gridHost.classList.add('drag-over'); overlay.hidden = false; };
    const hide = () => { gridHost.classList.remove('drag-over'); overlay.hidden = true; };

    gridHost.addEventListener('dragenter', (e) => {
        if (!isFileDrag(e)) return;
        e.preventDefault();
        if (++dragCounter === 1) show();
    });
    gridHost.addEventListener('dragleave', () => {
        dragCounter = Math.max(0, dragCounter - 1);
        if (dragCounter === 0) hide();
    });
    gridHost.addEventListener('dragover', (e) => { if (isFileDrag(e)) e.preventDefault(); });
    gridHost.addEventListener('drop', (e) => {
        e.preventDefault();
        dragCounter = 0;
        hide();
        const file = e.dataTransfer.files && e.dataTransfer.files[0];
        if (file) handleDroppedFile(file);
    });
}

function stripExtension(filename) {
    const idx = filename.lastIndexOf('.');
    return idx > 0 ? filename.slice(0, idx) : filename;
}

// The error slot is looked up through noamDropError rather than captured, because a tab
// switch mid-upload rebuilds the tab and detaches the element this closure was created
// with — writing into that detached node would report the failure to nobody.
function handleDroppedFile(file) {
    clearNoamInlineError();
    const title = stripExtension(file.name);
    performNoamUpload(file, title, {
        onError: (msg) => showNoamInlineError(msg, () => handleDroppedFile(file)),
    });
}

function showNoamInlineError(message, onRetry) {
    const el = noamDropError;
    if (!el) return;
    el.innerHTML = '';
    el.hidden = false;
    const p = document.createElement('p');
    p.textContent = message;
    el.appendChild(p);
    if (onRetry) {
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'topic-button';
        retry.textContent = 'Reintentar subida';
        retry.addEventListener('click', onRetry);
        el.appendChild(retry);
    }
}

function clearNoamInlineError() {
    if (!noamDropError) return;
    noamDropError.hidden = true;
    noamDropError.innerHTML = '';
}

// ── Shared upload path (modal Subir + drag-and-drop both call this) ──
// The 32 MB client-side check MUST run before the POST: noam's CORS headers are only
// written after multipart size validation, so an oversized upload otherwise surfaces as
// an opaque "Failed to fetch" with no real error message reachable from JS (see the note
// at the bottom of T07's task file).
async function performNoamUpload(file, title, { onError } = {}) {
    if (file.size > NOAM_MAX_FILE_SIZE) {
        onError && onError('El archivo supera el límite de 32 MB.');
        return false;
    }
    // Same reason fetchAndRenderDocuments waits: a file can be dropped or submitted right
    // after entering 文 mode, while the availability probe is still resolving.
    await noamProbe;
    if (!noamConfig || !noamConfig.baseUrl) {
        onError && onError('noam no está disponible.');
        return false;
    }
    try {
        const form = new FormData();
        form.append('file', file, file.name);
        form.append('language', 'es');
        if (title) form.append('title', title);
        const resp = await fetch(`${noamConfig.baseUrl}/documents`, { method: 'POST', body: form });
        if (!resp.ok) {
            const err = await resp.json().catch(() => ({}));
            throw new Error(err.error || err.message || `Error al subir (HTTP ${resp.status})`);
        }
        const job = await resp.json();
        addOptimisticDocument(job, title || stripExtension(file.name));
        fetchAndRenderDocuments({ silent: true });
        return true;
    } catch (error) {
        onError && onError(humanizeNoamError(error));
        return false;
    }
}

function humanizeNoamError(error) {
    const raw = error?.message || String(error);
    if (raw === 'Failed to fetch') return 'No se pudo conectar con noam. Comprueba tu conexión e inténtalo de nuevo.';
    if (/^HTTP \d+$/.test(raw)) return `noam devolvió un error (${raw}).`;
    return raw;
}

// Shows the new document as "procesando…" immediately, ahead of the background refresh
// that will reconcile it against noam's authoritative record.
function addOptimisticDocument(job, title) {
    if (!job || !job.documentId) return;
    const optimistic = {
        id: job.documentId,
        title: title || '(sin título)',
        language: 'es',
        status: 'UPLOADED',
        createdAt: new Date().toISOString(),
    };
    noamDocuments = [optimistic, ...(noamDocuments || []).filter(d => d.id !== optimistic.id)];
    renderDocGrid();
}

// ── "Subir documento" modal ───────────────────────────────────

function openUploadModal() {
    // Mirrors closeUploadModal's guard: reopening mid-upload would strand the busy backdrop
    // in the DOM with no reference left to remove it.
    if (noamUploadInFlight) return;
    closeUploadModal();

    const backdrop = document.createElement('div');
    backdrop.className = 'noam-modal-backdrop';
    backdrop.addEventListener('click', (e) => { if (e.target === backdrop) closeUploadModal(); });

    const modal = document.createElement('div');
    modal.className = 'noam-modal';
    modal.setAttribute('role', 'dialog');
    modal.setAttribute('aria-modal', 'true');
    modal.setAttribute('aria-labelledby', 'noamUploadTitle');

    const header = document.createElement('div');
    header.className = 'noam-modal-header';
    const heading = document.createElement('h3');
    heading.id = 'noamUploadTitle';
    heading.textContent = 'Subir documento';
    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'icon-button noam-modal-close';
    closeBtn.setAttribute('aria-label', 'Cerrar');
    closeBtn.textContent = '×';
    closeBtn.addEventListener('click', closeUploadModal);
    header.appendChild(heading);
    header.appendChild(closeBtn);

    const fileLabel = document.createElement('label');
    fileLabel.className = 'noam-modal-field';
    fileLabel.textContent = 'Archivo';
    const fileInputEl = document.createElement('input');
    fileInputEl.type = 'file';
    fileInputEl.accept = '.txt,.pdf,.epub,.html,.htm';
    fileLabel.appendChild(fileInputEl);

    const titleLabel = document.createElement('label');
    titleLabel.className = 'noam-modal-field';
    titleLabel.textContent = 'Título (opcional)';
    const titleInputEl = document.createElement('input');
    titleInputEl.type = 'text';
    titleInputEl.placeholder = 'Título del documento';
    titleLabel.appendChild(titleInputEl);

    const errorEl = document.createElement('p');
    errorEl.className = 'noam-modal-error';
    errorEl.hidden = true;

    const actions = document.createElement('div');
    actions.className = 'noam-modal-actions';
    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'topic-button';
    cancelBtn.textContent = 'Cancelar';
    cancelBtn.addEventListener('click', closeUploadModal);
    const submitBtn = document.createElement('button');
    submitBtn.type = 'button';
    submitBtn.className = 'topic-button';
    submitBtn.textContent = 'Subir';
    submitBtn.addEventListener('click', () => submitUploadModal(fileInputEl, titleInputEl, errorEl, submitBtn));
    actions.appendChild(cancelBtn);
    actions.appendChild(submitBtn);

    modal.appendChild(header);
    modal.appendChild(fileLabel);
    modal.appendChild(titleLabel);
    modal.appendChild(errorEl);
    modal.appendChild(actions);
    backdrop.appendChild(modal);
    document.body.appendChild(backdrop);
    noamModalBackdrop = backdrop;

    document.addEventListener('keydown', noamModalEscHandler);
    fileInputEl.focus();
}

async function submitUploadModal(fileInputEl, titleInputEl, errorEl, submitBtn) {
    const file = fileInputEl.files[0];
    if (!file) {
        errorEl.textContent = 'Elige un archivo primero.';
        errorEl.hidden = false;
        return;
    }
    errorEl.hidden = true;
    // Lock the modal for the duration of the POST. Without this, closing it mid-upload
    // (Escape, ×, Cancelar, backdrop) detaches errorEl, so the failure is reported into a
    // node nobody can see; and reopening it mid-upload would let this call's trailing
    // closeUploadModal() dismiss the *second* modal along with its fresh file selection.
    noamUploadInFlight = true;
    setUploadModalBusy(true);
    submitBtn.textContent = 'Subiendo…';
    // On failure the file input is left untouched, so its selection survives for a retry.
    const ok = await performNoamUpload(file, titleInputEl.value.trim(), {
        onError: (msg) => { errorEl.textContent = msg; errorEl.hidden = false; },
    });
    noamUploadInFlight = false;
    setUploadModalBusy(false);
    submitBtn.textContent = 'Subir';
    if (ok) closeUploadModal();
}

// Disables every control in the open modal (Subir, Cancelar, ×) while an upload runs.
function setUploadModalBusy(busy) {
    if (!noamModalBackdrop) return;
    noamModalBackdrop.querySelectorAll('button').forEach(b => { b.disabled = busy; });
}

function noamModalEscHandler(e) {
    if (e.key === 'Escape') closeUploadModal();
}

function closeUploadModal() {
    if (noamUploadInFlight) return;
    if (!noamModalBackdrop) return;
    noamModalBackdrop.remove();
    noamModalBackdrop = null;
    document.removeEventListener('keydown', noamModalEscHandler);
}

document.addEventListener('DOMContentLoaded', () => {
    // Fire-and-forget, but the promise is kept: fetchAndRenderDocuments awaits it
    // so an early 文 click doesn't read a not-yet-assigned noamConfig. The extra
    // .catch is a belt-and-braces guard against an uncaught rejection ever reaching
    // the page (e.g. from a future edit to probeNoamAvailability's body).
    noamProbe = probeNoamAvailability().catch(() => disableDocumentsMode(NOAM_UNAVAILABLE_TOOLTIP));
});
