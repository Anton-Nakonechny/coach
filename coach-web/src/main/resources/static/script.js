// API base URL - relative path so it works from any host
const API_URL = '/api';

// Matches the phone breakpoint in style.css where the rails become drawers
const mobileQuery = window.matchMedia('(max-width: 768px)');

// Global state
let currentConversationId = null;
let currentModel = null;
let currentEffort = null;
let models = [];
let effortLevels = [];
let pendingAttachments = []; // [{id, file, kind, objectUrl?}]
let conversationCoach = {};  // conversationId -> coachType slug ('none' for plain chats)
let activeSetup = null;   // null | 'spanish' | 'spanish-words' | 'claude-architect'
let selectedTopic = null;
let spanishTopics = null;
let certTopics = null;
let spanishMode = 'language';  // 'language' (語) | 'words' (字)
let pendingMissedWords = null; // word list string for "practice missed" flow

const SPANISH_WELCOME = 'Nuevo chat. Elige un modelo a la izquierda y un tema abajo, e introduce una lista de palabras para practicar.';
const CLAUDE_WELCOME = 'New chat. Pick a topic below — I will quiz you with exam-style multiple-choice questions and explain every answer.';
const NEXT_QUESTION = 'Next question.';
const GLYPH_LABELS = { '語': 'language mode', '字': 'words mode' };

marked.use({ renderer: { link(token) {
    const html = marked.Renderer.prototype.link.call(this, token);
    return html.replace(/^<a /, '<a target="_blank" rel="noopener noreferrer" ');
} } });

const BILLING_URL = 'https://console.anthropic.com/settings/billing';
const BILLING_TRIGGER = /credit balance|plans?\s*&\s*billing/i;

// Anthropic SDK exceptions arrive as "400: {<json>}" — extract the inner message.
function humanizeError(raw) {
    const m = /^\d{3}:\s*(\{[\s\S]+)$/.exec(raw);
    if (m) {
        try { return JSON.parse(m[1])?.error?.message || raw; } catch {}
    }
    return raw;
}

function withBillingLink(message) {
    if (BILLING_TRIGGER.test(message))
        return `${message}\n\n[Add credits at Anthropic Billing →](${BILLING_URL})`;
    return message;
}

function addError(message) {
    addMessage(withBillingLink(`Error: ${humanizeError(message)}`), 'assistant');
}

// DOM elements
let chatMessages, chatInput, sendButton, modelButtons, effortSelect, effortNote,
    conversationList, clearAllButton, attachButton, fileInput, attachmentStrip,
    composerError, dropOverlay, coachNote,
    sidebar, coachPanel, sidebarToggle, coachToggle, drawerBackdrop,
    spanishModeToggle;

document.addEventListener('DOMContentLoaded', async () => {
    chatMessages    = document.getElementById('chatMessages');
    chatInput       = document.getElementById('chatInput');
    sendButton      = document.getElementById('sendButton');
    modelButtons    = document.getElementById('modelButtons');
    effortSelect    = document.getElementById('effortSelect');
    effortNote      = document.getElementById('effortNote');
    conversationList = document.getElementById('conversationList');
    clearAllButton  = document.getElementById('clearAllButton');
    attachButton    = document.getElementById('attachButton');
    fileInput       = document.getElementById('fileInput');
    attachmentStrip = document.getElementById('attachmentStrip');
    composerError   = document.getElementById('composerError');
    dropOverlay     = document.getElementById('dropOverlay');
    coachNote       = document.getElementById('coachNote');
    sidebar         = document.getElementById('sidebar');
    coachPanel      = document.getElementById('coachPanel');
    sidebarToggle      = document.getElementById('sidebarToggle');
    coachToggle        = document.getElementById('coachToggle');
    drawerBackdrop     = document.getElementById('drawerBackdrop');
    spanishModeToggle  = document.getElementById('spanishModeToggle');

    setupEventListeners();
    setupDragAndDrop();
    await loadModels();
    await loadConversations();
    startNewChat();
});

function setupEventListeners() {
    sendButton.addEventListener('click', sendMessage);
    chatInput.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter') return;
        if (e.ctrlKey || e.metaKey) {
            e.preventDefault();
            insertNewlineAtCursor();
        } else if (!e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });
    chatInput.addEventListener('input', autoResize);
    chatInput.addEventListener('paste', handlePaste);
    document.getElementById('newChatButton').addEventListener('click', () => {
        startNewChat();
        closeDrawers();
    });
    effortSelect.addEventListener('change', () => { currentEffort = effortSelect.value; });
    clearAllButton.addEventListener('click', clearAllConversations);
    attachButton.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', () => { addFiles(fileInput.files); fileInput.value = ''; });
    document.querySelectorAll('input[name="coach"]').forEach(radio =>
        radio.addEventListener('change', () => {
            closeDrawers();
            onCoachSelected(radio.value);
        }));
    sidebarToggle.addEventListener('click', () => toggleDrawer(sidebar, sidebarToggle));
    coachToggle.addEventListener('click', () => toggleDrawer(coachPanel, coachToggle));
    drawerBackdrop.addEventListener('click', closeDrawers);
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeDrawers(); });
    // Crossing the breakpoint (rotation, resize) clears drawer state so the
    // desktop layout never inherits a stale .open class or visible backdrop.
    mobileQuery.addEventListener('change', (e) => { if (!e.matches) closeDrawers(); });
    // 語/字 mode toggle — a click anywhere on the chip flips between the two modes
    spanishModeToggle.addEventListener('click', switchSpanishMode);
}

function setSpanishMode(mode) {
    spanishMode = mode;
    if (!spanishModeToggle) return;
    spanishModeToggle.querySelectorAll('.mode-btn').forEach(b => {
        const active = b.dataset.mode === mode;
        b.classList.toggle('active', active);
        b.setAttribute('aria-pressed', String(active));
    });
}

// Flip 語↔字. Clicking the chip also selects the Español coach. Right after word
// feedback, the flip re-drills the missed words — into 語 sentence practice or a fresh
// 字 quiz — the same outcome as the two in-dialog buttons.
function hintsFromOpenChat() {
    const blocks = chatMessages.querySelectorAll('.sentence-cards');
    if (!blocks.length) return [];
    const seen = new Set();
    const words = [];
    for (const part of [...blocks[blocks.length - 1].querySelectorAll('.hint-icon')]
        .flatMap(icon => (icon.dataset.tooltip || '').split(','))) {
        const word = part.trim();
        const key = word.toLowerCase();
        if (word && !seen.has(key)) { seen.add(key); words.push(word); }
    }
    return words;
}

function switchSpanishMode() {
    setCoachRadio('spanish');
    const next = spanishMode === 'words' ? 'language' : 'words';
    setSpanishMode(next);
    if (pendingMissedWords && pendingMissedWords.length) {
        if (next === 'language') practiceMissed(pendingMissedWords);
        else retryMissedInWords(pendingMissedWords);
        return;
    }
    const hints = next === 'words' ? hintsFromOpenChat() : [];
    if (hints.length) retryMissedInWords(hints);
    else onCoachSelected('spanish');
}

// ── Mobile drawers ───────────────────────────────────────────

function openDrawer(panel, toggle) {
    closeDrawers();
    panel.classList.add('open');
    toggle.setAttribute('aria-expanded', 'true');
    drawerBackdrop.hidden = false;
}

function closeDrawers() {
    sidebar.classList.remove('open');
    coachPanel.classList.remove('open');
    sidebarToggle.setAttribute('aria-expanded', 'false');
    coachToggle.setAttribute('aria-expanded', 'false');
    drawerBackdrop.hidden = true;
}

function toggleDrawer(panel, toggle) {
    document.body.classList.add('drawer-anim');
    if (panel.classList.contains('open')) closeDrawers();
    else openDrawer(panel, toggle);
}

// ── Coach panel ──────────────────────────────────────────────

// The radio group mirrors the open conversation's coach: selecting a coach creates a
// fresh coach chat and stays there; opening a conversation from history sets the radio
// to its stored coach (a programmatic check fires no change event, so this never loops).
function setCoachRadio(value) {
    const radio = document.querySelector(`input[name="coach"][value="${value}"]`)
        || document.querySelector('input[name="coach"][value="none"]');
    radio.checked = true;
}

function setCoachRadiosDisabled(disabled) {
    document.querySelectorAll('input[name="coach"]').forEach(r => { r.disabled = disabled; });
}

async function onCoachSelected(value) {
    coachNote.textContent = '';
    activeSetup = null;
    selectedTopic = null;
    pendingMissedWords = null;  // a fresh coach entry ends any pending word drill
    if (value === 'none') {
        startNewChat();
        return;
    }
    if (value === 'spanish') {
        if (spanishMode === 'words') {
            enterWordsSetup();
        } else {
            enterSpanishSetup();
        }
        return;
    }
    if (value === 'claude-architect') {
        enterCertSetup();
        return;
    }

    await startCoachChat({
        message: '',
        coachType: value,
        model: currentModel,
        effort: currentEffort,
    }, 'Failed to start coach chat');
}

// Start (or fail to start) a coach conversation from a ready request body.
// Refresh the sidebar first so the coach map knows the new conversation, then
// open it (which also keeps the radio on the chosen coach).
async function startCoachChat(body, errorLabel) {
    activeSetup = null;
    setCoachRadiosDisabled(true);
    chatMessages.innerHTML = '';
    const loadingMessage = createLoadingMessage();
    chatMessages.appendChild(loadingMessage);

    try {
        const response = await fetch(`${API_URL}/chat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        if (!response.ok) {
            const err = await response.json().catch(() => ({}));
            throw new Error(err.message || errorLabel);
        }
        const data = await response.json();
        await loadConversations();
        await openConversation(data.conversationId);
    } catch (error) {
        startNewChat();
        addError(error.message);
    } finally {
        setCoachRadiosDisabled(false);
    }
}

async function enterSpanishSetup() {
    const topics = await enterTopicSetup({
        welcome: SPANISH_WELCOME,
        setupName: 'spanish',
        endpoint: '/coaches/spanish/topics',
        gridId: 'topicGrid',
        cached: spanishTopics,
        render: renderSpanishTopicSections,
        onPick: t => { selectedTopic = t; setActive('.topic-button', 'topic', t); },
    });
    if (topics) spanishTopics = topics;
}

// Clear the chat pane back to an in-panel setup screen — no conversation open.
function resetToSetup() {
    currentConversationId = null;
    chatMessages.innerHTML = '';
    highlightActiveConversation();
    coachNote.textContent = '';
}

function enterWordsSetup() {
    resetToSetup();
    pendingMissedWords = null;
    addMessage(`Modo <span data-tooltip="${GLYPH_LABELS['字']}">字</span> — vocabulario. Escribe o pega palabras en español separadas por comas.`, 'assistant');
    activeSetup = 'spanish-words';
}

// Render a coach's topic grid, lazily fetching (and returning) its topic list.
// Returns null if loading failed, so the caller keeps its existing cache.
async function enterTopicSetup({ welcome, setupName, endpoint, gridId, cached, onPick, render = renderTopicGrid }) {
    resetToSetup();
    addMessage(welcome, 'assistant');
    activeSetup = setupName;

    let topics = cached;
    if (!topics) {
        try {
            const resp = await fetch(`${API_URL}${endpoint}`);
            const data = await resp.json();
            if (!resp.ok) throw new Error(data.message || 'Failed to load topics');
            topics = data;
        } catch (e) {
            startNewChat();
            addError(e.message);
            return null;
        }
    }
    render(gridId, topics, onPick);
    return topics;
}

// Build one clickable topic button (shared by the flat grid and the level sections).
function topicButton(topic, onClick) {
    const btn = document.createElement('button');
    btn.className = 'topic-button';
    btn.dataset.topic = topic;
    btn.textContent = topic;
    btn.addEventListener('click', () => onClick(topic));
    return btn;
}

function renderTopicGrid(gridId, topics, onClick) {
    const grid = document.createElement('div');
    grid.className = 'topic-grid';
    grid.id = gridId;
    topics.forEach(t => grid.appendChild(topicButton(t, onClick)));
    chatMessages.appendChild(grid);
}

// Spanish topics arrive as ordered { level, topics } sections; render each as a
// labelled column (e.g. B1, B2) of buttons in workbook order.
function renderSpanishTopicSections(gridId, sections, onClick) {
    const wrap = document.createElement('div');
    wrap.className = 'topic-sections';
    wrap.id = gridId;
    sections.forEach(({ level, topics }) => {
        const section = document.createElement('div');
        section.className = 'topic-section';
        const heading = document.createElement('h4');
        heading.textContent = level;
        section.appendChild(heading);
        const pairs = document.createElement('div');
        pairs.className = 'topic-pairs';
        topics.forEach(t => pairs.appendChild(topicButton(t, onClick)));
        section.appendChild(pairs);
        wrap.appendChild(section);
    });
    chatMessages.appendChild(wrap);
}

async function enterCertSetup() {
    const topics = await enterTopicSetup({
        welcome: CLAUDE_WELCOME,
        setupName: 'claude-architect',
        endpoint: '/coaches/claude-architect/topics',
        gridId: 'certTopicGrid',
        cached: certTopics,
        onPick: t => startCertChat(t),
    });
    if (topics) certTopics = topics;
}

async function startCertChat(topic) {
    await startCoachChat({
        message: '',
        coachType: 'claude-architect',
        topic,
        model: currentModel,
        effort: currentEffort,
    }, 'Failed to start quiz');
}

function sendQuizReply(text) {
    if (chatInput.disabled) return;
    chatInput.value = text;
    sendMessage();
}

// ── Attachment validation & state ────────────────────────────

const ALLOWED_TYPES = new Set([
    'image/png', 'image/jpeg', 'image/gif', 'image/webp',
    'application/pdf', 'text/plain', 'text/markdown', 'text/csv',
    'application/json', 'application/xml', 'application/zip',
]);
const ALLOWED_EXT    = /\.(png|jpe?g|gif|webp|pdf|txt|md|csv|json|xml|zip)$/i;
const MAX_FILE_SIZE  = 32 * 1024 * 1024;
const MAX_FILE_COUNT = 20;

// Client-side id for a pending attachment (never sent to the server). crypto.randomUUID
// only exists in a secure context (HTTPS or localhost), so it is undefined when the page
// is opened over plain http from another machine — fall back to a timestamp+random id.
const uid = () =>
    (crypto.randomUUID?.() ?? `att-${Date.now()}-${Math.random().toString(36).slice(2)}`);

function addFiles(fileList) {
    let firstError = null;
    for (const file of Array.from(fileList)) {
        if (pendingAttachments.length >= MAX_FILE_COUNT) {
            firstError = firstError || `Max ${MAX_FILE_COUNT} files per message`;
            break;
        }
        if (!ALLOWED_TYPES.has(file.type) && !ALLOWED_EXT.test(file.name)) {
            firstError = firstError || `"${file.name}" is not a supported type`;
            continue;
        }
        if (file.size > MAX_FILE_SIZE) {
            firstError = firstError || `"${file.name}" exceeds the 32 MB limit`;
            continue;
        }
        // dedupe by name + size
        if (pendingAttachments.some(a => a.file.name === file.name && a.file.size === file.size)) continue;

        const kind = (file.type === 'application/zip' || /\.zip$/i.test(file.name)) ? 'ZIP'
                   : file.type.startsWith('image/') ? 'IMAGE'
                   : 'DOCUMENT';
        pendingAttachments.push({
            id: uid(),
            file,
            kind,
            objectUrl: kind === 'IMAGE' ? URL.createObjectURL(file) : null,
        });
    }
    composerError.textContent = firstError || '';
    renderAttachmentStrip();
}

function renderAttachmentStrip() {
    if (!pendingAttachments.length) {
        attachmentStrip.hidden = true;
        attachmentStrip.innerHTML = '';
        chatInput.placeholder = 'Message Claude...';
        return;
    }
    attachmentStrip.hidden = false;
    chatInput.placeholder = 'Add a message (optional)...';
    attachmentStrip.innerHTML = '';
    pendingAttachments.forEach(a => attachmentStrip.appendChild(buildPendingChip(a)));
}

function buildPendingChip(a) {
    const chip = document.createElement('div');
    if (a.kind === 'IMAGE') {
        chip.className = 'attachment-chip image-chip';
        const img = document.createElement('img');
        img.src = a.objectUrl;
        img.alt = a.file.name;
        img.className = 'chip-thumbnail';
        chip.appendChild(img);
    } else {
        chip.className = `attachment-chip ${a.kind === 'ZIP' ? 'zip-chip' : 'doc-chip'}`;
        const icon = document.createElement('span');
        icon.className = 'chip-icon';
        icon.setAttribute('aria-hidden', 'true');
        icon.textContent = a.kind === 'ZIP' ? '🗜' : '📄';
        const info = document.createElement('div');
        info.className = 'chip-info';
        const name = document.createElement('span');
        name.className = 'chip-name';
        name.textContent = a.file.name;
        const meta = document.createElement('span');
        meta.className = 'chip-meta';
        meta.textContent = a.kind === 'ZIP' ? 'will be expanded' : formatSize(a.file.size);
        info.appendChild(name);
        info.appendChild(meta);
        chip.appendChild(icon);
        chip.appendChild(info);
    }
    chip.appendChild(buildRemoveButton(a.id, a.file.name));
    return chip;
}

function buildRemoveButton(id, filename) {
    const btn = document.createElement('button');
    btn.className = 'chip-remove';
    btn.setAttribute('aria-label', `Remove ${filename}`);
    btn.textContent = '×';
    btn.addEventListener('click', () => {
        const idx = pendingAttachments.findIndex(a => a.id === id);
        if (idx === -1) return;
        const [removed] = pendingAttachments.splice(idx, 1);
        if (removed.objectUrl) URL.revokeObjectURL(removed.objectUrl);
        composerError.textContent = '';
        renderAttachmentStrip();
        if (!pendingAttachments.length) chatInput.focus();
    });
    return btn;
}

function formatSize(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1048576) return `${Math.round(bytes / 1024)} KB`;
    return `${(bytes / 1048576).toFixed(1)} MB`;
}

// ── Paste ────────────────────────────────────────────────────

function handlePaste(e) {
    const imageFiles = Array.from(e.clipboardData?.items || [])
        .filter(item => item.kind === 'file' && item.type.startsWith('image/'))
        .map(item => {
            const raw = item.getAsFile();
            return new File([raw], `pasted-${Date.now()}.png`, { type: raw.type });
        });
    if (!imageFiles.length) return;
    e.preventDefault();
    addFiles(imageFiles);
}

// ── Drag and drop ────────────────────────────────────────────

function setupDragAndDrop() {
    const container = document.querySelector('.chat-input-container');
    let dragCounter = 0;

    const isFileDrag = e => e.dataTransfer?.types?.includes('Files');

    document.addEventListener('dragenter', (e) => {
        if (!isFileDrag(e)) return;
        e.preventDefault();
        if (++dragCounter === 1) {
            container.classList.add('drag-over');
            dropOverlay.hidden = false;
        }
    });

    document.addEventListener('dragleave', () => {
        dragCounter = Math.max(0, dragCounter - 1);
        if (dragCounter === 0) {
            container.classList.remove('drag-over');
            dropOverlay.hidden = true;
        }
    });

    document.addEventListener('dragover', (e) => { if (isFileDrag(e)) e.preventDefault(); });

    // Prevent near-miss drops from navigating the page
    window.addEventListener('drop', (e) => {
        e.preventDefault();
        dragCounter = 0;
        container.classList.remove('drag-over');
        dropOverlay.hidden = true;
    });

    container.addEventListener('drop', (e) => {
        addFiles(e.dataTransfer.files);
    });
}

// ── Model / effort ───────────────────────────────────────────

async function loadModels() {
    const response = await fetch(`${API_URL}/models`);
    const data = await response.json();
    models = data.models;
    effortLevels = data.effortLevels;
    currentModel = data.defaultModel;
    currentEffort = data.defaultEffort;

    modelButtons.innerHTML = '';
    models.forEach(m => {
        const btn = document.createElement('button');
        btn.className = 'model-button';
        btn.textContent = m.label;
        btn.dataset.key = m.key;
        if (m.key === currentModel) btn.classList.add('active');
        btn.addEventListener('click', () => selectModel(m.key));
        modelButtons.appendChild(btn);
    });

    effortSelect.innerHTML = effortLevels
        .map(level => `<option value="${level}">${level}</option>`)
        .join('');
    effortSelect.value = currentEffort;

    applyEffortAvailability();
}

function setActive(selector, attr, value) {
    document.querySelectorAll(selector).forEach(el => {
        el.classList.toggle('active', el.dataset[attr] === value);
    });
}

function selectModel(key) {
    currentModel = key;
    setActive('.model-button', 'key', key);
    applyEffortAvailability();
}

function applyEffortAvailability() {
    const model = models.find(m => m.key === currentModel);
    const supported = model ? model.supportsEffort : false;
    effortSelect.disabled = !supported;
    effortNote.textContent = supported ? '' : 'Not applicable for this model';
}

// ── Composer utilities ───────────────────────────────────────

function insertNewlineAtCursor() {
    const start = chatInput.selectionStart;
    const end = chatInput.selectionEnd;
    const value = chatInput.value;
    if (!value) return;
    chatInput.value = value.slice(0, start) + '\n' + value.slice(end);
    chatInput.selectionStart = chatInput.selectionEnd = start + 1;
    autoResize();
}

function autoResize() {
    const before = chatInput.offsetHeight;
    chatInput.style.height = 'auto';
    chatInput.style.height = `${chatInput.scrollHeight}px`;
    const delta = chatInput.offsetHeight - before;
    if (delta > 0) chatMessages.scrollTop += delta;
}

// ── Send ─────────────────────────────────────────────────────

async function sendMessage() {
    const message = chatInput.value.trim();
    if (!message && pendingAttachments.length === 0) return;
    if (activeSetup === 'spanish' && !selectedTopic) {
        composerError.textContent = 'Elige un tema primero';
        return;
    }
    if (activeSetup === 'claude-architect') {
        composerError.textContent = 'Select a topic first';
        return;
    }
    if (activeSetup === 'spanish-words') {
        translateWords(message);
        return;
    }

    // Snapshot & clear before anything async
    const attachmentsSnapshot = [...pendingAttachments];
    pendingAttachments = [];
    renderAttachmentStrip();
    composerError.textContent = '';

    chatInput.value = '';
    autoResize();
    chatInput.disabled  = true;
    sendButton.disabled = true;
    attachButton.disabled = true;

    const isSpanishFirst = activeSetup === 'spanish';
    addMessage(message, 'user', attachmentsSnapshot);

    const loadingMessage = createLoadingMessage();
    chatMessages.appendChild(loadingMessage);
    chatMessages.scrollTop = chatMessages.scrollHeight;

    try {
        const payload = JSON.stringify({
            message,
            model: currentModel,
            effort: currentEffort,
            conversationId: currentConversationId,
            ...(activeSetup === 'spanish' && { coachType: 'spanish', topic: selectedTopic }),
        });
        let response;
        if (attachmentsSnapshot.length > 0) {
            const form = new FormData();
            form.append('request', new Blob([payload], { type: 'application/json' }));
            attachmentsSnapshot.forEach(a => form.append('files', a.file, a.file.name));
            response = await fetch(`${API_URL}/chat`, { method: 'POST', body: form });
        } else {
            response = await fetch(`${API_URL}/chat`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: payload,
            });
        }

        // Images are already rendered in the DOM; safe to revoke object URLs now
        attachmentsSnapshot.forEach(a => { if (a.objectUrl) URL.revokeObjectURL(a.objectUrl); });

        if (!response.ok) {
            const err = await response.json().catch(() => ({}));
            throw new Error(err.message || 'Request failed');
        }

        const data = await response.json();
        loadingMessage.remove();

        if (isSpanishFirst) {
            activeSetup = null;
            selectedTopic = null;
            await loadConversations();
            await openConversation(data.conversationId);
        } else {
            const isNew = !currentConversationId;
            currentConversationId = data.conversationId;
            addMessage(data.answer, 'assistant', null, data.sentences, data.question);
            activateQuiz();
            if (isNew) loadConversations();
        }
    } catch (error) {
        loadingMessage.remove();
        // Re-enable any quiz buttons that were disabled before the failed send,
        // so the user can still pick an answer without losing the question.
        chatMessages.querySelectorAll('.quiz-option:disabled')
            .forEach(btn => { btn.disabled = false; });
        addError(error.message);
        activateQuiz();
        attachmentsSnapshot.forEach(a => { if (a.objectUrl) URL.revokeObjectURL(a.objectUrl); });
    } finally {
        chatInput.disabled  = false;
        sendButton.disabled = false;
        attachButton.disabled = false;
        chatInput.focus();
    }
}

// ── 字 word-quiz flow ─────────────────────────────────────────

async function translateWords(words) {
    if (!words) return;
    composerError.textContent = '';
    chatInput.value = '';
    autoResize();
    chatInput.disabled = true;
    sendButton.disabled = true;
    attachButton.disabled = true;

    // The pasted list is not echoed as a message — it would reveal the español we quiz on.
    const loadingMessage = createLoadingMessage();
    chatMessages.appendChild(loadingMessage);
    chatMessages.scrollTop = chatMessages.scrollHeight;

    try {
        const resp = await fetch(`${API_URL}/spanish/words/translate`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ words, model: currentModel, effort: currentEffort }),
        });
        const data = await resp.json();
        if (!resp.ok) throw new Error(data.message || 'Error translating words');
        loadingMessage.remove();
        activeSetup = null;
        buildWordCheck(data.setId, data.items);
    } catch (err) {
        loadingMessage.remove();
        addError(err.message);
    } finally {
        chatInput.disabled = false;
        sendButton.disabled = false;
        attachButton.disabled = false;
        chatInput.focus();
    }
}

function buildWordCheck(setId, items) {
    const container = document.createElement('div');
    container.className = 'word-check';

    const inputMinWidth = `${Math.max(...items.map(i => i.spanish.length)) + 5}ch`;

    const rows = items.map(item => {
        const row = document.createElement('div');
        row.className = 'word-row';

        const english = document.createElement('span');
        english.className = 'word-english';
        english.textContent = item.english;

        const hint = document.createElement('span');
        hint.className = 'hint-icon';
        hint.dataset.tooltip = item.hint;
        hint.textContent = '?';
        hint.title = item.hint;
        // Clicking escalates the partial hint to the full word (a "full hint"), pins the
        // tooltip, and flags the row so the grade counts it as yellow even when correct.
        hint.addEventListener('click', () => {
            hint.dataset.tooltip = item.spanish;
            hint.title = item.spanish;
            hint.classList.add('revealed');
            row.dataset.fullHint = 'true';
        });

        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'word-answer';
        input.placeholder = 'español…';
        input.style.minWidth = inputMinWidth;
        input.addEventListener('keydown', e => {
            if (e.key === 'Enter') { e.preventDefault(); checkWords(setId, rows, checkBtn); }
        });

        row.appendChild(english);
        row.appendChild(hint);
        row.appendChild(input);
        return row;
    });
    rows.forEach(r => container.appendChild(r));

    const checkBtn = document.createElement('button');
    checkBtn.className = 'topic-button';
    checkBtn.textContent = 'Comprobar ✓';
    checkBtn.style.alignSelf = 'flex-start';
    checkBtn.addEventListener('click', () => checkWords(setId, rows, checkBtn));
    container.appendChild(checkBtn);

    const msgDiv = document.createElement('div');
    msgDiv.className = 'message assistant';
    const inner = document.createElement('div');
    inner.className = 'message-content';
    inner.appendChild(container);
    msgDiv.appendChild(inner);
    chatMessages.appendChild(msgDiv);
    chatMessages.scrollTop = chatMessages.scrollHeight;
    if (rows.length > 0) rows[0].querySelector('.word-answer').focus();
}

async function checkWords(setId, rows, checkBtn) {
    const answers = rows.map(r => r.querySelector('.word-answer').value);
    const hintsUsed = rows.map(r => r.dataset.fullHint === 'true');
    rows.forEach(r => { r.querySelector('.word-answer').disabled = true; });

    try {
        const resp = await fetch(`${API_URL}/spanish/words/check`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ setId, answers, hintsUsed }),
        });
        if (resp.status === 404) {
            addMessage('El conjunto de palabras ha expirado. Vuelve a introducir las palabras.', 'assistant');
            rows.forEach(r => { r.querySelector('.word-answer').disabled = false; });
            activeSetup = 'spanish-words';
            return;
        }
        const data = await resp.json();
        if (!resp.ok) throw new Error(data.message || 'Error checking answers');

        data.results.forEach((result, i) => {
            const row = rows[i];
            if (!row) return;
            // Tri-state: green = correct w/o hint, yellow = correct but full hint, red = wrong.
            const state = !result.correct ? 'incorrect' : result.fullHint ? 'hinted' : 'correct';
            row.classList.add(state);

            const grade = document.createElement('span');
            grade.className = 'word-grade';
            grade.textContent = result.correct ? '✓' : '✗';
            row.appendChild(grade);

            if (!result.correct) {
                const reveal = document.createElement('span');
                reveal.className = 'word-spanish-reveal';
                reveal.textContent = result.spanish;
                row.appendChild(reveal);
            }
        });

        // Review set = wrong OR full-hint words; only clean-correct words are dropped.
        const review = data.results.filter(r => !r.correct || r.fullHint).map(r => r.spanish);
        const all = data.results.map(r => r.spanish);
        pendingMissedWords = review.length > 0 ? review : null;

        const actions = document.createElement('div');
        actions.className = 'word-actions';
        if (review.length > 0) {
            actions.appendChild(missedButton(review, '語', () => practiceMissed(review)));
            actions.appendChild(missedButton(review, '字', () => retryMissedInWords(review)));
        }
        const again = document.createElement('button');
        again.className = 'topic-button';
        again.innerHTML = `De nuevo <span data-tooltip="${GLYPH_LABELS['字']}">字</span>`;
        again.addEventListener('click', () => retryMissedInWords(all));
        actions.appendChild(again);
        if (checkBtn) checkBtn.hidden = true;
        const container = rows[0].closest('.word-check');
        if (container) container.appendChild(actions);
    } catch (err) {
        addError(err.message);
        rows.forEach(r => { r.querySelector('.word-answer').disabled = false; });
    }
}

// A result button whose grammar agrees with the miss count (1 → "la falla", N → "las N fallas").
function missedButton(missed, glyph, onClick) {
    const btn = document.createElement('button');
    btn.className = 'topic-button';
    const prefix = missed.length === 1
        ? 'Practicar la falla en modo '
        : `Practicar las ${missed.length} fallas en modo `;
    btn.innerHTML = `${prefix}<span data-tooltip="${GLYPH_LABELS[glyph] || ''}">${glyph}</span>`;
    btn.addEventListener('click', onClick);
    return btn;
}

// Restart a 字 quiz seeded with only the given (failed) Spanish words.
function retryMissedInWords(words) {
    setCoachRadio('spanish');
    setSpanishMode('words');
    resetToSetup();
    activeSetup = 'spanish-words';
    translateWords(words.join(', '));
}

async function practiceMissed(words) {
    const wordStr = words.join(', ');
    chatInput.disabled = true;
    sendButton.disabled = true;
    const loadingMessage = createLoadingMessage();
    chatMessages.appendChild(loadingMessage);
    chatMessages.scrollTop = chatMessages.scrollHeight;
    try {
        const resp = await fetch(`${API_URL}/chat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message: wordStr,
                model: currentModel,
                effort: currentEffort,
                coachType: 'spanish',
            }),
        });
        const data = await resp.json();
        if (!resp.ok) throw new Error(data.message || 'Failed to start practice');
        loadingMessage.remove();
        await loadConversations();
        await openConversation(data.conversationId);
        setSpanishMode('language');  // switch toggle back to 語
    } catch (err) {
        loadingMessage.remove();
        addError(err.message);
    } finally {
        chatInput.disabled = false;
        sendButton.disabled = false;
        chatInput.focus();
    }
}

function createLoadingMessage() {
    const messageDiv = document.createElement('div');
    messageDiv.className = 'message assistant';
    messageDiv.innerHTML = `
        <div class="message-content">
            <div class="loading"><span></span><span></span><span></span></div>
        </div>
    `;
    return messageDiv;
}

// ── Message rendering ─────────────────────────────────────────

const COPY_SVG = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>`;
const CHECK_SVG = `<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>`;

function buildCopyButton(content) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'icon-button copy-button';
    btn.setAttribute('aria-label', 'Copy message');
    btn.innerHTML = COPY_SVG;
    btn.addEventListener('click', () => {
        if (!navigator.clipboard) return;
        navigator.clipboard.writeText(content).then(() => {
            btn.innerHTML = CHECK_SVG;
            setTimeout(() => btn.innerHTML = COPY_SVG, 1200);
        }).catch(() => {});
    });
    return btn;
}

// attachments is optional; entries are either pending shape {file, kind, objectUrl}
// or history shape {fileId, filename, mediaType, kind}.
function addMessage(content, type, attachments, sentences, question) {
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${type}`;

    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';

    if (attachments && attachments.length > 0) {
        const strip = document.createElement('div');
        strip.className = 'message-attachments';
        attachments.forEach(a => {
            const filename  = a.file ? a.file.name : a.filename;
            const { kind }  = a;
            const objectUrl = a.objectUrl || null;

            if (kind === 'IMAGE' && objectUrl) {
                const img = document.createElement('img');
                img.src       = objectUrl;
                img.alt       = filename;
                img.className = 'message-attachment-thumb';
                strip.appendChild(img);
            } else {
                const chip = document.createElement('div');
                chip.className = `message-chip${kind === 'ZIP' ? ' zip' : ''}`;
                const glyph = kind === 'IMAGE' ? '🖼' : kind === 'ZIP' ? '🗜' : '📄';
                const icon  = document.createElement('span');
                icon.setAttribute('aria-hidden', 'true');
                icon.textContent = glyph;
                const label = document.createElement('span');
                label.className  = 'chip-name';
                label.textContent = filename;
                chip.appendChild(icon);
                chip.appendChild(label);
                strip.appendChild(chip);
            }
        });
        contentDiv.appendChild(strip);
    }

    if (content) {
        if (type === 'assistant' && question) {
            contentDiv.appendChild(buildQuizBlock(question));
        } else if (type === 'assistant' && sentences && sentences.length) {
            contentDiv.appendChild(buildSentenceCards(sentences));
        } else if (type === 'assistant') {
            const html = document.createElement('div');
            html.innerHTML = marked.parse(content);
            contentDiv.appendChild(html);
        } else {
            const text = document.createElement('div');
            text.textContent = content;
            contentDiv.appendChild(text);
        }
    }

    messageDiv.appendChild(contentDiv);
    if (content) messageDiv.appendChild(buildCopyButton(content));
    chatMessages.appendChild(messageDiv);
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

function buildSentenceCards(sentences) {
    const container = document.createElement('div');
    container.className = 'sentence-cards';
    sentences.forEach(({ hint, sentence }) => {
        const card = document.createElement('div');
        card.className = 'sentence-card';
        card.dataset.sentence = sentence;

        const text = document.createElement('span');
        text.className = 'sentence-text';
        text.textContent = sentence;

        const hintIcon = document.createElement('span');
        hintIcon.className = 'hint-icon';
        hintIcon.setAttribute('data-tooltip', hint);
        hintIcon.textContent = '?';

        card.appendChild(text);
        card.appendChild(hintIcon);
        container.appendChild(card);
    });
    container.addEventListener('click', e => {
        const card = e.target.closest('.sentence-card');
        if (!card) return;
        const sep = chatInput.value.trimEnd() ? '\n' : '';
        chatInput.value = chatInput.value.trimEnd() + sep + card.dataset.sentence + '\n';
        autoResize();
        chatInput.focus();
    });
    return container;
}

function buildQuizBlock(question) {
    const block = document.createElement('div');
    block.className = 'quiz-block';

    const stem = document.createElement('div');
    stem.className = 'quiz-stem';
    stem.textContent = question.stem;
    block.appendChild(stem);

    const optionsDiv = document.createElement('div');
    optionsDiv.className = 'quiz-options';
    question.options.forEach(({ letter, text }) => {
        const btn = document.createElement('button');
        btn.className = 'quiz-option';
        btn.disabled = true;
        const fullText = `${letter}) ${text}`;
        btn.textContent = fullText;
        btn.dataset.answer = fullText;
        optionsDiv.appendChild(btn);
    });
    optionsDiv.addEventListener('click', e => {
        const btn = e.target.closest('.quiz-option:not(:disabled)');
        if (!btn) return;
        optionsDiv.querySelectorAll('.quiz-option').forEach(b => { b.disabled = true; });
        btn.classList.add('chosen');
        sendQuizReply(btn.dataset.answer);
    });
    block.appendChild(optionsDiv);
    return block;
}

function activateQuiz() {
    // Remove any existing next-question rows
    chatMessages.querySelectorAll('.next-question-row').forEach(el => el.remove());

    if ((conversationCoach[currentConversationId] || 'none') !== 'claude-architect') return;

    const assistantMessages = chatMessages.querySelectorAll('.message.assistant');
    if (!assistantMessages.length) return;
    const last = assistantMessages[assistantMessages.length - 1];
    const options = last.querySelectorAll('.quiz-option');
    if (options.length) {
        options.forEach(btn => { btn.disabled = false; });
    } else {
        const row = document.createElement('div');
        row.className = 'next-question-row';
        const btn = document.createElement('button');
        btn.className = 'next-question';
        btn.textContent = 'Next question ▸';
        btn.addEventListener('click', () => sendQuizReply(NEXT_QUESTION));
        row.appendChild(btn);
        last.querySelector('.message-content').appendChild(row);
    }
}

// ── Conversations ─────────────────────────────────────────────

function startNewChat() {
    activeSetup = null;
    selectedTopic = null;
    pendingMissedWords = null;
    currentConversationId = null;
    chatMessages.innerHTML = '';
    addMessage("New chat. Pick a model on the left and ask me anything.", 'assistant');
    highlightActiveConversation();
    setCoachRadio('none');
    coachNote.textContent = '';
    chatInput.focus();
}

// Small sunburst mark standing in for the "Claude" word in claude-architect history rows.
function claudeLogoIcon() {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'claude-logo-icon');
    svg.setAttribute('width', '14');
    svg.setAttribute('height', '14');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.innerHTML = '<path fill="currentColor" d="m4.7144 15.9555 4.7174-2.6471.079-.2307-.079-.1275h-.2307l-.7893-.0486-2.6956-.0729-2.3375-.0971-2.2646-.1214-.5707-.1215-.5343-.7042.0546-.3522.4797-.3218.686.0608 1.5179.1032 2.2767.1578 1.6514.0972 2.4468.255h.3886l.0546-.1579-.1336-.0971-.1032-.0972L6.973 9.8356l-2.55-1.6879-1.3356-.9714-.7225-.4918-.3643-.4614-.1578-1.0078.6557-.7225.8803.0607.2246.0607.8925.686 1.9064 1.4754 2.4893 1.8336.3643.3035.1457-.1032.0182-.0728-.164-.2733-1.3539-2.4467-1.445-2.4893-.6435-1.032-.17-.6194c-.0607-.255-.1032-.4674-.1032-.7285L6.287.1335 6.6997 0l.9957.1336.419.3642.6192 1.4147 1.0018 2.2282 1.5543 3.0296.4553.8985.2429.8318.091.255h.1579v-.1457l.1275-1.706.2368-2.0947.2307-2.6957.0789-.7589.3764-.9107.7468-.4918.5828.2793.4797.686-.0668.4433-.2853 1.8517-.5586 2.9021-.3643 1.9429h.2125l.2429-.2429.9835-1.3053 1.6514-2.0643.7286-.8196.85-.9046.5464-.4311h1.0321l.759 1.1293-.34 1.1657-1.0625 1.3478-.8804 1.1414-1.2628 1.7-.7893 1.36.0729.1093.1882-.0183 2.8535-.607 1.5421-.2794 1.8396-.3157.8318.3886.091.3946-.3278.8075-1.967.4857-2.3072.4614-3.4364.8136-.0425.0304.0486.0607 1.5482.1457.6618.0364h1.621l3.0175.2247.7892.522.4736.6376-.079.4857-1.2142.6193-1.6393-.3886-3.825-.9107-1.3113-.3279h-.1822v.1093l1.0929 1.0686 2.0035 1.8092 2.5075 2.3314.1275.5768-.3218.4554-.34-.0486-2.2039-1.6575-.85-.7468-1.9246-1.621h-.1275v.17l.4432.6496 2.3436 3.5214.1214 1.0807-.17.3521-.6071.2125-.6679-.1214-1.3721-1.9246L14.38 17.959l-1.1414-1.9428-.1397.079-.674 7.2552-.3156.3703-.7286.2793-.6071-.4614-.3218-.7468.3218-1.4753.3886-1.9246.3157-1.53.2853-1.9004.17-.6314-.0121-.0425-.1397.0182-1.4328 1.9672-2.1796 2.9446-1.7243 1.8456-.4128.164-.7164-.3704.0667-.6618.4008-.5889 2.386-3.0357 1.4389-1.882.929-1.0868-.0062-.1579h-.0546l-6.3385 4.1164-1.1293.1457-.4857-.4554.0608-.7467.2307-.2429 1.9064-1.3114Z"/>';
    return svg;
}

async function loadConversations() {
    try {
        const response = await fetch(`${API_URL}/conversations`);
        const items = await response.json();
        conversationList.innerHTML = '';
        clearAllButton.hidden = !items.length;
        if (!items.length) {
            conversationList.innerHTML = '<span class="muted">No conversations yet</span>';
            return;
        }
        conversationCoach = {};
        items.forEach(item => {
            conversationCoach[item.conversationId] = item.coachType || 'none';

            const row = document.createElement('div');
            row.className = 'conversation-row';

            const btn = document.createElement('button');
            btn.className = 'conversation-item';
            if (item.coachType === 'claude-architect') {
                btn.classList.add('claude-chat');
                btn.appendChild(claudeLogoIcon());
                btn.appendChild(document.createTextNode(item.preview.replace(/^Claude\s*·\s*/, '')));
            } else {
                if (item.coachType === 'spanish') btn.classList.add('spanish-chat');
                else if (item.coachType) btn.classList.add('coach-chat');
                btn.textContent = item.preview;
            }
            btn.title = btn.textContent;
            btn.dataset.id = item.conversationId;
            btn.addEventListener('click', () => {
                openConversation(item.conversationId);
                closeDrawers();
            });

            const del = document.createElement('button');
            del.className = 'conversation-delete icon-button';
            del.title = 'Delete conversation';
            del.setAttribute('aria-label', 'Delete conversation');
            del.innerHTML = `
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="3 6 5 6 21 6"></polyline>
                    <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"></path>
                    <path d="M10 11v6"></path><path d="M14 11v6"></path>
                    <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"></path>
                </svg>`;
            del.addEventListener('click', () => deleteConversation(item.conversationId));

            row.appendChild(del);
            row.appendChild(btn);
            conversationList.appendChild(row);
        });
        highlightActiveConversation();
    } catch (e) {
        console.error('Failed to load conversations:', e);
    }
}

async function deleteConversation(conversationId) {
    try {
        const response = await fetch(`${API_URL}/conversations/${conversationId}`, { method: 'DELETE' });
        if (!response.ok) throw new Error('Failed to delete conversation');
        if (conversationId === currentConversationId) startNewChat();
        await loadConversations();
    } catch (e) {
        console.error(e);
    }
}

async function clearAllConversations() {
    if (!confirm('Clear all conversation history? Archived copies are kept on the server.')) return;
    try {
        const response = await fetch(`${API_URL}/conversations`, { method: 'DELETE' });
        if (!response.ok) throw new Error('Failed to clear history');
        startNewChat();
        await loadConversations();
    } catch (e) {
        console.error(e);
    }
}

async function openConversation(conversationId) {
    try {
        const response = await fetch(`${API_URL}/conversations/${conversationId}`);
        if (!response.ok) throw new Error('Failed to load conversation');
        const messages = await response.json();
        currentConversationId = conversationId;
        chatMessages.innerHTML = '';
        messages.forEach(m => addMessage(m.content, m.role, m.attachments, m.sentences, m.question));
        highlightActiveConversation();
        setCoachRadio(conversationCoach[conversationId] || 'none');
        activateQuiz();
    } catch (e) {
        console.error(e);
        coachNote.textContent = 'Failed to load conversation — refresh or click it in the sidebar.';
    }
}

function highlightActiveConversation() {
    setActive('.conversation-item', 'id', currentConversationId);
}
