(() => {
    'use strict';

    const app = document.getElementById('app');
    let token = localStorage.getItem('lwa_token');
    let me = null;
    const pollers = {};
    const seenLogIds = { server: new Set(), web: new Set() };

    const NAV_ITEMS = [
        { key: 'stats', label: '📊 Статистика', perm: 'VIEW_STATS' },
        { key: 'server-logs', label: '📜 Серверные логи', perm: 'VIEW_SERVER_LOGS' },
        { key: 'web-logs', label: '🔐 Логи панели', perm: 'VIEW_WEB_LOGS' },
        { key: 'console', label: '⌨️ Консоль', perm: 'EXECUTE_COMMANDS' },
        { key: 'vesuvio', label: '🛡️ Vesuvio', perm: 'VIEW_VESUVIO' },
        { key: 'admins', label: '👥 Администраторы', perm: 'MANAGE_ADMINS' },
        { key: 'roles', label: '🎭 Роли', perm: 'MANAGE_ROLES' },
    ];

    const ALL_PERMISSIONS = [
        'VIEW_SERVER_LOGS', 'VIEW_WEB_LOGS', 'VIEW_STATS', 'EXECUTE_COMMANDS',
        'MANAGE_ADMINS', 'MANAGE_ROLES', 'MANAGE_PASSWORDS',
        'VIEW_VESUVIO', 'VIEW_VESUVIO_ADVANCED', 'MANAGE_VESUVIO'
    ];

    const PERMISSION_LABELS = {
        VIEW_SERVER_LOGS: 'Просмотр серверных логов',
        VIEW_WEB_LOGS: 'Просмотр логов панели',
        VIEW_STATS: 'Просмотр статистики',
        EXECUTE_COMMANDS: 'Выполнение команд',
        MANAGE_ADMINS: 'Управление администраторами',
        MANAGE_ROLES: 'Управление ролями',
        MANAGE_PASSWORDS: 'Сброс паролей',
        VIEW_VESUVIO: 'Vesuvio: подозреваемые и наказания',
        VIEW_VESUVIO_ADVANCED: 'Vesuvio: расширенная телеметрия',
        MANAGE_VESUVIO: 'Vesuvio: управление (сброс VL, подозрения)',
    };

    function esc(s) {
        const div = document.createElement('div');
        div.textContent = s == null ? '' : String(s);
        return div.innerHTML;
    }

    function fmtTime(ts) {
        const d = new Date(ts * 1000);
        return d.toLocaleString('ru-RU');
    }

    // Vesuvio timestamps are System.currentTimeMillis() (milliseconds), unlike this panel's own
    // logs which store seconds - keep them separate rather than guessing units.
    function fmtTimeMs(ts) {
        return new Date(ts).toLocaleString('ru-RU');
    }

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

    function clearPollers() {
        Object.values(pollers).forEach(clearInterval);
        for (const k in pollers) delete pollers[k];
    }

    function setToken(newToken) {
        token = newToken;
        if (token) localStorage.setItem('lwa_token', token);
        else localStorage.removeItem('lwa_token');
    }

    function hasPerm(perm) {
        return me && me.permissions && me.permissions.includes(perm);
    }

    // ---------- Boot ----------

    async function boot() {
        if (!token) {
            await renderAuthGate();
            return;
        }
        try {
            me = await api('GET', '/api/me');
            renderDashboard();
        } catch (e) {
            setToken(null);
            await renderAuthGate();
        }
    }

    async function renderAuthGate() {
        let status;
        try {
            status = await api('GET', '/api/auth/status');
        } catch (e) {
            app.innerHTML = `<div class="auth-screen"><div class="auth-card"><p class="error">${esc(e.message)}</p></div></div>`;
            return;
        }
        if (!status.ownerExists) {
            renderSetupOwner();
        } else {
            renderLogin();
        }
    }

    // ---------- Auth screens ----------

    function renderSetupOwner() {
        app.innerHTML = `
            <div class="auth-screen"><div class="auth-card">
                <h1>Добро пожаловать!</h1>
                <p class="sub">Вы первый, кто открывает эту панель. Введите свой ник в Minecraft, чтобы стать Управляющим.</p>
                <div class="field"><label>Ник в Minecraft</label><input id="su-username" type="text" placeholder="Steve"></div>
                <p class="error" id="su-error"></p>
                <button class="primary" id="su-submit">Стать Управляющим</button>
            </div></div>`;
        document.getElementById('su-submit').addEventListener('click', async () => {
            const username = document.getElementById('su-username').value.trim();
            const errorEl = document.getElementById('su-error');
            if (!username) { errorEl.textContent = 'Введите ник'; return; }
            try {
                await api('POST', '/api/auth/setup-owner', { username });
                renderSetPassword(username);
            } catch (e) {
                errorEl.textContent = e.message;
            }
        });
    }

    function renderLogin() {
        app.innerHTML = `
            <div class="auth-screen"><div class="auth-card">
                <h1>Вход в панель</h1>
                <p class="sub">LoveWebAdmin</p>
                <div class="field"><label>Никнейм</label><input id="li-username" type="text"></div>
                <div class="field"><label>Пароль</label><input id="li-password" type="password"></div>
                <p class="error" id="li-error"></p>
                <button class="primary" id="li-submit">Войти</button>
            </div></div>`;

        const submit = async () => {
            const username = document.getElementById('li-username').value.trim();
            const password = document.getElementById('li-password').value;
            const errorEl = document.getElementById('li-error');
            if (!username || !password) { errorEl.textContent = 'Заполните оба поля'; return; }
            try {
                const data = await api('POST', '/api/auth/login', { username, password });
                if (data.status === 'NEED_SET_PASSWORD') {
                    renderSetPassword(username);
                    return;
                }
                setToken(data.token);
                me = await api('GET', '/api/me');
                renderDashboard();
            } catch (e) {
                errorEl.textContent = e.message;
            }
        };
        document.getElementById('li-submit').addEventListener('click', submit);
        document.getElementById('li-password').addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
    }

    function renderSetPassword(username) {
        app.innerHTML = `
            <div class="auth-screen"><div class="auth-card">
                <h1>Создайте пароль</h1>
                <p class="sub">Это первый вход для «${esc(username)}». Задайте пароль для входа в дальнейшем.</p>
                <div class="field"><label>Новый пароль</label><input id="sp-password" type="password"></div>
                <div class="field"><label>Повторите пароль</label><input id="sp-password2" type="password"></div>
                <p class="error" id="sp-error"></p>
                <button class="primary" id="sp-submit">Создать пароль и войти</button>
            </div></div>`;
        document.getElementById('sp-submit').addEventListener('click', async () => {
            const p1 = document.getElementById('sp-password').value;
            const p2 = document.getElementById('sp-password2').value;
            const errorEl = document.getElementById('sp-error');
            if (!p1 || p1.length < 4) { errorEl.textContent = 'Пароль слишком короткий'; return; }
            if (p1 !== p2) { errorEl.textContent = 'Пароли не совпадают'; return; }
            try {
                const data = await api('POST', '/api/auth/set-password', { username, password: p1 });
                setToken(data.token);
                me = await api('GET', '/api/me');
                renderDashboard();
            } catch (e) {
                errorEl.textContent = e.message;
            }
        });
    }

    // ---------- Dashboard shell ----------

    function renderDashboard() {
        const items = NAV_ITEMS.filter(item => hasPerm(item.perm));
        app.innerHTML = `
            <div class="layout">
                <div class="sidebar" id="sidebar">
                    <div class="brand">LoveWebAdmin</div>
                    <nav id="nav">
                        ${items.map(item => `<div class="nav-item" data-key="${item.key}">${item.label}</div>`).join('')}
                    </nav>
                    <div class="footer">
                        <div>${esc(me.username)}</div>
                        <div style="font-size:11px">${esc(me.role)}</div>
                        <button id="logout-btn">Выйти</button>
                    </div>
                </div>
                <div class="main">
                    <button class="menu-toggle" id="menu-toggle">☰ Меню</button>
                    <div id="main-content"></div>
                </div>
            </div>`;

        document.getElementById('logout-btn').addEventListener('click', async () => {
            try { await api('POST', '/api/auth/logout'); } catch (e) { /* ignore */ }
            setToken(null);
            clearPollers();
            renderLogin();
        });

        document.getElementById('menu-toggle').addEventListener('click', () => {
            document.getElementById('sidebar').classList.toggle('open');
        });

        document.getElementById('nav').addEventListener('click', (e) => {
            const item = e.target.closest('.nav-item');
            if (!item) return;
            navigateTo(item.dataset.key);
            document.getElementById('sidebar').classList.remove('open');
        });

        if (items.length > 0) {
            navigateTo(items[0].key);
        } else {
            document.getElementById('main-content').innerHTML = '<p>У вас нет доступных разделов.</p>';
        }
    }

    function navigateTo(key) {
        clearPollers();
        document.querySelectorAll('.nav-item').forEach(el => el.classList.toggle('active', el.dataset.key === key));

        const item = NAV_ITEMS.find(i => i.key === key);
        if (item) {
            api('POST', '/api/logs/section', { section: item.label.replace(/^\S+\s/, '') }).catch(() => {});
        }

        switch (key) {
            case 'stats': renderStatsSection(); break;
            case 'server-logs': renderLogsSection('server'); break;
            case 'web-logs': renderLogsSection('web'); break;
            case 'console': renderConsoleSection(); break;
            case 'vesuvio': renderVesuvioSection(); break;
            case 'admins': renderAdminsSection(); break;
            case 'roles': renderRolesSection(); break;
        }
    }

    // ---------- Stats ----------

    function tpsClass(value) {
        if (value >= 18) return 'tps-green';
        if (value >= 15) return 'tps-yellow';
        return 'tps-red';
    }

    async function renderStatsSection() {
        const main = document.getElementById('main-content');
        main.innerHTML = '<h2>Статистика</h2><div id="stats-body">Загрузка...</div>';

        const refresh = async () => {
            try {
                const s = await api('GET', '/api/stats');
                const tpsLabels = ['1 мин', '5 мин', '15 мин'];
                document.getElementById('stats-body').innerHTML = `
                    <div class="cards">
                        ${s.tps.map((v, i) => `
                            <div class="stat-card">
                                <div class="label">TPS (${tpsLabels[i]})</div>
                                <div class="value ${tpsClass(v)}">${v.toFixed(2)}</div>
                            </div>`).join('')}
                        <div class="stat-card">
                            <div class="label">Игроков онлайн</div>
                            <div class="value">${s.onlinePlayers} / ${s.maxPlayers}</div>
                        </div>
                        <div class="stat-card">
                            <div class="label">Версия сервера</div>
                            <div class="value" style="font-size:14px">${esc(s.serverVersion)}</div>
                        </div>
                        <div class="stat-card">
                            <div class="label">Аптайм</div>
                            <div class="value" style="font-size:14px">${formatUptime(s.uptime)}</div>
                        </div>
                    </div>
                    <div class="table-wrap">
                        <table>
                            <thead><tr><th>Ник</th><th>Пинг</th></tr></thead>
                            <tbody>
                                ${s.players.length ? s.players.map(p => `<tr><td>${esc(p.name)}</td><td>${p.ping} мс</td></tr>`).join('') : '<tr><td colspan="2">Нет игроков онлайн</td></tr>'}
                            </tbody>
                        </table>
                    </div>`;
            } catch (e) {
                document.getElementById('stats-body').innerHTML = `<p class="error">${esc(e.message)}</p>`;
            }
        };

        await refresh();
        pollers.stats = setInterval(refresh, 3000);
    }

    function formatUptime(seconds) {
        const h = Math.floor(seconds / 3600);
        const m = Math.floor((seconds % 3600) / 60);
        return `${h}ч ${m}м`;
    }

    // ---------- Logs (server / web) ----------

    function renderLogsSection(type) {
        const main = document.getElementById('main-content');
        const title = type === 'server' ? 'Серверные логи' : 'Логи панели';
        const state = { mode: 'live', offset: 0, limit: 50, search: '', rows: [] };

        main.innerHTML = `
            <h2>${title}</h2>
            <div class="toolbar">
                <button id="mode-live">Live</button>
                <button id="mode-history">История</button>
                <span class="tag live" id="live-tag" style="display:none">● LIVE</span>
                <div class="spacer"></div>
                <input type="text" id="search-input" placeholder="Поиск по тексту..." style="display:none">
            </div>
            <div id="logs-pagination" style="display:none; margin-bottom:10px;">
                <button id="prev-page">← Назад</button>
                <button id="next-page">Вперёд →</button>
            </div>
            <div class="table-wrap">
                <table>
                    <thead><tr><th>Время</th>${type === 'web' ? '<th>Администратор</th>' : ''}<th>${type === 'web' ? 'Действие' : 'Сообщение'}</th></tr></thead>
                    <tbody id="logs-tbody"></tbody>
                </table>
            </div>`;

        function renderRows(rows) {
            const tbody = document.getElementById('logs-tbody');
            if (!rows.length) {
                tbody.innerHTML = `<tr><td colspan="3">Нет записей</td></tr>`;
                return;
            }
            tbody.innerHTML = rows.map(r => {
                const isNew = state.mode === 'live' && !seenLogIds[type].has(r.id);
                seenLogIds[type].add(r.id);
                const cells = type === 'web'
                    ? `<td>${fmtTime(r.timestamp)}</td><td>${esc(r.actor)}</td><td>${esc(r.action)}</td>`
                    : `<td>${fmtTime(r.timestamp)}</td><td>${esc(r.action)}</td>`;
                return `<tr class="${isNew ? 'log-new' : ''}">${cells}</tr>`;
            }).join('');
        }

        async function fetchLive() {
            const params = type === 'server' ? '?mode=recent' : '?limit=50&offset=0';
            const rows = await api('GET', `/api/logs/${type}${params}`);
            state.rows = rows;
            renderRows(applySearch(rows));
        }

        async function fetchHistory() {
            const rows = await api('GET', `/api/logs/${type}?limit=${state.limit}&offset=${state.offset}`);
            state.rows = rows;
            renderRows(applySearch(rows));
        }

        function applySearch(rows) {
            if (!state.search) return rows;
            const q = state.search.toLowerCase();
            return rows.filter(r => (r.action || '').toLowerCase().includes(q) || (r.actor || '').toLowerCase().includes(q));
        }

        function setMode(mode) {
            state.mode = mode;
            seenLogIds[type].clear();
            clearPollers();
            document.getElementById('live-tag').style.display = mode === 'live' ? 'inline-block' : 'none';
            document.getElementById('search-input').style.display = mode === 'history' ? 'inline-block' : 'none';
            document.getElementById('logs-pagination').style.display = mode === 'history' ? 'block' : 'none';
            if (mode === 'live') {
                fetchLive().catch(showLogsError);
                pollers.logs = setInterval(() => fetchLive().catch(showLogsError), 3000);
            } else {
                fetchHistory().catch(showLogsError);
            }
        }

        function showLogsError(e) {
            document.getElementById('logs-tbody').innerHTML = `<tr><td colspan="3" class="error">${esc(e.message)}</td></tr>`;
        }

        document.getElementById('mode-live').addEventListener('click', () => setMode('live'));
        document.getElementById('mode-history').addEventListener('click', () => setMode('history'));
        document.getElementById('search-input').addEventListener('input', (e) => {
            state.search = e.target.value;
            renderRows(applySearch(state.rows));
        });
        document.getElementById('prev-page').addEventListener('click', () => {
            state.offset = Math.max(0, state.offset - state.limit);
            fetchHistory().catch(showLogsError);
        });
        document.getElementById('next-page').addEventListener('click', () => {
            state.offset += state.limit;
            fetchHistory().catch(showLogsError);
        });

        setMode('live');
    }

    // ---------- Console ----------

    function renderConsoleSection() {
        const main = document.getElementById('main-content');
        const historyKey = 'lwa_console_history';
        const history = JSON.parse(sessionStorage.getItem(historyKey) || '[]');

        main.innerHTML = `
            <h2>Консоль</h2>
            <div class="console-input">
                <input type="text" id="cmd-input" placeholder="Введите команду без /">
                <button class="primary" id="cmd-run">Выполнить</button>
            </div>
            <div class="console-history" id="cmd-history"></div>`;

        function renderHistory() {
            const el = document.getElementById('cmd-history');
            el.innerHTML = history.length
                ? history.map(h => `<div class="line"><span class="cmd">/${esc(h.command)}</span> — ${esc(h.result)}</div>`).join('')
                : '<div class="line">История пуста</div>';
            el.scrollTop = el.scrollHeight;
        }

        async function runCommand() {
            const input = document.getElementById('cmd-input');
            const command = input.value.trim().replace(/^\//, '');
            if (!command) return;
            input.value = '';
            try {
                const data = await api('POST', '/api/command', { command });
                history.push({ command, result: data.message || 'Выполнено' });
            } catch (e) {
                history.push({ command, result: 'Ошибка: ' + e.message });
            }
            sessionStorage.setItem(historyKey, JSON.stringify(history));
            renderHistory();
        }

        document.getElementById('cmd-run').addEventListener('click', runCommand);
        document.getElementById('cmd-input').addEventListener('keydown', (e) => { if (e.key === 'Enter') runCommand(); });
        renderHistory();
    }

    // ---------- Vesuvio AntiCheat ----------

    function vesuvioRiskClass(v) {
        if (v >= 75) return 'tps-red';
        if (v >= 40) return 'tps-yellow';
        return 'tps-green';
    }

    async function renderVesuvioSection() {
        const main = document.getElementById('main-content');
        main.innerHTML = '<h2>🛡️ Vesuvio AntiCheat</h2><div id="vesuvio-body">Загрузка...</div>';
        const bodyEl = document.getElementById('vesuvio-body');

        let status;
        try {
            status = await api('GET', '/api/vesuvio/status');
        } catch (e) {
            bodyEl.innerHTML = `<p class="error">${esc(e.message)}</p>`;
            return;
        }
        if (!status.available) {
            bodyEl.innerHTML = '<p>Vesuvio не установлен или ещё не загружен на сервере.</p>';
            return;
        }

        const advanced = hasPerm('VIEW_VESUVIO_ADVANCED');
        const manage = hasPerm('MANAGE_VESUVIO');
        const suspectCols = manage ? 7 : 6;

        bodyEl.innerHTML = `
            ${advanced ? '<div class="cards" id="vs-engine-cards"></div>' : ''}
            <h3>Подозреваемые</h3>
            <div class="table-wrap"><table>
                <thead><tr>
                    <th>Игрок</th><th>Статус</th><th>VL</th><th>Риск</th><th>Доверие</th><th>Клиент</th>
                    ${manage ? '<th>Действия</th>' : ''}
                </tr></thead>
                <tbody id="vs-suspects-tbody"><tr><td colspan="${suspectCols}">Загрузка...</td></tr></tbody>
            </table></div>

            <h3 style="margin-top:22px">Последние наказания</h3>
            <div class="table-wrap"><table>
                <thead><tr><th>Время</th><th>Игрок</th><th>Действие</th><th>Причина</th></tr></thead>
                <tbody id="vs-punishments-tbody"><tr><td colspan="4">Загрузка...</td></tr></tbody>
            </table></div>

            ${advanced ? `
            <h3 style="margin-top:22px">Лог флагов <span class="tag">расширенно</span></h3>
            <div class="table-wrap"><table>
                <thead><tr><th>Время</th><th>Игрок</th><th>Проверка</th><th>VL</th><th>Увер.</th><th>Объяснение</th></tr></thead>
                <tbody id="vs-violations-tbody"><tr><td colspan="6">Загрузка...</td></tr></tbody>
            </table></div>` : ''}
        `;

        function renderSuspectRow(s) {
            const clickable = advanced ? ' class="vs-clickable"' : '';
            return `<tr${clickable} data-uuid="${esc(s.uuid)}">
                <td>${esc(s.name)}</td>
                <td>${esc(s.status)}</td>
                <td>${s.vl.toFixed(1)}</td>
                <td class="${vesuvioRiskClass(s.risk)}">${s.risk.toFixed(1)}</td>
                <td>${s.trust.toFixed(1)}</td>
                <td>${esc(s.brand)}</td>
                ${manage ? `<td class="actions-cell">
                    <button class="icon-btn" data-vs-reset="${esc(s.uuid)}" title="Сбросить VL">↺</button>
                    ${s.manualSuspect
                        ? `<button class="icon-btn" data-vs-unsuspect="${esc(s.uuid)}" title="Снять подозрение">✔</button>`
                        : `<button class="icon-btn danger" data-vs-suspect="${esc(s.uuid)}" title="Пометить подозреваемым">⚠</button>`}
                </td>` : ''}
            </tr>`;
        }

        async function refreshSuspects() {
            try {
                const suspects = await api('GET', '/api/vesuvio/suspects');
                document.getElementById('vs-suspects-tbody').innerHTML = suspects.length
                    ? suspects.map(renderSuspectRow).join('')
                    : `<tr><td colspan="${suspectCols}">Нет подозреваемых</td></tr>`;
                wireSuspectRowActions(suspects);
            } catch (e) {
                document.getElementById('vs-suspects-tbody').innerHTML =
                    `<tr><td colspan="${suspectCols}" class="error">${esc(e.message)}</td></tr>`;
            }
        }

        function wireSuspectRowActions(suspects) {
            if (advanced) {
                document.querySelectorAll('#vs-suspects-tbody tr[data-uuid]').forEach(row => {
                    row.addEventListener('click', (e) => {
                        if (e.target.closest('button')) return;
                        openVesuvioPlayerModal(row.dataset.uuid);
                    });
                });
            }
            if (manage) {
                document.querySelectorAll('[data-vs-reset]').forEach(btn => {
                    btn.addEventListener('click', async (e) => {
                        e.stopPropagation();
                        try {
                            await api('POST', `/api/vesuvio/player/${btn.dataset.vsReset}/reset-vl`);
                            refreshSuspects();
                        } catch (err) { alert(err.message); }
                    });
                });
                document.querySelectorAll('[data-vs-suspect]').forEach(btn => {
                    btn.addEventListener('click', async (e) => {
                        e.stopPropagation();
                        try {
                            await api('POST', `/api/vesuvio/player/${btn.dataset.vsSuspect}/suspect`, { suspect: true });
                            refreshSuspects();
                        } catch (err) { alert(err.message); }
                    });
                });
                document.querySelectorAll('[data-vs-unsuspect]').forEach(btn => {
                    btn.addEventListener('click', async (e) => {
                        e.stopPropagation();
                        try {
                            await api('POST', `/api/vesuvio/player/${btn.dataset.vsUnsuspect}/suspect`, { suspect: false });
                            refreshSuspects();
                        } catch (err) { alert(err.message); }
                    });
                });
            }
        }

        async function refreshPunishments() {
            try {
                const rows = await api('GET', '/api/vesuvio/punishments?limit=50');
                document.getElementById('vs-punishments-tbody').innerHTML = rows.length
                    ? rows.map(p => `<tr>
                        <td>${fmtTimeMs(p.timestamp)}</td>
                        <td>${esc(p.name)}</td>
                        <td>${esc(p.action)}</td>
                        <td>${esc(p.reason)}</td>
                    </tr>`).join('')
                    : '<tr><td colspan="4">Наказаний ещё не было</td></tr>';
            } catch (e) {
                document.getElementById('vs-punishments-tbody').innerHTML = `<tr><td colspan="4" class="error">${esc(e.message)}</td></tr>`;
            }
        }

        async function refreshAdvanced() {
            if (!advanced) return;
            try {
                const engine = await api('GET', '/api/vesuvio/engine');
                document.getElementById('vs-engine-cards').innerHTML = `
                    <div class="stat-card"><div class="label">Онлайн под наблюдением</div><div class="value">${engine.onlineTrackedPlayers}</div></div>
                    <div class="stat-card"><div class="label">Подозреваемых</div><div class="value">${engine.suspectsCount}</div></div>
                    <div class="stat-card"><div class="label">ONNX модели</div><div class="value" style="font-size:14px">Клик: ${engine.clickModelLoaded ? '✅' : '❌'} / Прицел: ${engine.aimModelLoaded ? '✅' : '❌'}</div></div>
                    <div class="stat-card"><div class="label">Инференсов ONNX</div><div class="value">${engine.onnxTotalInferences}</div></div>
                    <div class="stat-card"><div class="label">Датасет самообучения</div><div class="value">${engine.datasetSize}</div></div>
                    <div class="stat-card"><div class="label">Обучено сэмплов</div><div class="value">${engine.classifierTrainedSamples}</div></div>
                    <div class="stat-card"><div class="label">Всего флагов</div><div class="value">${engine.totalFlagsLogged}</div></div>
                    <div class="stat-card"><div class="label">Автосбор датасета</div><div class="value" style="font-size:14px">${engine.autoCollectionEnabled ? 'Включён' : 'Выключен'}</div></div>
                `;
            } catch (e) {
                document.getElementById('vs-engine-cards').innerHTML = `<p class="error">${esc(e.message)}</p>`;
            }

            try {
                const violations = await api('GET', '/api/vesuvio/violations?limit=100');
                document.getElementById('vs-violations-tbody').innerHTML = violations.length
                    ? violations.map(v => `<tr>
                        <td>${fmtTimeMs(v.timestamp)}</td>
                        <td>${esc(v.name)}</td>
                        <td>${esc(v.checkName)}</td>
                        <td>${v.vl.toFixed(1)}</td>
                        <td>${(v.confidence * 100).toFixed(0)}%</td>
                        <td>${esc(v.explanation)}</td>
                    </tr>`).join('')
                    : '<tr><td colspan="6">Флагов ещё не было</td></tr>';
            } catch (e) {
                document.getElementById('vs-violations-tbody').innerHTML = `<tr><td colspan="6" class="error">${esc(e.message)}</td></tr>`;
            }
        }

        async function refreshAll() {
            await Promise.all([refreshSuspects(), refreshPunishments(), refreshAdvanced()]);
        }

        await refreshAll();
        pollers.vesuvio = setInterval(refreshAll, 5000);
    }

    async function openVesuvioPlayerModal(uuid) {
        openModal('<h3>Профиль игрока</h3><p>Загрузка...</p>', () => {});
        try {
            const d = await api('GET', `/api/vesuvio/player/${uuid}`);
            if (!d) {
                openModal(`<h3>Профиль игрока</h3><p>Игрок сейчас не отслеживается (не в сети).</p><div class="actions"><button id="vs-modal-close">Закрыть</button></div>`, () => {
                    document.getElementById('vs-modal-close').addEventListener('click', closeModal);
                });
                return;
            }
            openModal(`
                <h3>🛡️ ${esc(d.name)}</h3>
                <div class="field"><label>VL / Риск / Доверие / Sensitivity</label>
                    <div>${d.vl.toFixed(1)} / ${d.risk.toFixed(1)} / ${d.trust.toFixed(1)} / ${d.sensitivity.toFixed(2)}x</div>
                </div>
                <div class="field"><label>Клиент / Последний чек</label>
                    <div>${esc(d.brand)} / ${esc(d.lastTriggeredCheck)}</div>
                </div>
                <div class="field"><label>CPS / StdDev / DupRatio / Entropy</label>
                    <div>${d.cps.toFixed(1)} / ${d.stdDevMs.toFixed(1)}ms / ${(d.dupRatio * 100).toFixed(0)}% / ${d.entropy.toFixed(2)}</div>
                </div>
                <div class="field"><label>ONNX / Self-Learn вероятность</label>
                    <div>${(d.mlProbability * 100).toFixed(1)}% / ${(d.selfLearnProbability * 100).toFixed(1)}%</div>
                </div>
                <div class="field"><label>AirTicks / ΔY / OnGround / В бою</label>
                    <div>${d.airTicks} / ${d.lastDeltaY.toFixed(2)} / ${d.onGround ? 'да' : 'нет'} / ${d.inCombat ? 'да' : 'нет'}</div>
                </div>
                <div class="field"><label>Буферы клика / прицела / GCD streak / Perfect-aim streak</label>
                    <div>${d.clickBufferCount}/64 / ${d.aimBufferCount}/64 / ${d.gcdSuspiciousStreak} / ${d.perfectAimStreak}</div>
                </div>
                <div class="field"><label>Ручное подозрение</label><div>${d.manualSuspect ? 'да' : 'нет'}</div></div>
                <div class="actions"><button id="vs-modal-close">Закрыть</button></div>
            `, () => {
                document.getElementById('vs-modal-close').addEventListener('click', closeModal);
            });
        } catch (e) {
            openModal(`<h3>Профиль игрока</h3><p class="error">${esc(e.message)}</p><div class="actions"><button id="vs-modal-close">Закрыть</button></div>`, () => {
                document.getElementById('vs-modal-close').addEventListener('click', closeModal);
            });
        }
    }

    // ---------- Admins ----------

    async function renderAdminsSection() {
        const main = document.getElementById('main-content');
        main.innerHTML = `
            <h2>Администраторы</h2>
            <div class="toolbar"><div class="spacer"></div><button class="primary" id="add-admin-btn">Добавить</button></div>
            <div class="table-wrap"><table>
                <thead><tr><th>Ник</th><th>Роль</th><th>Последний вход</th><th>Действия</th></tr></thead>
                <tbody id="admins-tbody"><tr><td colspan="4">Загрузка...</td></tr></tbody>
            </table></div>`;

        let roles = [];
        try {
            roles = await api('GET', '/api/roles');
        } catch (e) { /* ignore, list still works without role names */ }

        async function load() {
            try {
                const admins = await api('GET', '/api/admins');
                document.getElementById('admins-tbody').innerHTML = admins.map(a => `
                    <tr>
                        <td>${esc(a.username)}</td>
                        <td>${esc(a.roleName || a.roleId)}</td>
                        <td>${a.lastLoginAt ? fmtTime(a.lastLoginAt) : 'Никогда'}</td>
                        <td class="actions-cell">
                            ${hasPerm('MANAGE_PASSWORDS') ? `<button class="icon-btn" data-reset="${a.id}" title="Сбросить пароль">🔑</button>` : ''}
                            <button class="icon-btn danger" data-delete="${a.id}" data-username="${esc(a.username)}" ${a.username === me.username ? 'disabled title="Нельзя удалить себя"' : ''}>🗑</button>
                        </td>
                    </tr>`).join('') || '<tr><td colspan="4">Нет администраторов</td></tr>';

                document.querySelectorAll('[data-delete]').forEach(btn => {
                    btn.addEventListener('click', async () => {
                        if (btn.disabled) return;
                        if (!confirm(`Удалить администратора ${btn.dataset.username}?`)) return;
                        try {
                            await api('DELETE', `/api/admins/${btn.dataset.delete}`);
                            load();
                        } catch (e) { alert(e.message); }
                    });
                });
                document.querySelectorAll('[data-reset]').forEach(btn => {
                    btn.addEventListener('click', async () => {
                        if (!confirm('Сбросить пароль этого администратора?')) return;
                        try {
                            await api('DELETE', `/api/admins/${btn.dataset.reset}/password`);
                            alert('Пароль сброшен.');
                        } catch (e) { alert(e.message); }
                    });
                });
            } catch (e) {
                document.getElementById('admins-tbody').innerHTML = `<tr><td colspan="4" class="error">${esc(e.message)}</td></tr>`;
            }
        }

        document.getElementById('add-admin-btn').addEventListener('click', () => {
            openModal(`
                <h3>Добавить администратора</h3>
                <div class="field"><label>Ник в Minecraft</label><input id="m-username" type="text"></div>
                <div class="field"><label>Роль</label>
                    <select id="m-role">${roles.filter(r => !r.isOwner).map(r => `<option value="${r.id}">${esc(r.name)}</option>`).join('')}</select>
                </div>
                <p class="error" id="m-error"></p>
                <div class="actions"><button id="m-cancel">Отмена</button><button class="primary" id="m-submit">Добавить</button></div>
            `, () => {
                document.getElementById('m-submit').addEventListener('click', async () => {
                    const username = document.getElementById('m-username').value.trim();
                    const roleId = parseInt(document.getElementById('m-role').value, 10);
                    if (!username) { document.getElementById('m-error').textContent = 'Введите ник'; return; }
                    try {
                        await api('POST', '/api/admins', { username, roleId });
                        closeModal();
                        load();
                    } catch (e) {
                        document.getElementById('m-error').textContent = e.message;
                    }
                });
                document.getElementById('m-cancel').addEventListener('click', closeModal);
            });
        });

        load();
    }

    // ---------- Roles ----------

    async function renderRolesSection() {
        const main = document.getElementById('main-content');
        main.innerHTML = `
            <h2>Роли</h2>
            <div class="toolbar"><div class="spacer"></div><button class="primary" id="add-role-btn">Создать роль</button></div>
            <div class="table-wrap"><table>
                <thead><tr><th>Название</th><th>LP группа</th><th>Права</th><th>Действия</th></tr></thead>
                <tbody id="roles-tbody"><tr><td colspan="4">Загрузка...</td></tr></tbody>
            </table></div>`;

        async function load() {
            try {
                const roles = await api('GET', '/api/roles');
                document.getElementById('roles-tbody').innerHTML = roles.map(r => `
                    <tr>
                        <td>${r.isOwner ? `<span class="badge-owner">${esc(r.name)}</span>` : esc(r.name)}</td>
                        <td>${esc(r.lpGroup || '—')}</td>
                        <td>${r.permissions.map(p => `<span class="tag">${esc(PERMISSION_LABELS[p] || p)}</span>`).join(' ')}</td>
                        <td class="actions-cell">
                            ${r.isOwner ? '<span style="color:var(--text-dim)">Только просмотр</span>' : `
                                <button class="icon-btn" data-edit="${r.id}">✏️</button>
                                <button class="icon-btn danger" data-delete="${r.id}" data-name="${esc(r.name)}">🗑</button>`}
                        </td>
                    </tr>`).join('') || '<tr><td colspan="4">Нет ролей</td></tr>';

                document.querySelectorAll('[data-edit]').forEach(btn => {
                    btn.addEventListener('click', () => {
                        const role = roles.find(r => r.id === parseInt(btn.dataset.edit, 10));
                        openRoleModal(role, load);
                    });
                });
                document.querySelectorAll('[data-delete]').forEach(btn => {
                    btn.addEventListener('click', async () => {
                        if (!confirm(`Удалить роль ${btn.dataset.name}?`)) return;
                        try {
                            await api('DELETE', `/api/roles/${btn.dataset.delete}`);
                            load();
                        } catch (e) { alert(e.message); }
                    });
                });
            } catch (e) {
                document.getElementById('roles-tbody').innerHTML = `<tr><td colspan="4" class="error">${esc(e.message)}</td></tr>`;
            }
        }

        document.getElementById('add-role-btn').addEventListener('click', () => openRoleModal(null, load));
        load();
    }

    function openRoleModal(role, onSaved) {
        const isEdit = !!role;
        const selected = role ? new Set(role.permissions) : new Set();

        openModal(`
            <h3>${isEdit ? 'Редактировать роль' : 'Создать роль'}</h3>
            <div class="field"><label>Название роли</label><input id="r-name" type="text" value="${role ? esc(role.name) : ''}" ${isEdit ? 'disabled' : ''}></div>
            <div class="field"><label>LP группа (необязательно)</label><input id="r-lpgroup" type="text" value="${role ? esc(role.lpGroup || '') : ''}"></div>
            <div class="field"><label>Права</label>
                ${ALL_PERMISSIONS.map(p => `
                    <div class="checkbox-row">
                        <input type="checkbox" id="perm-${p}" ${selected.has(p) ? 'checked' : ''}>
                        <label for="perm-${p}" style="margin:0">${esc(PERMISSION_LABELS[p])}</label>
                    </div>`).join('')}
            </div>
            <p class="error" id="r-error"></p>
            <div class="actions"><button id="r-cancel">Отмена</button><button class="primary" id="r-submit">${isEdit ? 'Сохранить' : 'Создать'}</button></div>
        `, () => {
            document.getElementById('r-cancel').addEventListener('click', closeModal);
            document.getElementById('r-submit').addEventListener('click', async () => {
                const name = document.getElementById('r-name').value.trim();
                const lpGroup = document.getElementById('r-lpgroup').value.trim() || null;
                const permissions = ALL_PERMISSIONS.filter(p => document.getElementById(`perm-${p}`).checked);
                if (!isEdit && !name) { document.getElementById('r-error').textContent = 'Введите название роли'; return; }
                try {
                    if (isEdit) {
                        await api('PUT', `/api/roles/${role.id}`, { lpGroup, permissions });
                    } else {
                        await api('POST', '/api/roles', { name, lpGroup, permissions });
                    }
                    closeModal();
                    onSaved();
                } catch (e) {
                    document.getElementById('r-error').textContent = e.message;
                }
            });
        });
    }

    // ---------- Modal helper ----------

    function openModal(html, onMount) {
        closeModal();
        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';
        overlay.id = 'modal-overlay';
        overlay.innerHTML = `<div class="modal">${html}</div>`;
        overlay.addEventListener('click', (e) => { if (e.target === overlay) closeModal(); });
        document.body.appendChild(overlay);
        if (onMount) onMount();
    }

    function closeModal() {
        const overlay = document.getElementById('modal-overlay');
        if (overlay) overlay.remove();
    }

    boot();
})();
