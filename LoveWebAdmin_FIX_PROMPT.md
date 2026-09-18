# Промпт: доработка фронта и оставшихся фич LoveWebAdmin

Репозиторий: https://github.com/mvr347/LoveWebAdmin  
Стек: Java (Paper plugin) + Jetty + SPA (`src/main/resources/web/app.js`, ~8k строк).

## Контекст: что УЖЕ сделано на бэкенде (не ломать)

### API приглашений
- `GET    /api/admins/invites` — список PENDING (нужен `MANAGE_ADMINS`)
- `POST   /api/admins/invites` — тело:
  ```json
  {
    "username": "Steve",
    "roleId": 4,
    "probationDays": 14
  }
Ответ:
JSON{
  "id": 1,
  "code": "LWA-A1B2C3D4",
  "username": "Steve",
  "roleId": 4,
  "roleExpiresAt": 1730000000,
  "expiresAt": 1730600000
}

DELETE /api/admins/invites/{id} — отмена кода

Регистрация по коду

POST /api/auth/validate-inviteJSON{ "code": "LWA-A1B2C3D4" }→ { "username", "roleId", "roleName", "roleExpiresAt" }
POST /api/auth/registerJSON{
  "inviteCode": "LWA-A1B2C3D4",
  "password": "Str0ng!Pass",
  "totpSecret": "BASE32...",
  "totpCode": "123456"
}→ { "status": "ACTIVE", "backupCodes": [...] }
Без inviteCode — ошибка 400.

Роли (пресеты)



































namecategorysortOrderправаУправляющийOWNER10всеАдминистраторADMIN20расширенныеМодераторMOD30модерацияИспытательный срокPROBATION40только VIEW_*
GET /api/roles отдаёт: id, name, description, category, sortOrder, color, permissions, isOwner, lpGroup.

Сортировка уже ORDER BY sort_order ASC.
Пароли
PasswordUtils.validateStrength: мин. 10, буква + цифра + спецсимвол.

Фронт должен показывать те же правила до отправки.
Прочее

БД: plugins/LoveWebAdmin/lovewebadmin.db
/lovewebadmin → только reload; /бан /репорт /разбан живы
Причины банов: bans_reasons.yml (поле category)
Смена роли логируется в веб-журнал строкой Смена роли: Nick | A → B
Owner не может менять себе роль
TOTP: replay одного кода в окне 90с отклонён
Истёкший испытательный срок → notification + uiPreferences.pendingRoleReview


ЗАДАЧА 1. Секция «Персонал» (вместо «Администраторы и роли»)
Навигация
В app.js найти пункт меню с id: 'admins' / label «Администраторы и роли».
Заменить на:
JavaScript{
  id: 'staff',
  label: 'Персонал',
  icon: 'users', // или существующая иконка
  permission: 'MANAGE_ADMINS' // виден только с правом / owner
}
В navigateTo / renderContent:
JavaScriptcase 'staff': renderStaffView(); break;
// удалить или алиас: case 'admins': renderStaffView(); break;
Структура UI: два подокна
JavaScriptasync function renderStaffView() {
  const area = document.getElementById('content-area');
  let sub = 'personnel'; // personnel | roles

  area.innerHTML = `
    <div class="view-header">
      <div class="view-title-block">
        <h2>ПЕРСОНАЛ</h2>
        <p>Сотрудники, приглашения и роли веб-панели</p>
      </div>
    </div>
    <div class="filter-tags" id="staff-sub-tabs">
      <button type="button" class="filter-tag-btn active" data-sub="personnel">Персонал</button>
      <button type="button" class="filter-tag-btn" data-sub="roles">Роли</button>
    </div>
    <div id="staff-tab-body"></div>
  `;

  const mount = () => {
    if (sub === 'personnel') renderPersonnelTab();
    else renderRolesTab();
  };
  document.getElementById('staff-sub-tabs').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-sub]');
    if (!btn) return;
    sub = btn.dataset.sub;
    document.querySelectorAll('#staff-sub-tabs .filter-tag-btn')
      .forEach(b => b.classList.toggle('active', b.dataset.sub === sub));
    mount();
  });
  mount();
}
Вкладка «Персонал»

Фильтры:
active — сотрудники status === 'ACTIVE'
invites — pending invites из GET /api/admins/invites
review — у кого uiPreferences.pendingRoleReview === true (истёк стаж)

Кнопка «+ Добавить сотрудника» → wizard из 2 шагов:

Шаг 1
HTML<input id="invite-username" placeholder="Никнейм Minecraft" autocomplete="username">
<select id="invite-role"><!-- роли кроме OWNER --></select>
<select id="invite-probation">
  <option value="0">Без срока</option>
  <option value="7">7 дней (испытательный)</option>
  <option value="14">14 дней</option>
  <option value="30">30 дней</option>
</select>
<button id="invite-next">Далее</button>
Шаг 2 — после POST /api/admins/invites:
HTML<div class="invite-code-box">
  <div class="code-label">Код для сотрудника</div>
  <div class="code-value" id="invite-code-display">LWA-A1B2C3D4</div>
  <button id="copy-invite-code">Скопировать</button>
  <p>Передайте код лично. Сотрудник вводит его на экране «Регистрация».</p>
  <button id="cancel-invite" class="danger">Отменить приглашение</button>
</div>
Отмена:
JavaScriptawait api('DELETE', `/api/admins/invites/${inviteId}`);

Таблица активных: ник, роль (бейдж цвета роли), 2FA, срок роли, последний вход, действия.
Для себя, если me.isOwner или роль OWNER — скрыть «Сменить роль» / редактирование прав.
Смена роли → PUT /api/admins/{id}/role + опционально expiresAt / probationDays.
В журнале уже пишется аудит — отдельно UI истории не нужен, фильтр журнала по тексту Смена роли.

Таблица приглашений: ник, роль, код (маскировать LWA-••••C3D4, показать по клику), кем создан, истекает, кнопка «Отменить».

Вкладка «Роли»

Список из GET /api/roles, сгруппировать по category:textOWNER → ADMIN → MOD → PROBATION → CUSTOM
Карточка роли:text[цвет] Название
description (не дублировать name!)
N прав · LP: group
[Редактировать] [Удалить]  // удалить нельзя если isOwner или есть сотрудники
Создание роли: name, description (отдельное поле), color, matrix permissions, lpGroup.JavaScriptawait api('POST', '/api/roles', {
  name, description, color, lpGroup, permissions: [...]
});
Не показывать description === name; если description пустой — серый текст «Без описания».


ЗАДАЧА 2. Регистрация по коду (экран входа)
Убрать старый flow «Подать заявку / PENDING_APPROVAL».
Экран логина
Ссылка: «Регистрация по приглашению» → renderInviteRegister().
Шаг A — код
HTML<form id="invite-code-form">
  <input id="reg-invite-code" placeholder="LWA-XXXXXXXX"
         autocomplete="one-time-code" style="text-transform:uppercase">
  <button type="submit">Проверить код</button>
</form>
JavaScriptconst res = await api('POST', '/api/auth/validate-invite', {
  code: code.trim().toUpperCase()
});
// res.username, res.roleName → шаг B
Шаг B — пароль + 2FA
HTML<p>Аккаунт: <b>${esc(username)}</b> · Роль: ${esc(roleName)}</p>

<input type="password" id="reg-password"
       autocomplete="new-password"
       passwordrules="minlength: 10; required: lower; required: upper; required: digit; required: special;">
<input type="password" id="reg-password2" autocomplete="new-password">

<!-- индикатор силы пароля: длина / буква / цифра / спецсимвол -->

<!-- 2FA: вкладки QR | Секретный ключ -->
<div id="reg-qrcode-box"></div>
<!-- секрет скрыт до hover/click — см. Задача 3 -->

<form id="reg-2fa-form">
  <input id="reg-totp" inputmode="numeric" autocomplete="one-time-code"
         placeholder="000-000" maxlength="7">
  <button type="submit">Завершить регистрацию</button>
</form>
Перед 2FA: GET /api/auth/totp-setup?username=... → { secret, otpUrl }.
Сабмит:
JavaScriptawait api('POST', '/api/auth/register', {
  inviteCode, password, totpSecret, totpCode: digitsOnly
});
// показать backup codes → «Войти»
Валидация пароля на клиенте (зеркало бэка):
JavaScriptfunction validatePasswordClient(p) {
  if (!p || p.length < 10) return 'Минимум 10 символов';
  if (!/[A-Za-zА-Яа-я]/.test(p)) return 'Нужна хотя бы одна буква';
  if (!/\d/.test(p)) return 'Нужна хотя бы одна цифра';
  if (!/[^A-Za-zА-Яа-я0-9]/.test(p)) return 'Нужен спецсимвол';
  return null;
}

ЗАДАЧА 3. 2FA UX
Файл: app.js → renderTotpQrCode, экраны setup/login 2FA.
3.1 Секретный ключ

По умолчанию: •••• •••• •••• •••• (или разбивка Base32 по 4)
Показать по клику или hover (desktop)
Кнопка «Скопировать» копирует реальный secret
Пример:

JavaScriptfunction renderSecretMasked(el, secret) {
  const groups = secret.match(/.{1,4}/g)?.join(' ') || secret;
  el.innerHTML = `<span class="totp-secret-mask" title="Нажмите, чтобы показать">•••• •••• •••• ••••</span>`;
  const mask = el.querySelector('.totp-secret-mask');
  let shown = false;
  const show = () => { mask.textContent = groups; shown = true; };
  const hide = () => { mask.textContent = '•••• •••• •••• ••••'; shown = false; };
  mask.addEventListener('click', () => shown ? hide() : show());
  mask.addEventListener('mouseenter', show);
  mask.addEventListener('mouseleave', hide);
}
3.2 Ввод кода XXX-XXX
JavaScriptfunction bindTotpInput(input) {
  input.addEventListener('input', () => {
    let d = input.value.replace(/\D/g, '').slice(0, 6);
    input.value = d.length > 3 ? d.slice(0,3) + '-' + d.slice(3) : d;
  });
}
// при отправке: input.value.replace(/\D/g, '')
Стили: letter-spacing, text-align: center, font-family: JetBrains Mono, крупный кегль.
3.3 QR

Убрать кнопку «Открыть Authenticator» (если есть).
Клик по контейнеру QR → перезапрос totp-setup и перерисовка (только до успешной привязки):

JavaScriptqrBox.addEventListener('click', async () => {
  const res = await api('GET', `/api/auth/totp-setup?username=${encodeURIComponent(username)}`);
  currentSecret = res.secret;
  renderTotpQrCode(qrBox, res.otpUrl);
  renderSecretMasked(secretEl, res.secret);
});

correctLevel: QRCode.CorrectLevel.H (если библиотека поддерживает).

3.4 Google Password Manager
На всех password-полях регистрации/смены:
HTMLautocomplete="new-password"
На логине:
HTMLautocomplete="current-password"
username: autocomplete="username".

ЗАДАЧА 4. Античит — режим «Наблюдение»
Сейчас: вкладка «Разбивка по игрокам / Наблюдение» — список.

Нужно: при клике «Наблюдение» открывать полноэкранную/панельную карточку, не теряя контекст.
UX
text┌─────────────────────────────────────────────┐
│ ← Выйти из наблюдения                       │
│ [голова] Steve   VL: 42   Подозрение: ДА    │
│ [Freeze] [Черновик бана] [Снять наблюдение] │
├──────────────┬──────────────────────────────┤
│ Поток флагов │ Полезная инфа                │
│ (live 3s)    │ IP, клиент, first/last join  │
│ check, VL+   │ альты (кратко), заметки      │
│ time         │ последние кики/баны          │
└──────────────┴──────────────────────────────┘
Логика
JavaScriptasync function openObservationView(playerName, uuid) {
  // 1) убедиться что в suspects: POST /api/vesuvio/player/{uuid}/suspect { suspect: true }
  // 2) отрисовать карточку
  // 3) poll violations filtered by uuid каждые 3s
  // 4) параллельно подтянуть досье: GET /api/players/{name} или существующий endpoint профиля
}

function closeObservationView() {
  clearInterval(pollers['observation']);
  renderAnticheatView(); // назад к списку, tab suspects
}
Данные потока: GET /api/vesuvio/violations?limit=50 + filter uuid/name на клиенте (или query если бэк умеет).
Кнопка «Выйти из наблюдения за игроком» — только закрывает UI; снятие с suspects — отдельная кнопка.

ЗАДАЧА 5. Мелочи бэка/UI (если ещё нет)

Роли в select’ах — только sortOrder, без OWNER при выдаче приглашения.
pendingRoleReview — бейдж «Срок истёк» в таблице персонала; действия: «Сделать Модератором» / «Оставить» / «Снять доступ».
«Сделать Модератором» → PUT /api/admins/{id}/role с id роли «Модератор» + очистить pendingRoleReview в preferences.

Причины банов — читать category из API/конфига; в модалке бана группировать <optgroup label="Общение">.
Журнал — пресет-фильтр «Роли и персонал» уже есть; убедиться что строки Смена роли: и Приглашение сотрудника: попадают.


ЗАДАЧА 6. Чего НЕ делать

Не менять default bind 0.0.0.0 на 127.0.0.1
Не добавлять TTL/лимит попыток invite (отклонено)
Не добавлять badge «N черновиков» в шапку
Не удалять /бан /репорт /разбан
Не возвращать /lovewebadmin info|resetowner|generatetoken
Не ломать BCrypt / существующие сессии / CSP с 'unsafe-inline' (пока есть onclick)


Порядок работ

renderStaffView + personnel/roles tabs
Wizard приглашения + список invites + cancel
Регистрация по коду (login flow)
2FA UX (mask, XXX-XXX, QR click refresh)
Observation card античита
pendingRoleReview actions + ban reasons optgroup

Критерии готовности

 Меню «Персонал», право MANAGE_ADMINS / owner
 Два подокна: Персонал | Роли
 Добавление сотрудника → код → cancel работает
 Регистрация только по коду, пароль со сложностью, 2FA, backup codes
 Owner не видит «сменить роль» на себе
 Роли отсортированы по category/sortOrder, description не = name
 Секрет 2FA скрыт до hover/click; ввод XXX-XXX; клик по QR обновляет
 Наблюдение: карточка с потоком + инфо + выход
mvn package успешен, панель кликабельна

Стиль кода

UI/логи — русский; идентификаторы — как в проекте
Не раздувать дубли: переиспользовать api(), esc(), showToast(), renderTotpQrCode
После правок app.js — проверить Console на CSP/JS errors
Коммиты осмысленные, по фичам

text