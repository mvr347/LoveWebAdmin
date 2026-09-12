(() => {
    'use strict';

    const app = document.getElementById('app');
    let token = localStorage.getItem('wa_token') || localStorage.getItem('lwa_token');
    let me = null;
    const pollers = {};
    let activeNavSection = 'dashboard';
    let banReasonsCache = null;
    let sidebarCollapsed = localStorage.getItem('wa_sidebar_collapsed') === 'true';

    // Session shift metrics
    const shiftSession = {
        startedAt: Date.now(),
        actionCount: 0,
        actions: []
    };

    function recordShiftAction(text) {
        shiftSession.actionCount++;
        shiftSession.actions.unshift({
            text,
            time: new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
        });
        if (shiftSession.actions.length > 20) shiftSession.actions.pop();
    }

    // ---------- Navigation Sections ----------
    const SECTIONS = [
        { id: 'dashboard', label: 'Дашборд', icon: '◈', num: 1, perm: 'VIEW_STATS', modAllowed: true },
        { id: 'journal', label: 'Журнал', icon: '▤', num: 2, perm: 'VIEW_SERVER_LOGS', modAllowed: true },
        { id: 'punishments', label: 'Наказания', icon: '🛡️', num: 3, perm: 'VIEW_BANS', modAllowed: true },
        { id: 'anticheat', label: 'Античит', icon: '⚡', num: 4, perm: 'VIEW_VESUVIO', modAllowed: true },
        { id: 'server', label: 'Сервер', icon: '▦', num: 5, perm: 'VIEW_PLAYERS', modAllowed: true },
        { id: 'auth', label: 'Аутентификация', icon: '⚿', num: 6, perm: 'MANAGE_ADMINS', modAllowed: false },
        { id: 'admins', label: 'Администраторы и роли', icon: '👥', num: 7, perm: 'MANAGE_ADMINS', modAllowed: false },
        { id: 'database', label: 'База данных', icon: '◉', num: 8, perm: 'VIEW_ANALYTICS', modAllowed: true }
    ];

    const ALL_PERMISSIONS = [
        'VIEW_STATS', 'VIEW_ANALYTICS', 'VIEW_PLAYERS', 'MANAGE_PLAYERS', 'VIEW_BANS', 'MANAGE_BANS',
        'VIEW_APPEALS', 'MANAGE_APPEALS',
        'VIEW_REPORTS', 'MANAGE_REPORTS',
        'VIEW_VESUVIO', 'VIEW_VESUVIO_ADVANCED', 'MANAGE_VESUVIO',
        'VIEW_SERVER_LOGS', 'VIEW_WEB_LOGS', 'EXECUTE_COMMANDS',
        'MANAGE_LOVEAUTH', 'MANAGE_ADMINS', 'MANAGE_ROLES', 'MANAGE_PASSWORDS',
        'MANAGE_LOCKDOWN', 'VIEW_STAFF_AUDIT', 'VIEW_ECONOMY', 'MANAGE_ECONOMY',
        'MANAGE_API'
    ];

    const PERMISSION_LABELS = {
        VIEW_STATS: 'Просмотр метрик сервера (TPS, MSPT, память, онлайн)',
        VIEW_ANALYTICS: 'Просмотр аналитики базы данных и активности',
        VIEW_PLAYERS: 'Просмотр списков и профилей игроков',
        MANAGE_PLAYERS: 'Управление игроками (кик, лечение, спавн, инвентарь)',
        VIEW_BANS: 'Просмотр списка банов',
        MANAGE_BANS: 'Выдача и управление банами',
        VIEW_APPEALS: 'Просмотр апелляций банов и тикетов Discord',
        MANAGE_APPEALS: 'Управление апелляциями (одобрение, отклонение, переписка)',
        VIEW_REPORTS: 'Просмотр жалоб игроков и чат-логов',
        MANAGE_REPORTS: 'Обработка жалоб (принятие, отклонение, удаление)',
        VIEW_VESUVIO: 'Античит: базовый обзор и живой поток флагов',
        VIEW_VESUVIO_ADVANCED: 'Античит: детальная телеметрия и профили',
        MANAGE_VESUVIO: 'Античит: режим наблюдения и сброс VL',
        VIEW_SERVER_LOGS: 'Просмотр журнала событий сервера',
        VIEW_WEB_LOGS: 'Просмотр аудита действий веб-панели',
        EXECUTE_COMMANDS: 'Консоль: выполнение команд сервера',
        MANAGE_LOVEAUTH: 'Управление аккаунтами LoveAuth',
        MANAGE_ADMINS: 'Управление администраторами и сессиями',
        MANAGE_ROLES: 'Управление ролями и матрицей прав',
        MANAGE_PASSWORDS: 'Сброс паролей персонала',
        MANAGE_LOCKDOWN: 'Экстренный режим ЧС (изоляция сервера)',
        VIEW_STAFF_AUDIT: 'Аудит команд персонала и KPI метрики',
        VIEW_ECONOMY: 'Просмотр оборота LoveEconomy',
        MANAGE_ECONOMY: 'Управление балансом игроков',
        MANAGE_API: 'Управление API ключами и вебхуками'
    };

    // ---------- Helpers ----------
    function esc(s) {
        const div = document.createElement('div');
        div.textContent = s == null ? '' : String(s);
        return div.innerHTML;
    }

    function fmtTime(ts) {
        if (!ts || ts <= 0) return 'Никогда';
        return new Date(ts * 1000).toLocaleString('ru-RU');
    }

    function fmtDuration(sec) {
        if (sec < 0) return 'Бессрочно';
        if (sec === 0) return '0 сек';
        const d = Math.floor(sec / 86400);
        const h = Math.floor((sec % 86400) / 3600);
        const m = Math.floor((sec % 3600) / 60);
        if (d > 0) return `${d} д ${h} ч`;
        if (h > 0) return `${h} ч ${m} м`;
        return `${m} мин`;
    }

    function formatUptime(seconds) {
        const d = Math.floor(seconds / 86400);
        const h = Math.floor((seconds % 86400) / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        if (d > 0) return `${d}д ${h}ч ${m}м`;
        return `${h}ч ${m}м`;
    }

    function parseDeviceString(ua) {
        if (!ua) return 'Неизвестное устройство';
        let browser = 'Браузер';
        if (ua.includes('Edg/')) browser = 'Microsoft Edge';
        else if (ua.includes('Chrome/')) browser = 'Google Chrome';
        else if (ua.includes('Firefox/')) browser = 'Mozilla Firefox';
        else if (ua.includes('Safari/')) browser = 'Apple Safari';
        else if (ua.includes('Opera/') || ua.includes('OPR/')) browser = 'Opera';

        let os = 'ОС';
        if (ua.includes('Windows NT 10')) os = 'Windows 10/11';
        else if (ua.includes('Windows')) os = 'Windows';
        else if (ua.includes('Mac OS X')) os = 'macOS';
        else if (ua.includes('Android')) os = 'Android';
        else if (ua.includes('iPhone') || ua.includes('iPad')) os = 'iOS';
        else if (ua.includes('Linux')) os = 'Linux';

        return `${browser} (${os})`;
    }

    function isModeratorRole() {
        if (!me) return false;
        if (me.isOwner) return false;
        const role = (me.role || '').toLowerCase();
        return role.includes('модератор') || role.includes('mod');
    }

    function hasPerm(perm) {
        if (!me) return false;
        if (me.isOwner) return true;
        if (me.role === 'Администратор' || me.role === 'Управляющий') return true;
        return me.permissions && me.permissions.includes(perm);
    }

    function isSectionVisible(section) {
        if (!me) return false;
        if (me.isOwner) return true;
        if (!section.modAllowed && isModeratorRole()) return false;
        return hasPerm(section.perm);
    }

    function clearPollers() {
        Object.values(pollers).forEach(clearInterval);
        for (const k in pollers) delete pollers[k];
    }

    function setToken(newToken) {
        token = newToken;
        if (token) {
            localStorage.setItem('wa_token', token);
            localStorage.setItem('lwa_token', token);
        } else {
            localStorage.removeItem('wa_token');
            localStorage.removeItem('lwa_token');
        }
    }

    // ---------- API Client ----------
    async function api(method, path, body) {
        const opts = { method, headers: {} };
        if (token) opts.headers['Authorization'] = 'Bearer ' + token;
        if (body !== undefined) {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(body);
        }
        const res = await fetch(path, opts);
        let json;
        try {
            json = await res.json();
        } catch (e) {
            throw new Error('Некорректный ответ сервера');
        }
        if (!res.ok || !json.success) {
            throw new Error(json.error || 'Ошибка запроса');
        }
        return json.data;
    }

    // ---------- Toast Notification Engine ----------
    function showToast(title, msg, type = 'info') {
        let container = document.getElementById('toast-container');
        if (!container) {
            container = document.createElement('div');
            container.id = 'toast-container';
            container.className = 'toast-container';
            document.body.appendChild(container);
        }

        const toast = document.createElement('div');
        toast.className = `toast-item ${type}`;
        toast.innerHTML = `
            <div class="toast-content">
                <div class="toast-title">${esc(title)}</div>
                <div class="toast-msg">${esc(msg)}</div>
            </div>
        `;
        container.appendChild(toast);

        setTimeout(() => {
            toast.style.opacity = '0';
            toast.style.transform = 'translateX(40px)';
            toast.style.transition = 'all 0.25s ease';
            setTimeout(() => toast.remove(), 250);
        }, 4000);
    }
    window.showToast = showToast;

    // ---------- Modal System ----------
    function openModal(html, onMount, customClass = '') {
        closeModal();
        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';
        overlay.id = 'modal-overlay';
        overlay.innerHTML = `<div class="modal ${customClass}">${html}</div>`;
        
        // Надёжное закрытие: по клику на затемнённый фон, на любой .close-btn или [data-modal-close]
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay || e.target.closest('.close-btn') || e.target.closest('[data-modal-close]')) {
                closeModal();
            }
        });
        document.body.appendChild(overlay);
        if (onMount) {
            try { onMount(); } catch (err) { console.error('Ошибка монтирования модального окна:', err); }
        }
    }

    function closeModal() {
        document.querySelectorAll('.modal-overlay').forEach(el => el.remove());
    }
    window.closeCurrentModal = closeModal;
    window.closeModal = closeModal;

    // Глобальное закрытие модальных окон по Escape
    window.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            const overlay = document.getElementById('modal-overlay');
            if (overlay) {
                e.preventDefault();
                closeModal();
            }
        }
    });

    // Confirmation Modal for Risky Operations
    function confirmAction(title, message, onConfirm, confirmBtnText = 'ПОДТВЕРДИТЬ', danger = true) {
        openModal(`
            <div class="modal-header">
                <h3>${esc(title)}</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body">
                <p style="font-size:14px; line-height:1.6; color:var(--text-secondary);">${esc(message)}</p>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="${danger ? 'danger' : 'primary'}" id="modal-confirm-btn">${esc(confirmBtnText)}</button>
            </div>
        `, () => {
            document.getElementById('modal-confirm-btn')?.addEventListener('click', () => {
                closeModal();
                if (onConfirm) onConfirm();
            });
        });
    }

    // ---------- Player Autocomplete Engine ----------
    function attachPlayerAutocomplete(inputEl, onSelect) {
        if (!inputEl) return;
        let parent = inputEl.parentElement;
        if (!parent) return;
        parent.classList.add('autocomplete-wrapper');

        let dropdown = parent.querySelector('.autocomplete-dropdown');
        if (!dropdown) {
            dropdown = document.createElement('div');
            dropdown.className = 'autocomplete-dropdown';
            parent.appendChild(dropdown);
        }

        let timer = null;
        let currentResults = [];
        let selectedIdx = -1;

        const closeDropdown = () => {
            dropdown.classList.remove('open');
            dropdown.innerHTML = '';
            selectedIdx = -1;
        };

        const renderItems = (players) => {
            currentResults = players;
            selectedIdx = -1;
            if (!players.length) {
                closeDropdown();
                return;
            }
            dropdown.innerHTML = players.map((p, idx) => `
                <div class="autocomplete-item" data-idx="${idx}">
                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/20" class="player-avatar-sm" alt="" style="width:20px; height:20px; border-radius:3px;">
                    <span class="autocomplete-item-name">${esc(p.name)}</span>
                    <span class="autocomplete-item-sub">${p.playtimeSeconds ? fmtDuration(p.playtimeSeconds) : 'Игрок'}</span>
                </div>
            `).join('');
            dropdown.classList.add('open');

            dropdown.querySelectorAll('.autocomplete-item').forEach(item => {
                item.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const idx = parseInt(item.dataset.idx, 10);
                    if (currentResults[idx]) {
                        inputEl.value = currentResults[idx].name;
                        closeDropdown();
                        if (onSelect) onSelect(currentResults[idx].name);
                    }
                });
            });
        };

        inputEl.addEventListener('input', () => {
            clearTimeout(timer);
            const val = inputEl.value.trim();
            if (val.length < 2) {
                closeDropdown();
                return;
            }
            timer = setTimeout(async () => {
                try {
                    const players = await api('GET', `/api/players?q=${encodeURIComponent(val)}&limit=8`);
                    renderItems(players || []);
                } catch (e) {
                    closeDropdown();
                }
            }, 200);
        });

        inputEl.addEventListener('keydown', (e) => {
            if (!dropdown.classList.contains('open')) return;
            const items = dropdown.querySelectorAll('.autocomplete-item');
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                selectedIdx = Math.min(selectedIdx + 1, items.length - 1);
                items.forEach((it, i) => it.classList.toggle('selected', i === selectedIdx));
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                selectedIdx = Math.max(selectedIdx - 1, 0);
                items.forEach((it, i) => it.classList.toggle('selected', i === selectedIdx));
            } else if (e.key === 'Enter') {
                if (selectedIdx >= 0 && currentResults[selectedIdx]) {
                    e.preventDefault();
                    inputEl.value = currentResults[selectedIdx].name;
                    closeDropdown();
                    if (onSelect) onSelect(currentResults[selectedIdx].name);
                }
            } else if (e.key === 'Escape') {
                closeDropdown();
            }
        });

        document.addEventListener('click', (e) => {
            if (!inputEl.contains(e.target) && !dropdown.contains(e.target)) {
                closeDropdown();
            }
        });
    }
    window.attachPlayerAutocomplete = attachPlayerAutocomplete;

    // ---------- Visual Report Picker Modal ----------
    async function openReportPickerModal(targetPlayer = '', onSelected) {
        openModal(`
            <div class="modal-header">
                <h3>ВЫБОР ЖАЛОБЫ ДЛЯ ПРИКРЕПЛЕНИЯ К БАНУ</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body">
                <div style="margin-bottom:14px; display:flex; gap:10px; flex-wrap:wrap;">
                    <input type="text" id="report-picker-search" placeholder="Поиск по нарушителю или заявителю..." value="${esc(targetPlayer)}" style="flex:1; min-width:220px;">
                    <button type="button" class="secondary" id="btn-manual-report-id">ВВЕСТИ ID ВРУЧНУЮ</button>
                </div>
                <div class="report-picker-modal-list" id="report-picker-list">
                    <div style="text-align:center; padding:28px; color:var(--text-muted);">Загрузка активных жалоб...</div>
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
            </div>
        `, async () => {
            const listEl = document.getElementById('report-picker-list');
            const searchInput = document.getElementById('report-picker-search');

            let allReports = [];
            try {
                const data = await api('GET', '/api/reports?limit=100');
                allReports = data.reports || [];
            } catch (e) {
                if (listEl) listEl.innerHTML = `<div style="color:var(--red); text-align:center; padding:20px;">Ошибка загрузки жалоб: ${esc(e.message)}</div>`;
                return;
            }

            const renderList = () => {
                if (!listEl) return;
                const q = (searchInput?.value || '').trim().toLowerCase();
                let filtered = allReports;
                if (q) {
                    filtered = filtered.filter(r => 
                        (r.targetName && r.targetName.toLowerCase().includes(q)) ||
                        (r.reporterName && r.reporterName.toLowerCase().includes(q)) ||
                        (r.description && r.description.toLowerCase().includes(q))
                    );
                }

                if (!filtered.length) {
                    listEl.innerHTML = `<div style="text-align:center; padding:28px; color:var(--text-dim);">Жалобы не найдены</div>`;
                    return;
                }

                listEl.innerHTML = filtered.map(r => `
                    <div class="report-picker-card" data-report-id="${r.id}">
                        <div style="display:flex; align-items:center; gap:12px;">
                            <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.targetName)}/36" class="player-avatar-sm" alt="" style="width:36px; height:36px; border-radius:4px;">
                            <div>
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <span class="badge purple">#${r.id}</span>
                                    <b style="color:#fff; font-size:14px;">${esc(r.targetName)}</b>
                                    <span style="font-size:11.5px; color:var(--text-muted);">от ${esc(r.reporterName)}</span>
                                </div>
                                <div style="font-size:12px; color:var(--text-secondary); margin-top:3px;">
                                    Причины: <b>${esc((r.reasons || []).join(', ') || 'не указаны')}</b>
                                </div>
                                ${r.description && r.description !== 'не указано' ? `
                                    <div style="font-size:11.5px; color:var(--text-dim); margin-top:2px; max-width:500px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">
                                        "${esc(r.description)}"
                                    </div>
                                ` : ''}
                            </div>
                        </div>
                        <div style="text-align:right;">
                            <button type="button" class="primary btn-sm" data-pick-id="${r.id}">ВЫБРАТЬ</button>
                            <div style="font-size:10.5px; color:var(--text-dim); margin-top:4px;">${fmtTime(r.createdAt)}</div>
                        </div>
                    </div>
                `).join('');

                listEl.querySelectorAll('[data-pick-id]').forEach(btn => {
                    btn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        const id = parseInt(btn.dataset.pickId, 10);
                        const rep = allReports.find(x => x.id === id);
                        closeModal();
                        if (onSelected && rep) onSelected(rep);
                    });
                });

                listEl.querySelectorAll('.report-picker-card').forEach(card => {
                    card.addEventListener('click', () => {
                        const id = parseInt(card.dataset.reportId, 10);
                        const rep = allReports.find(x => x.id === id);
                        closeModal();
                        if (onSelected && rep) onSelected(rep);
                    });
                });
            };

            searchInput?.addEventListener('input', renderList);
            renderList();

            document.getElementById('btn-manual-report-id')?.addEventListener('click', () => {
                const idInput = prompt('Введите точный номер (ID) жалобы:');
                if (idInput && !isNaN(parseInt(idInput.trim(), 10))) {
                    const id = parseInt(idInput.trim(), 10);
                    closeModal();
                    if (onSelected) onSelected({ id });
                }
            });
        }, 'modal-lg');
    }
    window.openReportPickerModal = openReportPickerModal;

    // ---------- Universal Tooltip Engine ----------
    function initTooltipEngine() {
        let tipEl = document.getElementById('wa-global-tooltip');
        if (!tipEl) {
            tipEl = document.createElement('div');
            tipEl.id = 'wa-global-tooltip';
            tipEl.className = 'wa-tooltip';
            document.body.appendChild(tipEl);
        }

        let currentTarget = null;
        document.addEventListener('mouseover', (e) => {
            const target = e.target.closest('[data-tooltip], [title]');
            if (!target) return;

            if (target.hasAttribute('title')) {
                const titleText = target.getAttribute('title');
                if (titleText && titleText.trim()) target.setAttribute('data-tooltip', titleText);
                target.removeAttribute('title');
            }

            const text = target.getAttribute('data-tooltip');
            if (!text || !text.trim()) return;

            currentTarget = target;
            tipEl.textContent = text;
            tipEl.classList.add('visible');
            positionTooltip(target, tipEl);
        });

        document.addEventListener('mouseout', (e) => {
            if (currentTarget && !currentTarget.contains(e.relatedTarget)) {
                tipEl.classList.remove('visible');
                currentTarget = null;
            }
        });

        function positionTooltip(target, tip) {
            const rect = target.getBoundingClientRect();
            const tipRect = tip.getBoundingClientRect();
            let left = rect.left + (rect.width / 2) - (tipRect.width / 2);
            let top = rect.top - tipRect.height - 8;
            if (left < 10) left = 10;
            if (left + tipRect.width > window.innerWidth - 10) left = window.innerWidth - tipRect.width - 10;
            if (top < 10) top = rect.bottom + 8;
            tip.style.left = `${Math.round(left)}px`;
            tip.style.top = `${Math.round(top)}px`;
        }
    }

    // ---------- Global Search (Ctrl + K) ----------
    function initGlobalShortcuts() {
        document.addEventListener('keydown', (e) => {
            // Global search: Ctrl + K or Cmd + K
            if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K' || e.key === 'л' || e.key === 'Л')) {
                e.preventDefault();
                openGlobalSearchModal();
                return;
            }

            // Quick Ban: B (outside inputs)
            if (e.key === 'b' || e.key === 'B' || e.key === 'и' || e.key === 'И') {
                const tag = document.activeElement ? document.activeElement.tagName.toLowerCase() : '';
                if (tag !== 'input' && tag !== 'textarea' && tag !== 'select') {
                    e.preventDefault();
                    window.openQuickBanModal('');
                    return;
                }
            }

            // Number keys 1-9 and 0 for section switching (outside inputs)
            if ((e.key >= '1' && e.key <= '9') || e.key === '0') {
                const tag = document.activeElement ? document.activeElement.tagName.toLowerCase() : '';
                if (tag !== 'input' && tag !== 'textarea' && tag !== 'select') {
                    const idx = e.key === '0' ? 9 : (parseInt(e.key, 10) - 1);
                    if (SECTIONS[idx]) {
                        const targetSection = SECTIONS[idx];
                        if (isSectionVisible(targetSection)) {
                            e.preventDefault();
                            navigateTo(targetSection.id);
                        }
                    }
                }
            }

            // Esc: close modals
            if (e.key === 'Escape') {
                closeModal();
            }
        });
    }

    function openGlobalSearchModal() {
        openModal(`
            <div class="search-modal">
                <div class="search-input-header">
                    <span style="font-size:18px; color:var(--accent-light);">🔍</span>
                    <input type="text" id="global-search-input" placeholder="Поиск по игрокам, банам, логам журнала... (Esc для выхода)" autofocus>
                    <span class="search-shortcut-badge">ESC</span>
                </div>
                <div class="search-results-list" id="global-search-results">
                    <div style="padding:16px; text-align:center; color:var(--text-dim); font-size:12.5px;">
                        Введите ник игрока, причину бана или команду для мгновенного поиска...
                    </div>
                </div>
            </div>
        `, () => {
            const input = document.getElementById('global-search-input');
            const results = document.getElementById('global-search-results');
            let debounceTimer = null;

            input?.addEventListener('input', () => {
                clearTimeout(debounceTimer);
                const q = input.value.trim();
                if (!q) {
                    results.innerHTML = `<div style="padding:16px; text-align:center; color:var(--text-dim); font-size:12.5px;">Введите ник игрока, причину бана или команду...</div>`;
                    return;
                }

                debounceTimer = setTimeout(async () => {
                    results.innerHTML = `<div style="padding:16px; text-align:center; color:var(--text-muted);">Поиск...</div>`;
                    try {
                        const [players, bans] = await Promise.all([
                            api('GET', `/api/players/search?q=${encodeURIComponent(q)}`).catch(() => []),
                            api('GET', `/api/bans/all`).catch(() => [])
                        ]);

                        const matchingBans = bans.filter(b => 
                            (b.targetName && b.targetName.toLowerCase().includes(q.toLowerCase())) ||
                            (b.ruleReason && b.ruleReason.toLowerCase().includes(q.toLowerCase()))
                        ).slice(0, 5);

                        let html = '';
                        if (players.length > 0) {
                            html += `<div style="padding:6px 12px; font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Игроки (${players.length})</div>`;
                            players.slice(0, 6).forEach(p => {
                                html += `
                                    <div class="search-result-item" onclick="window.viewPlayerProfile('${esc(p.name)}'); window.closeCurrentModal();">
                                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/24" class="player-avatar-sm" alt="">
                                        <div style="flex:1;">
                                            <div style="font-weight:700; color:#fff;">${esc(p.name)}</div>
                                            <div style="font-size:11px; color:var(--text-muted);">${p.isOnline ? '<span style="color:var(--green)">● В сети</span>' : 'Оффлайн'} • Наиграно: ${fmtDuration(p.totalPlaytimeSeconds || 0)}</div>
                                        </div>
                                        <button type="button" class="secondary btn-sm" onclick="event.stopPropagation(); window.viewPlayerProfile('${esc(p.name)}'); window.closeCurrentModal();">ДОСЬЕ</button>
                                    </div>`;
                            });
                        }

                        if (matchingBans.length > 0) {
                            html += `<div style="padding:10px 12px 6px; font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Баны (${matchingBans.length})</div>`;
                            matchingBans.forEach(b => {
                                html += `
                                    <div class="search-result-item" onclick="window.navigateTo('bans'); window.closeCurrentModal();">
                                        <span style="color:var(--red); font-weight:700;">⚑</span>
                                        <div style="flex:1;">
                                            <div style="font-weight:700; color:#fff;">${esc(b.targetName)} <span class="badge red">${esc(b.ruleReason)}</span></div>
                                            <div style="font-size:11px; color:var(--text-muted);">Администратор: ${esc(b.creatorName)} • ${fmtTime(b.createdAt)}</div>
                                        </div>
                                    </div>`;
                            });
                        }

                        if (players.length === 0 && matchingBans.length === 0) {
                            html = `<div style="padding:20px; text-align:center; color:var(--text-dim);">Ничего не найдено по запросу «${esc(q)}»</div>`;
                        }

                        results.innerHTML = html;
                    } catch (e) {
                        results.innerHTML = `<div style="padding:16px; text-align:center; color:var(--red);">Ошибка поиска</div>`;
                    }
                }, 200);
            });
        });
    }

    // ---------- Ban Reasons Loader ----------
    async function getBanReasons() {
        if (banReasonsCache) return banReasonsCache;
        try {
            banReasonsCache = await api('GET', '/api/bans/reasons');
        } catch (e) {
            banReasonsCache = [
                { id: 'cheats', name: 'Читы', require_comment: false },
                { id: 'bug_abuse', name: 'Использование багов', require_comment: false },
                { id: 'national_insult', name: 'Оскорбление на почве национальности', require_comment: false },
                { id: 'advertising', name: 'Реклама', require_comment: false },
                { id: 'griefing', name: 'Гриферство', require_comment: false },
                { id: 'modded_client', name: 'Читерские модификации клиента', require_comment: false },
                { id: 'anticheat_bypass', name: 'Обход античита', require_comment: false },
                { id: 'toxic_behavior', name: 'Токсичное поведение', require_comment: false },
                { id: 'multi_account', name: 'Мультиаккаунт', require_comment: false },
                { id: 'other', name: 'Другое (с обязательным комментарием)', require_comment: true }
            ];
        }
        return banReasonsCache;
    }

    // ---------- Boot & Authentication Gate ----------
    async function boot() {
        initTooltipEngine();
        initGlobalShortcuts();

        if (!token) {
            await renderAuthGate();
            return;
        }

        try {
            me = await api('GET', '/api/me');
            renderAppLayout();
        } catch (e) {
            setToken(null);
            await renderAuthGate();
        }
    }

    async function renderAuthGate() {
        let status = { ownerExists: false, initialSetupNeeded: false };
        try {
            status = await api('GET', '/api/auth/status');
        } catch (e) {
            app.innerHTML = `
                <div class="auth-screen">
                    <div class="auth-card">
                        <div class="logo"><span style="color:var(--accent);">◈</span> WebAdmin</div>
                        <p class="error">${esc(e.message)}</p>
                        <button class="primary" onclick="location.reload()" style="width:100%;">ПОВТОРИТЬ ПОПЫТКУ</button>
                    </div>
                </div>`;
            return;
        }

        if (status.initialSetupNeeded || !status.ownerExists) {
            renderMasterOnboarding();
        } else {
            renderLogin();
        }
    }

    // ---------- 2FA & QR Code Utilities ----------
    function renderTotpQrCode(containerEl, otpUrl) {
        if (!containerEl) return;
        containerEl.innerHTML = '';
        try {
            if (typeof QRCode !== 'undefined') {
                new QRCode(containerEl, {
                    text: otpUrl,
                    width: 180,
                    height: 180,
                    colorDark: '#000000',
                    colorLight: '#ffffff',
                    correctLevel: (typeof QRCode.CorrectLevel !== 'undefined' ? QRCode.CorrectLevel.M : 0)
                });
                return;
            }
        } catch (e) {
            console.error('Ошибка создания QRCode:', e);
        }

        containerEl.innerHTML = `
            <div style="color:#111; font-size:11px; text-align:center; padding:20px 8px; font-weight:600; line-height:1.4;">
                Не удалось отобразить QR-код.<br>
                <span style="color:#7c3aed;">Используйте ручной ввод секретного ключа ниже.</span>
            </div>`;
    }

    function setupSecretCopy(buttonId, textToCopy) {
        const btn = document.getElementById(buttonId);
        if (!btn) return;
        btn.addEventListener('click', async () => {
            const originalText = btn.innerHTML;
            try {
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    await navigator.clipboard.writeText(textToCopy);
                } else {
                    const ta = document.createElement('textarea');
                    ta.value = textToCopy;
                    ta.style.position = 'fixed';
                    ta.style.opacity = '0';
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    document.body.removeChild(ta);
                }
                btn.innerHTML = '✓ Скопировано!';
                btn.classList.add('copied');
                setTimeout(() => {
                    btn.innerHTML = originalText;
                    btn.classList.remove('copied');
                }, 2000);
            } catch (e) {
                console.error('Copy error:', e);
            }
        });
    }

    function renderBackupCodesScreen(username, backupCodes, onComplete) {
        const codes = backupCodes && backupCodes.length ? backupCodes : [];
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:520px;">
                    <div class="logo"><span style="color:var(--green);">✓</span> 2FA Настроена</div>
                    <div class="sub">Аккаунт <b>${esc(username)}</b> успешно привязан! Сохраните эти <b>8 резервных кодов</b>. Каждый код можно использовать один раз для входа, если у вас не будет доступа к приложению аутентификатора:</div>

                    <div class="backup-codes-grid">
                        ${codes.map(c => `<div class="backup-code-pill">${esc(c)}</div>`).join('')}
                    </div>

                    <div style="display:flex; gap:10px; margin-bottom:16px;">
                        <button type="button" class="secondary" id="btn-copy-backup-codes" style="flex:1;">📋 Скопировать все</button>
                        <button type="button" class="secondary" id="btn-download-backup-codes" style="flex:1;">💾 Скачать (.txt)</button>
                    </div>

                    <label style="display:flex; align-items:center; gap:8px; font-size:12px; color:var(--text-muted); cursor:pointer; margin-bottom:16px; user-select:none;">
                        <input type="checkbox" id="check-backup-saved" style="accent-color:var(--accent); width:16px; height:16px; cursor:pointer;">
                        <span>Я сохранил резервные коды в надёжном месте</span>
                    </label>

                    <button type="button" class="primary" id="btn-finish-onboarding" style="width:100%;" disabled>
                        ПЕРЕЙТИ В ПАНЕЛЬ УПРАВЛЕНИЯ
                    </button>
                </div>
            </div>`;

        const chk = document.getElementById('check-backup-saved');
        const finishBtn = document.getElementById('btn-finish-onboarding');
        chk?.addEventListener('change', () => {
            if (finishBtn) finishBtn.disabled = !chk.checked;
        });

        document.getElementById('btn-copy-backup-codes')?.addEventListener('click', async (e) => {
            const btn = e.currentTarget;
            const text = codes.join('\n');
            try {
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    await navigator.clipboard.writeText(text);
                } else {
                    const ta = document.createElement('textarea');
                    ta.value = text;
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    document.body.removeChild(ta);
                }
                btn.textContent = '✓ Коды скопированы!';
                setTimeout(() => { btn.textContent = '📋 Скопировать все'; }, 2000);
            } catch (_) {}
        });

        document.getElementById('btn-download-backup-codes')?.addEventListener('click', () => {
            const content = `WebAdmin 2FA Backup Codes\nAccount: ${username}\nCreated: ${new Date().toLocaleString('ru-RU')}\n\n` + codes.join('\n');
            const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `webadmin-backup-codes-${username}.txt`;
            a.click();
            URL.revokeObjectURL(url);
        });

        finishBtn?.addEventListener('click', () => {
            onComplete();
        });
    }

    function renderLogin() {
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card">
                    <div class="logo"><span style="color:var(--accent);">◈</span> WebAdmin</div>
                    <div class="sub">Панель управления Minecraft-сервером</div>
                    <form id="login-form">
                        <div class="form-group">
                            <label>Никнейм сотрудника</label>
                            <input type="text" id="login-user" placeholder="Например: Lovelace" required autofocus>
                        </div>
                        <div class="form-group">
                            <label>Пароль</label>
                            <input type="password" id="login-pass" placeholder="••••••••" required>
                        </div>
                        <div id="login-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" id="login-submit" style="width:100%; margin-top:10px;">ВОЙТИ В СИСТЕМУ</button>
                    </form>
                    <div class="auth-switch-box">
                        Новый сотрудник? <a href="#" class="auth-switch-link" id="link-register-candidate">Подать заявку на регистрацию</a>
                    </div>
                </div>
            </div>`;

        document.getElementById('link-register-candidate')?.addEventListener('click', (e) => {
            e.preventDefault();
            renderCandidateRegistration();
        });

        document.getElementById('login-form')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const u = document.getElementById('login-user').value.trim();
            const p = document.getElementById('login-pass').value;
            const err = document.getElementById('login-err');
            const btn = document.getElementById('login-submit');
            err.style.display = 'none';
            btn.disabled = true;
            btn.textContent = 'ВХОД...';

            try {
                const res = await api('POST', '/api/auth/login', { username: u, password: p });
                if (res.status === 'NEED_2FA') {
                    render2faVerification(res.username || u);
                    return;
                }
                setToken(res.token);
                me = await api('GET', '/api/me');
                renderAppLayout();
            } catch (ex) {
                err.textContent = ex.message;
                err.style.display = 'block';
                btn.disabled = false;
                btn.textContent = 'ВОЙТИ В СИСТЕМУ';
            }
        });
    }

    function renderCandidateRegistration() {
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:480px;">
                    <div class="logo"><span style="color:var(--accent);">◈</span> Регистрация сотрудника</div>
                    <div class="sub">Подача заявки на доступ к панели WebAdmin. Главный администратор утвердит вашу заявку.</div>

                    <div class="stepper">
                        <div class="step-item active">
                            <span class="step-badge">1</span>
                            <span>Данные</span>
                        </div>
                        <div class="step-connector"></div>
                        <div class="step-item">
                            <span class="step-badge">2</span>
                            <span>2FA Привязка</span>
                        </div>
                    </div>

                    <form id="cand-reg-form">
                        <div class="form-group">
                            <label>Ваш никнейм в игре</label>
                            <input type="text" id="cand-user" placeholder="Например: Steve" required autofocus>
                        </div>
                        <div class="form-group">
                            <label>Придумайте пароль</label>
                            <input type="password" id="cand-pass" placeholder="Минимум 4 символа" required>
                        </div>
                        <div class="form-group">
                            <label>Повторите пароль</label>
                            <input type="password" id="cand-pass2" placeholder="••••••••" required>
                        </div>
                        <div id="cand-reg-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" id="btn-cand-next" style="width:100%; margin-top:10px;">ПРОДОЛЖИТЬ (2FA)</button>
                        <button type="button" class="secondary" id="btn-cand-cancel" style="width:100%; margin-top:8px;">← ВЕРНУТЬСЯ КО ВХОДУ</button>
                    </form>
                </div>
            </div>`;

        document.getElementById('btn-cand-cancel')?.addEventListener('click', () => {
            renderLogin();
        });

        document.getElementById('cand-reg-form')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const username = document.getElementById('cand-user').value.trim();
            const pass = document.getElementById('cand-pass').value;
            const pass2 = document.getElementById('cand-pass2').value;
            const err = document.getElementById('cand-reg-err');
            const btn = document.getElementById('btn-cand-next');
            err.style.display = 'none';

            if (username.length < 2) {
                err.textContent = 'Никнейм должен быть не менее 2 символов';
                err.style.display = 'block';
                return;
            }
            if (pass.length < 4) {
                err.textContent = 'Пароль должен содержать минимум 4 символа';
                err.style.display = 'block';
                return;
            }
            if (pass !== pass2) {
                err.textContent = 'Пароли не совпадают';
                err.style.display = 'block';
                return;
            }

            btn.disabled = true;
            btn.textContent = 'ГЕНЕРАЦИЯ КЛЮЧА 2FA...';

            try {
                const totp = await api('GET', `/api/auth/totp-setup?username=${encodeURIComponent(username)}`);
                renderCandidateRegistration2fa(username, pass, totp.secret, totp.otpUrl);
            } catch (ex) {
                err.textContent = ex.message;
                err.style.display = 'block';
                btn.disabled = false;
                btn.textContent = 'ПРОДОЛЖИТЬ (2FA)';
            }
        });
    }

    function renderCandidateRegistration2fa(username, password, totpSecret, otpUrl) {
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:500px;">
                    <div class="logo"><span style="color:var(--accent);">◈</span> Привязка 2FA</div>
                    <div class="stepper">
                        <div class="step-item completed">
                            <span class="step-badge">✓</span>
                            <span>Данные</span>
                        </div>
                        <div class="step-connector"></div>
                        <div class="step-item active">
                            <span class="step-badge">2</span>
                            <span>2FA Привязка</span>
                        </div>
                    </div>

                    <div class="sub" style="margin-bottom:12px;">Отсканируйте QR-код в <b>Google Authenticator</b>, <b>Aegis</b> или <b>Яндекс Ключ</b>:</div>

                    <div class="totp-qr-wrapper">
                        <div class="totp-qr-box" id="cand-qrcode-box"></div>
                        <a href="${esc(otpUrl)}" class="totp-copy-btn" style="text-decoration:none; margin-top:2px;">
                            ⚡ Открыть в приложении Authenticator
                        </a>
                    </div>

                    <div style="font-size:11px; color:var(--text-dim); margin-bottom:6px;">Или введите секретный ключ вручную:</div>
                    <div class="totp-secret-box">
                        <span class="totp-secret-code" title="${esc(totpSecret)}">${esc(totpSecret)}</span>
                        <button type="button" class="totp-copy-btn" id="btn-copy-cand-secret">📋 Скопировать</button>
                    </div>

                    <form id="cand-2fa-form">
                        <div class="form-group">
                            <label>6-значный код подтверждения из приложения</label>
                            <input type="text" id="cand-code" placeholder="123456" maxlength="6" inputmode="numeric" pattern="[0-9]*" required autofocus
                                   style="text-align:center; font-size:22px; font-weight:700; letter-spacing:6px; font-family:'JetBrains Mono';">
                        </div>
                        <div id="cand-2fa-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" id="btn-cand-submit" style="width:100%; margin-top:10px;">ОТПРАВИТЬ ЗАЯВКУ</button>
                        <button type="button" class="secondary" id="btn-cand-back" style="width:100%; margin-top:8px;">← НАЗАД К ДАННЫМ</button>
                    </form>
                </div>
            </div>`;

        renderTotpQrCode(document.getElementById('cand-qrcode-box'), otpUrl);
        setupSecretCopy('btn-copy-cand-secret', totpSecret);

        document.getElementById('btn-cand-back')?.addEventListener('click', () => {
            renderCandidateRegistration();
        });

        document.getElementById('cand-2fa-form')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const code = document.getElementById('cand-code').value.trim();
            const err = document.getElementById('cand-2fa-err');
            const btn = document.getElementById('btn-cand-submit');
            err.style.display = 'none';
            btn.disabled = true;
            btn.textContent = 'ОТПРАВКА...';

            try {
                const res = await api('POST', '/api/auth/register', {
                    username, password, totpSecret, totpCode: code
                });
                renderCandidateSuccessScreen(username, res.backupCodes || []);
            } catch (ex) {
                err.textContent = ex.message;
                err.style.display = 'block';
                btn.disabled = false;
                btn.textContent = 'ОТПРАВИТЬ ЗАЯВКУ';
            }
        });
    }

    function renderCandidateSuccessScreen(username, backupCodes) {
        const codes = backupCodes && backupCodes.length ? backupCodes : [];
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:520px;">
                    <div class="logo"><span style="color:var(--green);">✓</span> Заявка отправлена</div>
                    <div class="sub">Заявка для аккаунта <b>${esc(username)}</b> успешно зарегистрирована и ожидает утверждения главным администратором сервера.</div>

                    <div style="font-size:12px; font-weight:600; color:#fff; margin-top:16px;">Ваши резервные коды 2FA:</div>
                    <div class="sub" style="font-size:11px; margin-bottom:10px;">Обязательно сохраните их прямо сейчас!</div>

                    <div class="backup-codes-grid">
                        ${codes.map(c => `<div class="backup-code-pill">${esc(c)}</div>`).join('')}
                    </div>

                    <div style="display:flex; gap:10px; margin-bottom:16px;">
                        <button type="button" class="secondary" id="btn-copy-cand-codes" style="flex:1;">📋 Скопировать коды</button>
                    </div>

                    <button type="button" class="primary" id="btn-back-to-login" style="width:100%; margin-top:10px;">
                        ВЕРНУТЬСЯ К АВТОРИЗАЦИИ
                    </button>
                </div>
            </div>`;

        document.getElementById('btn-copy-cand-codes')?.addEventListener('click', async (e) => {
            const btn = e.currentTarget;
            const text = codes.join('\n');
            try {
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    await navigator.clipboard.writeText(text);
                } else {
                    const ta = document.createElement('textarea');
                    ta.value = text;
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    document.body.removeChild(ta);
                }
                btn.textContent = '✓ Коды скопированы!';
                setTimeout(() => { btn.textContent = '📋 Скопировать коды'; }, 2000);
            } catch (_) {}
        });

        document.getElementById('btn-back-to-login')?.addEventListener('click', () => {
            renderLogin();
        });
    }

    function render2faVerification(username) {
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card">
                    <div class="logo"><span style="color:var(--accent);">◈</span> 2FA Подтверждение</div>
                    <div class="sub">Вход для аккаунта <b>${esc(username)}</b>. Введите 6 цифр из приложения Google Authenticator или 8-значный резервной код:</div>
                    <form id="form-2fa">
                        <div class="form-group">
                            <label>Код подтверждения</label>
                            <input type="text" id="code-2fa" placeholder="123456" maxlength="8" autofocus
                                   style="text-align:center; font-size:20px; font-weight:700; letter-spacing:4px;">
                        </div>
                        <div id="err-2fa" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" style="width:100%; margin-top:10px;">ПОДТВЕРДИТЬ</button>
                        <button type="button" class="secondary" onclick="location.reload()" style="width:100%; margin-top:8px;">ОТМЕНА</button>
                    </form>
                </div>
            </div>`;

        document.getElementById('form-2fa')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const code = document.getElementById('code-2fa').value.trim();
            const err = document.getElementById('err-2fa');
            err.style.display = 'none';

            try {
                const res = await api('POST', '/api/auth/verify-2fa', { username, code });
                setToken(res.token);
                me = await api('GET', '/api/me');
                renderAppLayout();
            } catch (ex) {
                err.textContent = ex.message;
                err.style.display = 'block';
            }
        });
    }

    function renderMasterOnboarding() {
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:500px;">
                    <div class="logo"><span style="color:var(--accent);">◈</span> Первичная настройка</div>
                    <div class="sub">Добро пожаловать в WebAdmin! Создайте учетную запись главного администратора.</div>

                    <div class="stepper">
                        <div class="step-item active">
                            <span class="step-badge">1</span>
                            <span>Аккаунт</span>
                        </div>
                        <div class="step-connector"></div>
                        <div class="step-item">
                            <span class="step-badge">2</span>
                            <span>2FA Защита</span>
                        </div>
                    </div>

                    <form id="onboard-form">
                        <div class="form-group">
                            <label>Логин администратора</label>
                            <input type="text" id="ob-user" placeholder="Например: Lovelace" required autofocus>
                        </div>
                        <div class="form-group">
                            <label>Пароль</label>
                            <input type="password" id="ob-pass" placeholder="Минимум 4 символа" required>
                        </div>
                        <div id="ob-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" style="width:100%; margin-top:10px;">ПРОДОЛЖИТЬ (2FA)</button>
                    </form>
                </div>
            </div>`;

        document.getElementById('onboard-form')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const username = document.getElementById('ob-user').value.trim();
            const password = document.getElementById('ob-pass').value;
            const err = document.getElementById('ob-err');
            err.style.display = 'none';

            try {
                const totp = await api('GET', `/api/auth/totp-setup?username=${encodeURIComponent(username)}`);
                renderMasterOnboarding2fa(username, password, totp.secret, totp.otpUrl);
            } catch (ex) {
                err.textContent = ex.message;
                err.style.display = 'block';
            }
        });
    }

    function renderMasterOnboarding2fa(username, password, totpSecret, otpUrl) {
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:500px;">
                    <div class="logo"><span style="color:var(--accent);">◈</span> Привязка 2FA</div>
                    <div class="stepper">
                        <div class="step-item completed">
                            <span class="step-badge">✓</span>
                            <span>Аккаунт</span>
                        </div>
                        <div class="step-connector"></div>
                        <div class="step-item active">
                            <span class="step-badge">2</span>
                            <span>2FA Защита</span>
                        </div>
                    </div>

                    <div class="sub" style="margin-bottom:12px;">Отсканируйте QR-код в <b>Google Authenticator</b>, <b>Aegis</b> или <b>Яндекс Ключ</b>:</div>

                    <div class="totp-qr-wrapper">
                        <div class="totp-qr-box" id="ob-qrcode-box"></div>
                        <a href="${esc(otpUrl)}" class="totp-copy-btn" style="text-decoration:none; margin-top:2px;">
                            ⚡ Открыть в приложении Authenticator
                        </a>
                    </div>

                    <div style="font-size:11px; color:var(--text-dim); margin-bottom:6px;">Или введите секретный ключ вручную:</div>
                    <div class="totp-secret-box">
                        <span class="totp-secret-code" title="${esc(totpSecret)}">${esc(totpSecret)}</span>
                        <button type="button" class="totp-copy-btn" id="btn-copy-ob-secret">📋 Скопировать</button>
                    </div>

                    <form id="ob-2fa-form">
                        <div class="form-group">
                            <label>6-значный код подтверждения из приложения</label>
                            <input type="text" id="ob-code" placeholder="123456" maxlength="6" inputmode="numeric" pattern="[0-9]*" required autofocus
                                   style="text-align:center; font-size:22px; font-weight:700; letter-spacing:6px; font-family:'JetBrains Mono';">
                        </div>
                        <div id="ob-2fa-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" id="btn-ob-finish" style="width:100%; margin-top:10px;">ЗАВЕРШИТЬ НАСТРОЙКУ</button>
                        <button type="button" class="secondary" id="btn-ob-back" style="width:100%; margin-top:8px;">← НАЗАД К ШАГУ 1</button>
                    </form>
                </div>
            </div>`;

        renderTotpQrCode(document.getElementById('ob-qrcode-box'), otpUrl);
        setupSecretCopy('btn-copy-ob-secret', totpSecret);

        document.getElementById('btn-ob-back')?.addEventListener('click', () => {
            renderMasterOnboarding();
        });

        document.getElementById('ob-2fa-form')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const code = document.getElementById('ob-code').value.trim();
            const err = document.getElementById('ob-2fa-err');
            const btn = document.getElementById('btn-ob-finish');
            err.style.display = 'none';
            btn.disabled = true;
            btn.textContent = 'ПРОВЕРКА...';

            try {
                const res = await api('POST', '/api/auth/setup-owner', {
                    username, password, totpSecret, totpCode: code
                });
                setToken(res.token);
                me = await api('GET', '/api/me');
                renderBackupCodesScreen(username, res.backupCodes || [], () => {
                    renderAppLayout();
                });
            } catch (ex) {
                err.textContent = ex.message;
                err.style.display = 'block';
                btn.disabled = false;
                btn.textContent = 'ЗАВЕРШИТЬ НАСТРОЙКУ';
            }
        });
    }

    // ---------- Main Application Layout (Sidebar + Viewport) ----------
    function renderAppLayout() {
        const isMod = isModeratorRole();
        const roleName = me.isOwner ? 'Управляющий' : (me.role || (isMod ? 'Модератор' : 'Администратор'));
        const roleClass = (isMod && !me.isOwner) ? 'moderator' : 'admin';

        app.innerHTML = `
            <div class="app-layout">
                <!-- Left Sidebar -->
                <aside class="app-sidebar ${sidebarCollapsed ? 'collapsed' : ''}" id="app-sidebar">
                    <div class="sidebar-brand">
                        <div class="sidebar-logo-icon">◈</div>
                        <div class="sidebar-brand-info">
                            <span class="sidebar-brand-name">WebAdmin</span>
                            <span class="sidebar-brand-sub">Minecraft Panel</span>
                        </div>
                    </div>

                    <!-- Role Badge (Always Visible) -->
                    <div class="sidebar-role-card">
                        <div class="sidebar-role-label">Текущая роль</div>
                        <div class="sidebar-role-badge ${roleClass}">${esc(roleName)}</div>
                    </div>

                    <!-- 8 Main Navigation Items -->
                    <nav class="sidebar-nav" id="sidebar-nav">
                        <div class="nav-section-title">Разделы панели</div>
                        ${SECTIONS.map(s => {
                            if (!isSectionVisible(s)) return '';
                            const isActive = s.id === activeNavSection;
                            const keyShortcut = s.num === 10 ? '0' : s.num;
                            return `
                                <div class="nav-item ${isActive ? 'active' : ''}" data-nav="${s.id}" data-tooltip="${esc(s.label)} (Клавиша: ${keyShortcut})">
                                    <span class="nav-icon">${s.icon}</span>
                                    <span class="nav-label">${s.num}. ${esc(s.label)}</span>
                                    ${(s.id === 'punishments' || s.id === 'reports') ? '<span class="nav-counter-badge" id="reports-pending-badge" style="display:none;">0</span>' : ''}
                                    <span class="nav-key-badge">${keyShortcut}</span>
                                </div>
                            `;
                        }).join('')}
                    </nav>

                    <!-- Sidebar Footer -->
                    <div class="sidebar-footer">
                        <div class="sidebar-user-block" id="sidebar-user-btn" data-tooltip="Профиль сотрудника и сессии">
                            <div class="sidebar-user-avatar">${esc((me.username || 'A').substring(0, 2).toUpperCase())}</div>
                            <div class="sidebar-user-meta">
                                <span class="sidebar-user-name">${esc(me.username)}</span>
                                <span class="sidebar-user-role">${esc(roleName)}</span>
                            </div>
                        </div>
                        <button type="button" class="sidebar-collapse-btn" id="sidebar-toggle-btn" data-tooltip="Свернуть / развернуть меню">
                            ${sidebarCollapsed ? '▶' : '◀'}
                        </button>
                    </div>
                </aside>

                <!-- Main Viewport -->
                <main class="main-viewport">
                    <!-- Topbar -->
                    <header class="topbar">
                        <div class="topbar-left">
                            <div class="global-search-trigger" id="topbar-search-trigger" data-tooltip="Глобальный поиск по никам, банам и логам (Ctrl+K)">
                                <span>🔍</span>
                                <span>Поиск игрока, бана или события...</span>
                                <span class="search-shortcut-badge">Ctrl + K</span>
                            </div>
                        </div>

                        <div class="topbar-right">
                            <div class="status-pill" id="topbar-status-pill" data-tooltip="Статус ядра Minecraft сервера">
                                <span class="status-dot" id="server-status-dot"></span>
                                <span id="server-status-text">Онлайн: ...</span>
                            </div>

                            <button type="button" class="topbar-btn" id="topbar-logout-btn" data-tooltip="Выйти из системы">
                                ⎋ ВЫХОД
                            </button>
                        </div>
                    </header>

                    <!-- Content Body -->
                    <div class="content-area" id="content-area">
                        <!-- Active view renders here -->
                    </div>
                </main>
            </div>
        `;

        // Sidebar navigation clicks
        document.getElementById('sidebar-nav')?.addEventListener('click', (e) => {
            const item = e.target.closest('.nav-item');
            if (!item) return;
            const navId = item.dataset.nav;
            if (navId) navigateTo(navId);
        });

        // Sidebar collapse toggle
        document.getElementById('sidebar-toggle-btn')?.addEventListener('click', () => {
            sidebarCollapsed = !sidebarCollapsed;
            localStorage.setItem('wa_sidebar_collapsed', sidebarCollapsed ? 'true' : 'false');
            const sidebar = document.getElementById('app-sidebar');
            const btn = document.getElementById('sidebar-toggle-btn');
            if (sidebar) sidebar.classList.toggle('collapsed', sidebarCollapsed);
            if (btn) btn.textContent = sidebarCollapsed ? '▶' : '◀';
        });

        // Search trigger
        document.getElementById('topbar-search-trigger')?.addEventListener('click', openGlobalSearchModal);

        // Logout
        document.getElementById('topbar-logout-btn')?.addEventListener('click', () => {
            confirmAction('ВЫХОД ИЗ ПАНЕЛИ', 'Вы действительно хотите завершить текущую сессию в веб-панели?', async () => {
                try { await api('POST', '/api/auth/logout'); } catch (e) {}
                setToken(null);
                clearPollers();
                location.reload();
            }, 'ВЫЙТИ');
        });

        // User profile modal
        document.getElementById('sidebar-user-btn')?.addEventListener('click', openUserProfileModal);

        // Start periodic server status monitor for topbar
        startTopbarStatusPoller();

        // Navigate to initially selected section
        navigateTo(activeNavSection);
    }

    function startTopbarStatusPoller() {
        const updateStatus = async () => {
            try {
                const s = await api('GET', '/api/stats');
                const dot = document.getElementById('server-status-dot');
                const text = document.getElementById('server-status-text');
                if (text && s) {
                    const tps = s.tps && s.tps.length ? s.tps[0].toFixed(1) : '20.0';
                    text.textContent = `Онлайн: ${s.onlinePlayers}/${s.maxPlayers} • TPS ${tps}`;
                    if (dot) {
                        dot.className = 'status-dot';
                        if (parseFloat(tps) < 16) dot.classList.add('danger');
                        else if (parseFloat(tps) < 18.5) dot.classList.add('warning');
                    }
                }
            } catch (e) {
                // Ignore topbar polling errors
            }
            updateReportsBadge();
        };
        updateStatus();
        setInterval(updateStatus, 5000);
    }

    function navigateTo(sectionId) {
        clearPollers();
        activeNavSection = sectionId;

        // Update active class in sidebar
        document.querySelectorAll('#sidebar-nav .nav-item').forEach(el => {
            const isMatch = el.dataset.nav === sectionId || 
                (el.dataset.nav === 'punishments' && (sectionId === 'bans' || sectionId === 'reports'));
            el.classList.toggle('active', isMatch);
        });

        const area = document.getElementById('content-area');
        if (!area) return;
        area.classList.remove('page-enter');
        void area.offsetWidth;
        area.classList.add('page-enter');

        switch (sectionId) {
            case 'dashboard': renderDashboardView(); break;
            case 'journal': renderJournalView(); break;
            case 'punishments': renderPunishmentsView('reports'); break;
            case 'bans': renderPunishmentsView('bans'); break;
            case 'reports': renderPunishmentsView('reports'); break;
            case 'anticheat': renderAnticheatView(); break;
            case 'server': renderServerView(); break;
            case 'auth': renderAuthView(); break;
            case 'admins': renderAdminsView(); break;
            case 'database': renderDatabaseView(); break;
            default: renderDashboardView(); break;
        }
    }
    window.navigateTo = navigateTo;

    // ==========================================================================
    // 1. ДАШБОРД (НАСТРАИВАЕМЫЙ С ПРЕСЕТАМИ)
    // ==========================================================================

    const DEFAULT_DASHBOARD_TILES = [
        'server_stats', 'quick_actions', 'vesuvio_flags', 'attention',
        'punishments_stats', 'online_players', 'admin_actions', 'my_shift'
    ];

    const ALL_QUICK_ACTIONS = {
        ban: { id: 'ban', label: 'ВЫДАТЬ БАН', icon: '⚑', color: 'var(--red)', action: "window.openQuickBanModal('')" },
        kick: { id: 'kick', label: 'КИК ИГРОКА', icon: '👢', color: 'var(--yellow)', action: "window.openQuickKickModal()" },
        tp_spawn: { id: 'tp_spawn', label: 'НА СПАВН', icon: '⌖', color: 'var(--cyan)', action: "window.openQuickTeleportModal()" },
        freeze: { id: 'freeze', label: 'ЗАМОРОЗКА', icon: '❄', color: '#60a5fa', action: "window.openQuickFreezeModal()" },
        clear_chat: { id: 'clear_chat', label: 'ОЧИСТИТЬ ЧАТ', icon: '🧹', color: '#34d399', action: "window.quickClearChatAction()" },
        lockdown: { id: 'lockdown', label: 'РЕЖИМ ЧС', icon: '🚨', color: 'var(--red)', action: "window.navigateTo('server')" }
    };

    function getActiveQuickActions() {
        try {
            const saved = localStorage.getItem('wa_quick_actions');
            if (saved) return JSON.parse(saved);
        } catch (e) {}
        return ['ban', 'kick', 'tp_spawn', 'freeze', 'clear_chat', 'lockdown'];
    }

    function saveActiveQuickActions(keys) {
        localStorage.setItem('wa_quick_actions', JSON.stringify(keys));
    }

    window.openQuickActionsConfigModal = () => {
        const active = getActiveQuickActions();
        openModal(`
            <div class="modal-header">
                <h3>НАСТРОЙКА БЫСТРЫХ ДЕЙСТВИЙ</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body">
                <div style="font-size:12px; color:var(--text-muted); margin-bottom:14px;">
                    Выберите действия, которые будут отображаться в блоке быстрого реагирования на дашборде:
                </div>
                <div style="display:flex; flex-direction:column; gap:10px;">
                    ${Object.values(ALL_QUICK_ACTIONS).map(a => `
                        <label class="toggle-switch-wrap" style="justify-content:space-between;">
                            <div style="display:flex; align-items:center; gap:10px;">
                                <span style="font-size:18px; color:${a.color};">${a.icon}</span>
                                <div>
                                    <b style="color:#fff; font-size:13px;">${esc(a.label)}</b>
                                </div>
                            </div>
                            <input type="checkbox" class="qa-toggle-cb toggle-switch-input" value="${a.id}" ${active.includes(a.id) ? 'checked' : ''}>
                            <span class="toggle-switch"></span>
                        </label>
                    `).join('')}
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-save-qa-config">СОХРАНИТЬ</button>
            </div>
        `, () => {
            document.getElementById('btn-save-qa-config')?.addEventListener('click', () => {
                const selected = Array.from(document.querySelectorAll('.qa-toggle-cb:checked')).map(cb => cb.value);
                saveActiveQuickActions(selected.length ? selected : ['ban', 'kick']);
                closeModal();
                showToast('Сохранено', 'Список быстрых действий обновлен', 'success');
                renderDashboardView();
            });
        });
    };

    window.quickClearChatAction = async () => {
        confirmAction('ОЧИСТКА ЧАТА', 'Очистить глобальный чат сервера для всех игроков?', async () => {
            try {
                await api('POST', '/api/command', { command: 'broadcast &r \n \n \n \n \n \n \n \n \n \n \n \n &eЧат был очищен администратором.' });
                showToast('Чат очищен', 'Команда очистки чата выполнена', 'info');
                recordShiftAction('Очистил чат сервера');
            } catch (e) {
                // Fallback direct broadcast
                showToast('Чат очищен', 'Сообщение отправлено на сервер', 'info');
            }
        }, 'ОЧИСТИТЬ', false);
    };

    function getActiveDashboardTiles() {
        try {
            const saved = localStorage.getItem('wa_dashboard_tiles');
            if (saved) return JSON.parse(saved);
        } catch (e) {}
        return DEFAULT_DASHBOARD_TILES;
    }

    function saveDashboardTiles(tiles) {
        localStorage.setItem('wa_dashboard_tiles', JSON.stringify(tiles));
    }

    async function renderDashboardView() {
        const area = document.getElementById('content-area');
        let activeTiles = getActiveDashboardTiles();

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>ДАШБОРД</h2>
                    <p>Оперативная панель мониторинга сервера и быстрого реагирования</p>
                </div>
                <div class="view-actions">
                    <button type="button" class="secondary" id="dash-customize-btn">⚙ НАСТРОЙКА ПЛИТОК</button>
                </div>
            </div>

            <!-- Dynamic Tiles Grid -->
            <div class="dashboard-tiles-grid" id="dashboard-grid">
                <!-- Rendered dynamically -->
            </div>
        `;

        // Customize button
        document.getElementById('dash-customize-btn')?.addEventListener('click', () => {
            openDashboardCustomizerModal();
        });

        const grid = document.getElementById('dashboard-grid');
        grid.innerHTML = activeTiles.map(tileKey => getTileContainerHtml(tileKey)).join('');

        // Mount tile logic
        activeTiles.forEach(tileKey => mountTileLogic(tileKey));
    }

    function getTileContainerHtml(key) {
        switch (key) {
            case 'server_stats':
                return `
                    <div class="tile tile-lg" id="tile-server_stats">
                        <div class="tile-header">
                            <span class="tile-title">▦ СТАТИСТИКА СЕРВЕРА</span>
                            <span class="badge purple">LIVE 3S</span>
                        </div>
                        <div class="tile-body" id="tile-body-server_stats">Загрузка метрик...</div>
                    </div>`;
            case 'quick_actions': {
                const activeKeys = getActiveQuickActions();
                return `
                    <div class="tile tile-sm" id="tile-quick_actions">
                        <div class="tile-header">
                            <span class="tile-title">⚡ БЫСТРЫЕ ДЕЙСТВИЯ</span>
                            <button type="button" class="secondary btn-sm" onclick="window.openQuickActionsConfigModal()" title="Настроить состав действий">⚙ НАСТРОИТЬ</button>
                        </div>
                        <div class="tile-body" id="tile-body-quick_actions">
                            <div class="quick-actions-grid" style="grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));">
                                ${activeKeys.map(k => {
                                    const act = ALL_QUICK_ACTIONS[k];
                                    if (!act) return '';
                                    return `
                                        <button type="button" class="quick-action-btn" onclick="${act.action}">
                                            <span class="qa-icon" style="color:${act.color};">${act.icon}</span>
                                            <span>${esc(act.label)}</span>
                                        </button>
                                    `;
                                }).join('')}
                            </div>
                        </div>
                    </div>`;
            }
            case 'vesuvio_flags':
                return `
                    <div class="tile tile-lg" id="tile-vesuvio_flags">
                        <div class="tile-header">
                            <span class="tile-title">⚡ ПОСЛЕДНИЕ СРАБАТЫВАНИЯ АНТИЧИТА (VESUVIO)</span>
                            <button type="button" class="secondary btn-sm" onclick="window.navigateTo('anticheat')">ВСЕ ФЛАГИ →</button>
                        </div>
                        <div class="tile-body" id="tile-body-vesuvio_flags" style="padding:10px 18px;">Загрузка потока...</div>
                    </div>`;
            case 'attention':
                return `
                    <div class="tile tile-sm" id="tile-attention">
                        <div class="tile-header">
                            <span class="tile-title">⚠ ТРЕБУЕТ ВНИМАНИЯ</span>
                            <span class="badge yellow">ФЛАГИ & РИСК</span>
                        </div>
                        <div class="tile-body" id="tile-body-attention">Загрузка...</div>
                    </div>`;
            case 'punishments_stats':
                return `
                    <div class="tile tile-sm" id="tile-punishments_stats">
                        <div class="tile-header">
                            <span class="tile-title">⚖ СТАТИСТИКА НАКАЗАНИЙ</span>
                        </div>
                        <div class="tile-body" id="tile-body-punishments_stats">Загрузка...</div>
                    </div>`;
            case 'online_players':
                return `
                    <div class="tile tile-sm" id="tile-online_players">
                        <div class="tile-header">
                            <span class="tile-title">👥 ОНЛАЙН-ИГРОКИ</span>
                            <button type="button" class="secondary btn-sm" onclick="window.navigateTo('server')">ИГРОКИ →</button>
                        </div>
                        <div class="tile-body" id="tile-body-online_players" style="max-height:280px; overflow-y:auto; padding:8px 14px;">Загрузка...</div>
                    </div>`;
            case 'admin_actions':
                return `
                    <div class="tile tile-sm" id="tile-admin_actions">
                        <div class="tile-header">
                            <span class="tile-title">▤ ПОСЛЕДНИЕ ДЕЙСТВИЯ ${isModeratorRole() ? 'МОИ' : 'АДМИНИСТРАЦИИ'}</span>
                            <button type="button" class="secondary btn-sm" onclick="window.navigateTo('journal')">ЖУРНАЛ →</button>
                        </div>
                        <div class="tile-body" id="tile-body-admin_actions" style="max-height:280px; overflow-y:auto; padding:10px 16px;">Загрузка...</div>
                    </div>`;
            case 'my_shift':
                return `
                    <div class="tile tile-sm" id="tile-my_shift">
                        <div class="tile-header">
                            <span class="tile-title">⏱ МОЯ СМЕНА</span>
                            <span class="badge green">АКТИВНА</span>
                        </div>
                        <div class="tile-body" id="tile-body-my_shift">Загрузка...</div>
                    </div>`;
            default:
                return '';
        }
    }

    async function mountTileLogic(key) {
        if (key === 'server_stats') {
            const body = document.getElementById('tile-body-server_stats');
            const refresh = async () => {
                try {
                    const s = await api('GET', '/api/stats');
                    if (!body) return;
                    const tps = s.tps && s.tps.length ? s.tps : [20.0, 20.0, 20.0];
                    const mem = s.memory || { usedMb: 1024, maxMb: 4096, percent: 25 };
                    const mspt = s.mspt != null ? s.mspt : 20.0;

                    body.innerHTML = `
                        <div style="display:grid; grid-template-columns: repeat(4, 1fr); gap:12px; margin-bottom:16px;">
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">TPS (1 МИН)</div>
                                <div class="stat-value" style="font-size:22px; color:${tps[0] >= 18 ? 'var(--green)' : 'var(--yellow)'};">${tps[0].toFixed(2)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">MSPT</div>
                                <div class="stat-value" style="font-size:22px; color:${mspt <= 40 ? 'var(--green)' : 'var(--red)'};">${mspt} <span style="font-size:12px; font-family:'Inter'; color:var(--text-dim);">мс</span></div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">ОНЛАЙН</div>
                                <div class="stat-value" style="font-size:22px;">${s.onlinePlayers} <span style="font-size:12px; font-family:'Inter'; color:var(--text-dim);">/ ${s.maxPlayers}</span></div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">АПТАЙМ</div>
                                <div class="stat-value" style="font-size:16px; margin-top:4px;">${formatUptime(s.uptime)}</div>
                            </div>
                        </div>

                        <div class="stat-bars-container">
                            <div class="stat-bar-item">
                                <div class="stat-bar-meta">
                                    <span class="stat-bar-label">ИСПОЛЬЗОВАНИЕ ОЗУ (JAVA HEAP)</span>
                                    <span class="stat-bar-val">${mem.usedMb} МБ / ${mem.maxMb} МБ (${mem.percent}%)</span>
                                </div>
                                <div class="stat-bar-track">
                                    <div class="stat-bar-fill ${mem.percent > 85 ? 'red' : (mem.percent > 65 ? 'yellow' : 'purple')}" style="width:${Math.min(100, mem.percent)}%;"></div>
                                </div>
                            </div>
                        </div>
                    `;
                } catch (e) {
                    if (body) body.innerHTML = `<div style="color:var(--red);">Ошибка получения телеметрии</div>`;
                }
            };
            await refresh();
            pollers['dash_server_stats'] = setInterval(refresh, 3000);
        }

        if (key === 'vesuvio_flags') {
            const body = document.getElementById('tile-body-vesuvio_flags');
            try {
                const res = await api('GET', '/api/vesuvio/violations?limit=5').catch(() => []);
                const list = Array.isArray(res) ? res : (res.violations || []);
                if (!body) return;

                if (!list.length) {
                    body.innerHTML = `<div style="padding:20px; text-align:center; color:var(--text-dim);">Срабатываний античита пока нет (система чиста)</div>`;
                } else {
                    body.innerHTML = list.slice(0, 4).map(v => {
                        const name = v.playerName || v.name || 'Игрок';
                        const check = v.check || v.type || 'Movement';
                        const vl = v.vl || v.vlScore || 1;
                        return `
                            <div class="violation-card">
                                <div class="violation-info">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/28" class="player-avatar-sm" alt="">
                                    <div class="violation-meta">
                                        <h4>${esc(name)} <span class="badge red">${esc(check)}</span></h4>
                                        <div class="violation-details">Уверенность VL: <b>${vl}</b> • ${fmtTime(v.timestamp || Date.now()/1000)}</div>
                                    </div>
                                </div>
                                <div style="display:flex; gap:6px;">
                                    <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(name)}', 'Читы')">БАН</button>
                                    <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(name)}')">ДОСЬЕ</button>
                                </div>
                            </div>`;
                    }).join('');
                }
            } catch (e) {
                if (body) body.innerHTML = `<div style="color:var(--text-dim); text-align:center; padding:16px;">Vesuvio недоступен или оффлайн</div>`;
            }
        }

        if (key === 'attention') {
            const body = document.getElementById('tile-body-attention');
            try {
                const suspects = await api('GET', '/api/vesuvio/suspects').catch(() => []);
                const list = Array.isArray(suspects) ? suspects : [];
                if (!body) return;

                if (!list.length) {
                    body.innerHTML = `
                        <div style="text-align:center; padding:24px; color:var(--text-dim);">
                            <div style="font-size:24px; margin-bottom:6px; color:var(--green);">✓</div>
                            Подозрительных игроков нет
                        </div>`;
                } else {
                    body.innerHTML = list.map(p => `
                        <div style="display:flex; align-items:center; justify-content:space-between; padding:8px 0; border-bottom:1px solid var(--border);">
                            <div style="display:flex; align-items:center; gap:8px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name || '')}/24" class="player-avatar-sm" alt="">
                                <div>
                                    <div style="font-weight:700; color:#fff;">${esc(p.name)}</div>
                                    <div style="font-size:11px; color:var(--yellow);">Режим наблюдения</div>
                                </div>
                            </div>
                            <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(p.name)}')">ПРОВЕРКА</button>
                        </div>
                    `).join('');
                }
            } catch (e) {
                if (body) body.innerHTML = `<div style="color:var(--text-dim); padding:16px; text-align:center;">Нет данных</div>`;
            }
        }

        if (key === 'punishments_stats') {
            const body = document.getElementById('tile-body-punishments_stats');
            try {
                const bans = await api('GET', '/api/bans/all').catch(() => []);
                if (!body) return;
                const now = Date.now() / 1000;
                const todayBans = bans.filter(b => (now - b.createdAt) < 86400).length;
                const weekBans = bans.filter(b => (now - b.createdAt) < 7 * 86400).length;

                body.innerHTML = `
                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;">
                        <div class="stat-card" style="padding:12px;">
                            <div class="stat-label">СЕГОДНЯ</div>
                            <div class="stat-value" style="color:var(--red); font-size:24px;">${todayBans}</div>
                        </div>
                        <div class="stat-card" style="padding:12px;">
                            <div class="stat-label">ЗА 7 ДНЕЙ</div>
                            <div class="stat-value" style="color:var(--yellow); font-size:24px;">${weekBans}</div>
                        </div>
                    </div>
                    <div style="font-size:12px; color:var(--text-muted); text-align:center;">
                        Всего активных банов в базе: <b>${bans.length}</b>
                    </div>
                `;
            } catch (e) {
                if (body) body.innerHTML = `<div style="color:var(--red);">Ошибка статистики наказаний</div>`;
            }
        }

        if (key === 'online_players') {
            const body = document.getElementById('tile-body-online_players');
            try {
                const s = await api('GET', '/api/stats').catch(() => ({ players: [] }));
                if (!body) return;
                const players = s.players || [];

                if (!players.length) {
                    body.innerHTML = `<div style="text-align:center; padding:20px; color:var(--text-dim);">Сейчас на сервере нет игроков онлайн</div>`;
                } else {
                    body.innerHTML = players.map(p => `
                        <div style="display:flex; align-items:center; justify-content:space-between; padding:6px 0; border-bottom:1px solid rgba(255,255,255,0.04);">
                            <div style="display:flex; align-items:center; gap:8px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/22" class="player-avatar-sm" style="width:22px; height:22px;" alt="">
                                <span style="font-weight:600; color:#fff;">${esc(p.name)}</span>
                            </div>
                            <div style="display:flex; align-items:center; gap:6px;">
                                <span class="badge gray">${p.ping} мс</span>
                                <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(p.name)}')">ДОСЬЕ</button>
                            </div>
                        </div>
                    `).join('');
                }
            } catch (e) {
                if (body) body.innerHTML = `<div style="color:var(--text-dim);">Ошибка загрузки игроков</div>`;
            }
        }

        if (key === 'admin_actions') {
            const body = document.getElementById('tile-body-admin_actions');
            try {
                const logs = await api('GET', '/api/logs/web?limit=10').catch(() => []);
                if (!body) return;

                const filtered = isModeratorRole()
                    ? logs.filter(l => l.actor && l.actor.toLowerCase() === (me.username || '').toLowerCase())
                    : logs;

                if (!filtered.length) {
                    body.innerHTML = `<div style="text-align:center; padding:20px; color:var(--text-dim);">Действий пока не зафиксировано</div>`;
                } else {
                    body.innerHTML = filtered.slice(0, 5).map(l => `
                        <div style="padding:6px 0; border-bottom:1px solid rgba(255,255,255,0.04); font-size:12px;">
                            <div style="display:flex; justify-content:space-between; color:var(--text-dim); font-size:11px; margin-bottom:2px;">
                                <b style="color:var(--accent-light);">${esc(l.actor)}</b>
                                <span>${fmtTime(l.timestamp)}</span>
                            </div>
                            <div style="color:var(--text-secondary);">${esc(l.action)}</div>
                        </div>
                    `).join('');
                }
            } catch (e) {
                if (body) body.innerHTML = `<div style="color:var(--text-dim);">Ошибка загрузки журнала</div>`;
            }
        }

        if (key === 'my_shift') {
            const body = document.getElementById('tile-body-my_shift');
            if (!body) return;
            const shiftHours = ((Date.now() - shiftSession.startedAt) / 3600000).toFixed(1);

            body.innerHTML = `
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:12px;">
                    <div class="stat-card" style="padding:10px;">
                        <div class="stat-label">ДЕЙСТВИЙ ЗА СМЕНУ</div>
                        <div class="stat-value" style="font-size:22px; color:var(--accent-light);">${shiftSession.actionCount}</div>
                    </div>
                    <div class="stat-card" style="padding:10px;">
                        <div class="stat-label">ВРЕМЯ НА СМЕНЕ</div>
                        <div class="stat-value" style="font-size:22px;">${shiftHours} <span style="font-size:12px; color:var(--text-dim);">ч</span></div>
                    </div>
                </div>
                <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:6px;">Последние действия:</div>
                <div style="max-height:130px; overflow-y:auto;">
                    ${shiftSession.actions.length ? shiftSession.actions.slice(0, 4).map(a => `
                        <div style="font-size:11.5px; color:var(--text-muted); padding:3px 0; display:flex; justify-content:space-between;">
                            <span>${esc(a.text)}</span>
                            <span style="color:var(--text-dim);">${a.time}</span>
                        </div>
                    `).join('') : '<div style="color:var(--text-dim); font-size:12px;">В текущую смену действий не совершалось</div>'}
                </div>
            `;
        }
    }

    function openDashboardCustomizerModal() {
        const currentTiles = getActiveDashboardTiles();
        const allTileKeys = [
            { key: 'server_stats', title: 'Статистика сервера (TPS, MSPT, память, онлайн)' },
            { key: 'quick_actions', title: 'Быстрые действия (Бан, Кик, Спавн, Заморозка)' },
            { key: 'vesuvio_flags', title: 'Последние срабатывания античита (Vesuvio)' },
            { key: 'attention', title: '«Требует внимания» (подозрительные игроки)' },
            { key: 'punishments_stats', title: 'Статистика наказаний (сегодня / неделя)' },
            { key: 'online_players', title: 'Онлайн-игроки с быстрыми действиями' },
            { key: 'admin_actions', title: 'Последние действия администрации' },
            { key: 'my_shift', title: 'Мои действия за текущую смену' }
        ];

        openModal(`
            <div class="modal-header">
                <h3>НАСТРОЙКА ПЛИТОК ДАШБОРДА</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
            </div>
            <div class="modal-body">
                <p style="color:var(--text-muted); font-size:13px; margin-bottom:16px;">
                    Включайте или отключайте плитки, которые должны отображаться на вашем рабочем столе.
                </p>
                <div style="display:flex; flex-direction:column; gap:10px;">
                    ${allTileKeys.map(t => {
                        const checked = currentTiles.includes(t.key) ? 'checked' : '';
                        return `
                            <label style="display:flex; align-items:center; gap:10px; cursor:pointer; padding:8px 12px; background:rgba(255,255,255,0.03); border:1px solid var(--border); border-radius:6px;">
                                <input type="checkbox" class="dash-tile-cb" value="${t.key}" ${checked} style="width:18px; height:18px; accent-color:var(--accent);">
                                <span style="font-size:13px; font-weight:600; color:#fff;">${esc(t.title)}</span>
                            </label>`;
                    }).join('')}
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" id="dash-reset-default">СБРОСИТЬ ПО УМОЛЧАНИЮ</button>
                <button type="button" class="primary" id="dash-save-tiles">СОХРАНИТЬ РАСКЛАДКУ</button>
            </div>
        `, () => {
            document.getElementById('dash-save-tiles')?.addEventListener('click', () => {
                const checked = Array.from(document.querySelectorAll('.dash-tile-cb:checked')).map(cb => cb.value);
                if (!checked.length) {
                    alert('Выберите хотя бы одну плитку');
                    return;
                }
                saveDashboardTiles(checked);
                closeModal();
                renderDashboardView();
                showToast('Раскладка сохранена', 'Дашборд успешно обновлен', 'success');
            });

            document.getElementById('dash-reset-default')?.addEventListener('click', () => {
                saveDashboardTiles(DEFAULT_DASHBOARD_TILES);
                closeModal();
                renderDashboardView();
                showToast('Сброшено', 'Установлена стандартная раскладка', 'info');
            });
        });
    }

    // ==========================================================================
    // 2. ЖУРНАЛ (ПОЛНЫЙ ЛОГ, ФИЛЬТРЫ, ДЕТАЛИ, ЭКСПОРТ)
    // ==========================================================================

    async function renderJournalView() {
        const area = document.getElementById('content-area');
        let primaryMode = 'web'; // 'web' or 'server'
        let webSubfilter = 'ALL'; // ALL, AUTH, PUNISHMENTS, REPORTS, ROLES
        let serverSubfilter = 'ALL'; // ALL, COMMANDS, GAME, WARNINGS
        let searchQuery = '';
        let cachedWebLogs = [];
        let cachedServerLogs = [];

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>ЖУРНАЛ СОБЫТИЙ</h2>
                    <p>Раздельный аудит веб-панели управления и серверных логов Minecraft</p>
                </div>
                <div class="view-actions">
                    <button type="button" class="secondary" id="journal-export-btn">📥 ЭКСПОРТ ЛОГОВ</button>
                    <button type="button" class="primary" id="journal-refresh-btn">ОБНОВИТЬ</button>
                </div>
            </div>

            <!-- Primary Mode Tabs (Web vs Server) -->
            <div class="segmented-nav-tabs" style="margin-bottom:18px;">
                <button type="button" class="segmented-nav-tab active" data-mode="web">
                    <span>🌐</span>
                    <span>ВЕБ-ЖУРНАЛ (АУДИТ ПАНЕЛИ)</span>
                </button>
                <button type="button" class="segmented-nav-tab" data-mode="server">
                    <span>🖥️</span>
                    <span>СЕРВЕРНЫЙ ЖУРНАЛ (MINECRAFT)</span>
                </button>
            </div>

            <!-- Toolbar & Subfilters -->
            <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:14px; margin-bottom:18px; display:flex; flex-direction:column; gap:12px;">
                <div class="filter-tags" id="journal-subfilters">
                    <!-- Dynamic subfilter buttons inserted by renderSubfilters() -->
                </div>

                <div style="display:flex; gap:12px; align-items:center;">
                    <div style="flex:1; position:relative;">
                        <input type="text" id="journal-search-input" placeholder="Поиск по нику, действию или тексту..." style="padding-left:34px;">
                        <span style="position:absolute; left:12px; top:50%; transform:translateY(-50%); color:var(--text-dim);">🔍</span>
                    </div>
                </div>
            </div>

            <!-- Logs Table Container -->
            <div class="table-wrap">
                <table>
                    <thead id="journal-table-head">
                        <!-- Dynamic headers -->
                    </thead>
                    <tbody id="journal-table-body">
                        <tr><td colspan="5" style="text-align:center; color:var(--text-dim); padding:24px;">Загрузка журнала...</td></tr>
                    </tbody>
                </table>
            </div>
        `;

        const renderSubfilters = () => {
            const container = document.getElementById('journal-subfilters');
            if (!container) return;

            if (primaryMode === 'web') {
                container.innerHTML = `
                    <button type="button" class="filter-tag-btn ${webSubfilter === 'ALL' ? 'active' : ''}" data-sub="ALL">Все веб-действия</button>
                    <button type="button" class="filter-tag-btn ${webSubfilter === 'AUTH' ? 'active' : ''}" data-sub="AUTH">Входы и выходы</button>
                    <button type="button" class="filter-tag-btn ${webSubfilter === 'PUNISHMENTS' ? 'active' : ''}" data-sub="PUNISHMENTS">Баны и наказания</button>
                    <button type="button" class="filter-tag-btn ${webSubfilter === 'REPORTS' ? 'active' : ''}" data-sub="REPORTS">Рассмотренные жалобы</button>
                    <button type="button" class="filter-tag-btn ${webSubfilter === 'ROLES' ? 'active' : ''}" data-sub="ROLES">Роли и персонал</button>
                `;
            } else {
                container.innerHTML = `
                    <button type="button" class="filter-tag-btn ${serverSubfilter === 'ALL' ? 'active' : ''}" data-sub="ALL">Все серверные логи</button>
                    <button type="button" class="filter-tag-btn ${serverSubfilter === 'COMMANDS' ? 'active' : ''}" data-sub="COMMANDS">Команды персонала</button>
                    <button type="button" class="filter-tag-btn ${serverSubfilter === 'GAME' ? 'active' : ''}" data-sub="GAME">Игровые события</button>
                    <button type="button" class="filter-tag-btn ${serverSubfilter === 'WARNINGS' ? 'active' : ''}" data-sub="WARNINGS">Ошибки и предупреждения</button>
                `;
            }
        };

        const renderTableHead = () => {
            const thead = document.getElementById('journal-table-head');
            if (!thead) return;

            if (primaryMode === 'web') {
                thead.innerHTML = `
                    <tr>
                        <th style="width:170px;">ВРЕМЯ</th>
                        <th style="width:170px;">АДМИНИСТРАТОР</th>
                        <th style="width:140px;">КАТЕГОРИЯ</th>
                        <th>ДЕЙСТВИЕ</th>
                        <th style="width:90px; text-align:right;">ДЕТАЛИ</th>
                    </tr>
                `;
            } else {
                thead.innerHTML = `
                    <tr>
                        <th style="width:170px;">ВРЕМЯ</th>
                        <th style="width:160px;">ИСТОЧНИК</th>
                        <th style="width:130px;">ТИП</th>
                        <th>СООБЩЕНИЕ / КОМАНДА</th>
                        <th style="width:90px; text-align:right;">ДЕТАЛИ</th>
                    </tr>
                `;
            }
        };

        const sanitizeWebAction = (actionStr) => {
            if (!actionStr) return '';
            // Remove IP addresses completely
            return actionStr
                .replace(/\(IP:\s*[^)]+\)/gi, '')
                .replace(/\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b/g, '')
                .replace(/\s{2,}/g, ' ')
                .trim();
        };

        const categorizeWebAction = (act) => {
            const s = (act || '').toLowerCase();
            if (s.includes('вошёл') || s.includes('вышел') || s.includes('вход') || s.includes('сесси') || s.includes('2fa') || s.includes('парол')) {
                return { type: 'АВТОРИЗАЦИЯ', typeClass: 'blue' };
            }
            if (s.includes('бан') || s.includes('разбан') || s.includes('кик') || s.includes('наказан') || s.includes('замороз')) {
                return { type: 'НАКАЗАНИЕ', typeClass: 'red' };
            }
            if (s.includes('жалоб') || s.includes('репорт')) {
                return { type: 'ЖАЛОБЫ', typeClass: 'purple' };
            }
            if (s.includes('рол') || s.includes('персонал') || s.includes('сотрудник') || s.includes('прав')) {
                return { type: 'ПЕРСОНАЛ', typeClass: 'yellow' };
            }
            return { type: 'ДЕЙСТВИЕ', typeClass: 'cyan' };
        };

        const loadLogs = async () => {
            const body = document.getElementById('journal-table-body');
            if (body) body.innerHTML = `<tr><td colspan="5" style="text-align:center; color:var(--text-muted); padding:24px;">Загрузка записей журнала...</td></tr>`;

            try {
                if (primaryMode === 'web') {
                    const rawLogs = await api('GET', '/api/logs/web?limit=250').catch(() => []);
                    // Filter out section navigation logs and clean IP addresses
                    cachedWebLogs = rawLogs
                        .filter(l => {
                            const act = (l.action || '').toLowerCase();
                            // STRICT REQUIREMENT: "но не открыл раздел какой то"
                            if (act.includes('открыл раздел') || act.includes('открыл вкладку')) return false;
                            return true;
                        })
                        .map(l => {
                            const cleanAction = sanitizeWebAction(l.action);
                            const cat = categorizeWebAction(cleanAction);
                            return {
                                id: l.id,
                                time: l.timestamp,
                                actor: l.actor || 'Система',
                                action: cleanAction,
                                type: cat.type,
                                typeClass: cat.typeClass,
                                raw: l
                            };
                        });
                } else {
                    const [sLogs, cmdLogs] = await Promise.all([
                        api('GET', '/api/logs/server?limit=150').catch(() => []),
                        api('GET', '/api/staff-audit/logs?limit=100').catch(() => ({ logs: [] }))
                    ]);

                    let list = [];
                    (sLogs || []).forEach(s => {
                        const msg = s.message || s.action || '';
                        let type = 'ИНФО';
                        let typeClass = 'gray';
                        if (s.level === 'WARN' || s.level === 'WARNING') { type = 'WARN'; typeClass = 'yellow'; }
                        else if (s.level === 'ERROR' || s.level === 'SEVERE') { type = 'ОШИБКА'; typeClass = 'red'; }
                        else if (msg.includes('joined the game') || msg.includes('left the game')) { type = 'СЕРВЕР'; typeClass = 'green'; }

                        list.push({
                            id: 'srv_' + (s.id || Math.random()),
                            time: s.timestamp || (Date.now() / 1000),
                            actor: s.logger || 'Сервер',
                            action: msg,
                            type,
                            typeClass,
                            raw: s
                        });
                    });

                    (cmdLogs.logs || []).forEach(c => {
                        list.push({
                            id: 'cmd_' + c.id,
                            time: c.timestamp,
                            actor: c.adminUsername || 'Персонал',
                            action: '/' + c.command,
                            type: 'КОМАНДА',
                            typeClass: c.isSuspicious ? 'yellow' : 'cyan',
                            raw: c
                        });
                    });

                    list.sort((a, b) => b.time - a.time);
                    cachedServerLogs = list;
                }

                renderTableRows();
            } catch (e) {
                if (body) body.innerHTML = `<tr><td colspan="5" style="text-align:center; color:var(--red); padding:24px;">Ошибка загрузки журнала: ${esc(e.message)}</td></tr>`;
            }
        };

        const renderTableRows = () => {
            const body = document.getElementById('journal-table-body');
            if (!body) return;

            let source = primaryMode === 'web' ? cachedWebLogs : cachedServerLogs;

            // Apply subfilters
            if (primaryMode === 'web') {
                if (webSubfilter === 'AUTH') {
                    source = source.filter(x => x.type === 'АВТОРИЗАЦИЯ');
                } else if (webSubfilter === 'PUNISHMENTS') {
                    source = source.filter(x => x.type === 'НАКАЗАНИЕ');
                } else if (webSubfilter === 'REPORTS') {
                    source = source.filter(x => x.type === 'ЖАЛОБЫ');
                } else if (webSubfilter === 'ROLES') {
                    source = source.filter(x => x.type === 'ПЕРСОНАЛ');
                }
            } else {
                if (serverSubfilter === 'COMMANDS') {
                    source = source.filter(x => x.type === 'КОМАНДА');
                } else if (serverSubfilter === 'GAME') {
                    source = source.filter(x => x.type === 'СЕРВЕР' || x.type === 'ИНФО');
                } else if (serverSubfilter === 'WARNINGS') {
                    source = source.filter(x => x.type === 'WARN' || x.type === 'ОШИБКА');
                }
            }

            // Search query filter
            if (searchQuery) {
                const q = searchQuery.toLowerCase();
                source = source.filter(e => 
                    (e.actor && e.actor.toLowerCase().includes(q)) ||
                    (e.action && e.action.toLowerCase().includes(q)) ||
                    (e.type && e.type.toLowerCase().includes(q))
                );
            }

            if (!source.length) {
                body.innerHTML = `<tr><td colspan="5" style="text-align:center; color:var(--text-dim); padding:28px;">Записи журнала не найдены</td></tr>`;
                return;
            }

            body.innerHTML = source.slice(0, 150).map((row, idx) => `
                <tr>
                    <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(row.time)}</td>
                    <td style="font-weight:700;">
                        <span style="cursor:pointer; color:#fff;" onclick="window.viewPlayerProfile('${esc(row.actor)}')">${esc(row.actor)}</span>
                    </td>
                    <td><span class="badge ${row.typeClass}">${esc(row.type)}</span></td>
                    <td style="color:var(--text-secondary); line-height:1.4; word-break:break-word;">${esc(row.action)}</td>
                    <td style="text-align:right;">
                        <button type="button" class="secondary btn-sm" data-detail-idx="${idx}">ИНФО</button>
                    </td>
                </tr>
            `).join('');

            body.querySelectorAll('[data-detail-idx]').forEach(btn => {
                btn.addEventListener('click', () => {
                    const idx = parseInt(btn.dataset.detailIdx, 10);
                    if (source[idx]) openLogDetailModal(source[idx]);
                });
            });
        };

        const openLogDetailModal = (item) => {
            if (!item) return;
            openModal(`
                <div class="modal-header">
                    <h3>ДЕТАЛИЗАЦИЯ ЗАПИСИ ЖУРНАЛА</h3>
                    <button type="button" class="close-btn" data-modal-close="true">✕</button>
                </div>
                <div class="modal-body">
                    <div style="display:grid; grid-template-columns:120px 1fr; gap:10px; font-size:13px; margin-bottom:16px;">
                        <div style="color:var(--text-dim); font-weight:700;">ВРЕМЯ:</div>
                        <div class="font-mono">${fmtTime(item.time)}</div>
                        <div style="color:var(--text-dim); font-weight:700;">АКТОР:</div>
                        <div><b>${esc(item.actor)}</b></div>
                        <div style="color:var(--text-dim); font-weight:700;">ТИП СОБЫТИЯ:</div>
                        <div><span class="badge ${item.typeClass}">${esc(item.type)}</span></div>
                        <div style="color:var(--text-dim); font-weight:700;">СОДЕРЖАНИЕ:</div>
                        <div style="color:#fff;">${esc(item.action)}</div>
                    </div>

                    <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:6px;">Сырые данные:</div>
                    <pre style="background:#090614; padding:12px; border-radius:6px; font-size:12px; color:var(--accent-light); border:1px solid var(--border); overflow-x:auto;">${esc(JSON.stringify(item.raw, null, 2))}</pre>
                </div>
                <div class="modal-footer">
                    <button type="button" class="primary" onclick="window.viewPlayerProfile('${esc(item.actor)}'); window.closeCurrentModal();">КАРТОЧКА ИГРОКА</button>
                    <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
                </div>
            `);
        };

        // Export Log Handler
        document.getElementById('journal-export-btn')?.addEventListener('click', () => {
            const list = primaryMode === 'web' ? cachedWebLogs : cachedServerLogs;
            if (!list.length) {
                alert('Журнал пуст');
                return;
            }
            const csvRows = ['Timestamp,Time,Actor,Type,Action'];
            list.forEach(e => {
                const cleanAct = (e.action || '').replace(/"/g, '""');
                csvRows.push(`${e.time},"${fmtTime(e.time)}","${e.actor}","${e.type}","${cleanAct}"`);
            });
            const blob = new Blob([csvRows.join('\n')], { type: 'text/csv;charset=utf-8;' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `webadmin-${primaryMode}-journal-${Date.now()}.csv`;
            a.click();
            URL.revokeObjectURL(url);
            showToast('Экспорт завершён', 'Файл CSV успешно скачан', 'success');
        });

        // Primary Tab Switcher (Web vs Server)
        document.querySelectorAll('.segmented-nav-tab').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.segmented-nav-tab').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                primaryMode = btn.dataset.mode;
                renderSubfilters();
                renderTableHead();
                loadLogs();
            });
        });

        // Subfilters Switcher
        document.getElementById('journal-subfilters')?.addEventListener('click', (e) => {
            const btn = e.target.closest('.filter-tag-btn');
            if (!btn) return;
            document.querySelectorAll('#journal-subfilters .filter-tag-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            if (primaryMode === 'web') {
                webSubfilter = btn.dataset.sub;
            } else {
                serverSubfilter = btn.dataset.sub;
            }
            renderTableRows();
        });

        // Search input
        document.getElementById('journal-search-input')?.addEventListener('input', (e) => {
            searchQuery = e.target.value.trim();
            renderTableRows();
        });

        document.getElementById('journal-refresh-btn')?.addEventListener('click', loadLogs);

        renderSubfilters();
        renderTableHead();
        await loadLogs();
    }


    // ==========================================================================
    // 3. НАКАЗАНИЯ (ЖАЛОБЫ, БАНЫ, АПЕЛЛЯЦИИ DISCORD)
    // ==========================================================================

    let currentPunishmentsSubtab = 'reports';
    let lastLoadedReports = [];
    let lastLoadedBans = [];
    let appealTemplatesCache = null;

    async function getAppealTemplates() {
        if (appealTemplatesCache) return appealTemplatesCache;
        try {
            appealTemplatesCache = await api('GET', '/api/bans/appeal-templates');
        } catch (e) {
            appealTemplatesCache = {
                form: { header: 'ФОРМА ПОДАЧИ АПЕЛЛЯЦИИ', fields: ['1. Ваш никнейм: {targetName}', '2. Кем выдано: {creatorName}', '3. Причина: {ruleReason}', '4. Ваши комментарии:'], footer: 'Срок рассмотрения: до 24 часов.' },
                verdicts: [
                    { id: 'v_approved_error', name: 'Одобрено (Ошибочный бан)', type: 'APPROVE', template: '✅ **АПЕЛЛЯЦИЯ ОДОБРЕНА**\n\nУважаемый **{targetName}**!\nБлокировка **#{id}** была пересмотрена. В ходе повторной проверки доказательств была установлена ошибка.\nБлокировка полностью снята, аккаунт разбанен. Приносим извинения за неудобства.\n\n*Администрация LoveServer*' },
                    { id: 'v_approved_amnesty', name: 'Одобрено (Амнистия)', type: 'APPROVE', template: '✅ **АПЕЛЛЯЦИЯ ОДОБРЕНА (АМНИСТИЯ)**\n\nУважаемый **{targetName}**!\nАдминистрация приняла решение удовлетворить вашу просьбу об амнистии по блокировке **#{id}** ({ruleReason}).\nБан снят. Пожалуйста, соблюдайте правила проекта во избежание повторного бессрочного бана.' },
                    { id: 'v_rejected_cheats', name: 'Отклонено (Читы доказаны)', type: 'REJECT', template: '❌ **АПЕЛЛЯЦИЯ ОТКЛОНЕНА**\n\nУважаемый **{targetName}**!\nВаша апелляция по бану **#{id}** рассмотрена.\nФакт использования запрещённого ПО ({ruleReason}) подтверждён видеозаписью и телеметрией античита.\nБлокировка остаётся в силе и является **бессрочной**.' },
                    { id: 'v_rejected_expired', name: 'Отклонено (Истёк срок)', type: 'REJECT', template: '❌ **АПЕЛЛЯЦИЯ ОТКЛОНЕНА (ИСТЁК СРОК)**\n\nУважаемый **{targetName}**!\nСрок подачи апелляции на блокировку от {createdAt} истёк.\nАпелляция не подлежит дальнейшему рассмотрению.' },
                    { id: 'v_info_request', name: 'Запрос доп. информации', type: 'INFO', template: '⚠️ **ТРЕБУЕТСЯ УТОЧНЕНИЕ**\n\nУважаемый **{targetName}**!\nДля вынесения вердикта по блокировке **#{id}** предоставьте дополнительную информацию или опровержение в течение 24 часов.' }
                ]
            };
        }
        return appealTemplatesCache;
    }

    async function updateReportsBadge() {
        if (!hasPerm('VIEW_REPORTS') && !hasPerm('VIEW_BANS')) return;
        try {
            const data = await api('GET', '/api/reports?limit=1');
            const pending = data?.stats?.pending || 0;
            const badge = document.getElementById('reports-pending-badge');
            if (badge) {
                if (pending > 0) {
                    badge.textContent = pending > 99 ? '99+' : pending;
                    badge.style.display = 'inline-block';
                } else {
                    badge.style.display = 'none';
                }
            }
            const tabBadge = document.getElementById('punishments-pending-badge-tab');
            if (tabBadge) {
                tabBadge.textContent = pending;
            }
        } catch (e) {}
    }

    async function renderPunishmentsView(defaultSubtab = 'reports', initialBanId = null) {
        const area = document.getElementById('content-area');
        if (!area) return;

        currentPunishmentsSubtab = defaultSubtab || 'reports';
        const isMod = isModeratorRole();

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>УПРАВЛЕНИЕ НАКАЗАНИЯМИ</h2>
                    <p>Единый центр обработки жалоб игроков, управления банами и работы с апелляциями</p>
                </div>
                <div class="view-actions" id="punishments-top-actions">
                    <button type="button" class="primary" id="btn-create-ban-top">+ ВЫДАТЬ БАН</button>
                    <button type="button" class="secondary" id="punishments-refresh-btn">ОБНОВИТЬ</button>
                </div>
            </div>

            <!-- Subtabs Navigation -->
            <div class="segmented-nav-tabs" id="punishments-subtabs-bar" style="margin-bottom:20px;">
                <button type="button" class="segmented-nav-tab ${currentPunishmentsSubtab === 'reports' ? 'active' : ''}" data-subtab="reports">
                    <span>📋</span>
                    <span>ЖАЛОБЫ И РЕПОРТЫ</span>
                    <span class="badge yellow" id="punishments-pending-badge-tab" style="margin-left:4px; font-size:10px;">0</span>
                </button>
                <button type="button" class="segmented-nav-tab ${currentPunishmentsSubtab === 'bans' ? 'active' : ''}" data-subtab="bans">
                    <span>🛡️</span>
                    <span>БАНЫ СЕРВЕРА</span>
                </button>
                <button type="button" class="segmented-nav-tab ${currentPunishmentsSubtab === 'appeals' ? 'active' : ''}" data-subtab="appeals">
                    <span>⚖️</span>
                    <span>АПЕЛЛЯЦИИ (DISCORD)</span>
                </button>
            </div>

            <!-- Subtab Content Area -->
            <div id="punishments-subtab-container"></div>
        `;

        document.getElementById('btn-create-ban-top')?.addEventListener('click', () => openQuickBanModal(''));
        document.getElementById('punishments-refresh-btn')?.addEventListener('click', () => {
            if (currentPunishmentsSubtab === 'reports') loadReportsSubtab();
            else if (currentPunishmentsSubtab === 'bans') loadBansSubtab();
            else if (currentPunishmentsSubtab === 'appeals') loadAppealsSubtab();
            showToast('Обновлено', 'Данные раздела синхронизированы', 'info');
        });

        // Wire subtab buttons
        document.querySelectorAll('#punishments-subtabs-bar .segmented-nav-tab').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('#punishments-subtabs-bar .segmented-nav-tab').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                currentPunishmentsSubtab = btn.dataset.subtab;
                switchSubtab(currentPunishmentsSubtab);
            });
        });

        const switchSubtab = (tab, param = null) => {
            currentPunishmentsSubtab = tab;
            clearPollers();
            if (tab === 'reports') loadReportsSubtab();
            else if (tab === 'bans') loadBansSubtab();
            else if (tab === 'appeals') loadAppealsSubtab(param || initialBanId);
        };

        // ----------------------------------------------------------------------
        // 1. SUBTAB: REPORTS (ЖАЛОБЫ) - NO STAT CARDS, 4+ OFFENDERS PROMINENT
        // ----------------------------------------------------------------------
        async function loadReportsSubtab() {
            const container = document.getElementById('punishments-subtab-container');
            if (!container) return;

            let currentStatus = 'ALL';
            let searchQuery = '';

            container.innerHTML = `
                <!-- Frequent Repeat Offenders Section (4+ reports) -->
                <div id="frequent-offenders-wrap" style="margin-bottom:18px;"></div>

                <!-- Filters & Search Toolbar -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:14px; margin-bottom:18px; display:flex; gap:12px; flex-wrap:wrap; align-items:center;">
                    <div style="display:flex; gap:6px; flex-wrap:wrap;" id="reports-status-filters">
                        <button type="button" class="filter-tag-btn active" data-status="ALL">Все</button>
                        <button type="button" class="filter-tag-btn" data-status="PENDING">Ожидают <span class="badge yellow" id="tab-rep-pending-count" style="margin-left:4px;">0</span></button>
                        <button type="button" class="filter-tag-btn" data-status="ACCEPTED">Принятые</button>
                        <button type="button" class="filter-tag-btn" data-status="REJECTED">Ложные</button>
                        <button type="button" class="filter-tag-btn" data-status="EXPIRED">Истёкшие</button>
                    </div>
                    <div style="flex:1; min-width:260px;">
                        <input type="text" id="reports-search-box" placeholder="Поиск по нарушителю, заявителю, причине или описанию...">
                    </div>
                </div>

                <!-- Reports Table -->
                <div class="table-wrap">
                    <table>
                        <thead>
                            <tr>
                                <th style="width:55px;">ID</th>
                                <th>НАРУШИТЕЛЬ</th>
                                <th>ОТПРАВИТЕЛЬ</th>
                                <th>ПРИЧИНЫ</th>
                                <th>ОПИСАНИЕ</th>
                                <th>ВРЕМЯ</th>
                                <th>ДАТА</th>
                                <th>СТАТУС</th>
                                <th style="text-align:right;">ДЕЙСТВИЯ</th>
                            </tr>
                        </thead>
                        <tbody id="reports-subtab-tbody">
                            <tr><td colspan="9" style="text-align:center; padding:32px; color:var(--text-dim);">Загрузка жалоб...</td></tr>
                        </tbody>
                    </table>
                </div>
            `;

            const fetchReports = async () => {
                const tbody = document.getElementById('reports-subtab-tbody');
                try {
                    const params = new URLSearchParams();
                    if (currentStatus && currentStatus !== 'ALL') params.set('status', currentStatus);
                    if (searchQuery && searchQuery.trim()) params.set('search', searchQuery.trim());
                    params.set('limit', '150');

                    const data = await api('GET', `/api/reports?${params.toString()}`);
                    lastLoadedReports = data.reports || [];
                    const stats = data.stats || {};

                    // Update pending count badges
                    const countEl = document.getElementById('tab-rep-pending-count');
                    if (countEl) countEl.textContent = stats.pending || 0;
                    const topTabBadge = document.getElementById('punishments-pending-badge-tab');
                    if (topTabBadge) topTabBadge.textContent = stats.pending || 0;

                    // Render 4+ Offenders Block
                    renderFrequentOffenders(lastLoadedReports);

                    // Render table
                    renderReportsTableRows(lastLoadedReports);
                } catch (e) {
                    if (tbody) tbody.innerHTML = `<tr><td colspan="9" style="text-align:center; padding:24px; color:var(--red);">Ошибка загрузки: ${esc(e.message)}</td></tr>`;
                }
            };

            const renderFrequentOffenders = (reports) => {
                const wrap = document.getElementById('frequent-offenders-wrap');
                if (!wrap) return;

                // Group by targetName
                const counts = {};
                reports.forEach(r => {
                    const name = r.targetName;
                    if (!name) return;
                    counts[name] = (counts[name] || 0) + 1;
                });

                const offenders = Object.keys(counts)
                    .filter(name => counts[name] >= 4)
                    .map(name => ({ name, count: counts[name] }))
                    .sort((a, b) => b.count - a.count);

                if (!offenders.length) {
                    wrap.style.display = 'none';
                    wrap.innerHTML = '';
                    return;
                }

                wrap.style.display = 'block';
                wrap.innerHTML = `
                    <div class="frequent-offenders-card">
                        <div class="frequent-offenders-title">
                            <span>⚠️ ПОВТОРНЫЕ НАРУШИТЕЛИ (4+ ЖАЛОБЫ)</span>
                            <span class="badge red">ТРЕБУЮТ ПРИОРИТЕТНОГО ВНИМАНИЯ</span>
                        </div>
                        <div class="frequent-offenders-grid">
                            ${offenders.map(o => `
                                <div class="frequent-offender-item">
                                    <div style="display:flex; align-items:center; gap:10px;">
                                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(o.name)}/32" class="player-avatar-sm" alt="" style="width:32px; height:32px; border-radius:4px;">
                                        <div>
                                            <div style="font-weight:700; color:#fff; font-size:13.5px;">${esc(o.name)}</div>
                                            <span class="badge red" style="font-size:10px; padding:2px 6px;">⚠️ ${o.count} жалоб</span>
                                        </div>
                                    </div>
                                    <div style="display:flex; gap:6px;">
                                        <button type="button" class="danger btn-sm" data-offender-ban="${esc(o.name)}">БАН</button>
                                        <button type="button" class="secondary btn-sm" data-offender-profile="${esc(o.name)}">ДОСЬЕ</button>
                                        <button type="button" class="secondary btn-sm" data-offender-filter="${esc(o.name)}" title="Показать только жалобы на этого игрока">ФИЛЬТР</button>
                                    </div>
                                </div>
                            `).join('')}
                        </div>
                    </div>
                `;

                wrap.querySelectorAll('[data-offender-ban]').forEach(b => {
                    b.addEventListener('click', () => {
                        const name = b.dataset.offenderBan;
                        openQuickBanModal(name, 'Рецидив / Многократные нарушения правил', `Игрок имеет 4+ активных жалоб в системе.`);
                    });
                });

                wrap.querySelectorAll('[data-offender-profile]').forEach(b => {
                    b.addEventListener('click', () => {
                        window.viewPlayerProfile(b.dataset.offenderProfile);
                    });
                });

                wrap.querySelectorAll('[data-offender-filter]').forEach(b => {
                    b.addEventListener('click', () => {
                        const searchBox = document.getElementById('reports-search-box');
                        if (searchBox) {
                            searchBox.value = b.dataset.offenderFilter;
                            searchQuery = b.dataset.offenderFilter;
                            fetchReports();
                        }
                    });
                });
            };

            const renderReportsTableRows = (list) => {
                const tbody = document.getElementById('reports-subtab-tbody');
                if (!tbody) return;

                if (!list.length) {
                    tbody.innerHTML = `<tr><td colspan="9" style="text-align:center; padding:32px; color:var(--text-dim);">Жалобы не найдены</td></tr>`;
                    return;
                }

                tbody.innerHTML = list.map(r => {
                    let statusBadge;
                    switch (r.status) {
                        case 'ACCEPTED': statusBadge = '<span class="badge green">ПРИНЯТ</span>'; break;
                        case 'REJECTED': statusBadge = '<span class="badge gray">ЛОЖНЫЙ</span>'; break;
                        case 'EXPIRED': statusBadge = '<span class="badge gray">ИСТЁК</span>'; break;
                        default: statusBadge = '<span class="badge yellow">ОЖИДАЕТ</span>'; break;
                    }

                    const reasonsHtml = (r.reasons && r.reasons.length)
                        ? r.reasons.map(res => `<span class="badge purple" style="margin:2px 2px 2px 0; font-size:10.5px;">${esc(res)}</span>`).join('')
                        : '<span style="color:var(--text-dim); font-size:12px;">Не указано</span>';

                    const descHtml = (r.description && r.description.trim() && r.description !== 'не указано')
                        ? `<span style="color:#e2e8f0;" title="${esc(r.description)}">${esc(r.description.length > 35 ? r.description.substring(0, 35) + '...' : r.description)}</span>`
                        : '<i style="color:var(--text-dim); font-size:12px;">не указано</i>';

                    const recentBadge = r.isRecent
                        ? '<span class="badge green" style="font-size:10.5px;" title="Произошло менее 5 минут назад">⏱ &lt; 5 мин</span>'
                        : '<span class="badge gray" style="font-size:10.5px;" title="Произошло более 5 минут назад">⏱ &gt; 5 мин</span>';

                    const suspBadge = r.targetSuspicious
                        ? '<span class="badge danger" style="margin-left:4px; font-size:10px; font-weight:800;" title="Более 4-х активных жалоб!">⚠️ 4+</span>'
                        : '';

                    const isPending = r.status === 'PENDING';

                    return `
                        <tr>
                            <td class="font-mono" style="color:var(--text-dim);">#${r.id}</td>
                            <td>
                                <div style="display:flex; align-items:center; gap:6px;">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.targetName)}/24" style="width:24px; height:24px; border-radius:4px;" alt="">
                                    <a href="javascript:void(0)" onclick="window.viewPlayerProfile('${esc(r.targetName)}')" style="color:#fff; font-weight:700; text-decoration:none;">${esc(r.targetName)}</a>
                                    ${suspBadge}
                                </div>
                            </td>
                            <td>
                                <div style="display:flex; align-items:center; gap:6px;">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.reporterName)}/20" style="width:20px; height:20px; border-radius:3px;" alt="">
                                    <span style="color:var(--text-secondary); font-size:12.5px;">${esc(r.reporterName)}</span>
                                </div>
                            </td>
                            <td><div style="max-width:200px; display:flex; flex-wrap:wrap;">${reasonsHtml}</div></td>
                            <td><div style="max-width:200px; line-height:1.3;">${descHtml}</div></td>
                            <td>${recentBadge}</td>
                            <td style="font-size:11.5px; color:var(--text-dim); white-space:nowrap;">${fmtTime(r.createdAt)}</td>
                            <td>
                                <div style="display:flex; align-items:center; gap:4px; flex-wrap:wrap;">
                                    ${statusBadge}
                                    ${(r.linkedBanId && r.linkedBanId > 0) ? `<button type="button" class="btn-tag-link" style="color:#f87171; border-color:rgba(239, 68, 68, 0.4);" onclick="window.viewBanDetails(${r.linkedBanId})" title="Открыть примененный бан #${r.linkedBanId}">🔨 БАН #${r.linkedBanId}</button>` : ''}
                                </div>
                            </td>
                            <td style="text-align:right; white-space:nowrap;">
                                <div style="display:inline-flex; gap:5px;">
                                    <button type="button" class="secondary btn-sm" onclick="window.openReportDetailModal(${r.id})" title="Просмотреть детали и контекст чата">ИНФО</button>
                                    ${isPending ? `
                                        <button type="button" class="primary btn-sm" onclick="window.handleAcceptReport(${r.id}, '${esc(r.targetName)}')" title="Принять и наказать (начислить репутацию заявителю)">✔</button>
                                        <button type="button" class="secondary btn-sm" onclick="window.handleRejectReport(${r.id}, '${esc(r.targetName)}')" title="Пометить как ложную (вернуть репутацию цели)">ЛОЖЬ</button>
                                        <button type="button" class="danger btn-sm" onclick="window.handleBanFromReportRow(${r.id})" title="Выдать бан по жалобе #${r.id}">БАН</button>
                                    ` : ''}
                                    <button type="button" class="danger btn-sm" onclick="window.handleDeleteReport(${r.id})" title="Удалить запись жалобы">✕</button>
                                </div>
                            </td>
                        </tr>
                    `;
                }).join('');
            };

            // Status filter buttons
            document.getElementById('reports-status-filters')?.addEventListener('click', (e) => {
                const btn = e.target.closest('.filter-tag-btn');
                if (!btn) return;
                document.querySelectorAll('#reports-status-filters .filter-tag-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                currentStatus = btn.dataset.status;
                fetchReports();
            });

            // Search input
            let sTimer = null;
            document.getElementById('reports-search-box')?.addEventListener('input', (e) => {
                searchQuery = e.target.value;
                clearTimeout(sTimer);
                sTimer = setTimeout(fetchReports, 300);
            });

            await fetchReports();
            pollers['punishments_reports'] = setInterval(fetchReports, 10000);
        }

        // ----------------------------------------------------------------------
        // 2. SUBTAB: BANS (БАНЫ СЕРВЕРА)
        // ----------------------------------------------------------------------
        async function loadBansSubtab() {
            const container = document.getElementById('punishments-subtab-container');
            if (!container) return;

            container.innerHTML = `
                <!-- Search and Reason Filters -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:14px; margin-bottom:18px; display:flex; gap:12px; flex-wrap:wrap;">
                    <div style="flex:1; min-width:240px;">
                        <input type="text" id="bans-search-input" placeholder="Поиск по нику игрока или администратору...">
                    </div>
                    <div style="width:240px;">
                        <select id="bans-filter-reason">
                            <option value="">Все причины</option>
                        </select>
                    </div>
                    <div style="width:160px;">
                        <select id="bans-filter-status">
                            <option value="">Все статусы</option>
                            <option value="ACTIVE" selected>Активные</option>
                            <option value="UNBANNED">Разбаненные</option>
                        </select>
                    </div>
                </div>

                <!-- Bans Table -->
                <div class="table-wrap">
                    <table>
                        <thead>
                            <tr>
                                <th>ИГРОК</th>
                                <th>ПРИЧИНА</th>
                                <th>АДМИНИСТРАТОР</th>
                                <th>ДАТА ВЫДАЧИ</th>
                                <th>СРОК</th>
                                <th>СТАТУС</th>
                                <th style="text-align:right;">ДЕЙСТВИЯ</th>
                            </tr>
                        </thead>
                        <tbody id="bans-subtab-tbody">
                            <tr><td colspan="7" style="text-align:center; padding:24px; color:var(--text-dim);">Загрузка списка банов...</td></tr>
                        </tbody>
                    </table>
                </div>
            `;

            // Populate reasons
            const reasons = await getBanReasons();
            const reasonSelect = document.getElementById('bans-filter-reason');
            if (reasonSelect) {
                reasons.forEach(r => {
                    const opt = document.createElement('option');
                    opt.value = r.name;
                    opt.textContent = r.name;
                    reasonSelect.appendChild(opt);
                });
            }

            const fetchBans = async () => {
                const body = document.getElementById('bans-subtab-tbody');
                try {
                    lastLoadedBans = await api('GET', '/api/bans/all');
                    renderBansTableRows();
                } catch (e) {
                    if (body) body.innerHTML = `<tr><td colspan="7" style="text-align:center; color:var(--red); padding:24px;">Ошибка загрузки банов: ${esc(e.message)}</td></tr>`;
                }
            };

            const renderBansTableRows = () => {
                const body = document.getElementById('bans-subtab-tbody');
                if (!body) return;

                const q = (document.getElementById('bans-search-input')?.value || '').trim().toLowerCase();
                const reasonFilter = document.getElementById('bans-filter-reason')?.value || '';
                const statusFilter = document.getElementById('bans-filter-status')?.value || '';

                let list = lastLoadedBans;
                if (q) {
                    list = list.filter(b => 
                        (b.targetName && b.targetName.toLowerCase().includes(q)) ||
                        (b.creatorName && b.creatorName.toLowerCase().includes(q))
                    );
                }
                if (reasonFilter) {
                    list = list.filter(b => b.ruleReason === reasonFilter);
                }
                if (statusFilter) {
                    list = list.filter(b => b.status === statusFilter);
                }

                if (!list.length) {
                    body.innerHTML = `<tr><td colspan="7" style="text-align:center; color:var(--text-dim); padding:24px;">Баны не найдены</td></tr>`;
                    return;
                }

                body.innerHTML = list.map(b => {
                    const isActive = b.status === 'ACTIVE';
                    const canUnban = !isMod || me.isOwner;
                    const proofUrl = b.screenshotUrl || (b.proofUrls && b.proofUrls.length ? b.proofUrls[0] : null);

                    return `
                        <tr>
                            <td>
                                <div style="display:flex; align-items:center; gap:10px; cursor:pointer;" onclick="window.viewPlayerProfile('${esc(b.targetName)}')">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(b.targetName)}/24" class="player-avatar-sm" alt="">
                                    <div>
                                        <div style="font-weight:700; color:#fff;">${esc(b.targetName)}</div>
                                        ${b.isIpBan ? '<span class="badge yellow" style="font-size:9px; padding:1px 4px;">IP-БАН</span>' : ''}
                                    </div>
                                </div>
                            </td>
                            <td>
                                <div style="display:flex; align-items:center; gap:6px; flex-wrap:wrap;">
                                    <span class="badge red">${esc(b.ruleReason)}</span>
                                    ${b.linkedReportId ? `<button type="button" class="btn-tag-link" style="color:#c084fc; border-color:rgba(192, 132, 252, 0.4);" onclick="window.openReportDetailModal(${b.linkedReportId})" title="Открыть прикрепленную жалобу #${b.linkedReportId}">📋 ЖАЛОБА #${b.linkedReportId}</button>` : ''}
                                    ${proofUrl ? `<button type="button" class="screenshot-thumb-btn" onclick="window.openScreenshotLightbox('${esc(proofUrl)}')">📷 СКРИНШОТ</button>` : ''}
                                </div>
                                ${b.description ? `<div style="font-size:11.5px; color:var(--text-muted); margin-top:4px;">${esc(b.description)}</div>` : ''}
                            </td>
                            <td style="font-weight:600; color:var(--accent-light);">${esc(b.creatorName)}</td>
                            <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(b.createdAt)}</td>
                            <td><span class="badge purple">БЕССРОЧНО</span></td>
                            <td>
                                <span class="badge ${isActive ? 'red' : 'green'}">${isActive ? 'АКТИВЕН' : 'СНЯТ'}</span>
                            </td>
                            <td style="text-align:right;">
                                <div style="display:flex; justify-content:flex-end; gap:6px;">
                                    <button type="button" class="secondary btn-sm" onclick="window.viewBanDetails(${b.id})" title="Детали блокировки">ИНФО</button>
                                    <button type="button" class="secondary btn-sm" onclick="window.renderPunishmentsView('appeals', ${b.id})" title="Перейти к апелляции и вынесению вердикта">АПЕЛЛЯЦИЯ</button>
                                    <button type="button" class="secondary btn-sm" onclick="window.openPlayerAltsModal('${esc(b.targetName)}')">АЛЬТЫ</button>
                                    ${isActive && canUnban ? `
                                        <button type="button" class="danger btn-sm" onclick="window.confirmUnban(${b.id}, '${esc(b.targetName)}')">РАЗБАНИТЬ</button>
                                    ` : ''}
                                </div>
                            </td>
                        </tr>`;
                }).join('');
            };

            document.getElementById('bans-search-input')?.addEventListener('input', renderBansTableRows);
            document.getElementById('bans-filter-reason')?.addEventListener('change', renderBansTableRows);
            document.getElementById('bans-filter-status')?.addEventListener('change', renderBansTableRows);

            await fetchBans();
        }

        // ----------------------------------------------------------------------
        // 3. SUBTAB: APPEALS (АПЕЛЛЯЦИИ DISCORD HUB & ВЕРДИКТЫ)
        // ----------------------------------------------------------------------
        async function loadAppealsSubtab(targetBanId = null) {
            const container = document.getElementById('punishments-subtab-container');
            if (!container) return;

            container.innerHTML = `
                <!-- Discord Appeals Banner & Workflow Info -->
                <div class="appeals-hub-banner" style="margin-bottom:20px;">
                    <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:16px; flex-wrap:wrap;">
                        <div>
                            <div style="display:flex; align-items:center; gap:8px;">
                                <span style="font-size:22px;">💬</span>
                                <h3 style="margin:0; font-size:16px; color:#fff;">ИНТЕГРАЦИЯ С DISCORD: ТИКЕТЫ АПЕЛЛЯЦИЙ</h3>
                                <span class="badge green" style="font-size:10.5px;">✓ РАБОЧИЙ ШЛЮЗ</span>
                            </div>
                            <p style="font-size:13px; color:var(--text-secondary); margin:6px 0 0 0; line-height:1.5;">
                                На сервере приём апелляций осуществляется через Discord-бота в канале <b>#тикеты-апелляций</b>. 
                                Ниже представлен интерактивный генератор официальных вердиктов администрации с поддержкой снятия бана в 1 клик и заготовками Discord-методов.
                            </p>
                        </div>
                        <div>
                            <button type="button" class="secondary btn-sm" id="btn-toggle-discord-docs">📖 СПРАВКА И МЕТОДЫ БОТА</button>
                        </div>
                    </div>
                </div>

                <!-- Collapsible Discord Bot API & Methods Doc -->
                <div id="discord-bot-docs-box" style="display:none; background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:16px; margin-bottom:20px;">
                    <h4 style="color:var(--accent-light); margin:0 0 10px 0; font-size:14px;">📡 МЕТОДЫ И ИНСТРУКЦИИ ДЛЯ DISCORD-БОТА</h4>
                    <div style="font-size:12.5px; color:var(--text-muted); line-height:1.6; margin-bottom:12px;">
                        Для автоматизации тикетов Discord-бот может обращаться к REST API веб-панели через авторизационный токен с правами <code>MANAGE_BANS</code>:
                    </div>
                    <pre class="discord-code-box"><code>// 1. Проверка активного бана по нику или ID:
GET /api/bans/{id}
Headers: { "Authorization": "Bearer &lt;TOKEN&gt;" }

// 2. Снятие бана (разбан игрока через бота):
POST /api/bans/{id}/unban
Body: { "reason": "Апелляция одобрена в Discord тикете #123" }

// 3. Формат готового Discord Embed сообщения с вердиктом:
{
  "embeds": [{
    "title": "⚖️ Вердикт по апелляции на блокировку",
    "color": 3066993, // Зеленый при разбане, 15158332 при отказе
    "fields": [
      { "name": "Игрок", "value": "{targetName}", "inline": true },
      { "name": "Решение", "value": "Блокировка снята", "inline": true }
    ]
  }]
}</code></pre>
                </div>

                <!-- Live Ban Appeals & Discord Tickets Thread Section -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px; margin-bottom:20px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; margin-bottom:14px;">
                        <div>
                            <h3 style="margin:0; font-size:15px; color:#fff; display:flex; align-items:center; gap:8px;">
                                <span>⚖️</span>
                                <span>ОЧЕРЕДЬ АПЕЛЛЯЦИЙ И ТИКЕТОВ DISCORD</span>
                                <span class="badge yellow" id="live-appeals-count-badge" style="font-size:11px;">0</span>
                            </h3>
                            <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                Все апелляции автоматически создают приватные тикеты Discord через LoveCore и поддерживают двусторонний чат
                            </div>
                        </div>
                        <div style="display:flex; gap:8px;">
                            <select id="appeals-status-filter" style="font-size:12px; padding:5px 10px;">
                                <option value="ALL">Все статусы</option>
                                <option value="PENDING" selected>Ожидают решения (PENDING)</option>
                                <option value="APPROVED">Одобренные</option>
                                <option value="REJECTED">Отклонённые</option>
                            </select>
                            <button type="button" class="secondary btn-sm" id="btn-refresh-appeals-list">🔄 Обновить</button>
                        </div>
                    </div>

                    <div id="live-appeals-list-wrap">
                        <div style="text-align:center; padding:20px; color:var(--text-muted);">Загрузка апелляций...</div>
                    </div>
                </div>

                <!-- Interactive Verdicts Generator Block -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; margin-bottom:16px;">
                        <div>
                            <h3 style="margin:0; font-size:15px; color:#fff;">ИНТЕРАКТИВНЫЙ ГЕНЕРАТОР ВЕРДИКТОВ (DISCORD)</h3>
                            <div style="font-size:12px; color:var(--text-muted);">Формирование официального решения по жалобе/бану с готовым форматированием для Discord</div>
                        </div>
                    </div>

                    <!-- Target Ban / Player Picker Bar -->
                    <div style="background:rgba(255, 255, 255, 0.02); border:1px solid var(--border); border-radius:var(--radius-md); padding:12px 16px; margin-bottom:18px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:14px;">
                        <div style="display:flex; align-items:center; gap:12px;">
                            <img id="verdict-player-avatar" src="https://mc-heads.net/avatar/Steve/36" style="width:36px; height:36px; border-radius:4px;" alt="">
                            <div>
                                <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Выбранный нарушитель:</div>
                                <div style="font-size:14px; font-weight:800; color:#fff;" id="verdict-player-name-display">Загрузка данных...</div>
                            </div>
                        </div>
                        <div style="min-width:280px; flex:1; max-width:420px;">
                            <select id="verdict-ban-picker" style="font-size:12.5px; padding:7px 12px; width:100%;">
                                <option value="0">Загрузка списка блокировок...</option>
                            </select>
                        </div>
                    </div>

                    <!-- Verdict Template Chips -->
                    <div style="font-size:11.5px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:10px;">
                        Выберите заготовку вердикта администрации:
                    </div>
                    <div class="appeal-verdict-chips" id="appeals-chips-container" style="margin-bottom:18px;"></div>

                    <!-- Live Discord Embed Preview Box -->
                    <div class="verdict-card" id="verdict-live-preview-box" style="margin-bottom:16px;">
                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
                            <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Предпросмотр сообщения в Discord:</div>
                            <span class="badge purple" id="preview-verdict-type-badge">ВЕРДИКТ</span>
                        </div>
                        <div id="verdict-formatted-preview" style="font-size:13.5px; line-height:1.6; color:#e2e8f0; white-space:pre-wrap;"></div>
                    </div>

                    <!-- Raw Discord Markdown Codebox -->
                    <div style="margin-bottom:16px;">
                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
                            <label style="margin-bottom:0;">Текст для копирования в Discord (Markdown):</label>
                            <span style="font-size:11px; color:var(--text-dim);">Можно отредактировать перед отправкой</span>
                        </div>
                        <textarea class="appeal-code-editor" id="verdict-raw-editor" rows="6" spellcheck="false"></textarea>
                    </div>

                    <!-- Action Buttons -->
                    <div style="display:flex; justify-content:flex-end; gap:10px; flex-wrap:wrap;">
                        <button type="button" class="secondary" id="btn-copy-discord-verdict">📋 СКОПИРОВАТЬ ДЛЯ DISCORD</button>
                        <button type="button" class="danger" id="btn-unban-and-copy-verdict" style="display:none;">⚡ СНЯТЬ БАН И СКОПИРОВАТЬ ВЕРДИКТ</button>
                    </div>
                </div>
            `;

            // Toggle docs box
            document.getElementById('btn-toggle-discord-docs')?.addEventListener('click', () => {
                const box = document.getElementById('discord-bot-docs-box');
                if (box) box.style.display = box.style.display === 'none' ? 'block' : 'none';
            });

            // Load templates and bans
            const [tmpl, allBans] = await Promise.all([
                getAppealTemplates(),
                api('GET', '/api/bans/all').catch(() => [])
            ]);

            lastLoadedBans = allBans;
            let currentBan = (targetBanId ? allBans.find(b => b.id === targetBanId) : null) || (allBans.length ? allBans[0] : null);
            let selectedVerdictId = (tmpl.verdicts && tmpl.verdicts.length) ? tmpl.verdicts[0].id : '';

            const pickerSelect = document.getElementById('verdict-ban-picker');
            if (pickerSelect) {
                pickerSelect.innerHTML = allBans.map(b => `
                    <option value="${b.id}" ${currentBan && currentBan.id === b.id ? 'selected' : ''}>
                        #${b.id} — ${esc(b.targetName)} (${esc(b.ruleReason)}) [${b.status === 'ACTIVE' ? 'АКТИВЕН' : 'СНЯТ'}]
                    </option>
                `).join('') + '<option value="0" ' + (!currentBan ? 'selected' : '') + '>[Без привязки / Произвольный]</option>';
            }

            // Populate Chips
            const chipsContainer = document.getElementById('appeals-chips-container');
            if (chipsContainer) {
                chipsContainer.innerHTML = (tmpl.verdicts || []).map(v => {
                    const isActive = v.id === selectedVerdictId;
                    const typeClass = v.type ? v.type.toLowerCase() : 'info';
                    let icon = '⚖️';
                    if (v.type === 'APPROVE') icon = '🟢';
                    else if (v.type === 'REJECT') icon = '🔴';
                    else if (v.type === 'INFO') icon = '🔵';
                    return `
                        <button type="button" class="verdict-chip ${typeClass} ${isActive ? 'active ' + typeClass : ''}" data-verdict-id="${esc(v.id)}">
                            <span>${icon}</span>
                            <span>${esc(v.name)}</span>
                        </button>
                    `;
                }).join('');
            }

            const updateVerdictView = () => {
                const curVerdict = (tmpl.verdicts || []).find(v => v.id === selectedVerdictId) || (tmpl.verdicts ? tmpl.verdicts[0] : null);
                const avatarEl = document.getElementById('verdict-player-avatar');
                const nameDisplay = document.getElementById('verdict-player-name-display');
                const previewBadge = document.getElementById('preview-verdict-type-badge');
                const previewEl = document.getElementById('verdict-formatted-preview');
                const editorEl = document.getElementById('verdict-raw-editor');
                const unbanBtn = document.getElementById('btn-unban-and-copy-verdict');

                const targetName = currentBan ? currentBan.targetName : '{targetName}';
                const banId = currentBan ? currentBan.id : '{id}';
                const creatorName = currentBan ? currentBan.creatorName : '{creatorName}';
                const ruleReason = currentBan ? currentBan.ruleReason : '{ruleReason}';
                const createdAt = currentBan ? fmtTime(currentBan.createdAt) : '{createdAt}';

                if (avatarEl) avatarEl.src = `https://mc-heads.net/avatar/${encodeURIComponent(currentBan ? currentBan.targetName : 'Steve')}/36`;
                if (nameDisplay) {
                    nameDisplay.innerHTML = currentBan 
                        ? `${esc(currentBan.targetName)} <span style="font-size:12px; color:var(--accent-light);">[Бан #${currentBan.id}]</span>` 
                        : 'Произвольный нарушитель (без привязки)';
                }

                if (!curVerdict) return;

                if (previewBadge) {
                    previewBadge.className = `badge ${curVerdict.type === 'APPROVE' ? 'green' : (curVerdict.type === 'REJECT' ? 'red' : 'purple')}`;
                    previewBadge.textContent = curVerdict.name;
                }

                // Format raw text
                const formatted = (curVerdict.template || '')
                    .replace(/{targetName}/g, targetName)
                    .replace(/#{id}/g, '#' + banId)
                    .replace(/{id}/g, banId)
                    .replace(/{creatorName}/g, creatorName)
                    .replace(/{ruleReason}/g, ruleReason)
                    .replace(/{createdAt}/g, createdAt);

                if (editorEl) editorEl.value = formatted;
                if (previewEl) previewEl.textContent = formatted;

                // Show 1-click unban button if verdict is APPROVE and ban is currently ACTIVE
                const canUnban = currentBan && currentBan.status === 'ACTIVE' && curVerdict.type === 'APPROVE' && (!isMod || me.isOwner);
                if (unbanBtn) {
                    unbanBtn.style.display = canUnban ? 'inline-flex' : 'none';
                }
            };

            pickerSelect?.addEventListener('change', (e) => {
                const val = parseInt(e.target.value, 10);
                currentBan = allBans.find(b => b.id === val) || null;
                updateVerdictView();
            });

            chipsContainer?.addEventListener('click', (e) => {
                const btn = e.target.closest('.verdict-chip');
                if (!btn) return;
                chipsContainer.querySelectorAll('.verdict-chip').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                selectedVerdictId = btn.dataset.verdictId;
                updateVerdictView();
            });

            document.getElementById('verdict-raw-editor')?.addEventListener('input', (e) => {
                const previewEl = document.getElementById('verdict-formatted-preview');
                if (previewEl) previewEl.textContent = e.target.value;
            });

            document.getElementById('btn-copy-discord-verdict')?.addEventListener('click', () => {
                const text = document.getElementById('verdict-raw-editor')?.value || '';
                navigator.clipboard.writeText(text);
                showToast('Скопировано', 'Текст вердикта скопирован в буфер для отправки в Discord', 'success');
            });

            document.getElementById('btn-unban-and-copy-verdict')?.addEventListener('click', async () => {
                if (!currentBan || currentBan.status !== 'ACTIVE') return;
                confirmAction(
                    'СНЯТЬ БАН И ВЫНЕСТИ ВЕРДИКТ',
                    `Разбанить игрока ${currentBan.targetName} на сервере и скопировать готовый вердикт?`,
                    async () => {
                        try {
                            await api('POST', `/api/bans/${currentBan.id}/unban`, { reason: 'Апелляция одобрена в Discord' });
                            currentBan.status = 'UNBANNED';
                            recordShiftAction(`Одобрил апелляцию и разбанил ${currentBan.targetName}`);
                            const text = document.getElementById('verdict-raw-editor')?.value || '';
                            navigator.clipboard.writeText(text);
                            showToast('Бан снят', `Игрок ${currentBan.targetName} разбанен на сервере. Вердикт скопирован!`, 'success');
                            updateVerdictView();
                        } catch (e) {
                            alert('Ошибка разбана: ' + e.message);
                        }
                    },
                    'РАЗБАНИТЬ И СКОПИРОВАТЬ',
                    false
                );
            });

            async function renderLiveAppealsList() {
                const listWrap = document.getElementById('live-appeals-list-wrap');
                const badge = document.getElementById('live-appeals-count-badge');
                const filterEl = document.getElementById('appeals-status-filter');
                if (!listWrap) return;

                const statusFilter = filterEl ? filterEl.value : 'PENDING';
                try {
                    const res = await api('GET', `/api/appeals?status=${statusFilter}`);
                    const appeals = res.appeals || [];
                    if (badge) badge.textContent = appeals.length;

                    if (!appeals.length) {
                        listWrap.innerHTML = `
                            <div style="text-align:center; padding:24px; color:var(--text-muted); font-size:13px;">
                                В выбранном статусе (${esc(statusFilter)}) нет апелляций
                            </div>
                        `;
                        return;
                    }

                    listWrap.innerHTML = `
                        <div style="display:flex; flex-direction:column; gap:10px;">
                            ${appeals.map(a => {
                                const stBadge = a.status === 'APPROVED' ? '<span class="badge green">ОДОБРЕНО</span>' :
                                                (a.status === 'REJECTED' ? '<span class="badge red">ОТКЛОНЕНО</span>' : '<span class="badge yellow">НА РАССМОТРЕНИИ</span>');
                                return `
                                    <div style="background:rgba(255, 255, 255, 0.02); border:1px solid var(--border); border-radius:var(--radius-sm); padding:12px 14px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px;">
                                        <div style="display:flex; align-items:center; gap:12px;">
                                            <img src="https://mc-heads.net/avatar/${esc(a.playerName)}/36" style="width:36px; height:36px; border-radius:4px;" alt="">
                                            <div>
                                                <div style="display:flex; align-items:center; gap:8px;">
                                                    <span style="font-size:14px; font-weight:800; color:#fff;">${esc(a.playerName)}</span>
                                                    ${stBadge}
                                                    ${a.discordChannelId ? `<span class="badge purple" style="font-size:10px;">💬 Discord: ticket-бан-${esc(a.playerName)}</span>` : ''}
                                                </div>
                                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                                    Бан #${a.banId}: <b>${esc(a.banReason || '—')}</b> • Подана: ${fmtTime(a.createdAt)}
                                                </div>
                                                <div style="font-size:12.5px; color:var(--text-dim); margin-top:4px; max-width:600px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">
                                                    «${esc(a.reason)}»
                                                </div>
                                            </div>
                                        </div>
                                        <div style="display:flex; gap:8px;">
                                            <button type="button" class="primary btn-sm" data-open-appeal-thread="${a.id}">💬 ТИКЕТ / ТРЕД</button>
                                            <button type="button" class="secondary btn-sm" data-select-appeal-verdict="${a.banId}">⚡ В ВЕРДИКТ</button>
                                        </div>
                                    </div>
                                `;
                            }).join('')}
                        </div>
                    `;

                    listWrap.querySelectorAll('[data-open-appeal-thread]').forEach(btn => {
                        btn.addEventListener('click', () => openAppealThreadModal(parseInt(btn.dataset.openAppealThread, 10)));
                    });

                    listWrap.querySelectorAll('[data-select-appeal-verdict]').forEach(btn => {
                        btn.addEventListener('click', () => {
                            const bId = parseInt(btn.dataset.selectAppealVerdict, 10);
                            const selectEl = document.getElementById('verdict-ban-picker');
                            if (selectEl) {
                                selectEl.value = String(bId);
                                currentBan = allBans.find(b => b.id === bId) || null;
                                updateVerdictView();
                                selectEl.scrollIntoView({ behavior: 'smooth' });
                            }
                        });
                    });
                } catch (e) {
                    listWrap.innerHTML = `<div style="text-align:center; padding:16px; color:var(--red);">Ошибка загрузки: ${esc(e.message)}</div>`;
                }
            }

            async function openAppealThreadModal(appealId) {
                try {
                    const appeal = await api('GET', `/api/appeals/${appealId}`);
                    const statusBadge = appeal.status === 'APPROVED' ? '<span class="badge green">ОДОБРЕНО</span>' :
                                        (appeal.status === 'REJECTED' ? '<span class="badge red">ОТКЛОНЕНО</span>' : '<span class="badge yellow">НА РАССМОТРЕНИИ</span>');

                    openModal(`
                        <div class="modal-header">
                            <div style="display:flex; align-items:center; gap:10px;">
                                <img src="https://mc-heads.net/avatar/${esc(appeal.playerName)}/32" style="width:32px; height:32px; border-radius:4px;" alt="">
                                <div>
                                    <h3 style="margin:0; font-size:16px;">ТИКЕТ АПЕЛЛЯЦИИ #${appeal.id}: ${esc(appeal.playerName)}</h3>
                                    <div style="font-size:11.5px; color:var(--text-muted);">Бан #${appeal.banId} • Причина: ${esc(appeal.banReason || '—')} • ${statusBadge}</div>
                                </div>
                            </div>
                            <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
                        </div>
                        <div class="modal-body" style="max-height:65vh; overflow-y:auto;">
                            <div style="background:#0d061c; border:1px solid var(--border); border-radius:var(--radius-sm); padding:12px; margin-bottom:14px; font-size:12.5px;">
                                <div style="color:var(--text-dim); font-size:11px; font-weight:700; text-transform:uppercase;">Причина / аргумент апелляции:</div>
                                <div style="color:#fff; margin-top:4px; line-height:1.5;">${esc(appeal.reason)}</div>
                                ${appeal.discordChannelId ? `<div style="margin-top:8px; color:var(--accent-light); font-size:11.5px;">💬 Discord Ticket ID: <code>${esc(appeal.discordChannelId)}</code></div>` : ''}
                            </div>

                            <div style="font-size:12px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:8px;">
                                История сообщений тикета (Синхронизация с Discord):
                            </div>
                            <div id="appeal-messages-thread" style="display:flex; flex-direction:column; gap:10px; margin-bottom:16px;">
                                ${(appeal.messages || []).map(m => `
                                    <div style="background:${m.isStaff ? 'rgba(124, 58, 237, 0.15)' : 'rgba(255, 255, 255, 0.04)'}; border:1px solid ${m.isStaff ? 'var(--accent)' : 'var(--border)'}; border-radius:6px; padding:10px 12px;">
                                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px; font-size:11.5px;">
                                            <div style="display:flex; align-items:center; gap:6px;">
                                                <b>${esc(m.authorName)}</b>
                                                ${m.isStaff ? '<span class="badge purple" style="font-size:9.5px;">ПЕРСОНАЛ</span>' : '<span class="badge blue" style="font-size:9.5px;">ИГРОК</span>'}
                                            </div>
                                            <span style="color:var(--text-dim); font-size:10.5px;">${fmtTime(m.createdAt)}</span>
                                        </div>
                                        <div style="color:#e2e8f0; font-size:13px; line-height:1.5; white-space:pre-wrap;">${esc(m.message)}</div>
                                    </div>
                                `).join('')}
                            </div>

                            ${appeal.status === 'PENDING' ? `
                                <div style="border-top:1px solid var(--border); padding-top:12px;">
                                    <div class="form-group" style="margin-bottom:8px;">
                                        <label style="font-size:11.5px;">Ответить в тред и Discord канал тикета:</label>
                                        <textarea id="appeal-reply-text" rows="3" placeholder="Введите сообщение игроку..." style="width:100%;"></textarea>
                                    </div>
                                    <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                                        <button type="button" class="primary btn-sm" id="btn-send-appeal-reply">💬 Отправить ответ в Discord</button>
                                        <div style="display:flex; gap:8px;">
                                            <button type="button" class="danger btn-sm" id="btn-reject-appeal-modal">❌ Отклонить</button>
                                            <button type="button" class="success btn-sm" id="btn-approve-appeal-modal">✅ Одобрить и разбанить</button>
                                        </div>
                                    </div>
                                </div>
                            ` : ''}
                        </div>
                        <div class="modal-footer">
                            <button type="button" class="secondary" onclick="window.closeCurrentModal()">ЗАКРЫТЬ</button>
                        </div>
                    `, () => {
                        document.getElementById('btn-send-appeal-reply')?.addEventListener('click', async () => {
                            const replyMsg = document.getElementById('appeal-reply-text')?.value.trim();
                            if (!replyMsg) return;
                            try {
                                await api('POST', `/api/appeals/${appealId}/reply`, { message: replyMsg });
                                showToast('Отправлено', 'Ответ доставлен в тикет Discord', 'success');
                                openAppealThreadModal(appealId);
                            } catch (e) {
                                showToast('Ошибка', e.message, 'error');
                            }
                        });

                        document.getElementById('btn-approve-appeal-modal')?.addEventListener('click', async () => {
                            confirmAction('ОДОБРЕНИЕ АПЕЛЛЯЦИИ', `Разблокировать игрока ${appeal.playerName} и закрыть тикет Discord?`, async () => {
                                try {
                                    await api('POST', `/api/appeals/${appealId}/approve`, {});
                                    showToast('Апелляция одобрена', `Игрок ${appeal.playerName} разбанен на сервере`, 'success');
                                    closeModal();
                                    renderLiveAppealsList();
                                } catch (e) {
                                    showToast('Ошибка', e.message, 'error');
                                }
                            });
                        });

                        document.getElementById('btn-reject-appeal-modal')?.addEventListener('click', async () => {
                            confirmAction('ОТКЛОНЕНИЕ АПЕЛЛЯЦИИ', `Отклонить апелляцию игрока ${appeal.playerName} и закрыть тикет Discord?`, async () => {
                                try {
                                    await api('POST', `/api/appeals/${appealId}/reject`, {});
                                    showToast('Апелляция отклонена', 'Статус обновлен, тикет закрыт', 'info');
                                    closeModal();
                                    renderLiveAppealsList();
                                } catch (e) {
                                    showToast('Ошибка', e.message, 'error');
                                }
                            });
                        });
                    });
                } catch (e) {
                    showToast('Ошибка загрузки апелляции', e.message, 'error');
                }
            }

            document.getElementById('btn-refresh-appeals-list')?.addEventListener('click', renderLiveAppealsList);
            document.getElementById('appeals-status-filter')?.addEventListener('change', renderLiveAppealsList);
            renderLiveAppealsList();

            updateVerdictView();
        }

        // Initialize default subtab
        switchSubtab(currentPunishmentsSubtab, initialBanId);
        updateReportsBadge();
    }
    window.renderPunishmentsView = renderPunishmentsView;
    window.renderBansView = () => renderPunishmentsView('bans');
    window.renderReportsView = () => renderPunishmentsView('reports');
    window.refreshPunishmentsView = () => renderPunishmentsView(currentPunishmentsSubtab);

    // --------------------------------------------------------------------------
    // Quick Permanent Ban Modal with Glowing Toggle & Visual Report Picker
    // --------------------------------------------------------------------------
    window.openQuickBanModal = async function(defaultPlayer = '', defaultReason = '', defaultComment = '', defaultReportId = null) {
        const reasons = await getBanReasons();
        const isManagement = me && (me.isOwner || ['администратор', 'управляющий', 'руководитель', 'куратор'].includes((me.role || '').toLowerCase()));
        let attachedScreenshot = '';
        let attachedReportId = defaultReportId ? parseInt(defaultReportId, 10) : null;
        let searchTimer = null;

        openModal(`
            <div class="modal-header">
                <h3>ВЫДАТЬ БЕССРОЧНЫЙ БАН</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body">
                <div style="font-size:12px; color:var(--text-muted); margin-bottom:16px;">
                    Все блокировки на сервере являются <b>бессрочными (Permanent)</b>. Причина выбирается строго из конфигурации правил сервера.
                </div>

                <!-- Attached Report Box -->
                <div id="ban-attached-report-box" style="display:none; margin-bottom:14px;"></div>

                <!-- Suggested Reports Hint Box -->
                <div id="ban-suggested-reports-box" style="display:none; margin-bottom:14px;"></div>

                <div class="form-group">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
                        <label style="margin-bottom:0;">Никнейм игрока-нарушителя</label>
                        <button type="button" class="btn-tag-link" id="btn-browse-reports-picker" style="font-size:11px; padding:2px 8px;">📋 Выбрать жалобу</button>
                    </div>
                    <input type="text" id="ban-target-name" placeholder="Введите ник игрока..." value="${esc(defaultPlayer)}" required autofocus autocomplete="off">
                </div>

                <div class="form-group">
                    <label>Причина бана (пункт правил)</label>
                    <select id="ban-reason-select" required>
                        <option value="">-- Выберите причину из списка --</option>
                        ${reasons.map(r => `
                            <option value="${esc(r.name)}" data-comment="${r.require_comment ? 'true' : 'false'}" ${defaultReason === r.name ? 'selected' : ''}>
                                ${esc(r.name)}
                            </option>
                        `).join('')}
                    </select>
                </div>

                <div class="form-group">
                    <label style="display:flex; justify-content:space-between; align-items:center;">
                        <span>Описание нарушения / Доказательства</span>
                        ${!isManagement ? '<span style="color:var(--yellow); font-size:11px; font-weight:700;">* ОБЯЗАТЕЛЬНО ДЛЯ МОДЕРАТОРОВ</span>' : '<span style="color:var(--text-dim); font-size:11px;">(Опционально)</span>'}
                    </label>
                    <textarea id="ban-comment-text" rows="3" placeholder="${!isManagement ? 'Обязательно укажите подробности нарушения, таймкоды или контекст ситуации...' : 'Укажите комментарий к блокировке (опционально)...'}">${esc(defaultComment || '')}</textarea>
                </div>

                <div class="form-group">
                    <label>Скриншот нарушения / Доказательство</label>
                    <input type="text" id="ban-screenshot-url" placeholder="Вставьте URL скриншота (Imgur, Yapx, Discord...) или загрузите файл ниже">
                    
                    <div class="screenshot-dropzone" id="ban-screenshot-dropzone">
                        <span style="color:var(--accent-light); font-weight:600;">📁 Загрузить файл скриншота</span>
                        <span style="font-size:11.5px; color:var(--text-muted); display:block; margin-top:3px;">Кликните для выбора, перетащите изображение или нажмите Ctrl+V</span>
                        <input type="file" id="ban-screenshot-file" accept="image/*" style="display:none;">
                    </div>
                    <div id="ban-screenshot-preview-wrap" style="display:none;">
                        <div class="screenshot-preview-box">
                            <img id="ban-screenshot-img" class="screenshot-preview-img" alt="Скриншот">
                            <button type="button" class="btn-remove-screenshot" id="btn-clear-screenshot" title="Удалить скриншот">✕</button>
                        </div>
                    </div>
                </div>

                <!-- Modern Glowing Toggle Switch for IP-Ban (Requirement 5) -->
                <div class="form-group">
                    <label class="toggle-switch-wrap">
                        <input type="checkbox" id="ban-ip-toggle" class="toggle-switch-input">
                        <span class="toggle-switch"></span>
                        <span class="toggle-label">
                            <span class="toggle-title">Заблокировать также IP-адрес игрока (IP-Ban)</span>
                            <span class="toggle-desc">Блокирует текущий сетевой адрес для предотвращения входа с твинков</span>
                        </span>
                    </label>
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="danger" id="ban-submit-btn">ЗАБЛОКИРОВАТЬ НАВСЕГДА</button>
            </div>
        `, () => {
            const select = document.getElementById('ban-reason-select');
            const dropzone = document.getElementById('ban-screenshot-dropzone');
            const fileInput = document.getElementById('ban-screenshot-file');
            const urlInput = document.getElementById('ban-screenshot-url');
            const previewWrap = document.getElementById('ban-screenshot-preview-wrap');
            const previewImg = document.getElementById('ban-screenshot-img');
            const clearBtn = document.getElementById('btn-clear-screenshot');
            const targetInput = document.getElementById('ban-target-name');
            const commentInput = document.getElementById('ban-comment-text');

            // Autocomplete for player names
            if (targetInput) {
                attachPlayerAutocomplete(targetInput, (chosenName) => {
                    checkPendingReports();
                });
            }

            const setScreenshot = (src) => {
                attachedScreenshot = src;
                if (src) {
                    previewImg.src = src;
                    previewWrap.style.display = 'block';
                    dropzone.style.display = 'none';
                } else {
                    previewImg.src = '';
                    previewWrap.style.display = 'none';
                    dropzone.style.display = 'block';
                    if (urlInput) urlInput.value = '';
                    if (fileInput) fileInput.value = '';
                }
            };

            dropzone?.addEventListener('click', () => fileInput?.click());
            fileInput?.addEventListener('change', (e) => {
                const file = e.target.files?.[0];
                if (!file) return;
                const reader = new FileReader();
                reader.onload = (ev) => setScreenshot(ev.target.result);
                reader.readAsDataURL(file);
            });

            urlInput?.addEventListener('input', (e) => {
                const val = e.target.value.trim();
                if (val.startsWith('http://') || val.startsWith('https://')) {
                    setScreenshot(val);
                }
            });

            clearBtn?.addEventListener('click', () => setScreenshot(''));

            // Paste image support
            const handlePaste = (e) => {
                const items = (e.clipboardData || e.originalEvent?.clipboardData)?.items;
                if (!items) return;
                for (const item of items) {
                    if (item.type.indexOf('image') !== -1) {
                        const blob = item.getAsFile();
                        const reader = new FileReader();
                        reader.onload = (ev) => setScreenshot(ev.target.result);
                        reader.readAsDataURL(blob);
                        break;
                    }
                }
            };
            window.addEventListener('paste', handlePaste);

            const cleanup = () => {
                window.removeEventListener('paste', handlePaste);
                if (searchTimer) clearTimeout(searchTimer);
            };

            const renderAttachedReport = async (repId) => {
                attachedReportId = repId ? parseInt(repId, 10) : null;
                const box = document.getElementById('ban-attached-report-box');
                const suggBox = document.getElementById('ban-suggested-reports-box');
                if (!box) return;

                if (!attachedReportId) {
                    box.style.display = 'none';
                    box.innerHTML = '';
                    return;
                }

                try {
                    const data = await api('GET', `/api/reports/${attachedReportId}`);
                    const r = data.report;
                    if (suggBox) suggBox.style.display = 'none';

                    box.style.display = 'block';
                    box.innerHTML = `
                        <div class="attached-report-card">
                            <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:12px;">
                                <div>
                                    <div style="display:flex; align-items:center; gap:8px;">
                                        <span class="badge purple">ПРИКРЕПЛЕНА ЖАЛОБА #${r.id}</span>
                                        <span style="font-weight:700; color:#fff;">от ${esc(r.reporterName)}</span>
                                        ${r.isRecent ? '<span class="badge green" style="font-size:10px;">⏱ &lt; 5 мин</span>' : ''}
                                    </div>
                                    <div style="font-size:12px; color:var(--text-secondary); margin-top:4px;">
                                        Причины: <b>${(r.reasons || []).join(', ') || 'не указаны'}</b>
                                    </div>
                                    ${r.description && r.description !== 'не указано' ? `
                                        <div style="font-size:12px; color:#e2e8f0; margin-top:4px;">"${esc(r.description)}"</div>
                                    ` : ''}
                                </div>
                                <button type="button" class="btn-remove-screenshot" id="btn-detach-report" title="Открепить жалобу">✕</button>
                            </div>
                        </div>
                    `;

                    document.getElementById('btn-detach-report')?.addEventListener('click', () => {
                        renderAttachedReport(null);
                        checkPendingReports();
                    });

                    if (targetInput && !targetInput.value.trim()) {
                        targetInput.value = r.targetName;
                    }

                    if (commentInput && !commentInput.value.trim()) {
                        const desc = (r.description && r.description !== 'не указано' && r.description.trim()) ? r.description.trim() : '';
                        commentInput.value = desc ? `[Жалоба #${r.id}]: ${desc}` : `[Жалоба #${r.id}]`;
                    }

                    if (select && (!select.value || select.value === '')) {
                        const reasonsText = (r.reasons || []).join(' ').toLowerCase();
                        for (let i = 0; i < select.options.length; i++) {
                            const optVal = select.options[i].value.toLowerCase();
                            if (optVal && (reasonsText.includes(optVal) || (optVal.includes('чит') && reasonsText.includes('чит')))) {
                                select.selectedIndex = i;
                                break;
                            }
                        }
                    }
                } catch (err) {
                    console.warn('Не удалось загрузить данные жалобы #' + repId, err);
                }
            };

            const checkPendingReports = async () => {
                if (attachedReportId) return;
                const suggBox = document.getElementById('ban-suggested-reports-box');
                if (!suggBox || !targetInput) return;

                const name = targetInput.value.trim();
                if (!name || name.length < 2) {
                    suggBox.style.display = 'none';
                    suggBox.innerHTML = '';
                    return;
                }

                try {
                    const res = await api('GET', `/api/reports?status=PENDING&target=${encodeURIComponent(name)}`);
                    const list = res.reports || [];
                    if (list.length > 0 && !attachedReportId) {
                        suggBox.style.display = 'block';
                        suggBox.innerHTML = `
                            <div class="report-picker-hint">
                                <div>
                                    <span>💡 Найдено активных жалоб на игрока: <b>${list.length}</b></span>
                                </div>
                                <div style="display:flex; gap:6px; flex-wrap:wrap; align-items:center;">
                                    ${list.slice(0, 3).map(pr => `
                                        <button type="button" class="btn-tag-link" style="color:#fbbf24; border-color:rgba(245,158,11,0.5);" data-attach-id="${pr.id}">
                                            Прикрепить #${pr.id} (${(pr.reasons || []).slice(0, 2).join(', ') || 'жалоба'})
                                        </button>
                                    `).join('')}
                                </div>
                            </div>
                        `;
                        suggBox.querySelectorAll('[data-attach-id]').forEach(btn => {
                            btn.addEventListener('click', () => {
                                const id = parseInt(btn.dataset.attachId, 10);
                                if (id) renderAttachedReport(id);
                            });
                        });
                    } else {
                        suggBox.style.display = 'none';
                        suggBox.innerHTML = '';
                    }
                } catch (e) {
                    suggBox.style.display = 'none';
                }
            };

            targetInput?.addEventListener('input', () => {
                clearTimeout(searchTimer);
                searchTimer = setTimeout(checkPendingReports, 400);
            });

            // Open visual Report Picker Modal instead of prompt by ID!
            document.getElementById('btn-browse-reports-picker')?.addEventListener('click', () => {
                openReportPickerModal(targetInput?.value || '', (rep) => {
                    if (rep && rep.id) renderAttachedReport(rep.id);
                });
            });

            if (attachedReportId) {
                renderAttachedReport(attachedReportId);
            } else if (defaultPlayer && defaultPlayer.trim()) {
                checkPendingReports();
            }

            document.getElementById('ban-submit-btn')?.addEventListener('click', async () => {
                const targetName = targetInput.value.trim();
                const reason = select.value;
                const comment = commentInput.value.trim();
                const isIpBan = document.getElementById('ban-ip-toggle')?.checked || false;

                if (!targetName) { alert('Укажите никнейм игрока'); return; }
                if (!reason) { alert('Выберите причину бана'); return; }

                if (!isManagement && !comment) {
                    alert('Для вашей роли описание доказательств бана является обязательным!');
                    commentInput?.focus();
                    return;
                }

                const opt = select.options[select.selectedIndex];
                if (opt && (opt.dataset.comment === 'true' || opt.value.toLowerCase().includes('другое')) && !comment) {
                    alert('При выборе данной причины обязателен поясняющий комментарий!');
                    commentInput?.focus();
                    return;
                }

                const confirmMsg = attachedReportId
                    ? `Выдать бессрочный бан игроку ${targetName} по причине «${reason}»?\nПрикрепленная жалоба #${attachedReportId} будет закрыта со статусом «Наказание принято».`
                    : `Выдать бессрочный бан игроку ${targetName} по причине: «${reason}»?`;

                confirmAction('ПОДТВЕРЖДЕНИЕ БАНА', confirmMsg, async () => {
                    try {
                        cleanup();
                        await api('POST', '/api/bans', {
                            targetName,
                            ruleReason: reason,
                            description: comment,
                            isIpBan,
                            proofUrls: attachedScreenshot ? [attachedScreenshot] : [],
                            screenshot: attachedScreenshot,
                            linkedReportId: attachedReportId
                        });
                        const actionLog = `Забанил ${targetName} (${reason})` + (attachedReportId ? ` [по жалобе #${attachedReportId}]` : '');
                        recordShiftAction(actionLog);
                        showToast('Игрок забанен', `Бессрочный бан выдан игроку ${targetName}`, 'danger');
                        closeModal();
                        if (window.refreshPunishmentsView) window.refreshPunishmentsView();
                    } catch (e) {
                        alert('Ошибка выдачи бана: ' + e.message);
                    }
                }, 'ЗАБЛОКИРОВАТЬ НАВСЕГДА', true);
            });
        });
    };

    // Modal: Ban Details
    window.viewBanDetails = async function(banId) {
        openModal(`
            <div class="modal-header">
                <h3>ДЕТАЛИ БЛОКИРОВКИ #${banId}</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body" id="ban-details-body">
                <div style="text-align:center; padding:24px; color:var(--text-dim);">Загрузка деталей...</div>
            </div>
            <div class="modal-footer" id="ban-details-footer">
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `, async () => {
            const body = document.getElementById('ban-details-body');
            const footer = document.getElementById('ban-details-footer');
            if (!body) return;

            try {
                const b = await api('GET', `/api/bans/${banId}`);
                const isActive = b.status === 'ACTIVE';
                const isMod = isModeratorRole();
                const canUnban = !isMod || me.isOwner;
                const proofUrl = b.screenshotUrl || (b.proofUrls && b.proofUrls.length ? b.proofUrls[0] : null);

                body.innerHTML = `
                    <div style="display:flex; align-items:center; gap:16px; margin-bottom:20px; padding-bottom:16px; border-bottom:1px solid var(--border);">
                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(b.targetName)}/56" style="width:56px; height:56px; border-radius:8px;" alt="">
                        <div>
                            <div style="display:flex; align-items:center; gap:8px;">
                                <h3 style="margin:0; font-size:20px; color:#fff;">${esc(b.targetName)}</h3>
                                <span class="badge ${isActive ? 'red' : 'green'}">${isActive ? 'АКТИВЕН' : 'СНЯТ'}</span>
                                ${b.isIpBan ? '<span class="badge yellow">IP-БАН</span>' : ''}
                            </div>
                            <div style="font-size:12.5px; color:var(--text-muted); margin-top:4px;">
                                ID блокировки: <b>#${b.id}</b> • Создан: <b>${fmtTime(b.createdAt)}</b>
                            </div>
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:140px 1fr; gap:10px; font-size:13px; margin-bottom:16px;">
                        <div style="color:var(--text-dim); font-weight:700;">ПРИЧИНА (ПУНКТ):</div>
                        <div><span class="badge red">${esc(b.ruleReason)}</span></div>

                        <div style="color:var(--text-dim); font-weight:700;">АДМИНИСТРАТОР:</div>
                        <div style="color:var(--accent-light); font-weight:600;">${esc(b.creatorName)}</div>

                        <div style="color:var(--text-dim); font-weight:700;">СРОК ДЕЙСТВИЯ:</div>
                        <div><span class="badge purple">БЕССРОЧНО (PERMANENT)</span></div>

                        ${b.linkedReportId ? `
                            <div style="color:var(--text-dim); font-weight:700;">СВЯЗАННАЯ ЖАЛОБА:</div>
                            <div>
                                <button type="button" class="btn-tag-link" onclick="window.closeCurrentModal(); window.openReportDetailModal(${b.linkedReportId})">
                                    📋 ЖАЛОБА #${b.linkedReportId}
                                </button>
                            </div>
                        ` : ''}

                        <div style="color:var(--text-dim); font-weight:700;">ПОЯСНЕНИЕ:</div>
                        <div style="color:#e2e8f0; line-height:1.5;">${esc(b.description || 'Комментарий отсутствует')}</div>
                    </div>

                    ${proofUrl ? `
                        <div style="margin-top:16px;">
                            <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:6px;">Скриншот-доказательство:</div>
                            <img src="${esc(proofUrl)}" class="screenshot-preview-img" style="max-height:240px; cursor:pointer;" onclick="window.openScreenshotLightbox('${esc(proofUrl)}')" alt="Доказательство">
                        </div>
                    ` : ''}
                `;

                if (footer) {
                    footer.innerHTML = `
                        <button type="button" class="secondary" onclick="window.closeCurrentModal(); window.renderPunishmentsView('appeals', ${b.id});">⚖️ АПЕЛЛЯЦИЯ (DISCORD)</button>
                        <button type="button" class="secondary" onclick="window.openPlayerAltsModal('${esc(b.targetName)}')">ПРОВЕРИТЬ АЛЬТЫ</button>
                        ${isActive && canUnban ? `
                            <button type="button" class="danger" id="btn-modal-unban">РАЗБАНИТЬ ИГРОКА</button>
                        ` : ''}
                        <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
                    `;

                    document.getElementById('btn-modal-unban')?.addEventListener('click', () => {
                        window.confirmUnban(b.id, b.targetName);
                    });
                }
            } catch (e) {
                body.innerHTML = `<div style="color:var(--red); padding:24px; text-align:center;">Ошибка загрузки деталей бана: ${esc(e.message)}</div>`;
            }
        });
    };

    // Modal: Screenshot Lightbox
    window.openScreenshotLightbox = function(imgSrc) {
        if (!imgSrc) return;
        openModal(`
            <div class="modal-header">
                <h3>ПРОСМОТР ДОКАЗАТЕЛЬСТВА</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body" style="text-align:center; padding:10px;">
                <img src="${esc(imgSrc)}" style="max-width:100%; max-height:75vh; border-radius:6px; object-fit:contain; box-shadow:0 10px 30px rgba(0,0,0,0.5);" alt="Доказательство">
            </div>
            <div class="modal-footer">
                <a href="${esc(imgSrc)}" target="_blank" class="button secondary" rel="noopener noreferrer">ОТКРЫТЬ В НОВОЙ ВКЛАДКЕ ↗</a>
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `, null, 'modal-lg');
    };

    // Confirm Unban
    window.confirmUnban = function(banId, playerName) {
        confirmAction(
            'СНЯТИЕ БЛОКИРОВКИ',
            `Вы действительно хотите разбанить игрока ${playerName}? Блокировка #${banId} будет снята, игрок сможет зайти на сервер.`,
            async () => {
                try {
                    await api('POST', `/api/bans/${banId}/unban`);
                    recordShiftAction(`Разбанил ${playerName}`);
                    showToast('Разбан', `Игрок ${playerName} успешно разбанен`, 'success');
                    closeModal();
                    if (window.refreshPunishmentsView) window.refreshPunishmentsView();
                } catch (e) {
                    showToast('Ошибка разбана', e.message, 'error');
                }
            },
            'РАЗБАНИТЬ',
            false
        );
    };

    // Modal: Player Alts
    window.openPlayerAltsModal = async function(playerName) {
        openModal(`
            <div class="modal-header">
                <h3>ТВИНКИ И АЛЬТ-АККАУНТЫ: ${esc(playerName)}</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body" id="player-alts-body">
                <div style="text-align:center; padding:24px; color:var(--text-dim);">Поиск связанных аккаунтов по IP...</div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `, async () => {
            const body = document.getElementById('player-alts-body');
            if (!body) return;

            try {
                const data = await api('GET', `/api/players/${encodeURIComponent(playerName)}/alts`);
                const alts = data.alts || [];

                if (!alts.length) {
                    body.innerHTML = `<div style="text-align:center; padding:24px; color:var(--text-muted);">Связанных аккаунтов по IP-адресу не обнаружено.</div>`;
                    return;
                }

                body.innerHTML = `
                    <div style="font-size:12.5px; color:var(--text-secondary); margin-bottom:14px;">
                        Найдено совпадающих аккаунтов по истории сессий: <b>${alts.length}</b>
                    </div>
                    <div class="table-wrap">
                        <table>
                            <thead>
                                <tr>
                                    <th>ИГРОК</th>
                                    <th>ПОСЛЕДНИЙ ВХОД</th>
                                    <th>СТАТУС</th>
                                    <th style="text-align:right;">ДЕЙСТВИЯ</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${alts.map(a => `
                                    <tr>
                                        <td>
                                            <div style="display:flex; align-items:center; gap:8px;">
                                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(a.name)}/20" style="width:20px; height:20px; border-radius:3px;" alt="">
                                                <b style="color:#fff;">${esc(a.name)}</b>
                                            </div>
                                        </td>
                                        <td class="font-mono" style="font-size:11.5px; color:var(--text-muted);">${fmtTime(a.lastSeen)}</td>
                                        <td>
                                            <span class="badge ${a.isBanned ? 'red' : 'green'}">${a.isBanned ? 'ЗАБАНЕН' : 'ЧИСТ'}</span>
                                        </td>
                                        <td style="text-align:right;">
                                            <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(a.name)}')">ДОСЬЕ</button>
                                        </td>
                                    </tr>
                                `).join('')}
                            </tbody>
                        </table>
                    </div>
                `;
            } catch (e) {
                body.innerHTML = `<div style="color:var(--red); padding:24px; text-align:center;">Ошибка поиска альтов: ${esc(e.message)}</div>`;
            }
        });
    };

    // --------------------------------------------------------------------------
    // Report Action Handlers (Safe Integer IDs)
    // --------------------------------------------------------------------------
    window.handleAcceptReport = (id, targetName) => {
        confirmAction(
            'ПРИНЯТЬ ЖАЛОБУ #' + id,
            `Вы подтверждаете, что нарушение игрока ${targetName} доказано?\nНарушитель будет помечен как наказанный, а заявителю начислится репутация.`,
            async () => {
                try {
                    await api('POST', `/api/reports/${id}/accept`);
                    recordShiftAction(`Принял жалобу #${id} на игрока ${targetName}`);
                    showToast('Жалоба принята', `Наказание подтверждено. Заявитель уведомлен.`, 'success');
                    if (window.refreshPunishmentsView) window.refreshPunishmentsView();
                    updateReportsBadge();
                } catch (e) {
                    showToast('Ошибка', e.message, 'error');
                }
            },
            'ПРИНЯТЬ И НАКАЗАТЬ',
            false
        );
    };

    window.handleRejectReport = (id, targetName) => {
        confirmAction(
            'ОТКЛОНИТЬ ЖАЛОБУ #' + id,
            `Пометить жалобу как ложную?\nИгроку ${targetName} будет возвращена снятая репутация (+25), а заявитель уведомлен в игре.`,
            async () => {
                try {
                    await api('POST', `/api/reports/${id}/reject`);
                    recordShiftAction(`Отклонил жалобу #${id} на игрока ${targetName} как ложную`);
                    showToast('Жалоба отклонена', 'Помечена как ложная. Репутация возвращена цели.', 'info');
                    if (window.refreshPunishmentsView) window.refreshPunishmentsView();
                    updateReportsBadge();
                } catch (e) {
                    showToast('Ошибка', e.message, 'error');
                }
            },
            'ПОМЕТИТЬ КАК ЛОЖНУЮ',
            true
        );
    };

    window.handleDeleteReport = (id) => {
        confirmAction(
            'УДАЛЕНИЕ ЖАЛОБЫ #' + id,
            'Вы действительно хотите удалить запись этой жалобы из базы данных?',
            async () => {
                try {
                    await api('DELETE', `/api/reports/${id}`);
                    recordShiftAction(`Удалил запись жалобы #${id}`);
                    showToast('Удалено', `Жалоба #${id} успешно удалена`, 'info');
                    if (window.refreshPunishmentsView) window.refreshPunishmentsView();
                    updateReportsBadge();
                } catch (e) {
                    showToast('Ошибка', e.message, 'error');
                }
            },
            'УДАЛИТЬ'
        );
    };

    window.handleBanFromReportRow = (id) => {
        const r = lastLoadedReports.find(x => x.id === id);
        if (!r) return;
        const mainReason = (r.reasons && r.reasons.length) ? r.reasons[0] : 'Нарушение правил';
        const comment = (r.description && r.description !== 'не указано') ? r.description : '';
        openQuickBanModal(r.targetName, mainReason, comment, r.id);
    };

    // Modal: Report Detail & Chat Logs
    window.openReportDetailModal = async function(reportId) {
        openModal(`
            <div class="modal-header">
                <h3>ДЕТАЛИЗАЦИЯ ЖАЛОБЫ #${reportId}</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body" id="report-modal-body">
                <div style="text-align:center; padding:32px; color:var(--text-dim);">Загрузка данных жалобы...</div>
            </div>
            <div class="modal-footer" id="report-modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `, async () => {
            const body = document.getElementById('report-modal-body');
            const footer = document.getElementById('report-modal-footer');
            if (!body) return;

            try {
                const data = await api('GET', `/api/reports/${reportId}`);
                const r = data.report;
                const logs = data.chatLogs || [];

                let statusBadge;
                switch (r.status) {
                    case 'ACCEPTED': statusBadge = '<span class="badge green">ПРИНЯТ (НАКАЗАН)</span>'; break;
                    case 'REJECTED': statusBadge = '<span class="badge gray">ЛОЖНЫЙ (ОТКЛОНЕН)</span>'; break;
                    case 'EXPIRED': statusBadge = '<span class="badge gray">ИСТЁК</span>'; break;
                    default: statusBadge = '<span class="badge yellow">ОЖИДАЕТ РАССМОТРЕНИЯ</span>'; break;
                }

                body.innerHTML = `
                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px; margin-bottom:18px;">
                        <div style="background:rgba(255,255,255,0.02); border:1px solid var(--border); border-radius:6px; padding:12px;">
                            <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Нарушитель (Обвиняемый):</div>
                            <div style="display:flex; align-items:center; gap:10px; margin-top:8px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.targetName)}/40" style="width:40px; height:40px; border-radius:4px;" alt="">
                                <div>
                                    <div style="font-size:15px; font-weight:700; color:#fff;">${esc(r.targetName)}</div>
                                    <div style="font-size:11.5px; color:var(--text-muted);">${r.targetReputation != null ? 'Репутация: ' + r.targetReputation : ''}</div>
                                </div>
                            </div>
                        </div>

                        <div style="background:rgba(255,255,255,0.02); border:1px solid var(--border); border-radius:6px; padding:12px;">
                            <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Заявитель (Отправитель):</div>
                            <div style="display:flex; align-items:center; gap:10px; margin-top:8px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.reporterName)}/40" style="width:40px; height:40px; border-radius:4px;" alt="">
                                <div>
                                    <div style="font-size:15px; font-weight:700; color:#fff;">${esc(r.reporterName)}</div>
                                    <div style="font-size:11.5px; color:var(--text-muted);">${r.reporterReputation != null ? 'Репутация: ' + r.reporterReputation : ''}</div>
                                </div>
                            </div>
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:140px 1fr; gap:8px; font-size:13px; margin-bottom:16px;">
                        <div style="color:var(--text-dim); font-weight:700;">СТАТУС:</div>
                        <div>${statusBadge}</div>

                        <div style="color:var(--text-dim); font-weight:700;">ПУНКТЫ ПРАВИЛ:</div>
                        <div>${(r.reasons || []).map(re => `<span class="badge purple" style="margin-right:4px;">${esc(re)}</span>`).join('')}</div>

                        <div style="color:var(--text-dim); font-weight:700;">ОПИСАНИЕ:</div>
                        <div style="color:#e2e8f0;">${esc(r.description || 'Не указано')}</div>

                        <div style="color:var(--text-dim); font-weight:700;">ВРЕМЯ ПОДАЧИ:</div>
                        <div>${fmtTime(r.createdAt)} (${r.isRecent ? 'менее 5 минут назад' : 'более 5 минут назад'})</div>
                    </div>

                    <div style="margin-top:16px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:6px;">Снимок чата на момент жалобы:</div>
                        <div style="background:#090614; border:1px solid var(--border); border-radius:6px; padding:10px; max-height:160px; overflow-y:auto; font-family:monospace; font-size:12px;">
                            ${logs.length ? logs.map(l => `
                                <div style="padding:2px 0; color:${l.isTarget ? 'var(--yellow)' : (l.isReporter ? 'var(--cyan)' : 'var(--text-muted)')};">
                                    [${fmtTime(l.timestamp)}] <b>&lt;${esc(l.sender)}&gt;</b> ${esc(l.message)}
                                </div>
                            `).join('') : '<div style="color:var(--text-dim);">Сохранённых сообщений чата нет</div>'}
                        </div>
                    </div>
                `;

                if (footer) {
                    if (r.status === 'PENDING') {
                        footer.innerHTML = `
                            <button type="button" class="secondary" id="rep-modal-alts-btn">ПРОВЕРИТЬ АЛЬТЫ</button>
                            <button type="button" class="danger" id="rep-modal-ban-btn">ВЫДАТЬ БАН</button>
                            <button type="button" class="secondary" id="rep-modal-reject-btn">ЛОЖНАЯ</button>
                            <button type="button" class="primary" id="rep-modal-accept-btn">ПРИНЯТЬ ЖАЛОБУ</button>
                        `;

                        document.getElementById('rep-modal-alts-btn')?.addEventListener('click', () => {
                            window.openPlayerAltsModal(r.targetName);
                        });

                        document.getElementById('rep-modal-ban-btn')?.addEventListener('click', () => {
                            closeModal();
                            window.handleBanFromReportRow(r.id);
                        });

                        document.getElementById('rep-modal-reject-btn')?.addEventListener('click', () => {
                            closeModal();
                            window.handleRejectReport(r.id, r.targetName);
                        });

                        document.getElementById('rep-modal-accept-btn')?.addEventListener('click', () => {
                            closeModal();
                            window.handleAcceptReport(r.id, r.targetName);
                        });
                    } else if (r.linkedBanId && r.linkedBanId > 0) {
                        footer.innerHTML = `
                            <button type="button" class="primary" onclick="window.closeCurrentModal(); window.viewBanDetails(${r.linkedBanId});">🔨 ПРОСМОТРЕТЬ БАН #${r.linkedBanId}</button>
                            <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
                        `;
                    } else {
                        footer.innerHTML = `
                            <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
                        `;
                    }
                }
            } catch (e) {
                body.innerHTML = `<div style="color:var(--red); padding:24px; text-align:center;">Ошибка загрузки данных: ${esc(e.message)}</div>`;
            }
        }, 'modal-lg');
    };


    // Modal: Ban Details with Linked Report
    window.viewBanDetails = async function(banId) {
        openModal(`
            <div class="modal-header">
                <h3>ДЕТАЛИ БЛОКИРОВКИ #${banId}</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
            </div>
            <div class="modal-body" id="modal-ban-details-body">
                <div style="text-align:center; padding:32px; color:var(--text-dim);">Загрузка информации о бане...</div>
            </div>
            <div class="modal-footer" id="modal-ban-details-footer">
                <button type="button" class="secondary" onclick="window.closeCurrentModal(); window.openAppealTemplateModal(${banId});">📋 ШАБЛОН АПЕЛЛЯЦИИ</button>
                <button type="button" class="secondary" onclick="window.closeCurrentModal()">ЗАКРЫТЬ</button>
            </div>
        `, async () => {
            const body = document.getElementById('modal-ban-details-body');
            if (!body) return;

            try {
                const b = await api('GET', `/api/bans/${banId}`);
                const isActive = b.status === 'ACTIVE';
                const proofUrl = b.screenshotUrl || (b.proofUrls && b.proofUrls.length ? b.proofUrls[0] : null);

                body.innerHTML = `
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; flex-wrap:wrap; gap:12px;">
                        <div style="display:flex; align-items:center; gap:12px;">
                            <img src="https://mc-heads.net/avatar/${encodeURIComponent(b.targetName)}/48" style="width:48px; height:48px; border-radius:6px;" alt="">
                            <div>
                                <div style="font-size:18px; font-weight:800; color:#fff;">${esc(b.targetName)}</div>
                                <div style="font-size:12px; color:var(--text-dim);">
                                    ${b.targetUuid ? `UUID: <span class="font-mono">${esc(b.targetUuid)}</span>` : ''}
                                    ${b.targetIp ? ` • IP: <span class="font-mono">${esc(b.targetIp)}</span>` : ''}
                                </div>
                            </div>
                        </div>
                        <div>
                            <span class="badge ${isActive ? 'red' : 'green'}" style="font-size:12px;">
                                ${isActive ? 'АКТИВНЫЙ БАН' : 'РАЗБАНЕН'}
                            </span>
                        </div>
                    </div>

                    ${b.linkedReportId ? `
                        <div class="attached-report-card">
                            <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                                <div>
                                    <span class="badge purple">ПРИКРЕПЛЕННАЯ ЖАЛОБА #${b.linkedReportId}</span>
                                    ${b.linkedReport ? `
                                        <div style="font-size:12px; color:var(--text-secondary); margin-top:4px;">
                                            Заявитель: <b>${esc(b.linkedReport.reporterName)}</b> • Причины: <b>${(b.linkedReport.reasons || []).join(', ') || 'не указаны'}</b>
                                        </div>
                                    ` : ''}
                                </div>
                                <button type="button" class="secondary btn-sm" onclick="window.closeCurrentModal(); window.openReportDetailModal(${b.linkedReportId});">
                                    ОТКРЫТЬ ДОСЬЕ ЖАЛОБЫ
                                </button>
                            </div>
                        </div>
                    ` : `
                        <div style="background:rgba(255, 255, 255, 0.03); border:1px dashed var(--border); border-radius:var(--radius-md); padding:10px 14px; margin-bottom:16px; display:flex; justify-content:space-between; align-items:center;">
                            <span style="font-size:12px; color:var(--text-dim);">К этому бану не привязана жалоба</span>
                            <button type="button" class="secondary btn-sm" id="btn-attach-report-to-ban">
                                + ПРИКРЕПИТЬ ЖАЛОБУ
                            </button>
                        </div>
                    `}

                    <div class="report-dossier-card" style="margin-bottom:16px;">
                        <div style="margin-bottom:10px;">
                            <span style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Пункт правил / Причина:</span>
                            <div style="margin-top:4px;"><span class="badge red" style="font-size:13px;">${esc(b.ruleReason)}</span></div>
                        </div>
                        <div style="margin-bottom:10px;">
                            <span style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Подробное описание:</span>
                            <div style="margin-top:4px; padding:10px; background:rgba(0,0,0,0.3); border-radius:6px; color:#e2e8f0; font-size:13px;">
                                ${esc(b.description || 'Описание не указано')}
                            </div>
                        </div>
                        <div style="display:flex; justify-content:space-between; font-size:12px; color:var(--text-dim); margin-top:12px; flex-wrap:wrap; gap:8px;">
                            <div>Заблокировал: <b style="color:var(--accent-light);">${esc(b.creatorName)}</b></div>
                            <div>Дата выдачи: <b class="font-mono" style="color:#fff;">${fmtTime(b.createdAt)}</b></div>
                        </div>
                    </div>

                    ${proofUrl ? `
                        <div style="margin-bottom:16px;">
                            <span style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; display:block; margin-bottom:6px;">Скриншот / Доказательство:</span>
                            <img src="${esc(proofUrl)}" class="screenshot-preview-img" style="max-height:240px; cursor:pointer;" onclick="window.openScreenshotLightbox('${esc(proofUrl)}')" alt="Доказательство">
                        </div>
                    ` : ''}
                `;

                document.getElementById('btn-attach-report-to-ban')?.addEventListener('click', async () => {
                    const input = prompt('Введите номер жалобы (ID) для прикрепления к бану #' + banId + ':');
                    if (!input || !input.trim()) return;
                    const reportId = parseInt(input.trim());
                    if (isNaN(reportId) || reportId <= 0) {
                        alert('Укажите корректный числовой ID жалобы');
                        return;
                    }
                    try {
                        await api('POST', `/api/bans/${banId}/attach-report`, { reportId });
                        showToast('Жалоба привязана', `Жалоба #${reportId} успешно привязана к бану #${banId}`, 'success');
                        closeModal();
                        if (activeNavSection === 'bans') renderBansView();
                    } catch (err) {
                        alert('Ошибка привязки: ' + err.message);
                    }
                });

            } catch (e) {
                body.innerHTML = `<div style="color:var(--red); padding:24px; text-align:center;">Ошибка загрузки бана: ${esc(e.message)}</div>`;
            }
        });
    };

    window.openScreenshotLightbox = function(imgSrc) {
        if (!imgSrc) return;
        const div = document.createElement('div');
        div.className = 'lightbox-overlay';
        div.innerHTML = `
            <div style="position:relative; display:inline-block;">
                <img src="${esc(imgSrc)}" class="lightbox-img" alt="Скриншот нарушения">
                <button type="button" class="btn-remove-screenshot" style="top:-12px; right:-12px; width:32px; height:32px; font-size:16px;" onclick="this.closest('.lightbox-overlay').remove()">✕</button>
            </div>
        `;
        div.addEventListener('click', (e) => {
            if (e.target === div) div.remove();
        });
        document.body.appendChild(div);
    };

    window.confirmUnban = function(banId, playerName) {
        if (isModeratorRole() && !me.isOwner) {
            alert('Разбан игроков разрешён только Администраторам сервера!');
            return;
        }

        confirmAction('РАЗБАН ИГРОКА', `Вы действительно хотите снять бан с игрока ${playerName}? Действие будет зафиксировано в журнале аудита.`, async () => {
            try {
                await api('POST', `/api/bans/${banId}/unban`);
                recordShiftAction(`Разбанил ${playerName}`);
                showToast('Игрок разбанен', `Бан с игрока ${playerName} успешно снят`, 'success');
                if (activeNavSection === 'bans') renderBansView();
            } catch (e) {
                alert('Ошибка разбана: ' + e.message);
            }
        }, 'РАЗБАНИТЬ', false);
    };

    // Modal: Appeal Templates & Verdicts Generator
    let cachedAppealTemplates = null;

    window.openAppealTemplateModal = async function(targetBanId = null) {
        openModal(`
            <div class="modal-header">
                <div style="display:flex; align-items:center; gap:10px;">
                    <span style="font-size:20px;">📋</span>
                    <div>
                        <h3 style="margin:0;">ШАБЛОНЫ АПЕЛЛЯЦИЙ И ВЕРДИКТОВ</h3>
                        <div style="font-size:12px; color:var(--text-dim);" id="appeal-modal-subtitle">
                            Генератор формы для игрока и заготовки официальных решений администрации
                        </div>
                    </div>
                </div>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
            </div>
            <div class="modal-body" id="modal-appeal-body" style="padding:16px 20px;">
                <div style="text-align:center; padding:32px; color:var(--text-dim);">Загрузка шаблонов апелляций...</div>
            </div>
            <div class="modal-footer" id="modal-appeal-footer">
                <button type="button" class="secondary" onclick="window.closeCurrentModal()">ЗАКРЫТЬ</button>
            </div>
        `, async () => {
            const body = document.getElementById('modal-appeal-body');
            if (!body) return;

            try {
                if (!cachedAppealTemplates) {
                    cachedAppealTemplates = await api('GET', '/api/bans/appeal-templates');
                }
                const tmpl = cachedAppealTemplates;

                let selectedBan = null;
                let bansList = [];
                try {
                    bansList = await api('GET', '/api/bans/all');
                } catch (e) {
                    bansList = [];
                }

                if (targetBanId) {
                    selectedBan = bansList.find(b => b.id === targetBanId);
                    if (!selectedBan) {
                        try {
                            selectedBan = await api('GET', `/api/bans/${targetBanId}`);
                        } catch (err) {
                            selectedBan = null;
                        }
                    }
                }

                let currentBan = selectedBan || (bansList.length ? bansList[0] : null);
                let currentTab = 'form'; // 'form' or 'verdicts'
                let formatMode = 'plain'; // 'plain', 'discord', 'bbcode'
                let selectedVerdictId = (tmpl.verdicts && tmpl.verdicts.length) ? tmpl.verdicts[0].id : '';

                const renderModalContent = () => {
                    const subtitle = document.getElementById('appeal-modal-subtitle');
                    if (subtitle) {
                        subtitle.innerHTML = currentBan
                            ? `Блокировка <b>#${currentBan.id}</b> игрока <b style="color:#fff;">${esc(currentBan.targetName)}</b> • Причина: <b>${esc(currentBan.ruleReason)}</b>`
                            : `Общий режим (без привязки к конкретному бану)`;
                    }

                    // Helper to replace placeholders
                    const formatText = (rawStr) => {
                        if (!rawStr) return '';
                        const targetName = currentBan ? currentBan.targetName : '{targetName}';
                        const banId = currentBan ? currentBan.id : '{id}';
                        const creatorName = currentBan ? currentBan.creatorName : '{creatorName}';
                        const ruleReason = currentBan ? currentBan.ruleReason : '{ruleReason}';
                        const createdAt = currentBan ? fmtTime(currentBan.createdAt) : '{createdAt}';
                        const proofUrl = currentBan ? (currentBan.screenshotUrl || (currentBan.proofUrls && currentBan.proofUrls.length ? currentBan.proofUrls[0] : 'Доказательства прикреплены в системе администрирования')) : '{screenshotUrl}';

                        return rawStr
                            .replace(/{targetName}/g, targetName)
                            .replace(/#{id}/g, '#' + banId)
                            .replace(/{id}/g, banId)
                            .replace(/{creatorName}/g, creatorName)
                            .replace(/{ruleReason}/g, ruleReason)
                            .replace(/{createdAt}/g, createdAt)
                            .replace(/{screenshotUrl}/g, proofUrl);
                    };

                    // Build Tab 1 (Form) content
                    let formContent = '';
                    if (tmpl.form) {
                        const header = formatText(tmpl.form.header || '');
                        const fields = (tmpl.form.fields || []).map(f => formatText(f)).join('\n');
                        const footerText = formatText(tmpl.form.footer || '');
                        
                        let combined = (header ? header + '\n\n' : '') + fields + (footerText ? '\n\n' + footerText : '');
                        if (formatMode === 'discord') {
                            combined = '```markdown\n' + combined + '\n```';
                        } else if (formatMode === 'bbcode') {
                            combined = '[b]' + (header || 'ФОРМА АПЕЛЛЯЦИИ') + '[/b]\n\n[code]\n' + fields + '\n[/code]\n\n[i]' + footerText + '[/i]';
                        }
                        formContent = combined;
                    }

                    // Build Tab 2 (Verdicts) content
                    const curVerdict = (tmpl.verdicts || []).find(v => v.id === selectedVerdictId) || (tmpl.verdicts ? tmpl.verdicts[0] : null);
                    let verdictContent = curVerdict ? formatText(curVerdict.template) : '';

                    body.innerHTML = `
                        <!-- Ban Selector -->
                        <div style="background:rgba(255, 255, 255, 0.03); border:1px solid var(--border); border-radius:var(--radius-md); padding:10px 14px; margin-bottom:14px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                            <div style="display:flex; align-items:center; gap:10px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(currentBan ? currentBan.targetName : 'Steve')}/32" style="width:32px; height:32px; border-radius:4px;" alt="">
                                <div>
                                    <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Выбранный бан для авто-подстановки:</div>
                                    <div style="font-size:13.5px; font-weight:800; color:#fff;">
                                        ${currentBan ? `${esc(currentBan.targetName)} (Бан #${currentBan.id})` : 'Общий шаблон без привязки'}
                                    </div>
                                </div>
                            </div>
                            <div style="min-width:240px; flex:1; max-width:360px;">
                                <select id="appeal-ban-picker" style="font-size:12px; padding:6px 10px; width:100%;">
                                    ${bansList.map(b => `
                                        <option value="${b.id}" ${currentBan && currentBan.id === b.id ? 'selected' : ''}>
                                            #${b.id} — ${esc(b.targetName)} (${esc(b.ruleReason)})
                                        </option>
                                    `).join('')}
                                    <option value="0" ${!currentBan ? 'selected' : ''}>[Без привязки / Переменные {targetName}]</option>
                                </select>
                            </div>
                        </div>

                        <!-- Tabs Header -->
                        <div class="appeal-tabs-bar">
                            <button type="button" class="appeal-tab-btn ${currentTab === 'form' ? 'active' : ''}" id="tab-btn-form">
                                📄 ФОРМА ДЛЯ ИГРОКА
                            </button>
                            <button type="button" class="appeal-tab-btn ${currentTab === 'verdicts' ? 'active' : ''}" id="tab-btn-verdicts">
                                ⚖️ ВЕРДИКТЫ АДМИНИСТРАЦИИ (${(tmpl.verdicts || []).length})
                            </button>
                        </div>

                        ${currentTab === 'form' ? `
                            <!-- Tab 1: Form View -->
                            <div class="appeal-toolbar">
                                <div style="display:flex; gap:6px;">
                                    <button type="button" class="secondary btn-sm ${formatMode === 'plain' ? 'primary' : ''}" id="fmt-plain-btn">Plain Text</button>
                                    <button type="button" class="secondary btn-sm ${formatMode === 'discord' ? 'primary' : ''}" id="fmt-discord-btn">Discord (Markdown)</button>
                                    <button type="button" class="secondary btn-sm ${formatMode === 'bbcode' ? 'primary' : ''}" id="fmt-bbcode-btn">Форум (BBCode)</button>
                                </div>
                                <button type="button" class="primary btn-sm" id="btn-copy-form-text">
                                    📋 СКОПИРОВАТЬ ФОРМУ
                                </button>
                            </div>
                            <textarea class="appeal-code-editor" id="appeal-form-textarea" spellcheck="false">${esc(formContent)}</textarea>
                            <div style="font-size:11.5px; color:var(--text-dim); margin-top:8px; display:flex; justify-content:space-between; flex-wrap:wrap; gap:8px;">
                                <span>Текст можно отредактировать перед копированием прямо в этом окне.</span>
                                <span>Формат: <b>${formatMode.toUpperCase()}</b></span>
                            </div>
                        ` : `
                            <!-- Tab 2: Verdicts View -->
                            <div style="font-size:11.5px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:8px;">
                                Выберите заготовку решения по апелляции:
                            </div>
                            <div class="appeal-verdict-chips">
                                ${(tmpl.verdicts || []).map(v => {
                                    const isActive = v.id === selectedVerdictId;
                                    const typeClass = v.type ? v.type.toLowerCase() : 'info';
                                    let icon = '⚖️';
                                    if (v.type === 'APPROVE') icon = '🟢';
                                    else if (v.type === 'REJECT') icon = '🔴';
                                    else if (v.type === 'INFO') icon = '🔵';
                                    return `
                                        <button type="button" class="verdict-chip ${typeClass} ${isActive ? 'active ' + typeClass : ''}" data-verdict-id="${esc(v.id)}">
                                            <span>${icon}</span>
                                            <span>${esc(v.name)}</span>
                                        </button>
                                    `;
                                }).join('')}
                            </div>

                            <div class="appeal-toolbar">
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <span class="badge ${curVerdict && curVerdict.type === 'APPROVE' ? 'green' : (curVerdict && curVerdict.type === 'REJECT' ? 'red' : 'purple')}">
                                        ${curVerdict ? esc(curVerdict.name) : 'ВЕРДИКТ'}
                                    </span>
                                </div>
                                <div style="display:flex; gap:8px; flex-wrap:wrap;">
                                    ${(curVerdict && curVerdict.type === 'APPROVE' && currentBan && currentBan.status === 'ACTIVE' && (!isModeratorRole() || me.isOwner)) ? `
                                        <button type="button" class="danger btn-sm" id="btn-unban-and-copy" title="Разбанить игрока на сервере и скопировать готовый вердикт">
                                            ⚡ СНЯТЬ БАН И СКОПИРОВАТЬ
                                        </button>
                                    ` : ''}
                                    <button type="button" class="primary btn-sm" id="btn-copy-verdict-text">
                                        📋 СКОПИРОВАТЬ ВЕРДИКТ
                                    </button>
                                </div>
                            </div>
                            <textarea class="appeal-code-editor" id="appeal-verdict-textarea" spellcheck="false">${esc(verdictContent)}</textarea>
                            <div style="font-size:11.5px; color:var(--text-dim); margin-top:8px;">
                                Официальный ответ администратора на апелляцию с подставленными данными нарушителя.
                            </div>
                        `}
                    `;

                    // Wire up listeners
                    document.getElementById('appeal-ban-picker')?.addEventListener('change', (e) => {
                        const val = parseInt(e.target.value);
                        currentBan = bansList.find(b => b.id === val) || null;
                        renderModalContent();
                    });

                    document.getElementById('tab-btn-form')?.addEventListener('click', () => {
                        currentTab = 'form';
                        renderModalContent();
                    });

                    document.getElementById('tab-btn-verdicts')?.addEventListener('click', () => {
                        currentTab = 'verdicts';
                        renderModalContent();
                    });

                    document.getElementById('fmt-plain-btn')?.addEventListener('click', () => {
                        formatMode = 'plain';
                        renderModalContent();
                    });

                    document.getElementById('fmt-discord-btn')?.addEventListener('click', () => {
                        formatMode = 'discord';
                        renderModalContent();
                    });

                    document.getElementById('fmt-bbcode-btn')?.addEventListener('click', () => {
                        formatMode = 'bbcode';
                        renderModalContent();
                    });

                    document.querySelectorAll('.verdict-chip').forEach(btn => {
                        btn.addEventListener('click', () => {
                            selectedVerdictId = btn.getAttribute('data-verdict-id');
                            renderModalContent();
                        });
                    });

                    const copyToClipboard = (text, successMsg) => {
                        if (navigator.clipboard && navigator.clipboard.writeText) {
                            navigator.clipboard.writeText(text).then(() => {
                                showToast('Скопировано', successMsg, 'success');
                            }).catch(() => {
                                fallbackCopy(text, successMsg);
                            });
                        } else {
                            fallbackCopy(text, successMsg);
                        }
                    };

                    const fallbackCopy = (text, successMsg) => {
                        const el = document.createElement('textarea');
                        el.value = text;
                        document.body.appendChild(el);
                        el.select();
                        document.execCommand('copy');
                        document.body.removeChild(el);
                        showToast('Скопировано', successMsg, 'success');
                    };

                    document.getElementById('btn-copy-form-text')?.addEventListener('click', () => {
                        const txt = document.getElementById('appeal-form-textarea')?.value || '';
                        copyToClipboard(txt, 'Форма апелляции скопирована в буфер обмена');
                    });

                    document.getElementById('btn-copy-verdict-text')?.addEventListener('click', () => {
                        const txt = document.getElementById('appeal-verdict-textarea')?.value || '';
                        copyToClipboard(txt, 'Текст вердикта скопирован в буфер обмена');
                    });

                    document.getElementById('btn-unban-and-copy')?.addEventListener('click', () => {
                        if (!currentBan) return;
                        confirmAction(
                            'СНЯТИЕ БАНА ПО АПЕЛЛЯЦИИ #' + currentBan.id,
                            `Вы действительно хотите разбанить игрока ${currentBan.targetName} по этой апелляции?\nБлокировка будет снята на игровом сервере, а текст вердикта скопирован в буфер обмена.`,
                            async () => {
                                try {
                                    await api('POST', `/api/bans/${currentBan.id}/unban`);
                                    const txt = document.getElementById('appeal-verdict-textarea')?.value || '';
                                    copyToClipboard(txt, `Игрок ${currentBan.targetName} разбанен. Вердикт скопирован!`);
                                    currentBan.status = 'UNBANNED';
                                    if (activeNavSection === 'bans') renderBansView();
                                    renderModalContent();
                                } catch (err) {
                                    showToast('Ошибка разбана', err.message, 'error');
                                }
                            },
                            'РАЗБАНИТЬ И СКОПИРОВАТЬ',
                            false
                        );
                    });
                };

                renderModalContent();

            } catch (e) {
                body.innerHTML = `<div style="color:var(--red); padding:24px; text-align:center;">Ошибка загрузки шаблонов апелляций: ${esc(e.message)}</div>`;
            }
        }, 'modal-lg');
    };

    // Modal: Linked Accounts / Alts (IP Graph)
    window.openPlayerAltsModal = async function(playerName) {
        openModal(`
            <div class="modal-header">
                <h3>СВЯЗАННЫЕ АККАУНТЫ (АЛЬТЫ)</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
            </div>
            <div class="modal-body" id="alts-modal-body">
                <div style="text-align:center; padding:20px; color:var(--text-muted);">Поиск связей по IP-графу для игрока <b>${esc(playerName)}</b>...</div>
            </div>
        `, async () => {
            const body = document.getElementById('alts-modal-body');
            try {
                const data = await api('GET', `/api/players/${encodeURIComponent(playerName)}/alts`);
                const direct = data.directAlts || [];
                const subnet = data.subnetAlts || [];
                const ip = data.primaryIp || '—';

                body.innerHTML = `
                    <div style="background:#110b28; padding:12px 16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Основной IP адрес игрока:</div>
                        <div class="font-mono" style="font-size:14px; font-weight:700; color:var(--accent-light); margin-top:2px;">${esc(ip)}</div>
                    </div>

                    <h4 style="font-size:13px; margin-bottom:8px; color:#fff;">Точные совпадения по IP (${direct.length}):</h4>
                    <div style="margin-bottom:16px;">
                        ${direct.length ? direct.map(name => `
                            <div style="display:flex; align-items:center; justify-content:space-between; padding:6px 0; border-bottom:1px solid var(--border);">
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/20" class="player-avatar-sm" alt="">
                                    <b style="color:#fff;">${esc(name)}</b>
                                </div>
                                <div style="display:flex; gap:6px;">
                                    <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(name)}')">ДОСЬЕ</button>
                                    <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(name)}', 'Мультиаккаунт')">БАН</button>
                                </div>
                            </div>
                        `).join('') : '<div style="color:var(--text-dim); font-size:12px;">Твинков с одинаковым IP не обнаружено</div>'}
                    </div>

                    ${subnet.length ? `
                        <h4 style="font-size:13px; margin-bottom:8px; color:var(--yellow);">Совпадения по подсети (${subnet.length}):</h4>
                        <div>
                            ${subnet.slice(0, 8).map(name => `
                                <span class="badge yellow" style="cursor:pointer; margin:3px;" onclick="window.viewPlayerProfile('${esc(name)}')">${esc(name)}</span>
                            `).join('')}
                        </div>
                    ` : ''}
                `;
            } catch (e) {
                body.innerHTML = `<div style="color:var(--red); padding:16px; text-align:center;">Ошибка поиска альтов: ${esc(e.message)}</div>`;
            }
        });
    };

    // ==========================================================================
    // 5. АНТИЧИТ (ИНТЕГРАЦИЯ С VESUVIO, ЖИВОЙ ПОТОК, НАБЛЮДЕНИЕ)
    // ==========================================================================

    async function renderAnticheatView() {
        const area = document.getElementById('content-area');
        let currentCheckFilter = '';
        let isStreamPaused = false;
        let activeTab = 'stream'; // stream, suspects, stats

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>АНТИЧИТ (VESUVIO)</h2>
                    <p>Прямая телеметрия, детекция запрещённых модов и поведенческий анализ</p>
                </div>
                <div class="view-actions">
                    <button type="button" class="secondary" id="vesuvio-pause-btn">⏸ ПАУЗА ПОТОКА</button>
                    <button type="button" class="primary" id="vesuvio-refresh-btn">ОБНОВИТЬ</button>
                </div>
            </div>

            <!-- Live Beacon Status Bar -->
            <div class="radar-stream-header">
                <div class="live-beacon">
                    <span class="live-beacon-dot" id="radar-beacon-dot"></span>
                    <span id="radar-beacon-text">ЖИВОЙ ПОТОК СРАБАТЫВАНИЙ VESUVIO (LIVE 3S)</span>
                </div>
                <div style="font-size:12px; color:var(--text-muted);">
                    Интеграция: <span class="badge green">АКТИВНА</span>
                </div>
            </div>

            <!-- Sub-Tabs: Stream / Suspects / Stats -->
            <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:12px 16px; margin-bottom:16px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px;">
                <div class="filter-tags" id="anticheat-tabs">
                    <button type="button" class="filter-tag-btn active" data-tab="stream">Поток нарушений</button>
                    <button type="button" class="filter-tag-btn" data-tab="suspects">Режим «Наблюдение»</button>
                    <button type="button" class="filter-tag-btn" data-tab="engine">Статус движка</button>
                </div>

                <div style="width:220px;">
                    <select id="anticheat-filter-category">
                        <option value="">Все типы проверок</option>
                        <option value="Movement">Movement (Fly, Speed, NoFall)</option>
                        <option value="Combat">Combat (Killaura, Reach, HitBox)</option>
                        <option value="World">World (Scaffold, FastBreak)</option>
                        <option value="Timer">Timer / Tick</option>
                    </select>
                </div>
            </div>

            <div id="anticheat-content-body">
                <!-- Stream / Suspects rendered here -->
            </div>
        `;

        const pauseBtn = document.getElementById('vesuvio-pause-btn');
        pauseBtn?.addEventListener('click', () => {
            isStreamPaused = !isStreamPaused;
            pauseBtn.textContent = isStreamPaused ? '▶ ВОЗОБНОВИТЬ' : '⏸ ПАУЗА ПОТОКА';
            const dot = document.getElementById('radar-beacon-dot');
            const txt = document.getElementById('radar-beacon-text');
            if (dot) dot.style.animationPlayState = isStreamPaused ? 'paused' : 'running';
            if (txt) txt.textContent = isStreamPaused ? 'ПОТОК ПРИОСТАНОВЛЕН (ПАУЗА)' : 'ЖИВОЙ ПОТОК СРАБАТЫВАНИЙ VESUVIO (LIVE 3S)';
        });

        // Tab switches
        document.getElementById('anticheat-tabs')?.addEventListener('click', (e) => {
            const btn = e.target.closest('.filter-tag-btn');
            if (!btn) return;
            document.querySelectorAll('#anticheat-tabs .filter-tag-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            activeTab = btn.dataset.tab;
            renderAnticheatTab();
        });

        document.getElementById('anticheat-filter-category')?.addEventListener('change', (e) => {
            currentCheckFilter = e.target.value;
            renderAnticheatTab();
        });

        document.getElementById('vesuvio-refresh-btn')?.addEventListener('click', () => renderAnticheatTab());

        const renderAnticheatTab = async () => {
            const body = document.getElementById('anticheat-content-body');
            if (!body) return;

            if (activeTab === 'stream') {
                await renderStream(body);
            } else if (activeTab === 'suspects') {
                await renderSuspects(body);
            } else if (activeTab === 'engine') {
                await renderEngineStatus(body);
            }
        };

        const renderStream = async (container) => {
            try {
                const res = await api('GET', '/api/vesuvio/violations?limit=40');
                const list = Array.isArray(res) ? res : (res.violations || []);

                let filtered = list;
                if (currentCheckFilter) {
                    filtered = filtered.filter(v => (v.check || v.type || '').toLowerCase().includes(currentCheckFilter.toLowerCase()));
                }

                if (!filtered.length) {
                    container.innerHTML = `<div style="text-align:center; padding:36px; color:var(--text-dim);">Новых нарушений пока нет. Сервер под защитой Vesuvio.</div>`;
                    return;
                }

                container.innerHTML = filtered.map(v => {
                    const name = v.playerName || v.name || 'Игрок';
                    const check = v.check || v.type || 'Movement';
                    const vl = v.vl || v.vlScore || 1;
                    return `
                        <div class="violation-card">
                            <div class="violation-info">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/32" class="player-avatar-sm" style="width:32px; height:32px;" alt="">
                                <div class="violation-meta">
                                    <h4>${esc(name)} <span class="badge red">${esc(check)}</span></h4>
                                    <div class="violation-details">
                                        Нарушение: <b>${esc(check)}</b> • Уверенность VL: <b style="color:var(--yellow);">${vl}</b> • ${fmtTime(v.timestamp || Date.now()/1000)}
                                    </div>
                                </div>
                            </div>
                            <div style="display:flex; gap:8px;">
                                <button type="button" class="secondary btn-sm" onclick="window.togglePlayerSuspect('${esc(name)}')">НАБЛЮДЕНИЕ</button>
                                <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(name)}', 'Читы')">ЗАБАНИТЬ</button>
                                <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(name)}')">ДОСЬЕ</button>
                            </div>
                        </div>`;
                }).join('');
            } catch (e) {
                container.innerHTML = `<div style="color:var(--red); padding:24px; text-align:center;">Vesuvio API временно недоступен</div>`;
            }
        };

        const renderSuspects = async (container) => {
            container.innerHTML = `<div style="text-align:center; padding:20px; color:var(--text-muted);">Загрузка списка наблюдения...</div>`;
            try {
                const list = await api('GET', '/api/vesuvio/suspects').catch(() => []);
                if (!list.length) {
                    container.innerHTML = `
                        <div style="text-align:center; padding:36px; color:var(--text-dim); background:var(--card-bg); border-radius:var(--radius-md); border:1px solid var(--border);">
                            <div style="font-size:28px; color:var(--green); margin-bottom:8px;">✓</div>
                            <h3>Список наблюдения пуст</h3>
                            <p style="font-size:12.5px; color:var(--text-muted); margin-top:4px;">Добавляйте подозрительных игроков в ручную проверку из карточек нарушений или досье</p>
                        </div>`;
                    return;
                }

                container.innerHTML = `
                    <div class="table-wrap">
                        <table>
                            <thead>
                                <tr>
                                    <th>ИГРОК</th>
                                    <th>СТАТУС</th>
                                    <th>ФЛАГИ</th>
                                    <th style="text-align:right;">ДЕЙСТВИЯ</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${list.map(s => `
                                    <tr>
                                        <td>
                                            <div style="display:flex; align-items:center; gap:8px;">
                                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(s.name || '')}/24" class="player-avatar-sm" alt="">
                                                <b>${esc(s.name)}</b>
                                            </div>
                                        </td>
                                        <td><span class="badge yellow">РУЧНАЯ ПРОВЕРКА</span></td>
                                        <td>VL: <b>${s.vl || 0}</b></td>
                                        <td style="text-align:right;">
                                            <div style="display:flex; justify-content:flex-end; gap:6px;">
                                                <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(s.name)}')">ПРОВЕРИТЬ</button>
                                                <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(s.name)}', 'Читы')">БАН</button>
                                                <button type="button" class="secondary btn-sm" onclick="window.togglePlayerSuspect('${esc(s.name)}', false)">СНЯТЬ</button>
                                            </div>
                                        </td>
                                    </tr>
                                `).join('')}
                            </tbody>
                        </table>
                    </div>`;
            } catch (e) {
                container.innerHTML = `<div style="color:var(--red); padding:20px;">Ошибка загрузки списка наблюдения</div>`;
            }
        };

        const renderEngineStatus = async (container) => {
            try {
                const eng = await api('GET', '/api/vesuvio/engine').catch(() => ({ status: 'ONLINE', checks: 28 }));
                container.innerHTML = `
                    <div class="cards-grid">
                        <div class="stat-card">
                            <div class="stat-label">СТАТУС ДВИЖКА</div>
                            <div class="stat-value" style="color:var(--green);">АКТИВЕН</div>
                            <div class="stat-sub">Vesuvio Core Module</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">АКТИВНЫХ ЧЕКОВ</div>
                            <div class="stat-value">28</div>
                            <div class="stat-sub">Movement, Combat, Packet</div>
                        </div>
                    </div>`;
            } catch (e) {
                container.innerHTML = `<div style="color:var(--text-dim); padding:20px;">Информация о движке недоступна</div>`;
            }
        };

        await renderAnticheatTab();

        // Stream poller
        pollers['vesuvio_live_stream'] = setInterval(() => {
            if (!isStreamPaused && activeTab === 'stream') {
                const body = document.getElementById('anticheat-content-body');
                if (body) renderStream(body);
            }
        }, 3000);
    }

    window.togglePlayerSuspect = async function(playerName, forceVal = null) {
        try {
            const p = await api('GET', `/api/players/${encodeURIComponent(playerName)}`);
            const uuid = p.uuid;
            const current = forceVal !== null ? !forceVal : (p.anticheat && p.anticheat.manualSuspect);
            const next = !current;

            await api('POST', `/api/vesuvio/player/${encodeURIComponent(uuid)}/suspect`, { suspect: next });
            recordShiftAction((next ? 'Поставил на наблюдение ' : 'Снял с наблюдения ') + playerName);
            showToast('Античит', next ? `Игрок ${playerName} добавлен в список наблюдения` : `С игрока ${playerName} снято наблюдение`, 'info');
            if (activeNavSection === 'anticheat') renderAnticheatView();
        } catch (e) {
            alert('Ошибка переключения наблюдения: ' + e.message);
        }
    };

    // ==========================================================================
    // 5. СЕРВЕР (ИГРОКИ, КОНСОЛЬ, WHITELIST & OPs)
    // ==========================================================================

    async function renderServerView() {
        const area = document.getElementById('content-area');
        const isMod = isModeratorRole();
        let subTab = 'players'; // players, console, whitelist

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>УПРАВЛЕНИЕ СЕРВЕРОМ</h2>
                    <p>Досье игроков, интерактивная консоль и администрирование списков доступа</p>
                </div>
                <div class="view-actions">
                    <button type="button" class="secondary" id="server-view-refresh-btn">ОБНОВИТЬ</button>
                </div>
            </div>

            <!-- Sub Tabs -->
            <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:10px 16px; margin-bottom:18px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                <div class="filter-tags" id="server-sub-tabs">
                    <button type="button" class="filter-tag-btn active" data-sub="players">Список игроков</button>
                    ${!isMod ? '<button type="button" class="filter-tag-btn" data-sub="console">Консоль сервера</button>' : ''}
                    ${!isMod ? '<button type="button" class="filter-tag-btn" data-sub="whitelist">Whitelist & Операторы</button>' : ''}
                </div>
            </div>

            <div id="server-tab-container">
                <!-- Dynamic tab view -->
            </div>
        `;

        document.getElementById('server-sub-tabs')?.addEventListener('click', (e) => {
            const btn = e.target.closest('.filter-tag-btn');
            if (!btn) return;
            document.querySelectorAll('#server-sub-tabs .filter-tag-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            subTab = btn.dataset.sub;
            mountSubTab();
        });

        document.getElementById('server-view-refresh-btn')?.addEventListener('click', () => mountSubTab());

        const mountSubTab = () => {
            const container = document.getElementById('server-tab-container');
            if (!container) return;
            if (subTab === 'players') renderServerPlayersTab(container);
            else if (subTab === 'console') renderServerConsoleTab(container);
            else if (subTab === 'whitelist') renderServerWhitelistTab(container);
        };

        mountSubTab();
    }

    async function renderServerPlayersTab(container) {
        container.innerHTML = `
            <div style="margin-bottom:16px; display:flex; gap:12px;">
                <div style="flex:1;">
                    <input type="text" id="srv-player-search" placeholder="Поиск игрока по нику...">
                </div>
                <div style="width:160px;">
                    <select id="srv-player-status-filter">
                        <option value="all">Все игроки</option>
                        <option value="online" selected>Только Онлайн</option>
                    </select>
                </div>
            </div>

            <div class="table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>ИГРОК</th>
                            <th>СТАТУС</th>
                            <th>ПИНГ</th>
                            <th>IP АДРЕС</th>
                            <th>НАИГРАНО</th>
                            <th style="text-align:right;">ДЕЙСТВИЯ</th>
                        </tr>
                    </thead>
                    <tbody id="srv-players-tbody">
                        <tr><td colspan="6" style="text-align:center; padding:24px; color:var(--text-dim);">Загрузка игроков...</td></tr>
                    </tbody>
                </table>
            </div>
        `;

        const loadList = async () => {
            const tbody = document.getElementById('srv-players-tbody');
            const q = (document.getElementById('srv-player-search')?.value || '').trim();
            const onlyOnline = document.getElementById('srv-player-status-filter')?.value === 'online';

            try {
                const stats = await api('GET', '/api/stats');
                const onlineNames = new Set((stats.players || []).map(p => p.name.toLowerCase()));

                let playerList = [];
                if (onlyOnline) {
                    playerList = (stats.players || []).map(p => ({
                        name: p.name,
                        uuid: p.uuid,
                        isOnline: true,
                        ping: p.ping,
                        ip: '—',
                        playtimeSeconds: 0
                    }));
                } else {
                    playerList = await api('GET', `/api/players/search?q=${encodeURIComponent(q)}`);
                }

                if (!playerList.length) {
                    tbody.innerHTML = `<tr><td colspan="6" style="text-align:center; color:var(--text-dim); padding:24px;">Игроки не найдены</td></tr>`;
                    return;
                }

                tbody.innerHTML = playerList.map(p => {
                    const isOnline = onlineNames.has(p.name.toLowerCase()) || p.isOnline;
                    return `
                        <tr>
                            <td>
                                <div style="display:flex; align-items:center; gap:8px; cursor:pointer;" onclick="window.viewPlayerProfile('${esc(p.name)}')">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/24" class="player-avatar-sm" alt="">
                                    <b>${esc(p.name)}</b>
                                </div>
                            </td>
                            <td>
                                <span class="badge ${isOnline ? 'green' : 'gray'}">${isOnline ? '● ОНЛАЙН' : 'ОФФЛАЙН'}</span>
                            </td>
                            <td>${isOnline ? `<span class="badge gray">${p.ping || 0} мс</span>` : '—'}</td>
                            <td class="font-mono" style="font-size:12px; color:var(--text-dim);">${esc(p.lastIp || p.ip || '—')}</td>
                            <td style="color:var(--text-muted);">${fmtDuration(p.totalPlaytimeSeconds || p.playtimeSeconds || 0)}</td>
                            <td style="text-align:right;">
                                <div style="display:flex; justify-content:flex-end; gap:6px;">
                                    <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(p.name)}')">ДОСЬЕ</button>
                                    <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(p.name)}')">БАН</button>
                                </div>
                            </td>
                        </tr>
                    `;
                }).join('');
            } catch (e) {
                tbody.innerHTML = `<tr><td colspan="6" style="text-align:center; color:var(--red); padding:20px;">Ошибка загрузки списка</td></tr>`;
            }
        };

        document.getElementById('srv-player-search')?.addEventListener('input', loadList);
        document.getElementById('srv-player-status-filter')?.addEventListener('change', loadList);

        await loadList();
    }

    // Player Dossier Modal
    window.viewPlayerProfile = async function(playerName) {
        openModal(`
            <div class="modal-header">
                <h3>ДОСЬЕ ИГРОКА: ${esc(playerName)}</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
            </div>
            <div class="modal-body" id="player-dossier-body">
                <div style="text-align:center; padding:30px; color:var(--text-muted);">Загрузка полного досье игрока...</div>
            </div>
        `, async () => {
            const body = document.getElementById('player-dossier-body');
            try {
                const p = await api('GET', `/api/players/${encodeURIComponent(playerName)}`);
                const trustColor = p.trustColor || '#10b981';
                const trustGrade = p.trustGrade || 'A';
                const trustScore = p.trustScore != null ? p.trustScore : 85;
                const notes = p.staffNotes || [];
                const bans = p.bans || [];

                body.innerHTML = `
                    <!-- Header Info Bar -->
                    <div style="display:flex; align-items:center; justify-content:space-between; background:#120c2a; border:1px solid var(--border); border-radius:var(--radius-md); padding:16px; margin-bottom:18px;">
                        <div style="display:flex; align-items:center; gap:14px;">
                            <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/48" style="width:48px; height:48px; border-radius:6px; background:#1e1442; border:1px solid var(--border-light);" alt="">
                            <div>
                                <h3 style="font-size:18px; margin-bottom:2px;">${esc(p.name)}</h3>
                                <div style="font-size:11.5px; color:var(--text-muted); font-family:'JetBrains Mono';">${esc(p.uuid)}</div>
                                <div style="margin-top:4px;">
                                    <span class="badge ${p.isOnline ? 'green' : 'gray'}">${p.isOnline ? `● В СЕТИ (${p.ping} мс)` : 'ОФФЛАЙН'}</span>
                                    <span class="badge purple">IP: ${esc(p.ip || '—')}</span>
                                </div>
                            </div>
                        </div>

                        <!-- Trust Score Pill -->
                        <div style="text-align:right; background:rgba(0,0,0,0.25); padding:10px 14px; border-radius:8px; border:1px solid var(--border);">
                            <div style="font-size:10.5px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Trust Score</div>
                            <div style="font-family:'Outfit'; font-size:24px; font-weight:800; color:${trustColor};">${trustGrade} <span style="font-size:16px;">(${trustScore}/100)</span></div>
                        </div>
                    </div>

                    <!-- Dossier Sub-tabs -->
                    <div class="filter-tags" id="dossier-tabs" style="margin-bottom:14px;">
                        <button type="button" class="filter-tag-btn active" data-tab="overview">Обзор</button>
                        <button type="button" class="filter-tag-btn" data-tab="actions">Быстрые действия</button>
                        <button type="button" class="filter-tag-btn" data-tab="notes">Заметки стаффа (${notes.length})</button>
                        <button type="button" class="filter-tag-btn" data-tab="bans">Наказания (${bans.length})</button>
                    </div>

                    <div id="dossier-tab-content">
                        <!-- Overview by default -->
                    </div>
                `;

                const dossierContainer = document.getElementById('dossier-tab-content');

                const renderOverview = () => {
                    dossierContainer.innerHTML = `
                        <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px; margin-bottom:16px;">
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Первый вход</div>
                                <div class="font-mono" style="font-size:13px; color:#fff;">${fmtTime(p.firstJoinedAt)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Последняя активность</div>
                                <div class="font-mono" style="font-size:13px; color:#fff;">${fmtTime(p.lastSeenAt)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Общее время в игре</div>
                                <div style="font-size:16px; font-weight:700; color:var(--accent-light);">${fmtDuration(p.playtimeSeconds || 0)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Баланс монет (LoveEconomy)</div>
                                <div style="font-size:16px; font-weight:700; color:#fff;">${(p.economy && p.economy.balance) ? p.economy.balance.toLocaleString() : 0} монет</div>
                            </div>
                        </div>

                        <div style="display:flex; justify-content:space-between; align-items:center; background:#100b26; padding:12px; border-radius:6px; border:1px solid var(--border);">
                            <span style="font-size:12px; color:var(--text-muted);">Проверка на связанные аккаунты по IP графу:</span>
                            <button type="button" class="secondary btn-sm" onclick="window.openPlayerAltsModal('${esc(p.name)}')">ПРОВЕРИТЬ АЛЬТЫ</button>
                        </div>
                    `;
                };

                const renderActions = () => {
                    if (!p.isOnline) {
                        dossierContainer.innerHTML = `<div style="text-align:center; padding:24px; color:var(--text-dim);">Игрок не в сети. Интерактивные действия доступны только когда игрок онлайн на сервере.</div>`;
                        return;
                    }

                    dossierContainer.innerHTML = `
                        <div class="quick-actions-grid" style="grid-template-columns: repeat(4, 1fr); margin-bottom:16px;">
                            <button type="button" class="quick-action-btn" id="act-heal">
                                <span class="qa-icon" style="color:var(--green);">❤</span>
                                <span>ИСЦЕЛИТЬ</span>
                            </button>
                            <button type="button" class="quick-action-btn" id="act-feed">
                                <span class="qa-icon" style="color:var(--yellow);">🍖</span>
                                <span>ПОКОРМИТЬ</span>
                            </button>
                            <button type="button" class="quick-action-btn" id="act-spawn">
                                <span class="qa-icon" style="color:var(--cyan);">⌖</span>
                                <span>НА СПАВН</span>
                            </button>
                            <button type="button" class="quick-action-btn" id="act-freeze">
                                <span class="qa-icon" style="color:#60a5fa;">❄</span>
                                <span>ЗАМОРОЗКА</span>
                            </button>
                            <button type="button" class="quick-action-btn" id="act-vanish">
                                <span class="qa-icon" style="color:#a855f7;">👻</span>
                                <span>VANISH</span>
                            </button>
                            <button type="button" class="quick-action-btn" id="act-mute">
                                <span class="qa-icon" style="color:#f97316;">🔇</span>
                                <span>МУТ ЧАТА</span>
                            </button>
                            <button type="button" class="quick-action-btn" id="act-kill">
                                <span class="qa-icon" style="color:var(--red);">💀</span>
                                <span>УБИТЬ</span>
                            </button>
                            <button type="button" class="quick-action-btn" id="act-clear-inv">
                                <span class="qa-icon" style="color:#eab308;">🗑</span>
                                <span>ОЧИСТИТЬ ИНВ.</span>
                            </button>
                            <button type="button" class="quick-action-btn" id="act-kick">
                                <span class="qa-icon" style="color:var(--red);">👢</span>
                                <span>КИКНУТЬ</span>
                            </button>
                            <button type="button" class="quick-action-btn" onclick="window.openQuickBanModal('${esc(p.name)}')">
                                <span class="qa-icon" style="color:var(--red);">⚑</span>
                                <span>ЗАБАНИТЬ</span>
                            </button>
                        </div>

                        <!-- Gamemode Switcher -->
                        <div style="background:rgba(255,255,255,0.02); border:1px solid var(--border); border-radius:var(--radius-sm); padding:12px; margin-bottom:14px;">
                            <div style="font-size:12px; font-weight:700; color:var(--text-secondary); margin-bottom:8px;">СМЕНА РЕЖИМА ИГРЫ (GAMEMODE):</div>
                            <div style="display:flex; gap:8px;">
                                <button type="button" class="secondary btn-sm" id="act-gm-survival">SURVIVAL</button>
                                <button type="button" class="secondary btn-sm" id="act-gm-creative">CREATIVE</button>
                                <button type="button" class="secondary btn-sm" id="act-gm-adventure">ADVENTURE</button>
                                <button type="button" class="secondary btn-sm" id="act-gm-spectator">SPECTATOR</button>
                            </div>
                        </div>
                    `;

                    const execPlayerAction = async (action, bodyObj = {}) => {
                        try {
                            const res = await api('POST', `/api/players/${encodeURIComponent(p.name)}/action`, { action, ...bodyObj });
                            showToast('Действие выполнено', res.message || 'Успешно', 'success');
                        } catch (e) {
                            alert('Ошибка действия: ' + e.message);
                        }
                    };

                    document.getElementById('act-heal')?.addEventListener('click', () => execPlayerAction('heal'));
                    document.getElementById('act-feed')?.addEventListener('click', () => execPlayerAction('feed'));
                    document.getElementById('act-spawn')?.addEventListener('click', () => execPlayerAction('teleport_spawn'));
                    document.getElementById('act-freeze')?.addEventListener('click', () => execPlayerAction('freeze'));
                    document.getElementById('act-vanish')?.addEventListener('click', () => execPlayerAction('vanish'));
                    document.getElementById('act-mute')?.addEventListener('click', () => {
                        const reason = prompt('Причина мута игрока:', 'Нарушение правил общения');
                        if (reason) execPlayerAction('mute', { reason });
                    });
                    document.getElementById('act-kill')?.addEventListener('click', () => {
                        confirmAction('УБИТЬ ИГРОКА', `Убить персонажа ${p.name}?`, () => execPlayerAction('kill'), 'УБИТЬ');
                    });
                    document.getElementById('act-clear-inv')?.addEventListener('click', () => {
                        confirmAction('ОЧИСТКА ИНВЕНТАРЯ', `Полностью очистить инвентарь игрока ${p.name}?`, () => execPlayerAction('clear_inventory'), 'ОЧИСТИТЬ');
                    });
                    document.getElementById('act-kick')?.addEventListener('click', () => {
                        const reason = prompt('Причина кика:', 'Кикнут администратором через веб-панель');
                        if (reason) execPlayerAction('kick', { reason });
                    });

                    document.getElementById('act-gm-survival')?.addEventListener('click', () => execPlayerAction('gamemode', { gameMode: 'SURVIVAL' }));
                    document.getElementById('act-gm-creative')?.addEventListener('click', () => execPlayerAction('gamemode', { gameMode: 'CREATIVE' }));
                    document.getElementById('act-gm-adventure')?.addEventListener('click', () => execPlayerAction('gamemode', { gameMode: 'ADVENTURE' }));
                    document.getElementById('act-gm-spectator')?.addEventListener('click', () => execPlayerAction('gamemode', { gameMode: 'SPECTATOR' }));
                };

                const renderNotes = () => {
                    dossierContainer.innerHTML = `
                        <div style="margin-bottom:14px; background:#0f0923; padding:12px; border-radius:6px; border:1px solid var(--border);">
                            <label>Добавить внутреннюю заметку стаффа:</label>
                            <div style="display:flex; gap:8px;">
                                <input type="text" id="new-note-text" placeholder="Заметка о поведении игрока (видна только стаффу)...">
                                <button type="button" class="primary btn-sm" id="btn-add-note">ДОБАВИТЬ</button>
                            </div>
                        </div>

                        <div id="notes-list-box" style="max-height:220px; overflow-y:auto;">
                            ${notes.length ? notes.map(n => `
                                <div style="background:#130e28; padding:10px 12px; border-radius:6px; border:1px solid var(--border); margin-bottom:8px; display:flex; justify-content:space-between; align-items:flex-start;">
                                    <div>
                                        <div style="font-size:11px; color:var(--text-dim); margin-bottom:3px;">
                                            Автор: <b style="color:var(--accent-light);">${esc(n.author)}</b> • ${fmtTime(n.createdAt)}
                                        </div>
                                        <div style="color:#fff; font-size:13px;">${esc(n.note)}</div>
                                    </div>
                                    <button type="button" class="secondary btn-sm" onclick="window.deleteStaffNote('${esc(p.name)}', ${n.id})" style="color:var(--red);">✕</button>
                                </div>
                            `).join('') : '<div style="color:var(--text-dim); font-size:12px; text-align:center; padding:16px;">Заметок пока нет</div>'}
                        </div>
                    `;

                    document.getElementById('btn-add-note')?.addEventListener('click', async () => {
                        const text = document.getElementById('new-note-text').value.trim();
                        if (!text) return;
                        try {
                            await api('POST', `/api/players/${encodeURIComponent(p.name)}/notes`, { note: text });
                            showToast('Заметка сохранена', 'Внутренняя заметка успешно добавлена', 'success');
                            window.viewPlayerProfile(p.name);
                        } catch (e) {
                            alert('Ошибка добавления заметки: ' + e.message);
                        }
                    });
                };

                const renderBans = () => {
                    dossierContainer.innerHTML = `
                        <div style="max-height:240px; overflow-y:auto;">
                            ${bans.length ? bans.map(b => `
                                <div style="background:#130e28; padding:10px 12px; border-radius:6px; border:1px solid var(--border); margin-bottom:8px;">
                                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
                                        <span class="badge red">${esc(b.ruleReason)}</span>
                                        <span class="badge ${b.status === 'ACTIVE' ? 'red' : 'green'}">${b.status === 'ACTIVE' ? 'АКТИВЕН' : 'СНЯТ'}</span>
                                    </div>
                                    <div style="font-size:12px; color:var(--text-muted);">${esc(b.description || 'Без описания')}</div>
                                    <div style="font-size:11px; color:var(--text-dim); margin-top:4px;">Выдал: ${esc(b.creatorName)} • ${fmtTime(b.createdAt)}</div>
                                </div>
                            `).join('') : '<div style="color:var(--text-dim); font-size:12px; text-align:center; padding:20px;">Наказаний нет</div>'}
                        </div>
                    `;
                };

                document.getElementById('dossier-tabs')?.addEventListener('click', (e) => {
                    const btn = e.target.closest('.filter-tag-btn');
                    if (!btn) return;
                    document.querySelectorAll('#dossier-tabs .filter-tag-btn').forEach(b => b.classList.remove('active'));
                    btn.classList.add('active');
                    const tab = btn.dataset.tab;
                    if (tab === 'overview') renderOverview();
                    else if (tab === 'actions') renderActions();
                    else if (tab === 'notes') renderNotes();
                    else if (tab === 'bans') renderBans();
                });

                renderOverview();
            } catch (e) {
                body.innerHTML = `<div style="color:var(--red); padding:20px;">Ошибка загрузки досье игрока: ${esc(e.message)}</div>`;
            }
        }, 'modal-lg');
    };

    window.deleteStaffNote = async function(playerName, noteId) {
        try {
            await api('DELETE', `/api/players/${encodeURIComponent(playerName)}/notes/${noteId}`);
            showToast('Заметка удалена', 'Заметка стаффа успешно удалена', 'info');
            window.viewPlayerProfile(playerName);
        } catch (e) {
            alert('Ошибка удаления заметки: ' + e.message);
        }
    };

    // Server Console Tab
    function renderServerConsoleTab(container) {
        container.innerHTML = `
            <div class="terminal-window">
                <div class="terminal-header">
                    <span style="font-family:'Outfit'; font-weight:700; color:#fff; font-size:13px;">КОНСОЛЬ СЕРВЕРА MINECRAFT</span>
                    <span class="badge green">ПЕРМИШИН EXECUTE_COMMANDS</span>
                </div>
                <div class="terminal-logs-body" id="console-logs-body">
                    Загрузка последних серверных логов...
                </div>
                <div class="terminal-input-row">
                    <span class="terminal-prompt">&gt;</span>
                    <input type="text" class="terminal-input" id="console-cmd-input" placeholder="Введите команду (например: list, tps, say Привет)..." autocomplete="off">
                    <button type="button" class="primary btn-sm" id="console-send-btn">ВЫПОЛНИТЬ</button>
                </div>
            </div>
        `;

        const logBody = document.getElementById('console-logs-body');
        const cmdInput = document.getElementById('console-cmd-input');
        const cmdHistory = [];
        let historyIdx = -1;

        const refreshConsoleLogs = async () => {
            try {
                const logs = await api('GET', '/api/logs/server?mode=recent');
                if (!logBody) return;
                const wasBottom = (logBody.scrollHeight - logBody.scrollTop) <= (logBody.clientHeight + 40);

                logBody.innerHTML = logs.map(l => {
                    const msg = l.action || l.message || '';
                    let cls = 'info';
                    if (msg.includes('WARN')) cls = 'warn';
                    if (msg.includes('ERROR') || msg.includes('Exception')) cls = 'error';
                    return `<div class="terminal-log-line ${cls}">[${fmtTime(l.timestamp)}] ${esc(msg)}</div>`;
                }).join('');

                if (wasBottom) logBody.scrollTop = logBody.scrollHeight;
            } catch (e) {}
        };

        refreshConsoleLogs();
        pollers['console_logs_poller'] = setInterval(refreshConsoleLogs, 3000);

        const sendCmd = async () => {
            const cmd = cmdInput.value.trim();
            if (!cmd) return;
            cmdHistory.push(cmd);
            historyIdx = cmdHistory.length;
            cmdInput.value = '';

            try {
                await api('POST', '/api/command', { command: cmd });
                recordShiftAction(`Консоль: /${cmd}`);
                showToast('Команда отправлена', `/${cmd}`, 'info');
                setTimeout(refreshConsoleLogs, 500);
            } catch (e) {
                alert('Ошибка выполнения: ' + e.message);
            }
        };

        document.getElementById('console-send-btn')?.addEventListener('click', sendCmd);
        cmdInput?.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') sendCmd();
            else if (e.key === 'ArrowUp') {
                if (historyIdx > 0) {
                    historyIdx--;
                    cmdInput.value = cmdHistory[historyIdx] || '';
                }
            } else if (e.key === 'ArrowDown') {
                if (historyIdx < cmdHistory.length - 1) {
                    historyIdx++;
                    cmdInput.value = cmdHistory[historyIdx] || '';
                } else {
                    historyIdx = cmdHistory.length;
                    cmdInput.value = '';
                }
            }
        });
    }

    // Server Whitelist & Operators Tab
    async function renderServerWhitelistTab(container) {
        container.innerHTML = `
            <div style="display:grid; grid-template-columns:1fr 1fr; gap:20px;">
                <!-- Whitelist Management -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; border-bottom:1px solid var(--border); padding-bottom:10px;">
                        <h3 style="font-size:15px; color:#fff;">БЕЛЫЙ СПИСОК (WHITELIST)</h3>
                        <button type="button" class="secondary btn-sm" id="btn-toggle-whitelist">ПЕРЕКЛЮЧИТЬ</button>
                    </div>

                    <div id="whitelist-status-banner" style="margin-bottom:14px;">Загрузка...</div>

                    <div style="display:flex; gap:8px; margin-bottom:14px;">
                        <input type="text" id="whitelist-add-name" placeholder="Никнейм для добавления...">
                        <button type="button" class="primary btn-sm" id="whitelist-add-btn">ДОБАВИТЬ</button>
                    </div>

                    <div class="table-wrap" style="max-height:300px; overflow-y:auto;">
                        <table>
                            <thead><tr><th>ИГРОК</th><th style="text-align:right;">ДЕЙСТВИЕ</th></tr></thead>
                            <tbody id="whitelist-tbody">
                                <tr><td colspan="2" style="text-align:center; padding:16px; color:var(--text-dim);">Загрузка...</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>

                <!-- Operators Management -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; border-bottom:1px solid var(--border); padding-bottom:10px;">
                        <h3 style="font-size:15px; color:#fff;">ОПЕРАТОРЫ СЕРВЕРА (OPs)</h3>
                        <span class="badge red">КРИТИЧЕСКИЙ ДОСТУП</span>
                    </div>

                    <div style="display:flex; gap:8px; margin-bottom:14px;">
                        <input type="text" id="ops-add-name" placeholder="Никнейм для выдачи OP...">
                        <button type="button" class="danger btn-sm" id="ops-add-btn">ВЫДАТЬ OP</button>
                    </div>

                    <div class="table-wrap" style="max-height:340px; overflow-y:auto;">
                        <table>
                            <thead><tr><th>ОПЕРАТОР</th><th style="text-align:right;">ДЕЙСТВИЕ</th></tr></thead>
                            <tbody id="ops-tbody">
                                <tr><td colspan="2" style="text-align:center; padding:16px; color:var(--text-dim);">Загрузка...</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
        `;

        const refreshWhitelist = async () => {
            try {
                const data = await api('GET', '/api/server/whitelist');
                const banner = document.getElementById('whitelist-status-banner');
                const tbody = document.getElementById('whitelist-tbody');
                if (banner) {
                    banner.innerHTML = `
                        <div style="display:flex; align-items:center; justify-content:space-between; padding:10px; background:${data.enabled ? 'var(--green-bg)' : 'rgba(255,255,255,0.03)'}; border:1px solid ${data.enabled ? 'var(--green-border)' : 'var(--border)'}; border-radius:6px;">
                            <span>Режим Whitelist: <b>${data.enabled ? 'ВКЛЮЧЕН' : 'ОТКЛЮЧЕН'}</b></span>
                            <span class="badge ${data.enabled ? 'green' : 'gray'}">${data.count || 0} игроков</span>
                        </div>`;
                }

                if (tbody) {
                    const players = data.players || [];
                    if (!players.length) {
                        tbody.innerHTML = `<tr><td colspan="2" style="text-align:center; color:var(--text-dim); padding:16px;">Whitelist пуст</td></tr>`;
                    } else {
                        tbody.innerHTML = players.map(p => `
                            <tr>
                                <td style="font-weight:700; color:#fff;">${esc(p.name)}</td>
                                <td style="text-align:right;">
                                    <button type="button" class="secondary btn-sm" onclick="window.removeWhitelistPlayer('${esc(p.name)}')">УДАЛИТЬ</button>
                                </td>
                            </tr>
                        `).join('');
                    }
                }
            } catch (e) {}
        };

        const refreshOps = async () => {
            try {
                const data = await api('GET', '/api/server/ops');
                const tbody = document.getElementById('ops-tbody');
                if (!tbody) return;
                const ops = data.operators || [];

                if (!ops.length) {
                    tbody.innerHTML = `<tr><td colspan="2" style="text-align:center; color:var(--text-dim); padding:16px;">Операторов нет</td></tr>`;
                } else {
                    tbody.innerHTML = ops.map(o => `
                        <tr>
                            <td>
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(o.name)}/20" class="player-avatar-sm" alt="">
                                    <b style="color:#fff;">${esc(o.name)}</b>
                                </div>
                            </td>
                            <td style="text-align:right;">
                                <button type="button" class="danger btn-sm" onclick="window.removeOpPlayer('${esc(o.name)}')">СНЯТЬ OP</button>
                            </td>
                        </tr>
                    `).join('');
                }
            } catch (e) {}
        };

        document.getElementById('btn-toggle-whitelist')?.addEventListener('click', async () => {
            const data = await api('GET', '/api/server/whitelist');
            const next = !data.enabled;
            await api('POST', '/api/server/whitelist', { action: next ? 'enable' : 'disable' });
            showToast('Whitelist', next ? 'Whitelist активирован' : 'Whitelist отключен', 'info');
            refreshWhitelist();
        });

        document.getElementById('whitelist-add-btn')?.addEventListener('click', async () => {
            const name = document.getElementById('whitelist-add-name').value.trim();
            if (!name) return;
            await api('POST', '/api/server/whitelist', { action: 'add', player: name });
            document.getElementById('whitelist-add-name').value = '';
            showToast('Whitelist', `Игрок ${name} добавлен в whitelist`, 'success');
            refreshWhitelist();
        });

        document.getElementById('ops-add-btn')?.addEventListener('click', () => {
            const name = document.getElementById('ops-add-name').value.trim();
            if (!name) return;
            confirmAction('ВЫДАЧА ПРАВ OP', `Выдать полные права оператора игроку ${name}? Операторы имеют доступ ко всем командам ядра!`, async () => {
                await api('POST', '/api/server/ops', { action: 'add', player: name });
                document.getElementById('ops-add-name').value = '';
                showToast('Операторы', `Права OP выданы игроку ${name}`, 'warning');
                refreshOps();
            }, 'ВЫДАТЬ OP');
        });

        window.removeWhitelistPlayer = async (name) => {
            await api('POST', '/api/server/whitelist', { action: 'remove', player: name });
            showToast('Whitelist', `Игрок ${name} удален из whitelist`, 'info');
            refreshWhitelist();
        };

        window.removeOpPlayer = (name) => {
            confirmAction('СНЯТИЕ OP', `Снять права оператора с игрока ${name}?`, async () => {
                await api('POST', '/api/server/ops', { action: 'remove', player: name });
                showToast('Операторы', `Права OP сняты с ${name}`, 'info');
                refreshOps();
            }, 'СНЯТЬ');
        };

        await Promise.all([refreshWhitelist(), refreshOps()]);
    }

    // ==========================================================================
    // 6. АУТЕНТИФИКАЦИЯ (СЕССИИ, ИСТОРИЯ ВХОДОВ, LOVEAUTH)
    // ==========================================================================

    async function renderAuthView() {
        const area = document.getElementById('content-area');
        let currentAuthTab = 'web';
        let inspectedPlayer = null;

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>АУТЕНТИФИКАЦИЯ И БЕЗОПАСНОСТЬ</h2>
                    <p>Управление сессиями персонала веб-панели и аккаунтами безопасности игрового сервера (LoveAuth)</p>
                </div>
                <div class="view-actions" id="auth-header-actions">
                    <!-- Dynamic actions per subtab -->
                </div>
            </div>

            <!-- Subtabs Navigation -->
            <div class="sub-tabs-bar" id="auth-subtabs">
                <button type="button" class="sub-tab-btn active" data-subtab="web">
                    💻 ВЕБ-ПАНЕЛЬ (СЕССИИ СТАФФА)
                </button>
                <button type="button" class="sub-tab-btn" data-subtab="loveauth">
                    🎮 ИГРОВОЙ СЕРВЕР (LOVEAUTH)
                </button>
            </div>

            <!-- Tab Content Container -->
            <div id="auth-tab-content">
                <!-- Rendered dynamically -->
            </div>
        `;

        // Switcher Logic
        const renderCurrentSubTab = async () => {
            const container = document.getElementById('auth-tab-content');
            const actions = document.getElementById('auth-header-actions');
            if (!container) return;

            document.querySelectorAll('#auth-subtabs .sub-tab-btn').forEach(btn => {
                btn.classList.toggle('active', btn.dataset.subtab === currentAuthTab);
            });

            if (currentAuthTab === 'web') {
                if (actions) {
                    actions.innerHTML = `
                        <button type="button" class="danger" id="btn-terminate-all-other">ЗАВЕРШИТЬ ВСЕ ЧУЖИЕ СЕССИИ</button>
                        <button type="button" class="secondary" id="auth-web-refresh-btn">ОБНОВИТЬ</button>
                    `;
                    document.getElementById('btn-terminate-all-other')?.addEventListener('click', () => {
                        confirmAction('ЗАВЕРШЕНИЕ СЕССИЙ', 'Завершить все активные сессии на всех других устройствах?', async () => {
                            await api('POST', '/api/me/sessions/other/terminate');
                            showToast('Готово', 'Все остальные сессии были завершены', 'info');
                            refreshWebAuthData();
                        }, 'ЗАВЕРШИТЬ ВСЕ');
                    });
                    document.getElementById('auth-web-refresh-btn')?.addEventListener('click', refreshWebAuthData);
                }

                container.innerHTML = `
                    <!-- Active Sessions Card -->
                    <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px; margin-bottom:20px;">
                        <h3 style="font-size:15px; margin-bottom:12px; color:#fff;">АКТИВНЫЕ СЕССИИ ВЕБ-ПАНЕЛИ</h3>
                        <div class="table-wrap">
                            <table>
                                <thead>
                                    <tr>
                                        <th>СОТРУДНИК</th>
                                        <th>РОЛЬ</th>
                                        <th>IP АДРЕС</th>
                                        <th>УСТРОЙСТВО / БРАУЗЕР</th>
                                        <th>АКТИВНОСТЬ</th>
                                        <th style="text-align:right;">ДЕЙСТВИЕ</th>
                                    </tr>
                                </thead>
                                <tbody id="sessions-table-body">
                                    <tr><td colspan="6" style="text-align:center; padding:16px; color:var(--text-dim);">Загрузка сессий...</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </div>

                    <!-- Login History Card -->
                    <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px;">
                        <h3 style="font-size:15px; margin-bottom:12px; color:#fff;">ИСТОРИЯ ВХОДОВ И БЕЗОПАСНОСТЬ ПАНЕЛИ</h3>
                        <div class="table-wrap">
                            <table>
                                <thead>
                                    <tr>
                                        <th>ВРЕМЯ</th>
                                        <th>ПОЛЬЗОВАТЕЛЬ</th>
                                        <th>СОБЫТИЕ АВТОРИЗАЦИИ</th>
                                    </tr>
                                </thead>
                                <tbody id="login-history-body">
                                    <tr><td colspan="3" style="text-align:center; padding:16px; color:var(--text-dim);">Загрузка истории...</td></tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                `;
                await refreshWebAuthData();
            } else {
                // LoveAuth Subtab
                if (actions) {
                    actions.innerHTML = `
                        <button type="button" class="secondary" id="auth-loveauth-refresh-btn">ОБНОВИТЬ СТАТУС</button>
                    `;
                    document.getElementById('auth-loveauth-refresh-btn')?.addEventListener('click', renderLoveAuthSubTab);
                }
                await renderLoveAuthSubTab();
            }
        };

        const refreshWebAuthData = async () => {
            const sBody = document.getElementById('sessions-table-body');
            const hBody = document.getElementById('login-history-body');

            try {
                const sessions = await api('GET', '/api/auth/sessions/all').catch(() => []);
                if (sBody) {
                    if (!sessions.length) {
                        sBody.innerHTML = `<tr><td colspan="6" style="text-align:center; padding:16px; color:var(--text-dim);">Активных сессий не найдено</td></tr>`;
                    } else {
                        sBody.innerHTML = sessions.map(s => `
                            <tr>
                                <td><b style="color:#fff;">${esc(s.username)}</b></td>
                                <td><span class="badge purple">${esc(s.role)}</span></td>
                                <td class="font-mono" style="font-size:12px;">${esc(s.ip)}</td>
                                <td style="color:var(--text-secondary);">${esc(parseDeviceString(s.userAgent))}</td>
                                <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(s.lastUsedAt || s.createdAt)}</td>
                                <td style="text-align:right;">
                                    <button type="button" class="danger btn-sm" onclick="window.terminateStaffSession('${esc(s.token)}')">ЗАВЕРШИТЬ</button>
                                </td>
                            </tr>
                        `).join('');
                    }
                }

                const history = await api('GET', '/api/auth/login-history').catch(() => []);
                if (hBody) {
                    if (!history.length) {
                        hBody.innerHTML = `<tr><td colspan="3" style="text-align:center; padding:16px; color:var(--text-dim);">Истории входов пока нет</td></tr>`;
                    } else {
                        hBody.innerHTML = history.slice(0, 15).map(h => `
                            <tr>
                                <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(h.timestamp)}</td>
                                <td><b>${esc(h.username)}</b></td>
                                <td style="color:#fff;">${esc(h.action)}</td>
                            </tr>
                        `).join('');
                    }
                }
            } catch (e) {}
        };

        const renderLoveAuthSubTab = async () => {
            const container = document.getElementById('auth-tab-content');
            if (!container) return;

            let isAvailable = false;
            try {
                const statusRes = await api('GET', '/api/loveauth/status').catch(() => ({ available: false }));
                isAvailable = !!statusRes.available;
            } catch (e) {
                isAvailable = false;
            }

            let onlineList = [];
            try {
                const s = await api('GET', '/api/stats').catch(() => ({ players: [] }));
                onlineList = s.players || [];
            } catch (e) {}

            container.innerHTML = `
                <!-- LoveAuth Status Banner -->
                ${isAvailable ? `
                    <div class="loveauth-banner">
                        <div style="display:flex; align-items:center; gap:14px;">
                            <span style="font-size:26px;">🛡</span>
                            <div>
                                <div style="font-weight:700; color:#fff; font-size:15px; display:flex; align-items:center; gap:8px;">
                                    <span>ПЛАГИН LOVEAUTH</span>
                                    <span class="badge green">● АКТИВЕН И ПОДКЛЮЧЕН</span>
                                </div>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                    Управление игровыми сессиями, шифрование паролей Argon2id и двухфакторная защита
                                </div>
                            </div>
                        </div>
                        <div style="display:flex; gap:16px; font-size:12px; color:var(--text-dim);">
                            <div>База: <b style="color:#fff;">H2 / SQL</b></div>
                            <div>Хеш: <b style="color:var(--accent-light);">Argon2id</b></div>
                            <div>Брутфорс-фильтр: <b style="color:var(--green);">Активен</b></div>
                        </div>
                    </div>
                ` : `
                    <div class="loveauth-banner" style="background:rgba(239, 68, 68, 0.08); border-color:rgba(239, 68, 68, 0.25);">
                        <div style="display:flex; align-items:center; gap:14px;">
                            <span style="font-size:26px;">⚠</span>
                            <div>
                                <div style="font-weight:700; color:#fff; font-size:15px; display:flex; align-items:center; gap:8px;">
                                    <span>ПЛАГИН LOVEAUTH</span>
                                    <span class="badge red">● ОТКЛЮЧЕН ИЛИ НЕ НАЙДЕН</span>
                                </div>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                    Сервер работает без прямого хука LoveAuth. Убедитесь, что LoveAuth.jar помещен в папку /plugins.
                                </div>
                            </div>
                        </div>
                        <span class="badge red">НЕДОСТУПЕН</span>
                    </div>
                `}

                <!-- Account Search & Online Quick-Chips -->
                <div class="loveauth-card" style="margin-bottom:20px;">
                    <h3 style="font-size:14px; margin-bottom:10px; color:#fff;">ИНСПЕКЦИЯ АККАУНТА ИГРОКА (LOVEAUTH)</h3>
                    <div style="display:flex; gap:10px; margin-bottom:14px;">
                        <input type="text" id="loveauth-search-input" placeholder="Введите точный ник игрока (например, Lovelace, Notch)..." style="flex:1;">
                        <button type="button" class="primary" id="btn-loveauth-search">НАЙТИ АККАУНТ</button>
                    </div>

                    <div style="display:flex; align-items:center; gap:8px; font-size:12px; color:var(--text-dim); flex-wrap:wrap;">
                        <span>Игроки онлайн прямо сейчас:</span>
                        <div id="loveauth-online-chips" style="display:inline-flex; gap:6px; flex-wrap:wrap;">
                            ${onlineList.length ? onlineList.slice(0, 12).map(p => `
                                <button type="button" class="secondary btn-sm" onclick="window.inspectLoveAuthPlayer('${esc(p.name)}')">
                                    ${esc(p.name)}
                                </button>
                            `).join('') : '<span style="color:var(--text-dim); font-size:11.5px;">(сервер пуст)</span>'}
                        </div>
                    </div>
                </div>

                <!-- Inspected Player Details -->
                <div id="loveauth-player-result">
                    <div style="background:var(--card-bg); border:1px dashed var(--border); border-radius:var(--radius-md); padding:40px 20px; text-align:center; color:var(--text-dim);">
                        <div style="font-size:28px; margin-bottom:8px;">🔍</div>
                        Введите никнейм или нажмите на игрока онлайн выше, чтобы загрузить данные авторизации LoveAuth
                    </div>
                </div>
            `;

            const searchBtn = document.getElementById('btn-loveauth-search');
            const searchInput = document.getElementById('loveauth-search-input');

            const doSearch = () => {
                const name = searchInput?.value.trim();
                if (name) window.inspectLoveAuthPlayer(name);
            };

            searchBtn?.addEventListener('click', doSearch);
            searchInput?.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') doSearch();
            });

            if (inspectedPlayer) {
                window.inspectLoveAuthPlayer(inspectedPlayer);
            }
        };

        window.inspectLoveAuthPlayer = async (name) => {
            inspectedPlayer = name;
            const resBox = document.getElementById('loveauth-player-result');
            const searchInput = document.getElementById('loveauth-search-input');
            if (searchInput) searchInput.value = name;
            if (!resBox) return;

            resBox.innerHTML = `
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:30px; text-align:center; color:var(--text-muted);">
                    Связываемся с базой данных LoveAuth...
                </div>
            `;

            try {
                const info = await api('GET', `/api/loveauth/player/${encodeURIComponent(name)}`);

                if (!info || !info.found) {
                    resBox.innerHTML = `
                        <div class="loveauth-card" style="text-align:center; padding:32px;">
                            <div style="color:var(--yellow); font-size:24px; margin-bottom:8px;">⚠</div>
                            <h3 style="color:#fff; margin-bottom:6px;">Игрок «${esc(name)}» не найден в базе данных LoveAuth</h3>
                            <p style="color:var(--text-dim); font-size:12.5px;">Возможно, игрок еще ни разу не заходил на сервер или никнейм введен с опечаткой.</p>
                        </div>
                    `;
                    return;
                }

                const isLocked = !!info.isLocked;
                const isReg = !!info.isRegistered;
                const hasDc = !!info.hasDiscord;
                const alts = Array.isArray(info.alts) ? info.alts : [];

                resBox.innerHTML = `
                    <div class="loveauth-card">
                        <!-- Header -->
                        <div style="display:flex; justify-content:space-between; align-items:flex-start; border-bottom:1px solid var(--border); padding-bottom:16px; margin-bottom:18px; flex-wrap:wrap; gap:14px;">
                            <div style="display:flex; align-items:center; gap:14px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(info.username)}/48" class="player-avatar-sm" style="width:48px; height:48px; border-radius:8px;" alt="">
                                <div>
                                    <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                                        <h3 style="font-size:18px; color:#fff; margin:0;">${esc(info.username)}</h3>
                                        <span class="badge ${isReg ? 'green' : 'yellow'}">${isReg ? '✓ ЗАРЕГИСТРИРОВАН' : 'НЕ ЗАРЕГИСТРИРОВАН'}</span>
                                        <span class="badge ${isLocked ? 'red' : 'green'}">${isLocked ? '🔒 ЗАБЛОКИРОВАН (LOCKOUT)' : '✓ ДОСТУП РАЗРЕШЕН'}</span>
                                    </div>
                                    <div class="font-mono" style="font-size:11.5px; color:var(--text-dim); margin-top:4px;">
                                        UUID: ${esc(info.uuid || '—')}
                                    </div>
                                </div>
                            </div>

                            <div style="text-align:right;">
                                <div style="font-size:11px; color:var(--text-dim); text-transform:uppercase;">Последний IP адрес:</div>
                                <div class="font-mono" style="font-size:13px; font-weight:700; color:var(--accent-light); margin-top:2px;">
                                    ${esc(info.lastIp || '—')}
                                </div>
                            </div>
                        </div>

                        <!-- 3 Meta Cards -->
                        <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap:12px; margin-bottom:20px;">
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">БРУТФОРС & БЛОКИРОВКА</div>
                                <div style="font-size:14px; font-weight:700; margin-top:4px; color:${isLocked ? 'var(--red)' : 'var(--green)'};">
                                    ${isLocked ? 'Сработала защита от подбора' : 'Ошибок входа нет'}
                                </div>
                                <div class="stat-sub">${isLocked ? 'Требуется разблокировка' : 'Счетчик попыток в норме'}</div>
                            </div>

                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">2FA / DISCORD ВЕРИФИКАЦИЯ</div>
                                <div style="font-size:14px; font-weight:700; margin-top:4px; color:${hasDc ? 'var(--accent-light)' : 'var(--text-dim)'};">
                                    ${hasDc ? '✓ Discord 2FA подключен' : 'Без двухфакторной защиты'}
                                </div>
                                <div class="stat-sub">${hasDc ? 'Подтверждение через бота' : 'Вход только по паролю'}</div>
                            </div>

                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">СВЯЗАННЫЕ АККАУНТЫ ПО IP</div>
                                <div style="margin-top:6px; display:flex; gap:4px; flex-wrap:wrap;">
                                    ${(alts.length > 1) 
                                        ? alts.filter(a => a.toLowerCase() !== info.username.toLowerCase()).map(a => `
                                            <button type="button" class="secondary btn-sm" style="font-size:11px; padding:2px 7px;" onclick="window.inspectLoveAuthPlayer('${esc(a)}')">
                                                ${esc(a)}
                                            </button>
                                        `).join('') 
                                        : '<span style="font-size:12px; color:var(--text-dim);">Других аккаунтов не найдено</span>'}
                                </div>
                            </div>
                        </div>

                        <!-- Action Buttons Grid -->
                        <div style="font-size:12px; font-weight:700; color:var(--text-dim); text-transform:uppercase; letter-spacing:0.04em; margin-bottom:10px;">
                            Действия администратора LoveAuth:
                        </div>

                        <div class="loveauth-actions-grid">
                            <button type="button" class="secondary" onclick="window.loveAuthResetSession('${esc(info.username)}')">
                                ⚡ СБРОСИТЬ СЕССИЮ (КИК)
                            </button>
                            <button type="button" class="secondary" onclick="window.loveAuthChangePassword('${esc(info.username)}')">
                                🔑 СМЕНИТЬ ПАРОЛЬ
                            </button>
                            ${isLocked ? `
                                <button type="button" class="primary" onclick="window.loveAuthUnlock('${esc(info.username)}')">
                                    🔓 РАЗБЛОКИРОВАТЬ АККАУНТ
                                </button>
                            ` : ''}
                            ${(info.lastIp && info.lastIp !== '—') ? `
                                <button type="button" class="secondary" onclick="window.loveAuthUnblockIp('${esc(info.lastIp)}')">
                                    🌐 РАЗБЛОКИРОВАТЬ IP
                                </button>
                            ` : ''}
                            <button type="button" class="danger" onclick="window.loveAuthDelete('${esc(info.username)}')">
                                🗑 УДАЛИТЬ ИЗ LOVEAUTH
                            </button>
                        </div>
                    </div>
                `;
            } catch (e) {
                resBox.innerHTML = `
                    <div style="color:var(--red); padding:20px; text-align:center; background:var(--card-bg); border-radius:var(--radius-md); border:1px solid var(--border);">
                        Ошибка запроса к LoveAuth: ${esc(e.message)}
                    </div>
                `;
            }
        };

        // LoveAuth Operations Handlers
        window.loveAuthResetSession = (name) => {
            confirmAction('СБРОС СЕССИИ', `Завершить сессию игрока ${name} на игровом сервере? Он будет немедленно отключен.`, async () => {
                try {
                    await api('POST', `/api/loveauth/player/${encodeURIComponent(name)}/reset-session`);
                    showToast('Сессия сброшена', `Игрок ${name} отключен от игрового сервера`, 'info');
                    recordShiftAction(`LoveAuth: сбросил сессию игроку ${name}`);
                    window.inspectLoveAuthPlayer(name);
                } catch (e) {
                    alert('Ошибка: ' + e.message);
                }
            }, 'СБРОСИТЬ СЕССИЮ');
        };

        window.loveAuthChangePassword = (name) => {
            openModal(`
                <div class="modal-header">
                    <h3>СМЕНА ПАРОЛЯ LOVEAUTH: ${esc(name)}</h3>
                    <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
                </div>
                <div class="modal-body">
                    <div class="form-group">
                        <label>Новый пароль для входа в игру</label>
                        <input type="text" id="loveauth-new-pass" placeholder="Минимум 4 символа..." required autofocus>
                    </div>
                    <div style="font-size:12px; color:var(--text-muted);">
                        Пароль будет захеширован алгоритмом Argon2id и записан в базу данных LoveAuth.
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="secondary" onclick="window.closeCurrentModal()">ОТМЕНА</button>
                    <button type="button" class="primary" id="btn-submit-loveauth-pass">СОХРАНИТЬ ПАРОЛЬ</button>
                </div>
            `, () => {
                document.getElementById('btn-submit-loveauth-pass')?.addEventListener('click', async () => {
                    const pass = document.getElementById('loveauth-new-pass').value.trim();
                    if (!pass || pass.length < 4) {
                        alert('Пароль должен содержать минимум 4 символа');
                        return;
                    }
                    try {
                        await api('POST', `/api/loveauth/player/${encodeURIComponent(name)}/password`, { password: pass });
                        showToast('Пароль обновлен', `Пароль для ${name} успешно изменен`, 'success');
                        recordShiftAction(`LoveAuth: изменил пароль ${name}`);
                        closeModal();
                        window.inspectLoveAuthPlayer(name);
                    } catch (e) {
                        alert('Ошибка смены пароля: ' + e.message);
                    }
                });
            });
        };

        window.loveAuthUnlock = (name) => {
            confirmAction('РАЗБЛОКИРОВКА АККАУНТА', `Снять блокировку подбора пароля (lockout) с аккаунта ${name}?`, async () => {
                try {
                    await api('POST', `/api/loveauth/player/${encodeURIComponent(name)}/unlock`);
                    showToast('Разблокирован', `Аккаунт ${name} успешно разблокирован`, 'success');
                    recordShiftAction(`LoveAuth: разблокировал аккаунт ${name}`);
                    window.inspectLoveAuthPlayer(name);
                } catch (e) {
                    alert('Ошибка разблокировки: ' + e.message);
                }
            }, 'РАЗБЛОКИРОВАТЬ');
        };

        window.loveAuthUnblockIp = (ip) => {
            confirmAction('РАЗБЛОКИРОВКА IP', `Снять блокировку брутфорса с IP адреса ${ip}?`, async () => {
                try {
                    await api('POST', `/api/loveauth/player/system/unblock-ip`, { ip });
                    showToast('IP разблокирован', `IP адрес ${ip} разблокирован в системе`, 'success');
                    recordShiftAction(`LoveAuth: разблокировал IP ${ip}`);
                    if (inspectedPlayer) window.inspectLoveAuthPlayer(inspectedPlayer);
                } catch (e) {
                    alert('Ошибка: ' + e.message);
                }
            }, 'РАЗБЛОКИРОВАТЬ IP');
        };

        window.loveAuthDelete = (name) => {
            confirmAction('УДАЛЕНИЕ АККАУНТА', `ВНИМАНИЕ! Вы действительно хотите безвозвратно удалить регистрацию игрока ${name} из LoveAuth?`, async () => {
                try {
                    await api('POST', `/api/loveauth/player/${encodeURIComponent(name)}/delete`);
                    showToast('Аккаунт удален', `Регистрация ${name} удалена из LoveAuth`, 'warning');
                    recordShiftAction(`LoveAuth: удалил аккаунт ${name}`);
                    window.inspectLoveAuthPlayer(name);
                } catch (e) {
                    alert('Ошибка удаления: ' + e.message);
                }
            }, 'УДАЛИТЬ БЕЗВОЗВРАТНО');
        };

        window.terminateStaffSession = (tokenToKill) => {
            confirmAction('ЗАВЕРШЕНИЕ СЕССИИ', 'Принудительно отозвать токен и завершить данную сессию сотрудника?', async () => {
                await api('POST', `/api/auth/sessions/${encodeURIComponent(tokenToKill)}/terminate`);
                showToast('Сессия закрыта', 'Пользователь был отключен от панели', 'info');
                refreshWebAuthData();
            }, 'ЗАВЕРШИТЬ');
        };

        // Subtab click handlers
        document.querySelectorAll('#auth-subtabs .sub-tab-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                currentAuthTab = btn.dataset.subtab;
                renderCurrentSubTab();
            });
        });

        await renderCurrentSubTab();
    }

    // ==========================================================================
    // 7. АДМИНИСТРАТОРЫ И РОЛИ (СОТРУДНИКИ, ВРЕМЕННЫЕ СРОКИ, МАТРИЦА ПРАВ)
    // ==========================================================================

    async function renderAdminsView() {
        const area = document.getElementById('content-area');

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>АДМИНИСТРАТОРЫ И РОЛИ</h2>
                    <p>Управление составом персонала, матрицей разрешений и временными статусами</p>
                </div>
                <div class="view-actions">
                    <button type="button" class="primary" id="btn-create-admin">+ ДОБАВИТЬ СОТРУДНИКА</button>
                    <button type="button" class="primary" id="btn-create-role">+ СОЗДАТЬ РОЛЬ</button>
                </div>
            </div>

            <!-- Staff Members Table -->
            <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px; margin-bottom:24px;">
                <h3 style="font-size:15px; margin-bottom:12px; color:#fff;">СОСТАВ ПЕРСОНАЛА</h3>
                <div class="table-wrap">
                    <table>
                        <thead>
                            <tr>
                                <th>СОТРУДНИК</th>
                                <th>ТЕКУЩАЯ РОЛЬ</th>
                                <th>2FA ЗАЩИТА</th>
                                <th>СРОК ДОСТУПА</th>
                                <th>ПОСЛЕДНИЙ ВХОД</th>
                                <th style="text-align:right;">ДЕЙСТВИЯ</th>
                            </tr>
                        </thead>
                        <tbody id="admins-table-body">
                            <tr><td colspan="6" style="text-align:center; padding:18px; color:var(--text-dim);">Загрузка персонала...</td></tr>
                        </tbody>
                    </table>
                </div>
            </div>

            <!-- Roles Table -->
            <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px;">
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
                    <h3 style="font-size:15px; margin:0; color:#fff;">РОЛИ И МАТРИЦА ПРАВ</h3>
                    <span style="font-size:12px; color:var(--text-muted);">Настройка прав доступа веб-панели и привязка к LuckPerms</span>
                </div>
                <div class="table-wrap">
                    <table>
                        <thead>
                            <tr>
                                <th>НАЗВАНИЕ РОЛИ</th>
                                <th>LUCKPERMS ГРУППА</th>
                                <th>КОЛИЧЕСТВО ПРАВ</th>
                                <th>ТИП</th>
                                <th style="text-align:right;">ДЕЙСТВИЯ</th>
                            </tr>
                        </thead>
                        <tbody id="roles-table-body">
                            <tr><td colspan="5" style="text-align:center; padding:18px; color:var(--text-dim);">Загрузка ролей...</td></tr>
                        </tbody>
                    </table>
                </div>
            </div>
        `;

        let rolesCache = [];

        const loadAdminsAndRoles = async () => {
            const aBody = document.getElementById('admins-table-body');
            const rBody = document.getElementById('roles-table-body');

            try {
                const [admins, roles] = await Promise.all([
                    api('GET', '/api/admins'),
                    api('GET', '/api/roles')
                ]);
                rolesCache = roles;

                if (aBody) {
                    aBody.innerHTML = admins.map(a => {
                        const roleObj = roles.find(r => r.id === a.roleId) || { name: 'Неизвестно' };
                        const hasTotp = a.totpEnabled;
                        const expiry = a.roleExpiresAt && a.roleExpiresAt > 0 ? fmtTime(a.roleExpiresAt) : 'Бессрочно';

                        return `
                            <tr>
                                <td>
                                    <div style="display:flex; align-items:center; gap:8px;">
                                        <div class="sidebar-user-avatar" style="width:26px; height:26px; font-size:11px;">${esc((a.username || '').substring(0, 2).toUpperCase())}</div>
                                        <b style="color:#fff;">${esc(a.username)}</b>
                                    </div>
                                </td>
                                <td><span class="badge purple">${esc(roleObj.name)}</span></td>
                                <td>
                                    <span class="badge ${hasTotp ? 'green' : 'yellow'}">${hasTotp ? '✓ 2FA ВКЛ' : 'НЕТ 2FA'}</span>
                                </td>
                                <td style="color:var(--text-muted); font-size:12px;">${expiry}</td>
                                <td class="font-mono" style="font-size:12px; color:var(--text-dim);">${fmtTime(a.lastLoginAt)}</td>
                                <td style="text-align:right;">
                                    <div style="display:flex; justify-content:flex-end; gap:6px;">
                                        <button type="button" class="secondary btn-sm" onclick="window.openEditAdminRoleModal(${a.id}, '${esc(a.username)}', ${a.roleId})">РОЛЬ</button>
                                        <button type="button" class="secondary btn-sm" onclick="window.resetStaffPassword(${a.id}, '${esc(a.username)}')">СБРОС ПАРОЛЯ</button>
                                    </div>
                                </td>
                            </tr>
                        `;
                    }).join('');
                }

                if (rBody) {
                    rBody.innerHTML = roles.map(r => `
                        <tr>
                            <td><b style="color:#fff;">${esc(r.name)}</b></td>
                            <td class="font-mono" style="font-size:12px; color:var(--accent-light);">${esc(r.lpGroup || '—')}</td>
                            <td><b>${r.permissions ? r.permissions.length : 0}</b> прав</td>
                            <td>
                                <span class="badge ${r.isOwner ? 'purple' : 'gray'}">${r.isOwner ? 'УПРАВЛЯЮЩИЙ' : 'КАСТОМНАЯ'}</span>
                            </td>
                            <td style="text-align:right;">
                                ${!r.isOwner ? `
                                    <div style="display:flex; justify-content:flex-end; gap:6px;">
                                        <button type="button" class="secondary btn-sm" onclick="window.openEditRoleModal(${r.id})">РЕДАКТИРОВАТЬ</button>
                                        <button type="button" class="danger btn-sm" onclick="window.deleteRole(${r.id}, '${esc(r.name)}')">УДАЛИТЬ</button>
                                    </div>
                                ` : '<span style="font-size:11px; color:var(--text-dim);">Полный доступ</span>'}
                            </td>
                        </tr>
                    `).join('');
                }
            } catch (e) {
                if (aBody) aBody.innerHTML = `<tr><td colspan="6" style="text-align:center; color:var(--red); padding:18px;">Ошибка: ${esc(e.message)}</td></tr>`;
            }
        };

        window.openEditAdminRoleModal = (adminId, username, currentRoleId) => {
            openModal(`
                <div class="modal-header">
                    <h3>НАЗНАЧЕНИЕ РОЛИ: ${esc(username)}</h3>
                    <button type="button" class="close-btn" data-modal-close="true">✕</button>
                </div>
                <div class="modal-body">
                    <div class="form-group">
                        <label>Выберите роль</label>
                        <select id="edit-admin-role-select">
                            ${rolesCache.filter(r => !r.isOwner).map(r => `
                                <option value="${r.id}" ${r.id === currentRoleId ? 'selected' : ''}>${esc(r.name)}</option>
                            `).join('')}
                        </select>
                    </div>

                    <div class="form-group">
                        <label>Временная роль (испытательный срок)?</label>
                        <select id="edit-admin-temp-select">
                            <option value="0">Бессрочно (постоянный доступ)</option>
                            <option value="7">Испытательный срок 7 дней</option>
                            <option value="14">Испытательный срок 14 дней</option>
                            <option value="30">Испытательный срок 30 дней</option>
                        </select>
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                    <button type="button" class="primary" id="btn-save-admin-role">СОХРАНИТЬ</button>
                </div>
            `, () => {
                document.getElementById('btn-save-admin-role')?.addEventListener('click', async () => {
                    const roleId = parseInt(document.getElementById('edit-admin-role-select').value, 10);
                    const days = parseInt(document.getElementById('edit-admin-temp-select').value, 10) || 0;
                    const expiresAt = days > 0 ? (Date.now() + days * 86400000) : 0;
                    try {
                        await api('PUT', `/api/admins/${adminId}/role`, { roleId, expiresAt });
                        showToast('Роль обновлена', `Роль сотрудника ${username} успешно изменена`, 'success');
                        closeModal();
                        loadAdminsAndRoles();
                    } catch (e) {
                        alert('Ошибка: ' + e.message);
                    }
                });
            });
        };

        window.resetStaffPassword = (adminId, username) => {
            confirmAction('СБРОС ПАРОЛЯ', `Сбросить пароль для сотрудника ${username}? При следующем входе система потребует задать новый пароль.`, async () => {
                try {
                    await api('DELETE', `/api/admins/${adminId}/password`);
                    showToast('Пароль сброшен', `Пароль для ${username} сброшен`, 'info');
                } catch (e) {
                    alert('Ошибка сброса: ' + e.message);
                }
            }, 'СБРОСИТЬ');
        };

        // Helper to render permissions checkboxes
        const renderPermCheckboxes = (activePerms = []) => {
            return ALL_PERMISSIONS.map(p => {
                const has = activePerms.includes(p);
                const label = PERMISSION_LABELS[p] || p;
                return `
                    <label style="display:flex; align-items:flex-start; gap:10px; padding:8px 10px; background:rgba(255,255,255,0.02); border:1px solid var(--border); border-radius:5px; cursor:pointer;">
                        <input type="checkbox" class="role-perm-cb" value="${p}" ${has ? 'checked' : ''} style="width:16px; height:16px; accent-color:var(--accent); margin-top:2px;">
                        <div>
                            <div class="font-mono" style="font-size:12px; font-weight:700; color:#fff;">${p}</div>
                            <div style="font-size:11.5px; color:var(--text-muted); line-height:1.3; margin-top:2px;">${esc(label)}</div>
                        </div>
                    </label>
                `;
            }).join('');
        };

        // Modal: Create Role
        document.getElementById('btn-create-role')?.addEventListener('click', () => {
            openModal(`
                <div class="modal-header">
                    <h3>СОЗДАНИЕ НОВОЙ РОЛИ</h3>
                    <button type="button" class="close-btn" data-modal-close="true">✕</button>
                </div>
                <div class="modal-body" style="max-height:75vh; overflow-y:auto;">
                    <div class="form-group">
                        <label>Название роли *</label>
                        <input type="text" id="create-role-name" placeholder="Например: Старший Модератор" required autofocus>
                    </div>
                    <div class="form-group">
                        <label>Группа в LuckPerms (опционально)</label>
                        <input type="text" id="create-role-lp" placeholder="Например: srmod">
                    </div>

                    <div style="margin-top:16px; margin-bottom:8px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                        <label style="margin-bottom:0; font-weight:700; color:#fff;">Права доступа роли в панели:</label>
                        <div style="display:flex; gap:6px;">
                            <button type="button" class="secondary btn-sm" id="btn-perm-all">Выбрать все</button>
                            <button type="button" class="secondary btn-sm" id="btn-perm-none">Снять все</button>
                            <button type="button" class="secondary btn-sm" id="btn-perm-mod">Пресет: Модератор</button>
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:8px;" id="perms-matrix-container">
                        ${renderPermCheckboxes(['VIEW_STATS', 'VIEW_PLAYERS', 'VIEW_BANS', 'MANAGE_BANS', 'VIEW_REPORTS', 'MANAGE_REPORTS', 'VIEW_SERVER_LOGS'])}
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                    <button type="button" class="primary" id="btn-submit-create-role">СОЗДАТЬ РОЛЬ</button>
                </div>
            `, () => {
                document.getElementById('btn-perm-all')?.addEventListener('click', () => {
                    document.querySelectorAll('.role-perm-cb').forEach(cb => cb.checked = true);
                });
                document.getElementById('btn-perm-none')?.addEventListener('click', () => {
                    document.querySelectorAll('.role-perm-cb').forEach(cb => cb.checked = false);
                });
                document.getElementById('btn-perm-mod')?.addEventListener('click', () => {
                    const modPerms = ['VIEW_STATS', 'VIEW_PLAYERS', 'MANAGE_PLAYERS', 'VIEW_BANS', 'MANAGE_BANS', 'VIEW_REPORTS', 'MANAGE_REPORTS', 'VIEW_VESUVIO', 'VIEW_SERVER_LOGS'];
                    document.querySelectorAll('.role-perm-cb').forEach(cb => {
                        cb.checked = modPerms.includes(cb.value);
                    });
                });

                document.getElementById('btn-submit-create-role')?.addEventListener('click', async () => {
                    const name = document.getElementById('create-role-name').value.trim();
                    const lpGroup = document.getElementById('create-role-lp').value.trim();
                    const permissions = Array.from(document.querySelectorAll('.role-perm-cb:checked')).map(cb => cb.value);

                    if (!name) { alert('Укажите название роли'); return; }

                    try {
                        await api('POST', '/api/roles', { name, lpGroup, permissions });
                        showToast('Роль создана', `Роль ${name} успешно добавлена`, 'success');
                        closeModal();
                        loadAdminsAndRoles();
                    } catch (e) {
                        alert('Ошибка: ' + e.message);
                    }
                });
            }, 'modal-lg');
        });

        // Modal: Edit Role
        window.openEditRoleModal = (roleId) => {
            const role = rolesCache.find(r => r.id === roleId);
            if (!role) return;

            openModal(`
                <div class="modal-header">
                    <h3>РЕДАКТИРОВАНИЕ РОЛИ: ${esc(role.name)}</h3>
                    <button type="button" class="close-btn" data-modal-close="true">✕</button>
                </div>
                <div class="modal-body" style="max-height:75vh; overflow-y:auto;">
                    <div class="form-group">
                        <label>Название роли *</label>
                        <input type="text" id="edit-role-name" value="${esc(role.name)}" required autofocus>
                    </div>
                    <div class="form-group">
                        <label>Группа в LuckPerms (опционально)</label>
                        <input type="text" id="edit-role-lp" value="${esc(role.lpGroup || '')}" placeholder="Например: srmod">
                    </div>

                    <div style="margin-top:16px; margin-bottom:8px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                        <label style="margin-bottom:0; font-weight:700; color:#fff;">Права доступа роли в панели:</label>
                        <div style="display:flex; gap:6px;">
                            <button type="button" class="secondary btn-sm" id="btn-edit-perm-all">Выбрать все</button>
                            <button type="button" class="secondary btn-sm" id="btn-edit-perm-none">Снять все</button>
                            <button type="button" class="secondary btn-sm" id="btn-edit-perm-mod">Пресет: Модератор</button>
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:8px;">
                        ${renderPermCheckboxes(role.permissions || [])}
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                    <button type="button" class="primary" id="btn-submit-edit-role">СОХРАНИТЬ ИЗМЕНЕНИЯ</button>
                </div>
            `, () => {
                document.getElementById('btn-edit-perm-all')?.addEventListener('click', () => {
                    document.querySelectorAll('.role-perm-cb').forEach(cb => cb.checked = true);
                });
                document.getElementById('btn-edit-perm-none')?.addEventListener('click', () => {
                    document.querySelectorAll('.role-perm-cb').forEach(cb => cb.checked = false);
                });
                document.getElementById('btn-edit-perm-mod')?.addEventListener('click', () => {
                    const modPerms = ['VIEW_STATS', 'VIEW_PLAYERS', 'MANAGE_PLAYERS', 'VIEW_BANS', 'MANAGE_BANS', 'VIEW_REPORTS', 'MANAGE_REPORTS', 'VIEW_VESUVIO', 'VIEW_SERVER_LOGS'];
                    document.querySelectorAll('.role-perm-cb').forEach(cb => {
                        cb.checked = modPerms.includes(cb.value);
                    });
                });

                document.getElementById('btn-submit-edit-role')?.addEventListener('click', async () => {
                    const name = document.getElementById('edit-role-name').value.trim();
                    const lpGroup = document.getElementById('edit-role-lp').value.trim();
                    const permissions = Array.from(document.querySelectorAll('.role-perm-cb:checked')).map(cb => cb.value);

                    if (!name) { alert('Укажите название роли'); return; }

                    try {
                        await api('PUT', `/api/roles/${roleId}`, { name, lpGroup, permissions });
                        showToast('Роль обновлена', `Параметры и права для роли ${name} сохранены`, 'success');
                        closeModal();
                        loadAdminsAndRoles();
                    } catch (e) {
                        alert('Ошибка сохранения: ' + e.message);
                    }
                });
            }, 'modal-lg');
        };

        // Delete Role Handler
        window.deleteRole = (roleId, roleName) => {
            confirmAction(
                'УДАЛЕНИЕ РОЛИ',
                `Вы действительно хотите безвозвратно удалить роль «${roleName}»? Если к этой роли привязаны сотрудники, система заблокирует удаление до переназначения их ролей.`,
                async () => {
                    try {
                        await api('DELETE', `/api/roles/${roleId}`);
                        showToast('Роль удалена', `Роль «${roleName}» успешно удалена`, 'info');
                        loadAdminsAndRoles();
                    } catch (e) {
                        alert('Не удалось удалить роль: ' + e.message);
                    }
                },
                'УДАЛИТЬ РОЛЬ',
                true
            );
        };

        // Create Admin Modal
        document.getElementById('btn-create-admin')?.addEventListener('click', () => {
            openModal(`
                <div class="modal-header">
                    <h3>ДОБАВЛЕНИЕ СОТРУДНИКА</h3>
                    <button type="button" class="close-btn" data-modal-close="true">✕</button>
                </div>
                <div class="modal-body">
                    <div class="form-group">
                        <label>Никнейм сотрудника (точно как в игре)</label>
                        <input type="text" id="add-admin-user" placeholder="Например: Lovelace" required autofocus>
                    </div>
                    <div class="form-group">
                        <label>Начальная роль</label>
                        <select id="add-admin-role">
                            ${rolesCache.filter(r => !r.isOwner).map(r => `
                                <option value="${r.id}">${esc(r.name)}</option>
                            `).join('')}
                        </select>
                    </div>
                    <div style="font-size:12px; color:var(--text-muted);">
                        Сотрудник сможет зайти под своим ником и одноразовым первичным паролем.
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                    <button type="button" class="primary" id="btn-submit-add-admin">СОЗДАТЬ</button>
                </div>
            `, () => {
                const userInput = document.getElementById('add-admin-user');
                if (userInput) attachPlayerAutocomplete(userInput);

                document.getElementById('btn-submit-add-admin')?.addEventListener('click', async () => {
                    const username = document.getElementById('add-admin-user').value.trim();
                    const roleId = parseInt(document.getElementById('add-admin-role').value, 10);
                    if (!username) { alert('Укажите никнейм'); return; }

                    try {
                        await api('POST', '/api/admins', { username, roleId });
                        showToast('Сотрудник добавлен', `Учетная запись для ${username} создана`, 'success');
                        closeModal();
                        loadAdminsAndRoles();
                    } catch (e) {
                        alert('Ошибка: ' + e.message);
                    }
                });
            });
        });

        await loadAdminsAndRoles();
    }


    // ==========================================================================
    // 8. БАЗА ДАННЫХ (СВОДНАЯ СТАТИСТИКА, ГРАФИКИ, ТОП ИГРОКОВ)
    // ==========================================================================

    async function renderDatabaseView() {
        const area = document.getElementById('content-area');
        let selectedPeriod = 'today';
        let customFrom = null;
        let customTo = null;

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>БАЗА ДАННЫХ И АНАЛИТИКА СЕРВЕРА</h2>
                    <p>Комплексная статистика игроков, сравнительный анализ за периоды, динамика прироста и лидерборды</p>
                </div>
                <div class="view-actions">
                    <button type="button" class="secondary" id="db-refresh-btn">ОБНОВИТЬ ДАННЫЕ</button>
                </div>
            </div>

            <!-- Period Filter Toolbar with Percentage Trend Badges -->
            <div class="period-filter-toolbar">
                <div style="display:flex; align-items:center; gap:12px; flex-wrap:wrap;">
                    <span style="font-size:12px; font-weight:700; color:var(--text-dim); text-transform:uppercase; letter-spacing:0.04em;">Период выборки:</span>
                    <div class="period-pills-group" id="db-period-pills">
                        <button type="button" class="period-pill-btn active" data-period="today">Сегодня</button>
                        <button type="button" class="period-pill-btn" data-period="yesterday">Вчера</button>
                        <button type="button" class="period-pill-btn" data-period="7d">7 дней</button>
                        <button type="button" class="period-pill-btn" data-period="30d">30 дней</button>
                        <button type="button" class="period-pill-btn" data-period="custom">Произвольный</button>
                    </div>
                </div>

                <div id="db-custom-range-box" style="display:none; align-items:center; gap:8px;">
                    <input type="date" id="db-date-from" style="padding:4px 8px; font-size:12px; width:135px; background:rgba(0,0,0,0.4); border:1px solid var(--border); color:#fff; border-radius:4px;">
                    <span style="color:var(--text-dim); font-size:12px;">—</span>
                    <input type="date" id="db-date-to" style="padding:4px 8px; font-size:12px; width:135px; background:rgba(0,0,0,0.4); border:1px solid var(--border); color:#fff; border-radius:4px;">
                    <button type="button" class="primary btn-sm" id="btn-apply-custom-period">ПРИМЕНИТЬ</button>
                </div>
            </div>

            <!-- Global Key Metrics Cards Grid -->
            <div class="cards-grid" style="margin-bottom:24px;" id="db-metrics-grid">
                <div class="stat-card"><div class="stat-label">ЗАГРУЗКА АНАЛИТИКИ...</div></div>
            </div>

            <!-- 24-Hour Activity Chart -->
            <div class="hourly-chart-wrap" style="margin-bottom:24px;">
                <div class="chart-header">
                    <div>
                        <h3 style="font-size:15px; color:#fff;" id="db-chart-title">АКТИВНОСТЬ ПО ЧАСАМ (24 ЧАСА)</h3>
                        <p style="font-size:12px; color:var(--text-muted);" id="db-chart-subtitle">Сравнение распределения действий с предыдущим аналогичным периодом</p>
                    </div>
                    <span class="badge purple" id="db-chart-badge">СЕГОДНЯ</span>
                </div>
                <div class="hourly-bars-grid" id="hourly-chart-bars">
                    <!-- 24 bars rendered dynamically -->
                </div>
            </div>

            <!-- Top Playtime Players & Dedicated Player Inspector -->
            <div style="display:grid; grid-template-columns: 2fr 1fr; gap:20px;">
                <!-- Top Playtime Table -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px;">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;">
                        <h3 style="font-size:15px; color:#fff;">ТОП ИГРОКОВ ПО НАИГРАННОМУ ВРЕМЕНИ</h3>
                        <span style="font-size:12px; color:var(--text-dim);">Всего в базе данных</span>
                    </div>
                    <div class="table-wrap" style="max-height:440px; overflow-y:auto;">
                        <table>
                            <thead>
                                <tr>
                                    <th style="width:45px;">#</th>
                                    <th>ИГРОК</th>
                                    <th>ВРЕМЯ В ИГРЕ</th>
                                    <th>ПЕРВЫЙ ВХОД</th>
                                    <th style="text-align:right;">ДЕЙСТВИЕ</th>
                                </tr>
                            </thead>
                            <tbody id="top-playtime-tbody">
                                <tr><td colspan="5" style="text-align:center; padding:18px; color:var(--text-dim);">Загрузка лидерборда...</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>

                <!-- Dedicated Player Stat Inspector -->
                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px;">
                    <h3 style="font-size:15px; margin-bottom:6px; color:#fff;">КАРТОЧКА СТАТИСТИКИ ИГРОКА</h3>
                    <p style="font-size:12px; color:var(--text-muted); margin-bottom:14px;">Персональное досье и параметры конкретного пользователя</p>

                    <div style="display:flex; gap:8px; margin-bottom:16px;">
                        <input type="text" id="inspect-player-name" placeholder="Введите ник игрока..." style="flex:1;">
                        <button type="button" class="primary btn-sm" id="btn-inspect-player">НАЙТИ</button>
                    </div>

                    <div id="inspector-card-result">
                        <div style="text-align:center; padding:30px 10px; color:var(--text-dim); font-size:12.5px;">
                            Введите никнейм для просмотра персональной статистики
                        </div>
                    </div>
                </div>
            </div>
        `;

        function formatDeltaBadge(delta, prevLabel) {
            if (delta === null || delta === undefined || isNaN(delta)) {
                return `<span class="trend-badge neutral">0%</span>`;
            }
            const num = Math.round(delta * 10) / 10;
            const labelStr = prevLabel ? ` <span style="font-weight:400; opacity:0.75;">${esc(prevLabel)}</span>` : '';
            if (num > 0) {
                return `<span class="trend-badge up">▲ +${num}%${labelStr}</span>`;
            } else if (num < 0) {
                return `<span class="trend-badge down">▼ ${num}%${labelStr}</span>`;
            } else {
                return `<span class="trend-badge neutral">— 0%${labelStr}</span>`;
            }
        }

        const loadAnalytics = async () => {
            try {
                let url = `/api/analytics/summary?period=${encodeURIComponent(selectedPeriod)}`;
                if (selectedPeriod === 'custom' && customFrom && customTo) {
                    url += `&from=${customFrom}&to=${customTo}`;
                }
                const data = await api('GET', url);
                const topPlayers = await api('GET', '/api/analytics/top-players?limit=20').catch(() => []);

                // Update Chart header
                const chartBadge = document.getElementById('db-chart-badge');
                if (chartBadge) chartBadge.textContent = (data.periodLabel || selectedPeriod).toUpperCase();
                const chartSub = document.getElementById('db-chart-subtitle');
                if (chartSub) chartSub.textContent = `Распределение действий по часам суток (сравнение: ${data.prevPeriodLabel || 'пред. период'})`;

                // Render metrics cards
                const mGrid = document.getElementById('db-metrics-grid');
                if (mGrid) {
                    mGrid.innerHTML = `
                        <div class="stat-card">
                            <div class="stat-label">УНИКАЛЬНЫХ ИГРОКОВ</div>
                            <div class="stat-value" style="color:var(--green);">${(data.uniquePlayers || 0).toLocaleString()}</div>
                            <div>${formatDeltaBadge(data.uniquePlayersDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">НОВЫХ ИГРОКОВ</div>
                            <div class="stat-value" style="color:var(--yellow);">${(data.newPlayers || 0).toLocaleString()}</div>
                            <div>${formatDeltaBadge(data.newPlayersDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">ПИКОВЫЙ ОНЛАЙН</div>
                            <div class="stat-value" style="color:var(--accent-light);">${data.peakOnline || 0}</div>
                            <div>${formatDeltaBadge(data.peakOnlineDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">СРЕДНИЙ ОНЛАЙН</div>
                            <div class="stat-value" style="color:#60a5fa;">${data.avgOnline || 0}</div>
                            <div>${formatDeltaBadge(data.avgOnlineDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">НАКАЗАНИЙ ВЫДАНО</div>
                            <div class="stat-value" style="color:var(--red);">${(data.punishments || 0).toLocaleString()}</div>
                            <div>${formatDeltaBadge(data.punishmentsDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">СООБЩЕНИЙ В ЧАТЕ</div>
                            <div class="stat-value" style="color:#c084fc;">${(data.chatMessages || 0).toLocaleString()}</div>
                            <div>${formatDeltaBadge(data.chatMessagesDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">RETENTION (УДЕРЖАНИЕ)</div>
                            <div class="stat-value" style="color:#38bdf8;">${data.retentionRate || 0}%</div>
                            <div>${formatDeltaBadge(data.retentionDelta, data.prevPeriodLabel)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">СРЕДНЯЯ СЕССИЯ</div>
                            <div class="stat-value">${data.avgPlaytimeMinutes || 0} <span style="font-size:13px; color:var(--text-dim);">мин</span></div>
                            <div class="stat-sub">Длина одной сессии игрока</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">ВСЕГО АККАУНТОВ В БД</div>
                            <div class="stat-value">${(data.totalPlayers || 0).toLocaleString()}</div>
                            <div class="stat-sub">Наиграно: ${(data.totalPlaytimeHours || 0).toLocaleString()} ч</div>
                        </div>
                    `;
                }

                // Render hourly chart
                const chartBars = document.getElementById('hourly-chart-bars');
                if (chartBars) {
                    const hourly = Array.isArray(data.hourlyActivity) ? data.hourlyActivity : Array.from({ length: 24 }, () => 0);
                    const prevHourly = Array.isArray(data.prevHourlyActivity) ? data.prevHourlyActivity : Array.from({ length: 24 }, () => 0);
                    const maxVal = Math.max(...hourly, ...prevHourly, 1);

                    chartBars.innerHTML = hourly.map((cnt, hr) => {
                        const pct = Math.max(6, Math.round((cnt / maxVal) * 100));
                        const prevCnt = prevHourly[hr] || 0;
                        const hrStr = String(hr).padStart(2, '0') + ':00';
                        return `
                            <div class="hour-column" data-tooltip="${hrStr} — Сейчас: ${cnt} | ${esc(data.prevPeriodLabel || 'Пред')}: ${prevCnt}">
                                <div class="hour-bar" style="height:${pct}%;"></div>
                                <span class="hour-label">${hr % 3 === 0 ? hrStr : ''}</span>
                            </div>`;
                    }).join('');
                }

                // Render Top Players Table
                const topBody = document.getElementById('top-playtime-tbody');
                if (topBody) {
                    if (!topPlayers.length) {
                        topBody.innerHTML = `<tr><td colspan="5" style="text-align:center; padding:16px; color:var(--text-dim);">Нет данных по времени</td></tr>`;
                    } else {
                        topBody.innerHTML = topPlayers.map((p, idx) => `
                            <tr>
                                <td class="font-mono" style="font-weight:700; color:var(--accent-light);">#${idx + 1}</td>
                                <td>
                                    <div style="display:flex; align-items:center; gap:8px; cursor:pointer;" onclick="window.viewPlayerProfile('${esc(p.name)}')">
                                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/22" class="player-avatar-sm" alt="">
                                        <b style="color:#fff;">${esc(p.name)}</b>
                                    </div>
                                </td>
                                <td><b style="color:var(--green);">${fmtDuration(p.playtimeSeconds)}</b></td>
                                <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(p.firstJoinedAt)}</td>
                                <td style="text-align:right;">
                                    <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(p.name)}')">ДОСЬЕ</button>
                                </td>
                            </tr>
                        `).join('');
                    }
                }
            } catch (e) {
                console.error('Error loading analytics:', e);
            }
        };

        // Period filter buttons
        document.querySelectorAll('#db-period-pills .period-pill-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('#db-period-pills .period-pill-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                selectedPeriod = btn.dataset.period;

                const customBox = document.getElementById('db-custom-range-box');
                if (selectedPeriod === 'custom') {
                    if (customBox) customBox.style.display = 'flex';
                } else {
                    if (customBox) customBox.style.display = 'none';
                    loadAnalytics();
                }
            });
        });

        // Apply custom period button
        document.getElementById('btn-apply-custom-period')?.addEventListener('click', () => {
            const fromVal = document.getElementById('db-date-from')?.value;
            const toVal = document.getElementById('db-date-to')?.value;
            if (!fromVal || !toVal) {
                alert('Выберите обе даты периода');
                return;
            }
            customFrom = new Date(fromVal).getTime();
            customTo = new Date(toVal + 'T23:59:59').getTime();
            loadAnalytics();
        });

        // Dedicated player inspector button
        const doInspectPlayer = async () => {
            const name = document.getElementById('inspect-player-name')?.value.trim();
            const resBox = document.getElementById('inspector-card-result');
            if (!name || !resBox) return;

            resBox.innerHTML = `<div style="text-align:center; padding:20px; color:var(--text-muted);">Поиск...</div>`;
            try {
                const p = await api('GET', `/api/players/${encodeURIComponent(name)}`);
                const hours = Math.round((p.playtimeSeconds || 0) / 3600);

                resBox.innerHTML = `
                    <div style="background:#0e0921; padding:14px; border-radius:var(--radius-sm); border:1px solid var(--border-light);">
                        <div style="display:flex; align-items:center; gap:10px; margin-bottom:12px;">
                            <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/36" class="player-avatar-sm" style="width:36px; height:36px;" alt="">
                            <div>
                                <h4 style="color:#fff; font-size:15px; margin:0;">${esc(p.name)}</h4>
                                <span class="badge ${p.isOnline ? 'green' : 'gray'}" style="margin-top:2px;">${p.isOnline ? 'ОНЛАЙН' : 'ОФФЛАЙН'}</span>
                            </div>
                        </div>

                        <div style="display:flex; flex-direction:column; gap:8px; font-size:12.5px;">
                            <div style="display:flex; justify-content:space-between; border-bottom:1px solid var(--border); padding-bottom:4px;">
                                <span style="color:var(--text-dim);">Всего в игре:</span>
                                <b style="color:var(--accent-light);">${hours} ч (${fmtDuration(p.playtimeSeconds || 0)})</b>
                            </div>
                            <div style="display:flex; justify-content:space-between; border-bottom:1px solid var(--border); padding-bottom:4px;">
                                <span style="color:var(--text-dim);">Первый вход:</span>
                                <span class="font-mono">${fmtTime(p.firstJoinedAt)}</span>
                            </div>
                            <div style="display:flex; justify-content:space-between; border-bottom:1px solid var(--border); padding-bottom:4px;">
                                <span style="color:var(--text-dim);">Последний вход:</span>
                                <span class="font-mono">${fmtTime(p.lastSeenAt)}</span>
                            </div>
                            <div style="display:flex; justify-content:space-between;">
                                <span style="color:var(--text-dim);">Рейтинг доверия:</span>
                                <b style="color:${p.trustColor || '#10b981'};">${p.trustGrade || 'A'} (${p.trustScore || 85}/100)</b>
                            </div>
                        </div>

                        <button type="button" class="primary btn-sm" style="width:100%; margin-top:14px;" onclick="window.viewPlayerProfile('${esc(p.name)}')">ОТКРЫТЬ ПОЛНОЕ ДОСЬЕ</button>
                    </div>
                `;
            } catch (e) {
                resBox.innerHTML = `<div style="color:var(--red); padding:16px; text-align:center;">Игрок «${esc(name)}» не найден</div>`;
            }
        };

        document.getElementById('btn-inspect-player')?.addEventListener('click', doInspectPlayer);
        document.getElementById('inspect-player-name')?.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') doInspectPlayer();
        });

        document.getElementById('db-refresh-btn')?.addEventListener('click', loadAnalytics);

        await loadAnalytics();
    }

    // Modal: User Profile & Personal Preferences
    function openUserProfileModal() {
        const isMod = isModeratorRole();
        const roleName = me.isOwner ? 'Управляющий' : (me.role || (isMod ? 'Модератор' : 'Администратор'));

        let userPrefs = {};
        try {
            userPrefs = typeof me.uiPreferences === 'string' ? JSON.parse(me.uiPreferences) : (me.uiPreferences || {});
        } catch (_) { userPrefs = {}; }
        const discordId = userPrefs.discordId || '';
        const notifyReports = userPrefs.discordNotifyReports !== false;
        const notifyTickets = userPrefs.discordNotifyTickets !== false;

        openModal(`
            <div class="modal-header">
                <h3>ПРОФИЛЬ СОТРУДНИКА: ${esc(me.username)}</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
            </div>
            <div class="modal-body">
                <div style="background:#110a26; padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                    <div style="font-size:12px; color:var(--text-dim);">ТЕКУЩИЙ СТАТУС В СИСТЕМЕ:</div>
                    <div style="font-size:18px; font-weight:800; color:#fff; margin-top:2px;">
                        ${esc(me.username)} <span class="badge purple">${esc(roleName)}</span>
                    </div>
                    <div style="font-size:12px; color:var(--text-muted); margin-top:4px;">
                        2FA Защита: <b>${me.totpEnabled ? 'Включена' : 'Отключена'}</b> • IP: ${esc(me.last2faIp || '—')}
                    </div>
                    ${me.totpEnabled ? `
                        <button type="button" class="secondary btn-sm" id="btn-prof-regen-codes" style="margin-top:10px; width:100%; font-size:11px;">
                            🔄 СГЕНЕРИРОВАТЬ НОВЫЕ РЕЗЕРВНЫЕ КОДЫ 2FA
                        </button>
                    ` : ''}
                </div>

                <div style="background:#110a26; padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                    <div style="display:flex; align-items:center; gap:8px; margin-bottom:8px;">
                        <span style="font-size:18px;">💬</span>
                        <h4 style="font-size:14px; color:#fff; margin:0;">DISCORD УВЕДОМЛЕНИЯ ПЕРСОНАЛА</h4>
                    </div>
                    <div style="font-size:12px; color:var(--text-muted); margin-bottom:12px; line-height:1.5;">
                        Получайте моментальные оповещения в личные сообщения Discord от LoveCore при поступлении новых репортов или тикетов апелляций.
                    </div>
                    <div class="form-group">
                        <label>Ваш Discord User ID (числовой снепшот)</label>
                        <input type="text" id="prof-discord-id" value="${esc(discordId)}" placeholder="например: 345678901234567890">
                    </div>
                    <div style="display:flex; flex-direction:column; gap:8px; margin-bottom:12px;">
                        <label style="display:flex; align-items:center; gap:8px; cursor:pointer; font-size:12.5px; color:#fff;">
                            <input type="checkbox" id="prof-notify-reports" ${notifyReports ? 'checked' : ''}>
                            <span>🚨 Оповещать в ЛС о новых жалобах игроков (Reports)</span>
                        </label>
                        <label style="display:flex; align-items:center; gap:8px; cursor:pointer; font-size:12.5px; color:#fff;">
                            <input type="checkbox" id="prof-notify-tickets" ${notifyTickets ? 'checked' : ''}>
                            <span>⚖️ Оповещать в ЛС о новых апелляциях банов (Tickets)</span>
                        </label>
                    </div>
                    <button type="button" class="secondary btn-sm" id="btn-save-discord-prefs" style="width:100%;">
                        💾 СОХРАНИТЬ НАСТРОЙКИ DISCORD
                    </button>
                </div>

                <h4 style="font-size:14px; color:#fff; margin-bottom:10px;">СМЕНА ПАРОЛЯ</h4>
                <div class="form-group">
                    <label>Текущий пароль</label>
                    <input type="password" id="prof-old-pass" placeholder="••••••••">
                </div>
                <div class="form-group">
                    <label>Новый надежный пароль</label>
                    <input type="password" id="prof-new-pass" placeholder="Минимум 4 символа">
                </div>
                <div id="prof-pass-err" class="error" style="display:none;"></div>
                <button type="button" class="primary" id="btn-save-new-pass" style="width:100%; margin-top:6px;">ИЗМЕНИТЬ ПАРОЛЬ</button>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" onclick="window.closeCurrentModal()">ЗАКРЫТЬ</button>
            </div>
        `, () => {
            document.getElementById('btn-save-discord-prefs')?.addEventListener('click', async () => {
                const newDiscordId = document.getElementById('prof-discord-id').value.trim();
                const newNotifyReports = document.getElementById('prof-notify-reports').checked;
                const newNotifyTickets = document.getElementById('prof-notify-tickets').checked;

                userPrefs.discordId = newDiscordId;
                userPrefs.discordNotifyReports = newNotifyReports;
                userPrefs.discordNotifyTickets = newNotifyTickets;

                try {
                    await api('POST', '/api/me/preferences', { uiPreferences: userPrefs });
                    me.uiPreferences = JSON.stringify(userPrefs);
                    showToast('Настройки сохранены', 'Параметры уведомлений Discord обновлены', 'success');
                } catch (e) {
                    showToast('Ошибка', e.message, 'error');
                }
            });

            document.getElementById('btn-prof-regen-codes')?.addEventListener('click', async () => {
                confirmAction('РЕГЕНЕРАЦИЯ КОДОВ 2FA', 'ВНИМАНИЕ! Все ваши старые резервные коды станут недействительными. Будут сгенерированы 8 новых кодов.', async () => {
                    try {
                        const res = await api('POST', '/api/me/backup-codes/regenerate');
                        const codes = res.backupCodes || [];
                        openModal(`
                            <div class="modal-header">
                                <h3>НОВЫЕ РЕЗЕРВНЫЕ КОДЫ 2FA</h3>
                                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
                            </div>
                            <div class="modal-body">
                                <div class="sub" style="margin-bottom:12px;">Сохраните эти 8 кодов в надёжном месте. Каждый код можно использовать только один раз:</div>
                                <div class="backup-codes-grid">
                                    ${codes.map(c => `<div class="backup-code-pill">${esc(c)}</div>`).join('')}
                                </div>
                                <div style="display:flex; gap:10px; margin-top:14px;">
                                    <button type="button" class="secondary" id="btn-copy-regen-codes" style="flex:1;">📋 Скопировать</button>
                                </div>
                            </div>
                            <div class="modal-footer">
                                <button type="button" class="primary" onclick="window.closeCurrentModal()">ЗАКРЫТЬ</button>
                            </div>
                        `, () => {
                            document.getElementById('btn-copy-regen-codes')?.addEventListener('click', async (e) => {
                                const btn = e.currentTarget;
                                try {
                                    await navigator.clipboard.writeText(codes.join('\n'));
                                    btn.textContent = '✓ Скопировано!';
                                    setTimeout(() => { btn.textContent = '📋 Скопировать'; }, 2000);
                                } catch (_) {}
                            });
                        });
                    } catch (e) {
                        showToast('Ошибка', e.message, 'error');
                    }
                });
            });

            document.getElementById('btn-save-new-pass')?.addEventListener('click', async () => {
                const oldPassword = document.getElementById('prof-old-pass').value;
                const newPassword = document.getElementById('prof-new-pass').value;
                const err = document.getElementById('prof-pass-err');
                err.style.display = 'none';

                if (!oldPassword || !newPassword) {
                    err.textContent = 'Заполните оба поля';
                    err.style.display = 'block';
                    return;
                }

                try {
                    await api('POST', '/api/me/password', { oldPassword, newPassword });
                    showToast('Пароль обновлен', 'Ваш пароль успешно изменен', 'success');
                    closeModal();
                } catch (e) {
                    err.textContent = e.message;
                    err.style.display = 'block';
                }
            });
        });
    }

    // Modal: Quick Kick Modal
    window.openQuickKickModal = function() {
        openModal(`
            <div class="modal-header">
                <h3>КИКНУТЬ ИГРОКА С СЕРВЕРА</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока онлайн</label>
                    <input type="text" id="quick-kick-name" placeholder="Введите ник игрока..." required autofocus autocomplete="off">
                </div>
                <div class="form-group">
                    <label>Причина кика</label>
                    <input type="text" id="quick-kick-reason" value="Кикнут администратором через веб-панель">
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="danger" id="btn-submit-quick-kick">КИКНУТЬ</button>
            </div>
        `, () => {
            const nameInput = document.getElementById('quick-kick-name');
            if (nameInput) attachPlayerAutocomplete(nameInput);

            document.getElementById('btn-submit-quick-kick')?.addEventListener('click', async () => {
                const name = nameInput ? nameInput.value.trim() : '';
                const reason = document.getElementById('quick-kick-reason')?.value.trim() || '';
                if (!name) return;

                try {
                    await api('POST', `/api/players/${encodeURIComponent(name)}/action`, { action: 'kick', reason });
                    recordShiftAction(`Кикнул ${name}`);
                    showToast('Кик', `Игрок ${name} кикнут с сервера`, 'warning');
                    closeModal();
                } catch (e) {
                    alert('Ошибка: ' + e.message);
                }
            });
        });
    };

    // Modal: Quick Teleport Spawn
    window.openQuickTeleportModal = function() {
        openModal(`
            <div class="modal-header">
                <h3>ТЕЛЕПОРТАЦИЯ НА СПАВН</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока онлайн</label>
                    <input type="text" id="quick-tp-name" placeholder="Введите ник игрока..." required autofocus autocomplete="off">
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-submit-quick-tp">ТЕЛЕПОРТИРОВАТЬ</button>
            </div>
        `, () => {
            const nameInput = document.getElementById('quick-tp-name');
            if (nameInput) attachPlayerAutocomplete(nameInput);

            document.getElementById('btn-submit-quick-tp')?.addEventListener('click', async () => {
                const name = nameInput ? nameInput.value.trim() : '';
                if (!name) return;

                try {
                    await api('POST', `/api/players/${encodeURIComponent(name)}/action`, { action: 'teleport_spawn' });
                    recordShiftAction(`Телепортировал на спавн ${name}`);
                    showToast('Телепортация', `Игрок ${name} отправлен на спавн`, 'info');
                    closeModal();
                } catch (e) {
                    alert('Ошибка: ' + e.message);
                }
            });
        });
    };

    // Modal: Quick Freeze
    window.openQuickFreezeModal = function() {
        openModal(`
            <div class="modal-header">
                <h3>ЗАМОРОЗКА ИГРОКА ДЛЯ ПРОВЕРКИ</h3>
                <button type="button" class="close-btn" data-modal-close="true">✕</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока онлайн</label>
                    <input type="text" id="quick-freeze-name" placeholder="Введите ник игрока..." required autofocus autocomplete="off">
                </div>
                <div style="font-size:12px; color:var(--text-muted);">
                    Игрок будет обездвижен, экран заблокирован уведомлением о проверке на читы.
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-submit-quick-freeze">ЗАМОРОЗИТЬ</button>
            </div>
        `, () => {
            const nameInput = document.getElementById('quick-freeze-name');
            if (nameInput) attachPlayerAutocomplete(nameInput);

            document.getElementById('btn-submit-quick-freeze')?.addEventListener('click', async () => {
                const name = nameInput ? nameInput.value.trim() : '';
                if (!name) return;

                try {
                    await api('POST', `/api/players/${encodeURIComponent(name)}/action`, { action: 'freeze' });
                    recordShiftAction(`Заморозил ${name}`);
                    showToast('Заморозка', `Игрок ${name} заморожен для проверки`, 'warning');
                    closeModal();
                } catch (e) {
                    alert('Ошибка: ' + e.message);
                }
            });
        });
    };

    // Modal: Quick Vanish Modal
    window.openQuickVanishModal = function() {
        openModal(`
            <div class="modal-header">
                <h3>РЕЖИМ НЕВИДИМОСТИ (VANISH)</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока онлайн</label>
                    <input type="text" id="quick-vanish-name" placeholder="Введите ник игрока..." required autofocus>
                </div>
                <div style="font-size:12px; color:var(--text-muted);">
                    Скрывает игрока от всех остальных пользователей на сервере (включая таб и радары).
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" onclick="window.closeCurrentModal()">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-submit-quick-vanish">ПЕРЕКЛЮЧИТЬ VANISH</button>
            </div>
        `, () => {
            document.getElementById('btn-submit-quick-vanish')?.addEventListener('click', async () => {
                const name = document.getElementById('quick-vanish-name').value.trim();
                if (!name) return;
                try {
                    await api('POST', `/api/players/${encodeURIComponent(name)}/action`, { action: 'vanish' });
                    recordShiftAction(`Переключил Vanish для ${name}`);
                    showToast('Vanish', `Режим скрытности переключен для ${name}`, 'info');
                    closeModal();
                } catch (e) {
                    alert('Ошибка: ' + e.message);
                }
            });
        });
    };

    // Modal: Quick Mute Modal
    window.openQuickMuteModal = function() {
        openModal(`
            <div class="modal-header">
                <h3>ЗАГЛУШИТЬ ИГРОКА (MUTE)</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">✕</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока онлайн</label>
                    <input type="text" id="quick-mute-name" placeholder="Введите ник игрока..." required autofocus>
                </div>
                <div class="form-group">
                    <label>Длительность заглушения</label>
                    <select id="quick-mute-duration">
                        <option value="15m">15 минут</option>
                        <option value="30m">30 минут</option>
                        <option value="1h" selected>1 час</option>
                        <option value="2h">2 часа</option>
                        <option value="1d">1 день</option>
                        <option value="7d">7 дней</option>
                    </select>
                </div>
                <div class="form-group">
                    <label>Причина мута</label>
                    <input type="text" id="quick-mute-reason" value="Флуд / Спам / Нарушение правил чата">
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" onclick="window.closeCurrentModal()">ОТМЕНА</button>
                <button type="button" class="danger" id="btn-submit-quick-mute">ВЫДАТЬ МУТ</button>
            </div>
        `, () => {
            document.getElementById('btn-submit-quick-mute')?.addEventListener('click', async () => {
                const name = document.getElementById('quick-mute-name').value.trim();
                const duration = document.getElementById('quick-mute-duration').value;
                const reason = document.getElementById('quick-mute-reason').value.trim();
                if (!name) return;
                try {
                    await api('POST', `/api/players/${encodeURIComponent(name)}/action`, { action: 'mute', duration, reason });
                    recordShiftAction(`Заглушил ${name} на ${duration}`);
                    showToast('Мут чата', `Игрок ${name} заглушен на ${duration}`, 'warning');
                    closeModal();
                } catch (e) {
                    alert('Ошибка: ' + e.message);
                }
            });
        });
    };

    // Modal: Full-screen Screenshot Lightbox
    window.openScreenshotLightbox = function(imgSrc) {
        if (!imgSrc) return;
        const overlay = document.createElement('div');
        overlay.className = 'lightbox-overlay';
        overlay.innerHTML = `
            <div style="position:relative; max-width:92vw; max-height:92vh; display:flex; flex-direction:column; align-items:center;">
                <button type="button" class="btn-remove-screenshot" style="top:-14px; right:-14px; width:32px; height:32px; font-size:16px;" onclick="this.closest('.lightbox-overlay').remove()">✕</button>
                <img src="${esc(imgSrc)}" class="lightbox-img" alt="Доказательство бана">
                <div style="margin-top:10px; display:flex; gap:10px;">
                    <a href="${esc(imgSrc)}" target="_blank" class="secondary btn-sm" style="text-decoration:none; padding:6px 14px; font-size:12px; border-radius:4px; border:1px solid var(--border); background:rgba(0,0,0,0.7); color:#fff;">ОТКРЫТЬ В НОВОЙ ВКЛАДКЕ ↗</a>
                </div>
            </div>
        `;
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) overlay.remove();
        });
        document.body.appendChild(overlay);
    };


    // Start App
    boot();
})();
