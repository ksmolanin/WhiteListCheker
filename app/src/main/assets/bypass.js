/* Раздел «Обход»: логика поверх WebView-моста (window.Bridge).
   Режимы: «Клиент» (VPN через ноду OpenFlux) и «Выходная нода» (раздача).
   Добавление подключения — только сканированием QR-кода. */

const B = window.Bridge || null;

const bypass = {
    mode: 'all',          // all | selected
    profiles: [],         // [{name, link, secret}]
    selected: 0,
    sites: [],
    apps: [],
    running: false,
    connected: false,
    sent: 0,
    received: 0,
    currentTab: 'client', // client | node
    nodeSelected: 0,
    nodeRunning: false,
    nodeConnected: false,
    nodeShareLink: '',
    nodeError: '',
};

let lastNodeError = '';
let lastQrLink = '';

/* ---------- переключатель Клиент / Нода ---------- */
function switchBypassMode(mode) {
    bypass.currentTab = mode;
    const tabClient = document.getElementById('tab-client');
    const tabNode = document.getElementById('tab-node');
    const contentClient = document.getElementById('client-content');
    const contentNode = document.getElementById('node-content');

    if (mode === 'client') {
        if (tabClient) tabClient.classList.add('active');
        if (tabNode) tabNode.classList.remove('active');
        contentClient.classList.remove('hidden');
        contentClient.classList.add('flex');
        if (contentNode) { contentNode.classList.add('hidden'); contentNode.classList.remove('flex'); }
    } else {
        if (tabNode) tabNode.classList.add('active');
        if (tabClient) tabClient.classList.remove('active');
        if (contentNode) { contentNode.classList.remove('hidden'); contentNode.classList.add('flex'); }
        contentClient.classList.add('hidden');
        contentClient.classList.remove('flex');
    }
}

/* ---------- сворачивание «Сведения» ---------- */
function toggleDetails(type) {
    const wrapper = document.getElementById(`${type}-wrapper`);
    const arrow = document.getElementById(`${type}-arrow`);
    if (!wrapper || !arrow) return;
    if (wrapper.classList.contains('open')) {
        wrapper.classList.remove('open');
        arrow.classList.remove('rotate-180');
    } else {
        wrapper.classList.add('open');
        arrow.classList.add('rotate-180');
    }
}

/* ---------- клиент: кнопка питания ---------- */
let bypassBusy = false;        // нажали «включить», ответа ядра ещё нет
let bypassBusySince = 0;
let lastBypassError = '';

function bypassPower() {
    if (bypass.running || bypassBusy) {
        if (B) B.bypassDisconnect();
        bypass.running = false;
        bypassBusy = false;
        setBypassUi('off');
        return;
    }
    const profile = bypass.profiles[bypass.selected];
    if (!profile || !profile.link) {
        showToast('Сначала добавьте подключение');
        return;
    }
    bypassBusy = true;
    bypassBusySince = Date.now();
    setBypassUi('connecting');
    if (B) B.bypassConnect(profile.link, profile.secret || '');
}

/** state: 'off' | 'connecting' | 'on' */
function setBypassUi(state) {
    const powerBtn = document.getElementById('bypass-power-btn');
    if (!powerBtn) return;
    const powerIcon = document.getElementById('bypass-power-icon');
    const statusText = document.getElementById('bypass-status-text');

    if (state === 'off') {
        powerBtn.classList.add('border-neutral-700');
        powerBtn.classList.remove('border-blue-400', 'pulse-ring');
        powerIcon.classList.add('text-neutral-400');
        powerIcon.classList.remove('text-blue-400');
        statusText.classList.add('text-neutral-400');
        statusText.classList.remove('text-blue-400');
        statusText.innerText = 'Отключено';
        return;
    }

    // и «Подключение…», и «Подключено» — активное состояние кнопки
    powerBtn.classList.remove('border-neutral-700');
    powerBtn.classList.add('border-blue-400', 'pulse-ring');
    powerIcon.classList.remove('text-neutral-400');
    powerIcon.classList.add('text-blue-400');
    statusText.classList.remove('text-neutral-400');
    statusText.classList.add('text-blue-400');
    statusText.innerText = state === 'on' ? 'Подключено' : 'Подключение…';
}

/* ---------- режим VPN ---------- */
function toggleVpnMode() {
    bypass.mode = bypass.mode === 'all' ? 'selected' : 'all';
    const text = document.getElementById('vpn-mode-text');
    const btn = document.getElementById('vpn-mode-btn');
    if (bypass.mode === 'all') {
        if (text) text.innerText = 'VPN: весь трафик';
        if (btn) { btn.classList.remove('text-blue-400', 'border-blue-500/50'); btn.classList.add('text-neutral-300', 'border-neutral-700'); }
    } else {
        if (text) text.innerText = 'VPN: только выбранные сайты';
        if (btn) { btn.classList.remove('text-neutral-300', 'border-neutral-700'); btn.classList.add('text-blue-400', 'border-blue-500/50'); }
    }
    if (B) B.bypassSetMode(bypass.mode);
}

/* ---------- внешний IP ---------- */
function refreshIp() {
    const el = document.getElementById('external-ip');
    if (el) el.innerText = '…';
    if (B) B.bypassExternalIp();
}

window.onExternalIp = function (ip) {
    const el = document.getElementById('external-ip');
    if (el) el.innerText = ip || '—';
};

/* ---------- bottom sheets ---------- */
function openConnectionSheet() {
    const backdrop = document.getElementById('connection-sheet-backdrop');
    const content = document.getElementById('connection-sheet');
    renderConnectionList();
    backdrop.classList.add('show');
    content.classList.add('animate', 'show');
}
function closeConnectionSheet() {
    const backdrop = document.getElementById('connection-sheet-backdrop');
    const content = document.getElementById('connection-sheet');
    content.classList.remove('show');
    backdrop.classList.remove('show');
}
function openAddMenu() {
    const backdrop = document.getElementById('add-sheet-backdrop');
    const content = document.getElementById('add-sheet');
    backdrop.classList.add('show');
    content.classList.add('animate', 'show');
}
function closeAddMenu() {
    const backdrop = document.getElementById('add-sheet-backdrop');
    const content = document.getElementById('add-sheet');
    content.classList.remove('show');
    backdrop.classList.remove('show');
}

function scanBypassQr() {
    closeAddMenu();
    closeConnectionSheet();
    if (B) B.bypassScanQr();
}

/* ---------- свайп для нижних окон ---------- */
function attachSwipe(sheetId, backdropId) {
    const sheet = document.getElementById(sheetId);
    const backdrop = document.getElementById(backdropId);
    if (!sheet || !backdrop) return;
    const handle = document.getElementById(sheetId === 'connection-sheet' ? 'drag-handle'
        : sheetId === 'add-sheet' ? 'add-drag-handle' : sheetId);
    let startY = 0, delta = 0, dragging = false;

    function onStart(e) {
        dragging = true;
        startY = (e.touches ? e.touches[0].clientY : e.clientY);
        sheet.style.transition = 'none';
    }
    function onMove(e) {
        if (!dragging) return;
        const y = (e.touches ? e.touches[0].clientY : e.clientY);
        delta = y - startY;
        if (delta > 0) sheet.style.transform = `translateY(${delta}px)`;
    }
    function onEnd() {
        if (!dragging) return;
        dragging = false;
        sheet.style.transition = '';
        if (delta > 100) {
            sheet.classList.remove('show');
            backdrop.classList.remove('show');
            setTimeout(() => { sheet.style.transform = ''; }, 300);
        } else {
            sheet.style.transform = 'translateY(0)';
            setTimeout(() => { sheet.style.transform = ''; }, 300);
        }
    }
    (handle || sheet).addEventListener('touchstart', onStart, { passive: true });
    (handle || sheet).addEventListener('mousedown', onStart);
    document.addEventListener('touchmove', onMove, { passive: false });
    document.addEventListener('mousemove', onMove);
    document.addEventListener('touchend', onEnd);
    document.addEventListener('mouseup', onEnd);
}

/* ---------- список подключений ---------- */
function renderConnectionList() {
    const container = document.getElementById('connection-list');
    if (!container) return;
    container.innerHTML = '';
    if (!bypass.profiles.length) {
        container.innerHTML = '<div class="text-center py-6 text-sm text-neutral-500">Нет доступных подключений</div>';
        return;
    }
    bypass.profiles.forEach((p, i) => {
        const row = document.createElement('div');
        row.className = 'flex items-center justify-between px-4 py-3 hover:bg-neutral-800 rounded-xl cursor-pointer';
        row.innerHTML = `
            <div class="flex flex-col min-w-0">
                <span class="text-sm font-medium ${i === bypass.selected ? 'text-blue-400' : 'text-neutral-200'}">${escapeHtml(p.name)}</span>
                <span class="text-xs text-neutral-500 mt-0.5 truncate">${escapeHtml(p.link)}</span>
            </div>
            ${i === bypass.selected ? '<span class="text-blue-400 shrink-0">✓</span>' : ''}
        `;
        row.onclick = () => {
            if (B) B.bypassSelectProfile(i);
            bypass.selected = i;
            renderConnectionList();
            updateSelectedText();
            closeConnectionSheet();
        };
        container.appendChild(row);
    });
}

function updateSelectedText() {
    const el = document.getElementById('selected-connection-text');
    const p = bypass.profiles[bypass.selected];
    if (el) el.innerText = p ? p.name : 'Не выбрано';
}

function escapeHtml(s) {
    return String(s || '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
}

/* ---------- настройки клиента из Kotlin ---------- */
window.onBypassSettings = function (json) {
    try {
        const s = JSON.parse(json);
        bypass.mode = s.mode || 'all';
        bypass.profiles = s.profiles || [];
        bypass.selected = s.selected || 0;
        bypass.sites = s.sites || [];
        bypass.apps = s.apps || [];
    } catch (e) { return; }
    applyBypassSettings();
};

function applyBypassSettings() {
    updateSelectedText();
    renderConnectionList();
    renderNodeProfiles();
    const btn = document.getElementById('vpn-mode-btn');
    const text = document.getElementById('vpn-mode-text');
    if (bypass.mode === 'all') {
        if (text) text.innerText = 'VPN: весь трафик';
        if (btn) { btn.classList.remove('text-blue-400', 'border-blue-500/50'); btn.classList.add('text-neutral-300', 'border-neutral-700'); }
    } else {
        if (text) text.innerText = 'VPN: только выбранные сайты';
        if (btn) { btn.classList.remove('text-neutral-300', 'border-neutral-700'); btn.classList.add('text-blue-400', 'border-blue-500/50'); }
    }
    setBypassUi(bypass.running ? 'on' : 'off');
}

window.onBypassState = function (json) {
    let s;
    try { s = JSON.parse(json); } catch (e) { return; }
    applyBypassState(s);
};

function applyBypassState(s) {
    const running = !!s.running;
    const connected = !!s.connected;

    bypass.running = running;
    bypass.connected = connected;
    bypass.sent = s.sent || 0;
    bypass.received = s.received || 0;

    if (running) {
        bypassBusy = false;
        setBypassUi(connected ? 'on' : 'connecting');
    } else if (bypassBusy) {
        // Ядро ещё поднимается (в т.ч. ждёт разрешения на VPN) — не сбрасываем
        // кнопку раньше времени, но и не держим её вечно.
        if (Date.now() - bypassBusySince > 90000) {
            bypassBusy = false;
            setBypassUi('off');
            showToast('Не удалось подключиться', true);
        }
    } else {
        setBypassUi('off');
    }

    const err = s.error || '';
    if (err && err !== lastBypassError) {
        lastBypassError = err;
        if (!running) {
            bypassBusy = false;
            setBypassUi('off');
            showToast(err, true);
        }
    } else if (!err) {
        lastBypassError = '';
    }
}

/* Состояние VPN-клиента ядро не пушит в JS само — опрашиваем, как и ноду. */
function pollBypassState() {
    if (!B) return;
    try { applyBypassState(JSON.parse(B.bypassStateJson() || '{}')); } catch (e) {}
}

/* ---------- нода: раздаём уже добавленное подключение ---------- */
function renderNodeProfiles() {
    const list = document.getElementById('node-profile-list');
    const block = document.getElementById('node-profiles-block');
    const emptyAdd = document.getElementById('node-empty-add');
    if (!list || !block) return;

    const profiles = bypass.profiles || [];
    const has = profiles.length > 0;
    block.classList.toggle('hidden', !has);
    if (emptyAdd) emptyAdd.classList.toggle('hidden', has);

    if (!has) {
        if (bypass.nodeRunning) {
            bypass.nodeRunning = false;
            setNodeUi();
            hideNodeActive();
        }
        return;
    }
    if (bypass.nodeSelected >= profiles.length) bypass.nodeSelected = 0;

    list.innerHTML = '';
    profiles.forEach((p, i) => {
        const active = i === bypass.nodeSelected;
        const row = document.createElement('div');
        row.className = 'flex items-center px-4 py-3 cursor-pointer transition-colors border-l-4 ' +
            (active ? 'border-blue-400 bg-neutral-800/80' : 'border-transparent hover:bg-neutral-750');
        row.innerHTML = `<div class="flex flex-col min-w-0">
                <span class="text-sm font-medium ${active ? 'text-blue-400' : 'text-neutral-200'}">${escapeHtml(p.name || 'Нода')}</span>
                <span class="text-xs text-neutral-500 mt-0.5 truncate">${escapeHtml(p.link || '')}</span>
            </div>`;
        row.onclick = () => {
            bypass.nodeSelected = i;
            renderNodeProfiles();
        };
        list.appendChild(row);
    });
}

/* ---------- нода: кнопка питания ---------- */
function nodePower() {
    if (bypass.nodeRunning) {
        if (B) B.bypassNodeStop();
        bypass.nodeRunning = false;
        setNodeUi();
        hideNodeActive();
        return;
    }
    const profile = (bypass.profiles || [])[bypass.nodeSelected];
    if (!profile || !profile.link) {
        openNodeAddScreen();
        return;
    }
    startNodeWith(profile);
}

function startNodeWith(profile) {
    if (!profile || !String(profile.link).includes('telemost.yandex.ru')) {
        showToast('В этом подключении нет ссылки на Телемост', true);
        return;
    }
    if (B) {
        B.bypassNodeStart(profile.link, profile.secret || '', profile.name || 'Нода');
    }
    bypass.nodeRunning = true;
    bypass.nodeConnected = false;
    bypass.nodeShareLink = '';
    bypass.nodeError = '';
    setNodeUi();
}

/* ---------- нода: экран настроек ---------- */
function openNodeAddScreen() {
    const confInput = document.getElementById('node-conf-input');
    const secretInput = document.getElementById('node-secret-input');
    const nameInput = document.getElementById('node-name-input');

    if (B) {
        try {
            const s = JSON.parse(B.bypassNodeSettingsJson() || '{}');
            if (confInput && !confInput.value) confInput.value = s.link || '';
            if (secretInput && !secretInput.value) secretInput.value = s.secret || '';
        } catch (e) {}
    }
    if (nameInput && !nameInput.value) nameInput.value = 'Моя нода';
    if (secretInput && !secretInput.value) generateNodeSecret();

    const screen = document.getElementById('node-add-screen');
    if (screen) screen.classList.add('show');
}

function closeNodeAddScreen() {
    const screen = document.getElementById('node-add-screen');
    if (screen) screen.classList.remove('show');
}

function saveNodeAndShare() {
    const name = (document.getElementById('node-name-input')?.value || '').trim() || 'Моя нода';
    const link = (document.getElementById('node-conf-input')?.value || '').trim();
    const secret = (document.getElementById('node-secret-input')?.value || '').trim();

    if (!link.includes('telemost.yandex.ru')) {
        showToast('Вставьте ссылку на конференцию Телемост', true);
        return;
    }
    if (secret.length < 16) {
        showToast('Ключ должен быть не короче 16 символов', true);
        return;
    }

    if (B) {
        B.bypassNodeSaveSettings(link, secret);
        B.bypassAddProfile(name, link, secret);
    }

    const profiles = bypass.profiles || (bypass.profiles = []);
    const existing = profiles.findIndex(p => p.link === link && (p.secret || '') === secret);
    if (existing >= 0) {
        bypass.nodeSelected = existing;
    } else {
        profiles.push({ name: name, link: link, secret: secret });
        bypass.nodeSelected = profiles.length - 1;
    }

    closeNodeAddScreen();
    renderNodeProfiles();
    startNodeWith(profiles[bypass.nodeSelected]);
}

function setNodeUi() {
    const powerBtn = document.getElementById('node-power-btn');
    if (!powerBtn) return;
    const powerIcon = document.getElementById('node-power-icon');
    const statusText = document.getElementById('node-status-text');
    const loadingRing = document.getElementById('node-loading-ring');
    if (bypass.nodeRunning) {
        powerBtn.classList.remove('border-neutral-700');
        powerBtn.classList.add('border-blue-400', 'pulse-ring');
        powerIcon.classList.remove('text-neutral-400');
        powerIcon.classList.add('text-blue-400');
        statusText.classList.remove('text-neutral-400');
        statusText.classList.add('text-blue-400');
        if (bypass.nodeConnected) {
            statusText.innerText = 'Нода работает';
            loadingRing.classList.add('hidden');
        } else {
            statusText.innerText = 'Запускается…';
            loadingRing.classList.remove('hidden');
        }
    } else {
        powerBtn.classList.add('border-neutral-700');
        powerBtn.classList.remove('border-blue-400', 'pulse-ring');
        powerIcon.classList.add('text-neutral-400');
        powerIcon.classList.remove('text-blue-400');
        statusText.classList.add('text-neutral-400');
        statusText.classList.remove('text-blue-400');
        statusText.innerText = 'Отключено';
        loadingRing.classList.add('hidden');
    }
}

function generateNodeSecret() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    let s = '';
    for (let i = 0; i < 40; i++) s += chars[Math.floor(Math.random() * chars.length)];
    const el = document.getElementById('node-secret-input');
    if (el) el.value = s;
}

function showNodeActive() {
    const screen = document.getElementById('node-active-screen');
    if (screen) screen.classList.add('show');
    renderNodeQr();
}

function hideNodeActive() {
    const screen = document.getElementById('node-active-screen');
    if (screen) screen.classList.remove('show');
    lastQrLink = '';
}

function renderNodeQr() {
    if (!bypass.nodeShareLink || !B) return;
    if (lastQrLink === bypass.nodeShareLink) return;
    lastQrLink = bypass.nodeShareLink;
    const img = document.getElementById('node-qr');
    if (img) {
        const dataUri = B.bypassNodeQrPng();
        if (dataUri) img.src = dataUri;
    }
}

function stopNodeAndClose() {
    hideNodeActive();
    if (B) B.bypassNodeStop();
}

function shareNodeLink() {
    if (B) B.bypassNodeShare();
}

function copyNodeLink() {
    if (B) {
        B.bypassNodeCopy();
        showToast('Ссылка скопирована');
    }
}

function applyNodeState(s) {
    const wasRunning = bypass.nodeRunning;
    bypass.nodeRunning = !!s.running;
    bypass.nodeConnected = !!s.connected;
    bypass.nodeShareLink = s.shareLink || '';
    bypass.nodeError = s.error || '';
    setNodeUi();

    if (bypass.nodeRunning && bypass.nodeShareLink) {
        showNodeActive();
    } else if (wasRunning && !bypass.nodeRunning) {
        hideNodeActive();
    }

    const err = s.error || '';
    if (err && err !== lastNodeError) {
        lastNodeError = err;
        if (!bypass.nodeRunning) showToast(err, true);
    } else if (!err) {
        lastNodeError = '';
    }
}

function pollNodeState() {
    if (!B) return;
    try {
        const s = JSON.parse(B.bypassNodeStateJson() || '{}');
        applyNodeState(s);
    } catch (e) {}
}

/* ---------- сайты и приложения для VPN ---------- */
function renderBypassSites() {
    const list = document.getElementById('bypass-sites-list');
    if (!list) return;
    list.innerHTML = '';
    bypass.sites.forEach((site) => {
        const chip = document.createElement('div');
        chip.className = 'flex items-center gap-2 bg-neutral-800 border border-neutral-700 rounded-lg px-3 py-1.5 text-sm text-neutral-200';
        chip.innerHTML = `<span class="font-mono">${escapeHtml(site)}</span>`;
        const x = document.createElement('button');
        x.className = 'text-neutral-500 hover:text-red-400';
        x.textContent = '×';
        x.onclick = () => {
            bypass.sites = bypass.sites.filter(s => s !== site);
            if (B) B.bypassSetSites(JSON.stringify(bypass.sites));
            renderBypassSites();
        };
        chip.appendChild(x);
        list.appendChild(chip);
    });
}

function bypassAddSite() {
    const input = document.getElementById('bypass-site-input');
    if (!input) return;
    const val = input.value.trim().toLowerCase();
    if (!val || !val.includes('.')) { showToast('Введите домен, напр. example.com', true); return; }
    if (bypass.sites.includes(val)) { showToast('Сайт уже есть', true); return; }
    bypass.sites.push(val);
    if (B) B.bypassSetSites(JSON.stringify(bypass.sites));
    input.value = '';
    renderBypassSites();
    showToast('Сайт добавлен');
}

function renderBypassApps() {
    const list = document.getElementById('bypass-apps-list');
    if (!list) return;
    list.innerHTML = '';
    if (!bypass.apps.length) {
        list.innerHTML = '<span class="text-xs text-neutral-500">Все приложения</span>';
        return;
    }
    const labels = appLabels || {};
    bypass.apps.forEach((pkg) => {
        const chip = document.createElement('div');
        chip.className = 'flex items-center gap-2 bg-neutral-800 border border-neutral-700 rounded-lg px-3 py-1.5 text-sm text-neutral-200';
        chip.innerHTML = `<span>${escapeHtml(labels[pkg] || pkg)}</span>`;
        const x = document.createElement('button');
        x.className = 'text-neutral-500 hover:text-red-400';
        x.textContent = '×';
        x.onclick = () => {
            bypass.apps = bypass.apps.filter(a => a !== pkg);
            if (B) B.bypassSetApps(JSON.stringify(bypass.apps));
            renderBypassApps();
        };
        chip.appendChild(x);
        list.appendChild(chip);
    });
}

let appLabels = {};
let allApps = [];

function openAppsPicker() {
    const backdrop = document.getElementById('apps-picker-backdrop');
    const content = document.getElementById('apps-picker');
    backdrop.classList.add('show');
    content.classList.add('animate', 'show');
    if (B) {
        allApps = JSON.parse(B.bypassAppsJson() || '[]');
        allApps.forEach(a => { appLabels[a.pkg] = a.label; });
        renderAppsPicker();
    }
}

function closeAppsPicker() {
    const backdrop = document.getElementById('apps-picker-backdrop');
    const content = document.getElementById('apps-picker');
    content.classList.remove('show');
    backdrop.classList.remove('show');
    renderBypassApps();
}

function renderAppsPicker() {
    const list = document.getElementById('apps-picker-list');
    if (!list) return;
    list.innerHTML = '';
    allApps.forEach((app) => {
        const checked = bypass.apps.includes(app.pkg);
        const row = document.createElement('div');
        row.className = 'flex items-center justify-between px-2 py-2.5 hover:bg-neutral-800 rounded-lg cursor-pointer';
        row.innerHTML = `<span class="text-sm text-neutral-200">${escapeHtml(app.label)}</span>
            <span class="${checked ? 'text-blue-400' : 'text-neutral-600'} text-lg">${checked ? '✓' : '○'}</span>`;
        row.onclick = () => {
            if (checked) {
                bypass.apps = bypass.apps.filter(a => a !== app.pkg);
            } else {
                bypass.apps.push(app.pkg);
            }
            if (B) B.bypassSetApps(JSON.stringify(bypass.apps));
            renderAppsPicker();
        };
        list.appendChild(row);
    });
}

/* ---------- инициализация ---------- */
function bootBypass() {
    const clientPower = document.getElementById('bypass-power-btn');
    if (clientPower) clientPower.addEventListener('click', bypassPower);
    const nodePowerBtn = document.getElementById('node-power-btn');
    if (nodePowerBtn) nodePowerBtn.addEventListener('click', nodePower);
    attachSwipe('connection-sheet', 'connection-sheet-backdrop');
    attachSwipe('add-sheet', 'add-sheet-backdrop');
    attachSwipe('apps-picker', 'apps-picker-backdrop');
    if (B) {
        const s = B.bypassSettingsJson();
        if (s) window.onBypassSettings(s);
        const st = B.bypassStateJson();
        if (st) window.onBypassState(st);
    } else {
        applyBypassSettings();
    }
    renderBypassSites();
    renderBypassApps();
    renderNodeProfiles();
    pollBypassState();
    pollNodeState();
    setInterval(pollBypassState, 1000);
    setInterval(pollNodeState, 1500);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bootBypass);
} else {
    bootBypass();
}
