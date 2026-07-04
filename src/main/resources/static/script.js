// API base URL - relative path so it works from any host
const API_URL = '/api';

// Global state
let currentConversationId = null;
let currentModel = null;
let currentEffort = null;
let models = [];
let effortLevels = [];
let pendingAttachments = []; // [{id, file, kind, objectUrl?}]

// DOM elements
let chatMessages, chatInput, sendButton, modelButtons, effortSelect, effortNote,
    conversationList, clearAllButton, attachButton, fileInput, attachmentStrip,
    composerError, dropOverlay;

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
    document.getElementById('newChatButton').addEventListener('click', startNewChat);
    effortSelect.addEventListener('change', () => { currentEffort = effortSelect.value; });
    clearAllButton.addEventListener('click', clearAllConversations);
    attachButton.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', () => { addFiles(fileInput.files); fileInput.value = ''; });
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
        const isNew = !currentConversationId;
        currentConversationId = data.conversationId;

        loadingMessage.remove();
        addMessage(data.answer, 'assistant');

        if (isNew) loadConversations();
    } catch (error) {
        loadingMessage.remove();
        addMessage(`Error: ${error.message}`, 'assistant');
        attachmentsSnapshot.forEach(a => { if (a.objectUrl) URL.revokeObjectURL(a.objectUrl); });
    } finally {
        chatInput.disabled  = false;
        sendButton.disabled = false;
        attachButton.disabled = false;
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
function addMessage(content, type, attachments) {
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
        if (type === 'assistant') {
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

// ── Conversations ─────────────────────────────────────────────

function startNewChat() {
    currentConversationId = null;
    chatMessages.innerHTML = '';
    addMessage("New chat. Pick a model on the left and ask me anything.", 'assistant');
    highlightActiveConversation();
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
        items.forEach(item => {
            const row = document.createElement('div');
            row.className = 'conversation-row';

            const btn = document.createElement('button');
            btn.className = 'conversation-item';
            btn.textContent = item.preview;
            btn.dataset.id = item.conversationId;
            btn.addEventListener('click', () => openConversation(item.conversationId));

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
        messages.forEach(m => addMessage(m.content, m.role, m.attachments));
        highlightActiveConversation();
    } catch (e) {
        console.error(e);
    }
}

function highlightActiveConversation() {
    setActive('.conversation-item', 'id', currentConversationId);
}
