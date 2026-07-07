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
    // 語/字 mode toggle
    spanishModeToggle.querySelectorAll('.mode-btn').forEach(btn =>
        btn.addEventListener('click', () => {
            const mode = btn.dataset.mode;
            if (mode === spanishMode) return;
            setSpanishMode(mode);
            onCoachSelected('spanish');
        }));
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
    // Show the 語/字 toggle only when the Español radio is selected
    if (spanishModeToggle) spanishModeToggle.hidden = (value !== 'spanish');
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
        coachNote.textContent = error.message;
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

function enterWordsSetup() {
    currentConversationId = null;
    chatMessages.innerHTML = '';
    highlightActiveConversation();
    coachNote.textContent = '';
    pendingMissedWords = null;
    addMessage('Modo 字 — vocabulario. Escribe o pega palabras en español separadas por comas.', 'assistant');
    activeSetup = 'spanish-words';
}

// Render a coach's topic grid, lazily fetching (and returning) its topic list.
// Returns null if loading failed, so the caller keeps its existing cache.
async function enterTopicSetup({ welcome, setupName, endpoint, gridId, cached, onPick, render = renderTopicGrid }) {
    currentConversationId = null;
    chatMessages.innerHTML = '';
    highlightActiveConversation();
    coachNote.textContent = '';
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
            coachNote.textContent = e.message;
            startNewChat();
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
    chatInput.style.height = 'auto';
    chatInput.style.height = `${chatInput.scrollHeight}px`;
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
        addMessage(`Error: ${error.message}`, 'assistant');
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

    addMessage(words, 'user');
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
        addMessage(`Error: ${err.message}`, 'assistant');
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

    const rows = items.map(item => {
        const row = document.createElement('div');
        row.className = 'word-row';

        const english = document.createElement('span');
        english.className = 'word-english';
        english.textContent = item.english;

        const hint = document.createElement('span');
        hint.className = 'hint-icon';
        hint.dataset.hint = item.hint;
        hint.textContent = '?';
        hint.title = item.hint;

        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'word-answer';
        input.placeholder = 'español…';
        input.addEventListener('keydown', e => {
            if (e.key === 'Enter') { e.preventDefault(); checkWords(setId, rows); }
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
    checkBtn.addEventListener('click', () => checkWords(setId, rows));
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

async function checkWords(setId, rows) {
    const answers = rows.map(r => r.querySelector('.word-answer').value);
    rows.forEach(r => { r.querySelector('.word-answer').disabled = true; });

    try {
        const resp = await fetch(`${API_URL}/spanish/words/check`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ setId, answers }),
        });
        if (resp.status === 404) {
            addMessage('El conjunto de palabras ha expirado. Vuelve a introducir las palabras.', 'assistant');
            rows.forEach(r => { r.querySelector('.word-answer').disabled = false; });
            activeSetup = 'spanish-words';
            return;
        }
        const data = await resp.json();
        if (!resp.ok) throw new Error(data.message || 'Error checking answers');

        const missed = [];
        data.results.forEach((result, i) => {
            const row = rows[i];
            if (!row) return;
            row.classList.add(result.correct ? 'correct' : 'incorrect');

            const grade = document.createElement('span');
            grade.className = 'word-grade';
            grade.textContent = result.correct ? '✓' : '✗';
            row.appendChild(grade);

            if (!result.correct) {
                const reveal = document.createElement('span');
                reveal.className = 'word-spanish-reveal';
                reveal.textContent = result.spanish;
                row.appendChild(reveal);
                missed.push(result.spanish);
            }
        });

        if (missed.length > 0) {
            const practiceBtn = document.createElement('button');
            practiceBtn.className = 'topic-button';
            practiceBtn.style.marginTop = '0.5rem';
            practiceBtn.textContent = `Practicar las ${missed.length} fallada${missed.length === 1 ? '' : 's'} en modo 語`;
            practiceBtn.addEventListener('click', () => practiceMissed(missed));
            const container = rows[0].closest('.word-check');
            if (container) container.appendChild(practiceBtn);
        }
    } catch (err) {
        addMessage(`Error: ${err.message}`, 'assistant');
        rows.forEach(r => { r.querySelector('.word-answer').disabled = false; });
    }
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
        addMessage(`Error: ${err.message}`, 'assistant');
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
        hintIcon.setAttribute('data-hint', hint);
        hintIcon.textContent = '?';

        card.appendChild(text);
        card.appendChild(hintIcon);
        container.appendChild(card);
    });
    container.addEventListener('click', e => {
        const card = e.target.closest('.sentence-card');
        if (!card) return;
        chatInput.value += '\n' + card.dataset.sentence + '\n';
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
    if (spanishModeToggle) spanishModeToggle.hidden = true;
    coachNote.textContent = '';
    chatInput.focus();
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
            if (item.coachType === 'claude-architect') btn.classList.add('claude-chat');
            else if (item.coachType === 'spanish') btn.classList.add('spanish-chat');
            else if (item.coachType) btn.classList.add('coach-chat');
            btn.textContent = item.preview;
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
