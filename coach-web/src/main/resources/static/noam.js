// ── 文 documents mode (noam vocabulary platform) ─────────────────
// Mode shell (T06) + Documentos tab list/upload (T07). T08 still owns the Cola
// tab body. Plain script (no modules) loaded after script.js: it reuses
// script.js's top-level `function`/`let` bindings (API_URL, chatMessages,
// spanishModeToggle, resetToSetup, setCoachRadio, activeSetup, GLYPH_LABELS, …)
// and registers its own DOMContentLoaded listener, which runs after script.js's
// handler has assigned the shared DOM variables (that handler is `async` and
// yields at its first `await` right after those assignments).

let noamConfig = null;      // {baseUrl, profileId} from GET /api/noam/config, cached once
let noamProbe = null;       // in-flight probeNoamAvailability(), awaited by anything that needs noamConfig

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
    docsTab.classList.add('active');
    panel.dataset.activeTab = 'documentos';

    tabs.appendChild(docsTab);
    tabs.appendChild(queueTab);
    shell.appendChild(tabs);
    shell.appendChild(panel);
    chatMessages.appendChild(shell);

    activateNoamTab('documentos', panel);
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
        activateNoamTab(tabId, panel);
    });
    return btn;
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
// one stranded, then rebuilds the panel for the newly active tab.
function activateNoamTab(tabId, panel) {
    closeUploadModal();
    if (tabId === 'documentos') {
        renderDocumentosTab(panel);
    } else {
        // Cola tab body is T08's job — leave the pane empty for now, same as before T07.
        noamDocContent = null;
        noamDropError = null;
        panel.innerHTML = '';
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

function openStudyItems(documentId, title) {
    renderNoamItemList(document.getElementById('noamPanel'), {
        title,
        onBack: () => renderDocumentosTab(document.getElementById('noamPanel')),
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

function renderNoamItemList(panel, { title, onBack, loadPage, paging }) {
    panel.innerHTML = '';
    noamStudyEntries = new Map();
    noamStudyState = { loadPage, offset: 0, exhausted: false, loading: false, paging: !!paging };

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
        msg.textContent = err.message || 'No se pudieron cargar las palabras.';
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
        empty.textContent = 'No hay palabras nuevas.';
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

    const known = [];
    const ignored = [];
    const checkedItems = [];
    noamStudyEntries.forEach(entry => {
        if (entry.mark === 'KNOWN') known.push(entry.item.lexemeId);
        else if (entry.mark === 'IGNORED') ignored.push(entry.item.lexemeId);
        if (entry.checked) checkedItems.push(entry.item);
    });

    if (known.length === 0 && ignored.length === 0 && checkedItems.length === 0) return;

    proceedBtn.disabled = true;
    try {
        if (known.length > 0) await postNoamLexemeStates(known, 'KNOWN');
        if (ignored.length > 0) await postNoamLexemeStates(ignored, 'IGNORED');
    } catch (err) {
        proceedBtn.disabled = false;
        showStudyListError(errorEl, err.message || 'No se pudo guardar la marca.',
            () => proceedStudyItems(proceedBtn, errorEl, list));
        return;
    }

    noamStudyEntries.forEach((entry, lexemeId) => {
        if (entry.mark) {
            entry.rowEl.remove();
            noamStudyEntries.delete(lexemeId);
        }
    });

    if (checkedItems.length === 0) {
        proceedBtn.disabled = false;
        updateStudyEmptyState(list);
        return;
    }

    startWordQuizFromNoam(checkedItems);
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

function showStudyListError(errorEl, message, onRetry) {
    errorEl.innerHTML = '';
    errorEl.hidden = false;
    const p = document.createElement('p');
    p.textContent = message;
    errorEl.appendChild(p);
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'topic-button';
    retry.textContent = 'Reintentar';
    retry.addEventListener('click', onRetry);
    errorEl.appendChild(retry);
}

// Stub — T10 implements the actual noam-seeded 字 quiz.
function startWordQuizFromNoam(items) {
    console.log(`startWordQuizFromNoam stub: ${items.length} item(s)`);
}

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
