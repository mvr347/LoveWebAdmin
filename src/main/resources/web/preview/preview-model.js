/* Pure local simulator. No fetch, Bukkit, database, command execution or Discord transport. */
(function (root, factory) {
  const api = factory();
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.LovePreviewModel = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const clone = (value) => JSON.parse(JSON.stringify(value));
  const fail = (status, message) => {
    throw Object.assign(new Error(message), { status });
  };
  const permissions = [
    "VIEW_SERVER_LOGS",
    "VIEW_WEB_LOGS",
    "VIEW_STATS",
    "EXECUTE_COMMANDS",
    "MANAGE_ADMINS",
    "MANAGE_ROLES",
    "MANAGE_PASSWORDS",
    "VIEW_BANS",
    "MANAGE_BANS",
    "VIEW_APPEALS",
    "MANAGE_APPEALS",
    "VIEW_PLAYERS",
    "MANAGE_PLAYERS",
    "VIEW_PLAYER_IP",
    "VIEW_PLAYER_INVENTORY",
    "CONFISCATE_ITEMS",
    "RESTORE_ITEMS",
    "FREEZE_PLAYERS",
    "TELEPORT_PLAYERS",
    "CHANGE_GAMEMODE",
    "KICK_PLAYERS",
    "KILL_PLAYERS",
    "VIEW_ANALYTICS",
    "MANAGE_LOVEAUTH",
    "VIEW_VESUVIO",
    "VIEW_VESUVIO_ADVANCED",
    "MANAGE_VESUVIO",
    "MANAGE_LOCKDOWN",
    "VIEW_STAFF_AUDIT",
    "VIEW_ECONOMY",
    "MANAGE_ECONOMY",
    "VIEW_REPORTS",
    "MANAGE_REPORTS",
    "MANAGE_API",
    "VIEW_SERVER_SETTINGS",
    "MANAGE_PANEL_SETTINGS",
    "MANAGE_SERVER_SETTINGS",
    "MANAGE_SECURITY_SETTINGS",
    "MANAGE_INTEGRATIONS",
    "MANAGE_MAINTENANCE",
    "MANAGE_WHITELIST",
    "MANAGE_OPERATORS",
    "MANAGE_PREVIEW",
    "REVIEW_VESUVIO_SAMPLES",
    "MANAGE_VESUVIO_MODELS",
    "VIEW_SERVER_INTERNALS",
    "BYPASS_MAINTENANCE",
  ];
  function seed(now) {
    const roles = [
      {
        id: 1,
        name: "Управляющий",
        isOwner: true,
        permissions,
        color: "#a78bfa",
        description: "Все права в превью",
      },
      {
        id: 2,
        name: "Модератор",
        isOwner: false,
        permissions: [
          "VIEW_STATS",
          "VIEW_PLAYERS",
          "VIEW_BANS",
          "MANAGE_BANS",
          "VIEW_REPORTS",
          "MANAGE_REPORTS",
          "VIEW_APPEALS",
          "MANAGE_APPEALS",
          "VIEW_PLAYER_INVENTORY",
          "CONFISCATE_ITEMS",
          "RESTORE_ITEMS",
          "FREEZE_PLAYERS",
          "KICK_PLAYERS",
        ],
        color: "#60a5fa",
      },
      {
        id: 3,
        name: "Наблюдатель",
        isOwner: false,
        permissions: [
          "VIEW_STATS",
          "VIEW_PLAYERS",
          "VIEW_BANS",
          "VIEW_REPORTS",
        ],
        color: "#94a3b8",
      },
    ];
    const admins = ["PreviewOwner", "PreviewModerator", "PreviewReader"].map(
      (username, i) => ({
        id: i + 1,
        username,
        roleId: i + 1,
        status: "ACTIVE",
        totpEnabled: true,
        createdAt: now - 86400,
        lastLoginAt: now,
        uiPreferences: {
          discordNotifyReports: true,
          discordNotifyTickets: true,
        },
      }),
    );
    const players = ["Ada123", "Lovelace69", "Traveller"].map((name, i) => ({
      name,
      uuid: `00000000-0000-4000-8000-00000000000${i + 1}`,
      isOnline: i < 2,
      online: i < 2,
      ping: 24 + i * 13,
      ip: "192.0.2." + (10 + i),
      playtimeSeconds: 18000 * (i + 1),
      firstJoinedAt: now - 864000,
      lastSeenAt: now - 60 * i,
      health: 20,
      foodLevel: 20,
      level: 16,
      gameMode: "SURVIVAL",
      balance: 25000 * (i + 1),
      hasDiscord: i !== 2,
      discordId: "preview-discord-" + i,
      authLocked: false,
      isFrozen: false,
      vanished: false,
      inventory: [
        {
          slot: 0,
          material: "DIAMOND",
          displayName: "Алмаз",
          amount: 12,
          empty: false,
          fingerprint: "preview-item-" + i,
        },
      ],
      notes: [],
    }));
    return {
      version: 1,
      viewer: 1,
      pendingLogin: null,
      nextId: 10,
      roles,
      admins,
      players,
      bans: [],
      appeals: [],
      reports: [],
      work: {},
      notes: {},
      discussion: {},
      evidence: [],
      deliveries: [],
      logs: [],
      notifications: [],
      invites: [],
      keys: [],
      webhooks: [],
      sessions: [],
      config: {
        previewEnabled: true,
        webPort: 8080,
        webHost: "127.0.0.1",
        sessionLifetimeMinutes: 60,
        keepLogCount: 5000,
        strictIp: false,
        minPasswordLength: 12,
        loginMaxAttempts: 5,
        loginLockoutMinutes: 15,
        commandBlacklist: ["stop", "op"],
        corsAllowedOrigins: [],
        maintenanceEnabled: false,
        maintenanceMessage: "Тестовый режим технических работ",
        webhooksEnabled: false,
        telegramEnabled: false,
      },
      whitelist: { enabled: false, players: [] },
      ops: [],
      templates: {
        ban: "Блокировка №{id}: {player}\n{reason}",
        report: "Жалоба №{id}: {player}",
        appeal: "Апелляция №{id}: {reason}",
        clarification: "Уточнение №{id}: {text}",
      },
      discord: { fail: false, quiet: false },
      scenario: "default",
    };
  }
  function create(options = {}) {
    const clock = options.clock || (() => Math.floor(Date.now() / 1000));
    let state;
    try {
      const saved = options.load?.();
      state = saved && saved.version === 1 ? clone(saved) : seed(clock());
    } catch {
      state = seed(clock());
    }
    const save = () => options.save?.(clone(state));
    const id = () => state.nextId++;
    const viewer = () =>
      state.admins.find((a) => a.id === state.viewer && a.status === "ACTIVE");
    const role = () => state.roles.find((r) => r.id === viewer()?.roleId);
    const has = (p) =>
      !!viewer() && (role()?.isOwner || role()?.permissions.includes(p));
    const requirePermission = (p) => {
      if (!viewer()) fail(401, "Войдите в тестовый аккаунт");
      if (p && !has(p))
        fail(403, "Недостаточно прав у выбранной тестовой роли");
    };
    const audit = (text) => {
      state.logs.push({
        id: id(),
        actor: viewer()?.username || "Тестовый игрок",
        action: text,
        message: text,
        createdAt: clock(),
        timestamp: clock(),
        level: "INFO",
      });
      state.logs = state.logs.slice(-200);
    };
    const player = (name) =>
      state.players.find(
        (p) => p.name.toLowerCase() === String(name).toLowerCase(),
      ) || fail(404, "Тестовый игрок не найден");
    const getBan = (n) =>
      state.bans.find((b) => b.id === Number(n)) ||
      fail(404, "Блокировка не найдена");
    const getAppeal = (n) =>
      state.appeals.find((a) => a.id === Number(n)) ||
      fail(404, "Апелляция не найдена");
    const getReport = (n) =>
      state.reports.find((r) => r.id === Number(n)) ||
      fail(404, "Жалоба не найдена");
    const reason = (text) => {
      if (typeof text !== "string" || text.trim().length < 5)
        fail(400, "Укажите обоснование (минимум 5 символов)");
      return text.trim();
    };
    const currentWork = (kind, n) =>
      state.work[kind + ":" + n] || {
        assignee: "",
        priority: "NORMAL",
        state: "OPEN",
      };
    const queue = (event, text, type = "player") => {
      state.deliveries.push({
        id: id(),
        event,
        state: "PENDING",
        attempts: 0,
        error: "",
        text,
        type,
      });
      state.deliveries = state.deliveries.slice(-100);
    };
    function me() {
      const a = viewer();
      if (!a) fail(401, "Войдите в тестовый аккаунт");
      return {
        ...clone(a),
        role: role().name,
        roleName: role().name,
        isOwner: role().isOwner,
        permissions: clone(role().permissions),
        discordId: "preview-staff-" + a.id,
        discordVerified: true,
        backupCodesCount: 8,
        last2faIp: "192.0.2.1",
        uiPreferences: clone(a.uiPreferences),
      };
    }
    function createBan(body) {
      const p = player(
        body.targetName || body.playerName || body.player || "Ada123",
      );
      const duration = Number(body.durationSeconds ?? body.duration ?? -1);
      const b = {
        id: id(),
        targetName: p.name,
        targetUuid: p.uuid,
        creatorName: viewer()?.username || "Система",
        ruleReason: body.ruleReason || body.reason || "Тестовое нарушение",
        description: body.description || "Проверка в превью",
        proofUrls: JSON.stringify(body.proofUrls || []),
        isIpBan: !!body.isIpBan,
        status: "ACTIVE",
        durationSeconds: duration,
        createdAt: clock(),
        expiresAt: duration > 0 ? clock() + duration : -1,
        source: { kind: "STAFF", actor: viewer()?.username || "PreviewOwner" },
        linkedReportId: body.linkedReportId || null,
      };
      state.bans.push(b);
      if (p.hasDiscord)
        queue("ban:" + b.id, "Заблокирован " + p.name + " · " + b.ruleReason);
      if (b.linkedReportId) {
        const r = getReport(b.linkedReportId);
        if (r.status === "PENDING") {
          r.status = "ACCEPTED";
          r.linkedBanId = b.id;
        }
      }
      audit("Выдан тестовый бан №" + b.id);
      return clone(b);
    }
    function createAppeal(body) {
      const b = body.banId
        ? getBan(body.banId)
        : state.bans.find(
            (b) =>
              b.status === "ACTIVE" &&
              b.targetName.toLowerCase() ===
                String(body.playerName).toLowerCase(),
          );
      if (!b || b.status !== "ACTIVE")
        fail(400, "Действующая блокировка не найдена");
      if (state.appeals.some((a) => a.banId === b.id && a.status === "PENDING"))
        fail(409, "Апелляция уже открыта");
      const message = reason(body.reason);
      const a = {
        id: id(),
        banId: b.id,
        playerName: b.targetName,
        playerUuid: b.targetUuid,
        reason: message,
        status: "PENDING",
        createdAt: clock(),
        updatedAt: clock(),
        accountVerified: body.accountVerified === true,
        messages: [],
        accessToken: "preview-author-" + id(),
        discordChannelId: "preview-channel-" + id(),
      };
      a.messages.push({
        id: id(),
        authorName: a.playerName,
        isStaff: false,
        message,
        createdAt: clock(),
      });
      state.appeals.push(a);
      queue("ticket:" + a.id, "Создание тестового тикета");
      audit("Создана апелляция №" + a.id);
      return clone(a);
    }
    function publicAppeal(a) {
      const b = getBan(a.banId);
      const result = {
        ...clone(a),
        reviews: publicReviews("appeal", a.id),
        banReason: b.ruleReason,
        banCreator: b.creatorName,
        banStatus: b.status,
        banExpiresAt: b.expiresAt,
      };
      delete result.accessToken;
      return result;
    }
    function createReport() {
      const target = player("Ada123"),
        reporter = player("Traveller");
      const r = {
        id: id(),
        targetName: target.name,
        targetUuid: target.uuid,
        reporterName: reporter.name,
        reporterUuid: reporter.uuid,
        reasons: ["Нарушение правил"],
        description: "Тестовая жалоба с контекстом чата",
        status: "PENDING",
        createdAt: clock(),
        reputationDeducted: 0,
        isRecent: true,
        resolvedBy: null,
        resolvedAt: 0,
      };
      state.reports.push(r);
      queue("staff:report:" + r.id, "Новая жалоба №" + r.id, "staff");
      audit("Подана жалоба №" + r.id);
      return r;
    }
    function author(a, headers) {
      return headers["x-appeal-token"] === a.accessToken;
    }
    function independentCase(kind, c) {
      const name = viewer().username.toLowerCase();
      if (
        kind === "report"
          ? c.targetName.toLowerCase() === name
          : getBan(c.banId).source?.kind === "STAFF" &&
            String(getBan(c.banId).source.actor).toLowerCase() === name
      )
        fail(
          409,
          "Собственное наказание или жалобу на себя рассматривает другой сотрудник",
        );
    }
    function reviewImageMime(encoded) {
      let bytes;
      try {
        bytes = atob(encoded);
      } catch {
        fail(400, "Некорректный файл скриншота");
      }
      if (bytes.length > 700000) fail(400, "Скриншот до 700 КБ");
      if (bytes.startsWith("\x89PNG\r\n\x1a\n")) return "image/png";
      if (bytes.startsWith("\xff\xd8") && bytes.endsWith("\xff\xd9"))
        return "image/jpeg";
      if (bytes.startsWith("RIFF") && bytes.slice(8, 12) === "WEBP")
        return "image/webp";
      fail(400, "Поддерживаются PNG, JPEG и WebP");
    }
    function requestReview(kind, c, actor, body) {
      if (["PENDING", "DECIDING"].includes(c.status))
        fail(409, "Дело ещё рассматривается");
      state.reviewRequests ||= [];
      if (
        state.reviewRequests.some(
          (r) =>
            r.kind === kind &&
            r.case_id === c.id &&
            ["PENDING", "PROCESSING"].includes(r.state),
        )
      )
        fail(409, "Дополнение уже ожидает рассмотрения");
      const text = String(body.text || "").trim();
      if (text.length < 10 || text.length > 2000)
        fail(400, "Опишите новые доказательства (10–2000 символов)");
      if (String(body.base64 || "").length > 933336)
        fail(400, "Скриншот до 700 КБ");
      const review = {
        id: id(),
        kind,
        case_id: c.id,
        actor,
        body: text,
        state: "PENDING",
        staff: "",
        explanation: "",
        new_kind: "",
        new_id: 0,
        hasImage: !!body.base64,
        image_name: body.imageName || "Скриншот",
        base64: body.base64,
        mime: body.base64 ? reviewImageMime(body.base64) : null,
        created: clock(),
      };
      state.reviewRequests.push(review);
      return { id: review.id };
    }
    const publicReviews = (kind, n) =>
      (state.reviewRequests || [])
        .filter((r) => r.kind === kind && r.case_id === Number(n))
        .map(({ actor, base64, mime, ...r }) => clone(r));
    function caseWorkflow(kind, c, action, method, body, headers) {
      const key = kind + ":" + c.id;
      state.replyTemplates ||= [
        {
          id: id(),
          owner: "*",
          purpose: "CLARIFY",
          name: "Уточнить время",
          text: "Укажите дату, примерное время и место ситуации.",
          revision: 0,
        },
        {
          id: id(),
          owner: "*",
          purpose: "VERDICT",
          name: "Недостаточно доказательств",
          text: "Для пересмотра решения недостаточно доказательств. Вы можете направить новые материалы.",
          revision: 0,
        },
      ];
      state.decisionPreviews ||= {};
      state.reviewParents ||= {};
      const snapshot = () =>
        JSON.stringify([
          c.status,
          currentWork(kind, c.id),
          kind === "appeal" ? getBan(c.banId) : null,
          state.caseVersions?.[key] || 0,
        ]);
      if (action === "workflow") {
        state.waiting ||= {};
        const w = currentWork(kind, c.id),
          last = (
            kind === "appeal" ? c.messages : state.discussion[key] || []
          ).at(-1),
          marker = last ? JSON.stringify(last) : "",
          old = state.waiting[key];
        let mode = !["PENDING"].includes(c.status)
          ? "NONE"
          : w.state === "WAITING"
            ? "PLAYER"
            : "STAFF";
        if (old && mode !== "NONE") {
          mode = old.mode === "NONE" ? mode : old.mode;
          if (marker && marker !== old.marker)
            mode = last.isStaff ? "PLAYER" : "STAFF";
          if (old.work !== w.state)
            mode = w.state === "WAITING" ? "PLAYER" : "STAFF";
        }
        state.waiting[key] = {
          mode,
          since: old && old.mode === mode ? old.since : clock(),
          marker,
          work: w.state,
        };
        const wait = state.waiting[key],
          deadline =
            mode === "NONE"
              ? 0
              : wait.since + (mode === "PLAYER" ? 2880 : 20) * 60;
        return {
          waiting: {
            ...wait,
            deadline,
            overdue: deadline > 0 && deadline <= clock(),
          },
          templates: state.replyTemplates.filter((t) =>
            ["*", viewer().username].includes(t.owner),
          ),
          reviews: publicReviews(kind, c.id),
          parent: state.reviewParents[key] || {},
        };
      }
      if (action === "decision-preview") {
        independentCase(kind, c);
        if (
          !["approve", "reject", "reduce"].includes(body.action) ||
          (kind === "report" && body.action === "reduce")
        )
          fail(400, "Неизвестное решение");
        if (c.status !== "PENDING") fail(409, "Дело закрыто");
        if (kind === "appeal" && body.action !== "reject")
          requirePermission("MANAGE_BANS");
        const why = reason(body.reason);
        const b = kind === "appeal" ? getBan(c.banId) : null;
        let end = 0;
        if (b?.source?.kind === "SECURITY" && body.action !== "reject")
          fail(400, "Ограничение снимается через раздел безопасности");
        if (body.action === "reduce") {
          if (
            b.status !== "ACTIVE" ||
            !(body.remainingSeconds > 0) ||
            body.remainingSeconds > 315360000
          )
            fail(400, "Неверный срок");
          end = clock() + body.remainingSeconds;
          if (b.expiresAt > 0 && end >= b.expiresAt)
            fail(400, "Новый срок должен быть короче");
        }
        const token = "preview-decision-" + id();
        const value = {
          key: token,
          kind,
          id: c.id,
          action: body.action,
          reason: why,
          staff: viewer().username,
          before: {
            target: c.playerName || c.targetName,
            expiry: b?.expiresAt,
          },
          targetExpiry: end,
          effect:
            !b || body.action === "reject"
              ? "Наказание не изменится"
              : body.action === "reduce"
                ? "Срок наказания будет сокращён"
                : "Блокировка будет снята",
          playerMessage: why,
          closesCase: true,
        };
        state.decisionPreviews[token] = {
          value: clone(value),
          actor: viewer().id,
          hash: snapshot(),
          state: "DRAFT",
          expires: clock() + 600,
        };
        return value;
      }
      if (action === "decision-confirm" || action === "decision-cancel") {
        const draft = state.decisionPreviews[body.key];
        if (
          !draft ||
          draft.actor !== viewer().id ||
          draft.value.id !== c.id ||
          draft.value.kind !== kind ||
          draft.expires < clock()
        )
          fail(403, "Предпросмотр недоступен");
        if (action === "decision-cancel") {
          if (draft.state === "DRAFT") draft.state = "CANCELLED";
          return {};
        }
        if (draft.state === "DONE") return draft.result;
        independentCase(kind, c);
        if (draft.state !== "DRAFT") fail(409, "Решение уже обработано");
        if (draft.hash !== snapshot())
          fail(409, "Дело или наказание изменились; обновите предпросмотр");
        if (kind === "appeal" && draft.value.action !== "reject")
          requirePermission("MANAGE_BANS");
        draft.state = "APPLYING";
        const a = draft.value.action,
          seconds = draft.value.targetExpiry - clock();
        const result = route(
          "POST",
          new URL(
            `/api/${kind === "appeal" ? "appeals" : "reports"}/${c.id}/${kind === "report" && a === "approve" ? "accept" : a}`,
            "https://preview.invalid",
          ),
          { verdictMessage: draft.value.reason, remainingSeconds: seconds },
          headers,
        );
        if (kind === "report")
          (state.discussion[key] ||= []).push({
            author: viewer().username,
            isStaff: true,
            body: draft.value.reason,
            createdAt: clock(),
          });
        draft.state = "DONE";
        draft.result = clone(result);
        return result;
      }
      if (action.startsWith("reply-template")) {
        if (body.shared) requirePermission("MANAGE_INTEGRATIONS");
        const owner = body.shared ? "*" : viewer().username,
          template = state.replyTemplates.find(
            (t) => t.id === Number(body.id) && t.owner === owner,
          );
        if (
          body.id &&
          (!template || template.revision !== Number(body.revision))
        )
          fail(409, "Шаблон изменён или недоступен");
        if (action.endsWith("delete")) {
          state.replyTemplates = state.replyTemplates.filter(
            (t) => t !== template,
          );
          return {};
        }
        if (
          !["CLARIFY", "VERDICT"].includes(body.purpose) ||
          !String(body.name || "").trim()
        )
          fail(400, "Заполните название и назначение");
        const values = {
          owner,
          purpose: body.purpose,
          name: body.name,
          text: reason(body.text),
        };
        if (template)
          Object.assign(template, values, { revision: template.revision + 1 });
        else state.replyTemplates.push({ id: id(), revision: 0, ...values });
        return {};
      }
      if (action === "review-image") {
        const proof = state.reviewRequests.find(
          (r) =>
            r.id === Number(body.imageId) &&
            r.kind === kind &&
            r.case_id === c.id,
        );
        if (!proof?.base64) fail(404, "Скриншот не найден");
        return { mime: proof.mime, base64: proof.base64 };
      }
      independentCase(kind, c);
      if (c.status === "PENDING") fail(409, "Исходное дело снова открыто");
      const review = (state.reviewRequests || []).find(
        (r) =>
          r.id === Number(body.requestId) &&
          r.kind === kind &&
          r.case_id === c.id,
      );
      if (!review || review.state !== "PENDING")
        fail(409, "Дополнение уже рассмотрено");
      const why = reason(body.reason);
      review.staff = viewer().username;
      review.explanation = why;
      if (action === "review-reject") {
        review.state = "REJECTED";
        return { state: "REJECTED" };
      }
      if (
        kind === "appeal" &&
        state.appeals.some(
          (a) =>
            a.banId === c.banId && ["PENDING", "DECIDING"].includes(a.status),
        )
      )
        fail(409, "Уже есть открытая апелляция");
      const child = {
        ...clone(c),
        id: id(),
        status: "PENDING",
        createdAt: clock(),
        reason: review.body,
        description: review.body,
        messages: [
          {
            id: id(),
            authorName: c.playerName || c.reporterName,
            isStaff: false,
            message: review.body,
            createdAt: clock(),
          },
        ],
        reputationDeducted: -1,
      };
      (kind === "appeal" ? state.appeals : state.reports).push(child);
      state.caseEvidence ||= {};
      const proof = {
        id: id(),
        author: "Автор обращения",
        type: "TEXT",
        value: review.body,
        visibility: "AUTHOR",
        createdAt: clock(),
      };
      state.caseEvidence[kind + ":" + child.id] = [proof];
      if (review.base64)
        state.caseEvidence[kind + ":" + child.id].push({
          ...proof,
          id: id(),
          type: "IMAGE",
          value: review.image_name,
          base64: review.base64,
          mime: review.mime,
        });
      review.state = "ACCEPTED";
      review.new_kind = kind;
      review.new_id = child.id;
      state.reviewParents[kind + ":" + child.id] = { kind, id: c.id };
      return { state: "ACCEPTED", kind, id: child.id };
    }
    function route(method, url, body, headers) {
      const p = url.pathname.replace(/^\/preview(?=\/api)/, ""),
        parts = p.split("/").filter(Boolean),
        n = parts[2],
        action = parts[3];
      for (const b of state.bans)
        if (b.status === "ACTIVE" && b.expiresAt > 0 && b.expiresAt <= clock())
          b.status = "EXPIRED";
      if (p === "/api/auth/status")
        return {
          ownerExists: state.scenario !== "onboarding",
          initialSetupNeeded: state.scenario === "onboarding",
          previewEnabled: true,
          maintenance: state.config.maintenanceEnabled,
          maintenanceMessage: state.config.maintenanceMessage,
        };
      if (p === "/api/auth/check-username") {
        const username = url.searchParams.get("username") || "";
        if (!/^[A-Za-z0-9_]{3,24}$/.test(username))
          fail(400, "Неверный тестовый ник");
        if (
          state.admins.some(
            (a) => a.username.toLowerCase() === username.toLowerCase(),
          )
        )
          fail(409, "Ник уже занят");
        return { available: true, username, message: "Никнейм свободен" };
      }
      if (p === "/api/auth/login") {
        const a = state.admins.find(
          (a) => a.username === body.username && a.status === "ACTIVE",
        );
        if (!a || body.password !== "PreviewOnly!25")
          fail(401, "Тестовый пароль: PreviewOnly!25");
        state.pendingLogin = a.id;
        return {
          status: "NEED_2FA",
          username: a.username,
          challenge: "preview-challenge",
        };
      }
      if (p === "/api/auth/verify-2fa") {
        const a = state.admins.find(
          (a) => a.id === state.pendingLogin && a.username === body.username,
        );
        if (
          !a ||
          body.code !== "123456" ||
          body.challenge !== "preview-challenge"
        )
          fail(401, "Сначала пароль, затем тестовый код 123456");
        state.viewer = a.id;
        state.pendingLogin = null;
        return {
          token: "preview-session-" + a.id,
          status: "SUCCESS",
          permissions: state.roles.find((r) => r.id === a.roleId).permissions,
        };
      }
      if (p === "/api/auth/logout") {
        state.viewer = null;
        return {};
      }
      if (
        p === "/api/auth/prepare-setup-owner" ||
        p === "/api/auth/validate-invite"
      )
        return {
          valid: true,
          username: body.username || "NewModerator",
          roleName: "Модератор",
          secret: "JBSWY3DPEHPK3PXP",
          totpSecret: "JBSWY3DPEHPK3PXP",
          otpUrl:
            "otpauth://totp/Preview?secret=JBSWY3DPEHPK3PXP&issuer=Preview",
        };
      if (p === "/api/auth/setup-owner" || p === "/api/auth/register") {
        if (body.totpCode !== "123456")
          fail(400, "Введите тестовый код 123456");
        const a = {
          id: id(),
          username: body.username || "NewModerator",
          roleId: p.endsWith("setup-owner") ? 1 : 2,
          status: "ACTIVE",
          totpEnabled: true,
          uiPreferences: {},
        };
        state.admins.push(a);
        state.viewer = a.id;
        state.scenario = "default";
        return {
          token: "preview-session-" + a.id,
          status: "ACTIVE",
          backupCodes: ["PREV-0001", "PREV-0002"],
        };
      }
      if (parts[1] === "portal") {
        state.portalSessions ||= {};
        if (n === "exchange" && method === "POST") {
          if (!body.code || body.code !== state.portalOneTimeCode)
            fail(401, "Ссылка использована или истекла");
          state.portalOneTimeCode = null;
          const token = "preview-player-" + id();
          state.portalSessions[token] = true;
          return { token };
        }
        const token =
          headers["x-player-session"] || headers["X-Player-Session"];
        if (!state.portalSessions[token])
          fail(401, "Откройте тестовую одноразовую ссылку");
        if (n === "logout") {
          delete state.portalSessions[token];
          return {};
        }
        if (n === "cases")
          return state.appeals.map((a) => ({
            kind: "appeal",
            id: a.id,
            player: a.playerName,
            status: a.status,
            reason: a.reason,
            verified: a.accountVerified,
          }));
        if (!["appeal", "report"].includes(n))
          fail(404, "Обращение не найдено");
        const c = n === "appeal" ? getAppeal(action) : getReport(action),
          key = n + ":" + c.id;
        if (method === "POST" && parts[4] === "review")
          return requestReview(n, c, "portal", body);
        if (method === "GET")
          return {
            reviews: publicReviews(n, c.id),
            messages: n === "appeal" ? c.messages : state.discussion[key] || [],
            evidence: (state.caseEvidence?.[key] || [])
              .filter((e) => e.visibility === "AUTHOR")
              .map(({ base64, ...e }) => e),
          };
        if (c.status !== "PENDING") fail(409, "Обращение закрыто");
        const message = {
          id: id(),
          authorName: c.playerName || c.reporterName,
          isStaff: false,
          message: reason(body.text),
          createdAt: clock(),
        };
        if (n === "appeal") c.messages.push(message);
        else (state.discussion[key] ||= []).push(message);
        return {};
      }
      if (p === "/api/appeals" && method === "POST") return createAppeal(body);
      if (parts[1] === "appeals" && n) {
        const a = getAppeal(n);
        if (method === "GET") {
          if (!author(a, headers)) requirePermission("VIEW_APPEALS");
          return publicAppeal(a);
        }
        if (action === "review") {
          if (!author(a, headers)) fail(403, "Нужна ссылка автора");
          return requestReview("appeal", a, "author", body);
        }
        if (action === "reply") {
          if (a.status !== "PENDING") fail(409, "Апелляция закрыта");
          if (!author(a, headers)) requirePermission("MANAGE_APPEALS");
          const m = {
            id: id(),
            authorName: author(a, headers) ? a.playerName : viewer().username,
            isStaff: !author(a, headers),
            message: reason(body.message),
            createdAt: clock(),
          };
          a.messages.push(m);
          queue("reply:" + a.id + ":" + m.id, m.message);
          return m;
        }
        requirePermission("MANAGE_APPEALS");
        if (a.status !== "PENDING") fail(409, "Решение уже принято");
        const text = reason(body.verdictMessage),
          b = getBan(a.banId);
        if (action === "approve") {
          b.status = "UNBANNED";
          a.status = "APPROVED";
        } else if (action === "reject") a.status = "REJECTED";
        else if (action === "reduce") {
          requirePermission("MANAGE_BANS");
          const remaining = Number(body.remainingSeconds);
          if (
            !Number.isInteger(remaining) ||
            remaining < 60 ||
            (b.expiresAt > 0 && clock() + remaining >= b.expiresAt)
          )
            fail(
              400,
              "Новый срок должен быть меньше текущего и не меньше минуты",
            );
          b.expiresAt = clock() + remaining;
          a.status = "REDUCED";
        } else fail(404, "Неизвестное решение");
        a.messages.push({
          id: id(),
          authorName: viewer().username,
          isStaff: true,
          message: text,
          createdAt: clock(),
        });
        queue("finish:" + a.id, text);
        audit("Решение по апелляции №" + a.id + ": " + a.status);
        return { status: a.status, message: text };
      }
      requirePermission();
      if (p === "/api/me") return me();
      if (p === "/api/me/preferences") {
        viewer().uiPreferences =
          typeof body.uiPreferences === "string"
            ? JSON.parse(body.uiPreferences)
            : body.uiPreferences;
        return {};
      }
      if (p === "/api/me/shift" || p === "/api/staff/shifts")
        fail(410, "Механика смен удалена");
      if (p === "/api/me/sessions")
        return [
          {
            tokenPrefix: "preview",
            ip: "192.0.2.1",
            userAgent: "Превью",
            createdAt: clock(),
            lastUsedAt: clock(),
            current: true,
          },
        ];
      if (p === "/api/me/sessions/other/terminate")
        return { message: "Тестовые сессии завершены" };
      if (p === "/api/me/backup-codes/regenerate")
        return { backupCodes: ["PREV-0003", "PREV-0004"] };
      if (p === "/api/me/password") {
        if (body.oldPassword !== "PreviewOnly!25" || body.totpCode !== "123456")
          fail(400, "Тестовый пароль и 2FA неверны");
        return {
          message:
            "Проверка смены пароля выполнена. Демопароль остаётся прежним",
        };
      }
      if (p === "/api/stats") {
        requirePermission("VIEW_STATS");
        return {
          tps: [20, 20, 20],
          mspt: 8,
          memory: {
            usedMb: 512,
            totalMb: 1024,
            maxMb: 2048,
            freeMb: 512,
            percent: 25,
          },
          players: clone(state.players.filter((p) => p.online)),
          onlinePlayers: state.players.filter((p) => p.online).length,
          maxPlayers: 100,
          serverVersion: "Paper 26.2 · ПРЕВЬЮ",
          uptime: 3600,
        };
      }
      if (parts[1] === "players") {
        requirePermission("VIEW_PLAYERS");
        if (!n || ["all", "search"].includes(n))
          return clone(
            state.players.filter((p) =>
              p.name
                .toLowerCase()
                .includes((url.searchParams.get("q") || "").toLowerCase()),
            ),
          );
        const pl = player(decodeURIComponent(n));
        if (action === "inventory") {
          requirePermission("VIEW_PLAYER_INVENTORY");
          return {
            storage: clone(pl.inventory),
            armor: {},
            offhand: { empty: true },
            enderchest: [],
            health: pl.health,
            maxHealth: 20,
            foodLevel: 20,
            level: 16,
            gameMode: pl.gameMode,
          };
        }
        if (action === "confiscated") {
          requirePermission("RESTORE_ITEMS");
          return clone(state.evidence.filter((e) => e.owner === pl.name));
        }
        if (action === "notes") {
          if (method === "GET") return clone(pl.notes);
          requirePermission("MANAGE_PLAYERS");
          if (method === "DELETE")
            pl.notes = pl.notes.filter((x) => x.id !== Number(parts[4]));
          else
            pl.notes.push({
              id: id(),
              author: viewer().username,
              note: reason(body.note),
              createdAt: clock(),
            });
          return {};
        }
        if (action === "chat")
          return [
            {
              playerName: pl.name,
              message: "Тестовое сообщение",
              createdAt: clock(),
            },
          ];
        if (action === "alts") {
          requirePermission("VIEW_PLAYER_IP");
          return {
            primaryIp: pl.ip,
            alts: [],
            sharedIps: [],
            directAlts: [],
            subnetAlts: [],
          };
        }
        if (action === "action") {
          const needed =
            {
              remove_item: "CONFISCATE_ITEMS",
              clear_inventory: "CONFISCATE_ITEMS",
              restore_item: "RESTORE_ITEMS",
              freeze: "FREEZE_PLAYERS",
              unfreeze: "FREEZE_PLAYERS",
              kick: "KICK_PLAYERS",
              kill: "KILL_PLAYERS",
              gamemode: "CHANGE_GAMEMODE",
              teleport_spawn: "TELEPORT_PLAYERS",
            }[body.action] || "MANAGE_PLAYERS";
          requirePermission(needed);
          if (!pl.online) fail(409, "Тестовый игрок не в сети");
          if (body.action === "remove_item") {
            const item = pl.inventory.find((i) => i.slot === Number(body.slot));
            if (!item || item.fingerprint !== body.fingerprint)
              fail(409, "Снимок предмета устарел");
            reason(body.reason);
            state.evidence.push({
              ...clone(item),
              id: String(id()),
              owner: pl.name,
              actor: viewer().username,
              reason: body.reason,
              state: "HELD",
              createdAt: clock(),
            });
            pl.inventory = pl.inventory.filter((i) => i !== item);
          } else if (body.action === "restore_item") {
            const e = state.evidence.find(
              (e) =>
                e.id === body.id && e.owner === pl.name && e.state === "HELD",
            );
            if (!e) fail(409, "Предмет уже возвращён");
            pl.inventory.push({
              ...clone(e),
              slot: pl.inventory.length,
              fingerprint: "preview-restored-" + id(),
            });
            e.state = "RETURNED";
            e.returnedBy = viewer().username;
          } else if (body.action === "clear_inventory") {
            for (const item of pl.inventory)
              state.evidence.push({
                ...clone(item),
                id: String(id()),
                owner: pl.name,
                actor: viewer().username,
                reason: "Очистка в превью",
                state: "HELD",
                createdAt: clock(),
              });
            pl.inventory = [];
          } else if (body.action === "freeze" || body.action === "unfreeze")
            pl.isFrozen = body.action === "freeze";
          else if (body.action === "kick") pl.online = pl.isOnline = false;
          else if (body.action === "heal") {
            pl.health = 20;
            pl.foodLevel = 20;
          } else if (body.action === "feed") pl.foodLevel = 20;
          else if (body.action === "kill") pl.health = 0;
          else if (body.action === "gamemode")
            pl.gameMode = String(
              body.gameMode || body.mode || "SURVIVAL",
            ).toUpperCase();
          else if (body.action === "vanish") pl.vanished = !pl.vanished;
          else if (!["teleport_spawn", "message", "mute"].includes(body.action))
            fail(501, "Неизвестное действие превью");
          audit("Действие " + body.action + " над " + pl.name);
          return { message: "Тестовое действие выполнено" };
        }
        const profile = {
          ...clone(pl),
          isBanned: state.bans.some(
            (b) => b.targetName === pl.name && b.status === "ACTIVE",
          ),
          bans: clone(state.bans.filter((b) => b.targetName === pl.name)),
          activeBans: [],
          trustScore: 75,
          trustGrade: "B",
          trustDescription: "Тестовый показатель доверия",
          trustColor: "#60a5fa",
          staffNotes: clone(pl.notes),
          stats: { deaths: 4, kills: 2, blocksBroken: 320 },
          loveAuth: {
            available: true,
            isRegistered: true,
            isLocked: pl.authLocked,
          },
          vesuvio: { available: true, vl: 0, risk: 10, trust: 90 },
        };
        if (!has("VIEW_PLAYER_IP")) delete profile.ip;
        return profile;
      }
      if (parts[1] === "bans") {
        requirePermission(method === "GET" ? "VIEW_BANS" : "MANAGE_BANS");
        if (n === "reasons")
          return [
            {
              id: "cheats",
              name: "Читы",
              category: "Честная игра",
              require_comment: false,
            },
            {
              id: "rules",
              name: "Нарушение правил",
              category: "Правила",
              require_comment: false,
            },
            {
              id: "insult",
              name: "Оскорбления",
              category: "Общение",
              require_comment: true,
            },
          ];
        if (n === "appeal-templates")
          return [
            {
              id: 1,
              title: "Разъяснение решения",
              name: "Разъяснение решения",
              body: "Причина: {reason}",
              text: "Причина: {reason}",
            },
          ];
        if (n === "archive") return { files: [] };
        if (!n || n === "all")
          return method === "GET" ? clone(state.bans) : createBan(body);
        const b = getBan(n);
        if (method === "GET")
          return {
            ...clone(b),
            revision: b.revision || 0,
            history: b.history || [],
          };
        if (action === "correct") {
          if (b.status !== "ACTIVE") fail(409, "Блокировка уже закрыта");
          if (Number(body.revision) !== (b.revision || 0))
            fail(409, "Блокировка изменена; черновик сохранён");
          const why = reason(body.explanation),
            before = JSON.stringify({
              ruleReason: b.ruleReason,
              expiresAt: b.expiresAt,
              description: b.description,
            });
          b.ruleReason = reason(body.ruleReason);
          b.description = body.description || "";
          const seconds = Number(body.remainingSeconds);
          if (seconds !== -1 && (!Number.isInteger(seconds) || seconds < 60))
            fail(400, "Неверный срок");
          b.expiresAt = seconds < 0 ? -1 : clock() + seconds;
          b.durationSeconds = seconds;
          b.revision = (b.revision || 0) + 1;
          (b.history ||= []).push({
            id: id(),
            actor: viewer().username,
            explanation: why,
            before,
            after: JSON.stringify({
              ruleReason: b.ruleReason,
              expiresAt: b.expiresAt,
              description: b.description,
            }),
            state: "APPLIED",
            createdAt: clock(),
          });
          return clone(b);
        }
        if (action === "unban") {
          if (b.status !== "ACTIVE") fail(409, "Блокировка уже снята");
          b.status = "UNBANNED";
          audit("Снят тестовый бан №" + b.id);
          return {};
        }
        if (action === "attach-report") {
          b.linkedReportId = Number(body.reportId);
          return {};
        }
        if (method === "DELETE") {
          state.bans = state.bans.filter((x) => x !== b);
          return {};
        }
      }
      if (p === "/api/appeals") {
        requirePermission("VIEW_APPEALS");
        const filter = url.searchParams.get("status");
        return {
          appeals: state.appeals
            .filter((a) => !filter || a.status === filter)
            .map(publicAppeal),
          total: state.appeals.length,
        };
      }
      if (parts[1] === "reports") {
        requirePermission(method === "GET" ? "VIEW_REPORTS" : "MANAGE_REPORTS");
        if (!n) {
          const filter = url.searchParams.get("status"),
            target = url.searchParams.get("target");
          return {
            reports: clone(
              state.reports.filter(
                (r) =>
                  (!filter || r.status === filter) &&
                  (!target || r.targetName === target),
              ),
            ),
            stats: {
              pending: state.reports.filter((r) => r.status === "PENDING")
                .length,
              total: state.reports.length,
              accepted: state.reports.filter((r) => r.status === "ACCEPTED")
                .length,
              rejected: state.reports.filter((r) => r.status === "REJECTED")
                .length,
            },
          };
        }
        const r = getReport(n);
        if (method === "GET")
          return {
            report: clone(r),
            chatLogs: [
              {
                playerName: r.targetName,
                message: "Синтетический контекст сообщения",
                createdAt: r.createdAt,
              },
            ],
            targetActiveReportsCount: 1,
            targetSuspicious: false,
          };
        if (r.status !== "PENDING") fail(409, "Жалоба уже рассмотрена");
        if (method === "DELETE")
          state.reports = state.reports.filter((x) => x !== r);
        else if (["accept", "reject"].includes(action)) {
          r.status = action === "accept" ? "ACCEPTED" : "REJECTED";
          r.resolvedBy = viewer().username;
          r.resolvedAt = clock();
        } else fail(404, "Неизвестное действие жалобы");
        audit("Рассмотрена жалоба №" + r.id);
        return {};
      }
      if (parts[1] === "cases") {
        if (n === "deliveries") {
          requirePermission("MANAGE_API");
          if (method === "GET") return clone(state.deliveries).reverse();
          const d = state.deliveries.find((d) => d.id === Number(action));
          if (!d || d.state !== "FAILED")
            fail(409, "Повтор доступен только для неудачной доставки");
          d.state = "PENDING";
          d.attempts = 0;
          return {};
        }
        if (n === "templates") {
          requirePermission("MANAGE_API");
          const tokens = {
            ban: ["player", "reason", "id", "source", "duration", "expiry"],
            report: [
              "player",
              "id",
              "reporter",
              "reason",
              "assignee",
              "status",
            ],
            appeal: ["id", "player", "reason", "ban"],
            clarification: ["id", "text", "actor"],
          };
          if (method === "GET")
            return { templates: clone(state.templates), tokens };
          if (!tokens[body.kind]) fail(400, "Неизвестный шаблон");
          for (const match of String(body.text).matchAll(/\{([A-Za-z]+)}/g))
            if (!tokens[body.kind].includes(match[1]))
              fail(400, "Неизвестное поле");
          if (action === "save") state.templates[body.kind] = body.text;
          return {
            preview: String(body.text).replace(
              /\{([A-Za-z]+)}/g,
              (_, token) =>
                ({
                  player: "Ada123",
                  id: "12",
                  reason: "Пример причины",
                  source: "Сотрудник",
                  text: "Подробнее о событии",
                  actor: "PreviewOwner",
                })[token] || "Пример",
            ),
          };
        }
        const kind = n,
          caseId = Number(action),
          act = parts[4];
        if (!["report", "appeal"].includes(kind))
          fail(404, "Тип дела не найден");
        requirePermission(
          (method === "GET" ? "VIEW_" : "MANAGE_") +
            (kind === "report" ? "REPORTS" : "APPEALS"),
        );
        const c = kind === "report" ? getReport(caseId) : getAppeal(caseId),
          key = kind + ":" + caseId;
        state.caseEvidence ||= {};
        state.caseVersions ||= {};
        if (
          [
            "workflow",
            "decision-preview",
            "decision-confirm",
            "decision-cancel",
            "reply-template",
            "reply-template-delete",
            "review-accept",
            "review-reject",
            "review-image",
          ].includes(act)
        )
          return caseWorkflow(
            kind,
            c,
            act,
            method,
            act === "review-image"
              ? { ...body, imageId: Number(parts[5]) }
              : body,
            headers,
          );
        if (method === "GET" && act === "evidence") {
          const evidence = (state.caseEvidence[key] || []).find(
            (e) => e.id === Number(parts[5]),
          );
          if (!evidence || evidence.type !== "IMAGE")
            fail(404, "Доказательство не найдено");
          return { mime: evidence.mime, base64: evidence.base64 };
        }
        if (method === "GET")
          return {
            revision: state.caseVersions[key] || 0,
            evidence: (state.caseEvidence[key] || []).map(
              ({ base64, ...e }) => e,
            ),
            work: clone(currentWork(kind, caseId)),
            notes: clone(state.notes[key] || []),
            discussion: clone(state.discussion[key] || []),
            related: clone(c.related || []),
          };
        if (c.status !== "PENDING") fail(409, "Дело закрыто");
        if (body.revision !== undefined) {
          if (Number(body.revision) !== (state.caseVersions[key] || 0))
            fail(409, "Дело изменилось; черновик сохранён");
          state.caseVersions[key] = (state.caseVersions[key] || 0) + 1;
        }
        const w = currentWork(kind, caseId);
        if (act === "evidence") {
          if (
            !["TEXT", "CHAT", "LINK", "IMAGE"].includes(body.type) ||
            !["STAFF", "AUTHOR"].includes(body.visibility)
          )
            fail(400, "Неверный тип доказательства");
          if (body.type === "LINK" && !/^https?:\/\//.test(body.value))
            fail(400, "Нужна http/https-ссылка");
          (state.caseEvidence[key] ||= []).push({
            id: id(),
            author: viewer().username,
            type: body.type,
            value: reason(body.value),
            visibility: body.visibility,
            createdAt: clock(),
            base64: body.base64,
            mime: body.type === "IMAGE" ? "image/png" : null,
          });
        } else if (act === "claim") {
          if (w.assignee) fail(409, "Дело уже занято");
          state.work[key] = {
            ...w,
            assignee: viewer().username,
            state: "IN_PROGRESS",
          };
        } else if (act === "note") {
          (state.notes[key] ||= []).push({
            author: viewer().username,
            body: reason(body.text),
            createdAt: clock(),
          });
        } else if (act === "work") {
          if (w.assignee !== viewer().username)
            fail(409, "Передать дело может ответственный");
          if (
            !state.admins.some(
              (a) => a.username === body.assignee && a.status === "ACTIVE",
            )
          )
            fail(400, "Сотрудник не найден");
          state.work[key] = {
            assignee: body.assignee,
            priority: body.priority,
            state: body.state,
          };
        } else if (act === "merge") {
          const target = getReport(body.targetId);
          if (
            kind !== "report" ||
            target.id === c.id ||
            target.targetUuid !== c.targetUuid ||
            target.status !== "PENDING"
          )
            fail(409, "Нужна другая открытая жалоба на того же игрока");
          c.status = "MERGED";
          const link = {
            source: c.id,
            target: target.id,
            actor: viewer().username,
          };
          (c.related ||= []).push(link);
          (target.related ||= []).push(link);
        } else if (act === "clarify") {
          if (kind !== "report") fail(400, "Уточнение доступно для жалобы");
          (state.discussion[key] ||= []).push({
            author: viewer().username,
            body: reason(body.text),
            createdAt: clock(),
            isStaff: true,
          });
          queue("clarify:" + id(), body.text);
        } else fail(404, "Неизвестное действие дела");
        audit("Изменено дело " + key + ": " + act);
        return {};
      }
      if (parts[1] === "admins" || parts[1] === "roles") {
        requirePermission(
          parts[1] === "roles" ? "MANAGE_ROLES" : "MANAGE_ADMINS",
        );
        const isRoles = parts[1] === "roles",
          list = isRoles ? state.roles : state.admins;
        if (n === "terminate-all")
          return { message: "Тестовые сессии завершены" };
        if (n === "invites") {
          if (method === "GET") return clone(state.invites);
          if (method === "DELETE") {
            state.invites = state.invites.filter(
              (x) => x.id !== Number(action),
            );
            return {};
          }
          const invite = {
            id: id(),
            code: "PREVIEW-" + id(),
            username: body.username,
            roleId: Number(body.roleId),
            status: "PENDING",
            createdAt: clock(),
            expiresAt: clock() + 86400,
          };
          state.invites.push(invite);
          return clone(invite);
        }
        if (!n) {
          if (method === "GET") return clone(list);
          const row = {
            ...clone(body),
            id: id(),
            status: "ACTIVE",
            createdAt: clock(),
          };
          list.push(row);
          return clone(row);
        }
        const item = list.find((x) => x.id === Number(n));
        if (!item) fail(404, "Запись не найдена");
        if (item.id === 1 && method === "DELETE")
          fail(409, "Управляющий защищён");
        if (method === "DELETE") {
          if (isRoles) state.roles = list.filter((x) => x !== item);
          else state.admins = list.filter((x) => x !== item);
        } else if (action === "role") item.roleId = Number(body.roleId);
        else if (method === "PUT") Object.assign(item, body);
        else if (
          !["terminate-sessions", "approve", "reject", "password"].includes(
            action,
          ) &&
          n !== "terminate-all"
        )
          fail(404, "Неизвестное действие сотрудника");
        audit("Изменена тестовая запись " + n);
        return {};
      }
      if (parts[1] === "server") {
        requirePermission(
          n === "config"
            ? method === "GET"
              ? "VIEW_SERVER_SETTINGS"
              : "MANAGE_SERVER_SETTINGS"
            : n === "maintenance"
              ? "MANAGE_MAINTENANCE"
              : n === "ops"
                ? "MANAGE_OPERATORS"
                : "MANAGE_WHITELIST",
        );
        if (n === "config") {
          if (method === "POST") Object.assign(state.config, body);
          return clone(state.config);
        }
        if (n === "maintenance") {
          if (method === "POST") {
            state.config.maintenanceEnabled = !!body.enabled;
            state.config.maintenanceMessage = body.message;
          }
          return {
            enabled: state.config.maintenanceEnabled,
            message: state.config.maintenanceMessage,
          };
        }
        if (n === "whitelist") {
          if (method === "POST") {
            if (body.action === "enable" || body.action === "disable")
              state.whitelist.enabled = body.action === "enable";
            else if (body.action === "add")
              state.whitelist.players.push(body.player);
            else if (body.action === "remove")
              state.whitelist.players = state.whitelist.players.filter(
                (x) => x !== body.player,
              );
          }
          return clone(state.whitelist);
        }
        if (n === "ops") {
          if (method === "POST") {
            if (body.action === "add") state.ops.push(body.player);
            else state.ops = state.ops.filter((x) => x !== body.player);
          }
          return { players: clone(state.ops), ops: clone(state.ops) };
        }
      }
      if (p === "/api/command") {
        requirePermission("EXECUTE_COMMANDS");
        audit("Симуляция команды: " + body.command);
        return {
          message:
            "Команда записана в журнал превью. На сервер она не отправлялась",
          output: ["[ПРЕВЬЮ] " + body.command],
        };
      }
      if (parts[1] === "logs") {
        requirePermission(
          n === "server" ? "VIEW_SERVER_LOGS" : "VIEW_WEB_LOGS",
        );
        return clone(state.logs).reverse();
      }
      if (parts[1] === "staff-audit") {
        requirePermission("VIEW_STAFF_AUDIT");
        return {
          logs: clone(state.logs),
          stats: { total: state.logs.length },
          admins: clone(state.admins),
        };
      }
      if (parts[1] === "auth" && n === "sessions") {
        requirePermission("MANAGE_ADMINS");
        return method === "GET" ? clone(state.sessions) : {};
      }
      if (p === "/api/auth/login-history") return clone(state.logs);
      if (parts[1] === "notifications") {
        if (method === "GET")
          return n === "unread-count"
            ? { count: state.notifications.filter((n) => !n.read).length }
            : { notifications: clone(state.notifications) };
        if (!n) {
          requirePermission("MANAGE_ADMINS");
          state.notifications.push({
            id: id(),
            ...clone(body),
            createdAt: clock(),
            read: false,
          });
        } else
          for (const notice of state.notifications)
            if (n === "read-all" || notice.id === Number(n)) notice.read = true;
        return {};
      }
      if (parts[1] === "loveauth") {
        requirePermission("MANAGE_LOVEAUTH");
        if (n === "status") return { available: true, preview: true };
        const pl = player(parts[3]);
        if (method !== "GET") {
          if (parts[4] === "unlock") pl.authLocked = false;
          else if (parts[4] === "delete") pl.authDeleted = true;
          else if (parts[4] === "reset-session") pl.authSession = false;
          else if (parts[4] === "password") pl.authSession = false;
          else if (parts[4] !== "unblock-ip") fail(404, "Действие не найдено");
          audit("LoveAuth · симуляция " + parts[4]);
          return {};
        }
        return {
          available: true,
          found: true,
          username: pl.name,
          uuid: pl.uuid,
          isRegistered: !pl.authDeleted,
          isLocked: pl.authLocked,
          isIpBlocked: false,
          hasDiscord: pl.hasDiscord,
          lastIp: pl.ip,
          alts: [],
        };
      }
      if (parts[1] === "vesuvio") {
        requirePermission(method === "GET" ? "VIEW_VESUVIO" : "MANAGE_VESUVIO");
        if (n === "suspects")
          return clone(
            state.players
              .filter((p) => p.suspect)
              .map((p) => ({
                ...p,
                vl: 20,
                risk: 60,
                trust: 40,
                status: "Тестовый подозреваемый",
              })),
          );
        if (n === "violations" || n === "punishments") return [];
        if (n === "player") {
          const pl =
            state.players.find((p) => p.uuid === parts[3]) || player(parts[3]);
          pl.suspect = !!body.suspect;
          return {};
        }
        return { available: true, preview: true };
      }
      if (parts[1] === "analytics") {
        requirePermission("VIEW_ANALYTICS");
        if (n === "top-players") return clone(state.players);
        if (n === "commands") return [{ command: "spawn", count: 12 }];
        return {
          totalPlayers: 3,
          uniquePlayers: 3,
          newPlayers: 1,
          totalPlaytimeHours: 30,
          avgPlaytimeMinutes: 600,
          peakOnline: 2,
          avgOnline: 1.5,
          currentOnline: 2,
          history: [],
          hourlyActivity: Array(24).fill(1),
          prevHourlyActivity: Array(24).fill(0),
          retentionRate: 66,
          periodLabel: "Превью",
          prevPeriodLabel: "Тестовый период",
          totalPlayersDatabase: 3,
          monthlyUniquePlayers: 3,
        };
      }
      if (parts[1] === "economy") {
        requirePermission(method === "GET" ? "VIEW_ECONOMY" : "MANAGE_ECONOMY");
        if (n === "history" || n === "transactions" || n === "anomalies")
          return [];
        if (method !== "GET") {
          const pl = player(body.player || body.playerName);
          pl.balance = Number(body.amount);
          audit("Тестовое изменение баланса " + pl.name);
          return {};
        }
        return {
          available: true,
          scaleVersion: 1,
          totalOnline: state.players
            .filter((p) => p.online)
            .reduce((s, p) => s + p.balance, 0),
          players: clone(state.players),
          history: [],
        };
      }
      if (["api-keys", "webhooks"].includes(parts[1])) {
        requirePermission("MANAGE_API");
        const list = parts[1] === "api-keys" ? state.keys : state.webhooks;
        if (method === "GET") return clone(list);
        if (method === "DELETE") {
          const i = list.findIndex((x) => x.id === Number(n));
          if (i >= 0) list.splice(i, 1);
          return {};
        }
        const item = {
          ...clone(body),
          id: id(),
          key: "PREVIEW-NOT-A-REAL-API-KEY",
          createdAt: clock(),
          isActive: true,
        };
        list.push(item);
        return clone(item);
      }
      fail(501, "Этот маршрут не моделируется: " + method + " " + p);
    }
    function request(method, path, body = {}, headers = {}) {
      try {
        const url = new URL(path, "https://preview.invalid");
        if (
          url.origin !== "https://preview.invalid" &&
          url.origin !== options.origin
        )
          fail(403, "Внешние запросы в превью запрещены");
        const normalized = {};
        for (const [k, v] of Object.entries(headers))
          normalized[k.toLowerCase()] = v;
        const data = route(method.toUpperCase(), url, body || {}, normalized);
        save();
        return { status: 200, body: { success: true, data: clone(data) } };
      } catch (error) {
        return {
          status: error.status || 500,
          body: {
            success: false,
            error: error.status ? error.message : "Ошибка модели превью",
          },
        };
      }
    }
    function scenario(name) {
      if (name === "reset") {
        state = seed(clock());
        save();
        return;
      }
      if (name === "login") {
        state.viewer = null;
        state.scenario = "default";
      } else if (name === "onboarding") {
        state.viewer = null;
        state.scenario = "onboarding";
      } else if (name === "report") createReport();
      else if (name === "ban") createBan({ targetName: "Ada123" });
      else if (name === "appeal") {
        const b =
          state.bans.find((b) => b.status === "ACTIVE") ||
          createBan({ targetName: "Ada123" });
        createAppeal({
          banId: b.id,
          reason: "Прошу проверить тестовую блокировку",
          accountVerified: true,
        });
      } else if (name === "unverified") {
        const b = createBan({ targetName: "Traveller" });
        createAppeal({
          banId: b.id,
          reason: "Апелляция по нику без привязки",
          accountVerified: false,
        });
      } else if (name === "discord-reply") {
        const a = state.appeals.find((a) => a.status === "PENDING");
        if (!a) fail(409, "Сначала создайте апелляцию");
        a.messages.push({
          id: id(),
          authorName: a.playerName,
          isStaff: false,
          message: "Ответ игрока из тестового Discord",
          createdAt: clock(),
        });
      } else if (name === "clarification-reply") {
        const r = state.reports.find((r) => r.status === "PENDING");
        if (!r) fail(409, "Сначала создайте жалобу");
        (state.discussion["report:" + r.id] ||= []).push({
          author: r.reporterName,
          body: "Событие произошло около спавна. Тестовый ответ Discord.",
          createdAt: clock(),
          isStaff: false,
        });
      } else if (name === "deliver") {
        for (const d of state.deliveries.filter((d) => d.state === "PENDING")) {
          if (d.type === "staff" && state.discord.quiet) continue;
          d.attempts++;
          d.state = state.discord.fail ? "FAILED" : "DELIVERED";
          d.error = state.discord.fail ? "Тестовая ошибка Discord" : "";
        }
      } else if (name === "failure") state.discord.fail = !state.discord.fail;
      else if (name === "quiet") state.discord.quiet = !state.discord.quiet;
      else if (name === "reconnect")
        for (const p of state.players) {
          p.online = p.isOnline = true;
          p.health = 20;
        }
      else if (name === "portal") {
        state.portalOneTimeCode = "preview-portal-" + id();
        save();
        return { code: state.portalOneTimeCode };
      } else fail(400, "Сценарий не найден");
      save();
    }
    function switchRole(n) {
      state.viewer = Number(n);
      state.pendingLogin = null;
      save();
      return me();
    }
    save();
    return {
      request,
      scenario,
      switchRole,
      snapshot: () => clone(state),
      permissions,
    };
  }
  return { create, seed, permissions };
});
