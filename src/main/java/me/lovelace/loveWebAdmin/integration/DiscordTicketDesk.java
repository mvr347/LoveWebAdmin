package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.database.TicketDeskStore;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/**
 * Private ticket workflow. All blocking work is dispatched to owned IO threads, never Bukkit's
 * main.
 */
public final class DiscordTicketDesk {
  private final LoveWebAdmin plugin;
  private final TicketDeskStore store;
  private final String panelChannel, queueChannel, publicUrl;
  private final List<String> supervisorRoles;
  private final Map<String, Long> published = new ConcurrentHashMap<>();
  private final Map<String, String> publishedBodies = new ConcurrentHashMap<>();
  private final AtomicBoolean busy = new AtomicBoolean();
  private final me.lovelace.loveWebAdmin.utils.SubmissionLimiter submissions =
      new me.lovelace.loveWebAdmin.utils.SubmissionLimiter();
  private final Object writes = new Object();
  private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();
  private volatile boolean closed;
  private volatile DiscordService service;
  private DiscordActions actions;
  private final DiscordCaseWorkflowActions caseWorkflowActions;
  private final CaseReminderDelivery reminders;

  public DiscordTicketDesk(LoveWebAdmin plugin, TicketDeskStore store) {
    this.plugin = plugin;
    this.store = store;
    this.caseWorkflowActions = new DiscordCaseWorkflowActions(plugin, this);
    this.reminders = new CaseReminderDelivery(plugin, this);
    var config = plugin.getConfig();
    panelChannel = config.getString("discord.tickets.panel-channel-id", "").trim();
    queueChannel = config.getString("discord.tickets.queue-channel-id", "").trim();
    publicUrl = config.getString("web.public-url", "").replaceAll("/+$", "");
    supervisorRoles = List.copyOf(config.getStringList("discord.tickets.supervisor-role-ids"));
    if (!panelChannel.isBlank() && !panelChannel.matches("[0-9]{5,25}")
        || !queueChannel.isBlank() && !queueChannel.matches("[0-9]{5,25}")
        || supervisorRoles.size() > 20
        || supervisorRoles.stream().anyMatch(id -> !id.matches("[0-9]{5,25}")))
      throw new IllegalArgumentException("Некорректные ID обращений Discord");
    if (!queueChannel.isBlank() && queueChannel.equals(panelChannel))
      throw new IllegalArgumentException(
          "Публичная панель и служебная очередь должны быть в разных каналах");
  }

  public void attach(DiscordService service, DiscordActions actions) {
    this.service = service;
    this.actions = actions;
    published.clear();
    publishedBodies.clear();
    try {
      adoptExisting();
    } catch (Exception error) {
      throw new IllegalStateException("Не восстановлены обращения", error);
    }
  }

  private void adoptExisting() throws Exception {
    for (var old : plugin.moderation().tickets()) {
      if (!old.get("kind").equals("report") || old.get("channel").toString().isBlank()) continue;
      int id = ((Number) old.get("id")).intValue();
      if (plugin
          .getDatabaseManager()
          .getReportById(id)
          .filter(r -> r.status().equals("PENDING"))
          .isEmpty()) continue;
      store.register("report", id, old.get("discordId").toString());
      var ticket = store.get("report", id).orElseThrow();
      if (ticket.state().equals("OPEN") && ticket.channel().isBlank())
        store.channel("report", id, old.get("channel").toString());
    }
    for (int offset = 0; ; offset += 200) {
      var appeals = plugin.getDatabaseManager().getAllAppeals("PENDING", 200, offset);
      for (var appeal : appeals) {
        if (appeal.discordChannelId() == null || appeal.discordChannelId().isBlank()) continue;
        store.register("appeal", appeal.id(), plugin.moderation().owner(appeal.id()).orElse(null));
        var ticket = store.get("appeal", appeal.id()).orElseThrow();
        if (ticket.state().equals("OPEN") && ticket.channel().isBlank())
          store.channel("appeal", appeal.id(), appeal.discordChannelId());
      }
      if (appeals.size() < 200) break;
    }
  }

  public void close() {
    closed = true;
    service = null;
  }

  public boolean enabled() {
    return !closed && service != null;
  }

  public void refreshMessage(String slot) {
    try {
      store.refreshClosedQueue(slot);
      if (slot.matches("review:[0-9]+"))
        plugin.workflows().store().noticeDone(Integer.parseInt(slot.substring(7)), "");
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
    published.remove(slot);
    publishedBodies.remove(slot);
  }

  public TicketDeskStore store() {
    return store;
  }

  DiscordActions workflowActions() {
    return actions;
  }

  String queueChannel() {
    return queueChannel;
  }

  String publicUrl() {
    return publicUrl;
  }

  String workflowPublish(String slot, String channel, Map<String, Object> body) throws Exception {
    return publish(slot, channel, body);
  }

  public boolean reminderAllowed(Map<String, Object> payload, String recipient) {
    try {
      return reminders.allowed(payload, recipient);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  boolean workflowChannel(String kind, int id, String channel) throws Exception {
    return store
        .get(kind, id)
        .filter(t -> t.channel().equals(channel) && !channel.isBlank())
        .isPresent();
  }

  WebAdmin workflowStaff(DiscordInteraction i, String kind, int id, boolean completed)
      throws Exception {
    if (!workflowChannel(kind, id, i.values().getOrDefault("_channelId", "")))
      throw new IllegalArgumentException("Откройте соответствующее обращение");
    var admin =
        actions
            .staffMember(i.userId(), permission(kind))
            .orElseThrow(() -> new IllegalArgumentException("Нужны действующие права сотрудника"));
    var ticket = store.get(kind, id).orElseThrow();
    if (!completed && (!ticket.state().equals("OPEN") || !domainOpen(ticket)))
      throw new IllegalArgumentException("Обращение уже закрыто");
    if (!assignee(ticket).equals(admin.username()) && !supervisor(admin, i.userId()))
      throw new IllegalArgumentException("Сначала возьмите обращение в работу");
    conflict(ticket, admin.username());
    return admin;
  }

  boolean reviewAuthor(String kind, int id, String user) throws Exception {
    if (!Set.of("report", "appeal").contains(kind)) return false;
    if (store.get(kind, id).map(t -> t.owner().equals(user)).orElse(false)) return true;
    if (kind.equals("appeal") && plugin.moderation().owner(id).filter(user::equals).isPresent())
      return true;
    String uuid =
        kind.equals("appeal")
            ? plugin.getDatabaseManager().getAppealById(id).map(a -> a.playerUuid()).orElse("")
            : plugin.getDatabaseManager().getReportById(id).map(r -> r.reporterUuid()).orElse("");
    return service
        .getLinkedPlayer(user)
        .map(UUID::toString)
        .filter(u -> u.equals(uuid))
        .isPresent();
  }

  static Map<String, Object> button(String label, String id, int style) {
    return Map.of("type", 2, "style", style, "label", label, "custom_id", id);
  }

  static Map<String, Object> row(List<Map<String, Object>> buttons) {
    return Map.of("type", 1, "components", buttons);
  }

  static Map<String, Object> field(String id, String label, int max, boolean required) {
    return Map.of(
        "type",
        1,
        "components",
        List.of(
            Map.of(
                "type",
                4,
                "custom_id",
                id,
                "label",
                label,
                "style",
                max > 100 ? 2 : 1,
                "max_length",
                max,
                "required",
                required)));
  }

  static Map<String, Object> body(String content, List<Map<String, Object>> components) {
    if (content.length() > 1900) {
      content = content.substring(0, 1900);
      if (Character.isHighSurrogate(content.charAt(content.length() - 1)))
        content = content.substring(0, content.length() - 1);
    }
    return Map.of(
        "content",
        content,
        "components",
        components,
        "allowed_mentions",
        Map.of("parse", List.of()));
  }

  static Map<String, Object> explainedBody(
      String content, String explanation, List<Map<String, Object>> components) {
    var response = new LinkedHashMap<String, Object>(body(content, components));
    response.put("embeds", List.of(Map.of("description", explanation, "color", 0xD8A74B)));
    return response;
  }

  private <T> T call(String method, Class<?>[] types, Object... args) throws Exception {
    return CoreDiscordExtensions.<T>managed(Objects.requireNonNull(service), method, types, args)
        .get(45, TimeUnit.SECONDS);
  }

  private String publish(String slot, String channel, Map<String, Object> payload)
      throws Exception {
    String json = JsonUtils.toJson(payload),
        digest = me.lovelace.loveWebAdmin.database.ModerationStore.hash(json);
    var existing = store.message(slot);
    if (existing.isPresent() && existing.get().get("state").equals("UNCERTAIN"))
      return existing.get().get("message");
    if (digest.equals(publishedBodies.get(slot))
        && System.currentTimeMillis() - published.getOrDefault(slot, 0L) < 60000)
      return existing.map(m -> m.get("message")).orElse("");
    if (System.currentTimeMillis() < retryAfter.getOrDefault("message:" + slot, 0L))
      return existing.map(m -> m.get("message")).orElse("");
    try {
      String sent = DiscordMessagePublisher.publish(store, service, slot, channel, payload);
      published.put(slot, System.currentTimeMillis());
      publishedBodies.put(slot, digest);
      retryAfter.remove("message:" + slot);
      return sent;
    } catch (Exception error) {
      retryAfter.put("message:" + slot, System.currentTimeMillis() + 60000);
      plugin
          .getLogger()
          .warning(
              "Панель/карточка Discord ["
                  + slot
                  + "]: "
                  + DiscordMessagePublisher.explanation(error));
      throw error;
    }
  }

  private Map<String, Object> productionPanel() {
    return DiscordPanelMessages.body("lwa:desk:", false);
  }

  public Map<String, Object> panelStatus() throws Exception {
    var result = new LinkedHashMap<String, Object>();
    result.put("channelId", panelChannel);
    result.put("botAvailable", enabled());
    result.put(
        "state",
        panelChannel.isBlank()
            ? "NOT_CONFIGURED"
            : !enabled()
                ? "BOT_UNAVAILABLE"
                : store
                    .message("panel:" + panelChannel)
                    .map(m -> m.get("state"))
                    .orElse("NOT_PUBLISHED"));
    result.put(
        "messageId", store.message("panel:" + panelChannel).map(m -> m.get("message")).orElse(""));
    result.put(
        "explanation",
        panelChannel.isBlank()
            ? "Не задан discord.tickets.panel-channel-id в config.yml. Укажите ID канала и перезапустите плагин."
            : !enabled()
                ? "Бот выключен, ядро недоступно или ещё запускается"
                : store
                    .message("panel:" + panelChannel)
                    .map(m -> m.get("error"))
                    .orElse("Публикация ещё не запускалась"));
    return result;
  }

  public Map<String, Object> diagnosePanel() throws Exception {
    var result = new LinkedHashMap<String, Object>(panelStatus());
    if (enabled() && !panelChannel.isBlank())
      try {
        Map<String, Object> channel =
            CoreDiscordExtensions.<Map<String, Object>>managed(
                    service, "inspectChannel", new Class<?>[] {String.class}, panelChannel)
                .get(30, TimeUnit.SECONDS);
        result.put("channel", channel);
        boolean text = ((Number) channel.get("type")).intValue() == 0;
        if (!text) result.put("state", "INVALID_CHANNEL");
        result.put(
            "inspection",
            text
                ? "Канал доступен для чтения; право отправки подтверждается только успешной публикацией"
                : "Указан не текстовый канал. Нужен ID текстового канала, а не категории");
      } catch (Exception error) {
        result.put("inspection", DiscordMessagePublisher.explanation(error));
      }
    return result;
  }

  public Map<String, Object> publishPanel() throws Exception {
    if (panelChannel.isBlank())
      throw new IllegalArgumentException(
          "Укажите discord.tickets.panel-channel-id в config.yml и перезапустите плагин");
    retryAfter.remove("message:panel:" + panelChannel);
    publishedBodies.remove("panel:" + panelChannel);
    publish("panel:" + panelChannel, panelChannel, productionPanel());
    return panelStatus();
  }

  public void tick() {
    if (!enabled() || !busy.compareAndSet(false, true)) return;
    plugin
        .io()
        .run(
            () -> {
              try {
                if (!enabled()) return;
                if (!panelChannel.isBlank())
                  publish("panel:" + panelChannel, panelChannel, productionPanel());
                for (var ticket : store.active()) {
                  if (closed) break;
                  String retryKey = ticket.kind() + ":" + ticket.id();
                  if (System.currentTimeMillis() < retryAfter.getOrDefault(retryKey, 0L)) continue;
                  try {
                    if (ticket.state().equals("OPEN")) {
                      synchronized (writes) {
                        var current = store.get(ticket.kind(), ticket.id()).orElseThrow();
                        if (current.state().equals("OPEN")) {
                          sync(current);
                          if (!domainOpen(current))
                            requestClose(current, "Система", closureReason(current));
                        }
                      }
                    } else finishClose(ticket);
                    retryAfter.remove(retryKey);
                  } catch (Exception error) {
                    retryAfter.put(retryKey, System.currentTimeMillis() + 60000);
                    plugin
                        .getLogger()
                        .warning(
                            "Обращение №"
                                + ticket.id()
                                + ": канал сохранён, требуется проверка ("
                                + error.getClass().getSimpleName()
                                + ")");
                  }
                }
                reminders.scan();
                reminders.reviews();
              } catch (Exception error) {
                plugin.getLogger().warning("Панель обращений: " + error.getClass().getSimpleName());
              } finally {
                busy.set(false);
              }
            })
        .exceptionally(
            error -> {
              busy.set(false);
              return null;
            });
  }

  private String closureReason(TicketDeskStore.Ticket ticket) {
    if (ticket.kind().equals("appeal")) {
      var messages = plugin.getDatabaseManager().getAppealMessages(ticket.id());
      if (!messages.isEmpty()) return messages.getLast().message();
    }
    return "Рассмотрение обращения завершено";
  }

  private boolean domainOpen(TicketDeskStore.Ticket ticket) {
    return ticket.kind().equals("report")
        ? plugin
            .getDatabaseManager()
            .getReportById(ticket.id())
            .filter(r -> r.status().equals("PENDING"))
            .isPresent()
        : plugin
            .getDatabaseManager()
            .getAppealById(ticket.id())
            .filter(a -> Set.of("PENDING", "DECIDING").contains(a.status()))
            .isPresent();
  }

  Optional<String> linked(String username) {
    return plugin
        .getDatabaseManager()
        .getPlayerStats(username)
        .flatMap(
            p -> {
              try {
                return service.getLinkedDiscordId(UUID.fromString(p.get("uuid").toString()));
              } catch (IllegalArgumentException invalid) {
                return Optional.empty();
              }
            });
  }

  private List<String> members(TicketDeskStore.Ticket ticket) throws Exception {
    var members = new LinkedHashSet<String>();
    if (!ticket.owner().isBlank()) members.add(ticket.owner());
    String assignee =
        plugin.moderation().work(ticket.kind(), ticket.id()).get("assignee").toString();
    if (!assignee.isBlank())
      plugin
          .getDatabaseManager()
          .getAdminByUsername(assignee)
          .filter(a -> a.status().equals("ACTIVE"))
          .filter(a -> allowed(a, permission(ticket.kind())))
          .flatMap(a -> linked(a.username()))
          .ifPresent(members::add);
    if (ticket.kind().equals("appeal")) {
      var appeal = plugin.getDatabaseManager().getAppealById(ticket.id());
      if (appeal.isPresent()) {
        var source = plugin.moderation().source(appeal.get().banId());
        if ("STAFF".equals(source.get("kind")))
          plugin
              .getDatabaseManager()
              .getAdminByUsername(source.get("actor").toString())
              .filter(a -> a.status().equals("ACTIVE"))
              .filter(a -> allowed(a, Permission.VIEW_APPEALS))
              .flatMap(a -> linked(a.username()))
              .ifPresent(members::add);
      }
    }
    return List.copyOf(members);
  }

  static Permission permission(String kind) {
    return kind.equals("report") ? Permission.MANAGE_REPORTS : Permission.MANAGE_APPEALS;
  }

  private boolean allowed(WebAdmin admin, Permission permission) {
    return plugin
        .getDatabaseManager()
        .getRoleById(admin.roleId())
        .map(r -> r.isOwner() || r.permissions().contains(permission))
        .orElse(false);
  }

  boolean supervisor(WebAdmin admin, String discord) throws Exception {
    if (plugin.getDatabaseManager().getRoleById(admin.roleId()).map(r -> r.isOwner()).orElse(false))
      return true;
    Set<String> roles = call("getMemberRoleIds", new Class<?>[] {String.class}, discord);
    return roles.stream().anyMatch(supervisorRoles::contains);
  }

  private String source(TicketDeskStore.Ticket ticket) throws Exception {
    if (!ticket.kind().equals("appeal")) return "Обращение игрока";
    var appeal = plugin.getDatabaseManager().getAppealById(ticket.id()).orElseThrow();
    var origin = plugin.moderation().source(appeal.banId());
    return switch (origin.get("kind").toString()) {
      case "STAFF" -> "Сотрудник: " + origin.get("actor");
      case "ANTICHEAT" -> "Античит";
      case "SECURITY" -> "Безопасность";
      default -> "Система";
    };
  }

  private String assignee(TicketDeskStore.Ticket ticket) throws Exception {
    return plugin.moderation().work(ticket.kind(), ticket.id()).get("assignee").toString();
  }

  private TicketDeskStore.Ticket recoverChannel(TicketDeskStore.Ticket ticket) throws Exception {
    if (ticket.channel().isBlank()) {
      String recovered =
          CoreDiscordExtensions.managedTicket(
                  service,
                  ticket.kind().equals("appeal") ? TicketType.BAN_APPEAL : TicketType.REPORT,
                  ticket.kind().equals("appeal")
                      ? String.valueOf(ticket.id())
                      : "report:" + ticket.id(),
                  "",
                  "",
                  ticket.owner().isBlank() ? null : ticket.owner(),
                  supervisorRoles,
                  members(ticket),
                  "mine-ticket-" + ticket.id() + (ticket.kind().equals("appeal") ? "-a" : "-r"),
                  true)
              .get(45, TimeUnit.SECONDS);
      if (recovered == null) return ticket;
      store.channel(ticket.kind(), ticket.id(), recovered);
      if (ticket.kind().equals("appeal"))
        plugin.getDatabaseManager().setAppealDiscordChannelId(ticket.id(), recovered);
      else plugin.moderation().ticketChannel(ticket.kind(), ticket.id(), recovered);
      ticket = store.get(ticket.kind(), ticket.id()).orElseThrow();
    }
    return ticket;
  }

  private void sync(TicketDeskStore.Ticket ticket) throws Exception {
    ticket = recoverChannel(ticket);
    if (ticket.channel().isBlank() || !ticket.state().equals("OPEN")) return;
    var members = members(ticket);
    String acl =
        me.lovelace.loveWebAdmin.database.ModerationStore.hash(
            JsonUtils.toJson(List.of(supervisorRoles, members)));
    if (!acl.equals(ticket.acl())) {
      Boolean ok =
          call(
              "updateTicketAccess",
              new Class<?>[] {String.class, List.class, List.class},
              ticket.channel(),
              supervisorRoles,
              members);
      if (!Boolean.TRUE.equals(ok)) throw new IllegalStateException("Доступ не обновлён");
    }
    String assigned = assignee(ticket),
        status = plugin.moderation().work(ticket.kind(), ticket.id()).get("state").toString();
    String state =
        switch (status) {
          case "WAITING" -> "Ожидаем ответа игрока";
          case "IN_PROGRESS" -> "В работе";
          case "CLOSED" -> "Рассмотрение завершено";
          default -> "Ожидает сотрудника";
        };
    if ("HIGH".equals(plugin.moderation().work(ticket.kind(), ticket.id()).get("priority")))
      state = "Передано руководству · " + state;
    String title = (ticket.kind().equals("appeal") ? "Апелляция" : "Жалоба") + " №" + ticket.id();
    String description =
        ticket.kind().equals("appeal")
            ? plugin.getDatabaseManager().getAppealById(ticket.id()).orElseThrow().reason()
            : plugin.getDatabaseManager().getReportById(ticket.id()).orElseThrow().description();
    var waiting = plugin.workflows().waiting(ticket.kind(), ticket.id());
    String waitText =
        waiting.get("mode").equals("NONE")
            ? ""
            : "\nОжидаем "
                + (waiting.get("mode").equals("PLAYER") ? "игрока" : "сотрудника")
                + (((Number) waiting.get("deadline")).longValue() > 0
                    ? " · срок <t:" + waiting.get("deadline") + ":R>"
                    : "");
    String card =
        publish(
            "case:" + ticket.kind() + ":" + ticket.id(),
            ticket.channel(),
            body(
                "**"
                    + title
                    + "**\nАвтор: "
                    + (ticket.owner().isBlank()
                        ? "Обращение из панели"
                        : "<@" + ticket.owner() + ">")
                    + "\nИсточник: "
                    + source(ticket)
                    + "\nОтветственный: "
                    + (assigned.isBlank() ? "Не назначен" : assigned)
                    + "\nСостояние: "
                    + state
                    + waitText
                    + "\n\n"
                    + description.substring(0, Math.min(description.length(), 900))
                    + "\n\nПереписка и вложения сохраняются. Закрытие канала не снимает наказание.",
                controls(ticket)));
    String queue = ticket.queueCard();
    if (!queueChannel.isBlank())
      queue =
          publish(
              "queue:" + ticket.kind() + ":" + ticket.id(),
              queueChannel,
              body(
                  "**"
                      + title
                      + "** · "
                      + state
                      + "\nОтветственный: "
                      + (assigned.isBlank() ? "Не назначен" : assigned),
                  List.of(
                      row(
                          List.of(
                              button(
                                  assigned.isBlank() ? "Взять обращение" : "Открыть обращение",
                                  "lwa:desk:claim:" + ticket.kind() + ":" + ticket.id(),
                                  1))))));
    store.display(ticket.kind(), ticket.id(), card, queue, acl);
  }

  private List<Map<String, Object>> controls(TicketDeskStore.Ticket ticket) {
    String key = ticket.kind() + ":" + ticket.id();
    var first =
        new ArrayList<Map<String, Object>>(
            List.of(
                button("Взять в работу", "lwa:desk:claim:" + key, 1),
                button("Передать", "lwa:desk:form:transfer:" + key, 2),
                button("Уточнить", "lwa:desk:form:clarify:" + key, 2),
                button("Руководству", "lwa:desk:form:escalate:" + key, 2)));
    if (!publicUrl.isBlank())
      first.add(
          Map.of(
              "type",
              2,
              "style",
              5,
              "label",
              "Открыть в панели",
              "url",
              publicUrl + "/case.html?kind=" + ticket.kind() + "&id=" + ticket.id()));
    var second =
        new ArrayList<Map<String, Object>>(
            List.of(
                button("Одобрить", "lwa:desk:form:approve:" + key, 3),
                button("Отклонить", "lwa:desk:form:reject:" + key, 4)));
    if (ticket.kind().equals("appeal"))
      second.add(button("Сократить срок", "lwa:desk:form:reduce:" + key, 1));
    second.add(button("Закрыть / отозвать", "lwa:desk:form:close:" + key, 2));
    return List.of(
        row(first),
        row(second),
        row(
            List.of(
                button("Шаблоны уточнений", "lwa:flow:templates:CLARIFY:" + key + ":0", 2),
                button("Шаблоны решений", "lwa:flow:templates:VERDICT:" + key + ":0", 2))));
  }

  public CompletableFuture<String> create(String kind, int id, String owner) {
    return plugin
        .io()
        .supply(
            () -> {
              synchronized (writes) {
                try {
                  store.register(kind, id, owner);
                  var ticket = store.get(kind, id).orElseThrow();
                  if (!ticket.state().equals("OPEN"))
                    throw new IllegalArgumentException("Обращение закрывается");
                  if (ticket.channel().isBlank()) {
                    String name =
                        kind.equals("appeal")
                            ? plugin
                                .getDatabaseManager()
                                .getAppealById(id)
                                .orElseThrow()
                                .playerName()
                            : plugin
                                .getDatabaseManager()
                                .getReportById(id)
                                .orElseThrow()
                                .reporterName();
                    store.startCreation(kind, id);
                    String channel =
                        CoreDiscordExtensions.managedTicket(
                                service,
                                kind.equals("appeal") ? TicketType.BAN_APPEAL : TicketType.REPORT,
                                kind.equals("appeal") ? String.valueOf(id) : "report:" + id,
                                name,
                                kind.equals("appeal")
                                    ? plugin
                                        .getDatabaseManager()
                                        .getAppealById(id)
                                        .orElseThrow()
                                        .reason()
                                    : plugin
                                        .getDatabaseManager()
                                        .getReportById(id)
                                        .orElseThrow()
                                        .description(),
                                owner,
                                supervisorRoles,
                                members(ticket),
                                "mine-ticket-" + id + (kind.equals("appeal") ? "-a" : "-r"))
                            .get(45, TimeUnit.SECONDS);
                    if (channel == null)
                      throw new IllegalStateException("Создание канала не подтверждено");
                    store.channel(kind, id, channel);
                    if (kind.equals("appeal")) {
                      if (!plugin.getDatabaseManager().setAppealDiscordChannelId(id, channel))
                        throw new IllegalStateException("Не сохранён канал");
                    } else {
                      plugin.moderation().ticketOwner(kind, id, owner);
                      plugin.moderation().ticketChannel(kind, id, channel);
                    }
                    ticket = store.get(kind, id).orElseThrow();
                    plugin.getDiscordBridge().notifyTicketCreated(kind, id);
                  }
                  sync(ticket);
                  return ticket.channel();
                } catch (Exception error) {
                  throw new CompletionException(error);
                }
              }
            });
  }

  private void answer(DiscordInteraction interaction, Map<String, Object> data) {
    service.respond(
        interaction, JsonUtils.toJson(Map.of("type", 4, "data", merge(data, Map.of("flags", 64)))));
  }

  private static Map<String, Object> merge(Map<String, Object> left, Map<String, Object> right) {
    var value = new LinkedHashMap<String, Object>(left);
    value.putAll(right);
    return value;
  }

  public boolean onInteraction(DiscordInteraction interaction) {
    if (enabled() && caseWorkflowActions.onInteraction(interaction, service, actions)) return true;
    String name = interaction.name();
    if (!name.startsWith("lwa:desk:")
        && !Set.of("жалоба", "апелляция", "мои-обращения").contains(name)
        && !name.startsWith("lwa:appeal:new:")) return false;
    if (!enabled()) return false;
    if (!interaction.guildId().isBlank()
        && !interaction.guildId().equals(CoreDiscordExtensions.guild(service))) {
      answer(interaction, body("Обращения доступны на сервере сообщества.", List.of()));
      return true;
    }
    if (name.equals("жалоба")
        || name.equals("апелляция")
        || name.startsWith("lwa:appeal:new:")
        || name.startsWith("lwa:desk:new:")) {
      String kind = name.equals("жалоба") || name.endsWith(":report") ? "report" : "appeal";
      var fields = new ArrayList<Map<String, Object>>();
      if (kind.equals("report")) fields.add(field("target", "Ник игрока-нарушителя", 16, true));
      fields.add(field("nick", "Ваш ник, если аккаунт не привязан", 16, false));
      if (kind.equals("appeal"))
        fields.add(field("ban", "Номер наказания, если знаете", 10, false));
      fields.add(
          field(
              "reason",
              kind.equals("report") ? "Что произошло?" : "Почему нужно пересмотреть наказание?",
              2000,
              true));
      service.respond(
          interaction,
          JsonUtils.toJson(
              Map.of(
                  "type",
                  9,
                  "data",
                  Map.of(
                      "custom_id",
                      "lwa:desk:draft:" + kind,
                      "title",
                      kind.equals("report") ? "Жалоба на игрока" : "Апелляция",
                      "components",
                      fields))));
      return true;
    }
    if (name.startsWith("lwa:desk:form:")) {
      String[] parts = name.split(":");
      if (parts.length != 6
          || !Set.of("report", "appeal").contains(parts[4])
          || !parts[5].matches("[0-9]{1,9}")) {
        answer(interaction, body("Кнопка недоступна.", List.of()));
        return true;
      }
      var fields = new ArrayList<Map<String, Object>>();
      if (parts[3].equals("transfer"))
        fields.add(field("staff", "Логин сотрудника в панели", 24, true));
      if (parts[3].equals("reduce"))
        fields.add(field("minutes", "Оставшийся срок в минутах", 10, true));
      fields.add(
          field(
              "reason",
              parts[3].equals("clarify") ? "Что нужно уточнить у игрока?" : "Объясните действие",
              2000,
              true));
      service.respond(
          interaction,
          JsonUtils.toJson(
              Map.of(
                  "type",
                  9,
                  "data",
                  Map.of(
                      "custom_id",
                      name.replace(":form:", ":submit:"),
                      "title",
                      parts[3].equals("close")
                          ? "Подтвердить закрытие / отзыв"
                          : "Действие по обращению",
                      "components",
                      fields))));
      return true;
    }
    service
        .respond(interaction, JsonUtils.toJson(Map.of("type", 5, "data", Map.of("flags", 64))))
        .thenAccept(
            ok -> {
              if (!ok) return;
              plugin
                  .io()
                  .run(
                      () -> {
                        Map<String, Object> result;
                        try {
                          synchronized (writes) {
                            result = execute(interaction);
                          }
                        } catch (Exception error) {
                          String message =
                              error instanceof IllegalArgumentException
                                      || error
                                          instanceof
                                          me.lovelace.loveWebAdmin.utils.ActionRejectedException
                                  ? error.getMessage()
                                  : "Действие не подтверждено. Проверьте состояние обращения; повторно оно не выполнено.";
                          result = body(message, List.of());
                          plugin
                              .getLogger()
                              .fine("Действие обращения: " + error.getClass().getSimpleName());
                        }
                        CoreDiscordExtensions.managed(
                            service,
                            "editInteractionResponse",
                            new Class<?>[] {DiscordInteraction.class, String.class},
                            interaction,
                            JsonUtils.toJson(result));
                      });
            });
    return true;
  }

  private Map<String, Object> execute(DiscordInteraction i) throws Exception {
    if (closed) throw new IllegalArgumentException("Обращения временно недоступны");
    String actor = "discord:" + i.userId();
    if (!plugin.operations().allow(actor))
      throw new IllegalArgumentException("Слишком много нажатий. Подождите минуту.");
    String name = i.name();
    if (name.equals("lwa:desk:mine") || name.equals("мои-обращения")) {
      String token =
          plugin
              .moderation()
              .portalLink(
                  i.userId(), service.getLinkedPlayer(i.userId()).map(UUID::toString).orElse(null));
      var buttons = new ArrayList<Map<String, Object>>();
      for (var t : store.active())
        if (t.owner().equals(i.userId())
            && !t.state().equals("CLOSED")
            && !t.channel().isBlank()
            && buttons.size() < 5)
          buttons.add(
              Map.of(
                  "type",
                  2,
                  "style",
                  5,
                  "label",
                  (t.kind().equals("report") ? "Жалоба" : "Апелляция") + " №" + t.id(),
                  "url",
                  "https://discord.com/channels/"
                      + CoreDiscordExtensions.guild(service)
                      + "/"
                      + t.channel()));
      var components = new ArrayList<Map<String, Object>>();
      if (!buttons.isEmpty()) components.add(row(buttons));
      if (!publicUrl.isBlank())
        components.add(
            row(
                List.of(
                    Map.of(
                        "type",
                        2,
                        "style",
                        5,
                        "label",
                        "Личный кабинет (5 минут)",
                        "url",
                        publicUrl + "/portal.html#" + token))));
      return body(
          buttons.isEmpty()
              ? "Сейчас у вас нет открытых каналов обращений. В личном кабинете доступна история."
              : "Ваши открытые обращения. Личную ссылку никому не передавайте.",
          components);
    }
    if (name.startsWith("lwa:desk:draft:")) {
      String kind = name.substring("lwa:desk:draft:".length());
      if (!Set.of("report", "appeal").contains(kind))
        throw new IllegalArgumentException("Неизвестная форма");
      if (!submissions.take(i.userId()))
        throw new IllegalArgumentException("Слишком много обращений. Повторите через 5 минут.");
      var data = validate(kind, i.userId(), i.values());
      String id = store.draft(i.userId(), kind, data);
      return body(
          "**Проверьте обращение**\n"
              + (kind.equals("report")
                  ? "Жалоба на " + data.get("target")
                  : "Апелляция на наказание №" + data.get("banId"))
              + "\nВаш ник: "
              + data.get("nick")
              + "\n\n"
              + data.get("reason")
                  .toString()
                  .substring(0, Math.min(data.get("reason").toString().length(), 1400))
              + "\n\nПосле подтверждения появится приватный канал.",
          List.of(
              row(
                  List.of(
                      button("Отправить обращение", "lwa:desk:confirm:" + id, 3),
                      button("Отменить", "lwa:desk:cancel:" + id, 2)))));
    }
    if (name.startsWith("lwa:desk:confirm:") || name.startsWith("lwa:desk:cancel:")) {
      String id = name.substring(name.lastIndexOf(':') + 1);
      var draft = store.draft(id, i.userId());
      if (draft.state().equals("CONFIRMED")) return existing(draft.kind(), draft.caseId());
      if (!draft.state().equals("DRAFT"))
        throw new IllegalArgumentException(
            "Форма уже обработана или создание требует проверки сотрудником");
      if (name.startsWith("lwa:desk:cancel:")) {
        store.consume(id, i.userId(), true);
        return body("Отправка отменена.", List.of());
      }
      var data = validate(draft.kind(), i.userId(), draft.data());
      if (!store.consume(id, i.userId(), false))
        throw new IllegalArgumentException("Форма уже обрабатывается");
      int caseId;
      try {
        caseId = createRecord(draft.kind(), i.userId(), data);
      } catch (IllegalArgumentException rejected) {
        store.rejectDraft(id);
        throw rejected;
      }
      store.completeDraft(id, caseId);
      return existing(draft.kind(), caseId);
    }
    String[] parts = name.split(":");
    if (parts.length < 5) throw new IllegalArgumentException("Кнопка недоступна");
    boolean submit = parts[2].equals("submit");
    String action = submit ? parts[3] : parts[2], kind = submit ? parts[4] : parts[3];
    int id = Integer.parseInt(submit ? parts[5] : parts[4]);
    if (!Set.of("report", "appeal").contains(kind))
      throw new IllegalArgumentException("Неизвестное обращение");
    var ticket =
        store.get(kind, id).orElseThrow(() -> new IllegalArgumentException("Обращение не найдено"));
    if (!ticket.state().equals("OPEN"))
      throw new IllegalArgumentException("Обращение уже закрывается или закрыто");
    if (!domainOpen(ticket))
      throw new IllegalArgumentException(
          "Рассмотрение уже завершено; канал будет закрыт после сохранения истории");
    String channel = i.values().getOrDefault("_channelId", "");
    if (!ticket.channel().equals(channel)
        && !(action.equals("claim") && queueChannel.equals(channel)))
      throw new IllegalArgumentException("Используйте кнопку в соответствующем канале обращения");
    if (action.equals("close") && ticket.owner().equals(i.userId())) {
      requestClose(ticket, "Автор обращения", reason(i));
      return body(
          "Обращение отозвано. История сохраняется, затем канал будет удалён. Наказание не изменяется.",
          List.of());
    }
    var admin =
        actions
            .staffMember(i.userId(), permission(kind))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Нужны действующие права сотрудника и подтверждённый аккаунт"));
    boolean boss = supervisor(admin, i.userId());
    String assigned = assignee(ticket);
    if (action.equals("claim")) {
      if (!assigned.isBlank() && !assigned.equals(admin.username()) && !boss)
        throw new IllegalArgumentException("Обращение уже назначено другому сотруднику");
      conflict(ticket, admin.username());
      if (assigned.isBlank() && !plugin.moderation().claim(kind, id, admin.username()))
        throw new IllegalArgumentException("Другой сотрудник уже взял обращение");
      sync(store.get(kind, id).orElseThrow());
      audit(admin.username(), ticket, "Взял обращение");
      return body("Обращение доступно: <#" + ticket.channel() + ">", List.of());
    }
    if (!assigned.equals(admin.username()) && !boss)
      throw new IllegalArgumentException(
          "Сначала возьмите обращение; чужим делом управляет ответственный или руководство");
    conflict(ticket, admin.username());
    String reason = reason(i);
    if (Set.of("approve", "reject", "reduce").contains(action))
      return caseWorkflowActions.preview(i, kind, id, admin, action, reason);
    String fingerprint =
        me.lovelace.loveWebAdmin.web.MutationRequests.fingerprint(
            "DISCORD",
            name,
            JsonUtils.toJson(i.values()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (plugin.operations().reserve(actor, i.id(), name, fingerprint) != null)
      throw new IllegalArgumentException("Действие уже обработано. Обновите карточку.");
    try {
      switch (action) {
        case "transfer" -> {
          String target = i.values().getOrDefault("staff", "").trim();
          var next =
              plugin
                  .getDatabaseManager()
                  .getAdminByUsername(target)
                  .filter(a -> a.status().equals("ACTIVE") && allowed(a, permission(kind)))
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Сотрудник не найден или у него нет нужных прав"));
          conflict(ticket, next.username());
          if (linked(next.username()).isEmpty())
            throw new IllegalArgumentException("У сотрудника не привязан Discord");
          if (!plugin
              .moderation()
              .updateWork(kind, id, assigned, next.username(), "NORMAL", "IN_PROGRESS"))
            throw new IllegalArgumentException("Назначение изменилось. Обновите карточку.");
        }
        case "clarify", "escalate" -> {
          if (!plugin
              .moderation()
              .updateWork(
                  kind,
                  id,
                  assigned,
                  assigned,
                  action.equals("escalate") ? "HIGH" : "NORMAL",
                  action.equals("clarify") ? "WAITING" : "IN_PROGRESS"))
            throw new IllegalArgumentException("Назначение изменилось");
          sendConversation(ticket, admin.username(), reason);
        }
        case "approve", "reject", "reduce" -> {
          if (kind.equals("appeal")) {
            if (action.equals("approve") && !allowed(admin, Permission.MANAGE_BANS))
              throw new IllegalArgumentException("Нужно право управления наказаниями");
            if (action.equals("reduce")) {
              if (!allowed(admin, Permission.MANAGE_BANS))
                throw new IllegalArgumentException("Нужно право управления наказаниями");
              long minutes = DiscordActions.minutes(i.values().get("minutes"));
              plugin
                  .appeals()
                  .reduce(id, admin.username(), Math.multiplyExact(minutes, 60), reason);
            } else plugin.appeals().decide(id, admin.username(), action.equals("approve"), reason);
          } else {
            boolean resolved =
                plugin
                    .main()
                    .call(
                        () ->
                            plugin
                                .getReportManager()
                                .resolveReport(
                                    id,
                                    action.equals("approve") ? "ACCEPTED" : "REJECTED",
                                    admin.username(),
                                    null))
                    .get(30, TimeUnit.SECONDS);
            if (!resolved) throw new IllegalArgumentException("Жалоба уже рассмотрена");
            sendConversation(
                ticket,
                admin.username(),
                "Решение: "
                    + (action.equals("approve") ? "жалоба подтверждена" : "жалоба отклонена")
                    + ". "
                    + reason);
          }
        }
        case "close" -> requestClose(ticket, admin.username(), reason);
        default -> throw new IllegalArgumentException("Неизвестное действие");
      }
      if (Set.of("approve", "reject", "reduce").contains(action))
        requestClose(ticket, admin.username(), "Решение сотрудника: " + reason);
      plugin
          .moderation()
          .note(kind, id, admin.username(), "Действие Discord: " + action + ". " + reason);
      audit(admin.username(), ticket, action + ": " + reason);
      plugin.operations().complete(actor, i.id(), 200, "Обработано", permission(kind).name());
      if (store.get(kind, id).orElseThrow().state().equals("OPEN"))
        sync(store.get(kind, id).orElseThrow());
      return body(
          action.equals("close")
              ? "Сначала сохраняются переписка и файлы; затем канал будет удалён. Наказание не меняется."
              : "Действие сохранено.",
          List.of());
    } catch (Exception error) {
      plugin.operations().uncertain(actor, i.id());
      throw error;
    }
  }

  private String reason(DiscordInteraction i) {
    String value = i.values().getOrDefault("reason", "").trim();
    if (value.length() < 5 || value.length() > 2000)
      throw new IllegalArgumentException("Объяснение: от 5 до 2000 символов");
    return value;
  }

  private void audit(String actor, TicketDeskStore.Ticket ticket, String text) {
    plugin
        .getLogManager()
        .logWebAction(actor, "Обращение " + ticket.kind() + " №" + ticket.id() + ": " + text);
  }

  private void conflict(TicketDeskStore.Ticket ticket, String staff) throws Exception {
    if (ticket.kind().equals("appeal")) {
      var appeal = plugin.getDatabaseManager().getAppealById(ticket.id()).orElseThrow();
      var source = plugin.moderation().source(appeal.banId());
      if ("STAFF".equals(source.get("kind"))
          && staff.equalsIgnoreCase(source.get("actor").toString()))
        throw new IllegalArgumentException(
            "Своё наказание рассматривает другой сотрудник или руководство");
    } else if (plugin
        .getDatabaseManager()
        .getReportById(ticket.id())
        .orElseThrow()
        .targetName()
        .equalsIgnoreCase(staff))
      throw new IllegalArgumentException("Жалобу на себя рассматривает другой сотрудник");
  }

  private Map<String, Object> validate(String kind, String discord, Map<String, ?> input) {
    var linked = service.getLinkedPlayer(discord);
    String nick =
        linked
            .flatMap(id -> plugin.getDatabaseManager().getPlayerStats(id.toString()))
            .map(p -> p.get("name").toString())
            .orElse(Objects.toString(input.get("nick"), "").trim());
    if (!nick.matches("[A-Za-z0-9_]{1,16}"))
      throw new IllegalArgumentException("Укажите корректный ник Minecraft");
    String reason = Objects.toString(input.get("reason"), "").trim();
    if (reason.length() < 5 || reason.length() > 2000)
      throw new IllegalArgumentException("Описание: от 5 до 2000 символов");
    var data = new LinkedHashMap<String, Object>();
    data.put("nick", nick);
    data.put("reason", reason);
    if (kind.equals("report")) {
      String target = Objects.toString(input.get("target"), "").trim();
      if (!target.matches("[A-Za-z0-9_]{1,16}")
          || plugin.getDatabaseManager().getPlayerStats(target).isEmpty())
        throw new IllegalArgumentException("Игрок не найден в истории сервера");
      if (target.equalsIgnoreCase(nick) && linked.isPresent())
        throw new IllegalArgumentException("Нельзя подать жалобу на себя");
      data.put("target", target);
    } else {
      var ban =
          plugin
              .getDatabaseManager()
              .getActiveBan(nick)
              .orElseThrow(() -> new IllegalArgumentException("Активное наказание не найдено"));
      String requested =
          Objects.toString(input.get("ban"), Objects.toString(input.get("banId"), "")).trim();
      if (!requested.isBlank() && !requested.equals(String.valueOf(ban.id())))
        throw new IllegalArgumentException(
            "Этот номер не соответствует активному наказанию указанного аккаунта");
      data.put("banId", ban.id());
    }
    return data;
  }

  private int createRecord(String kind, String discord, Map<String, Object> data) throws Exception {
    String nick = data.get("nick").toString(), reason = data.get("reason").toString();
    if (kind.equals("appeal")) {
      int banId = ((Number) data.get("banId")).intValue();
      var existing = plugin.getDatabaseManager().getAppealByBanId(banId);
      if (existing.filter(a -> Set.of("PENDING", "DECIDING").contains(a.status())).isPresent()) {
        var previous = existing.get();
        if (plugin.moderation().owner(previous.id()).filter(discord::equals).isEmpty())
          throw new IllegalArgumentException(
              "По наказанию уже открыта апелляция; обратитесь к персоналу");
        return previous.id();
      }
      var ban = plugin.getDatabaseManager().getBanById(banId).orElseThrow();
      int id =
          plugin
              .getDatabaseManager()
              .createAppeal(banId, ban.targetUuid(), ban.targetName(), reason);
      if (id < 1) throw new IllegalStateException("Апелляция уже существует или не сохранена");
      plugin.moderation().createAccess(id, discord, service.getLinkedPlayer(discord).isPresent());
      plugin.getDatabaseManager().addAppealMessage(id, nick, false, reason);
      plugin
          .getDiscordBridge()
          .createAppealTicket(plugin.getDatabaseManager().getAppealById(id).orElseThrow());
      return id;
    }
    String target = data.get("target").toString();
    for (var ticket : store.active())
      if (ticket.kind().equals("report")
          && ticket.owner().equals(discord)
          && domainOpen(ticket)
          && plugin
              .getDatabaseManager()
              .getReportById(ticket.id())
              .map(r -> r.targetName().equalsIgnoreCase(target))
              .orElse(false)) return ticket.id();
    var identity = service.getLinkedPlayer(discord);
    String uuid =
        identity
            .orElseGet(
                () ->
                    UUID.nameUUIDFromBytes(
                        ("discord:" + discord).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .toString();
    String targetId =
        plugin.getDatabaseManager().getPlayerStats(target).orElseThrow().get("uuid").toString();
    if (!plugin.getDatabaseManager().canPlayerReportTarget(uuid, "", targetId))
      throw new IllegalArgumentException("Вы уже отправляли жалобу на этого игрока на этой неделе");
    int id =
        plugin
            .getDatabaseManager()
            .createPlayerReport(
                uuid, nick, "", targetId, target, List.of("Обращение в Discord"), reason, false, 0);
    if (id < 1) throw new IllegalStateException("Жалоба не сохранена");
    plugin.moderation().ticketOwner("report", id, discord);
    store.register("report", id, discord);
    var report = plugin.getDatabaseManager().getReportById(id).orElseThrow();
    plugin.getDiscordBridge().createReportTicket(report);
    plugin.getDiscordBridge().notifyReportCreated(report);
    return id;
  }

  private Map<String, Object> existing(String kind, int id) throws Exception {
    var ticket = store.get(kind, id);
    return body(
        (kind.equals("report") ? "Жалоба" : "Апелляция")
            + " №"
            + id
            + " сохранена. "
            + (ticket.isPresent() && !ticket.get().channel().isBlank()
                ? "Ваш канал: <#" + ticket.get().channel() + ">"
                : "Приватный канал создаётся. Откройте «Мои обращения» через несколько секунд."),
        List.of());
  }

  private void sendConversation(TicketDeskStore.Ticket ticket, String actor, String message) {
    if (ticket.kind().equals("appeal"))
      plugin
          .getDatabaseManager()
          .getAppealById(ticket.id())
          .ifPresent(
              a -> {
                plugin.getDatabaseManager().addAppealMessage(a.id(), actor, true, message);
                plugin.getDiscordBridge().sendTicketReply(a, actor, true, message);
              });
    else {
      if (plugin
          .getDatabaseManager()
          .getReportById(ticket.id())
          .filter(r -> r.status().equals("PENDING"))
          .isPresent())
        plugin
            .getDatabaseManager()
            .addReportDiscussion(
                ticket.id(), actor, true, message, "discord-staff:" + UUID.randomUUID());
      plugin.getDiscordBridge().sendReportConversation(ticket.id(), actor, message);
    }
  }

  public boolean permitsMessage(String kind, int id, String user) throws Exception {
    var ticket = store.get(kind, id);
    if (ticket.isEmpty()) return false;
    var t = ticket.get();
    if (!t.state().equals("OPEN")) return false;
    if (t.owner().equals(user)) return true;
    var admin =
        actions.staffMember(
            user, kind.equals("appeal") ? Permission.VIEW_APPEALS : Permission.VIEW_REPORTS);
    if (admin.isEmpty()) return false;
    if (assignee(t).equals(admin.get().username()) || supervisor(admin.get(), user)) return true;
    return kind.equals("appeal") && members(t).contains(user);
  }

  public CompletableFuture<Boolean> finish(String kind, int id, String reason) {
    return plugin
        .io()
        .supply(
            () -> {
              synchronized (writes) {
                try {
                  var ticket = store.get(kind, id);
                  if (ticket.isEmpty()) return false;
                  requestClose(ticket.get(), "Система", reason);
                  return true;
                } catch (Exception error) {
                  throw new CompletionException(error);
                }
              }
            });
  }

  private void requestClose(TicketDeskStore.Ticket ticket, String actor, String reason)
      throws Exception {
    if (reason == null || reason.trim().length() < 5 || reason.length() > 4000)
      throw new IllegalArgumentException("Объяснение: от 5 до 4000 символов");
    if (ticket.kind().equals("appeal")) {
      plugin.getDatabaseManager().closeAppealConversation(ticket.id());
      if (plugin
          .getDatabaseManager()
          .getAppealById(ticket.id())
          .filter(a -> a.status().equals("DECIDING"))
          .isPresent())
        throw new IllegalArgumentException("Дождитесь сохранения решения по апелляции");
    } else plugin.getDatabaseManager().resolveReport(ticket.id(), "CLOSED", actor);
    if (store.beginClose(ticket.kind(), ticket.id(), actor, reason)) {
      plugin
          .moderation()
          .note(
              ticket.kind(),
              ticket.id(),
              actor,
              "Закрытие переписки: " + reason + ". Наказание не меняется.");
      audit(actor, ticket, "Закрытие: " + reason);
    }
  }

  private void finishClose(TicketDeskStore.Ticket ticket) throws Exception {
    ticket = recoverChannel(ticket);
    var archive =
        new DiscordTicketArchive(
            store,
            new DiscordTicketArchive.Transport() {
              public void readOnly(String channel) throws Exception {
                Boolean ok =
                    call(
                        "setTicketReadOnly",
                        new Class<?>[] {String.class, boolean.class},
                        channel,
                        true);
                if (!Boolean.TRUE.equals(ok))
                  throw new IllegalStateException("Канал не заблокирован");
              }

              public List<TicketMessage> history(String channel, String before) throws Exception {
                return call(
                    "getTicketHistory",
                    new Class<?>[] {String.class, String.class},
                    channel,
                    before);
              }

              public byte[] attachment(String channel, String message, String url)
                  throws Exception {
                return call(
                    "getTicketAttachment",
                    new Class<?>[] {String.class, String.class, String.class},
                    channel,
                    message,
                    url);
              }

              public void delete(String channel) throws Exception {
                Boolean ok = call("deleteTicketChannel", new Class<?>[] {String.class}, channel);
                if (!Boolean.TRUE.equals(ok))
                  throw new IllegalStateException("Удаление не подтверждено");
              }
            },
            () -> !closed);
    archive.finish(ticket);
    var current = store.get(ticket.kind(), ticket.id()).orElseThrow();
    if (current.state().equals("CLOSED")) {
      if (ticket.kind().equals("appeal"))
        plugin.getDatabaseManager().setAppealDiscordChannelId(ticket.id(), null);
      else plugin.moderation().ticketChannel(ticket.kind(), ticket.id(), "");
      String event = "desk-close:" + ticket.kind() + ":" + ticket.id();
      if (!ticket.owner().isBlank())
        plugin
            .moderation()
            .enqueue(
                event,
                ticket.owner(),
                JsonUtils.toJson(
                    explainedBody(
                        "Обращение №"
                            + ticket.id()
                            + " закрыто. Переписка сохранена. Закрытие канала не меняет наказание.",
                        ticket.reason().isBlank() ? "Переписка завершена" : ticket.reason(),
                        List.of(
                            row(
                                List.of(
                                    button(
                                        "Есть новые доказательства",
                                        "lwa:flow:review:new:" + ticket.kind() + ":" + ticket.id(),
                                        1)))))));

      if (!queueChannel.isBlank())
        publish(
            "queue:" + ticket.kind() + ":" + ticket.id(),
            queueChannel,
            body(
                "**Обращение №" + ticket.id() + " закрыто**\nИстория и файлы сохранены.",
                List.of()));
      store.notified(ticket.kind(), ticket.id());
      published.remove("case:" + ticket.kind() + ":" + ticket.id());
      publishedBodies.remove("case:" + ticket.kind() + ":" + ticket.id());
      published.remove("queue:" + ticket.kind() + ":" + ticket.id());
      publishedBodies.remove("queue:" + ticket.kind() + ":" + ticket.id());
    }
  }
}
