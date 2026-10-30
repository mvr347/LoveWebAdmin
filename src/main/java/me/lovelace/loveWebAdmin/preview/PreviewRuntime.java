package me.lovelace.loveWebAdmin.preview;

import java.nio.file.*;
import java.util.*;
import me.lovelace.loveWebAdmin.database.*;
import me.lovelace.loveWebAdmin.managers.AppealService;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.utils.*;

/** Server-authoritative sandbox. Owns isolated stores; has no Bukkit/server-command reference. */
public final class PreviewRuntime implements AutoCloseable, AppealService.Context {
  public static final class Rejected extends RuntimeException {
    public final int status;

    public Rejected(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  private final Path folder;
  private final DatabaseManager database;
  private final ModerationStore moderation;
  private final AppealService appeals;
  me.lovelace.loveWebAdmin.workflow.CaseWorkflowEngine workflows;
  final Map<String, Object> state;
  private final String namespace;
  private boolean closed;
  private final Set<String> requiredRights = new LinkedHashSet<>();
  private final String totpSecret;
  private final Map<String, String> passwords = new HashMap<>();
  private static final String INITIAL_HASH = PasswordUtils.hash("PreviewOnly!25");
  private final OperationStore operations;

  public PreviewRuntime(Path folder, String namespace) throws Exception {
    Files.createDirectories(folder);
    this.folder = folder;
    this.namespace = namespace;
    database = new DatabaseManager(folder);
    database.initialize();
    try {
      moderation = new ModerationStore(folder.resolve("moderation.db"));
    } catch (Exception error) {
      database.close();
      throw error;
    }
    try {
      operations = new OperationStore(folder.resolve("operations.db"));
    } catch (Exception error) {
      moderation.close();
      database.close();
      throw error;
    }
    appeals = new AppealService(this);
    try {
      String saved = database.loadPreviewState();
      if (saved == null)
        try (var input = getClass().getResourceAsStream("/preview/seed.json")) {
          saved =
              new String(
                  Objects.requireNonNull(input).readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
        }
      state = JsonUtils.parseObject(saved);
      state.put("pendingLogin", null);
      totpSecret = text(state, "_totpSecret", TotpUtils.generateSecret());
      state.put("_totpSecret", totpSecret);
      var savedPasswords =
          map(state.computeIfAbsent("_passwordHashes", k -> new LinkedHashMap<>()));
      for (var admin : list("admins")) {
        String name = text(admin, "username", "");
        passwords.put(name, text(savedPasswords, name, INITIAL_HASH));
      }
      savedPasswords.putAll(passwords);
      workflows =
          new me.lovelace.loveWebAdmin.workflow.CaseWorkflowEngine(
              database,
              moderation,
              folder.resolve("case-workflows.db"),
              me.lovelace.loveWebAdmin.workflow.CaseWaitPolicy.defaults(),
              (kind, id, action, staff, reason, seconds) -> {
                if (kind.equals("appeal"))
                  return action.equals("reduce")
                      ? appeals.reduce(id, staff, seconds, reason)
                      : appeals.decide(id, staff, action.equals("approve"), reason);
                if (!database.resolveReport(
                    id, action.equals("approve") ? "ACCEPTED" : "REJECTED", staff))
                  throw new Rejected(409, "Жалоба уже рассмотрена");
                database.recordReportVerdict(id, staff, reason);
                delivery("REPORT_DECISION", Map.of("reportId", id, "reason", reason));
                return "Тестовое решение сохранено";
              },
              (kind, id) ->
                  delivery(
                      kind.equals("appeal") ? "APPEAL" : "REPORT",
                      Map.of(kind.equals("appeal") ? "appealId" : "reportId", id)));
      persist();
    } catch (Exception error) {
      if (workflows != null)
        try {
          workflows.close();
        } catch (Exception close) {
          error.addSuppressed(close);
        }
      try {
        operations.close();
      } catch (Exception close) {
        error.addSuppressed(close);
      }
      try {
        moderation.close();
      } catch (Exception close) {
        error.addSuppressed(close);
      }
      database.close();
      throw error;
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> list(String key) {
    return (List<Map<String, Object>>) state.computeIfAbsent(key, k -> new ArrayList<>());
  }

  static int integer(Object value) {
    return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
  }

  static String text(Map<String, Object> body, String key, String fallback) {
    return body.get(key) == null ? fallback : String.valueOf(body.get(key));
  }

  long now() {
    return System.currentTimeMillis() / 1000;
  }

  int nextId() {
    int id = integer(state.get("nextId")) + 1;
    state.put("nextId", id);
    return id;
  }

  Map<String, Object> find(String key, String id) {
    return list(key).stream()
        .filter(x -> String.valueOf(x.get("id")).replace(".0", "").equals(id))
        .findFirst()
        .orElseThrow(() -> new Rejected(404, "Запись не найдена"));
  }

  public synchronized Map<String, Object> snapshot() {
    syncCases();
    var value = JsonUtils.parseObject(JsonUtils.toJson(state));
    value.keySet().removeIf(key -> key.startsWith("_"));
    return value;
  }

  private void persist() throws java.sql.SQLException {
    var document = new LinkedHashMap<String, Object>(state);
    for (String key : List.of("bans", "reports", "appeals", "deliveries")) document.remove(key);
    database.savePreviewState(JsonUtils.toJson(document));
  }

  Map<String, Object> viewer() {
    if (state.get("viewer") == null) throw new Rejected(401, "Войдите в тестовый аккаунт");
    var user = find("admins", String.valueOf(integer(state.get("viewer"))));
    if (!"ACTIVE".equals(user.get("status"))) throw new Rejected(401, "Тестовый аккаунт отключён");
    return user;
  }

  Map<String, Object> role() {
    return find("roles", String.valueOf(integer(viewer().get("roleId"))));
  }

  boolean has(String permission) {
    return Boolean.TRUE.equals(role().get("isOwner"))
        || ((List<?>) role().getOrDefault("permissions", List.of())).contains(permission);
  }

  void require(String permission) {
    if (!has(permission))
      throw new Rejected(403, "Тестовой роли недоступно это действие: " + permission);
    requiredRights.add(permission);
  }

  String actor() {
    return String.valueOf(viewer().get("username"));
  }

  Object safeRecord(Object record) {
    return JsonUtils.parseObject(JsonUtils.toJson(record));
  }

  private void syncCases() {
    try {
      state.put("deliveries", moderation.deliveries());
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    state.put("bans", database.getAllBans(null).stream().map(this::safeRecord).toList());
    state.put(
        "reports",
        database.getAllReports(null, null, 200, 0).stream().map(this::safeRecord).toList());
    state.put(
        "appeals",
        database.getAllAppeals(null, 200, 0).stream()
            .map(
                a -> {
                  var value = map(safeRecord(a));
                  try {
                    value.put("accountVerified", moderation.verified(a.id()));
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                  return value;
                })
            .toList());
  }

  public synchronized Object execute(
      String method, String path, Map<String, Object> body, Map<String, String> headers)
      throws Exception {
    if (closed) throw new Rejected(410, "Песочница закрыта");
    if (!Set.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(method))
      throw new Rejected(405, "Метод не поддерживается");
    requiredRights.clear();
    boolean mutation = !method.equals("GET");
    String
        actor = "sandbox:" + (state.get("viewer") == null ? "guest" : integer(state.get("viewer"))),
        key = headers.get("idempotency-key");
    if (mutation && !operations.allow(actor))
      throw new Rejected(429, "Слишком много действий в песочнице");
    if (mutation && key != null) {
      var previous =
          operations.reserve(
              actor,
              key,
              path,
              me.lovelace.loveWebAdmin.web.MutationRequests.fingerprint(
                  method,
                  path,
                  JsonUtils.toJson(body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      if (previous != null) {
        if (previous.permission() != null)
          for (String permission : previous.permission().split(","))
            if (!permission.isBlank()) require(permission);
        if (!previous.state().equals("COMPLETE"))
          throw new Rejected(409, "Результат требует проверки; действие не повторено");
        if (previous.status() >= 400)
          throw new Rejected(
              previous.status(),
              text(
                  JsonUtils.parseObject(previous.response()),
                  "error",
                  "Результат уже установлен; обновите данные"));
        return JsonUtils.parseObject(previous.response()).get("result");
      }
    }
    try {
      Object result = route(method, path, body, headers);
      persist();
      if (mutation && key != null) {
        var value = new LinkedHashMap<String, Object>();
        value.put("result", result);
        operations.complete(
            actor, key, 200, JsonUtils.toJson(value), String.join(",", requiredRights));
      }
      return result;
    } catch (Exception error) {
      if (mutation && key != null) operations.uncertain(actor, key);
      throw error;
    }
  }

  private Object route(
      String method, String raw, Map<String, Object> body, Map<String, String> headers)
      throws Exception {
    String path = raw.split("\\?", 2)[0].replaceFirst("^/api/", "");
    String[] parts = path.split("/");
    String scope = parts[0],
        id = parts.length > 1 ? parts[1] : "",
        action = parts.length > 2 ? parts[2] : "";
    boolean read = method.equals("GET");
    if (scope.equals("control")) {
      if (!method.equals("POST")) throw new Rejected(405, "Сценарии запускаются через POST");
      return control(text(body, "scenario", ""), body);
    }
    if (scope.equals("auth") && !Set.of("sessions", "login-history").contains(id))
      return auth(method, id, body);
    if (scope.equals("appeals") && method.equals("POST") && id.isEmpty())
      return createAppeal(body, false);
    if (scope.equals("me")) {
      var user = new LinkedHashMap<>(viewer());
      user.put("permissions", role().get("permissions"));
      user.put("isOwner", role().get("isOwner"));
      user.put("role", role().get("name"));
      user.put("roleName", role().get("name"));
      if (id.equals("preferences")) {
        if (read) return viewer().getOrDefault("uiPreferences", Map.of());
        viewer().put("uiPreferences", body.getOrDefault("uiPreferences", Map.of()));
        return Map.of();
      }
      if (id.equals("password") || id.equals("backup-codes")) return personal(id, body);
      if (id.equals("shift")) throw new Rejected(410, "Механика смен удалена");
      if (id.equals("sessions"))
        return List.of(Map.of("id", "preview", "current", true, "ip", "127.0.0.1"));
      return user;
    }
    if (!Set.of("appeals", "portal").contains(scope)) viewer();
    if (scope.equals("portal")) return PreviewPortal.route(this, method, parts, body, headers);
    if (scope.equals("operations")) {
      String owner = "sandbox:" + integer(viewer().get("id"));
      var operation =
          operations.get(owner, id).orElseThrow(() -> new Rejected(404, "Операция не найдена"));
      if (operation.permission() != null)
        for (String permission : operation.permission().split(","))
          if (!permission.isBlank()) require(permission);
      if (read)
        return Map.of(
            "state",
            operation.state(),
            "status",
            operation.status(),
            "route",
            operation.route(),
            "response",
            operation.response());
      if (!action.equals("resolve")
          || !operations.resolve(
              owner, id, Boolean.TRUE.equals(body.get("applied")), reason(body, "explanation")))
        throw new Rejected(409, "Результат уже установлен либо операция выполняется");
      return Map.of();
    }
    if (scope.equals("stats")) return PreviewFeatures.stats(this);
    if (scope.equals("bans")) {
      require(read ? "VIEW_BANS" : "MANAGE_BANS");
      if (id.equals("reasons"))
        return List.of(
            Map.of(
                "id",
                "cheats",
                "name",
                "Читы",
                "category",
                "Честная игра",
                "require_comment",
                false),
            Map.of(
                "id",
                "rules",
                "name",
                "Нарушение правил",
                "category",
                "Правила",
                "require_comment",
                false));
      if (id.equals("archive")) return Map.of("files", List.of());
      if (id.equals("appeal-templates"))
        return List.of(
            Map.of("id", 1, "title", "Разъяснение решения", "body", "Причина: {reason}"));
      if (id.isEmpty() || id.equals("all"))
        return read ? database.getAllBans(null) : createBan(body);
      int ban = integer(id);
      if (read) {
        var value =
            map(
                safeRecord(
                    database
                        .getBanById(ban)
                        .orElseThrow(() -> new Rejected(404, "Блокировка не найдена"))));
        value.put("revision", moderation.revision("ban", ban));
        value.put("history", moderation.banHistory(ban));
        value.put("sourceDisplay", "Сотрудник Preview");
        return value;
      }
      if (action.equals("correct")) {
        var old =
            database.getBanById(ban).orElseThrow(() -> new Rejected(404, "Блокировка не найдена"));
        if (!old.status().equals("ACTIVE")) throw new Rejected(409, "Блокировка уже закрыта");
        String why = reason(body, "explanation"), rule = reason(body, "ruleReason");
        long seconds = ((Number) body.getOrDefault("remainingSeconds", -1)).longValue();
        if (seconds != -1 && (seconds < 60 || seconds > 315360000L))
          throw new Rejected(400, "Неверный срок");
        if (!(body.get("revision") instanceof Number revision)
            || !moderation.reserveRevision("ban", ban, revision.longValue()))
          throw new Rejected(409, "Блокировка изменена; черновик сохранён");
        var changed =
            new WebBan(
                ban,
                old.targetName(),
                old.targetUuid(),
                old.targetIp(),
                old.creatorName(),
                rule,
                text(body, "description", ""),
                old.proofUrls(),
                old.isIpBan(),
                old.status(),
                seconds,
                old.createdAt(),
                seconds < 0 ? -1 : now() + seconds,
                old.linkedReportId());
        int history =
            moderation.prepareBanCorrection(
                ban, actor(), why, JsonUtils.toJson(old), JsonUtils.toJson(changed));
        if (!database.correctActiveBan(old, changed)) {
          moderation.finishBanCorrection(history, "ROLLED_BACK");
          throw new Rejected(409, "Блокировка изменена одновременно");
        }
        moderation.finishBanCorrection(history, "APPLIED");
        delivery(
            "BAN",
            Map.of(
                "banId", ban, "playerName", old.targetName(), "reason", rule, "correction", true));
        return changed;
      }
      if (action.equals("unban")) {
        if (!unban(ban, actor())) throw new Rejected(409, "Блокировка уже снята");
        return Map.of();
      }
    }
    if (scope.equals("appeals")) {
      int appealId = id.isEmpty() ? 0 : integer(id);
      boolean author = appealId > 0 && moderation.author(appealId, headers.get("x-appeal-token"));
      if (!author) require(read ? "VIEW_APPEALS" : "MANAGE_APPEALS");
      if (id.isEmpty())
        return Map.of(
            "appeals",
            database.getAllAppeals(null, 200, 0),
            "total",
            database.getAllAppeals(null, 200, 0).size());
      if (read && action.equals("evidence") && parts.length == 4) {
        var bytes =
            moderation
                .evidenceBytes("appeal", appealId, integer(parts[3]), author)
                .orElseThrow(() -> new Rejected(404, "Доказательство не найдено"));
        return Map.of(
            "mime",
            me.lovelace.loveWebAdmin.managers.CaseEvidence.imageMime(bytes),
            "base64",
            Base64.getEncoder().encodeToString(bytes));
      }
      if (!read && action.equals("review")) {
        if (!author) throw new Rejected(403, "Нужна ссылка автора");
        return Map.of(
            "id",
            workflows.requestReview(
                "appeal",
                appealId,
                "cap:" + ModerationStore.hash(headers.get("x-appeal-token")),
                body));
      }
      if (read) {
        var appeal =
            map(
                safeRecord(
                    database
                        .getAppealById(appealId)
                        .orElseThrow(() -> new Rejected(404, "Апелляция не найдена"))));
        appeal.put("evidence", moderation.evidence("appeal", appealId, true));
        appeal.put("messages", database.getAppealMessages(appealId));
        appeal.put("reviews", workflows.publicReviews("appeal", appealId));
        appeal.put("accountVerified", moderation.verified(appealId));
        return appeal;
      }
      if (action.equals("reply")) {
        String message = reason(body, "message");
        int messageId =
            database.addOpenAppealMessage(
                appealId,
                author ? database.getAppealById(appealId).orElseThrow().playerName() : actor(),
                !author,
                message);
        if (messageId <= 0) throw new Rejected(409, "Апелляция закрыта");
        delivery("APPEAL_MESSAGE", Map.of("appealId", appealId, "message", message));
        return Map.of("id", messageId);
      }
      if (!read && !action.equals("reply")) require("MANAGE_APPEALS");
      if (action.equals("approve")) require("MANAGE_BANS");
      if (action.equals("approve") || action.equals("reject"))
        return Map.of(
            "message",
            appeals.decide(
                appealId, actor(), action.equals("approve"), reason(body, "verdictMessage")));
      if (action.equals("reduce")) {
        require("MANAGE_BANS");
        return Map.of(
            "message",
            appeals.reduce(
                appealId,
                actor(),
                ((Number) body.get("remainingSeconds")).longValue(),
                reason(body, "verdictMessage")));
      }
    }
    if (scope.equals("reports")) {
      require(read ? "VIEW_REPORTS" : "MANAGE_REPORTS");
      if (id.isEmpty()) {
        var reports = database.getAllReports(null, null, 200, 0);
        return Map.of(
            "reports",
            reports,
            "stats",
            Map.of(
                "total",
                reports.size(),
                "pending",
                reports.stream().filter(v -> v.status().equals("PENDING")).count()));
      }
      int report = integer(id);
      if (read)
        return Map.of(
            "report",
            database
                .getReportById(report)
                .orElseThrow(() -> new Rejected(404, "Жалоба не найдена")),
            "chatLogs",
            List.of());
      if (action.equals("accept") || action.equals("reject")) {
        database.resolveReport(report, action.equals("accept") ? "ACCEPTED" : "REJECTED", actor());
        return Map.of();
      }
    }
    if (scope.equals("cases") && Set.of("templates", "deliveries").contains(id))
      return PreviewFeatures.tools(this, method, parts, body);
    if (scope.equals("cases")) {
      String kind = id;
      int caseId = integer(action);
      String verb = parts.length > 3 ? parts[3] : "";
      require((read ? "VIEW_" : "MANAGE_") + (kind.equals("report") ? "REPORTS" : "APPEALS"));
      if (!Set.of("report", "appeal").contains(kind)) throw new Rejected(404, "Тип дела не найден");
      String status =
          kind.equals("report")
              ? database
                  .getReportById(caseId)
                  .map(PlayerReport::status)
                  .orElseThrow(() -> new Rejected(404, "Дело не найдено"))
              : database
                  .getAppealById(caseId)
                  .map(WebBanAppeal::status)
                  .orElseThrow(() -> new Rejected(404, "Дело не найдено"));
      if (read && parts.length == 5 && verb.equals("review-image")) {
        var bytes =
            workflows
                .store()
                .reviewImage(kind, caseId, integer(parts[4]))
                .orElseThrow(() -> new Rejected(404, "Скриншот не найден"));
        return Map.of(
            "mime",
            me.lovelace.loveWebAdmin.managers.CaseEvidence.imageMime(bytes),
            "base64",
            Base64.getEncoder().encodeToString(bytes));
      }
      if (Set.of(
              "workflow",
              "decision-preview",
              "decision-confirm",
              "decision-cancel",
              "reply-template",
              "reply-template-delete",
              "review-accept",
              "review-reject")
          .contains(verb)) return PreviewWorkflowRoutes.route(this, read, kind, caseId, verb, body);
      if (!read && !status.equals("PENDING")) throw new Rejected(409, "Дело уже закрыто");
      if (read) {
        if (parts.length == 5 && verb.equals("evidence")) {
          var bytes =
              moderation
                  .evidenceBytes(kind, caseId, integer(parts[4]), false)
                  .orElseThrow(() -> new Rejected(404, "Доказательство не найдено"));
          return Map.of(
              "mime",
              me.lovelace.loveWebAdmin.managers.CaseEvidence.imageMime(bytes),
              "base64",
              Base64.getEncoder().encodeToString(bytes));
        }
        return Map.of(
            "revision",
            moderation.revision(kind, caseId),
            "evidence",
            moderation.evidence(kind, caseId, false),
            "work",
            moderation.work(kind, caseId),
            "notes",
            moderation.notes(kind, caseId),
            "discussion",
            kind.equals("report") ? database.reportDiscussion(caseId) : List.of(),
            "related",
            kind.equals("report") ? database.relatedReports(caseId) : List.of());
      }
      if (body.get("revision") instanceof Number revision
          && !moderation.reserveRevision(kind, caseId, revision.longValue()))
        throw new Rejected(409, "Дело изменилось; черновик сохранён");
      if (verb.equals("evidence")) {
        int evidence =
            me.lovelace.loveWebAdmin.managers.CaseEvidence.add(
                moderation, kind, caseId, actor(), body);
        if ("AUTHOR".equals(body.get("visibility")))
          delivery(
              "IMAGE".equals(body.get("type"))
                  ? "CASE_IMAGE"
                  : kind.equals("appeal") ? "APPEAL_MESSAGE" : "REPORT_MESSAGE",
              Map.of(
                  kind.equals("appeal") ? "appealId" : "reportId",
                  caseId,
                  "evidenceId",
                  evidence,
                  "message",
                  text(body, "value", "Доказательство")));
        return Map.of("id", evidence);
      }
      if (verb.equals("claim")) {
        if (!moderation.claim(kind, caseId, actor())) throw new Rejected(409, "Дело уже занято");
        return Map.of();
      }
      if (verb.equals("note")) {
        moderation.note(kind, caseId, actor(), reason(body, "text"));
        return Map.of();
      }
      if (verb.equals("work")) {
        if (!moderation.updateWork(
            kind,
            caseId,
            actor(),
            text(body, "assignee", actor()),
            text(body, "priority", "NORMAL"),
            text(body, "state", "IN_PROGRESS"))) throw new Rejected(409, "Дело закрыто");
        return Map.of();
      }
      if (verb.equals("clarify")) {
        String message = reason(body, "text");
        database.addReportDiscussion(caseId, actor(), true, message, namespace + ":" + nextId());
        delivery("REPORT_MESSAGE", Map.of("reportId", caseId, "message", message));
        return Map.of();
      }
      if (verb.equals("merge")) {
        if (!database.mergeReports(caseId, integer(body.get("targetId")), actor()))
          throw new Rejected(409, "Жалобы нельзя объединить");
        return Map.of();
      }
    }
    if (scope.equals("server")) {
      if (!id.equals("config") || read)
        require(
            id.equals("config")
                ? "VIEW_SERVER_SETTINGS"
                : id.equals("maintenance")
                    ? "MANAGE_MAINTENANCE"
                    : id.equals("ops") ? "MANAGE_OPERATORS" : "MANAGE_WHITELIST");
      var config = map(state.get("config"));
      if (id.equals("config")) {
        if (!read) {
          for (String field : body.keySet())
            require(
                me.lovelace
                    .loveWebAdmin
                    .web
                    .handlers
                    .ApiServerOpsHandler
                    .configPermission(field)
                    .name());
          config.putAll(body);
        }
        return config;
      }
      if (id.equals("maintenance")) {
        if (!read) {
          config.put("maintenanceEnabled", body.get("enabled"));
          config.put("maintenanceMessage", body.get("message"));
        }
        return Map.of(
            "enabled",
            config.get("maintenanceEnabled"),
            "message",
            config.get("maintenanceMessage"));
      }
      if (Set.of("whitelist", "ops").contains(id))
        return PreviewFeatures.accessList(this, id, read, body);
    }
    if (scope.equals("command")) {
      require("EXECUTE_COMMANDS");
      audit(actor(), "Смоделирована команда: " + text(body, "command", ""));
      return Map.of("message", "Команда смоделирована; игровой сервер не изменён");
    }
    return PreviewFeatures.route(this, method, raw, parts, body);
  }

  private Object auth(String method, String action, Map<String, Object> body) {
    if (!action.equals("status") && !action.equals("check-username") && !method.equals("POST"))
      throw new Rejected(405, "Используйте POST");
    if (action.equals("status"))
      return Map.of(
          "ownerExists",
          !"onboarding".equals(state.get("scenario")),
          "initialSetupNeeded",
          "onboarding".equals(state.get("scenario")),
          "preview",
          true,
          "previewEnabled",
          true,
          "maintenance",
          false);
    if (action.equals("logout")) {
      state.put("viewer", null);
      return Map.of();
    }
    if (action.equals("login")) {
      var user =
          list("admins").stream()
              .filter(
                  a ->
                      text(a, "username", "").equals(text(body, "username", ""))
                          && "ACTIVE".equals(a.get("status")))
              .findFirst()
              .orElseThrow(() -> new Rejected(401, "Тестовый аккаунт не найден"));
      if (!PasswordUtils.verify(
          text(body, "password", ""), passwords.get(text(user, "username", ""))))
        throw new Rejected(401, "Неверный тестовый пароль");
      state.put("pendingLogin", user.get("id"));
      return Map.of(
          "status", "NEED_2FA", "challenge", "preview-auth", "username", user.get("username"));
    }
    if (action.equals("verify-2fa")) {
      if (state.get("pendingLogin") == null) throw new Rejected(401, "Сначала введите пароль");
      String username =
          text(find("admins", String.valueOf(integer(state.get("pendingLogin")))), "username", "");
      String code = text(body, "code", text(body, "totpCode", ""));
      if (!text(body, "challenge", "").equals("preview-auth")
          || body.containsKey("username") && !username.equals(body.get("username")))
        throw new Rejected(401, "Неверный тестовый запрос 2FA");
      boolean verified = TotpUtils.verifyCode(totpSecret, code);
      if (!verified) {
        Object stored =
            map(state.computeIfAbsent("_backupCodes", k -> new LinkedHashMap<>())).get(username);
        if (stored instanceof List<?> codes)
          verified = codes.remove(TotpUtils.hashBackupCode(code));
      }
      if (!verified) throw new Rejected(401, "Неверный или использованный тестовый код 2FA");
      state.put("viewer", state.remove("pendingLogin"));
      return Map.of(
          "token",
          "preview-session-" + integer(state.get("viewer")),
          "username",
          actor(),
          "permissions",
          role().get("permissions"));
    }
    if (Set.of("prepare-setup-owner", "validate-invite").contains(action)) {
      var invite = action.equals("validate-invite") ? invitation(body) : null;
      return Map.of(
          "valid",
          true,
          "username",
          invite == null ? text(body, "username", "NewModerator") : invite.get("username"),
          "roleName",
          invite == null
              ? "Управляющий"
              : find("roles", String.valueOf(integer(invite.get("roleId")))).get("name"),
          "totpSecret",
          totpSecret,
          "secret",
          totpSecret,
          "otpUrl",
          TotpUtils.getOtpAuthUrl("LoveWebAdmin Preview", "Test", totpSecret));
    }
    if (Set.of("setup-owner", "register").contains(action)) {
      if (action.equals("setup-owner") && !"onboarding".equals(state.get("scenario")))
        throw new Rejected(409, "Управляющий уже создан");
      if (!TotpUtils.verifyCode(totpSecret, text(body, "totpCode", "")))
        throw new Rejected(400, "Неверный тестовый код 2FA");
      var invite = action.equals("register") ? invitation(body) : null;
      String name = invite == null ? text(body, "username", "") : text(invite, "username", "");
      if (!name.matches("[A-Za-z0-9_]{3,24}") || passwords.containsKey(name))
        throw new Rejected(400, "Неверный или занятый ник");
      String password = text(body, "password", "");
      String invalid = PasswordUtils.validateStrength(password);
      if (invalid != null) throw new Rejected(400, invalid);
      int id = nextId();
      var user =
          new LinkedHashMap<String, Object>(
              Map.of(
                  "id",
                  id,
                  "username",
                  name,
                  "roleId",
                  invite == null ? 1 : integer(invite.get("roleId")),
                  "status",
                  "ACTIVE",
                  "totpEnabled",
                  true,
                  "uiPreferences",
                  new LinkedHashMap<>()));
      list("admins").add(user);
      passwords.put(name, PasswordUtils.hash(password));
      map(state.get("_passwordHashes")).put(name, passwords.get(name));
      state.put("viewer", id);
      state.put("scenario", "default");
      if (invite != null) invite.put("status", "USED");
      var codes = personal("backup-codes", Map.of());
      return Map.of(
          "token",
          "preview-session-" + id,
          "status",
          "ACTIVE",
          "backupCodes",
          map(codes).get("backupCodes"));
    }
    if (action.equals("check-username"))
      return Map.of("available", true, "message", "Проверка тестового имени");
    throw new Rejected(404, "Маршрут входа не найден");
  }

  private Map<String, Object> invitation(Map<String, Object> body) {
    String code = text(body, "inviteCode", text(body, "code", ""));
    return list("invites").stream()
        .filter(
            invite ->
                code.equals(invite.get("code"))
                    && "PENDING".equals(invite.get("status"))
                    && ((Number) invite.get("expiresAt")).longValue() > now())
        .findFirst()
        .orElseThrow(() -> new Rejected(400, "Приглашение не найдено, использовано или истекло"));
  }

  void setPassword(String username, String password) {
    String invalid = PasswordUtils.validateStrength(password);
    if (invalid != null) throw new Rejected(400, invalid);
    passwords.put(username, PasswordUtils.hash(password));
    map(state.get("_passwordHashes")).put(username, passwords.get(username));
  }

  public synchronized Object control(String scenario, Map<String, Object> body) throws Exception {
    Object result;
    switch (scenario) {
      case "run-checks" -> result = PreviewChecks.run(folder);
      case "role" -> {
        int role = integer(body.get("id"));
        find("admins", String.valueOf(role));
        state.put("viewer", role);
        result = Map.of();
      }
      case "ban" ->
          result = createBan(Map.of("targetName", "Ada123", "ruleReason", "Тестовая блокировка"));
      case "report" -> {
        int report =
            database.createPlayerReport(
                "preview-reporter",
                "Traveller",
                "127.0.0.1",
                "preview-player",
                "Ada123",
                List.of("Тестовая жалоба"),
                "Проверка обращения",
                true,
                0);
        delivery("REPORT", Map.of("reportId", report, "playerName", "Ada123"));
        result = Map.of("id", report);
      }
      case "appeal", "unverified" -> {
        if (database.getActiveBan("Ada123").isEmpty())
          createBan(Map.of("targetName", "Ada123", "ruleReason", "Тестовая блокировка"));
        result =
            createAppeal(
                Map.of("playerName", "Ada123", "reason", "Прошу проверить тестовую блокировку"),
                scenario.equals("appeal"));
      }
      case "reconnect" -> {
        for (var p : list("players")) {
          p.put("online", true);
          p.put("isOnline", true);
          p.put("health", 20);
        }
        result = Map.of();
      }
      case "failure" -> {
        var discord = map(state.get("discord"));
        discord.put("fail", !Boolean.TRUE.equals(discord.get("fail")));
        result = Map.of();
      }
      case "quiet" -> {
        var discord = map(state.get("discord"));
        discord.put("quiet", !Boolean.TRUE.equals(discord.get("quiet")));
        result = Map.of();
      }
      case "onboarding" -> {
        state.put("viewer", null);
        state.put("scenario", "onboarding");
        result = Map.of("totpSecret", totpSecret);
      }
      case "deliver" -> result = deliveryStatus();
      case "portal" -> result = Map.of("code", moderation.portalLink("1234567890", null));
      case "discord-reply", "clarification-reply" -> {
        boolean appeal = scenario.equals("discord-reply");
        if (appeal) {
          var current = database.getAllAppeals("PENDING", 1, 0);
          if (current.isEmpty()) throw new Rejected(409, "Сначала создайте апелляцию");
          database.addDiscordAppealMessage(
              current.getFirst().id(),
              "simulated:" + nextId(),
              "Тестовый игрок",
              false,
              "Смоделированный ответ; из Discord не получен");
        } else {
          var current = database.getAllReports("PENDING", null, 1, 0);
          if (current.isEmpty()) throw new Rejected(409, "Сначала создайте жалобу");
          database.addReportDiscussion(
              current.getFirst().id(),
              "Тестовый игрок",
              false,
              "Смоделированное уточнение; из Discord не получено",
              "simulated:" + nextId());
        }
        result = Map.of("simulated", true);
      }
      case "login" -> {
        state.put("viewer", null);
        state.put("pendingLogin", null);
        result =
            Map.of(
                "totpSecret",
                totpSecret,
                "otpAuthUrl",
                TotpUtils.getOtpAuthUrl("LoveWebAdmin Preview", "Test", totpSecret));
      }
      default -> throw new Rejected(501, "Сценарий не поддерживается: " + scenario);
    }
    persist();
    return result;
  }

  private WebBan createBan(Map<String, Object> body) throws Exception {
    require("MANAGE_BANS");
    String player = text(body, "targetName", "");
    if (!player.matches("[A-Za-z0-9_]{1,16}")) throw new Rejected(400, "Укажите ник игрока");
    long duration = body.get("durationSeconds") instanceof Number n ? n.longValue() : -1;
    var ban =
        database.saveBan(
            new WebBan(
                0,
                player,
                UUID.nameUUIDFromBytes(player.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString(),
                "127.0.0.1",
                actor(),
                text(body, "ruleReason", "Проверка Preview"),
                text(body, "description", ""),
                "[]",
                false,
                "ACTIVE",
                duration,
                now(),
                duration > 0 ? now() + duration : -1));
    moderation.source(ban.id(), "STAFF", actor(), namespace + ":ban:" + ban.id());
    delivery("BAN", Map.of("banId", ban.id(), "playerName", player, "reason", ban.ruleReason()));
    return ban;
  }

  private Object createAppeal(Map<String, Object> body, boolean verified) throws Exception {
    var ban =
        database
            .getActiveBan(text(body, "playerName", "Ada123"))
            .orElseThrow(() -> new Rejected(400, "Действующая блокировка не найдена"));
    String reason = reason(body, "reason");
    int id = database.createAppeal(ban.id(), ban.targetUuid(), ban.targetName(), reason);
    if (id <= 0) throw new Rejected(409, "Апелляция уже открыта");
    String access = moderation.createAccess(id, "1234567890", verified);
    database.addAppealMessage(id, ban.targetName(), false, reason);
    delivery("APPEAL", Map.of("appealId", id, "playerName", ban.targetName(), "reason", reason));
    var value = map(safeRecord(database.getAppealById(id).orElseThrow()));
    value.put("accessToken", access);
    value.put("accountVerified", verified);
    return value;
  }

  Object personal(String id, Map<String, Object> body) {
    String username = text(viewer(), "username", "");
    if (id.equals("backup-codes")) {
      var codes = TotpUtils.generateBackupCodes(8);
      map(state.computeIfAbsent("_backupCodes", k -> new LinkedHashMap<>()))
          .put(username, new ArrayList<>(codes.stream().map(TotpUtils::hashBackupCode).toList()));
      return Map.of("backupCodes", codes);
    }
    if (!PasswordUtils.verify(
            text(body, "oldPassword", text(body, "currentPassword", "")), passwords.get(username))
        || !TotpUtils.verifyCode(totpSecret, text(body, "totpCode", text(body, "code", ""))))
      throw new Rejected(400, "Неверный тестовый пароль или код 2FA");
    if (id.equals("password")) {
      String next = text(body, "newPassword", "");
      String invalid = PasswordUtils.validateStrength(next);
      if (invalid != null) throw new Rejected(400, invalid);
      passwords.put(username, PasswordUtils.hash(next));
      map(state.get("_passwordHashes")).put(username, passwords.get(username));
      return Map.of("message", "Пароль изменён только в песочнице");
    }
    return Map.of("backupCodes", TotpUtils.generateBackupCodes(8));
  }

  static String reason(Map<String, Object> body, String key) {
    String value = text(body, key, "").trim();
    if (value.length() < 5 || value.length() > 4000)
      throw new Rejected(400, "Введите объяснение от 5 до 4000 символов");
    return value;
  }

  void delivery(String event, Map<String, Object> payload) throws Exception {
    moderation.enqueue(
        namespace + ":" + event + ":" + nextId(),
        "preview",
        JsonUtils.toJson(Map.of("event", event, "data", payload)));
  }

  public synchronized List<Map<String, Object>> dueDeliveries() throws Exception {
    return closed ? List.of() : moderation.due();
  }

  public synchronized boolean takeDelivery(int id) throws Exception {
    return !closed && moderation.takeDelivery(id);
  }

  public synchronized void uncertainDelivery(int id, String reason) throws Exception {
    moderation.uncertainDelivery(id, reason);
  }

  public synchronized void delivered(int id, boolean ok, int attempts, String error)
      throws Exception {
    moderation.delivered(id, ok, attempts, error);
  }

  public synchronized String channel(String kind, int id) {
    return String.valueOf(
        map(state.computeIfAbsent("discordTickets", k -> new LinkedHashMap<>()))
            .getOrDefault(kind + ":" + id, ""));
  }

  Path folder() {
    return folder;
  }

  public synchronized int discordCase(String kind, String nick, String text, String owner)
      throws Exception {
    if (closed) throw new Rejected(410, "Песочница закрыта");
    if (!Set.of("report", "appeal").contains(kind)
        || !nick.matches("[A-Za-z0-9_]{3,16}")
        || text.trim().length() < 5
        || text.length() > 2000)
      throw new IllegalArgumentException("Тестовый ник: 3–16 символов, описание: 5–2000 символов");
    String uuid =
        java.util
            .UUID
            .nameUUIDFromBytes((namespace + nick).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    int id;
    if (kind.equals("report")) {
      id =
          database.createPlayerReport(
              "preview-discord:" + owner,
              "Тестовый автор",
              "127.0.0.1",
              uuid,
              nick,
              List.of("Тестовая жалоба"),
              text,
              false,
              0);
      moderation.ticketOwner(kind, id, owner);
    } else {
      var ban =
          database.saveBan(
              new WebBan(
                  0,
                  nick,
                  uuid,
                  null,
                  "Система",
                  "Тестовая блокировка",
                  "",
                  "[]",
                  false,
                  "ACTIVE",
                  3600,
                  now(),
                  now() + 3600));
      moderation.source(ban.id(), "CONSOLE", "Система", null);
      id = database.createAppeal(ban.id(), uuid, nick, text);
      moderation.createAccess(id, owner, false);
      database.addAppealMessage(id, nick, false, text);
    }
    if (id < 1) throw new IllegalStateException("Тестовое дело не сохранено");
    persist();
    return id;
  }

  public synchronized boolean isClosed() {
    return closed;
  }

  public synchronized void channel(String kind, int id, String channel) throws Exception {
    if (closed) throw new Rejected(410, "Песочница закрыта");
    map(state.computeIfAbsent("discordTickets", k -> new LinkedHashMap<>()))
        .put(kind + ":" + id, channel);
    if (kind.equals("appeal")) database.setAppealDiscordChannelId(id, channel);
    persist();
  }

  public synchronized void incoming(
      String kind, int id, String event, String author, String message) throws Exception {
    if (closed) return;
    if (kind.equals("appeal")) database.addDiscordAppealMessage(id, event, author, false, message);
    else database.addReportDiscussion(id, author, false, message, event);
    persist();
  }

  public synchronized boolean simulatedDiscordFailure() {
    return Boolean.TRUE.equals(map(state.get("discord")).get("fail"));
  }

  public synchronized List<Map<String, Object>> deliveryStatus() throws Exception {
    return moderation.deliveries();
  }

  @Override
  public DatabaseManager database() {
    return database;
  }

  @Override
  public ModerationStore moderation() {
    return moderation;
  }

  @Override
  public boolean unban(int ban, String actor) {
    return database.unban(ban);
  }

  @Override
  public void reduce(int id, String actor, long seconds) {
    var ban = database.getBanById(id).orElseThrow();
    if (seconds < 1 || ban.expiresAt() > 0 && now() + seconds >= ban.expiresAt())
      throw new Rejected(400, "Новый срок должен быть меньше текущего");
    database.saveBan(
        new WebBan(
            id,
            ban.targetName(),
            ban.targetUuid(),
            ban.targetIp(),
            ban.creatorName(),
            ban.ruleReason(),
            ban.description(),
            ban.proofUrls(),
            ban.isIpBan(),
            ban.status(),
            seconds,
            ban.createdAt(),
            now() + seconds,
            ban.linkedReportId()));
  }

  @Override
  public void finish(WebBanAppeal appeal, String message) {
    try {
      delivery("APPEAL_DECISION", Map.of("appealId", appeal.id(), "message", message));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  @Override
  public void audit(String actor, String message) {
    var logs = list("logs");
    logs.add(Map.of("actor", actor, "action", message, "createdAt", now()));
    if (logs.size() > 200) logs.subList(0, logs.size() - 200).clear();
  }

  @Override
  public void failure(String message) {
    audit("Система", message);
  }

  @Override
  public synchronized void close() throws Exception {
    if (closed) return;
    closed = true;
    try {
      persist();
    } finally {
      try {
        if (workflows != null) workflows.close();
      } finally {
      try {
        operations.close();
      } finally {
        try {
          moderation.close();
        } finally {
          database.close();
          passwords.clear();
        }
      }
    }
    }
  }
}
