(() => {
    'use strict';

    const app = document.getElementById('app');
    let token = localStorage.getItem('wa_token') || localStorage.getItem('lwa_token');
    let me = null;
    const pollers = {};
    let activeNavSection = 'dashboard';
    let banReasonsCache = null;
    let sidebarCollapsed = localStorage.getItem('wa_sidebar_collapsed') === 'true';
    let serverStatus = null;

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
        { 
            id: 'dashboard', 
            label: 'Дашборд', 
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>`, 
            num: 1, 
            perm: 'VIEW_STATS', 
            modAllowed: true 
        },
        { 
            id: 'journal', 
            label: 'Журнал', 
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>`, 
            num: 2, 
            perm: 'VIEW_SERVER_LOGS', 
            modAllowed: true 
        },
        { 
            id: 'punishments', 
            label: 'Наказания', 
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`, 
            num: 3, 
            perm: 'VIEW_BANS', 
            modAllowed: true 
        },
        { 
            id: 'anticheat', 
            label: 'Античит', 
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>`, 
            num: 4, 
            perm: 'VIEW_VESUVIO', 
            modAllowed: true 
        },
        { 
            id: 'server', 
            label: 'Сервер', 
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="8"/><rect x="2" y="14" width="20" height="8"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg>`, 
            num: 5, 
            perm: 'VIEW_PLAYERS', 
            modAllowed: true 
        },
        { 
            id: 'auth', 
            label: 'Авторизация', 
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>`, 
            num: 6, 
            perm: 'MANAGE_LOVEAUTH', 
            modAllowed: false 
        },
        { 
            id: 'staff',
            label: 'Персонал', 
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>`, 
            num: 7, 
            perm: 'MANAGE_ADMINS', 
            modAllowed: false 
        },
        { 
            id: 'database', 
            label: 'База данных', 
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>`, 
            num: 8, 
            perm: 'VIEW_ANALYTICS', 
            modAllowed: true 
        }
    ];

    const ALL_PERMISSIONS = [
        'VIEW_STATS', 'VIEW_ANALYTICS', 'VIEW_PLAYERS', 'MANAGE_PLAYERS', 'VIEW_BANS', 'MANAGE_BANS',
        'VIEW_APPEALS', 'MANAGE_APPEALS',
        'VIEW_REPORTS', 'MANAGE_REPORTS',
        'VIEW_VESUVIO', 'VIEW_VESUVIO_ADVANCED', 'MANAGE_VESUVIO',
        'VIEW_SERVER_LOGS', 'VIEW_WEB_LOGS', 'EXECUTE_COMMANDS',
        'MANAGE_LOVEAUTH', 'MANAGE_ADMINS', 'MANAGE_ROLES', 'MANAGE_PASSWORDS',
        'MANAGE_LOCKDOWN', 'VIEW_STAFF_AUDIT', 'VIEW_ECONOMY', 'MANAGE_ECONOMY',
        'MANAGE_API', 'VIEW_SERVER_INTERNALS', 'BYPASS_MAINTENANCE'
    ];

    const PERMISSION_LABELS = {
        VIEW_STATS: 'Просмотр метрик сервера (TPS, MSPT, память, онлайн)',
        VIEW_ANALYTICS: 'Просмотр аналитики базы данных и активности',
        VIEW_PLAYERS: 'Просмотр списков и профилей игроков',
        MANAGE_PLAYERS: 'Управление игроками (кик, очистка инвентаря)',
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
        MANAGE_LOVEAUTH: 'Управление игровыми аккаунтами авторизации',
        MANAGE_ADMINS: 'Управление администраторами и сессиями',
        MANAGE_ROLES: 'Управление ролями и матрицей прав',
        MANAGE_PASSWORDS: 'Сброс паролей персонала',
        MANAGE_LOCKDOWN: 'Экстренный режим ЧС (изоляция сервера)',
        VIEW_STAFF_AUDIT: 'Аудит команд персонала и KPI метрики',
        VIEW_ECONOMY: 'Просмотр оборота экономики',
        MANAGE_ECONOMY: 'Управление балансом игроков',
        MANAGE_API: 'Управление API ключами и вебхуками',
        VIEW_SERVER_INTERNALS: 'Позволяет видеть ошибки плагинов, технические детали сервера, расширенные логи и stacktrace',
        BYPASS_MAINTENANCE: 'Позволяет входить в веб-панель во время технических работ'
    };

    const PERMISSION_NAMES = {
        VIEW_STATS: 'Просмотр метрик сервера',
        VIEW_ANALYTICS: 'Аналитика активности',
        VIEW_PLAYERS: 'Просмотр игроков',
        MANAGE_PLAYERS: 'Управление игроками',
        VIEW_BANS: 'Просмотр банов',
        MANAGE_BANS: 'Выдача и снятие банов',
        VIEW_APPEALS: 'Просмотр апелляций',
        MANAGE_APPEALS: 'Управление апелляциями',
        VIEW_REPORTS: 'Просмотр жалоб',
        MANAGE_REPORTS: 'Обработка жалоб',
        VIEW_VESUVIO: 'Античит: базовый мониторинг',
        VIEW_VESUVIO_ADVANCED: 'Античит: детальная телеметрия',
        MANAGE_VESUVIO: 'Античит: управление и сброс VL',
        VIEW_SERVER_LOGS: 'Серверный журнал',
        VIEW_WEB_LOGS: 'Веб журнал аудита',
        EXECUTE_COMMANDS: 'Консоль и команды',
        MANAGE_LOVEAUTH: 'Управление аккаунтами LoveAuth',
        MANAGE_ADMINS: 'Управление сотрудниками',
        MANAGE_ROLES: 'Управление ролями',
        MANAGE_PASSWORDS: 'Сброс паролей персонала',
        MANAGE_LOCKDOWN: 'Режим изоляции сервера (ЧС)',
        VIEW_STAFF_AUDIT: 'Аудит действий персонала',
        VIEW_ECONOMY: 'Просмотр экономики',
        MANAGE_ECONOMY: 'Управление балансами',
        MANAGE_API: 'Управление API-ключами',
        VIEW_SERVER_INTERNALS: 'Техническая информация и ошибки',
        BYPASS_MAINTENANCE: 'Обход тех. работ панели'
    };

    function renderSvgIcon(name, colorType = 'gray', size = 16) {
        let color = 'currentColor';
        if (colorType === 'violet' || colorType === 'purple') color = 'var(--accent, #a855f7)';
        else if (colorType === 'white') color = '#ffffff';
        else if (colorType === 'gray') color = 'var(--text-muted, #94a3b8)';
        else if (colorType === 'green') color = 'var(--green, #22c55e)';
        else if (colorType === 'red') color = 'var(--red, #ef4444)';
        else if (colorType === 'yellow') color = 'var(--yellow, #f59e0b)';
        else if (colorType && colorType.startsWith('#')) color = colorType;

        const sw = 1.8;
        const iconPaths = {
            gear: `<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>`,
            shield: `<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>`,
            bell: `<path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/>`,
            palette: `<circle cx="13.5" cy="6.5" r=".5" fill="currentColor"/><circle cx="17.5" cy="10.5" r=".5" fill="currentColor"/><circle cx="8.5" cy="7.5" r=".5" fill="currentColor"/><circle cx="6.5" cy="12.5" r=".5" fill="currentColor"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z"/>`,
            plug: `<path d="M12 22v-5"/><path d="M9 8V2"/><path d="M15 8V2"/><path d="M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8z"/>`,
            wrench: `<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>`,
            alert: `<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>`,
            globe: `<circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>`,
            server: `<rect x="2" y="2" width="20" height="8"/><rect x="2" y="14" width="20" height="8"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/>`,
            terminal: `<polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/>`,
            search: `<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>`,
            users: `<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>`,
            user: `<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>`,
            scales: `<path d="m16 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="m2 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z"/><path d="M7 21h10"/><path d="M12 3v18"/><path d="M3 7h2c2 0 5-1 7-2 2 1 5 2 7 2h2"/>`,
            zap: `<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>`,
            moon: `<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>`,
            sun: `<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>`,
            save: `<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/>`,
            copy: `<rect width="14" height="14" x="8" y="8"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>`,
            check: `<polyline points="20 6 9 17 4 12"/>`,
            key: `<path d="m21 2-2 2m-1.5 1.5L16 7l-1.5-1.5M16 7l-3 3M8 14a5 5 0 1 0-6 6 5 5 0 0 0 6-6Z"/>`,
            lock: `<rect x="3" y="11" width="18" height="11"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>`,
            refresh: `<path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"/><path d="M16 16h5v5"/>`,
            message: `<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>`,
            phone: `<rect width="14" height="20" x="5" y="2" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/>`,
            ban: `<circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>`,
            close: `<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>`,
            menu: `<line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="18" x2="21" y2="18"/>`,
            dot: `<circle cx="12" cy="12" r="5" fill="${color}"/>`,
            fileText: `<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>`,
            download: `<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>`,
            trash: `<polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>`,
            plus: `<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>`,
            volumeX: `<polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/>`,
            userMinus: `<path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="8.5" cy="7" r="4"/><line x1="23" y1="11" x2="17" y2="11"/>`,
            clock: `<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>`,
            tag: `<path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><line x1="7" y1="7" x2="7.01" y2="7"/>`,
            shieldAlert: `<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>`
        };

        const path = iconPaths[name] || iconPaths['gear'];
        return `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="${color}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; vertical-align:middle; flex-shrink:0;">${path}</svg>`;
    }

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
        return Boolean(me.permissions && me.permissions.includes(perm));
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
            if (res.status === 401 && token && !path.includes('/login')) {
                setToken(null);
                clearPollers();
                const errMsg = json.error || 'Сессия аннулирована';
                showToast('Сессия аннулирована', errMsg, 'error');
                setTimeout(() => renderLoginScreen(), 400);
                throw new Error(errMsg);
            }
            if (res.status === 503 && (json.maintenance || path.includes('/api/'))) {
                renderMaintenanceScreen(json.error);
                throw new Error(json.error || 'Ведутся технические работы');
            }
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
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
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
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
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
                                    <b style="color:var(--text-heading); font-size:14px;">${esc(r.targetName)}</b>
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
                    <span style="display:inline-flex; align-items:center;">${renderSvgIcon('search', 'violet', 18)}</span>
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
                                    <div class="search-result-item" onclick="window.viewPlayerProfile('${esc(p.name)}');">
                                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/24" class="player-avatar-sm" alt="">
                                        <div style="flex:1;">
                                            <div style="font-weight:700; color:var(--text-main);">${esc(p.name)}</div>
                                            <div style="font-size:11px; color:var(--text-muted);">${p.isOnline ? '<span style="color:var(--green)">● В сети</span>' : 'Оффлайн'} • Наиграно: ${fmtDuration(p.totalPlaytimeSeconds || 0)}</div>
                                        </div>
                                        <button type="button" class="secondary btn-sm" onclick="event.stopPropagation(); window.viewPlayerProfile('${esc(p.name)}');">ДОСЬЕ</button>
                                    </div>`;
                            });
                        }

                        if (matchingBans.length > 0) {
                            html += `<div style="padding:10px 12px 6px; font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Баны (${matchingBans.length})</div>`;
                            matchingBans.forEach(b => {
                                html += `
                                    <div class="search-result-item" onclick="window.closeCurrentModal(); window.navigateTo('punishments');">
                                        <span style="display:inline-flex; align-items:center;">${renderSvgIcon('ban', 'red', 16)}</span>
                                        <div style="flex:1;">
                                            <div style="font-weight:700; color:var(--text-main);">${esc(b.targetName)} <span class="badge red">${esc(b.ruleReason)}</span></div>
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
        let status = { ownerExists: false, initialSetupNeeded: false, debugMode: false };
        try {
            status = await api('GET', '/api/auth/status');
            serverStatus = status;
        } catch (e) {
            serverStatus = status;
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
                    correctLevel: (typeof QRCode.CorrectLevel !== 'undefined' ? (QRCode.CorrectLevel.H || QRCode.CorrectLevel.M) : 0)
                });
                // qrcode.js: гарантируем корректное отображение img или canvas
                setTimeout(() => {
                    const canvas = containerEl.querySelector('canvas');
                    const img = containerEl.querySelector('img');
                    if (img && img.src && img.src.length > 50) {
                        img.style.display = 'block';
                        img.style.width = '180px';
                        img.style.height = '180px';
                        img.style.margin = '0 auto';
                        if (canvas) canvas.style.display = 'none';
                    } else if (canvas) {
                        canvas.style.display = 'block';
                        canvas.style.width = '180px';
                        canvas.style.height = '180px';
                        canvas.style.margin = '0 auto';
                        if (img) img.style.display = 'none';
                    }
                }, 50);
                return;
            }
        } catch (e) {
            console.error('Ошибка создания QRCode:', e);
        }

        containerEl.innerHTML = `
            <div style="color:#111; font-size:12px; text-align:center; padding:24px 10px; font-weight:600; line-height:1.5;">
                <div style="margin-bottom:8px; display:flex; justify-content:center;">${renderSvgIcon('key', 'violet', 24)}</div>
                Используйте вкладку <b>«Секретный код»</b><br>для ввода ключа в Authenticator.
            </div>`;
    }

    function validatePasswordClient(p) {
        if (!p || p.length < 10) return 'Минимум 12 символов';
        if (!/[A-Za-zА-Яа-я]/.test(p)) return 'Нужна хотя бы одна буква';
        if (!/\d/.test(p)) return 'Нужна хотя бы одна цифра';
        if (!/[^A-Za-zА-Яа-я0-9]/.test(p)) return 'Нужен спецсимвол';
        return null;
    }

    function updatePasswordMeter(password, fillEl, hintsEl) {
        if (!fillEl) return;
        const p = password || '';
        const hasLen = p.length >= 12;
        const hasLetter = /[A-Za-zА-Яа-я]/.test(p);
        const hasDigit = /\d/.test(p);
        const hasSpecial = /[^A-Za-zА-Яа-я0-9]/.test(p);

        if (hintsEl) {
            const hints = hintsEl.querySelectorAll('span');
            if (hints.length >= 4) {
                hints[0].className = hasLen ? 'valid' : 'invalid';
                hints[0].textContent = (hasLen ? '✓ ' : '• ') + '10+ симв.';
                hints[1].className = hasLetter ? 'valid' : 'invalid';
                hints[1].textContent = (hasLetter ? '✓ ' : '• ') + 'буква';
                hints[2].className = hasDigit ? 'valid' : 'invalid';
                hints[2].textContent = (hasDigit ? '✓ ' : '• ') + 'цифра';
                hints[3].className = hasSpecial ? 'valid' : 'invalid';
                hints[3].textContent = (hasSpecial ? '✓ ' : '• ') + 'спецсимвол';
            }
        }

        const score = (hasLen ? 1 : 0) + (hasLetter ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);
        const pct = (score / 4) * 100;
        fillEl.style.width = pct + '%';
        if (score <= 1) fillEl.style.background = 'var(--red, #ef4444)';
        else if (score <= 3) fillEl.style.background = 'var(--yellow, #eab308)';
        else fillEl.style.background = 'var(--green, #22c55e)';
    }

    function renderSecretMasked(el, secret) {
        if (!el || !secret) return;
        const groups = secret.match(/.{1,4}/g)?.join(' ') || secret;
        const maskedText = '•••• •••• •••• ••••';
        el.innerHTML = `
            <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; background:var(--card-inner-bg, rgba(255,255,255,0.03)); border:1px solid var(--border); border-radius:6px; padding:8px 12px;">
                <span class="totp-secret-mask" title="Нажмите, чтобы показать/скрыть">${maskedText}</span>
                <button type="button" class="secondary btn-sm" id="btn-copy-secret" style="display:inline-flex; align-items:center; gap:4px; font-size:11px;">
                    ${renderSvgIcon('copy', 'gray', 12)} Копировать
                </button>
            </div>
        `;
        const mask = el.querySelector('.totp-secret-mask');
        let shown = false;
        const show = () => { if (mask) { mask.textContent = groups; shown = true; } };
        const hide = () => { if (mask) { mask.textContent = maskedText; shown = false; } };
        mask?.addEventListener('click', () => shown ? hide() : show());
        mask?.addEventListener('mouseenter', show);
        mask?.addEventListener('mouseleave', hide);

        el.querySelector('#btn-copy-secret')?.addEventListener('click', async () => {
            try {
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    await navigator.clipboard.writeText(secret);
                } else {
                    const ta = document.createElement('textarea');
                    ta.value = secret;
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    document.body.removeChild(ta);
                }
                showToast('Скопировано', 'Секретный ключ скопирован в буфер обмена', 'info');
            } catch (_) {}
        });
    }

    function bindTotpInput(input) {
        if (!input) return;
        input.classList.add('totp-input-masked');
        input.addEventListener('input', () => {
            let d = input.value.replace(/\D/g, '').slice(0, 6);
            input.value = d.length > 3 ? d.slice(0, 3) + '-' + d.slice(3) : d;
        });
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
                btn.innerHTML = `${renderSvgIcon('check', 'green', 12)} Скопировано!`;
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
                    <div class="logo"><span style="color:var(--green); display:inline-flex; vertical-align:middle; margin-right:6px;">${renderSvgIcon('check', 'green', 18)}</span> 2FA Настроена</div>
                    <div class="sub">Аккаунт <b>${esc(username)}</b> успешно привязан! Сохраните эти <b>8 резервных кодов</b>. Каждый код можно использовать один раз для входа, если у вас не будет доступа к приложению аутентификатора:</div>

                    <div class="backup-codes-grid">
                        ${codes.map(c => `<div class="backup-code-pill">${esc(c)}</div>`).join('')}
                    </div>

                    <div style="display:flex; gap:10px; margin-bottom:16px;">
                        <button type="button" class="secondary" id="btn-copy-backup-codes" style="flex:1; display:inline-flex; align-items:center; justify-content:center; gap:6px;">${renderSvgIcon('copy', 'gray', 14)} Скопировать все</button>
                        <button type="button" class="secondary" id="btn-download-backup-codes" style="flex:1; display:inline-flex; align-items:center; justify-content:center; gap:6px;">${renderSvgIcon('download', 'gray', 14)} Скачать (.txt)</button>
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
                btn.innerHTML = `${renderSvgIcon('check', 'green', 14)} Коды скопированы!`;
                setTimeout(() => { btn.innerHTML = `${renderSvgIcon('copy', 'gray', 14)} Скопировать все`; }, 2000);
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
        const isDebug = serverStatus && serverStatus.debugMode;
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card">
                    <div class="logo"><span style="color:var(--accent);">◈</span> WebAdmin</div>
                    <div class="sub">Панель управления Minecraft-сервером</div>
                    ${isDebug ? `<div class="auth-debug-badge" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('zap', 'violet', 13)} Режим отладки: 2FA / QR не требуется</div>` : ''}
                    <form id="login-form">
                        <div class="form-group">
                            <label>Никнейм сотрудника</label>
                            <input type="text" id="login-user" placeholder="Например: Lovelace" required autofocus autocomplete="username">
                        </div>
                        <div class="form-group">
                            <label>Пароль</label>
                            <input type="password" id="login-pass" placeholder="••••••••" required autocomplete="current-password">
                        </div>
                        <div id="login-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" id="login-submit" style="width:100%; margin-top:10px;">ВОЙТИ В СИСТЕМУ</button>
                    </form>
                    <div class="auth-switch-box">
                        Новый сотрудник? <a href="#" class="auth-switch-link" id="link-register-invite">Регистрация по приглашению</a>
                    </div>
                </div>
            </div>`;

        document.getElementById('link-register-invite')?.addEventListener('click', (e) => {
            e.preventDefault();
            renderInviteRegister();
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

    function renderInviteRegister() {
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:480px;">
                    <div class="logo"><span style="color:var(--accent);">◈</span> Регистрация по приглашению</div>
                    <div class="sub">Введите код приглашения, полученный от руководства сервера</div>

                    <!-- Step A: Code Form -->
                    <form id="invite-code-form">
                        <div class="form-group">
                            <label>Код приглашения</label>
                            <input id="reg-invite-code" placeholder="LWA-XXXXXXXX"
                                   autocomplete="one-time-code" style="text-transform:uppercase; text-align:center; font-family:'JetBrains Mono'; font-size:18px; font-weight:700; letter-spacing:2px;" required autofocus>
                        </div>
                        <div id="invite-code-err" class="error" style="display:none; margin-bottom:12px;"></div>
                        <button type="submit" class="primary" id="btn-validate-code" style="width:100%; margin-top:6px;">ПРОВЕРИТЬ КОД</button>
                        <button type="button" class="secondary" id="btn-invite-cancel" style="width:100%; margin-top:8px;">← ВЕРНУТЬСЯ КО ВХОДУ</button>
                    </form>

                    <!-- Step B: Account + Password + 2FA Form (Hidden initially) -->
                    <div id="invite-step-b" style="display:none;"></div>
                </div>
            </div>`;

        document.getElementById('btn-invite-cancel')?.addEventListener('click', () => {
            renderLogin();
        });

        document.getElementById('invite-code-form')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const codeInput = document.getElementById('reg-invite-code');
            const code = (codeInput?.value || '').trim().toUpperCase();
            const errEl = document.getElementById('invite-code-err');
            const btn = document.getElementById('btn-validate-code');

            if (!code) return;
            if (errEl) errEl.style.display = 'none';
            btn.disabled = true;
            btn.textContent = 'ПРОВЕРКА...';

            try {
                const inviteData = await api('POST', '/api/auth/validate-invite', { code });
                renderInviteStepB(code, inviteData);
            } catch (ex) {
                if (errEl) {
                    errEl.textContent = ex.message || 'Недействительный код приглашения';
                    errEl.style.display = 'block';
                }
                btn.disabled = false;
                btn.textContent = 'ПРОВЕРИТЬ КОД';
            }
        });
    }

    async function renderInviteStepB(inviteCode, inviteData) {
        const stepB = document.getElementById('invite-step-b');
        const codeForm = document.getElementById('invite-code-form');
        if (!stepB) return;

        codeForm.style.display = 'none';
        stepB.style.display = 'block';

        const username = inviteData.username || '';
        const roleName = inviteData.roleName || 'Сотрудник';

        stepB.innerHTML = `
            <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); border:1px solid var(--border); border-radius:6px; padding:12px 14px; margin-bottom:16px;">
                <div style="font-size:13px; color:var(--text-main);">Сотрудник: <b>${esc(username)}</b></div>
                <div style="font-size:12px; color:var(--text-muted); margin-top:3px;">Назначенная роль: <b style="color:var(--accent);">${esc(roleName)}</b></div>
            </div>

            <form id="invite-reg-final-form">
                <div class="form-group">
                    <label>Придумайте пароль *</label>
                    <input type="password" id="reg-password"
                           autocomplete="new-password"
                           placeholder="Минимум 12 символов"
                           passwordrules="minlength: 10; required: lower; required: upper; required: digit; required: special;" required>
                    <div class="password-strength-bar">
                        <div class="password-strength-fill" id="reg-pass-strength-fill"></div>
                    </div>
                    <div class="password-strength-hints" id="reg-pass-strength-hints">
                        <span class="invalid">• 12+ симв.</span>
                        <span class="invalid">• буква</span>
                        <span class="invalid">• цифра</span>
                        <span class="invalid">• спецсимвол</span>
                    </div>
                </div>

                <div class="form-group">
                    <label>Повторите пароль *</label>
                    <input type="password" id="reg-password2" autocomplete="new-password" placeholder="••••••••" required>
                </div>

                <!-- 2FA Section -->
                <div style="margin-top:16px; margin-bottom:12px;">
                    <div style="font-weight:700; font-size:13px; color:var(--text-heading); margin-bottom:4px;">Двухфакторная защита (2FA)</div>
                    <div style="font-size:11.5px; color:var(--text-muted); line-height:1.4;">
                        Отсканируйте QR в Google Authenticator. Кликните по QR для обновления ключа.
                    </div>
                </div>

                <div class="totp-qr-wrapper" style="margin-bottom:12px;">
                    <div class="totp-qr-box" id="reg-qrcode-box" title="Нажмите, чтобы перегенерировать QR-код" style="cursor:pointer;"></div>
                </div>

                <div class="form-group">
                    <label>Секретный ключ (для ручного ввода)</label>
                    <div id="reg-secret-container"></div>
                </div>

                <div class="form-group">
                    <label>Одноразовый 6-значный код из Authenticator *</label>
                    <input id="reg-totp" inputmode="numeric" autocomplete="one-time-code"
                           placeholder="000-000" maxlength="7" required>
                </div>

                <div id="reg-final-err" class="error" style="display:none; margin-bottom:12px;"></div>
                <button type="submit" class="primary" id="btn-reg-finish" style="width:100%; margin-top:6px;">ЗАВЕРШИТЬ РЕГИСТРАЦИЮ</button>
                <button type="button" class="secondary" id="btn-reg-back" style="width:100%; margin-top:8px;">← НАЗАД К ВВОДУ КОДА</button>
            </form>
        `;

        const passInput = document.getElementById('reg-password');
        const pass2Input = document.getElementById('reg-password2');
        const fillEl = document.getElementById('reg-pass-strength-fill');
        const hintsEl = document.getElementById('reg-pass-strength-hints');
        const qrBox = document.getElementById('reg-qrcode-box');
        const secretContainer = document.getElementById('reg-secret-container');
        const totpInput = document.getElementById('reg-totp');

        passInput?.addEventListener('input', () => {
            updatePasswordMeter(passInput.value, fillEl, hintsEl);
        });

        bindTotpInput(totpInput);

        let currentSecret = inviteData.totpSecret || '';
        if (currentSecret && qrBox) {
            renderTotpQrCode(qrBox, inviteData.otpUrl || ('otpauth://totp/WebAdmin:' + encodeURIComponent(username) + '?secret=' + currentSecret + '&issuer=WebAdmin'));
        }
        if (currentSecret && secretContainer) renderSecretMasked(secretContainer, currentSecret);

        const fetchAndRenderTotp = async () => {
            try {
                const res = await api('POST', '/api/auth/validate-invite', { code: inviteCode });
                currentSecret = res.totpSecret || res.secret || currentSecret;
                if (qrBox) renderTotpQrCode(qrBox, res.otpUrl);
                if (secretContainer) renderSecretMasked(secretContainer, currentSecret);
            } catch (e) {
                console.error('TOTP setup error:', e);
            }
        };

        qrBox?.addEventListener('click', async () => {
            await fetchAndRenderTotp();
            showToast('2FA обновлен', 'Сгенерирован новый секретный ключ', 'info');
        });

        document.getElementById('btn-reg-back')?.addEventListener('click', () => {
            renderInviteRegister();
        });

        document.getElementById('invite-reg-final-form')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const password = passInput.value;
            const pass2 = pass2Input.value;
            const digitsOnly = normalizeAuthCode(totpInput.value || '');
            const errEl = document.getElementById('reg-final-err');
            const submitBtn = document.getElementById('btn-reg-finish');

            if (errEl) errEl.style.display = 'none';

            const passErr = validatePasswordClient(password);
            if (passErr) {
                if (errEl) { errEl.textContent = passErr; errEl.style.display = 'block'; }
                return;
            }

            if (password !== pass2) {
                if (errEl) { errEl.textContent = 'Пароли не совпадают'; errEl.style.display = 'block'; }
                return;
            }

            if (digitsOnly.length !== 6) {
                if (errEl) { errEl.textContent = 'Введите 6 цифр кода подтверждения 2FA'; errEl.style.display = 'block'; }
                return;
            }

            submitBtn.disabled = true;
            submitBtn.textContent = 'РЕГИСТРАЦИЯ...';

            try {
                const res = await api('POST', '/api/auth/register', {
                    inviteCode,
                    password,
                    totpSecret: currentSecret,
                    totpCode: digitsOnly
                });

                renderBackupCodesScreen(username, res.backupCodes || [], () => {
                    renderLogin();
                });
            } catch (ex) {
                if (errEl) {
                    errEl.textContent = ex.message || 'Ошибка регистрации';
                    errEl.style.display = 'block';
                }
                submitBtn.disabled = false;
                submitBtn.textContent = 'ЗАВЕРШИТЬ РЕГИСТРАЦИЮ';
            }
        });
    }

    function render2faVerification(username) {
        const isDebug = serverStatus && serverStatus.debugMode;
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card">
                    <div class="logo"><span style="color:var(--accent);">◈</span> 2FA Подтверждение</div>
                    <div class="sub">Вход для аккаунта <b>${esc(username)}</b>. Код из Google Authenticator (000-000) или резервный код (XXXX-XXXX) — в это же поле.</div>
                    ${isDebug ? `<div class="auth-debug-badge" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('zap', 'violet', 13)} Режим отладки: можно войти без ввода кода из приложения</div>` : ''}
                    <form id="form-2fa">
                        <div class="form-group">
                            <label>Код подтверждения</label>
                            <input type="text" id="code-2fa" class="totp-input-masked" inputmode="numeric" autocomplete="one-time-code" placeholder="000-000" maxlength="9" autofocus
                                   style="text-align:center; font-size:20px; font-weight:700; letter-spacing:4px;">
                        </div>
                        <div id="err-2fa" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" style="width:100%; margin-top:10px;">ПОДТВЕРДИТЬ</button>
                        ${isDebug ? `<button type="button" class="btn-debug-bypass" id="btn-debug-bypass-2fa" style="width:100%; margin-top:8px; display:inline-flex; align-items:center; justify-content:center; gap:6px;">${renderSvgIcon('zap', 'violet', 13)} ВОЙТИ БЕЗ 2FA / QR (РЕЖИМ ОТЛАДКИ)</button>` : ''}
                        <button type="button" class="secondary" onclick="location.reload()" style="width:100%; margin-top:8px;">ОТМЕНА</button>
                    </form>
                </div>
            </div>`;

        if (isDebug) {
            document.getElementById('btn-debug-bypass-2fa')?.addEventListener('click', async () => {
                const err = document.getElementById('err-2fa');
                err.style.display = 'none';
                try {
                    const res = await api('POST', '/api/auth/verify-2fa', { username, code: 'debug' });
                    setToken(res.token);
                    me = await api('GET', '/api/me');
                    renderAppLayout();
                } catch (ex) {
                    err.textContent = ex.message;
                    err.style.display = 'block';
                }
            });
        }

        bindLogin2faInput(document.getElementById('code-2fa'));

        document.getElementById('form-2fa')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const code = normalizeAuthCode(document.getElementById('code-2fa').value) || (isDebug ? 'debug' : '');
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
        const isDebug = serverStatus && serverStatus.debugMode;
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:480px;">
                    <div class="logo">Первичная настройка</div>
                    <div class="sub">Создание учётной записи Управляющего</div>
                    ${isDebug ? `<div class="auth-debug-badge">Режим отладки</div>` : ''}
                    <div class="stepper">
                        <div class="step-item active"><span class="step-badge">1</span> Токен</div>
                        <div class="step-connector"></div>
                        <div class="step-item"><span class="step-badge">2</span> Пароль</div>
                        <div class="step-connector"></div>
                        <div class="step-item"><span class="step-badge">3</span> 2FA</div>
                    </div>
                    <form id="ob-step1-form">
                        <div class="form-group">
                            <label>Токен из консоли сервера *</label>
                            <input type="text" id="ob-token" placeholder="LWA-..." required autofocus style="font-family:'JetBrains Mono',monospace;">
                            <div style="font-size:11px;color:var(--text-muted);margin-top:6px;line-height:1.4;">
                                Токен печатается в <b>консоль при запуске</b> сервера, если Управляющий ещё не создан.
                            </div>
                        </div>
                        <div id="ob-step1-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" style="width:100%;">ДАЛЕЕ</button>
                    </form>
                </div>
            </div>`;
        document.getElementById('ob-step1-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            const setupToken = document.getElementById('ob-token').value.trim();
            const err = document.getElementById('ob-step1-err');
            if (!setupToken) { err.textContent = 'Укажите токен из консоли'; err.style.display = 'block'; return; }
            renderMasterOnboardingPassword(setupToken);
        });
    }

    function renderMasterOnboardingPassword(setupToken) {
        const isDebug = serverStatus && serverStatus.debugMode;
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:480px;">
                    <div class="logo">Аккаунт</div>
                    <div class="sub">Логин и пароль Управляющего</div>
                    <div class="stepper">
                        <div class="step-item completed"><span class="step-badge">✓</span> Токен</div>
                        <div class="step-connector"></div>
                        <div class="step-item active"><span class="step-badge">2</span> Пароль</div>
                        <div class="step-connector"></div>
                        <div class="step-item"><span class="step-badge">3</span> 2FA</div>
                    </div>
                    <form id="ob-step2-form">
                        <div class="form-group">
                            <label>Логин *</label>
                            <input type="text" id="ob-user" required autofocus autocomplete="username">
                        </div>
                        <div class="form-group">
                            <label>Пароль *</label>
                            <input type="password" id="ob-pass" placeholder="Минимум 12 символов" required autocomplete="new-password">
                            <div class="password-strength-bar"><div class="password-strength-fill" id="ob-pass-fill"></div></div>
                            <div class="password-strength-hints" id="ob-pass-hints">
                                <span class="invalid">• 12+</span><span class="invalid">• а/А</span>
                                <span class="invalid">• цифра</span><span class="invalid">• спец</span>
                            </div>
                        </div>
                        <div class="form-group">
                            <label>Повтор пароля *</label>
                            <input type="password" id="ob-pass2" required autocomplete="new-password">
                        </div>
                        <div id="ob-step2-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" style="width:100%;">ДАЛЕЕ — 2FA</button>
                        ${isDebug ? `<button type="button" class="btn-debug-bypass" id="btn-ob-debug" style="width:100%;margin-top:8px;">БЕЗ 2FA (ОТЛАДКА)</button>` : ''}
                        <button type="button" class="secondary" id="btn-ob-back1" style="width:100%;margin-top:8px;">НАЗАД</button>
                    </form>
                </div>
            </div>`;
        const passInput = document.getElementById('ob-pass');
        passInput?.addEventListener('input', () => updatePasswordMeter(passInput.value, document.getElementById('ob-pass-fill'), document.getElementById('ob-pass-hints')));
        document.getElementById('btn-ob-back1')?.addEventListener('click', () => renderMasterOnboarding());
        if (isDebug) {
            document.getElementById('btn-ob-debug')?.addEventListener('click', async () => {
                const username = document.getElementById('ob-user').value.trim();
                const password = document.getElementById('ob-pass').value;
                const err = document.getElementById('ob-step2-err');
                const pe = validatePasswordClient(password);
                if (pe) { err.textContent = pe; err.style.display = 'block'; return; }
                try {
                    const res = await api('POST', '/api/auth/setup-owner', { setupToken, username, password, totpSecret: 'debug', totpCode: 'debug' });
                    setToken(res.token);
                    me = await api('GET', '/api/me');
                    renderBackupCodesScreen(username, res.backupCodes || [], () => renderAppLayout());
                } catch (ex) { err.textContent = ex.message; err.style.display = 'block'; }
            });
        }
        document.getElementById('ob-step2-form')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const username = document.getElementById('ob-user').value.trim();
            const password = document.getElementById('ob-pass').value;
            const pass2 = document.getElementById('ob-pass2').value;
            const err = document.getElementById('ob-step2-err');
            const btn = e.target.querySelector('button[type="submit"]');
            err.style.display = 'none';
            if (!username || username.toLowerCase() === 'admin') { err.textContent = 'Укажите персональный логин'; err.style.display = 'block'; return; }
            const pe = validatePasswordClient(password);
            if (pe) { err.textContent = pe; err.style.display = 'block'; return; }
            if (password !== pass2) { err.textContent = 'Пароли не совпадают'; err.style.display = 'block'; return; }
            btn.disabled = true; btn.textContent = 'ПОДГОТОВКА 2FA...';
            try {
                const totp = await api('POST', '/api/auth/prepare-setup-owner', { setupToken, username });
                renderMasterOnboarding2fa(username, password, setupToken, totp.secret, totp.otpUrl);
            } catch (ex) {
                err.textContent = ex.message || 'Проверьте токен';
                err.style.display = 'block';
                btn.disabled = false; btn.textContent = 'ДАЛЕЕ — 2FA';
            }
        });
    }

    function renderMasterOnboarding2fa(username, password, setupToken, totpSecret, otpUrl) {
        const isDebug = serverStatus && serverStatus.debugMode;
        app.innerHTML = `
            <div class="auth-screen">
                <div class="auth-card" style="max-width:500px;">
                    <div class="logo">Привязка 2FA</div>
                    <div class="sub">Аккаунт <b>${esc(username)}</b>. QR в <b>Google Authenticator</b>.</div>
                    <div class="stepper">
                        <div class="step-item completed"><span class="step-badge">✓</span> Токен</div>
                        <div class="step-connector"></div>
                        <div class="step-item completed"><span class="step-badge">✓</span> Пароль</div>
                        <div class="step-connector"></div>
                        <div class="step-item active"><span class="step-badge">3</span> 2FA</div>
                    </div>
                    <div class="totp-method-toggle">
                        <button type="button" class="totp-toggle-btn active" id="ob-tab-qr">QR-код</button>
                        <button type="button" class="totp-toggle-btn" id="ob-tab-code">Секретный код</button>
                    </div>
                    <div id="ob-pane-qr">
                        <div class="totp-qr-wrapper"><div class="totp-qr-box" id="ob-qrcode-box" title="Клик — обновить"></div></div>
                    </div>
                    <div id="ob-pane-code" style="display:none;"><div id="ob-secret-box"></div></div>
                    <form id="ob-2fa-form">
                        <div class="form-group">
                            <label>Код из приложения</label>
                            <input type="text" id="ob-code" placeholder="000-000" maxlength="7" required autofocus
                                   style="text-align:center;font-size:20px;font-weight:700;letter-spacing:4px;font-family:'JetBrains Mono',monospace;">
                        </div>
                        <div id="ob-2fa-err" class="error" style="display:none;"></div>
                        <button type="submit" class="primary" id="btn-ob-finish" style="width:100%;">ЗАВЕРШИТЬ НАСТРОЙКУ</button>
                        <button type="button" class="secondary" id="btn-ob-back2" style="width:100%;margin-top:8px;">НАЗАД</button>
                    </form>
                </div>
            </div>`;
        renderTotpQrCode(document.getElementById('ob-qrcode-box'), otpUrl);
        if (typeof renderSecretMasked === 'function') renderSecretMasked(document.getElementById('ob-secret-box'), totpSecret);
        bindTotpInput(document.getElementById('ob-code'));
        document.getElementById('ob-tab-qr')?.addEventListener('click', () => {
            document.getElementById('ob-tab-qr').classList.add('active');
            document.getElementById('ob-tab-code').classList.remove('active');
            document.getElementById('ob-pane-qr').style.display = 'block';
            document.getElementById('ob-pane-code').style.display = 'none';
        });
        document.getElementById('ob-tab-code')?.addEventListener('click', () => {
            document.getElementById('ob-tab-code').classList.add('active');
            document.getElementById('ob-tab-qr').classList.remove('active');
            document.getElementById('ob-pane-code').style.display = 'block';
            document.getElementById('ob-pane-qr').style.display = 'none';
        });
        document.getElementById('btn-ob-back2')?.addEventListener('click', () => renderMasterOnboardingPassword(setupToken));
        document.getElementById('ob-qrcode-box')?.addEventListener('click', async () => {
            try {
                const totp = await api('POST', '/api/auth/prepare-setup-owner', { setupToken, username });
                totpSecret = totp.secret; otpUrl = totp.otpUrl;
                renderTotpQrCode(document.getElementById('ob-qrcode-box'), otpUrl);
                if (typeof renderSecretMasked === 'function') renderSecretMasked(document.getElementById('ob-secret-box'), totpSecret);
            } catch (e) { showToast('2FA', e.message || 'Ошибка', 'error'); }
        });
        document.getElementById('ob-2fa-form')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const code = normalizeAuthCode(document.getElementById('ob-code').value) || (isDebug ? 'debug' : '');
            const err = document.getElementById('ob-2fa-err');
            const btn = document.getElementById('btn-ob-finish');
            err.style.display = 'none';
            if (code.length !== 6 && !isDebug) { err.textContent = 'Введите 6 цифр'; err.style.display = 'block'; return; }
            btn.disabled = true; btn.textContent = 'ПРОВЕРКА...';
            try {
                const res = await api('POST', '/api/auth/setup-owner', { setupToken, username, password, totpSecret, totpCode: code });
                setToken(res.token);
                me = await api('GET', '/api/me');
                renderBackupCodesScreen(username, res.backupCodes || [], () => renderAppLayout());
            } catch (ex) {
                err.textContent = ex.message; err.style.display = 'block';
                btn.disabled = false; btn.textContent = 'ЗАВЕРШИТЬ НАСТРОЙКУ';
            }
        });
    }

    // ---------- Main Application Layout (Sidebar + Viewport) ----------
    function renderAppLayout() {
        const isMod = isModeratorRole();
        const roleName = me.isOwner ? 'Управляющий' : (me.role || (isMod ? 'Модератор' : 'Администратор'));
        const roleClass = (isMod && !me.isOwner) ? 'moderator' : 'admin';

        let userPrefs = {};
        try {
            userPrefs = typeof me.uiPreferences === 'string' ? JSON.parse(me.uiPreferences) : (me.uiPreferences || {});
        } catch (_) { userPrefs = {}; }

        app.innerHTML = `
            <div class="app-layout">
                <div class="sidebar-mobile-backdrop" id="sidebar-mobile-backdrop"></div>
                <!-- Left Sidebar -->
                <aside class="app-sidebar ${sidebarCollapsed ? 'collapsed' : ''}" id="app-sidebar">
                    <div class="sidebar-brand">
                        <div class="sidebar-logo-cube">
                            <svg viewBox="0 0 32 32" width="28" height="28" fill="none">
                                <polygon points="16,2 30,10 16,18 2,10" fill="#9B5CFF"/>
                                <polygon points="16,2 23,6 16,10 9,6" fill="#B788FF" opacity="0.6"/>
                                <polygon points="2,10 16,18 16,30 2,22" fill="#4C1D95"/>
                                <polygon points="16,18 30,10 30,22 16,30" fill="#6D28D9"/>
                                <polyline points="2,10 16,18 30,10" stroke="#1F2440" stroke-width="0.8"/>
                                <line x1="16" y1="18" x2="16" y2="30" stroke="#1F2440" stroke-width="0.8"/>
                            </svg>
                        </div>
                        <div class="sidebar-brand-info">
                            <span class="sidebar-brand-name">MINECRAFT</span>
                            <span class="sidebar-brand-sub">WebAdmin</span>
                        </div>
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
                                    <span class="nav-label">${esc(s.label)}</span>
                                    ${(s.id === 'punishments' || s.id === 'reports') ? '<span class="nav-counter-badge" id="reports-pending-badge" style="display:none;">0</span>' : ''}
                                    <span class="nav-key-badge">${keyShortcut}</span>
                                </div>
                            `;
                        }).join('')}
                    </nav>

                    <!-- Sidebar Footer -->
                    <div class="sidebar-footer">
                        <div class="sidebar-user-block" id="sidebar-user-btn" data-tooltip="Настройки администратора и профиль">
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
                        <div class="topbar-left" style="display:flex; align-items:center; gap:10px;">
                            <button type="button" class="mobile-menu-btn" id="mobile-menu-toggle" title="Открыть меню">${renderSvgIcon('menu', 'gray', 16)}</button>
                            <h1 class="topbar-page-title" id="topbar-page-title">Дашборд</h1>
                        </div>

                        <div class="topbar-center">
                            <div class="global-search-trigger" id="topbar-search-trigger" data-tooltip="Глобальный поиск по никам, банам и логам (Ctrl+K)">
                                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                                <span>Поиск игрока, бана или события...</span>
                                <span class="search-shortcut-badge">Ctrl + K</span>
                            </div>
                        </div>

                        <div class="topbar-right" style="display:flex; align-items:center; gap:8px;">
                            <!-- Shift Status Toggle -->
                            <div style="display:flex; align-items:center; gap:2px;">
                                <button type="button" class="topbar-shift-btn" id="topbar-shift-btn" data-tooltip="Ваш статус: Нажмите для переключения смены">
                                    <span id="shift-btn-dot">${renderSvgIcon('dot', 'gray', 10)}</span>
                                    <span id="shift-btn-text">ВНЕ СМЕНЫ</span>
                                </button>
                                <button type="button" class="topbar-icon-btn" id="topbar-shift-list-btn" data-tooltip="Кто сейчас на смене из персонала" style="width:28px; height:31px;">
                                    ${renderSvgIcon('users', 'violet', 14)}
                                </button>
                            </div>

                            <!-- Notifications Bell -->
                            <button type="button" class="topbar-icon-btn" id="topbar-notif-btn" data-tooltip="Уведомления персонала">
                                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
                                <span class="notif-badge" id="topbar-notif-badge" style="display:none;">0</span>
                            </button>

                            <!-- Theme Toggle Button -->
                            <button type="button" class="topbar-icon-btn" id="topbar-theme-btn" data-tooltip="Сменить тему (Тёмная / Светлая)">
                                <span id="theme-btn-icon">${renderSvgIcon('moon', 'violet', 15)}</span>
                            </button>

                            <!-- Admin Settings Button -->
                            <button type="button" class="topbar-icon-btn" id="topbar-settings-btn" data-tooltip="Настройки администратора панели">
                                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
                            </button>

                            <div class="status-pill" id="topbar-status-pill" data-tooltip="Статус ядра Minecraft сервера">
                                <span class="status-dot" id="server-status-dot"></span>
                                <span id="server-status-text">Онлайн: ...</span>
                            </div>

                            <button type="button" class="topbar-btn logout-btn" id="topbar-logout-btn" data-tooltip="Выйти из системы">
                                <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
                                ВЫХОД
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

        // Apply theme from preferences
        const savedTheme = userPrefs.theme || localStorage.getItem('wa_theme') || 'dark';
        applyUserTheme(savedTheme);

        // Mobile drawer handlers
        const sidebar = document.getElementById('app-sidebar');
        const mobileBackdrop = document.getElementById('sidebar-mobile-backdrop');
        document.getElementById('mobile-menu-toggle')?.addEventListener('click', () => {
            sidebar?.classList.toggle('mobile-open');
            mobileBackdrop?.classList.toggle('open');
        });
        mobileBackdrop?.addEventListener('click', () => {
            sidebar?.classList.remove('mobile-open');
            mobileBackdrop?.classList.remove('open');
        });

        // Theme button click
        document.getElementById('topbar-theme-btn')?.addEventListener('click', async () => {
            const isLightNow = document.body.classList.contains('theme-light');
            const newTheme = isLightNow ? 'dark' : 'light';
            applyUserTheme(newTheme);
            userPrefs.theme = newTheme;
            try {
                await api('PUT', '/api/me/preferences', { uiPreferences: userPrefs });
                me.uiPreferences = JSON.stringify(userPrefs);
            } catch (_) {}
        });

        // Shift button handlers
        document.getElementById('topbar-shift-btn')?.addEventListener('click', toggleMyShift);
        document.getElementById('topbar-shift-list-btn')?.addEventListener('click', openStaffOnShiftModal);
        initShiftStatus();

        // Notifications button handler
        document.getElementById('topbar-notif-btn')?.addEventListener('click', openNotificationsModal);
        updateNotificationsBadge();
        setInterval(updateNotificationsBadge, 6000);

        // Admin settings modal button
        document.getElementById('topbar-settings-btn')?.addEventListener('click', () => openAdminSettingsModal('general'));

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
                (el.dataset.nav === 'punishments' && (sectionId === 'bans' || sectionId === 'reports')) ||
                (el.dataset.nav === 'staff' && sectionId === 'admins');
            el.classList.toggle('active', isMatch);
        });

        // Update topbar page title on the left
        const topbarTitle = document.getElementById('topbar-page-title');
        if (topbarTitle) {
            const sec = SECTIONS.find(s => s.id === sectionId);
            if (sec) {
                topbarTitle.textContent = sec.label;
            } else if (sectionId === 'bans') {
                topbarTitle.textContent = 'Список блокировок';
            } else if (sectionId === 'reports') {
                topbarTitle.textContent = 'Жалобы игроков';
            } else {
                topbarTitle.textContent = 'Панель управления';
            }
        }

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
            case 'staff':
            case 'admins': renderStaffView(); break;
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
        ban: {
            id: 'ban',
            label: 'ВЫДАТЬ БАН',
            sub: 'Блокировка',
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
            color: 'var(--red)',
            action: "window.openQuickBanModal('')"
        },
        mute: {
            id: 'mute',
            label: 'ВЫДАТЬ МУТ',
            sub: 'Заглушить в чате',
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5L6 9H2v6h4l5 4V5z"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>`,
            color: 'var(--yellow)',
            action: "window.openQuickMuteModal()"
        },
        kick: {
            id: 'kick',
            label: 'КИК ИГРОКА',
            sub: 'Выгнать с сервера',
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="18" y1="8" x2="23" y2="13"/><line x1="23" y1="8" x2="18" y2="13"/></svg>`,
            color: '#F87171',
            action: "window.openQuickKickModal()"
        },
        vanish: {
            id: 'vanish',
            label: 'VANISH',
            sub: 'Режим скрытности',
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>`,
            color: 'var(--accent)',
            action: "window.openQuickVanishModal()"
        },
        clear_chat: {
            id: 'clear_chat',
            label: 'ОЧИСТИТЬ ЧАТ',
            sub: 'Сброс спама',
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>`,
            color: 'var(--green)',
            action: "window.quickClearChatAction()"
        },
        dossier: {
            id: 'dossier',
            label: 'ДОСЬЕ ИГРОКА',
            sub: 'Поиск по нику',
            icon: `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><path d="M11 8v6M8 11h6"/></svg>`,
            color: 'var(--text-secondary)',
            action: "window.openQuickPlayerSearchModal()"
        }
    };

    function getActiveQuickActions() {
        try {
            const saved = localStorage.getItem('wa_quick_actions');
            if (saved) {
                const parsed = JSON.parse(saved);
                const valid = parsed.filter(k => ALL_QUICK_ACTIONS[k]);
                if (valid.length > 0) return valid;
            }
        } catch (e) {}
        return ['ban', 'mute', 'kick', 'vanish', 'clear_chat', 'dossier'];
    }

    function saveActiveQuickActions(keys) {
        localStorage.setItem('wa_quick_actions', JSON.stringify(keys));
    }

    window.openQuickActionsConfigModal = () => {
        const active = getActiveQuickActions();
        openModal(`
            <div class="modal-header">
                <h3>НАСТРОЙКА БЫСТРЫХ ДЕЙСТВИЙ</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
            </div>
            <div class="modal-body">
                <div style="font-size:12px; color:var(--text-muted); margin-bottom:14px;">
                    Выберите действия персонала для быстрого реагирования на дашборде:
                </div>
                <div style="display:flex; flex-direction:column; gap:10px;">
                    ${Object.values(ALL_QUICK_ACTIONS).map(a => `
                        <label class="toggle-switch-wrap" style="justify-content:space-between;">
                            <div style="display:flex; align-items:center; gap:10px;">
                                <span style="font-size:18px; color:${a.color}; display:flex; align-items:center;">${a.icon}</span>
                                <div>
                                    <b style="color:var(--text-heading); font-size:13px;">${esc(a.label)}</b>
                                    <div style="font-size:11px; color:var(--text-dim);">${esc(a.sub)}</div>
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
                saveActiveQuickActions(selected.length ? selected : ['ban', 'mute', 'kick']);
                closeModal();
                showToast('Сохранено', 'Список быстрых действий обновлен', 'success');
                renderDashboardView();
            });
        });
    };

    window.openQuickPlayerSearchModal = function() {
        openModal(`
            <div class="modal-header">
                <h3>ПОИСК ДОСЬЕ ИГРОКА</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Никнейм игрока</label>
                    <input type="text" id="quick-dossier-search-name" placeholder="Введите ник игрока..." required autofocus autocomplete="off">
                </div>
                <div style="font-size:12px; color:var(--text-muted); line-height:1.5;">
                    Мгновенный переход к полному досье игрока: альты, история наказаний, активные ограничения, инвентарь и статистика.
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-submit-quick-dossier">ОТКРЫТЬ ДОСЬЕ</button>
            </div>
        `, () => {
            const input = document.getElementById('quick-dossier-search-name');
            if (input) attachPlayerAutocomplete(input);
            const submit = () => {
                const name = input ? input.value.trim() : '';
                if (!name) return;
                closeModal();
                window.viewPlayerProfile(name);
            };
            input?.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    submit();
                }
            });
            document.getElementById('btn-submit-quick-dossier')?.addEventListener('click', submit);
        });
    };

    window.quickClearChatAction = async () => {
        confirmAction('ОЧИСТКА ЧАТА', 'Очистить глобальный чат сервера для всех игроков от флуда и спама?', async () => {
            try {
                await api('POST', '/api/command', { command: 'broadcast &r \n \n \n \n \n \n \n \n \n \n \n \n &eГлобальный чат был очищен администратором.' });
                showToast('Чат очищен', 'Команда очистки чата выполнена', 'info');
                recordShiftAction('Очистил чат сервера');
            } catch (e) {
                showToast('Чат очищен', 'Сообщение отправлено на сервер', 'info');
            }
        }, 'ОЧИСТИТЬ', false);
    };

    function getActiveDashboardTiles() {
        let prefs = {};
        try {
            prefs = typeof me?.uiPreferences === 'string' ? JSON.parse(me.uiPreferences) : (me?.uiPreferences || {});
        } catch (_) {}
        if (Array.isArray(prefs.dashboardTiles) && prefs.dashboardTiles.length) {
            return prefs.dashboardTiles;
        }
        try {
            const saved = localStorage.getItem('wa_dashboard_tiles');
            if (saved) return JSON.parse(saved);
        } catch (e) {}
        return DEFAULT_DASHBOARD_TILES;
    }

    async function saveDashboardTiles(tiles) {
        localStorage.setItem('wa_dashboard_tiles', JSON.stringify(tiles));
        let prefs = {};
        try {
            prefs = typeof me?.uiPreferences === 'string' ? JSON.parse(me.uiPreferences) : (me?.uiPreferences || {});
        } catch (_) {}
        prefs.dashboardTiles = tiles;
        if (me) me.uiPreferences = JSON.stringify(prefs);
        try {
            await api('PUT', '/api/me/preferences', { uiPreferences: prefs });
        } catch (_) {}
    }

    async function renderDashboardView() {
        const area = document.getElementById('content-area');
        let activeTiles = getActiveDashboardTiles();

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>ДАШБОРД</h2>
                    <p>Оперативная панель мониторинга сервера и быстрого реагирования (перетаскивайте плитки для настройки порядка)</p>
                </div>
                <div class="view-actions">
                    <button type="button" class="secondary" id="dash-customize-btn" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('gear', 'gray', 14)} НАСТРОЙКА ПЛИТОК</button>
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
        grid.innerHTML = activeTiles.map((tileKey, idx) => {
            const html = getTileContainerHtml(tileKey);
            return html.replace('<div class="tile ', `<div draggable="true" data-tile-key="${tileKey}" data-tile-idx="${idx}" class="tile `);
        }).join('');

        // Mount Drag & Drop handlers for tiles
        let draggedTileKey = null;
        let draggedTileIdx = null;

        grid.querySelectorAll('.tile[draggable="true"]').forEach(el => {
            el.addEventListener('dragstart', (e) => {
                draggedTileKey = el.dataset.tileKey;
                draggedTileIdx = parseInt(el.dataset.tileIdx, 10);
                el.classList.add('dragging');
                e.dataTransfer.effectAllowed = 'move';
                e.dataTransfer.setData('text/plain', draggedTileKey);
            });
            el.addEventListener('dragenter', (e) => {
                e.preventDefault();
                if (el.dataset.tileKey !== draggedTileKey) {
                    el.classList.add('drag-over');
                }
            });
            el.addEventListener('dragover', (e) => {
                e.preventDefault();
                e.dataTransfer.dropEffect = 'move';
            });
            el.addEventListener('dragleave', (e) => {
                if (!el.contains(e.relatedTarget)) {
                    el.classList.remove('drag-over');
                }
            });
            el.addEventListener('drop', async (e) => {
                e.preventDefault();
                el.classList.remove('drag-over');
                const targetKey = el.dataset.tileKey;
                const targetIdx = parseInt(el.dataset.tileIdx, 10);
                if (draggedTileKey && targetKey && draggedTileKey !== targetKey) {
                    const newTiles = [...activeTiles];
                    const [removed] = newTiles.splice(draggedTileIdx, 1);
                    newTiles.splice(targetIdx, 0, removed);
                    activeTiles = newTiles;
                    await saveDashboardTiles(newTiles);
                    renderDashboardView();
                    showToast('Порядок сохранён', 'Порядок плиток обновлён и сохранён в профиль', 'info');
                }
            });
            el.addEventListener('dragend', () => {
                el.classList.remove('dragging');
                grid.querySelectorAll('.tile').forEach(t => t.classList.remove('drag-over'));
                draggedTileKey = null;
                draggedTileIdx = null;
            });
        });

        // Mount tile logic
        activeTiles.forEach(tileKey => mountTileLogic(tileKey));
    }

    function getTileContainerHtml(key) {
        switch (key) {
            case 'server_stats':
                return `
                    <div class="tile tile-lg" id="tile-server_stats">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon('server', 'violet', 16)} СТАТИСТИКА СЕРВЕРА</span>
                            <span class="badge purple">LIVE 3S</span>
                        </div>
                        <div class="tile-body" id="tile-body-server_stats">Загрузка метрик...</div>
                    </div>`;
            case 'quick_actions': {
                const activeKeys = getActiveQuickActions();
                return `
                    <div class="tile tile-sm" id="tile-quick_actions">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">
                                ${renderSvgIcon('zap', 'violet', 16)}
                                БЫСТРЫЕ ДЕЙСТВИЯ
                            </span>
                            <button type="button" class="secondary btn-sm" onclick="window.openQuickActionsConfigModal()" title="Настроить состав действий">НАСТРОИТЬ</button>
                        </div>
                        <div class="tile-body" id="tile-body-quick_actions">
                            <div class="quick-actions-grid">
                                ${activeKeys.map(k => {
                                    const act = ALL_QUICK_ACTIONS[k];
                                    if (!act) return '';
                                    return `
                                        <button type="button" class="quick-action-btn" onclick="${act.action}">
                                            <span class="qa-icon" style="color:${act.color};">${act.icon}</span>
                                            <span class="qa-label">${esc(act.label)}</span>
                                            <span class="qa-sub">${esc(act.sub || '')}</span>
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
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon('shieldAlert', 'violet', 16)} ПОСЛЕДНИЕ СРАБАТЫВАНИЯ АНТИЧИТА</span>
                            <button type="button" class="secondary btn-sm" onclick="window.navigateTo('anticheat')">ВСЕ ФЛАГИ →</button>
                        </div>
                        <div class="tile-body" id="tile-body-vesuvio_flags" style="padding:10px 18px;">Загрузка потока...</div>
                    </div>`;
            case 'attention':
                return `
                    <div class="tile tile-sm" id="tile-attention">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon('alert', 'red', 16)} ТРЕБУЕТ ВНИМАНИЯ</span>
                            <span class="badge yellow">ФЛАГИ & РИСК</span>
                        </div>
                        <div class="tile-body" id="tile-body-attention">Загрузка...</div>
                    </div>`;
            case 'punishments_stats':
                return `
                    <div class="tile tile-sm" id="tile-punishments_stats">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon('scales', 'violet', 16)} СТАТИСТИКА НАКАЗАНИЙ</span>
                        </div>
                        <div class="tile-body" id="tile-body-punishments_stats">Загрузка...</div>
                    </div>`;
            case 'online_players':
                return `
                    <div class="tile tile-sm" id="tile-online_players">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon('users', 'violet', 16)} ОНЛАЙН-ИГРОКИ</span>
                            <button type="button" class="secondary btn-sm" onclick="window.navigateTo('server')">ИГРОКИ →</button>
                        </div>
                        <div class="tile-body" id="tile-body-online_players" style="max-height:280px; overflow-y:auto; padding:8px 14px;">Загрузка...</div>
                    </div>`;
            case 'admin_actions':
                return `
                    <div class="tile tile-sm" id="tile-admin_actions">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon('fileText', 'gray', 16)} ПОСЛЕДНИЕ ДЕЙСТВИЯ ${isModeratorRole() ? 'МОИ' : 'АДМИНИСТРАЦИИ'}</span>
                            <button type="button" class="secondary btn-sm" onclick="window.navigateTo('journal')">ЖУРНАЛ →</button>
                        </div>
                        <div class="tile-body" id="tile-body-admin_actions" style="max-height:280px; overflow-y:auto; padding:10px 16px;">Загрузка...</div>
                    </div>`;
            case 'my_shift':
                return `
                    <div class="tile tile-sm" id="tile-my_shift">
                        <div class="tile-header">
                            <span class="tile-title" style="display:flex; align-items:center; gap:8px;">${renderSvgIcon('clock', 'green', 16)} МОЯ СМЕНА</span>
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
                if (body) body.innerHTML = `<div style="color:var(--text-dim); text-align:center; padding:16px;">Античит недоступен или отключен</div>`;
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
                            <div style="margin-bottom:6px; display:inline-flex; align-items:center;">${renderSvgIcon('check', 'green', 24)}</div>
                            <div>Подозрительных игроков нет</div>
                        </div>`;
                } else {
                    body.innerHTML = list.map(p => `
                        <div style="display:flex; align-items:center; justify-content:space-between; padding:8px 0; border-bottom:1px solid var(--border);">
                            <div style="display:flex; align-items:center; gap:8px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name || '')}/24" class="player-avatar-sm" alt="">
                                <div>
                                    <div style="font-weight:700; color:var(--text-main);">${esc(p.name)}</div>
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
                                <span style="font-weight:600; color:var(--text-heading);">${esc(p.name)}</span>
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
            { key: 'quick_actions', title: 'Быстрые действия (Бан, Мут, Кик, Спавн)' },
            { key: 'vesuvio_flags', title: 'Последние срабатывания античита' },
            { key: 'attention', title: '«Требует внимания» (подозрительные игроки)' },
            { key: 'punishments_stats', title: 'Статистика наказаний (сегодня / неделя)' },
            { key: 'online_players', title: 'Онлайн-игроки с быстрыми действиями' },
            { key: 'admin_actions', title: 'Последние действия администрации' },
            { key: 'my_shift', title: 'Мои действия за текущую смену' }
        ];

        openModal(`
            <div class="modal-header">
                <h3>НАСТРОЙКА ПЛИТОК ДАШБОРДА</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon('close', 'gray', 16)}</button>
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
                                <span style="font-size:13px; font-weight:600; color:var(--text-heading);">${esc(t.title)}</span>
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
        const canViewWebLogs = hasPerm('VIEW_WEB_LOGS');
        let primaryMode = canViewWebLogs ? 'web' : 'server'; // 'web' or 'server'
        let webSubfilter = 'ALL'; // ALL, AUTH, PUNISHMENTS, REPORTS, ROLES
        let serverSubfilter = 'ALL'; // ALL, COMMANDS, GAME, WARNINGS
        let filterStaff = '';
        let filterActionType = 'ALL';
        let filterDateFrom = '';
        let filterDateTo = '';
        let searchQuery = '';
        let cachedWebLogs = [];
        let cachedServerLogs = [];

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>ЖУРНАЛ СОБЫТИЙ</h2>
                    <p>Аудит действий веб-панели и серверных событий Minecraft</p>
                </div>
            </div>

            <!-- Primary Mode Tabs (Web vs Server) -->
            <div class="segmented-nav-tabs" style="margin-bottom:18px;">
                ${canViewWebLogs ? `
                <button type="button" class="segmented-nav-tab ${primaryMode === 'web' ? 'active' : ''}" data-mode="web">
                    ${renderSvgIcon('globe', 'violet', 16)}
                    <span>Веб журнал</span>
                </button>` : ''}
                <button type="button" class="segmented-nav-tab ${primaryMode === 'server' ? 'active' : ''}" data-mode="server">
                    ${renderSvgIcon('server', 'gray', 16)}
                    <span>Серверный журнал</span>
                </button>
            </div>

            <!-- Toolbar & Subfilters -->
            <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:14px; margin-bottom:18px; display:flex; flex-direction:column; gap:12px;">
                <div class="filter-tags" id="journal-subfilters">
                    <!-- Dynamic subfilter buttons inserted by renderSubfilters() -->
                </div>

                <!-- Audit Log Filters: Staff, Action, Date Range -->
                <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                    <div style="flex:1; min-width:200px; position:relative;">
                        <input type="text" id="journal-search-input" placeholder="Поиск по содержанию лога..." style="padding-left:34px;">
                        <span style="position:absolute; left:12px; top:50%; transform:translateY(-50%); display:inline-flex; align-items:center;">${renderSvgIcon('search', 'gray', 14)}</span>
                    </div>

                    <input type="text" id="journal-filter-staff" placeholder="Сотрудник (ник)..." style="width:160px;">

                    <select id="journal-filter-action" style="width:170px;">
                        <option value="ALL">Все типы действий</option>
                        <option value="AUTH">Авторизация / Сессии</option>
                        <option value="PUNISHMENTS">Баны / Наказания</option>
                        <option value="REPORTS">Жалобы игроков</option>
                        <option value="ROLES">Роли и персонал</option>
                        <option value="MAINTENANCE">Технические работы</option>
                    </select>

                    <div style="display:flex; align-items:center; gap:6px;">
                        <span style="font-size:12px; color:var(--text-dim);">С:</span>
                        <input type="date" id="journal-filter-from" style="width:130px; font-size:12px; padding:6px;">
                        <span style="font-size:12px; color:var(--text-dim);">По:</span>
                        <input type="date" id="journal-filter-to" style="width:130px; font-size:12px; padding:6px;">
                    </div>

                    <button type="button" class="secondary btn-sm" id="journal-reset-filters">СБРОС</button>
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
            if (s.includes('бан') || s.includes('разбан') || s.includes('кик') || s.includes('наказан')) {
                return { type: 'НАКАЗАНИЕ', typeClass: 'red' };
            }
            if (s.includes('жалоб') || s.includes('репорт')) {
                return { type: 'ЖАЛОБЫ', typeClass: 'purple' };
            }
            if (s.includes('рол') || s.includes('персонал') || s.includes('сотрудник') || s.includes('прав') || s.includes('приглаш') || s.includes('инвайт')) {
                return { type: 'ПЕРСОНАЛ', typeClass: 'yellow' };
            }
            if (s.includes('тех') || s.includes('maintenance')) {
                return { type: 'ТЕХ. РАБОТЫ', typeClass: 'yellow' };
            }
            return { type: 'ДЕЙСТВИЕ', typeClass: 'cyan' };
        };

        const loadLogs = async () => {
            const body = document.getElementById('journal-table-body');

            try {
                if (primaryMode === 'web') {
                    let queryUrl = '/api/logs/web?limit=300';
                    if (filterStaff) queryUrl += `&staff=${encodeURIComponent(filterStaff)}`;
                    if (filterActionType && filterActionType !== 'ALL') queryUrl += `&action=${encodeURIComponent(filterActionType)}`;
                    if (filterDateFrom) {
                        const fromSec = Math.floor(new Date(filterDateFrom).getTime() / 1000);
                        queryUrl += `&from=${fromSec}`;
                    }
                    if (filterDateTo) {
                        const toSec = Math.floor(new Date(filterDateTo).getTime() / 1000) + 86399;
                        queryUrl += `&to=${toSec}`;
                    }

                    const rawLogs = await api('GET', queryUrl).catch(() => []);
                    cachedWebLogs = rawLogs
                        .filter(l => {
                            const act = (l.action || '').toLowerCase();
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
                        api('GET', '/api/logs/server?limit=200').catch(() => []),
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

            // Apply staff filter in server mode if specified
            if (filterStaff && primaryMode === 'server') {
                const fs = filterStaff.toLowerCase();
                source = source.filter(e => e.actor && e.actor.toLowerCase().includes(fs));
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

            body.innerHTML = source.slice(0, 200).map((row, idx) => `
                <tr>
                    <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(row.time)}</td>
                    <td style="font-weight:700;">
                        <span style="cursor:pointer; color:var(--text-heading);" onclick="window.viewPlayerProfile('${esc(row.actor)}')">${esc(row.actor)}</span>
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
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
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
                        <div style="color:var(--text-main);">${esc(item.action)}</div>
                    </div>

                    <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:6px;">Сырые данные:</div>
                    <pre style="background:var(--card-inner-bg); padding:12px; border-radius:6px; font-size:12px; color:var(--accent-light); border:1px solid var(--border); overflow-x:auto;">${esc(JSON.stringify(item.raw, null, 2))}</pre>
                </div>
                <div class="modal-footer">
                    <button type="button" class="primary" onclick="window.viewPlayerProfile('${esc(item.actor)}');">КАРТОЧКА ИГРОКА</button>
                    <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
                </div>
            `);
        };

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

        // Filter Inputs
        document.getElementById('journal-filter-staff')?.addEventListener('input', (e) => {
            filterStaff = e.target.value.trim();
            loadLogs();
        });

        document.getElementById('journal-filter-action')?.addEventListener('change', (e) => {
            filterActionType = e.target.value;
            loadLogs();
        });

        document.getElementById('journal-filter-from')?.addEventListener('change', (e) => {
            filterDateFrom = e.target.value;
            loadLogs();
        });

        document.getElementById('journal-filter-to')?.addEventListener('change', (e) => {
            filterDateTo = e.target.value;
            loadLogs();
        });

        document.getElementById('journal-reset-filters')?.addEventListener('click', () => {
            filterStaff = '';
            filterActionType = 'ALL';
            filterDateFrom = '';
            filterDateTo = '';
            searchQuery = '';
            const isStaff = document.getElementById('journal-filter-staff'); if (isStaff) isStaff.value = '';
            const isAct = document.getElementById('journal-filter-action'); if (isAct) isAct.value = 'ALL';
            const isFrom = document.getElementById('journal-filter-from'); if (isFrom) isFrom.value = '';
            const isTo = document.getElementById('journal-filter-to'); if (isTo) isTo.value = '';
            const isQ = document.getElementById('journal-search-input'); if (isQ) isQ.value = '';
            loadLogs();
        });

        // Search input
        document.getElementById('journal-search-input')?.addEventListener('input', (e) => {
            searchQuery = e.target.value.trim();
            renderTableRows();
        });

        renderSubfilters();
        renderTableHead();
        await loadLogs();

        // Auto-refresh without manual button
        pollers['journal_auto_refresh'] = setInterval(loadLogs, 10000);
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
                    { id: 'v_approved_error', name: 'Одобрено (Ошибочный бан)', type: 'APPROVE', template: '**[ОДОБРЕНО] АПЕЛЛЯЦИЯ ОДОБРЕНА**\n\nУважаемый **{targetName}**!\nБлокировка **#{id}** была пересмотрена. В ходе повторной проверки доказательств была установлена ошибка.\nБлокировка полностью снята, аккаунт разбанен. Приносим извинения за неудобства.\n\n*Администрация сервера*' },
                    { id: 'v_approved_amnesty', name: 'Одобрено (Амнистия)', type: 'APPROVE', template: '**[ОДОБРЕНО] АПЕЛЛЯЦИЯ ОДОБРЕНА (АМНИСТИЯ)**\n\nУважаемый **{targetName}**!\nАдминистрация приняла решение удовлетворить вашу просьбу об амнистии по блокировке **#{id}** ({ruleReason}).\nБан снят. Пожалуйста, соблюдайте правила проекта во избежание повторного бессрочного бана.' },
                    { id: 'v_rejected_cheats', name: 'Отклонено (Читы доказаны)', type: 'REJECT', template: '**[ОТКЛОНЕНО] АПЕЛЛЯЦИЯ ОТКЛОНЕНА**\n\nУважаемый **{targetName}**!\nВаша апелляция по бану **#{id}** рассмотрена.\nФакт использования запрещённого ПО ({ruleReason}) подтверждён видеозаписью и телеметрией античита.\nБлокировка остаётся в силе и является **бессрочной**.' },
                    { id: 'v_rejected_expired', name: 'Отклонено (Истёк срок)', type: 'REJECT', template: '**[ОТКЛОНЕНО] АПЕЛЛЯЦИЯ ОТКЛОНЕНА (ИСТЁК СРОК)**\n\nУважаемый **{targetName}**!\nСрок подачи апелляции на блокировку от {createdAt} истёк.\nАпелляция не подлежит дальнейшему рассмотрению.' },
                    { id: 'v_info_request', name: 'Запрос доп. информации', type: 'INFO', template: '**[ТРЕБУЕТСЯ УТОЧНЕНИЕ]**\n\nУважаемый **{targetName}**!\nДля вынесения вердикта по блокировке **#{id}** предоставьте дополнительную информацию или опровержение в течение 24 часов.' }
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
                    <button type="button" class="primary" id="btn-ban-archive" class="secondary">Архив</button><button type="button" class="primary" id="btn-create-ban-top">+ ВЫДАТЬ БАН</button>
                </div>
            </div>

            <!-- Subtabs Navigation -->
            <div class="segmented-nav-tabs" id="punishments-subtabs-bar" style="margin-bottom:20px;">
                <button type="button" class="segmented-nav-tab ${currentPunishmentsSubtab === 'reports' ? 'active' : ''}" data-subtab="reports">
                    <span>${renderSvgIcon('fileText', 'violet', 15)}</span>
                    <span>ЖАЛОБЫ И РЕПОРТЫ</span>
                    <span class="badge yellow" id="punishments-pending-badge-tab" style="margin-left:4px; font-size:10px;">0</span>
                </button>
                <button type="button" class="segmented-nav-tab ${currentPunishmentsSubtab === 'bans' ? 'active' : ''}" data-subtab="bans">
                    <span>${renderSvgIcon('shield', 'red', 15)}</span>
                    <span>БАНЫ СЕРВЕРА</span>
                </button>
                <button type="button" class="segmented-nav-tab ${currentPunishmentsSubtab === 'appeals' ? 'active' : ''}" data-subtab="appeals">
                    <span>${renderSvgIcon('scales', 'violet', 15)}</span>
                    <span>АПЕЛЛЯЦИИ (DISCORD)</span>
                </button>
            </div>

            <!-- Subtab Content Area -->
            <div id="punishments-subtab-container"></div>
        `;

        document.getElementById('btn-create-ban-top')?.addEventListener('click', () => openQuickBanModal(''));
        document.getElementById('btn-ban-archive')?.addEventListener('click', async () => {
            try {
                const data = await api('GET', '/api/bans/archive');
                const files = data.files || [];
                openModal(`<div class="modal-header"><h3>Архив банов</h3><button type="button" class="close-btn" data-modal-close="true">×</button></div>
                <div class="modal-body"><p style="font-size:12px;color:var(--text-muted);margin-bottom:12px;">Zip: plugins/LoveWebAdmin/archive/ (${data.retentionDays||90} дн.)</p>
                ${files.length ? '<ul style="font-size:13px;">'+files.map(f=>'<li>'+esc(f)+'</li>').join('')+'</ul>' : '<div style="text-align:center;color:var(--text-dim);padding:16px;">Пусто</div>'}</div>
                <div class="modal-footer"><button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button></div>`);
            } catch (e) { showToast('Архив', e.message||'Ошибка','error'); }
        });

        // Auto-refresh pollers for punishments
        pollers['punishments_sync'] = setInterval(() => {
            if (currentPunishmentsSubtab === 'reports') loadReportsSubtab();
            else if (currentPunishmentsSubtab === 'bans') loadBansSubtab();
            else if (currentPunishmentsSubtab === 'appeals') {
                const listWrap = document.getElementById('live-appeals-list-wrap');
                if (listWrap && typeof renderLiveAppealsList === 'function') renderLiveAppealsList();
            }
        }, 10000);

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
                            <span style="display:flex; align-items:center; gap:6px;">${renderSvgIcon('alert', 'red', 14)} ПОВТОРНЫЕ НАРУШИТЕЛИ (4+ ЖАЛОБЫ)</span>
                            <span class="badge red">ТРЕБУЮТ ПРИОРИТЕТНОГО ВНИМАНИЯ</span>
                        </div>
                        <div class="frequent-offenders-grid">
                            ${offenders.map(o => `
                                <div class="frequent-offender-item">
                                    <div style="display:flex; align-items:center; gap:10px;">
                                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(o.name)}/32" class="player-avatar-sm" alt="" style="width:32px; height:32px; border-radius:4px;">
                                        <div>
                                            <div style="font-weight:700; color:var(--text-main); font-size:13.5px;">${esc(o.name)}</div>
                                            <span class="badge red" style="display:inline-flex; align-items:center; gap:4px; font-size:10px; padding:2px 6px;">${renderSvgIcon('alert', 'red', 11)} ${o.count} жалоб</span>
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
                        ? `<span style="color:var(--text-main);" title="${esc(r.description)}">${esc(r.description.length > 35 ? r.description.substring(0, 35) + '...' : r.description)}</span>`
                        : '<i style="color:var(--text-dim); font-size:12px;">не указано</i>';

                    const recentBadge = r.isRecent
                        ? `<span class="badge green" style="font-size:10.5px; display:inline-flex; align-items:center; gap:4px;" title="Произошло менее 5 минут назад">${renderSvgIcon('clock', 'green', 11)} &lt; 5 мин</span>`
                        : `<span class="badge gray" style="font-size:10.5px; display:inline-flex; align-items:center; gap:4px;" title="Произошло более 5 минут назад">${renderSvgIcon('clock', 'gray', 11)} &gt; 5 мин</span>`;

                    const suspBadge = r.targetSuspicious
                        ? `<span class="badge danger" style="margin-left:4px; font-size:10px; font-weight:800; display:inline-flex; align-items:center; gap:3px;" title="Более 4-х активных жалоб!">${renderSvgIcon('alert', 'red', 10)} 4+</span>`
                        : '';

                    const isPending = r.status === 'PENDING';

                    return `
                        <tr>
                            <td class="font-mono" style="color:var(--text-dim);">#${r.id}</td>
                            <td>
                                <div style="display:flex; align-items:center; gap:6px;">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.targetName)}/24" style="width:24px; height:24px; border-radius:4px;" alt="">
                                    <a href="javascript:void(0)" onclick="window.viewPlayerProfile('${esc(r.targetName)}')" style="color:var(--text-main); font-weight:700; text-decoration:none;">${esc(r.targetName)}</a>
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
                                    ${(r.linkedBanId && r.linkedBanId > 0) ? `<button type="button" class="btn-tag-link" style="color:#f87171; border-color:rgba(239, 68, 68, 0.4); display:inline-flex; align-items:center; gap:4px;" onclick="window.viewBanDetails(${r.linkedBanId})" title="Открыть примененный бан #${r.linkedBanId}">${renderSvgIcon('shield', 'red', 12)} БАН #${r.linkedBanId}</button>` : ''}
                                </div>
                            </td>
                            <td style="text-align:right; white-space:nowrap;">
                                <div style="display:inline-flex; gap:5px;">
                                    <button type="button" class="secondary btn-sm" onclick="window.openReportDetailModal(${r.id})" title="Просмотреть детали и контекст чата">ИНФО</button>
                                    ${isPending ? `
                                        <button type="button" class="primary btn-sm" onclick="window.handleAcceptReport(${r.id}, '${esc(r.targetName)}')" title="Принять и наказать (начислить репутацию заявителю)">${renderSvgIcon('check', 'white', 12)}</button>
                                        <button type="button" class="secondary btn-sm" onclick="window.handleRejectReport(${r.id}, '${esc(r.targetName)}')" title="Пометить как ложную (вернуть репутацию цели)">ЛОЖЬ</button>
                                        <button type="button" class="danger btn-sm" onclick="window.handleBanFromReportRow(${r.id})" title="Выдать бан по жалобе #${r.id}">БАН</button>
                                    ` : ''}
                                    <button type="button" class="danger btn-sm" onclick="window.handleDeleteReport(${r.id})" title="Удалить запись жалобы">${renderSvgIcon('close', 'white', 12)}</button>
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
                                        <div style="font-weight:700; color:var(--text-heading);">${esc(b.targetName)}</div>
                                        ${b.isIpBan ? '<span class="badge yellow" style="font-size:9px; padding:1px 4px;">IP-БАН</span>' : ''}
                                    </div>
                                </div>
                            </td>
                            <td>
                                <div style="display:flex; align-items:center; gap:6px; flex-wrap:wrap;">
                                    <span class="badge red">${esc(b.ruleReason)}</span>
                                    ${b.linkedReportId ? `<button type="button" class="btn-tag-link" style="color:#c084fc; border-color:rgba(192, 132, 252, 0.4); display:inline-flex; align-items:center; gap:4px;" onclick="window.openReportDetailModal(${b.linkedReportId})" title="Открыть прикрепленную жалобу #${b.linkedReportId}">${renderSvgIcon('fileText', 'violet', 12)} ЖАЛОБА #${b.linkedReportId}</button>` : ''}
                                    ${proofUrl ? `<button type="button" class="screenshot-thumb-btn" onclick="window.openScreenshotLightbox('${esc(proofUrl)}')">${renderSvgIcon('fileText', 'gray', 12)} СКРИНШОТ</button>` : ''}
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
                                <span>${renderSvgIcon('message', 'violet', 20)}</span>
                                <h3 style="margin:0; font-size:16px; color:var(--text-main);">ИНТЕГРАЦИЯ С DISCORD: ТИКЕТЫ АПЕЛЛЯЦИЙ</h3>
                                <span class="badge green" style="font-size:10.5px; display:inline-flex; align-items:center; gap:4px;">${renderSvgIcon('check', 'green', 11)} РАБОЧИЙ ШЛЮЗ</span>
                            </div>
                            <p style="font-size:13px; color:var(--text-secondary); margin:6px 0 0 0; line-height:1.5;">
                                На сервере приём апелляций осуществляется через Discord-бота в канале <b>#тикеты-апелляций</b>. 
                                Ниже представлена очередь апелляций и тикетов с поддержкой двусторонней переписки и принятия решений.
                            </p>
                        </div>
                        <div>
                            <button type="button" class="secondary btn-sm" id="btn-toggle-discord-docs" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('fileText', 'gray', 12)} СПРАВКА И МЕТОДЫ БОТА</button>
                        </div>
                    </div>
                </div>

                <!-- Collapsible Discord Bot API & Methods Doc -->
                <div id="discord-bot-docs-box" style="display:none; background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:16px; margin-bottom:20px;">
                    <h4 style="color:var(--accent-light); margin:0 0 10px 0; font-size:14px; display:flex; align-items:center; gap:6px;">${renderSvgIcon('server', 'violet', 14)} МЕТОДЫ И ИНСТРУКЦИИ ДЛЯ DISCORD-БОТА</h4>
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
    "title": "Вердикт по апелляции на блокировку",
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
                            <h3 style="margin:0; font-size:15px; color:var(--text-main); display:flex; align-items:center; gap:8px;">
                                <span>${renderSvgIcon('scales', 'violet', 15)}</span>
                                <span>ОЧЕРЕДЬ АПЕЛЛЯЦИЙ И ТИКЕТОВ DISCORD</span>
                                <span class="badge yellow" id="live-appeals-count-badge" style="font-size:11px;">0</span>
                            </h3>
                            <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                Все апелляции автоматически создают приватные тикеты Discord через системный мост и поддерживают двусторонний чат
                            </div>
                        </div>
                        <div style="display:flex; gap:8px;">
                            <select id="appeals-status-filter" style="font-size:12px; padding:5px 10px;">
                                <option value="ALL">Все статусы</option>
                                <option value="PENDING" selected>Ожидают решения (PENDING)</option>
                                <option value="APPROVED">Одобренные</option>
                                <option value="REJECTED">Отклонённые</option>
                            </select>
                        </div>
                    </div>

                    <div id="live-appeals-list-wrap">
                        <div style="text-align:center; padding:20px; color:var(--text-muted);">Загрузка апелляций...</div>
                    </div>
                </div>
            `;

            // Toggle docs box
            document.getElementById('btn-toggle-discord-docs')?.addEventListener('click', () => {
                const box = document.getElementById('discord-bot-docs-box');
                if (box) box.style.display = box.style.display === 'none' ? 'block' : 'none';
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
                                                    <span style="font-size:14px; font-weight:800; color:var(--text-main);">${esc(a.playerName)}</span>
                                                    ${stBadge}
                                                    ${a.discordChannelId ? `<span class="badge purple" style="font-size:10px; display:inline-flex; align-items:center; gap:4px;">${renderSvgIcon('message', 'violet', 11)} Discord: ticket-бан-${esc(a.playerName)}</span>` : ''}
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
                                            <button type="button" class="primary btn-sm" data-open-appeal-thread="${a.id}" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('message', 'white', 12)} ТИКЕТ / ТРЕД</button>
                                        </div>
                                    </div>
                                `;
                            }).join('')}
                        </div>
                    `;

                    listWrap.querySelectorAll('[data-open-appeal-thread]').forEach(btn => {
                        btn.addEventListener('click', () => openAppealThreadModal(parseInt(btn.dataset.openAppealThread, 10)));
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
                            <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon('close', 'gray', 16)}</button>
                        </div>
                        <div class="modal-body" style="max-height:65vh; overflow-y:auto;">
                            <div style="background:var(--card-inner-bg); border:1px solid var(--border); border-radius:var(--radius-sm); padding:12px; margin-bottom:14px; font-size:12.5px;">
                                <div style="color:var(--text-dim); font-size:11px; font-weight:700; text-transform:uppercase;">Причина / аргумент апелляции:</div>
                                <div style="color:var(--text-main); margin-top:4px; line-height:1.5;">${esc(appeal.reason)}</div>
                                ${appeal.discordChannelId ? `<div style="margin-top:8px; color:var(--accent-light); font-size:11.5px; display:flex; align-items:center; gap:4px;">${renderSvgIcon('message', 'violet', 12)} Discord Ticket ID: <code>${esc(appeal.discordChannelId)}</code></div>` : ''}
                            </div>

                            <div style="font-size:12px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:8px;">
                                История сообщений тикета (Синхронизация с Discord):
                            </div>
                            <div id="appeal-messages-thread" style="display:flex; flex-direction:column; gap:10px; margin-bottom:16px;">
                                ${(appeal.messages || []).map(m => `
                                    <div style="background:${m.isStaff ? 'rgba(124, 58, 237, 0.12)' : 'var(--card-inner-bg)'}; border:1px solid ${m.isStaff ? 'var(--accent)' : 'var(--border)'}; border-radius:6px; padding:10px 12px;">
                                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px; font-size:11.5px;">
                                            <div style="display:flex; align-items:center; gap:6px;">
                                                <b>${esc(m.authorName)}</b>
                                                ${m.isStaff ? '<span class="badge purple" style="font-size:9.5px;">ПЕРСОНАЛ</span>' : '<span class="badge blue" style="font-size:9.5px;">ИГРОК</span>'}
                                            </div>
                                            <span style="color:var(--text-dim); font-size:10.5px;">${fmtTime(m.createdAt)}</span>
                                        </div>
                                        <div style="color:var(--text-main); font-size:13px; line-height:1.5; white-space:pre-wrap;">${esc(m.message)}</div>
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
                                        <button type="button" class="primary btn-sm" id="btn-send-appeal-reply" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('message', 'white', 12)} Отправить ответ в Discord</button>
                                        <div style="display:flex; gap:8px;">
                                            <button type="button" class="danger btn-sm" id="btn-reject-appeal-modal" style="display:inline-flex; align-items:center; gap:4px;">${renderSvgIcon('ban', 'white', 12)} Отклонить</button>
                                            <button type="button" class="success btn-sm" id="btn-approve-appeal-modal" style="display:inline-flex; align-items:center; gap:4px;">${renderSvgIcon('check', 'white', 12)} Одобрить и разбанить</button>
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
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
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
                        <button type="button" class="btn-tag-link" id="btn-browse-reports-picker" style="font-size:11px; padding:2px 8px; display:inline-flex; align-items:center; gap:5px;">${renderSvgIcon('fileText', 'violet', 12)} Выбрать жалобу</button>
                    </div>
                    <input type="text" id="ban-target-name" placeholder="Введите ник игрока..." value="${esc(defaultPlayer)}" required autofocus autocomplete="off">
                </div>

                <div class="form-group">
                    <label>Причина бана (пункт правил)</label>
                    <select id="ban-reason-select" required>
                        <option value="">-- Выберите причину из списка --</option>
                        ${(() => {
                            const grouped = {};
                            reasons.forEach(r => {
                                const cat = r.category || 'Прочее';
                                if (!grouped[cat]) grouped[cat] = [];
                                grouped[cat].push(r);
                            });
                            return Object.keys(grouped).map(cat => `
                                <optgroup label="${esc(cat)}">
                                    ${grouped[cat].map(r => {
                                        const isSel = defaultReason && (
                                            r.name.toLowerCase() === defaultReason.toLowerCase() ||
                                            r.name.toLowerCase().startsWith(defaultReason.toLowerCase()) ||
                                            defaultReason.toLowerCase().startsWith(r.name.toLowerCase())
                                        );
                                        return `
                                            <option value="${esc(r.name)}" data-comment="${r.require_comment ? 'true' : 'false'}" ${isSel ? 'selected' : ''}>
                                                ${esc(r.name)}
                                            </option>
                                        `;
                                    }).join('')}
                                </optgroup>
                            `).join('');
                        })()}
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
                        <span style="color:var(--accent-light); font-weight:600; display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('download', 'violet', 14)} Загрузить файл скриншота</span>
                        <span style="font-size:11.5px; color:var(--text-muted); display:block; margin-top:3px;">Кликните для выбора, перетащите изображение или нажмите Ctrl+V</span>
                        <input type="file" id="ban-screenshot-file" accept="image/*" style="display:none;">
                    </div>
                    <div id="ban-screenshot-preview-wrap" style="display:none;">
                        <div class="screenshot-preview-box">
                            <img id="ban-screenshot-img" class="screenshot-preview-img" alt="Скриншот">
                            <button type="button" class="btn-remove-screenshot" id="btn-clear-screenshot" title="Удалить скриншот">${renderSvgIcon('close', 'gray', 14)}</button>
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
                                        <span style="font-weight:700; color:var(--text-heading);">от ${esc(r.reporterName)}</span>
                                        ${r.isRecent ? '<span class="badge green" style="font-size:10px;">⏱ &lt; 5 мин</span>' : ''}
                                    </div>
                                    <div style="font-size:12px; color:var(--text-secondary); margin-top:4px;">
                                        Причины: <b>${(r.reasons || []).join(', ') || 'не указаны'}</b>
                                    </div>
                                    ${r.description && r.description !== 'не указано' ? `
                                        <div style="font-size:12px; color:#e2e8f0; margin-top:4px;">"${esc(r.description)}"</div>
                                    ` : ''}
                                </div>
                                <button type="button" class="btn-remove-screenshot" id="btn-detach-report" title="Открепить жалобу">${renderSvgIcon('close', 'gray', 14)}</button>
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
                                    <span style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('alert', 'yellow', 13)} Найдено активных жалоб на игрока: <b>${list.length}</b></span>
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
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
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
                                <h3 style="margin:0; font-size:20px; color:var(--text-heading);">${esc(b.targetName)}</h3>
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
                                <button type="button" class="btn-tag-link" onclick="window.closeCurrentModal(); window.openReportDetailModal(${b.linkedReportId})" style="display:inline-flex; align-items:center; gap:5px;">
                                    ${renderSvgIcon('fileText', 'violet', 12)} ЖАЛОБА #${b.linkedReportId}
                                </button>
                            </div>
                        ` : `
                            <div style="color:var(--text-dim); font-weight:700;">СВЯЗАННАЯ ЖАЛОБА:</div>
                            <div>
                                <button type="button" class="secondary btn-sm" id="btn-attach-report-to-ban" style="font-size:11px; padding:2px 8px;">
                                    + ПРИКРЕПИТЬ ЖАЛОБУ
                                </button>
                            </div>
                        `}

                        <div style="color:var(--text-dim); font-weight:700;">ПОЯСНЕНИЕ:</div>
                        <div style="color:var(--text-main); line-height:1.5;">${esc(b.description || 'Комментарий отсутствует')}</div>
                    </div>

                    ${proofUrl ? `
                        <div style="margin-top:16px;">
                            <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:6px;">Скриншот-доказательство:</div>
                            <img src="${esc(proofUrl)}" class="screenshot-preview-img" style="max-height:240px; cursor:pointer;" onclick="window.openScreenshotLightbox('${esc(proofUrl)}')" alt="Доказательство">
                        </div>
                    ` : ''}
                `;

                document.getElementById('btn-attach-report-to-ban')?.addEventListener('click', async () => {
                    const input = prompt('Введите номер жалобы (ID) для прикрепления к бану #' + banId + ':');
                    if (!input || !input.trim()) return;
                    const reportId = parseInt(input.trim());
                    if (isNaN(reportId) || reportId <= 0) {
                        showToast('Ошибка', 'Укажите корректный числовой ID жалобы', 'warning');
                        return;
                    }
                    try {
                        await api('POST', `/api/bans/${banId}/attach-report`, { reportId });
                        showToast('Жалоба привязана', `Жалоба #${reportId} успешно привязана к бану #${banId}`, 'success');
                        closeModal();
                        window.viewBanDetails(banId);
                    } catch (err) {
                        showToast('Ошибка', err.message || 'Не удалось привязать жалобу', 'danger');
                    }
                });

                if (footer) {
                    footer.innerHTML = `
                        <button type="button" class="secondary" onclick="window.closeCurrentModal(); window.renderPunishmentsView('appeals', ${b.id});" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('scales', 'violet', 13)} АПЕЛЛЯЦИЯ (ТИКЕТЫ)</button>
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
        const overlay = document.createElement('div');
        overlay.className = 'lightbox-overlay';
        overlay.innerHTML = `
            <div style="position:relative; max-width:92vw; max-height:92vh; display:flex; flex-direction:column; align-items:center;">
                <button type="button" class="btn-remove-screenshot" style="top:-14px; right:-14px; width:32px; height:32px;" onclick="this.closest('.lightbox-overlay').remove()">${renderSvgIcon('close', 'white', 16)}</button>
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

    // Confirm Unban
    window.confirmUnban = function(banId, playerName) {
        if (isModeratorRole() && !me.isOwner) {
            showToast('Недостаточно прав', 'Разбан игроков разрешён только Администраторам сервера!', 'warning');
            return;
        }
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
                    if (activeNavSection === 'punishments' && window.renderPunishmentsView) window.renderPunishmentsView('bans');
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
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
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
                                                <b style="color:var(--text-heading);">${esc(a.name)}</b>
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
                    await api('POST', `/api/reports/me.lovelace:LoveWebAdmin:jar:1.1.0/accept`);
                    recordShiftAction(`Принял жалобу #me.lovelace:LoveWebAdmin:jar:1.1.0 на игрока ${targetName}`);
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
                    await api('POST', `/api/reports/me.lovelace:LoveWebAdmin:jar:1.1.0/reject`);
                    recordShiftAction(`Отклонил жалобу #me.lovelace:LoveWebAdmin:jar:1.1.0 на игрока ${targetName} как ложную`);
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
                    await api('DELETE', `/api/reports/me.lovelace:LoveWebAdmin:jar:1.1.0`);
                    recordShiftAction(`Удалил запись жалобы #me.lovelace:LoveWebAdmin:jar:1.1.0`);
                    showToast('Удалено', `Жалоба #me.lovelace:LoveWebAdmin:jar:1.1.0 успешно удалена`, 'info');
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
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
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
                                    <div style="font-size:15px; font-weight:700; color:var(--text-main);">${esc(r.targetName)}</div>
                                    <div style="font-size:11.5px; color:var(--text-muted);">${r.targetReputation != null ? 'Репутация: ' + r.targetReputation : ''}</div>
                                </div>
                            </div>
                        </div>

                        <div style="background:rgba(255,255,255,0.02); border:1px solid var(--border); border-radius:6px; padding:12px;">
                            <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Заявитель (Отправитель):</div>
                            <div style="display:flex; align-items:center; gap:10px; margin-top:8px;">
                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(r.reporterName)}/40" style="width:40px; height:40px; border-radius:4px;" alt="">
                                <div>
                                    <div style="font-size:15px; font-weight:700; color:var(--text-main);">${esc(r.reporterName)}</div>
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
                        <div style="color:var(--text-main);">${esc(r.description || 'Не указано')}</div>

                        <div style="color:var(--text-dim); font-weight:700;">ВРЕМЯ ПОДАЧИ:</div>
                        <div>${fmtTime(r.createdAt)} (${r.isRecent ? 'менее 5 минут назад' : 'более 5 минут назад'})</div>
                    </div>

                    <div style="margin-top:16px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; margin-bottom:6px;">Снимок чата на момент жалобы:</div>
                        <div style="background:var(--card-inner-bg); border:1px solid var(--border); border-radius:6px; padding:10px; max-height:160px; overflow-y:auto; font-family:monospace; font-size:12px;">
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
                            <button type="button" class="primary" onclick="window.closeCurrentModal(); window.viewBanDetails(${r.linkedBanId});" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('ban', 'white', 13)} ПРОСМОТРЕТЬ БАН #${r.linkedBanId}</button>
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


    // Modal: Appeal Templates & Verdicts Generator
    let cachedAppealTemplates = null;

    window.openAppealTemplateModal = async function(targetBanId = null) {
        openModal(`
            <div class="modal-header">
                <div style="display:flex; align-items:center; gap:10px;">
                    <span style="display:inline-flex; align-items:center;">${renderSvgIcon('fileText', 'violet', 20)}</span>
                    <div>
                        <h3 style="margin:0;">ШАБЛОНЫ АПЕЛЛЯЦИЙ И ВЕРДИКТОВ</h3>
                        <div style="font-size:12px; color:var(--text-dim);" id="appeal-modal-subtitle">
                            Генератор формы для игрока и заготовки официальных решений администрации
                        </div>
                    </div>
                </div>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon('close', 'gray', 16)}</button>
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
                            ? `Блокировка <b>#${currentBan.id}</b> игрока <b style="color:var(--text-heading);">${esc(currentBan.targetName)}</b> • Причина: <b>${esc(currentBan.ruleReason)}</b>`
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
                                    <div style="font-size:13.5px; font-weight:800; color:var(--text-heading);">
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
                                <span style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('fileText', 'violet', 14)} ФОРМА ДЛЯ ИГРОКА</span>
                            </button>
                            <button type="button" class="appeal-tab-btn ${currentTab === 'verdicts' ? 'active' : ''}" id="tab-btn-verdicts">
                                <span style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('scales', 'violet', 14)} ВЕРДИКТЫ АДМИНИСТРАЦИИ (${(tmpl.verdicts || []).length})</span>
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
                                <button type="button" class="primary btn-sm" id="btn-copy-form-text" style="display:inline-flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon('copy', 'white', 13)} СКОПИРОВАТЬ ФОРМУ
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
                                    let icon = renderSvgIcon('scales', 'violet', 13);
                                    if (v.type === 'APPROVE') icon = renderSvgIcon('check', 'green', 13);
                                    else if (v.type === 'REJECT') icon = renderSvgIcon('ban', 'red', 13);
                                    else if (v.type === 'INFO') icon = renderSvgIcon('fileText', 'gray', 13);
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
                                        <button type="button" class="danger btn-sm" id="btn-unban-and-copy" title="Разбанить игрока на сервере и скопировать готовый вердикт" style="display:inline-flex; align-items:center; gap:6px;">
                                            ${renderSvgIcon('shield', 'white', 13)} СНЯТЬ БАН И СКОПИРОВАТЬ
                                        </button>
                                    ` : ''}
                                    <button type="button" class="primary btn-sm" id="btn-copy-verdict-text" style="display:inline-flex; align-items:center; gap:6px;">
                                        ${renderSvgIcon('copy', 'white', 13)} СКОПИРОВАТЬ ВЕРДИКТ
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
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon('close', 'gray', 16)}</button>
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
                    <div style="background:var(--card-inner-bg); padding:12px 16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                        <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase;">Основной IP адрес игрока:</div>
                        <div class="font-mono" style="font-size:14px; font-weight:700; color:var(--accent-light); margin-top:2px;">${esc(ip)}</div>
                    </div>

                    <h4 style="font-size:13px; margin-bottom:8px; color:var(--text-main);">Точные совпадения по IP (${direct.length}):</h4>
                    <div style="margin-bottom:16px;">
                        ${direct.length ? direct.map(name => `
                            <div style="display:flex; align-items:center; justify-content:space-between; padding:6px 0; border-bottom:1px solid var(--border);">
                                <div style="display:flex; align-items:center; gap:8px;">
                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(name)}/20" class="player-avatar-sm" alt="">
                                    <b style="color:var(--text-main);">${esc(name)}</b>
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
        let activeTab = 'stream'; // stream, suspects, models

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>АНТИЧИТ</h2>
                    <p>Прямая телеметрия, детекция запрещённых модификаций, поведенческие эвристики и ML-модели</p>
                </div>
            </div>

            <!-- Live Beacon Status Bar with embedded Pause Button inside the stream -->
            <div class="radar-stream-header">
                <div class="live-beacon">
                    <span class="live-beacon-dot" id="radar-beacon-dot"></span>
                    <span id="radar-beacon-text">ЖИВОЙ ПОТОК СРАБАТЫВАНИЙ (LIVE 3S)</span>
                </div>
                <div style="display:flex; align-items:center; gap:10px;">
                    <button type="button" class="secondary btn-sm" id="vesuvio-pause-btn">⏸ ПАУЗА ПОТОКА</button>
                    <div style="font-size:12px; color:var(--text-muted);">
                        Эвристики: <span class="badge green">АКТИВНЫ</span>
                    </div>
                </div>
            </div>

            <!-- Sub-Tabs: Stream / Suspects / Models -->
            <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:12px 16px; margin-bottom:16px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px;">
                <div class="filter-tags" id="anticheat-tabs">
                    <button type="button" class="filter-tag-btn active" data-tab="stream">Поток нарушений</button>
                    <button type="button" class="filter-tag-btn" data-tab="suspects">Разбивка по игрокам / Наблюдение</button>
                    <button type="button" class="filter-tag-btn" data-tab="models">Модели и типы проверок</button>
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
                <!-- Stream / Suspects / Models rendered here -->
            </div>
        `;

        const pauseBtn = document.getElementById('vesuvio-pause-btn');
        pauseBtn?.addEventListener('click', () => {
            isStreamPaused = !isStreamPaused;
            pauseBtn.textContent = isStreamPaused ? '▶ ВОЗОБНОВИТЬ ПОТОК' : '⏸ ПАУЗА ПОТОКА';
            const dot = document.getElementById('radar-beacon-dot');
            const txt = document.getElementById('radar-beacon-text');
            if (dot) dot.style.animationPlayState = isStreamPaused ? 'paused' : 'running';
            if (txt) txt.textContent = isStreamPaused ? 'ПОТОК ПРИОСТАНОВЛЕН (ПАУЗА)' : 'ЖИВОЙ ПОТОК СРАБАТЫВАНИЙ (LIVE 3S)';
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

        const renderAnticheatTab = async () => {
            const body = document.getElementById('anticheat-content-body');
            if (!body) return;

            if (activeTab === 'stream') {
                await renderStream(body);
            } else if (activeTab === 'suspects') {
                await renderSuspects(body);
            } else if (activeTab === 'models') {
                await renderCheckModels(body);
            }
        };

        const renderStream = async (container) => {
            try {
                const res = await api('GET', `/api/vesuvio/violations?player=${encodeURIComponent(playerName)}&limit=50`).catch(() => api('GET', '/api/vesuvio/violations?limit=50'));
                const list = Array.isArray(res) ? res : (res.violations || []);

                let filtered = list;
                if (currentCheckFilter) {
                    filtered = filtered.filter(v => (v.check || v.type || '').toLowerCase().includes(currentCheckFilter.toLowerCase()));
                }

                if (!filtered.length) {
                    container.innerHTML = `<div style="text-align:center; padding:36px; color:var(--text-dim); background:var(--card-bg); border-radius:var(--radius-md); border:1px solid var(--border);">Новых нарушений пока нет. Сервер под защитой античита.</div>`;
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
                                <button type="button" class="secondary btn-sm" onclick="window.openObservationView('${esc(name)}', '${esc(v.playerUuid || v.uuid || '')}')">НАБЛЮДЕНИЕ</button>
                                <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(name)}', 'Читы')">ЗАБАНИТЬ</button>
                                <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(name)}')">ДОСЬЕ</button>
                            </div>
                        </div>`;
                }).join('');
            } catch (e) {
                container.innerHTML = `<div style="color:var(--red); padding:24px; text-align:center;">Модуль античита временно недоступен</div>`;
            }
        };

        const renderSuspects = async (container) => {
            container.innerHTML = `<div style="text-align:center; padding:20px; color:var(--text-muted);">Загрузка аналитики нарушителей...</div>`;
            try {
                const [suspectsList, rawViolations] = await Promise.all([
                    api('GET', '/api/vesuvio/suspects').catch(() => []),
                    api('GET', '/api/vesuvio/violations?limit=100').catch(() => [])
                ]);

                const violations = Array.isArray(rawViolations) ? rawViolations : (rawViolations.violations || []);

                // Aggregate per-player violation stats
                const playerStats = {};
                violations.forEach(v => {
                    const p = v.playerName || v.name || 'Неизвестный';
                    if (!playerStats[p]) {
                        playerStats[p] = { count: 0, maxVl: 0, checks: new Set(), lastTime: 0 };
                    }
                    playerStats[p].count++;
                    playerStats[p].maxVl = Math.max(playerStats[p].maxVl, v.vl || 1);
                    playerStats[p].checks.add(v.check || v.type || 'General');
                    playerStats[p].lastTime = Math.max(playerStats[p].lastTime, v.timestamp || 0);
                });

                const mergedList = [...suspectsList];
                Object.keys(playerStats).forEach(pName => {
                    if (!mergedList.find(s => (s.name || '').toLowerCase() === pName.toLowerCase())) {
                        mergedList.push({
                            name: pName,
                            vl: playerStats[pName].maxVl,
                            isAutomated: true,
                            checks: Array.from(playerStats[pName].checks)
                        });
                    }
                });

                if (!mergedList.length) {
                    container.innerHTML = `
                        <div style="text-align:center; padding:36px; color:var(--text-dim); background:var(--card-bg); border-radius:var(--radius-md); border:1px solid var(--border);">
                            <div style="margin-bottom:8px; display:flex; justify-content:center;">${renderSvgIcon('check', 'green', 28)}</div>
                            <h3>Подозреваемых игроков нет</h3>
                            <p style="font-size:12.5px; color:var(--text-muted); margin-top:4px;">Добавляйте игроков в ручное наблюдение из потока нарушений или досье</p>
                        </div>`;
                    return;
                }

                container.innerHTML = `
                    <div class="table-wrap">
                        <table>
                            <thead>
                                <tr>
                                    <th>ИГРОК</th>
                                    <th>СТАТУС ПРОВЕРКИ</th>
                                    <th>ФЛАГИ & ТИПЫ НАРУШЕНИЙ</th>
                                    <th>СКОР (MAX VL)</th>
                                    <th style="text-align:right;">ДЕЙСТВИЯ</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${mergedList.map(s => {
                                    const st = playerStats[s.name] || { count: 0, checks: new Set() };
                                    const checksArr = s.checks || Array.from(st.checks);
                                    return `
                                    <tr>
                                        <td>
                                            <div style="display:flex; align-items:center; gap:8px;">
                                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(s.name || '')}/24" class="player-avatar-sm" alt="">
                                                <b style="color:var(--text-heading);">${esc(s.name)}</b>
                                            </div>
                                        </td>
                                        <td>
                                            <span class="badge ${s.isAutomated ? 'red' : 'yellow'}">
                                                ${s.isAutomated ? 'ТЕЛЕМЕТРИЯ FLAGGED' : 'РУЧНОЕ НАБЛЮДЕНИЕ'}
                                            </span>
                                        </td>
                                        <td>
                                            ${checksArr.length ? checksArr.slice(0, 3).map(c => `<span class="badge gray" style="margin-right:4px;">${esc(c)}</span>`).join('') : '<span style="color:var(--text-dim);">—</span>'}
                                        </td>
                                        <td class="font-mono"><b style="color:var(--yellow);">${s.vl || st.maxVl || 1}</b></td>
                                        <td style="text-align:right;">
                                            <div style="display:flex; justify-content:flex-end; gap:6px;">
                                                <button type="button" class="secondary btn-sm" onclick="window.viewPlayerProfile('${esc(s.name)}')">ДОСЬЕ</button>
                                                <button type="button" class="danger btn-sm" onclick="window.openQuickBanModal('${esc(s.name)}', 'Читы')">БАН</button>
                                                <button type="button" class="secondary btn-sm" onclick="window.openObservationView('${esc(s.name)}', '${esc(s.uuid || '')}')">
                                                    НАБЛЮДЕНИЕ
                                                </button>
                                            </div>
                                        </td>
                                    </tr>
                                `;
                                }).join('')}
                            </tbody>
                        </table>
                    </div>`;
            } catch (e) {
                container.innerHTML = `<div style="color:var(--red); padding:20px;">Ошибка загрузки списка нарушителей</div>`;
            }
        };

        const renderCheckModels = async (container) => {
            const categories = [
                {
                    title: 'Movement Heuristics (Передвижение)',
                    badge: '9 МОДЕЛЕЙ',
                    color: 'blue',
                    checks: [
                        { name: 'Fly / AirWalk', desc: 'Детекция левитации, нулевой гравитации и модификаций падения', sens: '99.4%' },
                        { name: 'Speed / Omnisprint', desc: 'Проверка скоростей перемещения, трения блоков и стрейфов', sens: '98.8%' },
                        { name: 'NoFall / GroundSpoof', desc: 'Валидация пакетов падения и математики высоты', sens: '99.9%' },
                        { name: 'Jesus / WaterWalk', desc: 'Контроль перемещения по воде и лаве', sens: '97.5%' }
                    ]
                },
                {
                    title: 'Combat ML Models (Боевые механики)',
                    badge: '12 МОДЕЛЕЙ',
                    color: 'red',
                    checks: [
                        { name: 'Killaura / AutoClicker', desc: 'Анализ распределения кликов CPS, угловых дельт и RayTrace', sens: '99.1%' },
                        { name: 'Reach / HitBox Expander', desc: 'Математическая дистанция атаки с учётом пинга и хитбоксов', sens: '99.8%' },
                        { name: 'AimAssist / SmoothAim', desc: 'Эвристика сглаживания прицеливания и машинное обучение углов', sens: '96.4%' },
                        { name: 'Criticals / MiniJump', desc: 'Контроль мини-прыжков и пакетов критических ударов', sens: '99.5%' }
                    ]
                },
                {
                    title: 'World & Packet Integrity (Мир и Сеть)',
                    badge: '7 МОДЕЛЕЙ',
                    color: 'purple',
                    checks: [
                        { name: 'Scaffold / FastPlace', desc: 'Установка блоков под себя на высокой скорости с валидацией углов', sens: '99.0%' },
                        { name: 'Timer / Tick Modulation', desc: 'Детекция ускорения игрового тикрейта на клиенте', sens: '99.9%' },
                        { name: 'FastBreak / PacketMine', desc: 'Проверка времени разрушения блоков с учётом чар и эффектов', sens: '99.2%' }
                    ]
                }
            ];

            container.innerHTML = `
                <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(320px, 1fr)); gap:16px;">
                    ${categories.map(cat => `
                        <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:16px;">
                            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
                                <h3 style="font-size:14px; font-weight:700; color:var(--text-main); margin:0;">${esc(cat.title)}</h3>
                                <span class="badge ${cat.color}">${cat.badge}</span>
                            </div>
                            <div style="display:flex; flex-direction:column; gap:8px;">
                                ${cat.checks.map(c => `
                                    <div style="background:var(--card-inner-bg); padding:10px 12px; border-radius:6px; border:1px solid var(--border); display:flex; justify-content:space-between; align-items:center;">
                                        <div>
                                            <div style="font-weight:700; color:var(--text-main); font-size:13px;">${esc(c.name)}</div>
                                            <div style="font-size:11px; color:var(--text-muted); margin-top:2px;">${esc(c.desc)}</div>
                                        </div>
                                        <div style="text-align:right;">
                                            <span class="badge green" style="font-size:10px;">АКТИВЕН</span>
                                            <div style="font-size:10.5px; color:var(--text-dim); margin-top:3px;">Точность: ${c.sens}</div>
                                        </div>
                                    </div>
                                `).join('')}
                            </div>
                        </div>
                    `).join('')}
                </div>
            `;
        };

        await renderAnticheatTab();

        // Stream poller: auto updates without manual refresh
        pollers['vesuvio_live_stream'] = setInterval(() => {
            if (!isStreamPaused && activeTab === 'stream') {
                const body = document.getElementById('anticheat-content-body');
                if (body) renderStream(body);
            }
        }, 3000);
    }

    // Observation Mode (Карточка прямого наблюдения за подозреваемым)
    window.openObservationView = async function(playerName, uuid = '') {
        const area = document.getElementById('content-area');
        if (!area) return;

        // Clear live stream poller if running
        if (pollers['vesuvio_live_stream']) {
            clearInterval(pollers['vesuvio_live_stream']);
            delete pollers['vesuvio_live_stream'];
        }
        if (pollers['observation']) {
            clearInterval(pollers['observation']);
            delete pollers['observation'];
        }

        let playerUuid = uuid;
        let isSuspect = true;
        let playerDossier = null;

        area.innerHTML = `
            <div class="observation-view-layout">
                <div style="margin-bottom:8px;">
                    <button type="button" class="secondary btn-sm" id="btn-close-observation" style="display:inline-flex; align-items:center; gap:6px;">
                        ${renderSvgIcon('arrowLeft', 'gray', 14)} ← Выйти из наблюдения
                    </button>
                </div>

                <!-- Top Header Card -->
                <div class="observation-header-card">
                    <div style="display:flex; align-items:center; gap:14px;">
                        <img src="https://mc-heads.net/avatar/${encodeURIComponent(playerName)}/48" class="player-avatar-sm" style="width:48px; height:48px; border-radius:6px;" alt="">
                        <div>
                            <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
                                <h3 style="font-size:18px; margin:0; color:var(--text-heading);">${esc(playerName)}</h3>
                                <span class="badge red" id="obs-badge-suspect">ПОДОЗРЕНИЕ: ДА</span>
                                <span class="badge yellow" id="obs-badge-vl">MAX VL: ...</span>
                            </div>
                            <div class="font-mono" style="font-size:11.5px; color:var(--text-dim); margin-top:4px;" id="obs-uuid-display">
                                UUID: ${esc(playerUuid || 'Поиск...')}
                            </div>
                        </div>
                    </div>

                    <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                        <button type="button" class="danger" id="btn-obs-ban-draft" style="display:inline-flex; align-items:center; gap:6px;">
                            ${renderSvgIcon('shieldAlert', 'white', 14)} Черновик бана
                        </button>
                        <button type="button" class="secondary" id="btn-obs-toggle-suspect" style="display:inline-flex; align-items:center; gap:6px;">
                            Снять наблюдение
                        </button>
                    </div>
                </div>

                <!-- 2 Columns Grid -->
                <div class="observation-grid">
                    <!-- Left: Stream of Flags -->
                    <div class="observation-stream-panel">
                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; border-bottom:1px solid var(--border); padding-bottom:10px;">
                            <div style="display:flex; align-items:center; gap:8px;">
                                <span class="live-beacon-dot" style="width:8px; height:8px; background:var(--red); border-radius:50%; box-shadow:0 0 8px var(--red);"></span>
                                <h4 style="margin:0; font-size:13.5px; color:var(--text-heading);">ПОТОК СРАБАТЫВАНИЙ ИГРОКА (LIVE 3S)</h4>
                            </div>
                            <span id="obs-flags-count" class="badge gray">0 флагов</span>
                        </div>
                        <div id="obs-violations-stream" style="display:flex; flex-direction:column; gap:8px; max-height:550px; overflow-y:auto; padding-right:4px;">
                            <div style="text-align:center; padding:30px; color:var(--text-muted);">Загрузка нарушений...</div>
                        </div>
                    </div>

                    <!-- Right: Dossier Info -->
                    <div class="observation-dossier-panel">
                        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px; border-bottom:1px solid var(--border); padding-bottom:10px;">
                            <h4 style="margin:0; font-size:13.5px; color:var(--text-heading);">ДОСЬЕ И АНАЛИТИКА</h4>
                            <button type="button" class="btn-tag-link" id="btn-obs-full-profile" style="font-size:11.5px;">Открыть полное досье →</button>
                        </div>
                        <div id="obs-dossier-body">
                            <div style="text-align:center; padding:30px; color:var(--text-muted);">Загрузка досье игрока...</div>
                        </div>
                    </div>
                </div>
            </div>
        `;

        document.getElementById('btn-close-observation')?.addEventListener('click', closeObservationView);
        document.getElementById('btn-obs-ban-draft')?.addEventListener('click', () => {
            window.openQuickBanModal(playerName, 'Читы');
        });
        document.getElementById('btn-obs-full-profile')?.addEventListener('click', () => {
            window.viewPlayerProfile(playerName);
        });

        const toggleSuspectBtn = document.getElementById('btn-obs-toggle-suspect');
        toggleSuspectBtn?.addEventListener('click', async () => {
            if (!playerUuid) return;
            isSuspect = !isSuspect;
            try {
                await api('POST', `/api/vesuvio/player/${encodeURIComponent(playerUuid)}/suspect`, { suspect: isSuspect });
                toggleSuspectBtn.textContent = isSuspect ? 'Снять наблюдение' : 'Поставить на наблюдение';
                const sBadge = document.getElementById('obs-badge-suspect');
                if (sBadge) {
                    sBadge.className = isSuspect ? 'badge red' : 'badge gray';
                    sBadge.textContent = isSuspect ? 'ПОДОЗРЕНИЕ: ДА' : 'ПОДОЗРЕНИЕ: НЕТ';
                }
                showToast('Наблюдение', isSuspect ? 'Игрок добавлен в список наблюдения' : 'С игрока снято наблюдение', 'info');
            } catch (e) {
                alert('Ошибка: ' + e.message);
            }
        });

        // Ensure suspect state and fetch dossier
        try {
            const p = await api('GET', `/api/players/${encodeURIComponent(playerName)}`);
            playerDossier = p;
            if (p.uuid) {
                playerUuid = p.uuid;
                const uuidEl = document.getElementById('obs-uuid-display');
                if (uuidEl) uuidEl.textContent = 'UUID: ' + playerUuid;
                // auto-suspect
                await api('POST', `/api/vesuvio/player/${encodeURIComponent(playerUuid)}/suspect`, { suspect: true }).catch(() => {});
            }
            renderObsDossier(playerDossier);
        } catch (e) {
            const db = document.getElementById('obs-dossier-body');
            if (db) db.innerHTML = `<div style="color:var(--text-dim); padding:16px;">Досье недоступно: ${esc(e.message)}</div>`;
        }

        // Render Dossier helper
        function renderObsDossier(data) {
            const db = document.getElementById('obs-dossier-body');
            if (!db) return;
            if (!data) {
                db.innerHTML = `<div style="color:var(--text-dim); padding:16px;">Данные отсутствуют</div>`;
                return;
            }

            const alts = Array.isArray(data.alts) ? data.alts : [];
            const kicks = data.kicksCount || 0;
            const bans = data.bansCount || 0;
            const notes = data.notes || data.staffNotes || 'Заметок персонала нет';

            db.innerHTML = `
                <div style="display:flex; flex-direction:column; gap:12px; font-size:12.5px;">
                    <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); padding:12px; border-radius:6px; border:1px solid var(--border);">
                        <div style="display:flex; justify-content:space-between; margin-bottom:6px;">
                            <span style="color:var(--text-muted);">IP адрес:</span>
                            <b class="font-mono" style="color:var(--accent-light);">${esc(data.ip || data.lastIp || '—')}</b>
                        </div>
                        <div style="display:flex; justify-content:space-between; margin-bottom:6px;">
                            <span style="color:var(--text-muted);">Клиент:</span>
                            <b>${esc(data.clientBrand || data.client || 'Vanilla')}</b>
                        </div>
                        <div style="display:flex; justify-content:space-between; margin-bottom:6px;">
                            <span style="color:var(--text-muted);">Первый вход:</span>
                            <span style="color:var(--text-dim);">${fmtTime(data.firstJoin || data.registeredAt || 0)}</span>
                        </div>
                        <div style="display:flex; justify-content:space-between;">
                            <span style="color:var(--text-muted);">Последний вход:</span>
                            <span style="color:var(--text-dim);">${fmtTime(data.lastJoin || data.lastSeen || 0)}</span>
                        </div>
                    </div>

                    <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); padding:12px; border-radius:6px; border:1px solid var(--border);">
                        <div style="color:var(--text-muted); font-size:11.5px; text-transform:uppercase; font-weight:700; margin-bottom:6px;">Связанные твинки (альты)</div>
                        <div style="display:flex; gap:6px; flex-wrap:wrap;">
                            ${alts.length ? alts.map(a => `
                                <span class="badge gray" style="font-size:11px; cursor:pointer;" onclick="window.openObservationView('${esc(a)}')">${esc(a)}</span>
                            `).join('') : '<span style="color:var(--text-dim);">Других аккаунтов по IP не зафиксировано</span>'}
                        </div>
                    </div>

                    <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px;">
                        <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); padding:10px 12px; border-radius:6px; border:1px solid var(--border);">
                            <div style="font-size:11px; color:var(--text-muted);">КИКОВ СЕРВЕРА</div>
                            <div style="font-size:16px; font-weight:700; color:var(--yellow); margin-top:2px;">${kicks}</div>
                        </div>
                        <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); padding:10px 12px; border-radius:6px; border:1px solid var(--border);">
                            <div style="font-size:11px; color:var(--text-muted);">БАНОВ СЕРВЕРА</div>
                            <div style="font-size:16px; font-weight:700; color:${bans > 0 ? 'var(--red)' : 'var(--green)'}; margin-top:2px;">${bans}</div>
                        </div>
                    </div>

                    <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); padding:12px; border-radius:6px; border:1px solid var(--border);">
                        <div style="color:var(--text-muted); font-size:11.5px; text-transform:uppercase; font-weight:700; margin-bottom:4px;">Заметки модераторов</div>
                        <div style="font-size:12px; color:var(--text-dim); line-height:1.4;">${esc(notes)}</div>
                    </div>
                </div>
            `;
        }

        // Polling Violations filtered by player
        async function fetchPlayerViolations() {
            try {
                const res = await api('GET', '/api/vesuvio/violations?limit=50');
                const list = Array.isArray(res) ? res : (res.violations || []);
                const playerViolations = list.filter(v => {
                    const vName = (v.playerName || v.name || '').toLowerCase();
                    const vUuid = (v.playerUuid || v.uuid || '').toLowerCase();
                    return vName === playerName.toLowerCase() || (playerUuid && vUuid === playerUuid.toLowerCase());
                });

                const streamEl = document.getElementById('obs-violations-stream');
                const countEl = document.getElementById('obs-flags-count');
                const vlEl = document.getElementById('obs-badge-vl');
                if (!streamEl) return;

                if (countEl) countEl.textContent = playerViolations.length + ' флагов';

                let maxVl = 0;
                playerViolations.forEach(v => {
                    const vl = v.vl || v.vlScore || 1;
                    if (vl > maxVl) maxVl = vl;
                });
                if (vlEl) vlEl.textContent = 'MAX VL: ' + (maxVl || 0);

                if (!playerViolations.length) {
                    streamEl.innerHTML = `
                        <div style="text-align:center; padding:30px; color:var(--text-dim); background:var(--card-inner-bg, rgba(255,255,255,0.02)); border-radius:6px; border:1px dashed var(--border);">
                            Нет активных флагов античита за последнее время
                        </div>
                    `;
                    return;
                }

                streamEl.innerHTML = playerViolations.map(v => {
                    const check = v.check || v.type || 'Movement';
                    const vl = v.vl || v.vlScore || 1;
                    return `
                        <div style="background:var(--card-inner-bg, rgba(255,255,255,0.03)); border:1px solid var(--border); border-radius:6px; padding:10px 12px; display:flex; justify-content:space-between; align-items:center;">
                            <div>
                                <div style="display:flex; align-items:center; gap:6px;">
                                    <span class="badge red" style="font-size:11px;">${esc(check)}</span>
                                    <span class="font-mono" style="font-size:11px; color:var(--text-dim);">${fmtTime(v.timestamp || Date.now() / 1000)}</span>
                                </div>
                                <div style="font-size:11.5px; color:var(--text-muted); margin-top:3px;">
                                    ${esc(v.details || v.description || 'Эвристическое отклонение пакетов')}
                                </div>
                            </div>
                            <div style="text-align:right;">
                                <div style="font-size:11px; color:var(--text-dim);">VL</div>
                                <div class="font-mono" style="font-size:14px; font-weight:700; color:var(--yellow);">+${vl}</div>
                            </div>
                        </div>
                    `;
                }).join('');
            } catch (_) {}
        }

        await fetchPlayerViolations();
        pollers['observation'] = setInterval(fetchPlayerViolations, 3000);
    };

    window.closeObservationView = function() {
        if (pollers['observation']) {
            clearInterval(pollers['observation']);
            delete pollers['observation'];
        }
        renderAnticheatView();
    };


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

        // Server auto-refresh
        pollers['server_auto_refresh'] = setInterval(() => {
            if (subTab === 'players') {
                const srvSearch = document.getElementById('srv-player-search');
                if (srvSearch && !srvSearch.value.trim()) mountSubTab();
            }
        }, 8000);

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
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon('close', 'gray', 16)}</button>
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
                    <div style="display:flex; align-items:center; justify-content:space-between; background:var(--card-inner-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:16px; margin-bottom:18px;">
                        <div style="display:flex; align-items:center; gap:14px;">
                            <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/48" style="width:48px; height:48px; border-radius:6px; background:var(--bg-surface); border:1px solid var(--border-light);" alt="">
                            <div>
                                <h3 style="font-size:18px; margin-bottom:2px; color:var(--text-main);">${esc(p.name)}</h3>
                                <div style="font-size:11.5px; color:var(--text-muted); font-family:'JetBrains Mono';">${esc(p.uuid)}</div>
                                <div style="margin-top:4px;">
                                    <span class="badge ${p.isOnline ? 'green' : 'gray'}">${p.isOnline ? `● В СЕТИ (${p.ping} мс)` : 'ОФФЛАЙН'}</span>
                                    <span class="badge purple">IP: ${esc(p.ip || '—')}</span>
                                </div>
                            </div>
                        </div>

                        <!-- Trust Score Pill -->
                        <div style="text-align:right; background:var(--card-bg); padding:10px 14px; border-radius:8px; border:1px solid var(--border);">
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
                                <div class="font-mono" style="font-size:13px; color:var(--text);">${fmtTime(p.firstJoinedAt)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Последняя активность</div>
                                <div class="font-mono" style="font-size:13px; color:var(--text);">${fmtTime(p.lastSeenAt)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Общее время в игре</div>
                                <div style="font-size:16px; font-weight:700; color:var(--accent-light);">${fmtDuration(p.playtimeSeconds || 0)}</div>
                            </div>
                            <div class="stat-card" style="padding:12px;">
                                <div class="stat-label">Баланс монет (Экономика)</div>
                                <div style="font-size:16px; font-weight:700; color:var(--text-main);">${(p.economy && p.economy.balance) ? p.economy.balance.toLocaleString() : 0} монет</div>
                            </div>
                        </div>

                        <div style="display:flex; justify-content:space-between; align-items:center; background:var(--card-inner-bg); padding:12px; border-radius:6px; border:1px solid var(--border);">
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

                    const isHigherRole = me && (me.isOwner || (me.role && (me.role.toLowerCase().includes('управляющий') || me.role.toLowerCase().includes('owner'))));

                    dossierContainer.innerHTML = `
                        <div style="display:flex; gap:12px; margin-bottom:16px; flex-wrap:wrap;">
                            <button type="button" class="quick-action-btn" id="act-kick" style="min-width:160px; flex:1; padding:18px;">
                                <span class="qa-icon" style="margin-bottom:6px;">${renderSvgIcon('userMinus', 'red', 24)}</span>
                                <span style="font-weight:700;">КИКНУТЬ ИГРОКА</span>
                            </button>
                            ${isHigherRole ? `
                            <button type="button" class="quick-action-btn" id="act-clear-inv" style="min-width:160px; flex:1; padding:18px;">
                                <span class="qa-icon" style="margin-bottom:6px;">${renderSvgIcon('trash', 'yellow', 24)}</span>
                                <span style="font-weight:700;">ОЧИСТИТЬ ИНВЕНТАРЬ</span>
                            </button>
                            ` : ''}
                        </div>
                    `;

                    const execPlayerAction = async (action, bodyObj = {}) => {
                        try {
                            const res = await api('POST', `/api/players/${encodeURIComponent(p.name)}/action`, { action, ...bodyObj });
                            showToast('Действие выполнено', res.message || 'Успешно', 'success');
                        } catch (e) {
                            if (e.message && e.message.includes('не в сети')) {
                                showToast('Игрок оффлайн', `Игрок ${p.name} не в сети или вышел с сервера`, 'warning');
                            } else {
                                showToast('Ошибка действия', e.message || 'Не удалось выполнить действие', 'danger');
                            }
                        }
                    };

                    document.getElementById('act-kick')?.addEventListener('click', () => {
                        const reason = prompt(`Причина кика игрока ${p.name}:`, 'Кикнут администратором через веб-панель');
                        if (reason !== null && reason.trim()) execPlayerAction('kick', { reason: reason.trim() });
                    });

                    if (isHigherRole) {
                        document.getElementById('act-clear-inv')?.addEventListener('click', () => {
                            confirmAction('ОЧИСТКА ИНВЕНТАРЯ', `Вы действительно хотите безвозвратно очистить инвентарь игрока ${p.name}?`, () => execPlayerAction('clear_inventory'), 'ОЧИСТИТЬ', true);
                        });
                    }
                };

                const renderNotes = () => {
                    dossierContainer.innerHTML = `
                        <div style="margin-bottom:14px; background:var(--card-inner-bg); padding:12px; border-radius:6px; border:1px solid var(--border);">
                            <label>Добавить внутреннюю заметку стаффа:</label>
                            <div style="display:flex; gap:8px;">
                                <input type="text" id="new-note-text" placeholder="Заметка о поведении игрока (видна только стаффу)...">
                                <button type="button" class="primary btn-sm" id="btn-add-note">ДОБАВИТЬ</button>
                            </div>
                        </div>

                        <div id="notes-list-box" style="max-height:220px; overflow-y:auto;">
                            ${notes.length ? notes.map(n => `
                                <div style="background:var(--card-inner-bg); padding:10px 12px; border-radius:6px; border:1px solid var(--border); margin-bottom:8px; display:flex; justify-content:space-between; align-items:flex-start;">
                                    <div>
                                        <div style="font-size:11px; color:var(--text-dim); margin-bottom:3px;">
                                            Автор: <b style="color:var(--accent-light);">${esc(n.author)}</b> • ${fmtTime(n.createdAt)}
                                        </div>
                                        <div style="color:var(--text-main); font-size:13px;">${esc(n.note)}</div>
                                    </div>
                                    <button type="button" class="secondary btn-sm" onclick="window.deleteStaffNote('${esc(p.name)}', ${n.id})" style="color:var(--red); padding:4px 8px; display:inline-flex; align-items:center;">${renderSvgIcon('trash', 'red', 13)}</button>
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
                                <div style="background:var(--card-inner-bg); padding:10px 12px; border-radius:6px; border:1px solid var(--border); margin-bottom:8px;">
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
                    <span style="font-family:'Outfit'; font-weight:700; color:var(--text-heading); font-size:13px;">КОНСОЛЬ СЕРВЕРА MINECRAFT</span>
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
                        <h3 style="font-size:15px; color:var(--text-heading);">БЕЛЫЙ СПИСОК (WHITELIST)</h3>
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
                        <h3 style="font-size:15px; color:var(--text-heading);">ОПЕРАТОРЫ СЕРВЕРА (OPs)</h3>
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
                                <td style="font-weight:700; color:var(--text-heading);">${esc(p.name)}</td>
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
                                    <b style="color:var(--text-heading);">${esc(o.name)}</b>
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
            showToast('Whitelist', `Игрок LoveWebAdmin добавлен в whitelist`, 'success');
            refreshWhitelist();
        });

        document.getElementById('ops-add-btn')?.addEventListener('click', () => {
            const name = document.getElementById('ops-add-name').value.trim();
            if (!name) return;
            confirmAction('ВЫДАЧА ПРАВ OP', `Выдать полные права оператора игроку LoveWebAdmin? Операторы имеют доступ ко всем командам ядра!`, async () => {
                await api('POST', '/api/server/ops', { action: 'add', player: name });
                document.getElementById('ops-add-name').value = '';
                showToast('Операторы', `Права OP выданы игроку LoveWebAdmin`, 'warning');
                refreshOps();
            }, 'ВЫДАТЬ OP');
        });

        window.removeWhitelistPlayer = async (name) => {
            await api('POST', '/api/server/whitelist', { action: 'remove', player: name });
            showToast('Whitelist', `Игрок LoveWebAdmin удален из whitelist`, 'info');
            refreshWhitelist();
        };

        window.removeOpPlayer = (name) => {
            confirmAction('СНЯТИЕ OP', `Снять права оператора с игрока LoveWebAdmin?`, async () => {
                await api('POST', '/api/server/ops', { action: 'remove', player: name });
                showToast('Операторы', `Права OP сняты с LoveWebAdmin`, 'info');
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
                    <p>Управление сессиями персонала и игровыми аккаунтами авторизации</p>
                </div>
                <div class="view-actions" id="auth-header-actions">
                    <!-- Dynamic actions per subtab -->
                </div>
            </div>

            <!-- Subtabs Navigation -->
            <div class="sub-tabs-bar" id="auth-subtabs">
                <button type="button" class="sub-tab-btn active" data-subtab="web" style="display:inline-flex; align-items:center; gap:6px;">
                    ${renderSvgIcon('server', 'violet', 15)} ВЕБ-ПАНЕЛЬ (СЕССИИ СТАФФА)
                </button>
                <button type="button" class="sub-tab-btn" data-subtab="loveauth" style="display:inline-flex; align-items:center; gap:6px;">
                    ${renderSvgIcon('users', 'violet', 15)} ИГРОВОЙ СЕРВЕР (АВТОРИЗАЦИЯ)
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
                    `;
                    document.getElementById('btn-terminate-all-other')?.addEventListener('click', () => {
                        confirmAction('ЗАВЕРШЕНИЕ СЕССИЙ', 'Завершить все активные сессии на всех других устройствах?', async () => {
                            await api('POST', '/api/me/sessions/other/terminate');
                            showToast('Готово', 'Все остальные сессии были завершены', 'info');
                            refreshWebAuthData();
                        }, 'ЗАВЕРШИТЬ ВСЕ');
                    });
                }

                container.innerHTML = `
                    <!-- Active Sessions Card -->
                    <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:18px; margin-bottom:20px;">
                        <h3 style="font-size:15px; margin-bottom:12px; color:var(--text-heading);">АКТИВНЫЕ СЕССИИ ВЕБ-ПАНЕЛИ</h3>
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
                        <h3 style="font-size:15px; margin-bottom:12px; color:var(--text-heading);">ИСТОРИЯ ВХОДОВ И БЕЗОПАСНОСТЬ ПАНЕЛИ</h3>
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
                    actions.innerHTML = '';
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
                                <td><b style="color:var(--text-heading);">${esc(s.username)}</b></td>
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
                                <td style="color:var(--text);">${esc(h.action)}</td>
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
                <!-- Auth Status Banner -->
                ${isAvailable ? `
                    <div class="loveauth-banner">
                        <div style="display:flex; align-items:center; gap:14px;">
                            <span style="display:inline-flex; align-items:center;">${renderSvgIcon('shield', 'violet', 26)}</span>
                            <div>
                                <div style="font-weight:700; color:var(--text-main); font-size:15px; display:flex; align-items:center; gap:8px;">
                                    <span>СИСТЕМА АВТОРИЗАЦИИ</span>
                                    <span class="badge green">● АКТИВНА</span>
                                </div>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                    Управление игровыми сессиями, шифрованием паролей и двухфакторной защитой
                                </div>
                            </div>
                        </div>
                    </div>
                ` : `
                    <div class="loveauth-banner" style="background:rgba(239, 68, 68, 0.08); border-color:rgba(239, 68, 68, 0.25);">
                        <div style="display:flex; align-items:center; gap:14px;">
                            <span style="display:inline-flex; align-items:center;">${renderSvgIcon('shieldAlert', 'red', 26)}</span>
                            <div>
                                <div style="font-weight:700; color:var(--text-main); font-size:15px; display:flex; align-items:center; gap:8px;">
                                    <span>СИСТЕМА АВТОРИЗАЦИИ</span>
                                    <span class="badge red">● НЕДОСТУПНА</span>
                                </div>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                    Модуль игровой авторизации не подключен или отключен на сервере.
                                </div>
                            </div>
                        </div>
                        <span class="badge red">НЕДОСТУПЕН</span>
                    </div>
                `}

                <!-- Account Search & Online Quick-Chips -->
                <div class="loveauth-card" style="margin-bottom:20px;">
                    <h3 style="font-size:14px; margin-bottom:10px; color:var(--text-main);">ИНСПЕКЦИЯ ИГРОВОГО АККАУНТА</h3>
                    <div style="display:flex; gap:10px; margin-bottom:14px;">
                        <input type="text" id="loveauth-search-input" placeholder="Введите точный ник игрока..." style="flex:1;">
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
                        <div style="margin-bottom:8px; display:inline-flex; align-items:center;">${renderSvgIcon('search', 'violet', 28)}</div>
                        <div>Введите никнейм или нажмите на игрока онлайн выше, чтобы загрузить данные авторизации</div>
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
                    Связываемся с базой авторизации...
                </div>
            `;

            try {
                const info = await api('GET', `/api/loveauth/player/${encodeURIComponent(name)}`);

                if (!info || !info.found) {
                    resBox.innerHTML = `
                        <div class="loveauth-card" style="text-align:center; padding:32px;">
                            <div style="margin-bottom:8px; display:inline-flex; align-items:center;">${renderSvgIcon('alert', 'yellow', 24)}</div>
                            <h3 style="color:var(--text-main); margin-bottom:6px;">Игрок «${esc(name)}» не найден в базе авторизации</h3>
                            <p style="color:var(--text-dim); font-size:12.5px;">Возможно, игрок еще ни разу не заходил на сервер или никнейм введен с опечаткой.</p>
                        </div>
                    `;
                    return;
                }

                const isLocked = !!info.isLocked;
                const isIpBlocked = !!info.isIpBlocked;
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
                                        <h3 style="font-size:18px; color:var(--text-main); margin:0;">${esc(info.username)}</h3>
                                        <span class="badge ${isReg ? 'green' : 'yellow'}" style="display:inline-flex; align-items:center; gap:4px;">${isReg ? renderSvgIcon('check', 'green', 11) : ''} ${isReg ? 'ЗАРЕГИСТРИРОВАН' : 'НЕ ЗАРЕГИСТРИРОВАН'}</span>
                                        <span class="badge ${isLocked ? 'red' : 'green'}" style="display:inline-flex; align-items:center; gap:4px;">${isLocked ? renderSvgIcon('lock', 'red', 11) : renderSvgIcon('check', 'green', 11)} ${isLocked ? 'ЗАБЛОКИРОВАН (LOCKOUT)' : 'ДОСТУП РАЗРЕШЕН'}</span>
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
                                <div style="font-size:14px; font-weight:700; margin-top:4px; color:${hasDc ? 'var(--accent-light)' : 'var(--text-dim)'}; display:flex; align-items:center; gap:4px;">
                                    ${hasDc ? renderSvgIcon('check', 'green', 12) + ' Discord 2FA подключен' : 'Без двухфакторной защиты'}
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
                            Действия администратора:
                        </div>

                        <div class="loveauth-actions-grid">
                            <button type="button" class="secondary" onclick="window.loveAuthResetSession('${esc(info.username)}')" style="display:inline-flex; align-items:center; gap:6px;">
                                ${renderSvgIcon('refresh', 'violet', 13)} СБРОСИТЬ СЕССИЮ (КИК)
                            </button>
                            <button type="button" class="secondary" onclick="window.loveAuthChangePassword('${esc(info.username)}')" style="display:inline-flex; align-items:center; gap:6px;">
                                ${renderSvgIcon('key', 'violet', 13)} СМЕНИТЬ ПАРОЛЬ
                            </button>
                            ${isLocked ? `
                                <button type="button" class="primary" onclick="window.loveAuthUnlock('${esc(info.username)}')" style="display:inline-flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon('lock', 'green', 13)} РАЗБЛОКИРОВАТЬ АККАУНТ
                                </button>
                            ` : `
                                <button type="button" class="secondary" disabled style="opacity:0.5; cursor:not-allowed; display:inline-flex; align-items:center; gap:6px;" title="Аккаунт не заблокирован">
                                    ${renderSvgIcon('check', 'gray', 13)} АККАУНТ ДОСТУПЕН
                                </button>
                            `}
                            ${isIpBlocked ? `
                                <button type="button" class="primary" onclick="window.loveAuthUnblockIp('${esc(info.lastIp)}')" style="display:inline-flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon('globe', 'green', 13)} РАЗБЛОКИРОВАТЬ IP (${esc(info.lastIp)})
                                </button>
                            ` : `
                                <button type="button" class="secondary" disabled style="opacity:0.5; cursor:not-allowed; display:inline-flex; align-items:center; gap:6px;" title="IP не заблокирован">
                                    ${renderSvgIcon('check', 'gray', 13)} IP НЕ ЗАБЛОКИРОВАН
                                </button>
                            `}
                            <button type="button" class="danger" onclick="window.loveAuthDelete('${esc(info.username)}')" style="display:inline-flex; align-items:center; gap:6px;">
                                ${renderSvgIcon('trash', 'white', 13)} УДАЛИТЬ АККАУНТ
                            </button>
                        </div>
                    </div>
                `;
            } catch (e) {
                resBox.innerHTML = `
                    <div style="color:var(--red); padding:20px; text-align:center; background:var(--card-bg); border-radius:var(--radius-md); border:1px solid var(--border);">
                        Ошибка запроса к авторизации: ${esc(e.message)}
                    </div>
                `;
            }
        };

        // Auth Operations Handlers
        window.loveAuthResetSession = (name) => {
            confirmAction('СБРОС СЕССИИ', `Завершить сессию игрока LoveWebAdmin на игровом сервере? Он будет немедленно отключен.`, async () => {
                try {
                    await api('POST', `/api/loveauth/player/${encodeURIComponent(name)}/reset-session`);
                    showToast('Сессия сброшена', `Игрок LoveWebAdmin отключен от игрового сервера`, 'info');
                    recordShiftAction(`Авторизация: сбросил сессию игроку LoveWebAdmin`);
                    window.inspectLoveAuthPlayer(name);
                } catch (e) {
                    alert('Ошибка: ' + e.message);
                }
            }, 'СБРОСИТЬ СЕССИЮ');
        };

        window.loveAuthChangePassword = (name) => {
            openModal(`
                <div class="modal-header">
                    <h3>СМЕНА ПАРОЛЯ: ${esc(name)}</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
                </div>
                <div class="modal-body">
                    <div class="form-group">
                        <label>Новый пароль для входа в игру</label>
                        <input type="text" id="loveauth-new-pass" placeholder="Минимум 4 символа..." required autofocus>
                    </div>
                    <div style="font-size:12px; color:var(--text-muted);">
                        Пароль будет захеширован и сохранен в базе данных авторизации.
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
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
                        showToast('Пароль обновлен', `Пароль для LoveWebAdmin успешно изменен`, 'success');
                        recordShiftAction(`Авторизация: изменил пароль LoveWebAdmin`);
                        closeModal();
                        window.inspectLoveAuthPlayer(name);
                    } catch (e) {
                        alert('Ошибка смены пароля: ' + e.message);
                    }
                });
            });
        };

        window.loveAuthUnlock = (name) => {
            confirmAction('РАЗБЛОКИРОВКА АККАУНТА', `Снять блокировку подбора пароля (lockout) с аккаунта LoveWebAdmin?`, async () => {
                try {
                    await api('POST', `/api/loveauth/player/${encodeURIComponent(name)}/unlock`);
                    showToast('Разблокирован', `Аккаунт LoveWebAdmin успешно разблокирован`, 'success');
                    recordShiftAction(`Авторизация: разблокировал аккаунт LoveWebAdmin`);
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
                    recordShiftAction(`Авторизация: разблокировал IP ${ip}`);
                    if (inspectedPlayer) window.inspectLoveAuthPlayer(inspectedPlayer);
                } catch (e) {
                    alert('Ошибка: ' + e.message);
                }
            }, 'РАЗБЛОКИРОВАТЬ IP');
        };

        window.loveAuthDelete = (name) => {
            confirmAction('УДАЛЕНИЕ АККАУНТА', `ВНИМАНИЕ! Вы действительно хотите безвозвратно удалить регистрацию игрока LoveWebAdmin?`, async () => {
                try {
                    await api('POST', `/api/loveauth/player/${encodeURIComponent(name)}/delete`);
                    showToast('Аккаунт удален', `Регистрация LoveWebAdmin удалена из авторизации`, 'warning');
                    recordShiftAction(`Авторизация: удалил аккаунт LoveWebAdmin`);
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
    // 7. ПЕРСОНАЛ И РОЛИ (СОТРУДНИКИ, ПРИГЛАШЕНИЯ, ИСПЫТАТЕЛЬНЫЙ СРОК, РОЛИ)
    // ==========================================================================

    async function renderStaffView() {
        const area = document.getElementById('content-area');
        let sub = 'personnel'; // personnel | roles
        let currentFilter = 'active'; // active | invites | review

        area.innerHTML = `
            <div class="view-header">
                <div class="view-title-block">
                    <h2>ПЕРСОНАЛ</h2>
                    <p>Сотрудники, приглашения и роли веб-панели</p>
                </div>
                <div class="view-actions" id="staff-header-actions">
                    <button type="button" class="primary" id="btn-add-staff">+ ДОБАВИТЬ СОТРУДНИКА</button>
                </div>
            </div>
            <div class="filter-tags" id="staff-sub-tabs" style="margin-bottom:18px;">
                <button type="button" class="filter-tag-btn active" data-sub="personnel">Персонал</button>
                <button type="button" class="filter-tag-btn" data-sub="roles">Роли</button>
            </div>
            <div id="staff-tab-body"></div>
        `;

        let rolesCache = [];
        let adminsCache = [];
        let invitesCache = [];

        const mount = () => {
            const headerActions = document.getElementById('staff-header-actions');
            if (headerActions) {
                if (sub === 'personnel') {
                    headerActions.innerHTML = `<button type="button" class="primary" id="btn-add-staff">+ ДОБАВИТЬ СОТРУДНИКА</button>`;
                    document.getElementById('btn-add-staff')?.addEventListener('click', openInviteWizardModal);
                } else {
                    headerActions.innerHTML = `<button type="button" class="primary" id="btn-create-role">+ СОЗДАТЬ РОЛЬ</button>`;
                    document.getElementById('btn-create-role')?.addEventListener('click', openCreateRoleModal);
                }
            }

            if (sub === 'personnel') {
                renderPersonnelTab();
            } else {
                renderRolesTab();
            }
        };

        document.getElementById('staff-sub-tabs')?.addEventListener('click', (e) => {
            const btn = e.target.closest('[data-sub]');
            if (!btn) return;
            sub = btn.dataset.sub;
            document.querySelectorAll('#staff-sub-tabs .filter-tag-btn')
                .forEach(b => b.classList.toggle('active', b.dataset.sub === sub));
            mount();
        });

        // ---------------- Personnel Tab ----------------
        async function renderPersonnelTab() {
            const container = document.getElementById('staff-tab-body');
            if (!container) return;

            container.innerHTML = `
                <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; margin-bottom:16px;">
                    <div class="filter-tags" id="personnel-filter-tags">
                        <button type="button" class="filter-tag-btn ${currentFilter === 'active' ? 'active' : ''}" data-filter="active">
                            Активные сотрудники (<span id="count-active">0</span>)
                        </button>
                        <button type="button" class="filter-tag-btn ${currentFilter === 'invites' ? 'active' : ''}" data-filter="invites">
                            Приглашения (<span id="count-invites">0</span>)
                        </button>
                        <button type="button" class="filter-tag-btn ${currentFilter === 'review' ? 'active' : ''}" data-filter="review">
                            Требуют решения (<span id="count-review">0</span>)
                        </button>
                    </div>
                    <button type="button" class="secondary btn-sm" id="btn-refresh-staff">
                        ${renderSvgIcon('refresh', 'gray', 13)} Обновить
                    </button>
                </div>
                <div id="personnel-table-container">
                    <div style="text-align:center; padding:30px; color:var(--text-muted);">Загрузка данных...</div>
                </div>
            `;

            document.getElementById('personnel-filter-tags')?.addEventListener('click', (e) => {
                const btn = e.target.closest('[data-filter]');
                if (!btn) return;
                currentFilter = btn.dataset.filter;
                document.querySelectorAll('#personnel-filter-tags .filter-tag-btn')
                    .forEach(b => b.classList.toggle('active', b.dataset.filter === currentFilter));
                renderPersonnelList();
            });

            document.getElementById('btn-refresh-staff')?.addEventListener('click', loadPersonnelData);
            await loadPersonnelData();
        }

        async function loadPersonnelData() {
            try {
                const [admins, roles, invites] = await Promise.all([
                    api('GET', '/api/admins'),
                    api('GET', '/api/roles'),
                    api('GET', '/api/admins/invites').catch(() => [])
                ]);
                adminsCache = admins;
                rolesCache = roles;
                invitesCache = invites;

                const activeCount = admins.filter(a => a.status === 'ACTIVE').length;
                const invitesCount = invites.length;
                const reviewCount = admins.filter(a => {
                    const expiry = a.roleExpiresAt || 0;
                    const expired = expiry > 0 && expiry <= Math.floor(Date.now() / 1000);
                    return a.pendingRoleReview || expired;
                }).length;

                const cAct = document.getElementById('count-active');
                const cInv = document.getElementById('count-invites');
                const cRev = document.getElementById('count-review');
                if (cAct) cAct.textContent = activeCount;
                if (cInv) cInv.textContent = invitesCount;
                if (cRev) cRev.textContent = reviewCount;

                renderPersonnelList();
            } catch (e) {
                const tc = document.getElementById('personnel-table-container');
                if (tc) tc.innerHTML = `<div style="text-align:center; color:var(--red); padding:20px;">Ошибка загрузки: ${esc(e.message)}</div>`;
            }
        }

        function renderPersonnelList() {
            const container = document.getElementById('personnel-table-container');
            if (!container) return;

            if (currentFilter === 'invites') {
                if (!invitesCache.length) {
                    container.innerHTML = `
                        <div style="text-align:center; padding:40px 20px; background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); color:var(--text-muted);">
                            <div style="margin-bottom:8px;">${renderSvgIcon('mail', 'gray', 28)}</div>
                            <div style="font-size:14px; font-weight:600; color:var(--text-main);">Нет активных приглашений</div>
                            <div style="font-size:12px; margin-top:4px;">Нажмите «+ Добавить сотрудника», чтобы создать новый код приглашения</div>
                        </div>
                    `;
                    return;
                }

                container.innerHTML = `
                    <div class="table-wrap" style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md);">
                        <table>
                            <thead>
                                <tr>
                                    <th>СОТРУДНИК</th>
                                    <th>РОЛЬ</th>
                                    <th>КОД ПРИГЛАШЕНИЯ</th>
                                    <th>КЕМ СОЗДАН</th>
                                    <th>ДЕЙСТВИТЕЛЕН ДО</th>
                                    <th style="text-align:right;">ДЕЙСТВИЯ</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${invitesCache.map(inv => {
                                    const code = inv.code || '';
                                    const masked = code.length > 4 ? (code.slice(0, 4) + '••••' + code.slice(-4)) : code;
                                    const roleObj = rolesCache.find(r => r.id === inv.roleId);
                                    const roleName = inv.roleName || (roleObj ? roleObj.name : `Роль #${inv.roleId}`);
                                    const roleColor = (roleObj && roleObj.color) || '#6366f1';
                                    const expiry = inv.expiresAt ? fmtTime(inv.expiresAt) : '7 дней';

                                    return `
                                        <tr>
                                            <td>
                                                <div style="display:flex; align-items:center; gap:8px;">
                                                    <img src="https://mc-heads.net/avatar/${encodeURIComponent(inv.username || '')}/24" class="player-avatar-sm" alt="" style="width:24px; height:24px; border-radius:4px;">
                                                    <b style="color:var(--text-main);">${esc(inv.username)}</b>
                                                </div>
                                            </td>
                                            <td>
                                                <span class="badge" style="background:${roleColor}22; color:${roleColor}; border:1px solid ${roleColor}44;">
                                                    ${esc(roleName)}
                                                </span>
                                            </td>
                                            <td>
                                                <span class="font-mono invite-code-preview" data-full="${esc(code)}" data-masked="${esc(masked)}" title="Нажмите, чтобы показать/скрыть" style="cursor:pointer; font-weight:700; color:var(--accent);">
                                                    ${esc(masked)}
                                                </span>
                                            </td>
                                            <td style="font-size:12px; color:var(--text-muted);">${esc(inv.createdBy || '—')}</td>
                                            <td style="font-size:12px; color:var(--text-dim);">${expiry}</td>
                                            <td style="text-align:right;">
                                                <button type="button" class="danger btn-sm" onclick="window.cancelStaffInvite(${inv.id}, '${esc(inv.username)}')">
                                                    ОТМЕНИТЬ
                                                </button>
                                            </td>
                                        </tr>
                                    `;
                                }).join('')}
                            </tbody>
                        </table>
                    </div>
                `;

                container.querySelectorAll('.invite-code-preview').forEach(el => {
                    let revealed = false;
                    el.addEventListener('click', () => {
                        revealed = !revealed;
                        el.textContent = revealed ? el.dataset.full : el.dataset.masked;
                    });
                });
                return;
            }

            // Filter admins: active or review
            let filteredAdmins = adminsCache.filter(a => a.status === 'ACTIVE');
            if (currentFilter === 'review') {
                filteredAdmins = filteredAdmins.filter(a => {
                    const expiry = a.roleExpiresAt || 0;
                    const expired = expiry > 0 && expiry <= Math.floor(Date.now() / 1000);
                    return a.pendingRoleReview || expired;
                });
            }

            if (!filteredAdmins.length) {
                container.innerHTML = `
                    <div style="text-align:center; padding:40px 20px; background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); color:var(--text-muted);">
                        <div style="font-size:14px; font-weight:600; color:var(--text-main);">
                            ${currentFilter === 'review' ? 'Нет сотрудников, требующих решения по испытательному сроку' : 'Нет активных сотрудников'}
                        </div>
                    </div>
                `;
                return;
            }

            container.innerHTML = `
                <div class="table-wrap" style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md);">
                    <table>
                        <thead>
                            <tr>
                                <th>СОТРУДНИК</th>
                                <th>ТЕКУЩАЯ РОЛЬ</th>
                                <th>2FA ЗАЩИТА</th>
                                <th>СРОК РОЛИ</th>
                                <th>ПОСЛЕДНИЙ ВХОД</th>
                                <th style="text-align:right;">ДЕЙСТВИЯ</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${filteredAdmins.map(a => {
                                const roleObj = rolesCache.find(r => r.id === a.roleId) || { name: 'Неизвестно', color: '#6366f1' };
                                const roleColor = roleObj.color || (roleObj.isOwner ? '#a855f7' : '#6366f1');
                                const hasTotp = a.totpEnabled;
                                const isSelf = me && (me.username === a.username || me.id === a.id);
                                const hideChangeRole = isSelf && (Boolean(me?.isOwner) || Boolean(roleObj.isOwner));

                                const expirySec = a.roleExpiresAt || 0;
                                const isExpired = expirySec > 0 && expirySec <= Math.floor(Date.now() / 1000);
                                const isPendingReview = a.pendingRoleReview || isExpired;

                                let expiryHtml = 'Бессрочно';
                                if (isPendingReview) {
                                    expiryHtml = `<span class="badge badge-review">Срок истёк</span>`;
                                } else if (expirySec > 0) {
                                    expiryHtml = `<span style="color:var(--text-muted); font-size:12px;">до ${fmtTime(expirySec)}</span>`;
                                }

                                return `
                                    <tr>
                                        <td>
                                            <div style="display:flex; align-items:center; gap:8px;">
                                                <img src="https://mc-heads.net/avatar/${encodeURIComponent(a.username || '')}/26" class="player-avatar-sm" alt="" style="width:26px; height:26px; border-radius:4px;">
                                                <div>
                                                    <b style="color:var(--text-main);">${esc(a.username)}</b>
                                                    ${isSelf ? '<span class="badge gray" style="font-size:10px; margin-left:4px;">ВЫ</span>' : ''}
                                                </div>
                                            </div>
                                        </td>
                                        <td>
                                            <span class="badge" style="background:${roleColor}22; color:${roleColor}; border:1px solid ${roleColor}44;">
                                                ${esc(roleObj.name)}
                                            </span>
                                        </td>
                                        <td>
                                            <span class="badge ${hasTotp ? 'green' : 'yellow'}" style="display:inline-flex; align-items:center; gap:4px;">
                                                ${hasTotp ? renderSvgIcon('check', 'green', 11) + ' 2FA ВКЛ' : 'НЕТ 2FA'}
                                            </span>
                                        </td>
                                        <td>${expiryHtml}</td>
                                        <td class="font-mono" style="font-size:12px; color:var(--text-dim);">${fmtTime(a.lastLoginAt)}</td>
                                        <td style="text-align:right;">
                                            <div style="display:flex; justify-content:flex-end; gap:6px; flex-wrap:wrap;">
                                                ${isPendingReview ? `
                                                    <button type="button" class="primary btn-sm" onclick="window.promoteToModerator(${a.id}, '${esc(a.username)}')">СДЕЛАТЬ МОДЕРАТОРОМ</button>
                                                    <button type="button" class="secondary btn-sm" onclick="window.keepCurrentRole(${a.id}, '${esc(a.username)}')">ОСТАВИТЬ</button>
                                                    <button type="button" class="danger btn-sm" onclick="window.revokeStaffAccess(${a.id}, '${esc(a.username)}')">СНЯТЬ ДОСТУП</button>
                                                ` : `
                                                    ${!hideChangeRole ? `
                                                        <button type="button" class="secondary btn-sm" onclick="window.openEditAdminRoleModal(${a.id}, '${esc(a.username)}', ${a.roleId})">РОЛЬ</button>
                                                    ` : ''}
                                                    <button type="button" class="secondary btn-sm" onclick="window.resetStaffPassword(${a.id}, '${esc(a.username)}')">СБРОС ПАРОЛЯ</button>
                                                    <button type="button" class="danger btn-sm" onclick="window.terminateStaffSessions(${a.id}, '${esc(a.username)}')">СБРОС СЕССИЙ</button>
                                                `}
                                            </div>
                                        </td>
                                    </tr>
                                `;
                            }).join('')}
                        </tbody>
                    </table>
                </div>
            `;
        }

        // ---------------- Roles Tab ----------------
        async function renderRolesTab() {
            const container = document.getElementById('staff-tab-body');
            if (!container) return;

            container.innerHTML = `<div style="text-align:center; padding:30px; color:var(--text-muted);">Загрузка ролей...</div>`;
            try {
                const [roles, admins] = await Promise.all([
                    api('GET', '/api/roles'),
                    api('GET', '/api/admins')
                ]);
                rolesCache = roles;
                adminsCache = admins;

                // Category order: OWNER -> ADMIN -> MOD -> PROBATION -> CUSTOM
                const catOrder = ['OWNER', 'ADMIN', 'MOD', 'PROBATION', 'CUSTOM'];
                const catLabels = {
                    'OWNER': 'Управляющие (OWNER)',
                    'ADMIN': 'Администраторы (ADMIN)',
                    'MOD': 'Модераторы (MOD)',
                    'PROBATION': 'Испытательный срок (PROBATION)',
                    'CUSTOM': 'Пользовательские роли (CUSTOM)'
                };

                const grouped = {};
                catOrder.forEach(c => { grouped[c] = []; });
                roles.forEach(r => {
                    let c = (r.category || '').toUpperCase();
                    if (!catOrder.includes(c)) c = 'CUSTOM';
                    grouped[c].push(r);
                });

                container.innerHTML = `
                    <div style="display:flex; flex-direction:column; gap:24px;">
                        ${catOrder.map(cat => {
                            const list = grouped[cat];
                            if (!list || !list.length) return '';
                            return `
                                <div>
                                    <div style="display:flex; align-items:center; gap:8px; margin-bottom:12px;">
                                        <h3 style="font-size:14px; font-weight:700; color:var(--text-heading); margin:0;">${esc(catLabels[cat] || cat)}</h3>
                                        <span class="badge gray" style="font-size:11px;">${list.length}</span>
                                    </div>
                                    <div style="display:grid; grid-template-columns:repeat(auto-fill, minmax(320px, 1fr)); gap:14px;">
                                        ${list.map(r => {
                                            const rCol = r.color || (r.isOwner ? '#a855f7' : '#8b5cf6');
                                            const assignedAdmins = admins.filter(a => a.roleId === r.id);
                                            const hasAdmins = assignedAdmins.length > 0;
                                            const canDelete = !r.isOwner && !hasAdmins;
                                            
                                            // Description rule: if empty or equals name, don't show duplicate; if empty show "Без описания"
                                            let descText = (r.description || '').trim();
                                            let isBlank = false;
                                            if (!descText || descText.toLowerCase() === r.name.toLowerCase()) {
                                                descText = 'Без описания';
                                                isBlank = true;
                                            }

                                            const permsCount = (r.permissions || []).length;
                                            const lpText = r.lpGroup ? ` · LP: ${esc(r.lpGroup)}` : '';

                                            return `
                                                <div style="background:var(--card-bg); border:1px solid var(--border); border-radius:var(--radius-md); padding:16px; display:flex; flex-direction:column; justify-content:space-between; gap:12px;">
                                                    <div>
                                                        <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:8px; margin-bottom:6px;">
                                                            <div style="display:flex; align-items:center; gap:8px;">
                                                                <span style="display:inline-block; width:12px; height:12px; border-radius:50%; background:${esc(rCol)}; box-shadow:0 0 6px ${esc(rCol)};"></span>
                                                                <h4 style="margin:0; font-size:15px; color:var(--text-heading);">${esc(r.name)}</h4>
                                                            </div>
                                                            <span class="badge" style="background:${rCol}22; color:${rCol}; border:1px solid ${rCol}44; font-size:10.5px;">
                                                                ${r.isOwner ? 'ВЛАДЕЛЕЦ' : esc(r.category || 'РОЛЬ')}
                                                            </span>
                                                        </div>
                                                        <p style="font-size:12px; color:${isBlank ? 'var(--text-dim)' : 'var(--text-muted)'}; margin:4px 0 10px 0; line-height:1.4;">
                                                            ${esc(descText)}
                                                        </p>
                                                        <div style="font-size:11.5px; color:var(--text-muted); display:flex; align-items:center; gap:6px;">
                                                            <span><b>${permsCount}</b> прав</span>${lpText}
                                                            <span style="color:var(--text-dim);">•</span>
                                                            <span>${assignedAdmins.length} сотр.</span>
                                                        </div>
                                                    </div>
                                                    <div style="display:flex; justify-content:flex-end; gap:8px; border-top:1px solid var(--border); padding-top:10px;">
                                                        ${!r.isOwner ? `
                                                            <button type="button" class="secondary btn-sm" onclick="window.openEditRoleModal(${r.id})">РЕДАКТИРОВАТЬ</button>
                                                            <button type="button" class="danger btn-sm" ${!canDelete ? 'disabled title="Нельзя удалить: к роли привязаны сотрудники"' : ''} onclick="window.deleteRole(${r.id}, '${esc(r.name)}')">
                                                                УДАЛИТЬ
                                                            </button>
                                                        ` : `
                                                            <span style="font-size:11.5px; color:var(--text-dim); padding:4px 0;">Полный доступ (системная роль)</span>
                                                        `}
                                                    </div>
                                                </div>
                                            `;
                                        }).join('')}
                                    </div>
                                </div>
                            `;
                        }).join('')}
                    </div>
                `;
            } catch (e) {
                container.innerHTML = `<div style="text-align:center; color:var(--red); padding:20px;">Ошибка загрузки ролей: ${esc(e.message)}</div>`;
            }
        }

        // ---------------- Invite Creation Wizard ----------------
        function openInviteWizardModal() {
            const availableRoles = rolesCache.filter(r => !r.isOwner).sort((a, b) => (a.sortOrder || 99) - (b.sortOrder || 99));
            
            openModal(`
                <div class="modal-header">
                    <h3>ПРИГЛАШЕНИЕ СОТРУДНИКА</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
                </div>
                <div class="modal-body" id="invite-wizard-body">
                    <!-- Step 1 -->
                    <div id="invite-step-1">
                        <div class="form-group">
                            <label>Никнейм сотрудника в Minecraft *</label>
                            <input id="invite-username" placeholder="Например: Steve" autocomplete="username" autofocus required>
                        </div>
                        <div class="form-group">
                            <label>Назначаемая роль *</label>
                            <select id="invite-role">
                                ${availableRoles.map(r => `
                                    <option value="${r.id}">${esc(r.name)} (${esc(r.category || 'Роль')})</option>
                                `).join('')}
                            </select>
                        </div>
                        <div class="form-group">
                            <label>Испытательный срок</label>
                            <select id="invite-probation">
                                <option value="0">Без срока (постоянный доступ)</option>
                                <option value="7">7 дней (испытательный срок)</option>
                                <option value="14">14 дней</option>
                                <option value="30">30 дней</option>
                            </select>
                        </div>
                        <div id="invite-err" class="error" style="display:none; margin-bottom:12px;"></div>
                    </div>
                    <!-- Step 2 -->
                    <div id="invite-step-2" style="display:none;"></div>
                </div>
                <div class="modal-footer" id="invite-wizard-footer">
                    <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                    <button type="button" class="primary" id="invite-next">ДАЛЕЕ</button>
                </div>
            `, () => {
                const uInput = document.getElementById('invite-username');
                if (uInput) attachPlayerAutocomplete(uInput);

                document.getElementById('invite-next')?.addEventListener('click', async () => {
                    const username = document.getElementById('invite-username').value.trim();
                    const roleId = parseInt(document.getElementById('invite-role').value, 10);
                    const probationDays = parseInt(document.getElementById('invite-probation').value, 10) || 0;
                    const errEl = document.getElementById('invite-err');
                    const nextBtn = document.getElementById('invite-next');

                    if (!username) {
                        if (errEl) { errEl.textContent = 'Укажите никнейм сотрудника'; errEl.style.display = 'block'; }
                        return;
                    }
                    if (errEl) errEl.style.display = 'none';
                    nextBtn.disabled = true;
                    nextBtn.textContent = 'СОЗДАНИЕ...';

                    try {
                        const res = await api('POST', '/api/admins/invites', { username, roleId, probationDays });
                        const code = res.code || '';
                        const inviteId = res.id;

                        // Switch to Step 2
                        document.getElementById('invite-step-1').style.display = 'none';
                        const step2 = document.getElementById('invite-step-2');
                        step2.style.display = 'block';
                        step2.innerHTML = `
                            <div class="invite-code-box">
                                <div class="code-label">Код для сотрудника ${esc(username)}</div>
                                <div class="code-value" id="invite-code-display">${esc(code)}</div>
                                <button type="button" class="primary" id="copy-invite-code" style="margin-bottom:14px; display:inline-flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon('copy', 'white', 14)} Скопировать код
                                </button>
                                <p style="font-size:12px; color:var(--text-muted); line-height:1.4; margin-bottom:16px;">
                                    Передайте этот одноразовый код сотруднику лично. Сотрудник вводит его на экране «Регистрация по приглашению» для создания пароля и привязки 2FA.
                                </p>
                                <button type="button" id="cancel-invite-btn" class="danger btn-sm">
                                    Отменить приглашение
                                </button>
                            </div>
                        `;

                        document.getElementById('invite-wizard-footer').innerHTML = `
                            <button type="button" class="primary" data-modal-close="true">ГОТОВО</button>
                        `;

                        document.getElementById('copy-invite-code')?.addEventListener('click', async () => {
                            try {
                                if (navigator.clipboard && navigator.clipboard.writeText) {
                                    await navigator.clipboard.writeText(code);
                                } else {
                                    const ta = document.createElement('textarea');
                                    ta.value = code;
                                    document.body.appendChild(ta);
                                    ta.select();
                                    document.execCommand('copy');
                                    document.body.removeChild(ta);
                                }
                                const btn = document.getElementById('copy-invite-code');
                                if (btn) {
                                    btn.innerHTML = `${renderSvgIcon('check', 'green', 14)} Код скопирован!`;
                                    setTimeout(() => {
                                        if (btn) btn.innerHTML = `${renderSvgIcon('copy', 'white', 14)} Скопировать код`;
                                    }, 2000);
                                }
                            } catch (_) {}
                        });

                        document.getElementById('cancel-invite-btn')?.addEventListener('click', async () => {
                            try {
                                await api('DELETE', `/api/admins/invites/${inviteId}`);
                                showToast('Приглашение отменено', `Код для ${username} отозван`, 'info');
                                closeModal();
                                loadPersonnelData();
                            } catch (e) {
                                alert('Ошибка отмены: ' + e.message);
                            }
                        });

                        loadPersonnelData();
                    } catch (e) {
                        if (errEl) { errEl.textContent = e.message; errEl.style.display = 'block'; }
                        nextBtn.disabled = false;
                        nextBtn.textContent = 'ДАЛЕЕ';
                    }
                });
            });
        }

        // ---------------- Staff Actions ----------------
        window.cancelStaffInvite = (inviteId, username) => {
            confirmAction('ОТМЕНА ПРИГЛАШЕНИЯ', `Отозвать приглашение для сотрудника ${username}? Код станет недействительным.`, async () => {
                try {
                    await api('DELETE', `/api/admins/invites/${inviteId}`);
                    showToast('Приглашение отменено', `Приглашение для ${username} успешно отменено`, 'info');
                    loadPersonnelData();
                } catch (e) {
                    alert('Ошибка отмены: ' + e.message);
                }
            }, 'ОТОЗВАТЬ');
        };

        window.promoteToModerator = async (adminId, username) => {
            // Find Moderator role (MOD category or name 'Модератор')
            const modRole = rolesCache.find(r => (r.category || '').toUpperCase() === 'MOD' || r.name.toLowerCase().includes('модератор'));
            if (!modRole) {
                alert('Роль Модератора не найдена в системе');
                return;
            }
            try {
                await api('PUT', `/api/admins/${adminId}/role`, {
                    roleId: modRole.id,
                    expiresAt: 0,
                    clearPendingRoleReview: true
                });
                showToast('Сотрудник переведён', `${username} успешно назначен на должность «${modRole.name}»`, 'success');
                loadPersonnelData();
            } catch (e) {
                alert('Ошибка назначения: ' + e.message);
            }
        };

        window.keepCurrentRole = async (adminId, username) => {
            const admin = adminsCache.find(a => a.id === adminId);
            if (!admin) return;
            try {
                await api('PUT', `/api/admins/${adminId}/role`, {
                    roleId: admin.roleId,
                    expiresAt: 0,
                    clearPendingRoleReview: true
                });
                showToast('Статус подтвержден', `Сотрудник ${username} оставлен в текущей должности на постоянной основе`, 'info');
                loadPersonnelData();
            } catch (e) {
                alert('Ошибка: ' + e.message);
            }
        };

        window.revokeStaffAccess = (adminId, username) => {
            confirmAction('СНЯТИЕ ДОСТУПА', `Снять доступ к панели для сотрудника ${username}? Учетная запись будет деактивирована.`, async () => {
                try {
                    await api('DELETE', `/api/admins/${adminId}`);
                    showToast('Доступ снят', `Сотрудник ${username} удален из панели`, 'info');
                    loadPersonnelData();
                } catch (e) {
                    alert('Ошибка: ' + e.message);
                }
            }, 'СНЯТЬ ДОСТУП');
        };

        window.openEditAdminRoleModal = (adminId, username, currentRoleId) => {
            const admin = adminsCache.find(a => a.id === adminId);
            const currentRole = rolesCache.find(r => r.id === currentRoleId);
            if (currentRole && currentRole.isOwner && me && me.isOwner && (me.username === username || me.id === adminId)) {
                alert('Управляющий не может изменить собственную роль');
                return;
            }

            openModal(`
                <div class="modal-header">
                    <h3>НАЗНАЧЕНИЕ РОЛИ: ${esc(username)}</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
                </div>
                <div class="modal-body">
                    <div class="form-group">
                        <label>Выберите роль</label>
                        <select id="edit-admin-role-select">
                            ${rolesCache.filter(r => !r.isOwner).map(r => `
                                <option value="${r.id}" ${r.id === currentRoleId ? 'selected' : ''}>${esc(r.name)} (${esc(r.category || 'Роль')})</option>
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
                    const expiresAt = days > 0 ? (Math.floor(Date.now() / 1000) + days * 86400) : 0;
                    try {
                        await api('PUT', `/api/admins/${adminId}/role`, { roleId, expiresAt, clearPendingRoleReview: true });
                        showToast('Роль обновлена', `Роль сотрудника ${username} успешно изменена`, 'success');
                        closeModal();
                        loadPersonnelData();
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

        window.terminateStaffSessions = (adminId, username) => {
            confirmAction('СБРОС СЕССИЙ СОТРУДНИКА', `Принудительно завершить все сессии сотрудника ${username}? Пользователь будет немедленно разлогинен на всех устройствах.`, async () => {
                try {
                    await api('POST', `/api/admins/${adminId}/terminate-sessions`);
                    showToast('Сессии завершены', `Все сессии ${username} успешно аннулированы`, 'info');
                    loadPersonnelData();
                } catch (e) {
                    showToast('Ошибка', e.message, 'error');
                }
            }, 'СБРОСИТЬ ВСЕ');
        };

        // Helper to render permissions checkboxes
        const renderPermCheckboxes = (activePerms = []) => {
            return ALL_PERMISSIONS.map(p => {
                const has = activePerms.includes(p);
                const desc = PERMISSION_LABELS[p] || p;
                const displayName = PERMISSION_NAMES[p] || p;
                return `
                    <label style="display:flex; align-items:flex-start; gap:10px; padding:10px 12px; background:var(--card-bg); border:1px solid var(--border); border-radius:5px; cursor:pointer;" title="${esc(desc)}">
                        <input type="checkbox" class="role-perm-cb" value="${p}" ${has ? 'checked' : ''} style="width:16px; height:16px; accent-color:var(--accent); margin-top:2px;">
                        <div>
                            <div style="font-size:12.5px; font-weight:700; color:var(--text-heading);">${esc(displayName)}</div>
                            <div style="font-size:11.5px; color:var(--text-muted); line-height:1.35; margin-top:3px;">${esc(desc)}</div>
                        </div>
                    </label>
                `;
            }).join('');
        };

        // Modal: Create Role
        function openCreateRoleModal() {
            openModal(`
                <div class="modal-header">
                    <h3>СОЗДАНИЕ НОВОЙ РОЛИ</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
                </div>
                <div class="modal-body" style="max-height:75vh; overflow-y:auto;">
                    <div class="form-group">
                        <label>Название роли *</label>
                        <input type="text" id="create-role-name" placeholder="Например: Старший Модератор" required autofocus>
                    </div>
                    <div class="form-group">
                        <label>Описание роли (кратко о правах и назначении)</label>
                        <input type="text" id="create-role-desc" placeholder="Например: Проверка жалоб и базовый надзор">
                    </div>
                    <div class="form-group">
                        <label>Категория роли</label>
                        <select id="create-role-category">
                            <option value="CUSTOM">Пользовательская (CUSTOM)</option>
                            <option value="ADMIN">Администраторы (ADMIN)</option>
                            <option value="MOD">Модераторы (MOD)</option>
                            <option value="PROBATION">Испытательный срок (PROBATION)</option>
                        </select>
                    </div>
                    <div class="form-group">
                        <label>Цвет роли (для бейджей, списков и журналов)</label>
                        <div style="display:flex; align-items:center; gap:10px;">
                            <input type="color" id="create-role-color" value="#8b5cf6" style="width:42px; height:34px; padding:0; cursor:pointer; background:none; border:none;">
                            <span style="font-size:12px; color:var(--text-muted);">Акцентный цвет оформления роли</span>
                        </div>
                    </div>

                    <div style="margin-top:16px; margin-bottom:8px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                        <label style="margin-bottom:0; font-weight:700; color:var(--text-heading);">Права доступа роли в панели:</label>
                        <div style="display:flex; gap:6px;">
                            <button type="button" class="secondary btn-sm" id="btn-perm-all">Выбрать все</button>
                            <button type="button" class="secondary btn-sm" id="btn-perm-none">Снять все</button>
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

                document.getElementById('btn-submit-create-role')?.addEventListener('click', async () => {
                    const name = document.getElementById('create-role-name').value.trim();
                    const description = document.getElementById('create-role-desc')?.value.trim() || '';
                    const category = document.getElementById('create-role-category')?.value || 'CUSTOM';
                    const color = document.getElementById('create-role-color')?.value || '#8b5cf6';
                    const permissions = Array.from(document.querySelectorAll('.role-perm-cb:checked')).map(cb => cb.value);

                    if (!name) { alert('Укажите название роли'); return; }

                    try {
                        await api('POST', '/api/roles', { name, description, category, lpGroup: '', color, permissions });
                        showToast('Роль создана', `Роль LoveWebAdmin успешно добавлена`, 'success');
                        closeModal();
                        renderRolesTab();
                    } catch (e) {
                        alert('Ошибка: ' + e.message);
                    }
                });
            }, 'modal-lg');
        }

        // Modal: Edit Role
        window.openEditRoleModal = (roleId) => {
            const role = rolesCache.find(r => r.id === roleId);
            if (!role) return;

            openModal(`
                <div class="modal-header">
                    <h3>РЕДАКТИРОВАНИЕ РОЛИ: ${esc(role.name)}</h3>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
                </div>
                <div class="modal-body" style="max-height:75vh; overflow-y:auto;">
                    <div class="form-group">
                        <label>Название роли *</label>
                        <input type="text" id="edit-role-name" value="${esc(role.name)}" required autofocus>
                    </div>
                    <div class="form-group">
                        <label>Описание роли</label>
                        <input type="text" id="edit-role-desc" value="${esc(role.description || '')}" placeholder="Краткое описание обязанностей">
                    </div>
                    <div class="form-group">
                        <label>Категория роли</label>
                        <select id="edit-role-category">
                            <option value="CUSTOM" ${role.category === 'CUSTOM' ? 'selected' : ''}>Пользовательская (CUSTOM)</option>
                            <option value="ADMIN" ${role.category === 'ADMIN' ? 'selected' : ''}>Администраторы (ADMIN)</option>
                            <option value="MOD" ${role.category === 'MOD' ? 'selected' : ''}>Модераторы (MOD)</option>
                            <option value="PROBATION" ${role.category === 'PROBATION' ? 'selected' : ''}>Испытательный срок (PROBATION)</option>
                        </select>
                    </div>
                    <div class="form-group">
                        <label>Цвет роли (для бейджей, списков и журналов)</label>
                        <div style="display:flex; align-items:center; gap:10px;">
                            <input type="color" id="edit-role-color" value="${esc(role.color || '#8b5cf6')}" style="width:42px; height:34px; padding:0; cursor:pointer; background:none; border:none;">
                            <span style="font-size:12px; color:var(--text-muted);">Акцентный цвет оформления роли</span>
                        </div>
                    </div>

                    <div style="margin-top:16px; margin-bottom:8px; display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                        <label style="margin-bottom:0; font-weight:700; color:var(--text-heading);">Права доступа роли в панели:</label>
                        <div style="display:flex; gap:6px;">
                            <button type="button" class="secondary btn-sm" id="btn-edit-perm-all">Выбрать все</button>
                            <button type="button" class="secondary btn-sm" id="btn-edit-perm-none">Снять все</button>
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

                document.getElementById('btn-submit-edit-role')?.addEventListener('click', async () => {
                    const name = document.getElementById('edit-role-name').value.trim();
                    const description = document.getElementById('edit-role-desc')?.value.trim() || '';
                    const category = document.getElementById('edit-role-category')?.value || 'CUSTOM';
                    const color = document.getElementById('edit-role-color')?.value || '#8b5cf6';
                    const permissions = Array.from(document.querySelectorAll('.role-perm-cb:checked')).map(cb => cb.value);

                    if (!name) { alert('Укажите название роли'); return; }

                    try {
                        await api('PUT', `/api/roles/${roleId}`, { name, description, category, lpGroup: '', color, permissions });
                        showToast('Роль обновлена', `Параметры и права для роли LoveWebAdmin сохранены`, 'success');
                        closeModal();
                        renderRolesTab();
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
                        renderRolesTab();
                    } catch (e) {
                        alert('Не удалось удалить роль: ' + e.message);
                    }
                },
                'УДАЛИТЬ РОЛЬ',
                true
            );
        };

        mount();
    }
    window.renderStaffView = renderStaffView;
    window.renderAdminsView = renderStaffView;


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
                    <input type="date" id="db-date-from" style="padding:4px 8px; font-size:12px; width:135px; background:var(--input-bg); border:1px solid var(--border); color:var(--text-heading); border-radius:4px;">
                    <span style="color:var(--text-dim); font-size:12px;">—</span>
                    <input type="date" id="db-date-to" style="padding:4px 8px; font-size:12px; width:135px; background:var(--input-bg); border:1px solid var(--border); color:var(--text-heading); border-radius:4px;">
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
                        <h3 style="font-size:15px; color:var(--text-heading);" id="db-chart-title">АКТИВНОСТЬ ПО ЧАСАМ (24 ЧАСА)</h3>
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
                        <h3 style="font-size:15px; color:var(--text-heading);">ТОП ИГРОКОВ ПО НАИГРАННОМУ ВРЕМЕНИ</h3>
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
                    <h3 style="font-size:15px; margin-bottom:6px; color:var(--text-heading);">КАРТОЧКА СТАТИСТИКИ ИГРОКА</h3>
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
                                        <b style="color:var(--text-heading);">${esc(p.name)}</b>
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
                    <div style="background:var(--card-inner-bg); padding:14px; border-radius:var(--radius-sm); border:1px solid var(--border-light);">
                        <div style="display:flex; align-items:center; gap:10px; margin-bottom:12px;">
                            <img src="https://mc-heads.net/avatar/${encodeURIComponent(p.name)}/36" class="player-avatar-sm" style="width:36px; height:36px;" alt="">
                            <div>
                                <h4 style="color:var(--text-main); font-size:15px; margin:0;">${esc(p.name)}</h4>
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

    // ==========================================================================
    // THEME & APPEARANCE
    // ==========================================================================
    function applyUserTheme(theme) {
        const isLight = theme === 'light';
        document.body.classList.toggle('theme-light', isLight);
        const icon = document.getElementById('theme-btn-icon');
        if (icon) icon.innerHTML = renderSvgIcon(isLight ? 'sun' : 'moon', isLight ? 'yellow' : 'violet', 15);
        localStorage.setItem('wa_theme', theme);
    }

    // ==========================================================================
    // SHIFT STATUS MANAGEMENT («Я НА СМЕНЕ»)
    // ==========================================================================
    async function initShiftStatus() {
        try {
            const res = await api('GET', '/api/me/shift');
            updateShiftButtonUI(res.onShift, res.startedAt);
        } catch (_) {}
    }

    function updateShiftButtonUI(onShift, startedAt) {
        const dot = document.getElementById('shift-btn-dot');
        const text = document.getElementById('shift-btn-text');
        const btn = document.getElementById('topbar-shift-btn');
        if (btn) btn.classList.toggle('on-shift', !!onShift);
        if (dot) dot.innerHTML = renderSvgIcon('dot', onShift ? 'green' : 'gray', 10);
        if (text) text.textContent = onShift ? 'НА СМЕНЕ' : 'ВНЕ СМЕНЫ';
    }

    async function toggleMyShift() {
        const isCurrentlyOn = document.getElementById('topbar-shift-btn')?.classList.contains('on-shift');
        const nextStatus = !isCurrentlyOn;
        try {
            const res = await api('POST', '/api/me/shift', { onShift: nextStatus });
            updateShiftButtonUI(res.onShift, res.startedAt);
            showToast(res.onShift ? 'Смена начата' : 'Смена завершена', res.onShift ? 'Вы вышли на смену модератора' : 'Вы завершили смену', 'info');
            recordShiftAction(res.onShift ? 'Вышел на смену' : 'Завершил смену');
        } catch (e) {
            showToast('Ошибка смены', e.message, 'error');
        }
    }

    async function openStaffOnShiftModal() {
        openModal(`
            <div class="modal-header">
                <h3 style="display:flex; align-items:center; gap:8px;">${renderSvgIcon('users', 'violet', 18)} ПЕРСОНАЛ СЕЙЧАС НА СМЕНЕ</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
            </div>
            <div class="modal-body" id="staff-shifts-modal-body">
                <div style="text-align:center; padding:20px; color:var(--text-muted);">Загрузка активных смен...</div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `, async () => {
            const body = document.getElementById('staff-shifts-modal-body');
            try {
                const data = await api('GET', '/api/staff/shifts');
                const list = data.shifts || [];
                if (!list.length) {
                    body.innerHTML = '<div style="text-align:center; padding:24px; color:var(--text-dim); background:var(--card-bg); border-radius:var(--radius-md); border:1px solid var(--border);">Сейчас никого из персонала нет на активной смене.</div>';
                    return;
                }
                body.innerHTML = `
                    <div class="table-wrap">
                        <table>
                            <thead>
                                <tr>
                                    <th>СОТРУДНИК</th>
                                    <th>РОЛЬ</th>
                                    <th>НАЧАЛО СМЕНЫ</th>
                                    <th>ВРЕМЯ НА СМЕНЕ</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${list.map(s => `
                                    <tr>
                                        <td>
                                            <div style="display:flex; align-items:center; gap:8px;">
                                                <div class="sidebar-user-avatar" style="width:24px; height:24px; font-size:10px;">${esc((s.username || 'A').substring(0, 2).toUpperCase())}</div>
                                                <b style="color:var(--text-main);">${esc(s.username)}</b>
                                            </div>
                                        </td>
                                        <td><span class="badge purple">${esc(s.role || 'Персонал')}</span></td>
                                        <td class="font-mono" style="font-size:12px; color:var(--text-muted);">${fmtTime(s.startedAt)}</td>
                                        <td style="color:var(--green); font-weight:700;">${fmtDuration(s.durationSeconds || 0)}</td>
                                    </tr>
                                `).join('')}
                            </tbody>
                        </table>
                    </div>
                `;
            } catch (e) {
                body.innerHTML = `<div style="color:var(--red); padding:16px;">Ошибка загрузки смен: ${esc(e.message)}</div>`;
            }
        });
    }

    // ==========================================================================
    // NOTIFICATIONS SYSTEM
    // ==========================================================================
    async function updateNotificationsBadge() {
        try {
            const res = await api('GET', '/api/notifications/unread-count');
            const badge = document.getElementById('topbar-notif-badge');
            if (badge) {
                const count = res.unreadCount || 0;
                badge.textContent = count > 99 ? '99+' : count;
                badge.style.display = count > 0 ? 'inline-flex' : 'none';
            }
        } catch (_) {}
    }

    async function openNotificationsModal() {
        openModal(`
            <div class="modal-header">
                <h3 style="display:flex; align-items:center; gap:8px;">${renderSvgIcon('bell', 'violet', 18)} УВЕДОМЛЕНИЯ ПЕРСОНАЛА</h3>
                <div style="display:flex; gap:8px; align-items:center;">
                    <button type="button" class="secondary btn-sm" id="btn-create-notif">+ ОБЪЯВЛЕНИЕ</button>
                    <button type="button" class="secondary btn-sm" id="btn-read-all-notifs">ПРОЧИТАТЬ ВСЕ</button>
                    <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
                </div>
            </div>
            <div class="modal-body" id="notifs-modal-body" style="max-height:65vh; overflow-y:auto;">
                <div style="text-align:center; padding:24px; color:var(--text-muted);">Загрузка уведомлений...</div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `, async () => {
            const body = document.getElementById('notifs-modal-body');

            const loadList = async () => {
                try {
                    const data = await api('GET', '/api/notifications?limit=50');
                    const list = data.notifications || [];
                    if (!list.length) {
                        body.innerHTML = `
                            <div style="text-align:center; padding:32px; color:var(--text-dim);">
                                <div style="margin-bottom:8px;">${renderSvgIcon('bell', 'gray', 32)}</div>
                                <div>Новых уведомлений для персонала нет</div>
                            </div>
                        `;
                        return;
                    }

                    body.innerHTML = list.map(n => {
                        let badgeClass = 'blue';
                        let typeLabel = 'ИНФО';
                        if (n.type === 'WARNING') { badgeClass = 'yellow'; typeLabel = 'ПРЕДУПРЕЖДЕНИЕ'; }
                        else if (n.type === 'IMPORTANT') { badgeClass = 'purple'; typeLabel = 'ВАЖНО'; }
                        else if (n.type === 'CRITICAL') { badgeClass = 'red'; typeLabel = 'КРИТИЧНО'; }

                        return `
                            <div class="notif-item ${n.read ? 'read' : 'unread'}" style="background:var(--card-inner-bg); border:1px solid var(--border); border-radius:var(--radius-sm); padding:12px; margin-bottom:8px;">
                                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
                                    <div style="display:flex; align-items:center; gap:8px;">
                                        <span class="badge ${badgeClass}">${typeLabel}</span>
                                        <b style="color:var(--text-main); font-size:13.5px;">${esc(n.title)}</b>
                                    </div>
                                    <span class="font-mono" style="font-size:11px; color:var(--text-dim);">${fmtTime(n.createdAt)}</span>
                                </div>
                                <div style="color:var(--text-secondary); font-size:12.5px; line-height:1.4; margin-top:4px;">
                                    ${esc(n.message)}
                                </div>
                                <div style="display:flex; justify-content:space-between; align-items:center; margin-top:8px; font-size:11px; color:var(--text-dim);">
                                    <span>От: <b>${esc(n.sender || 'Система')}</b></span>
                                    ${!n.read ? `
                                        <button type="button" class="secondary btn-sm" onclick="window.markNotifRead(${n.id})" style="font-size:10px; padding:2px 6px; display:inline-flex; align-items:center; gap:4px;">
                                            ${renderSvgIcon('check', 'gray', 11)} Прочитано
                                        </button>
                                    ` : '<span style="color:var(--text-dim);">Прочитано</span>'}
                                </div>
                            </div>
                        `;
                    }).join('');
                } catch (e) {
                    body.innerHTML = `<div style="color:var(--red); padding:16px;">Ошибка загрузки уведомлений: ${esc(e.message)}</div>`;
                }
            };

            window.markNotifRead = async (id) => {
                try {
                    await api('POST', `/api/notifications/me.lovelace:LoveWebAdmin:jar:1.1.0/read`);
                    updateNotificationsBadge();
                    loadList();
                } catch (_) {}
            };

            document.getElementById('btn-read-all-notifs')?.addEventListener('click', async () => {
                try {
                    await api('POST', '/api/notifications/read-all');
                    updateNotificationsBadge();
                    loadList();
                    showToast('Уведомления', 'Все уведомления помечены как прочитанные', 'info');
                } catch (_) {}
            });

            document.getElementById('btn-create-notif')?.addEventListener('click', openNewAnnouncementModal);

            await loadList();
        });
    }

    function openNewAnnouncementModal() {
        openModal(`
            <div class="modal-header">
                <h3>СОЗДАТЬ ОБЪЯВЛЕНИЕ ДЛЯ ПЕРСОНАЛА</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
            </div>
            <div class="modal-body">
                <div class="form-group">
                    <label>Категория важности</label>
                    <select id="ann-type-select">
                        <option value="INFO">Инфо (Стандартное оповещение)</option>
                        <option value="WARNING">Предупреждение (Обратите внимание)</option>
                        <option value="IMPORTANT">Важно (Инструкция / Правила)</option>
                        <option value="CRITICAL">Критично (Срочные действия)</option>
                    </select>
                </div>
                <div class="form-group">
                    <label>Заголовок объявления</label>
                    <input type="text" id="ann-title" placeholder="Например: Собрание состава в Discord в 19:00" required>
                </div>
                <div class="form-group">
                    <label>Текст сообщения</label>
                    <textarea id="ann-message" rows="4" placeholder="Подробный текст сообщения для всех членов персонала..." style="width:100%;"></textarea>
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ОТМЕНА</button>
                <button type="button" class="primary" id="btn-submit-announcement">ОТПРАВИТЬ ОБЪЯВЛЕНИЕ</button>
            </div>
        `, () => {
            document.getElementById('btn-submit-announcement')?.addEventListener('click', async () => {
                const type = document.getElementById('ann-type-select').value;
                const title = document.getElementById('ann-title').value.trim();
                const message = document.getElementById('ann-message').value.trim();
                if (!title || !message) {
                    showToast('Ошибка', 'Заполните заголовок и текст сообщения', 'warning');
                    return;
                }

                try {
                    await api('POST', '/api/notifications', { type, title, message });
                    showToast('Объявление отправлено', 'Сообщение доставлено всем сотрудникам', 'success');
                    closeModal();
                    updateNotificationsBadge();
                } catch (e) {
                    showToast('Ошибка отправки', e.message, 'error');
                }
            });
        });
    }

    // ==========================================================================
    // MAINTENANCE MODE SCREEN
    // ==========================================================================
    function renderMaintenanceScreen(message) {
        clearPollers();
        app.innerHTML = `
            <div class="maintenance-screen">
                <div class="maintenance-card">
                    <div class="maintenance-icon" style="display:flex; justify-content:center; margin-bottom:16px;">${renderSvgIcon('wrench', 'violet', 48)}</div>
                    <h2>ВЕДУТСЯ ТЕХНИЧЕСКИЕ РАБОТЫ</h2>
                    <p class="maintenance-msg">${esc(message || 'Веб-панель временно закрыта на плановое обслуживание сервера. Доступ разрешён только Управляющему.')}</p>
                    <div style="font-size:12px; color:var(--text-dim); margin-bottom:18px;">
                        Если вы являетесь Управляющим или имеете право обхода тех. работ, войдите с подтверждением.
                    </div>
                    <div style="display:flex; gap:10px; justify-content:center;">
                        <button type="button" class="primary" onclick="location.reload()" style="display:inline-flex; align-items:center; gap:6px;">${renderSvgIcon('refresh', 'white', 14)} ПРОВЕРИТЬ СНОВА</button>
                        <button type="button" class="secondary" onclick="localStorage.removeItem('wa_token'); location.reload();">ВЫЙТИ В ВХОД</button>
                    </div>
                </div>
            </div>
        `;
    }

    // ==========================================================================
    // UNIFIED ADMIN SETTINGS MODAL (REAL SETTINGS & ICONS)
    // ==========================================================================
    async function openAdminSettingsModal(initialTab = 'general') {
        const isMod = isModeratorRole();
        const roleName = me.isOwner ? 'Управляющий' : (me.role || (isMod ? 'Модератор' : 'Администратор'));

        let userPrefs = {};
        try {
            userPrefs = typeof me.uiPreferences === 'string' ? JSON.parse(me.uiPreferences) : (me.uiPreferences || {});
        } catch (_) { userPrefs = {}; }

        let serverConfig = {
            webPort: 8080,
            webHost: '0.0.0.0',
            sessionLifetimeMinutes: 1440,
            keepLogCount: 2000,
            strictIp: true,
            loginMaxAttempts: 5,
            loginLockoutMinutes: 15,
            minPasswordLength: 8,
            debugMode: false,
            commandBlacklist: ['stop', 'restart', 'op', 'deop', 'sudo', 'pex', 'lp'],
            webhooksEnabled: false,
            discordWebhookUrl: '',
            telegramBotToken: '',
            telegramChatId: ''
        };

        try {
            const fetchedConfig = await api('GET', '/api/server/config');
            if (fetchedConfig && typeof fetchedConfig === 'object') {
                serverConfig = { ...serverConfig, ...fetchedConfig };
            }
        } catch (_) {}

        let currentTab = initialTab || 'general';

        const tabs = [
            { id: 'general', label: 'Общие', icon: renderSvgIcon('gear', 'violet', 16) },
            { id: 'server', label: 'Сервер и сеть', icon: renderSvgIcon('server', 'violet', 16) },
            { id: 'security', label: 'Безопасность', icon: renderSvgIcon('shield', 'green', 16) },
            { id: 'blacklist', label: 'Черный список', icon: renderSvgIcon('ban', 'red', 16) },
            { id: 'webhooks', label: 'Вебхуки и связь', icon: renderSvgIcon('bell', 'violet', 16) },
            { id: 'maintenance', label: 'Тех. работы', icon: renderSvgIcon('wrench', 'gray', 16) },
            { id: 'danger', label: 'Опасные зоны', icon: renderSvgIcon('alert', 'red', 16) }
        ];

        openModal(`
            <div class="modal-header">
                <h3>НАСТРОЙКИ ПАНЕЛИ И СЕРВЕРА</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
            </div>
            <div class="modal-body" style="padding:0; max-height:82vh; overflow:hidden;">
                <div class="settings-modal-layout">
                    <!-- Left Navigation Sidebar -->
                    <aside class="settings-nav-sidebar">
                        ${tabs.map(t => `
                            <button type="button" class="settings-tab-btn ${t.id === currentTab ? 'active' : ''}" data-tab="${t.id}">
                                <span>${t.icon}</span>
                                <span>${esc(t.label)}</span>
                            </button>
                        `).join('')}
                    </aside>

                    <!-- Right Tab Pane -->
                    <section class="settings-tab-pane" id="settings-tab-content">
                        <!-- Rendered by switchSettingsTab -->
                    </section>
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="secondary" data-modal-close="true">ЗАКРЫТЬ</button>
            </div>
        `, () => {
            const contentPane = document.getElementById('settings-tab-content');

            const switchSettingsTab = async (tabId) => {
                currentTab = tabId;
                document.querySelectorAll('.settings-tab-btn').forEach(b => {
                    b.classList.toggle('active', b.dataset.tab === tabId);
                });

                if (!contentPane) return;

                if (tabId === 'general') {
                    // TAB 1: ОБЩИЕ
                    const isLight = document.body.classList.contains('theme-light');
                    contentPane.innerHTML = `
                        <h4 style="color:var(--text-main); margin-bottom:14px;">ОБЩИЙ ПРОФИЛЬ СОТРУДНИКА</h4>
                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <div style="display:flex; align-items:center; gap:12px;">
                                <div class="sidebar-user-avatar" style="width:46px; height:46px; font-size:18px;">
                                    ${esc((me.username || 'A').substring(0, 2).toUpperCase())}
                                </div>
                                <div>
                                    <div style="font-size:17px; font-weight:800; color:var(--text-main);">
                                        ${esc(me.username)} <span class="badge purple">${esc(roleName)}</span>
                                    </div>
                                    <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                        2FA Статус: <b>${me.totpEnabled ? 'Включена (TOTP)' : 'Отключена'}</b> • IP последнего входа: ${esc(me.last2faIp || '—')}
                                    </div>
                                </div>
                            </div>
                        </div>

                        <div class="form-group">
                            <label>Привязанный игровой никнейм Minecraft</label>
                            <input type="text" id="gen-mc-nick" value="${esc(userPrefs.minecraftNick || me.username || '')}" placeholder="Никнейм в игре...">
                            <div style="font-size:11.5px; color:var(--text-muted); margin-top:4px;">Используется для быстрого автозаполнения и синхронизации смен в игре</div>
                        </div>

                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <b style="color:var(--text-main); font-size:13.5px;">Тема оформления интерфейса</b>
                            <div style="font-size:12px; color:var(--text-muted); margin-bottom:12px;">Выберите цветовую схему для комфортной работы:</div>

                            <div style="display:flex; gap:12px;">
                                <button type="button" class="secondary ${!isLight ? 'active' : ''}" id="btn-theme-dark" style="flex:1; padding:12px; display:flex; align-items:center; justify-content:center; gap:8px;">
                                    ${renderSvgIcon('moon', 'violet', 16)} ТЁМНАЯ ТЕМА (DARK)
                                </button>
                                <button type="button" class="secondary ${isLight ? 'active' : ''}" id="btn-theme-light" style="flex:1; padding:12px; display:flex; align-items:center; justify-content:center; gap:8px;">
                                    ${renderSvgIcon('sun', 'yellow', 16)} СВЕТЛАЯ ТЕМА (LIGHT)
                                </button>
                            </div>
                        </div>

                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <b style="color:var(--text-main); font-size:13.5px;">Раскладка дашборда</b>
                            <div style="font-size:12px; color:var(--text-muted); margin-bottom:10px;">
                                Плитки дашборда можно свободно перетаскивать и настраивать прямо на главной странице.
                            </div>
                            <button type="button" class="secondary btn-sm" onclick="window.openDashboardCustomizerModal()" style="display:inline-flex; align-items:center; gap:6px;">
                                ${renderSvgIcon('gear', 'gray', 14)} НАСТРОИТЬ АКТИВНЫЕ ПЛИТКИ
                            </button>
                        </div>

                        <button type="button" class="primary btn-sm" id="btn-save-general-settings" style="width:100%; margin-top:6px; display:flex; align-items:center; justify-content:center; gap:6px;">
                            ${renderSvgIcon('save', 'white', 14)} СОХРАНИТЬ ОБЩИЕ НАСТРОЙКИ
                        </button>
                    `;

                    document.getElementById('btn-theme-dark')?.addEventListener('click', async () => {
                        applyUserTheme('dark');
                        userPrefs.theme = 'dark';
                        try { await api('PUT', '/api/me/preferences', { uiPreferences: userPrefs }); } catch (_) {}
                        switchSettingsTab('general');
                    });

                    document.getElementById('btn-theme-light')?.addEventListener('click', async () => {
                        applyUserTheme('light');
                        userPrefs.theme = 'light';
                        try { await api('PUT', '/api/me/preferences', { uiPreferences: userPrefs }); } catch (_) {}
                        switchSettingsTab('general');
                    });

                    document.getElementById('btn-save-general-settings')?.addEventListener('click', async () => {
                        userPrefs.minecraftNick = document.getElementById('gen-mc-nick').value.trim();
                        try {
                            await api('PUT', '/api/me/preferences', { uiPreferences: userPrefs });
                            me.uiPreferences = JSON.stringify(userPrefs);
                            showToast('Сохранено', 'Общие настройки профиля обновлены', 'success');
                        } catch (e) {
                            showToast('Ошибка', e.message, 'error');
                        }
                    });
                } else if (tabId === 'server') {
                    // TAB 2: СЕРВЕР И СЕТЬ (РЕАЛЬНЫЙ CONFIG.YML)
                    contentPane.innerHTML = `
                        <h4 style="color:var(--text-main); margin-bottom:14px;">ПАРАМЕТРЫ СЕРВЕРА И ВЕБ-ПАНЕЛИ</h4>

                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <div class="form-group">
                                <label>Порт веб-сервера (web.port)</label>
                                <input type="number" id="cfg-web-port" value="${serverConfig.webPort || 8080}" min="1" max="65535">
                                <div style="font-size:11.5px; color:var(--text-muted); margin-top:3px;">Порт, на котором работает веб-интерфейс панели</div>
                            </div>

                            <div class="form-group">
                                <label>Хост прослушивания (web.host)</label>
                                <input type="text" id="cfg-web-host" value="${esc(serverConfig.webHost || '0.0.0.0')}">
                                <div style="font-size:11.5px; color:var(--text-muted); margin-top:3px;">IP-адрес привязки (0.0.0.0 для всех сетевых интерфейсов)</div>
                            </div>

                            <div class="form-group">
                                <label>Время жизни сессии администратора (минуты)</label>
                                <input type="number" id="cfg-session-lifetime" value="${serverConfig.sessionLifetimeMinutes || 1440}" min="10" max="43200">
                                <div style="font-size:11.5px; color:var(--text-muted); margin-top:3px;">По истечении времени потребуется повторная авторизация</div>
                            </div>

                            <div class="form-group">
                                <label>Лимит хранения логов в БД (записей)</label>
                                <input type="number" id="cfg-keep-logs" value="${serverConfig.keepLogCount || 2000}" min="100" max="50000">
                                <div style="font-size:11.5px; color:var(--text-muted); margin-top:3px;">Количество записей журнала, сохраняемых в локальной базе данных</div>
                            </div>

                            <div class="form-group" style="margin-bottom:0;">
                                <label style="display:flex; align-items:center; gap:8px; cursor:pointer; font-size:13px; color:var(--text-main);">
                                    <input type="checkbox" id="cfg-debug-mode" ${serverConfig.debugMode ? 'checked' : ''}>
                                    <span>Включить подробный режим отладки в консоли сервера (debug-mode)</span>
                                </label>
                            </div>
                        </div>

                        <button type="button" class="primary btn-sm" id="btn-save-server-config" style="width:100%; display:flex; align-items:center; justify-content:center; gap:6px;">
                            ${renderSvgIcon('save', 'white', 14)} СОХРАНИТЬ НАСТРОЙКИ СЕРВЕРА
                        </button>
                    `;

                    document.getElementById('btn-save-server-config')?.addEventListener('click', async () => {
                        const port = parseInt(document.getElementById('cfg-web-port').value, 10) || 8080;
                        const host = document.getElementById('cfg-web-host').value.trim() || '0.0.0.0';
                        const sessionLifetime = parseInt(document.getElementById('cfg-session-lifetime').value, 10) || 1440;
                        const keepLogs = parseInt(document.getElementById('cfg-keep-logs').value, 10) || 2000;
                        const debugMode = document.getElementById('cfg-debug-mode').checked;

                        try {
                            const res = await api('POST', '/api/server/config', {
                                webPort: port,
                                webHost: host,
                                sessionLifetimeMinutes: sessionLifetime,
                                keepLogCount: keepLogs,
                                debugMode: debugMode
                            });
                            serverConfig = { ...serverConfig, ...res.config };
                            showToast('Сохранено', 'Конфигурация сервера успешно обновлена в config.yml', 'success');
                        } catch (e) {
                            showToast('Ошибка', e.message, 'error');
                        }
                    });
                } else if (tabId === 'security') {
                    // TAB 3: БЕЗОПАСНОСТЬ (СИСТЕМА + СМЕНА ПАРОЛЯ + 2FA)
                    contentPane.innerHTML = `
                        <h4 style="color:var(--text-main); margin-bottom:14px;">БЕЗОПАСНОСТЬ И АВТОРИЗАЦИЯ</h4>

                        <!-- System Security Rules -->
                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <b style="color:var(--text-main); font-size:13.5px;">Параметры безопасности панели</b>
                            <div style="font-size:12px; color:var(--text-muted); margin-bottom:12px;">Защита от подбора паролей и фиксация IP-адресов:</div>

                            <div class="form-group">
                                <label style="display:flex; align-items:center; gap:8px; cursor:pointer; font-size:13px; color:var(--text-main);">
                                    <input type="checkbox" id="cfg-strict-ip" ${serverConfig.strictIp ? 'checked' : ''}>
                                    <span>Строгая привязка сессий к IP (аннулировать сессию при смене адреса)</span>
                                </label>
                            </div>

                            <div style="display:grid; grid-template-columns:1fr 1fr 1fr; gap:12px;">
                                <div class="form-group" style="margin-bottom:0;">
                                    <label>Мин. длина пароля</label>
                                    <input type="number" id="cfg-min-pass" value="${serverConfig.minPasswordLength || 8}" min="6" max="32">
                                </div>
                                <div class="form-group" style="margin-bottom:0;">
                                    <label>Макс. попыток входа</label>
                                    <input type="number" id="cfg-max-attempts" value="${serverConfig.loginMaxAttempts || 5}" min="1" max="20">
                                </div>
                                <div class="form-group" style="margin-bottom:0;">
                                    <label>Блокировка (мин)</label>
                                    <input type="number" id="cfg-lockout-min" value="${serverConfig.loginLockoutMinutes || 15}" min="1" max="1440">
                                </div>
                            </div>

                            <button type="button" class="secondary btn-sm" id="btn-save-sec-rules" style="margin-top:14px; width:100%; display:flex; align-items:center; justify-content:center; gap:6px;">
                                ${renderSvgIcon('save', 'white', 14)} СОХРАНИТЬ ПРАВИЛА БЕЗОПАСНОСТИ
                            </button>
                        </div>

                        <!-- Sessions & 2FA Info Card -->
                        <div style="background:var(--card-inner-bg); padding:14px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <div style="display:flex; justify-content:space-between; align-items:center;">
                                <div>
                                    <b style="color:var(--text-main); font-size:13px;">Управление сессиями вашей учетной записи</b>
                                    <div style="font-size:11.5px; color:var(--text-muted);">Завершите активные сессии на всех остальных смартфонах и ПК</div>
                                </div>
                                <button type="button" class="danger btn-sm" id="btn-sec-terminate-other" style="display:flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon('ban', 'white', 12)} ЗАВЕРШИТЬ ДРУГИЕ
                                </button>
                            </div>
                            ${me.totpEnabled ? `
                                <button type="button" class="secondary btn-sm" id="btn-sec-regen-codes" style="width:100%; font-size:11px; margin-top:10px; display:flex; align-items:center; justify-content:center; gap:6px;">
                                    ${renderSvgIcon('refresh', 'gray', 12)} СГЕНЕРИРОВАТЬ НОВЫЕ РЕЗЕРВНЫЕ КОДЫ 2FA
                                </button>
                            ` : ''}
                        </div>

                        <!-- Password Change -->
                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border);">
                            <div style="font-weight:700; color:var(--text-main); margin-bottom:4px; font-size:13.5px;">СМЕНА ПАРОЛЯ УЧЁТНОЙ ЗАПИСИ</div>
                            <div style="font-size:11.5px; color:var(--yellow); margin-bottom:12px;">
                                Внимание: ввод неверного старого пароля приведёт к аннулированию сессии!
                            </div>

                            <div class="form-group">
                                <label>1. Текущий пароль *</label>
                                <input type="password" id="sec-old-pass" placeholder="••••••••" autocomplete="current-password">
                            </div>
                            <div class="form-group">
                                <label>2. Новый надёжный пароль (минимум ${serverConfig.minPasswordLength || 8} символов) *</label>
                                <input type="password" id="sec-new-pass" placeholder="Новый пароль" autocomplete="new-password">
                            </div>
                            <div class="form-group">
                                <label>3. Подтвердите новый пароль *</label>
                                <input type="password" id="sec-confirm-pass" placeholder="Повторите новый пароль" autocomplete="new-password">
                            </div>
                            ${me.totpEnabled ? `
                            <div class="form-group">
                                <label>4. 6-значный код 2FA подтверждения *</label>
                                <input type="text" id="sec-totp-code" placeholder="123456" maxlength="8" style="letter-spacing:0.2em; font-family:'JetBrains Mono';">
                            </div>
                            ` : ''}

                            <div id="sec-pass-err" class="error" style="display:none; margin-bottom:10px;"></div>
                            <button type="button" class="primary" id="btn-submit-change-pass" style="width:100%; display:flex; align-items:center; justify-content:center; gap:6px;">
                                ${renderSvgIcon('key', 'white', 14)} ИЗМЕНИТЬ ПАРОЛЬ
                            </button>
                        </div>
                    `;

                    document.getElementById('btn-save-sec-rules')?.addEventListener('click', async () => {
                        const strictIp = document.getElementById('cfg-strict-ip').checked;
                        const minLength = parseInt(document.getElementById('cfg-min-pass').value, 10) || 8;
                        const maxAttempts = parseInt(document.getElementById('cfg-max-attempts').value, 10) || 5;
                        const lockoutMin = parseInt(document.getElementById('cfg-lockout-min').value, 10) || 15;

                        try {
                            const res = await api('POST', '/api/server/config', {
                                strictIp,
                                minPasswordLength: minLength,
                                loginMaxAttempts: maxAttempts,
                                loginLockoutMinutes: lockoutMin
                            });
                            serverConfig = { ...serverConfig, ...res.config };
                            showToast('Сохранено', 'Правила безопасности обновлены в config.yml', 'success');
                        } catch (e) {
                            showToast('Ошибка', e.message, 'error');
                        }
                    });

                    document.getElementById('btn-sec-terminate-other')?.addEventListener('click', () => {
                        confirmAction('ЗАВЕРШЕНИЕ СЕССИЙ', 'Завершить все активные сессии на всех других устройствах?', async () => {
                            try {
                                await api('POST', '/api/me/sessions/other/terminate');
                                showToast('Готово', 'Все остальные сессии успешно завершены', 'info');
                            } catch (e) {
                                showToast('Ошибка', e.message, 'error');
                            }
                        }, 'ЗАВЕРШИТЬ');
                    });

                    document.getElementById('btn-sec-regen-codes')?.addEventListener('click', async () => {
                        confirmAction('РЕГЕНЕРАЦИЯ КОДОВ 2FA', 'Старые резервные коды станут недействительными. Будут сгенерированы 8 новых кодов.', async () => {
                            try {
                                const res = await api('POST', '/api/me/backup-codes/regenerate');
                                const codes = res.backupCodes || [];
                                alert('Новые резервные коды 2FA:\n\n' + codes.join('\n'));
                            } catch (e) {
                                showToast('Ошибка', e.message, 'error');
                            }
                        });
                    });

                    document.getElementById('btn-submit-change-pass')?.addEventListener('click', async () => {
                        const oldPassword = document.getElementById('sec-old-pass').value;
                        const newPassword = document.getElementById('sec-new-pass').value;
                        const confirmPass = document.getElementById('sec-confirm-pass').value;
                        const totpCode = document.getElementById('sec-totp-code')?.value.trim();
                        const err = document.getElementById('sec-pass-err');
                        err.style.display = 'none';

                        const minRequired = serverConfig.minPasswordLength || 8;
                        if (!oldPassword || !newPassword || !confirmPass) {
                            err.textContent = 'Заполните все обязательные поля';
                            err.style.display = 'block';
                            return;
                        }
                        if (newPassword.length < minRequired) {
                            err.textContent = `Новый пароль должен содержать минимум ${minRequired} символов`;
                            err.style.display = 'block';
                            return;
                        }
                        if (newPassword !== confirmPass) {
                            err.textContent = 'Новый пароль и подтверждение не совпадают';
                            err.style.display = 'block';
                            return;
                        }

                        try {
                            await api('POST', '/api/me/password', { oldPassword, newPassword, confirmPassword: confirmPass, totpCode });
                            showToast('Пароль обновлен', 'Ваш пароль успешно изменен', 'success');
                            closeModal();
                        } catch (e) {
                            err.textContent = e.message;
                            err.style.display = 'block';
                        }
                    });
                } else if (tabId === 'blacklist') {
                    // TAB 4: ЧЕРНЫЙ СПИСОК КОМАНД
                    let currentBlacklist = Array.isArray(serverConfig.commandBlacklist) ? [...serverConfig.commandBlacklist] : [];

                    const renderBlacklistUI = () => {
                        contentPane.innerHTML = `
                            <h4 style="color:var(--text-main); margin-bottom:14px;">ЧЕРНЫЙ СПИСОК КОМАНД ВЕБ-КОНСОЛИ</h4>
                            <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                                <div style="font-size:12.5px; color:var(--text-muted); margin-bottom:14px;">
                                    Команды, перечисленные ниже, полностью блокируются при попытке выполнить их через веб-терминал LoveWebAdmin для защиты целостности сервера.
                                </div>

                                <div style="display:flex; flex-wrap:wrap; gap:8px; margin-bottom:16px; min-height:42px; padding:10px; background:var(--bg-main); border-radius:var(--radius-sm); border:1px solid var(--border);">
                                    ${currentBlacklist.length === 0 ? `<div style="font-size:12px; color:var(--text-dim); font-style:italic;">Черный список пуст</div>` : ''}
                                    ${currentBlacklist.map((cmd, idx) => `
                                        <span class="badge red" style="display:inline-flex; align-items:center; gap:6px; font-size:12.5px; padding:4px 10px; border-radius:4px;">
                                            <code>/${esc(cmd)}</code>
                                            <button type="button" class="btn-remove-cmd" data-idx="${idx}" style="background:none; border:none; color:inherit; cursor:pointer; padding:0; line-height:1; display:inline-flex; align-items:center;">${renderSvgIcon('close', 'gray', 12)}</button>
                                        </span>
                                    `).join('')}
                                </div>

                                <div style="display:flex; gap:10px; align-items:center;">
                                    <input type="text" id="input-new-blacklist-cmd" placeholder="Например: restart, stop, op..." style="flex:1;">
                                    <button type="button" class="secondary" id="btn-add-blacklist-cmd" style="display:inline-flex; align-items:center; gap:6px;">
                                        ${renderSvgIcon('plus', 'violet', 14)} ДОБАВИТЬ
                                    </button>
                                </div>
                            </div>

                            <button type="button" class="primary btn-sm" id="btn-save-blacklist" style="width:100%; display:flex; align-items:center; justify-content:center; gap:6px;">
                                ${renderSvgIcon('save', 'white', 14)} СОХРАНИТЬ ЧЕРНЫЙ СПИСОК
                            </button>
                        `;

                        document.querySelectorAll('.btn-remove-cmd').forEach(btn => {
                            btn.addEventListener('click', () => {
                                const idx = parseInt(btn.dataset.idx, 10);
                                currentBlacklist.splice(idx, 1);
                                renderBlacklistUI();
                            });
                        });

                        const addCmd = () => {
                            const input = document.getElementById('input-new-blacklist-cmd');
                            let val = input.value.trim().toLowerCase();
                            if (val.startsWith('/')) val = val.substring(1).trim();
                            if (val && !currentBlacklist.includes(val)) {
                                currentBlacklist.push(val);
                                renderBlacklistUI();
                            }
                        };

                        document.getElementById('btn-add-blacklist-cmd')?.addEventListener('click', addCmd);
                        document.getElementById('input-new-blacklist-cmd')?.addEventListener('keydown', (e) => {
                            if (e.key === 'Enter') { e.preventDefault(); addCmd(); }
                        });

                        document.getElementById('btn-save-blacklist')?.addEventListener('click', async () => {
                            try {
                                const res = await api('POST', '/api/server/config', { commandBlacklist: currentBlacklist });
                                serverConfig.commandBlacklist = [...currentBlacklist];
                                showToast('Сохранено', 'Черный список команд успешно записан в config.yml', 'success');
                            } catch (e) {
                                showToast('Ошибка', e.message, 'error');
                            }
                        });
                    };

                    renderBlacklistUI();
                } else if (tabId === 'webhooks') {
                    // TAB 5: ВЕБХУКИ И СВЯЗЬ (DISCORD + TELEGRAM)
                    const discordId = userPrefs.discordId || '';
                    const notifyReports = userPrefs.discordNotifyReports !== false;
                    const notifyTickets = userPrefs.discordNotifyTickets !== false;

                    contentPane.innerHTML = `
                        <h4 style="color:var(--text-main); margin-bottom:14px;">ОПОВЕЩЕНИЯ, DISCORD И TELEGRAM ВЕБХУКИ</h4>

                        <!-- Server Global Webhooks Card -->
                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;">
                                <div>
                                    <b style="color:var(--text-main); font-size:13.5px;">Глобальные вебхуки сервера</b>
                                    <div style="font-size:11.5px; color:var(--text-muted); margin-top:2px;">Отправка событий наказаний, входов и алертов в каналы</div>
                                </div>
                                <label style="display:flex; align-items:center; gap:8px; cursor:pointer;">
                                    <input type="checkbox" id="cfg-webhooks-enabled" ${serverConfig.webhooksEnabled ? 'checked' : ''}>
                                    <span style="font-size:12.5px; color:var(--text-main); font-weight:700;">ВКЛЮЧЕНЫ</span>
                                </label>
                            </div>

                            <div class="form-group">
                                <label>Discord Webhook URL (webhooks.discord)</label>
                                <input type="text" id="cfg-discord-webhook" value="${esc(serverConfig.discordWebhookUrl || '')}" placeholder="https://discord.com/api/webhooks/...">
                                <div style="font-size:11.5px; color:var(--text-muted); margin-top:3px;">Канал Discord для серверных оповещений и логов</div>
                            </div>

                            <div style="display:grid; grid-template-columns:1.5fr 1fr; gap:12px;">
                                <div class="form-group" style="margin-bottom:0;">
                                    <label>Telegram Bot Token</label>
                                    <input type="text" id="cfg-tg-token" value="${esc(serverConfig.telegramBotToken || '')}" placeholder="123456789:ABCdefGHI...">
                                </div>
                                <div class="form-group" style="margin-bottom:0;">
                                    <label>Telegram Chat ID</label>
                                    <input type="text" id="cfg-tg-chat" value="${esc(serverConfig.telegramChatId || '')}" placeholder="-100123456789">
                                </div>
                            </div>
                        </div>

                        <!-- Personal Staff Notifications -->
                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <div style="display:flex; align-items:center; gap:8px; margin-bottom:6px;">
                                <span style="font-size:16px;">${renderSvgIcon('bell', 'violet', 16)}</span>
                                <h5 style="font-size:13.5px; color:var(--text-main); margin:0;">ЛИЧНЫЕ ОПОВЕЩЕНИЯ В ЛС DISCORD</h5>
                            </div>
                            <div style="font-size:12px; color:var(--text-muted); margin-bottom:12px;">
                                Моментальные личные оповещения от бота при поступлении новых жалоб игроков или тикетов.
                            </div>

                            <div class="form-group">
                                <label>Ваш Discord User ID (числовой снепшот)</label>
                                <input type="text" id="pref-discord-id" value="${esc(discordId)}" placeholder="например: 345678901234567890">
                            </div>

                            <div style="display:flex; flex-direction:column; gap:8px;">
                                <label style="display:flex; align-items:center; gap:8px; cursor:pointer; font-size:12.5px; color:var(--text-main);">
                                    <input type="checkbox" id="pref-notify-reports" ${notifyReports ? 'checked' : ''}>
                                    <span>Оповещать в ЛС о новых жалобах игроков (Reports)</span>
                                </label>
                                <label style="display:flex; align-items:center; gap:8px; cursor:pointer; font-size:12.5px; color:var(--text-main);">
                                    <input type="checkbox" id="pref-notify-tickets" ${notifyTickets ? 'checked' : ''}>
                                    <span>Оповещать в ЛС о новых апелляциях банов (Tickets)</span>
                                </label>
                            </div>
                        </div>

                        <button type="button" class="primary btn-sm" id="btn-save-webhooks" style="width:100%; display:flex; align-items:center; justify-content:center; gap:6px;">
                            ${renderSvgIcon('save', 'white', 14)} СОХРАНИТЬ ВЕБХУКИ И ОПОВЕЩЕНИЯ
                        </button>
                    `;

                    document.getElementById('btn-save-webhooks')?.addEventListener('click', async () => {
                        const webhooksEnabled = document.getElementById('cfg-webhooks-enabled').checked;
                        const discordWebhook = document.getElementById('cfg-discord-webhook').value.trim();
                        const tgToken = document.getElementById('cfg-tg-token').value.trim();
                        const tgChat = document.getElementById('cfg-tg-chat').value.trim();

                        userPrefs.discordId = document.getElementById('pref-discord-id').value.trim();
                        userPrefs.discordNotifyReports = document.getElementById('pref-notify-reports').checked;
                        userPrefs.discordNotifyTickets = document.getElementById('pref-notify-tickets').checked;

                        try {
                            const [resCfg] = await Promise.all([
                                api('POST', '/api/server/config', {
                                    webhooksEnabled,
                                    discordWebhookUrl: discordWebhook,
                                    telegramBotToken: tgToken,
                                    telegramChatId: tgChat
                                }),
                                api('PUT', '/api/me/preferences', { uiPreferences: userPrefs })
                            ]);
                            serverConfig = { ...serverConfig, ...resCfg.config };
                            me.uiPreferences = JSON.stringify(userPrefs);
                            showToast('Сохранено', 'Настройки вебхуков и оповещений успешно обновлены', 'success');
                        } catch (e) {
                            showToast('Ошибка', e.message, 'error');
                        }
                    });
                } else if (tabId === 'maintenance') {
                    // TAB 6: ТЕХНИЧЕСКИЕ РАБОТЫ
                    let maintStatus = { enabled: false, message: 'Ведутся технические работы' };
                    try {
                        maintStatus = await api('GET', '/api/server/maintenance');
                    } catch (_) {}

                    contentPane.innerHTML = `
                        <h4 style="color:var(--text-main); margin-bottom:14px;">РЕЖИМ ТЕХНИЧЕСКИХ РАБОТ ПАНЕЛИ</h4>
                        <div style="background:var(--card-inner-bg); padding:16px; border-radius:var(--radius-sm); border:1px solid var(--border); margin-bottom:16px;">
                            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
                                <div>
                                    <b style="color:var(--text-main); font-size:14px;">Глобальный режим обслуживания веб-панели</b>
                                    <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">
                                        Когда включён — вход разрешён только Управляющему и сотрудникам с правом «Обход тех. работ».
                                    </div>
                                </div>
                                <label class="switch" style="position:relative; display:inline-block; width:44px; height:24px;">
                                    <input type="checkbox" id="settings-maint-toggle" ${maintStatus.enabled ? 'checked' : ''}>
                                    <span style="position:absolute; cursor:pointer; top:0; left:0; right:0; bottom:0; background:${maintStatus.enabled ? 'var(--accent)' : 'var(--border)'}; border-radius:24px; transition:.3s;"></span>
                                </label>
                            </div>

                            <div class="form-group" style="margin-top:14px;">
                                <label>Сообщение для заблокированных пользователей</label>
                                <input type="text" id="settings-maint-msg" value="${esc(maintStatus.message || 'Ведутся технические работы')}" placeholder="Сообщение...">
                            </div>

                            <button type="button" class="primary btn-sm" id="btn-save-maintenance-settings" style="width:100%; margin-top:6px; display:flex; align-items:center; justify-content:center; gap:6px;">
                                ${renderSvgIcon('wrench', 'white', 14)} ПРИМЕНИТЬ РЕЖИМ ТЕХНИЧЕСКИХ РАБОТ
                            </button>
                        </div>
                    `;

                    document.getElementById('btn-save-maintenance-settings')?.addEventListener('click', async () => {
                        const enabled = document.getElementById('settings-maint-toggle').checked;
                        const message = document.getElementById('settings-maint-msg').value.trim();

                        try {
                            await api('POST', '/api/server/maintenance', { enabled, message });
                            showToast('Технические работы', enabled ? 'Режим тех. работ ВКЛЮЧЁН' : 'Режим тех. работ ОТКЛЮЧЁН', enabled ? 'warning' : 'success');
                            switchSettingsTab('maintenance');
                        } catch (e) {
                            showToast('Ошибка', e.message, 'error');
                        }
                    });
                } else if (tabId === 'danger') {
                    // TAB 7: ОПАСНЫЕ ЗОНЫ
                    contentPane.innerHTML = `
                        <h4 style="color:var(--red); margin-bottom:14px;">ОПАСНЫЕ ЗОНЫ И СБРОС</h4>
                        <div style="display:flex; flex-direction:column; gap:14px;">
                            <div style="background:rgba(239, 68, 68, 0.08); border:1px solid rgba(239, 68, 68, 0.3); border-radius:var(--radius-sm); padding:16px;">
                                <b style="color:var(--text-main);">Принудительный выход всех сотрудников</b>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px; margin-bottom:12px;">
                                    Немедленно аннулирует все выданные сессии и токены всех пользователей, кроме вашей текущей сессии.
                                </div>
                                <button type="button" class="danger btn-sm" id="btn-danger-term-all" style="display:flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon('ban', 'white', 14)} ЗАВЕРШИТЬ ВСЕ СЕССИИ ПЕРСОНАЛА
                                </button>
                            </div>

                            <div style="background:var(--card-inner-bg); border:1px solid var(--border); border-radius:var(--radius-sm); padding:16px;">
                                <b style="color:var(--text-main);">Перезагрузка конфигурации плагина</b>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px; margin-bottom:12px;">
                                    Выполняет команду /lovewebadmin reload на сервере без перезапуска ядра Minecraft.
                                </div>
                                <button type="button" class="secondary btn-sm" id="btn-danger-reload-plugin" style="display:flex; align-items:center; gap:6px;">
                                    ${renderSvgIcon('refresh', 'gray', 14)} ПЕРЕЗАГРУЗИТЬ LOVEWEBADMIN
                                </button>
                            </div>
                        </div>
                    `;

                    document.getElementById('btn-danger-term-all')?.addEventListener('click', () => {
                        confirmAction('СБРОС ВСЕХ СЕССИЙ', 'Вы действительно хотите принудительно разлогинить всех сотрудников на всех устройствах?', async () => {
                            try {
                                await api('POST', '/api/admins/terminate-all');
                                showToast('Готово', 'Все сессии персонала успешно завершены', 'info');
                            } catch (e) {
                                showToast('Ошибка', e.message, 'error');
                            }
                        }, 'ЗАВЕРШИТЬ ВСЕ');
                    });

                    document.getElementById('btn-danger-reload-plugin')?.addEventListener('click', async () => {
                        try {
                            await api('POST', '/api/command', { command: 'lovewebadmin reload' });
                            showToast('Перезагрузка', 'Конфигурация плагина перезагружена', 'success');
                        } catch (e) {
                            showToast('Ошибка', e.message, 'error');
                        }
                    });
                }
            };

            // Sidebar tab click handler
            document.querySelectorAll('.settings-tab-btn').forEach(btn => {
                btn.addEventListener('click', () => {
                    const tid = btn.dataset.tab;
                    if (tid) switchSettingsTab(tid);
                });
            });

            switchSettingsTab(currentTab);
        }, 'modal-lg');
    }

    // Backwards-compatible alias for user profile trigger
    function openUserProfileModal() {
        openAdminSettingsModal('general');
    }

    // Modal: Quick Kick Modal
    window.openQuickKickModal = function() {
        openModal(`
            <div class="modal-header">
                <h3>КИКНУТЬ ИГРОКА С СЕРВЕРА</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
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
                    recordShiftAction(`Кикнул LoveWebAdmin`);
                    showToast('Кик', `Игрок LoveWebAdmin кикнут с сервера`, 'warning');
                    closeModal();
                } catch (e) {
                    if (e.message && e.message.includes('не в сети')) {
                        showToast('Игрок оффлайн', `Игрок LoveWebAdmin не в сети или не найден`, 'warning');
                    } else {
                        showToast('Ошибка', e.message || 'Не удалось кикнуть игрока', 'danger');
                    }
                }
            });
        });
    };

    // Modal: Quick Teleport Spawn
    window.openQuickTeleportModal = function() {
        openModal(`
            <div class="modal-header">
                <h3>ТЕЛЕПОРТАЦИЯ НА СПАВН</h3>
                <button type="button" class="close-btn" data-modal-close="true">${renderSvgIcon('close', 'gray', 16)}</button>
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
                    recordShiftAction(`Телепортировал на спавн LoveWebAdmin`);
                    showToast('Телепортация', `Игрок LoveWebAdmin отправлен на спавн`, 'info');
                    closeModal();
                } catch (e) {
                    if (e.message && e.message.includes('не в сети')) {
                        showToast('Игрок оффлайн', `Игрок LoveWebAdmin не в сети или не найден`, 'warning');
                    } else {
                        showToast('Ошибка', e.message || 'Не удалось телепортировать игрока', 'danger');
                    }
                }
            });
        });
    };

    // Modal: Quick Vanish Modal
    window.openQuickVanishModal = function() {
        openModal(`
            <div class="modal-header">
                <h3>РЕЖИМ НЕВИДИМОСТИ (VANISH)</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon('close', 'gray', 16)}</button>
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
                    recordShiftAction(`Переключил Vanish для LoveWebAdmin`);
                    showToast('Vanish', `Режим скрытности переключен для LoveWebAdmin`, 'info');
                    closeModal();
                } catch (e) {
                    if (e.message && e.message.includes('не в сети')) {
                        showToast('Игрок оффлайн', `Игрок LoveWebAdmin не в сети или не найден`, 'warning');
                    } else {
                        showToast('Ошибка', e.message || 'Не удалось переключить Vanish', 'danger');
                    }
                }
            });
        });
    };

    // Modal: Quick Mute Modal
    window.openQuickMuteModal = function() {
        openModal(`
            <div class="modal-header">
                <h3>ЗАГЛУШИТЬ ИГРОКА (MUTE)</h3>
                <button type="button" class="close-btn" onclick="window.closeCurrentModal()">${renderSvgIcon('close', 'gray', 16)}</button>
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
                    recordShiftAction(`Заглушил LoveWebAdmin на ${duration}`);
                    showToast('Мут чата', `Игрок LoveWebAdmin заглушен на ${duration}`, 'warning');
                    closeModal();
                } catch (e) {
                    if (e.message && e.message.includes('не в сети')) {
                        showToast('Игрок оффлайн', `Игрок LoveWebAdmin не в сети или не найден`, 'warning');
                    } else {
                        showToast('Ошибка', e.message || 'Не удалось выдать мут', 'danger');
                    }
                }
            });
        });
    };


    // Start App
    boot();
})();
