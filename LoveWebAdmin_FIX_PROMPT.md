# Промпт: полный аудит и исправление LoveWebAdmin

Репозиторий: https://github.com/mvr347/LoveWebAdmin  
Стек: Java (Paper plugin) + встроенный Jetty + SPA (`src/main/resources/web/app.js`).

Твоя задача — исправить баги и доработать проект по пунктам ниже. Работай по коду репозитория, не выдумывай несуществующие файлы. После изменений проект должен собираться (`mvn package`) и панели должны быть кликабельны.

---

## 1. КРИТИЧНО: мёртвые кнопки UI (CSP)

### Проблема
В `src/main/java/me/lovelace/loveWebAdmin/web/CorsFilter.java` CSP:

```
script-src 'self'
```

Во фронте (`src/main/resources/web/app.js`) сотни кнопок через inline-обработчики:

```html
<button onclick="window.openQuickBanModal('')">...</button>
<button onclick="window.viewPlayerProfile('Nick')">...</button>
```

Без `'unsafe-inline'` браузер блокирует все `onclick` → «много кнопок не работает».

### Исправление (минимум)
В `CorsFilter` заменить CSP на:

```
default-src 'self';
script-src 'self' 'unsafe-inline';
style-src 'self' 'unsafe-inline' https://fonts.googleapis.com;
font-src 'self' https://fonts.gstatic.com;
img-src 'self' data: https:;
connect-src 'self';
frame-ancestors 'none'
```

Дополнительно:
- в `Access-Control-Allow-Headers` добавить `X-API-Key`;
- `img-src` с `https:` нужен для аватаров (mc-heads.net).

### Исправление (правильно, позже)
Постепенно убрать все `onclick="..."` из генерируемого HTML в `app.js`, заменить на делегирование:

```js
document.addEventListener('click', (e) => {
  const el = e.target.closest('[data-action]');
  if (!el) return;
  // switch(el.dataset.action) { ... }
});
```

После этого можно вернуть жёсткий CSP без `'unsafe-inline'`.

Файл для замены: `src/main/java/me/lovelace/loveWebAdmin/web/CorsFilter.java`.

---

## 2. Несовпадение HTTP-метода: preferences

### Проблема
- Фронт (`app.js`): `api('POST', '/api/me/preferences', { uiPreferences: ... })`
- Бэкенд (`ApiAuthHandler`): обработка только в `doPut` → `handleUpdatePreferences`

Кнопка сохранения настроек всегда ломается (405/404).

### Исправление
Либо:
- A) во фронте сменить на `api('PUT', '/api/me/preferences', ...)`,  
либо
- B) в `ApiAuthHandler.doPost` добавить ветку `/preferences` → `handleUpdatePreferences`.

Предпочтительно A (REST-стиль) + на бэке оставить PUT.

---

## 3. Версии и документация

### Проблемы
| Место | Сейчас | Проблема |
|-------|--------|----------|
| `pom.xml` | `java.version` = 25, paper-api `26.2.build.100-stable` | Java 25 / Paper 26   |
| `plugin.yml` | `api-version: '26.2'` | То же |
| `README.md` | Java 21, Paper 1.21.11 | Не совпадает с pom  | 

<groupId>io.papermc.paper</groupId>
            <artifactId>paper-api</artifactId>
            <version>26.2.build.100-stable</version>
            <scope>provided</scope> - вот исправление, заодно такой же исправь и в Vesuvio

### Исправление
1. Синхронизировать Java / Paper API / `api-version` на реальные стабильные версии проекта.
2. Обновить README под фактический `config.yml` (убрать или добавить недостающие опции: SSL, websocket, monitoring и т.д. — сейчас README врёт).
3. Дефолт `web.host` лучше `127.0.0.1`, а не `0.0.0.0` (или оставить с жирным warning, как сейчас).

---

## 4. Безопасность и политика паролей

### Проблемы
- В `AdminManager` минимум пароля **4 символа** — слишком слабо для веб-админки.
- `debug-mode: true` полностью отключает 2FA (онбординг, логин, verify). Опасно на проде.

### Исправление
1. Минимум пароля: 10–12 символов (желательно + проверка сложности).
2. В README и при старте плагина явно предупреждать: `debug-mode` только для разработки.
3. Опционально: при `debug-mode: true` писать в лог WARNING каждые N минут.

Хеширование (BCrypt cost 12) и TOTP — в порядке, не ломать.

---

## 5. Backend: действия над игроками

Файл: `ApiPlayersHandler`.

### Поведение
- `/api/players/{name}/action` работает **только для онлайн**-игроков (`getPlayerExact` + isOnline).
- Действия: heal, feed, gamemode, teleport_spawn, freeze, unfreeze, kick, remove_item, clear_inventory, kill, message, mute, vanish.
- `mute` / `vanish` идут через `Bukkit.dispatchCommand` (`mute`, `v`) — зависят от внешних плагинов.

### Улучшения
1. Во фронте при 404 «не в сети» показывать понятный тост, а не голый alert/тишина.
2. Если mute/vanish-команд нет на сервере — либо soft-fail с сообщением, либо конфигурируемые команды в `config.yml`.
3. Не смешивать кик оффлайн (невозможен) с баном (другой эндпоинт `/api/bans`).

---

## 6. Архитектура и поддержка

### Проблемы
- `DatabaseManager.java` ~145 КБ — монолит (схема, миграции, CRUD всего).
- Нет unit/integration-тестов на auth, 2FA, permissions, bans.
- README описывает функции, которых нет в актуальном коде/конфиге.

### Улучшения (не блокеры)
1. Разбить `DatabaseManager` на репозитории: Admin, Session, Ban, Report, Economy…
2. Добавить хотя бы тесты на:
   - login / 2FA / backup codes
   - requirePermission
   - password hash/verify
3. Привести README к реальности.

SQL в основном через `PreparedStatement` — SQL-injection в порядке, не ломать.

---

## 7. Фронтенд (`app.js`)

### Что проверить после фикса CSP
- Дашборд: быстрые действия (бан, кик, freeze, tp, clear chat, lockdown).
- Досье игрока: кнопки действий, заметки, альты.
- Наказания: баны, репорты accept/reject, апелляции.
- Сервер: whitelist/ops, консоль команд.
- Админы/роли: смена роли, сброс пароля, создание роли.
- Профиль: смена пароля, backup codes, terminate sessions, preferences.

### Техдолг
- Много дублирования (`viewBanDetails`, `openScreenshotLightbox` объявлены дважды).
- Смешение `addEventListener` и `onclick` — после фикса CSP приоритет: делегирование через `data-action`.

---

## 8. Порядок работ (приоритет)

1. **CSP** — `CorsFilter`: `'unsafe-inline'` + `img-src https:` → кнопки оживают.
2. **Preferences** — POST↔PUT согласовать фронт и бэк.
3. **Версии** — pom / plugin.yml / README синхронизировать.
4. **Пароли** — минимум 10+ символов.
5. **UX ошибок** — тосты вместо молчаливых fail / сырых alert.
6. (Опционально) рефакторинг DatabaseManager, тесты, отказ от inline onclick.

---

## 9. Критерии готовности

- [ ] `mvn package` успешно.
- [ ] После входа все основные кнопки дашборда/досье/банов кликабельны (нет CSP errors в Console).
- [ ] Сохранение preferences не даёт 405.
- [ ] README не противоречит `config.yml` и реальным версиям.
- [ ] Слабые пароли (4 символа) отклоняются.
- [ ] При недостатке прав / оффлайн-игроке UI показывает понятное сообщение.

---

## 10. Контекст для агента

- Не меняй публичный API без необходимости.
- Не удаляй 2FA / BCrypt / rate-limit логина.
- Soft-depend: LuckPerms, Vesuvio, LoveCore — не делай hard-depend.
- Язык UI и логов — русский; код/идентификаторы — как в проекте.
- Коммиты/PR: осмысленные сообщения на русском или английском, по стилю репо.

Начни с пункта 1 (CorsFilter), затем 2 (preferences), затем остальное по приоритету.
