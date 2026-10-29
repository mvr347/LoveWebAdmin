package me.lovelace.loveWebAdmin.workflow;

import java.nio.file.Path;
import java.util.*;
import java.util.function.LongSupplier;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.database.*;
import me.lovelace.loveWebAdmin.managers.CaseEvidence;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/**
 * Caller authenticates permissions; this engine owns immutable previews and workflow invariants.
 */
public final class CaseWorkflowEngine implements AutoCloseable {
  @FunctionalInterface
  public interface Decision {
    String apply(String kind, int id, String action, String staff, String reason, long seconds)
        throws Exception;
  }

  @FunctionalInterface
  public interface Created {
    void accept(String kind, int id) throws Exception;
  }

  public record Reminder(
      String kind, int id, long version, int stage, String mode, long since, String assignee) {}

  private final DatabaseManager database;
  private final ModerationStore moderation;
  private final CaseWorkflowStore store;
  private final Decision decision;
  private final Created created;
  private final LongSupplier clock;
  private final CaseWaitPolicy policy;
  private volatile boolean closed;

  public CaseWorkflowEngine(
      DatabaseManager database,
      ModerationStore moderation,
      Path file,
      CaseWaitPolicy policy,
      Decision decision,
      Created created)
      throws Exception {
    this(
        database,
        moderation,
        file,
        policy,
        decision,
        created,
        () -> System.currentTimeMillis() / 1000);
  }

  public CaseWorkflowEngine(
      DatabaseManager database,
      ModerationStore moderation,
      Path file,
      CaseWaitPolicy policy,
      Decision decision,
      Created created,
      LongSupplier clock)
      throws Exception {
    this.database = database;
    this.moderation = moderation;
    this.store = new CaseWorkflowStore(file);
    this.policy = policy;
    this.decision = decision;
    this.created = created;
    this.clock = clock;
  }

  public CaseWorkflowStore store() {
    return store;
  }

  public static CaseWorkflowEngine live(LoveWebAdmin plugin) throws Exception {
    CaseWaitPolicy policy;
    try {
      policy = CaseWaitPolicy.from(plugin.getConfig());
    } catch (IllegalArgumentException error) {
      plugin
          .getLogger()
          .warning("Некорректные сроки обращений; используются 20 минут / 60 минут / 48 часов");
      policy = CaseWaitPolicy.defaults();
    }
    return new CaseWorkflowEngine(
        plugin.getDatabaseManager(),
        plugin.moderation(),
        plugin.getDataFolder().toPath().resolve("case-workflows.db"),
        policy,
        (kind, id, action, staff, reason, seconds) -> {
          String result;
          if (kind.equals("appeal"))
            result =
                action.equals("reduce")
                    ? plugin.appeals().reduce(id, staff, seconds, reason)
                    : plugin.appeals().decide(id, staff, action.equals("approve"), reason);
          else {
            boolean applied =
                plugin
                    .main()
                    .call(
                        () ->
                            plugin
                                .getReportManager()
                                .resolveReport(
                                    id,
                                    action.equals("approve") ? "ACCEPTED" : "REJECTED",
                                    staff,
                                    null))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!applied) throw new IllegalArgumentException("Жалоба уже рассмотрена");
            plugin.getDatabaseManager().recordReportVerdict(id, staff, reason);
            result = action.equals("approve") ? "Жалоба подтверждена" : "Жалоба отклонена";
          }
          plugin.moderation().note(kind, id, staff, "Решение: " + result + ". " + reason);
          plugin
              .getLogManager()
              .logWebAction(staff, "Решение по обращению " + kind + " №" + id + ": " + reason);
          plugin
              .getDiscordBridge()
              .ticketDesk()
              .finish(kind, id, "Решение сотрудника " + staff + ": " + reason);
          return result;
        },
        (kind, id) -> {
          if (kind.equals("appeal"))
            plugin
                .getDiscordBridge()
                .createAppealTicket(plugin.getDatabaseManager().getAppealById(id).orElseThrow());
          else if (plugin.moderation().ticket(kind, id).isPresent())
            plugin
                .getDiscordBridge()
                .createReportTicket(plugin.getDatabaseManager().getReportById(id).orElseThrow());
        });
  }

  private void ready() {
    if (closed) throw new IllegalStateException("Работа с обращениями остановлена");
  }

  private Map<String, Object> snapshot(String kind, int id) throws Exception {
    ready();
    if (!Set.of("report", "appeal").contains(kind))
      throw new IllegalArgumentException("Неизвестный тип обращения");
    var value = new LinkedHashMap<String, Object>();
    value.put("kind", kind);
    value.put("id", id);
    value.put("revision", moderation.revision(kind, id));
    value.put("assignee", moderation.work(kind, id).get("assignee"));
    if (kind.equals("report")) {
      var report =
          database
              .getReportById(id)
              .orElseThrow(() -> new IllegalArgumentException("Жалоба не найдена"));
      value.put("status", report.status());
      value.put("target", report.targetName());
      value.put("reporter", report.reporterName());
    } else {
      var appeal =
          database
              .getAppealById(id)
              .orElseThrow(() -> new IllegalArgumentException("Апелляция не найдена"));
      var ban = database.getBanById(appeal.banId()).orElseThrow();
      value.put("status", appeal.status());
      value.put("target", appeal.playerName());
      value.put("banId", ban.id());
      value.put("banStatus", ban.status());
      value.put("expiry", ban.expiresAt());
      value.put("banReason", Objects.toString(ban.ruleReason(), ""));
      value.put("source", moderation.source(ban.id()));
    }
    return value;
  }

  public boolean open(String kind, int id) throws Exception {
    return snapshot(kind, id).get("status").equals("PENDING");
  }

  public void independent(String kind, int id, String staff) throws Exception {
    if (kind.equals("appeal")) {
      var appeal = database.getAppealById(id).orElseThrow();
      var source = moderation.source(appeal.banId());
      if (source.get("kind").equals("STAFF")
          && staff.equalsIgnoreCase(source.get("actor").toString()))
        throw new IllegalArgumentException(
            "Своё наказание рассматривает другой сотрудник или руководство");
    } else {
      var report = database.getReportById(id).orElseThrow();
      if (report.targetName().equalsIgnoreCase(staff))
        throw new IllegalArgumentException("Жалобу на себя рассматривает другой сотрудник");
    }
  }

  private static String hash(Map<String, Object> value) {
    return ModerationStore.hash(JsonUtils.toJson(value));
  }

  public synchronized Map<String, Object> preview(
      String kind, int id, String actor, String staff, String action, String reason, long seconds)
      throws Exception {
    independent(kind, id, staff);
    var before = snapshot(kind, id);
    if (!before.get("status").equals("PENDING"))
      throw new IllegalArgumentException("Обращение уже закрыто");
    if (!Set.of("approve", "reject", "reduce").contains(action)
        || kind.equals("report") && action.equals("reduce"))
      throw new IllegalArgumentException("Неизвестное решение");
    if (reason == null || reason.trim().length() < 5 || reason.length() > 2000)
      throw new IllegalArgumentException("Объясните решение (5–2000 символов)");
    long now = clock.getAsLong(), newEnd = 0;
    if (kind.equals("appeal")
        && !action.equals("reject")
        && ((Map<?, ?>) before.get("source")).get("kind").equals("SECURITY"))
      throw new IllegalArgumentException("Ограничение снимается через раздел безопасности");
    if (action.equals("reduce")) {
      if (seconds <= 0 || seconds > 315360000L || !before.get("banStatus").equals("ACTIVE"))
        throw new IllegalArgumentException("Некорректный срок или наказание не активно");
      newEnd = now + seconds;
      long old = ((Number) before.get("expiry")).longValue();
      if (old > 0 && newEnd >= old)
        throw new IllegalArgumentException("Новый срок должен быть короче действующего");
    }
    String effect =
        kind.equals("report")
            ? "Наказание не изменится"
            : action.equals("reject")
                ? "Наказание не изменится"
                : action.equals("reduce")
                    ? "Будет сокращён срок наказания"
                    : before.get("banStatus").equals("ACTIVE")
                        ? "Блокировка будет снята"
                        : "Наказание уже не активно; изменится только решение обращения";
    var payload = new LinkedHashMap<String, Object>();
    payload.put("kind", kind);
    payload.put("id", id);
    payload.put("staff", staff);
    payload.put("action", action);
    payload.put("reason", reason.trim());
    payload.put("targetExpiry", newEnd);
    payload.put("before", before);
    payload.put("effect", effect);
    payload.put("closesCase", true);
    payload.put("playerMessage", reason.trim());
    String key = store.prepare(actor, payload, hash(before), now);
    payload.put("key", key);
    return payload;
  }

  public synchronized String confirm(String key, String actor, String staff) throws Exception {
    var prepared = store.prepared(key, actor, clock.getAsLong());
    var p = prepared.payload();
    if (!staff.equals(p.get("staff")))
      throw new IllegalArgumentException("Учётная запись изменилась; создайте новый предпросмотр");
    if (prepared.state().equals("DONE")) return prepared.result();
    if (!prepared.state().equals("DRAFT"))
      throw new IllegalArgumentException("Результат применения требует сверки; повтор не выполнен");
    String kind = p.get("kind").toString();
    int id = ((Number) p.get("id")).intValue();
    independent(kind, id, staff);
    if (!hash(snapshot(kind, id)).equals(prepared.hash()))
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
          "Дело или наказание изменились. Обновите предпросмотр; текст решения сохранён");
    long seconds = 0;
    if (p.get("action").equals("reduce")) {
      seconds = ((Number) p.get("targetExpiry")).longValue() - clock.getAsLong();
      if (seconds <= 0)
        throw new IllegalArgumentException(
            "Предложенная дата уже прошла; создайте новый предпросмотр");
    }
    if (!store.reserveDecision(key, actor))
      throw new IllegalArgumentException("Решение уже применяется");
    String result =
        decision.apply(
            kind, id, p.get("action").toString(), staff, p.get("reason").toString(), seconds);
    store.decisionDone(key, result);
    return result;
  }

  public Map<String, Object> waiting(String kind, int id) throws Exception {
    boolean open = open(kind, id);
    var work = moderation.work(kind, id);
    String marker = "";
    boolean lastStaff = false;
    if (kind.equals("appeal")) {
      var messages = database.getAppealMessages(id);
      if (!messages.isEmpty()) {
        var last = messages.getLast();
        marker = "a:" + last.id();
        lastStaff = last.isStaff();
      }
    } else {
      var messages = database.reportDiscussion(id);
      if (!messages.isEmpty()) {
        var last = messages.getLast();
        marker = "r:" + last.get("createdAt") + ":" + ModerationStore.hash(JsonUtils.toJson(last));
        lastStaff = Boolean.TRUE.equals(last.get("isStaff"));
      }
    }
    var wait =
        store.observe(
            kind, id, open, work.get("state").toString(), marker, lastStaff, clock.getAsLong());
    long deadline = policy.deadline(wait.mode(), wait.since(), wait.stage() >= 1 ? 2 : 1);
    if (deadline == 0 && wait.mode().equals("STAFF"))
      deadline = policy.deadline("STAFF", wait.since(), wait.stage() >= 1 ? 1 : 2);
    if (wait.mode().equals("NONE")) deadline = 0;
    return Map.of(
        "mode",
        wait.mode(),
        "since",
        wait.since(),
        "version",
        wait.version(),
        "stage",
        wait.stage(),
        "deadline",
        deadline,
        "overdue",
        deadline > 0 && clock.getAsLong() >= deadline);
  }

  public List<Reminder> due() throws Exception {
    var rows = new ArrayList<Reminder>();
    for (String kind : List.of("report", "appeal"))
      for (int offset = 0; offset < 1000; offset += 200) {
        List<Integer> ids =
            kind.equals("report")
                ? database.getAllReports("PENDING", null, 200, offset).stream()
                    .map(PlayerReport::id)
                    .toList()
                : database.getAllAppeals("PENDING", 200, offset).stream()
                    .map(WebBanAppeal::id)
                    .toList();
        for (int id : ids) {
          waiting(kind, id);
          var w = store.waitFor(kind, id).orElseThrow();
          int stage = w.stage() + 1;
          if (stage > 2 || w.mode().equals("PLAYER") && stage > 1) continue;
          long at = policy.deadline(w.mode(), w.since(), stage);
          if (at == 0 && stage == 1 && w.mode().equals("STAFF")) {
            stage = 2;
            at = policy.deadline(w.mode(), w.since(), stage);
          }
          if (at > 0 && clock.getAsLong() >= at)
            rows.add(
                new Reminder(
                    kind,
                    id,
                    w.version(),
                    stage,
                    w.mode(),
                    w.since(),
                    moderation.work(kind, id).get("assignee").toString()));
        }
        if (ids.size() < 200) break;
      }
    return rows;
  }

  public boolean reminderCurrent(String kind, int id, long version, String mode) throws Exception {
    waiting(kind, id);
    return open(kind, id)
        && store
            .waitFor(kind, id)
            .filter(w -> w.version() == version && w.mode().equals(mode))
            .isPresent();
  }

  public int requestReview(String kind, int id, String actor, Map<String, Object> body)
      throws Exception {
    if (open(kind, id) || snapshot(kind, id).get("status").equals("DECIDING"))
      throw new IllegalArgumentException("Дело ещё рассматривается; дополните текущее обращение");
    String name =
        Objects.toString(body.get("imageName"), "Скриншот").replaceAll("[\\\\/\\p{Cntrl}]", "_");
    if (name.length() > 100) name = name.substring(0, 100);
    byte[] bytes = null;
    String encoded = Objects.toString(body.get("base64"), "");
    if (!encoded.isBlank()) {
      if (encoded.length() > 933336) throw new IllegalArgumentException("Скриншот до 700 КБ");
      bytes = Base64.getDecoder().decode(encoded);
      if (bytes.length > 700000) throw new IllegalArgumentException("Скриншот до 700 КБ");
      CaseEvidence.imageMime(bytes);
    }
    return store.requestReview(
        kind, id, actor, Objects.toString(body.get("text"), ""), name, bytes, clock.getAsLong());
  }

  public synchronized Map<String, Object> review(
      String kind, int id, int request, String staff, boolean accept, String reason)
      throws Exception {
    independent(kind, id, staff);
    if (open(kind, id))
      throw new IllegalArgumentException("Исходное дело снова открыто; обновите данные");
    var review =
        store.reviews(kind, id).stream()
            .filter(r -> ((Number) r.get("id")).intValue() == request)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Дополнение не найдено"));
    if (!review.get("state").equals("PENDING"))
      throw new IllegalArgumentException("Дополнение уже рассмотрено или результат требует сверки");
    if (accept && kind.equals("appeal")) {
      var old = database.getAppealById(id).orElseThrow();
      if (database
          .getAppealByBanId(old.banId())
          .filter(a -> Set.of("PENDING", "DECIDING").contains(a.status()))
          .isPresent())
        throw new IllegalArgumentException(
            "По этому наказанию уже есть открытая апелляция; объединение не выполняется автоматически");
    }
    if (!store.reserveReview(request, kind, id, staff, reason, accept))
      throw new IllegalArgumentException("Дополнение уже рассматривается");
    if (!accept) return Map.of("state", "REJECTED");
    int child;
    if (kind.equals("appeal")) {
      var old = database.getAppealById(id).orElseThrow();
      child =
          database.createAppeal(
              old.banId(),
              old.playerUuid(),
              old.playerName(),
              "Повторное рассмотрение обращения №" + id + ". " + review.get("body"));
      if (child < 1) throw new IllegalStateException("Новое обращение не сохранено");
      store.child(request, kind, child);
      moderation.copyAppealAccess(id, child);
      database.addAppealMessage(child, old.playerName(), false, review.get("body").toString());
    } else {
      var old = database.getReportById(id).orElseThrow();
      child =
          database.createPlayerReport(
              old.reporterUuid(),
              old.reporterName(),
              old.reporterIp(),
              old.targetUuid(),
              old.targetName(),
              old.reasons(),
              "Повторное рассмотрение обращения №" + id + ". " + review.get("body"),
              false,
              PlayerReport.REVIEW_NO_REPUTATION);
      if (child < 1) throw new IllegalStateException("Новое обращение не сохранено");
      store.child(request, kind, child);
      var owner = moderation.ticket(kind, id).map(t -> t.get("discordId").toString());
      if (owner.isEmpty() && review.get("actor").toString().startsWith("discord:"))
        owner = Optional.of(review.get("actor").toString().substring(8));
      if (owner.isPresent()) moderation.ticketOwner(kind, child, owner.get());
    }
    moderation.addEvidence(
        kind,
        child,
        "Автор обращения",
        "TEXT",
        review.get("body").toString(),
        "AUTHOR",
        null,
        null);
    var image = store.reviewImage(kind, id, request);
    if (image.isPresent())
      moderation.addEvidence(
          kind,
          child,
          "Автор обращения",
          "IMAGE",
          review.get("image_name").toString(),
          "AUTHOR",
          CaseEvidence.imageMime(image.get()),
          image.get());
    moderation.note(kind, child, staff, "Повторное рассмотрение дела №" + id + ". " + reason);
    created.accept(kind, child);
    store.accepted(request);
    return Map.of("state", "ACCEPTED", "kind", kind, "id", child);
  }

  public List<Map<String, Object>> publicReviews(String kind, int id) throws Exception {
    var result = new ArrayList<Map<String, Object>>();
    for (var stored : store.reviews(kind, id)) {
      var row = new LinkedHashMap<String, Object>(stored);
      row.remove("actor");
      result.add(row);
    }
    return result;
  }

  public Map<String, Object> view(String kind, int id, String actor) throws Exception {
    return Map.of(
        "waiting",
        waiting(kind, id),
        "templates",
        store.templates(actor),
        "reviews",
        publicReviews(kind, id),
        "parent",
        store.parent(kind, id).orElse(Map.of()));
  }

  @Override
  public void close() throws Exception {
    closed = true;
    store.close();
  }
}
