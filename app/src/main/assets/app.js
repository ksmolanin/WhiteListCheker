/* Мост между этой страницей и Kotlin-бэкендом.
   Со стороны Android в WebView добавлен объект window.Bridge
   (MainActivity.WebBridge). Все методы синхронные, состояние
   прилетает обратно колбэками onNativeState / onCheckStarted /
   onSiteResult / onCheckFinished / onVpnDetected. */

const Bridge = window.Bridge || null;

let state = {
    status: 'idle',
    checking: false,
    trafficKb: 0,
    durationMs: 0,
    whiteSites: [],
    blockSites: [],
    autoCheck: true,
    intervalSec: 60
};
let results = {};          // домен -> 'OK' | 'RESET' | '--' | 'STOP'
let tickHandle = null;
let checkStartedAt = 0;
let currentTab = 'main';

const toggleButton = document.getElementById('toggle-button');
const statusText = document.getElementById('status-text');
const modeIcon = document.getElementById('mode-icon');
const toast = document.getElementById('toast');
const trafficEl = document.getElementById('traffic-count');
const timeEl = document.getElementById('check-time');
const autoCheckToggle = document.getElementById('auto-check-toggle');
const intervalSelect = document.getElementById('check-interval-select');
const intervalSection = document.getElementById('interval-section');
const intervalHint = document.getElementById('interval-hint');

/* ---------- статусы ---------- */

const ICON_GLOBE = '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/>';
const ICON_LOCK = '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"/>';
const ICON_OFFLINE = '<circle cx="12" cy="12" r="9.5" stroke-width="1.5"/>' +
    '<path stroke-linecap="round" d="M5.3 5.3l13.4 13.4" stroke-width="1.5"/>';
const ICON_UNKNOWN = '<circle cx="12" cy="12" r="9.5" stroke-width="1.5"/>' +
    '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9.4 9.2a2.7 2.7 0 015.2 .8c0 1.8-2.6 2.2-2.6 4"/>' +
    '<path stroke-linecap="round" stroke-width="1.5" d="M12 17.1h.01"/>';

const STATUS_META = {
    on:          { text: 'Включены',      color: 'red',     icon: ICON_LOCK },
    off:         { text: 'Выключены',     color: 'emerald', icon: ICON_GLOBE },
    unavailable: { text: 'Нет доступа',   color: 'neutral', icon: ICON_OFFLINE },
    unknown:     { text: 'Не определено', color: 'amber',   icon: ICON_UNKNOWN },
    checking:    { text: 'Проверка…',     color: 'sky',     icon: ICON_GLOBE },
    idle:        { text: 'Не проверено',  color: 'neutral', icon: ICON_GLOBE },
    vpn:         { text: 'Выключи VPN',   color: 'amber',   icon: ICON_OFFLINE }
};

function applyStatus(status) {
    const meta = STATUS_META[status] || STATUS_META.idle;
    statusText.innerText = meta.text;
    const textColor = meta.color === 'neutral' ? 'text-neutral-300' : `text-${meta.color}-500`;
    statusText.className = `text-xl font-bold ${textColor} tracking-wide`;

    const border = meta.color === 'neutral' ? 'border-neutral-600'
        : (meta.color === 'sky' ? 'border-sky-500' : `border-${meta.color}-500`);
    const iconColor = meta.color === 'neutral' ? 'text-neutral-400'
        : (meta.color === 'sky' ? 'text-sky-400' : `text-${meta.color}-500`);

    toggleButton.className = `status-button w-32 h-32 rounded-full border-8 ${border} bg-neutral-900 flex items-center justify-center shadow-2xl hover:bg-neutral-800 focus:outline-none mb-4`;
    modeIcon.innerHTML = meta.icon;
    modeIcon.setAttribute('class', `w-16 h-16 ${iconColor} transform transition-transform`);
}

/* ---------- списки сайтов ---------- */

function updateMainLists() {
    renderMainList('main-white-list', state.whiteSites, 'white');
    renderMainList('main-block-list', state.blockSites, 'block');
}

function renderMainList(containerId, sites, type) {
    const container = document.getElementById(containerId);
    container.innerHTML = '';

    if (!sites || sites.length === 0) {
        container.innerHTML = '<div class="text-neutral-500 text-xs">Пусто</div>';
        return;
    }

    const color = type === 'white' ? 'text-rose-300' : 'text-emerald-300';
    sites.forEach(site => {
        const row = document.createElement('div');
        row.className = 'list-row';
        row.dataset.site = site;

        const name = document.createElement('span');
        name.className = `truncate flex-1 min-w-0 ${color}`;
        name.textContent = site;

        const status = document.createElement('span');
        status.className = 'ping-placeholder shrink-0 ml-2';
        status.dataset.role = 'status';
        status.textContent = results[site] || '—';

        row.appendChild(name);
        row.appendChild(status);
        container.appendChild(row);
    });
}

function setSiteStatus(domain, label) {
    results[domain] = label;
    document.querySelectorAll(`.list-row[data-site="${CSS.escape(domain)}"] [data-role="status"]`)
        .forEach(el => {
            el.textContent = label;
            el.className = label === 'OK'
                ? 'text-emerald-400'
                : (label === 'RESET' ? 'text-amber-400' : 'text-neutral-500');
        });
}

/* ---------- проверка ---------- */

function startCheck() {
    if (state.checking) return;
    if (Bridge) Bridge.startCheck();
}

toggleButton.addEventListener('click', startCheck);

window.onCheckStarted = function () {
    state.checking = true;
    results = {};
    document.querySelectorAll('[data-role="status"]').forEach(el => {
        el.textContent = '—';
        el.className = 'ping-placeholder';
    });
    applyStatus('checking');
    trafficEl.textContent = 'считаем…';
    checkStartedAt = Date.now();
    if (tickHandle) clearInterval(tickHandle);
    tickHandle = setInterval(() => {
        timeEl.textContent = formatDuration(Date.now() - checkStartedAt);
    }, 100);
};

window.onSiteResult = function (type, domain, label) {
    setSiteStatus(domain, label);
};

window.onCheckFinished = function (status, trafficKb, durationMs) {
    state.checking = false;
    state.status = status;
    state.trafficKb = trafficKb;
    state.durationMs = durationMs;

    if (tickHandle) { clearInterval(tickHandle); tickHandle = null; }
    applyStatus(status);
    timeEl.textContent = formatDuration(durationMs);
    trafficEl.textContent = formatTraffic(trafficKb);
};

/* Счётчики трафика в системе обновляются с задержкой, поэтому бэкенд
   присылает уточнённое значение через полсекунды после вердикта */
window.onTrafficUpdated = function (trafficKb) {
    state.trafficKb = trafficKb;
    trafficEl.textContent = formatTraffic(trafficKb);
};

/* Обход/VPN включён: замер соврёт, поэтому объясняем и предлагаем выбор */
window.onVpnDetected = function (kind) {
    state.checking = false;
    if (tickHandle) { clearInterval(tickHandle); tickHandle = null; }
    applyStatus('vpn');
    showVpnModal(kind);
};

function showVpnModal(kind) {
    const modal = document.getElementById('vpn-modal');
    const title = document.getElementById('vpn-modal-title');
    if (title) {
        title.innerText = kind === 'own' ? 'Включен наш обход (OpenFlux)' : 'Включен VPN';
    }
    if (modal) modal.classList.remove('hidden');
}

function hideVpnModal() {
    const modal = document.getElementById('vpn-modal');
    if (modal) modal.classList.add('hidden');
}

/* Принудительный замер поверх VPN — по явному желанию пользователя */
function forceCheckWithVpn() {
    hideVpnModal();
    if (Bridge) Bridge.startCheckForced();
}

function formatDuration(ms) {
    if (!ms || ms < 0) return '—';
    return (ms / 1000).toFixed(1).replace('.', ',') + ' сек';
}

function formatTraffic(kb) {
    if (kb === null || kb === undefined || kb < 0) return '—';
    if (kb >= 1024) return (kb / 1024).toFixed(2).replace('.', ',') + ' МБ';
    return kb.toFixed(1).replace('.', ',') + ' КБ';
}

/* ---------- состояние и настройки ---------- */

window.onNativeState = function (json) {
    try {
        state = Object.assign(state, JSON.parse(json));
    } catch (e) {
        return;
    }
    results = {};
    applyStatus(state.checking ? 'checking' : state.status);
    updateMainLists();
    renderSettings();
};

function renderSettings() {
    autoCheckToggle.checked = !!state.autoCheck;
    intervalSelect.disabled = !state.autoCheck;
    intervalSelect.value = String(state.intervalSec);
    if (!intervalSelect.value) {
        // если пришёл интервал, которого нет в списке — показываем ближайший
        const options = Array.from(intervalSelect.options).map(o => Number(o.value));
        const closest = options.reduce((a, b) =>
            Math.abs(b - state.intervalSec) < Math.abs(a - state.intervalSec) ? b : a, options[0]);
        intervalSelect.value = String(closest);
    }
    intervalSection.classList.toggle('opacity-40', !state.autoCheck);
    if (intervalHint) {
        intervalHint.classList.toggle('hidden', !(state.autoCheck && state.intervalSec < 900));
    }
}

autoCheckToggle.addEventListener('change', (e) => {
    if (Bridge) Bridge.setAutoCheck(e.target.checked);
    state.autoCheck = e.target.checked;
    renderSettings();
    showToast(e.target.checked ? 'Автопроверка включена' : 'Автопроверка выключена');
});

intervalSelect.addEventListener('change', (e) => {
    if (Bridge) Bridge.setInterval(Number(e.target.value));
    state.intervalSec = Number(e.target.value);
    renderSettings();
    showToast('Интервал сохранён');
});

/* ---------- вкладки ---------- */

function switchTab(tabName) {
    if (currentTab === tabName) return;

    ['main', 'settings', 'bypass'].forEach(name => {
        const screen = document.getElementById(`${name}-screen`);
        if (!screen) return;
        screen.classList.add('hidden');
        screen.classList.remove('flex');
    });

    const target = document.getElementById(`${tabName}-screen`);
    if (target) {
        target.classList.remove('hidden');
        if (tabName !== 'main') target.classList.add('flex');
    }

    document.querySelectorAll('.nav-button').forEach(btn => {
        if (btn.id.includes(`nav-btn-${tabName}`)) {
            btn.classList.add('active');
            const svg = btn.querySelector('svg');
            if (svg) {
                svg.classList.remove('bounce');
                void svg.offsetWidth;
                svg.classList.add('bounce');
                setTimeout(() => svg.classList.remove('bounce'), 400);
            }
        } else {
            btn.classList.remove('active');
        }
    });

    currentTab = tabName;
}

function openMainScreen() { switchTab('main'); }
function openSettingsScreen() { switchTab('settings'); }
function openBypassScreen() { switchTab('bypass'); }

// Системная кнопка «назад»: если открыта не главная вкладка — вернуться на неё
window.androidBack = function () {
    if (currentTab !== 'main') {
        switchTab('main');
        return true;
    }
    return false;
};

/* ---------- модалки управления списками ---------- */

function showSection(id) {
    document.getElementById(id).classList.remove('hidden');
    if (id === 'white-list-manager') renderSites('white', true);
    if (id === 'block-list-manager') renderSites('block', true);
}

function closeSection(id) {
    document.getElementById(id).classList.add('hidden');
}

function renderSites(type, animate = false) {
    const container = document.getElementById(`${type}-sites-container`);
    const list = type === 'white' ? state.whiteSites : state.blockSites;
    const itemBg = type === 'white'
        ? 'bg-rose-950/40 border-rose-900/50'
        : 'bg-emerald-950/40 border-emerald-900/50';
    const textClass = type === 'white' ? 'text-rose-300' : 'text-emerald-300';

    container.innerHTML = '';

    if (!list || list.length === 0) {
        container.innerHTML = '<div class="text-neutral-500 text-center py-4">Список пуст</div>';
        return;
    }

    list.forEach((site, index) => {
        const row = document.createElement('div');
        row.className = `flex justify-between items-center p-2.5 rounded-lg border ${itemBg}`;
        if (animate) {
            row.classList.add('site-item-enter');
            row.style.animationDelay = `${index * 0.03}s`;
        }

        const label = document.createElement('span');
        label.className = `${textClass} font-mono text-xs truncate max-w-[200px]`;
        label.textContent = site;

        const del = document.createElement('button');
        del.className = 'text-neutral-500 hover:text-neutral-300 px-2 py-0.5';
        del.textContent = '×';
        del.addEventListener('click', () => deleteSite(type, site, row));

        row.appendChild(label);
        row.appendChild(del);
        container.appendChild(row);
    });
}

function addSite(type) {
    const input = document.getElementById(`new-${type}-site`);
    const value = input.value.trim().toLowerCase();

    if (!value) return;
    if (!value.includes('.')) {
        showToast('Введите корректный адрес (напр. domain.com)', true);
        return;
    }
    if (type === 'white' ? state.whiteSites.includes(value) : state.blockSites.includes(value)) {
        showToast('Сайт уже есть', true);
        return;
    }

    if (Bridge) Bridge.addSite(type, value);
    if (type === 'white') state.whiteSites.push(value);
    else state.blockSites.push(value);

    input.value = '';
    renderSites(type, false);
    updateMainLists();
    showToast(type === 'white' ? 'Белый сайт добавлен' : 'Обычный сайт добавлен');
}

function deleteSite(type, site, row) {
    row.classList.add('site-item-exit');
    setTimeout(() => {
        if (Bridge) Bridge.removeSite(type, site);
        if (type === 'white') {
            state.whiteSites = state.whiteSites.filter(s => s !== site);
        } else {
            state.blockSites = state.blockSites.filter(s => s !== site);
        }
        delete results[site];
        renderSites(type, false);
        updateMainLists();
        showToast('Сайт удалён');
    }, 300);
}

/* ---------- тост ---------- */

function showToast(message, isError = false) {
    toast.innerText = message;
    toast.className = isError
        ? 'fixed bottom-24 left-1/2 bg-red-900 border border-red-700 text-white px-4 py-2 rounded-xl text-xs shadow-xl z-[70] whitespace-nowrap show'
        : 'fixed bottom-24 left-1/2 bg-neutral-800 border border-neutral-700 text-white px-4 py-2 rounded-xl text-xs shadow-xl z-[70] whitespace-nowrap show';
    setTimeout(() => toast.classList.remove('show'), 2500);
}

/* ---------- запуск ---------- */

function boot() {
    if (Bridge) {
        const json = Bridge.getStateJson();
        if (json) window.onNativeState(json);
    }
    applyStatus(state.status);
    updateMainLists();
    renderSettings();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
} else {
    boot();
}
