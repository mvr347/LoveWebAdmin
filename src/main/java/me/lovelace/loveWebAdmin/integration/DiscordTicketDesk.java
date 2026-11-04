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
  /** Last warning logged per ticket, so an unchanged failure is not repeated every minute. */
  private final Map<String, String> lastWarning = new ConcurrentHashMap<>();
  private volatile boolean closed;
  private volatile DiscordService service;
  private DiscordActions actions;
  private final DiscordCaseWorkflowActions caseWorkflowActions;
  private final CaseReminderDelivery reminders;
  private final DiscordTicketWizard wizard;
  private final DiscordSupportFlow supportFlow;
  private final SupportTopics supportTopics;

  public DiscordTicketDesk(LoveWebAdmin plugin, TicketDeskStore store) {
    this.plugin = plugin;
    this.store = store;
    this.caseWorkflowActions = new DiscordCaseWorkflowActions(plugin, this);
    this.reminders = new CaseReminderDelivery(plugin, this);
    this.wizard = new DiscordTicketWizard(plugin,this,store);
    this.supportFlow = new DiscordSupportFlow(plugin, this);
    var config = plugin.getConfig();
    this.supportTopics =
        SupportTopics.parse(
            config.getBoolean("discord.tickets.support.enabled", false),
            config.getMapList("discord.tickets.support.topics"));
    if (config.getBoolean("discord.tickets.support.enabled", false) && !supportTopics.enabled())
      plugin.getLogger().warning("Поддержка отключена: " + supportTopics.problem());
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

  DiscordService service() {
    return service;
  }

  SupportTopics supportTopics() {
    return supportTopics;
  }

  /** Why support is not offered although it was switched on; empty when it is working. */
  public String supportProblem() {
    return supportTopics.problem();
  }

  /** Human title of a configured topic; the raw id when the topic was removed from config. */
  public String supportTopicLabel(String id) {
    return supportTopics.find(id).map(SupportTopics.Topic::label).orElse(id);
  }

  /** A staff answer written in the web panel: into the private channel and to the author. */
  public void supportReply(int id, String actor, String text) throws Exception {
    if (!enabled()) throw new IllegalArgumentException("Обращения временно недоступны");
    if (text == null || text.trim().length() < 5 || text.length() > 1800)
      throw new IllegalArgumentException("Сообщение: от 5 до 1800 символов");
    synchronized (writes) {
      var ticket =
          store
              .get("support", id)
              .filter(t -> t.state().equals("OPEN") && domainOpen(t))
              .orElseThrow(() -> new IllegalArgumentException("Обращение уже закрыто"));
      if (ticket.channel().isBlank())
        throw new IllegalArgumentException("Канал обращения ещё не создан");
      sendConversation(ticket, actor, text.trim());
      audit(actor, ticket, "Ответ из панели");
    }
  }

  /** Closes a case from the web panel through the same pipeline as the Discord button. */
  public void closeFromPanel(String kind, int id, String actor, String reason) throws Exception {
    if (!enabled()) throw new IllegalArgumentException("Обращения временно недоступны");
    synchronized (writes) {
      var ticket =
          store
              .get(kind, id)
              .filter(t -> t.state().equals("OPEN"))
              .orElseThrow(() -> new IllegalArgumentException("Обращение уже закрывается или закрыто"));
      requestClose(ticket, actor, reason == null ? "" : reason.trim());
    }
  }

  static boolean isSupport(String kind) {
    return "support".equals(kind);
  }

  static String caseLabel(String kind) {
    return switch (kind) {
      case "report" -> "Жалоба";
      case "support" -> "Поддержка";
      default -> "Апелляция";
    };
  }

  /** The core ticket type a desk kind is created with. */
  static TicketType coreType(String kind) {
    return switch (kind) {
      case "appeal" -> TicketType.BAN_APPEAL;
      case "support" -> TicketType.SUPPORT;
      default -> TicketType.REPORT;
    };
  }

  /** Appeals keep their bare numeric id for compatibility; other kinds are namespaced. */
  static String coreKey(String kind, int id) {
    return kind.equals("appeal") ? String.valueOf(id) : kind + ":" + id;
  }

  /**
   * The core accepts only {@code mine-ticket-N} with an optional {@code -a} or {@code -r}. Support
   * uses the bare form: lookups also compare the ticket id in the topic ("support:N"), so it
   * cannot be mistaken for a report or an appeal with the same number.
   */
  static String channelName(String kind, int id) {
    return "mine-ticket-" + id + switch (kind) {
      case "appeal" -> "-a";
      case "support" -> "";
      default -> "-r";
    };
  }

  /** Closing a conversation only changes a punishment for reports and appeals. */
  static String punishmentNote(String kind) {
    return isSupport(kind) ? "" : " Наказание не меняется.";
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

  /**
   * Test tool: deletes every message the bot wrote in the guild text channels and known direct
   * messages. Stored message ids become dangling on purpose: publishing edits a deleted message,
   * gets 404 and creates a replacement, so the panel and cards come back by themselves.
   */
  public int purgeBotMessages(java.util.function.Consumer<String> out) throws Exception {
    if (service == null || !service.isEnabled()) throw new IllegalStateException("Бот Discord выключен");
    List<String> channels = call("listPurgeableChannels", new Class<?>[] {});
    int total = 0;
    for (String channel : channels) {
      try {
        Integer deleted =
            CoreDiscordExtensions.<Integer>managed(
                    service, "purgeOwnMessages", new Class<?>[] {String.class}, channel)
                .get(10, TimeUnit.MINUTES);
        total += deleted == null ? 0 : deleted;
        out.accept(channel + " → удалено " + deleted);
      } catch (Exception error) {
        out.accept(channel + " → пропущен: " + DiscordMessagePublisher.explanation(error));
      }
    }
    published.clear();
    publishedBodies.clear();
    retryAfter.clear();
    return total;
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
    return DiscordPanelMessages.body("lwa:desk:", false, supportTopics.enabled());
  }

  public Map<String, Object> panelStatus() throws Exception {
    var result = new LinkedHashMap<String, Object>();
    result.put("channelId", panelChannel);
    result.put("botAvailable", enabled());
    result.put("supportEnabled", supportTopics.enabled());
    result.put("supportProblem", supportTopics.problem());
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
                    if (lastWarning.remove(retryKey) != null)
                      plugin
                          .getLogger()
                          .info("Обращение №" + ticket.id() + ": синхронизация восстановлена");
                  } catch (Exception error) {
                    retryAfter.put(retryKey, System.currentTimeMillis() + 60000);
                    String warning =
                        "Обращение №"
                            + ticket.id()
                            + ": "
                            + (ticket.channel().isBlank() ? "канал не создан. " : "Канал сохранён. ")
                            + ticketFailure(ticket, error);
                    if (!warning.equals(lastWarning.put(retryKey, warning)))
                      plugin.getLogger().warning(warning);
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

  private String ticketFailure(TicketDeskStore.Ticket ticket,Throwable error) {
    if(!ticket.channel().isBlank()) return DiscordMessagePublisher.explanation(error);
    try {
      return DiscordTicketFailure.describe(new java.util.concurrent.RejectedExecutionException("Не удалось проверить каналы обращения; новый канал не создавался",error),store.creationStarted(ticket.kind(),ticket.id()));
    }catch(java.sql.SQLException unavailable) {return "Не удалось прочитать состояние создания канала из базы; обращение не удалено";}
  }

  /** Discord confirmed (HTTP 404) or the core already forgot the channel: it no longer exists. */
  static boolean channelGone(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof IllegalArgumentException
          && "Канал не является активным обращением".equals(cause.getMessage())) return true;
      try {
        if (((Number) cause.getClass().getMethod("getStatusCode").invoke(cause)).intValue() == 404)
          return true;
      } catch (ReflectiveOperationException ignored) {
      }
    }
    return false;
  }

  /** Reconcile a lost creation result without staff once Discord can no longer answer late. */
  long autoReconcileMs = 3 * 60_000L; // package-private: tests shorten it

  private boolean reconcileLostCreation(TicketDeskStore.Ticket ticket) throws Exception {
    if (!ticket.channel().isBlank() || !store.creationStarted(ticket.kind(), ticket.id()))
      return false;
    long at = store.creationAt(ticket.kind(), ticket.id());
    if (at <= 0 || System.currentTimeMillis() - at < autoReconcileMs) return false;
    Boolean pending;
    try {
      var type = coreType(ticket.kind());
      String key = coreKey(ticket.kind(), ticket.id());
      pending =
          (Boolean)
              Objects.requireNonNull(service)
                  .getClass()
                  .getMethod("isTicketCreationPending", TicketType.class, String.class)
                  .invoke(service, type, key);
    } catch (ReflectiveOperationException oldCore) {
      return false; // An older core cannot confirm that nothing is in flight: stay manual.
    }
    if (Boolean.TRUE.equals(pending)) return false;
    store.creationFailed(
        ticket.kind(),
        ticket.id(),
        "Автосверка: канал не найден, создание не выполняется; повтор разрешён",
        true);
    plugin
        .getLogger()
        .info("Обращение " + ticket.kind() + " №" + ticket.id() + ": автосверка, повторное создание канала");
    return true;
  }

  private String closureReason(TicketDeskStore.Ticket ticket) {
    if (ticket.kind().equals("appeal")) {
      var messages = plugin.getDatabaseManager().getAppealMessages(ticket.id());
      if (!messages.isEmpty()) return messages.getLast().message();
    }
    return "Рассмотрение обращения завершено";
  }

  private boolean domainOpen(TicketDeskStore.Ticket ticket) {
    if (isSupport(ticket.kind())) {
      try {
        return plugin
            .moderation()
            .support(ticket.id())
            .filter(row -> "OPEN".equals(row.get("status")))
            .isPresent();
      } catch (java.sql.SQLException error) {
        throw new IllegalStateException(error);
      }
    }
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
    // Support has no punishment behind it, so it is handled with the report permissions.
    return kind.equals("appeal") ? Permission.MANAGE_APPEALS : Permission.MANAGE_REPORTS;
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
                  coreType(ticket.kind()),
                  coreKey(ticket.kind(), ticket.id()),
                  "",
                  "",
                  ticket.owner().isBlank() ? null : ticket.owner(),
                  supervisorRoles,
                  members(ticket),
                  channelName(ticket.kind(), ticket.id()),
                  true)
              .get(45, TimeUnit.SECONDS);
      if (recovered == null) return ticket;
      store.channel(ticket.kind(), ticket.id(), recovered);
      if (ticket.kind().equals("appeal"))
        plugin.getDatabaseManager().setAppealDiscordChannelId(ticket.id(), recovered);
      else if (!isSupport(ticket.kind()))
        plugin.moderation().ticketChannel(ticket.kind(), ticket.id(), recovered);
      ticket = store.get(ticket.kind(), ticket.id()).orElseThrow();
    }
    return ticket;
  }

  private void sync(TicketDeskStore.Ticket ticket) throws Exception {
    ticket = recoverChannel(ticket);
    if (ticket.channel().isBlank() && reconcileLostCreation(ticket)) {
      create(ticket.kind(), ticket.id(), ticket.owner().isBlank() ? null : ticket.owner())
          .handle((channel, error) -> null);
      return;
    }
    if (ticket.channel().isBlank()
        && isSupport(ticket.kind())
        && !store.creationStarted(ticket.kind(), ticket.id())) {
      // Support has no outbox that retries channel creation, so the desk does it itself, at most
      // once a minute per request (tick() runs every few seconds).
      long now = System.currentTimeMillis();
      String createKey = "create:" + ticket.kind() + ":" + ticket.id();
      if (now >= retryAfter.getOrDefault(createKey, 0L)) {
        retryAfter.put(createKey, now + 60_000);
        create(ticket.kind(), ticket.id(), ticket.owner().isBlank() ? null : ticket.owner())
            .handle((channel, error) -> null);
      }
      return;
    }
    if (ticket.channel().isBlank()) throw new IllegalStateException("Канал ещё не создан: "+store.creationError(ticket.kind(),ticket.id()));
    if (!ticket.state().equals("OPEN")) return;
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
    store.workDisplay(ticket.kind(), ticket.id(), status, assigned);
    String state =
        switch (status) {
          case "WAITING" -> "Ожидаем ответа игрока";
          case "IN_PROGRESS" -> "В работе";
          case "CLOSED" -> "Рассмотрение завершено";
          default -> "Ожидает сотрудника";
        };
    if ("HIGH".equals(plugin.moderation().work(ticket.kind(), ticket.id()).get("priority")))
      state = "Передано руководству · " + state;
    String title = caseLabel(ticket.kind()) + " №" + ticket.id();
    String description;
    if (isSupport(ticket.kind())) {
      var support = plugin.moderation().support(ticket.id()).orElseThrow();
      description =
          "Тема: "
              + supportTopics.find(support.get("topic").toString()).map(SupportTopics.Topic::label).orElse(support.get("topic").toString())
              + "\n\n"
              + support.get("body");
    } else
      description =
          ticket.kind().equals("appeal")
              ? plugin.getDatabaseManager().getAppealById(ticket.id()).orElseThrow().reason()
              : plugin.getDatabaseManager().getReportById(ticket.id()).orElseThrow().description();
    // Reminders and wait deadlines are computed from report/appeal discussions only.
    var waiting =
        isSupport(ticket.kind())
            ? Map.<String, Object>of("mode", "NONE")
            : plugin.workflows().waiting(ticket.kind(), ticket.id());
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
                    + (isSupport(ticket.kind())
                        ? "\n\nПереписка и вложения сохраняются до закрытия обращения."
                        : "\n\nПереписка и вложения сохраняются. Закрытие канала не снимает наказание."),
                channelControls(ticket)));
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
    plugin.getDiscordBridge().notifyAssigned(ticket.kind(),ticket.id(),assigned,ticket.channel());
  }

  /**
   * Buttons on the card inside the private channel. The author sits in that channel and Discord
   * cannot hide a button from one member, so the staff actions are not placed here: only an entry
   * point whose result is an ephemeral panel, visible to the staff member who pressed it.
   */
  private List<Map<String, Object>> channelControls(TicketDeskStore.Ticket ticket) {
    String key = ticket.kind() + ":" + ticket.id();
    return List.of(
        row(
            List.of(
                button("Действия сотрудника", "lwa:desk:panel:" + key, 1),
                button("Отозвать обращение", "lwa:desk:form:close:" + key, 2))));
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
    if (isSupport(ticket.kind()))
      return List.of(
          row(first),
          row(List.of(button("Закрыть / отозвать", "lwa:desk:form:close:" + key, 2))));
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
                boolean wasAmbiguous=false;
                try {
                  store.register(kind, id, owner);
                  var ticket = store.get(kind, id).orElseThrow();
                  wasAmbiguous=ticket.channel().isBlank() && store.creationStarted(kind,id);
                  if (!ticket.state().equals("OPEN"))
                    throw new IllegalArgumentException("Обращение закрывается");
                  if (ticket.channel().isBlank() && store.creationStarted(kind, id)) {
                    ticket = recoverChannel(ticket);
                    if (ticket.channel().isBlank()) throw new IllegalStateException("Результат создания неизвестен: нужна сверка каналов сотрудником");
                  }
                  if (ticket.channel().isBlank()) {
                    String name, reason;
                    if (isSupport(kind)) {
                      var support = plugin.moderation().support(id).orElseThrow();
                      name = support.get("nick").toString();
                      reason = support.get("body").toString();
                    } else if (kind.equals("appeal")) {
                      var appeal = plugin.getDatabaseManager().getAppealById(id).orElseThrow();
                      name = appeal.playerName();
                      reason = appeal.reason();
                    } else {
                      var report = plugin.getDatabaseManager().getReportById(id).orElseThrow();
                      name = report.reporterName();
                      reason = report.description();
                    }
                    store.startCreation(kind, id);
                    String channel =
                        CoreDiscordExtensions.managedTicket(
                                service,
                                coreType(kind),
                                coreKey(kind, id),
                                name,
                                reason,
                                owner,
                                supervisorRoles,
                                members(ticket),
                                channelName(kind, id))
                            .get(45, TimeUnit.SECONDS);
                    if (channel == null)
                      throw new IllegalStateException("Создание канала не подтверждено");
                    store.channel(kind, id, channel);
                    if (kind.equals("appeal")) {
                      if (!plugin.getDatabaseManager().setAppealDiscordChannelId(id, channel))
                        throw new IllegalStateException("Не сохранён канал");
                    } else if (!isSupport(kind)) {
                      // case_tickets is the report ticket registry; support lives in desk_tickets.
                      plugin.moderation().ticketOwner(kind, id, owner);
                      plugin.moderation().ticketChannel(kind, id, channel);
                    }
                    ticket = store.get(kind, id).orElseThrow();
                    if (!isSupport(kind)) plugin.getDiscordBridge().notifyTicketCreated(kind, id);
                  }
                  sync(ticket);
                  return ticket.channel();
                } catch (Exception error) {
                  boolean definitive = DiscordMessagePublisher.definitive(error);
                  for (Throwable cause=error; cause!=null; cause=cause.getCause())
                    if (cause instanceof IllegalArgumentException && Objects.toString(cause.getMessage(), "").startsWith("Категория тикетов")) definitive=true;
                  definitive = definitive && !wasAmbiguous;
                  String explanation = DiscordTicketFailure.describe(error,wasAmbiguous);
                  try { store.creationFailed(kind,id,explanation,definitive); } catch (java.sql.SQLException persistence) { error.addSuppressed(persistence); }
                  plugin.getLogger().warning("Обращение " + kind + " №" + id + ": " + explanation);
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
    if (name.equals("lwa:desk:mine") || name.equals("мои-обращения"))
      return actions.openMyCases(interaction);
    if (supportFlow.onInteraction(interaction, service)) return true;
    if(wizard.modal(interaction,service)) return true;
    if (name.startsWith("lwa:desk:form:")) {
      String[] parts = name.split(":");
      if (parts.length != 6
          || !Set.of("report", "appeal", "support").contains(parts[4])
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
                          result = body(message, wizard.recovery(interaction));
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
    var wizardResult=wizard.submit(i,service);
    if(wizardResult!=null) return wizardResult;
    if (name.startsWith("lwa:desk:draft:")) {
      String kind = name.substring("lwa:desk:draft:".length());
      if (!Set.of("report", "appeal").contains(kind))
        throw new IllegalArgumentException("Неизвестная форма");
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
    if (name.startsWith("lwa:desk:sdraft:")) return supportFlow.draft(i);
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
      if(((Number)draft.data().getOrDefault("_step",3)).intValue()!=3) throw new IllegalArgumentException("Сначала заполните описание обращения");
      var data = validate(draft.kind(), i.userId(), draft.data());
      if(draft.kind().equals("report")) {
        var prior=openReport(i.userId(),data.get("target").toString());
        if(prior.isPresent()&&!draft.data().containsKey("appendCase")) {
          var changed=new LinkedHashMap<String,Object>(draft.data());changed.put("appendCase",prior.get().id());store.updateDraft(id,i.userId(),changed);
          return body("Уже есть открытая жалоба №"+prior.get().id()+" на этого игрока. Новая жалоба не будет создана. Добавить введённую информацию в существующую?",List.of(row(List.of(button("Добавить информацию","lwa:desk:confirm:"+id,3),button("Отменить","lwa:desk:cancel:"+id,2)))));
        }
        if(draft.data().containsKey("appendCase")) data.put("appendCase",draft.data().get("appendCase"));
      }
      if (!data.containsKey("appendCase") && !submissions.take(i.userId())) throw new IllegalArgumentException("Слишком много обращений. Повторите через 5 минут.");
      if (!store.consume(id, i.userId(), false))
        throw new IllegalArgumentException("Форма уже обрабатывается");
      int caseId;
      try {
        caseId = createRecord(draft.kind(), i.userId(), data,id);
      } catch (IllegalArgumentException rejected) {
        store.rejectDraft(id);
        throw rejected;
      }
      store.completeDraft(id, caseId);
      if(data.containsKey("appendCase")) return body("Дополнение к жалобе №"+caseId+" сохранено. Оно будет доставлено в существующую переписку.",List.of(row(List.of(button("Открыть жалобу","lwa:mine:open:report:"+caseId,1)))));
      return existing(draft.kind(), caseId);
    }
    String[] parts = name.split(":");
    if (parts.length < 5) throw new IllegalArgumentException("Кнопка недоступна");
    boolean submit = parts[2].equals("submit");
    String action = submit ? parts[3] : parts[2], kind = submit ? parts[4] : parts[3];
    int id = Integer.parseInt(submit ? parts[5] : parts[4]);
    if (!Set.of("report", "appeal", "support").contains(kind))
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
        && !(action.equals("close") && ticket.owner().equals(i.userId()) && i.guildId().isBlank())
        && !(action.equals("claim") && queueChannel.equals(channel)))
      throw new IllegalArgumentException("Используйте кнопку в соответствующем канале обращения");
    if (action.equals("close") && ticket.owner().equals(i.userId())) {
      requestClose(ticket, "Автор обращения", reason(i));
      return body(
          "Обращение отозвано. История сохраняется, затем канал будет удалён."
              + (isSupport(kind) ? "" : " Наказание не изменяется."),
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
    if (action.equals("panel"))
      return body(
          "**Действия по обращению №" + id + "** · видно только вам\nОтветственный: "
              + (assigned.isBlank() ? "не назначен" : assigned),
          controls(ticket));
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
    if (Set.of("approve", "reject", "reduce").contains(action)) {
      if (isSupport(kind))
        throw new IllegalArgumentException(
            "Для обращений в поддержку нет вердиктов: ответьте игроку и закройте обращение");
      return caseWorkflowActions.preview(i, kind, id, admin, action, reason);
    }
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
              ? "Сначала сохраняются переписка и файлы; затем канал будет удалён." + punishmentNote(kind)
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
    if (isSupport(ticket.kind())) return; // nobody is being judged in a support request
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

  Map<String, Object> validate(String kind, String discord, Map<String, ?> input) {
    if (isSupport(kind))
      return supportFlow.validate(discord, Objects.toString(input.get("topic"), ""), input);
    var context=new DiscordPlayerContext(plugin,service);
    var identity=context.linked(discord);
    var linked=identity.map(DiscordPlayerContext.Identity::uuid);
    String currentIdentity=linked.map(UUID::toString).orElse("");
    if(input.containsKey("linkedUuid")&&!Objects.toString(input.get("linkedUuid"),"").equals(currentIdentity)) throw new IllegalArgumentException("Привязка аккаунта изменилась. Откройте форму заново, чтобы проверить участника");
    String nick=identity.map(DiscordPlayerContext.Identity::name).orElse(Objects.toString(input.get("nick"),"").trim());
    if (!nick.matches("[A-Za-z0-9_]{1,16}"))
      throw new IllegalArgumentException("Укажите корректный ник Minecraft");
    String reason = Objects.toString(input.get("reason"), "").trim();
    if (reason.length() < 5 || reason.length() > 2000)
      throw new IllegalArgumentException("Описание: от 5 до 2000 символов");
    var data = new LinkedHashMap<String, Object>();
    data.put("nick", nick);
    data.put("linkedUuid",currentIdentity);
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
      var ban=context.appealBan(discord,input,nick);
      data.put("banId", ban.id());
    }
    return data;
  }

  Optional<me.lovelace.loveWebAdmin.database.TicketDeskStore.Ticket> openReport(String owner,String target) throws Exception {
    for(var ticket:store.active()) if(ticket.kind().equals("report")&&ticket.owner().equals(owner)&&ticket.state().equals("OPEN")&&domainOpen(ticket)&&plugin.getDatabaseManager().getReportById(ticket.id()).map(r->r.targetName().equalsIgnoreCase(target)).orElse(false)) return Optional.of(ticket);
    return Optional.empty();
  }

  private int createRecord(String kind, String discord, Map<String, Object> data, String draftId) throws Exception {
    String nick = data.get("nick").toString(), reason = data.get("reason").toString();
    if (isSupport(kind)) {
      if (!supportTopics.enabled()) throw new IllegalArgumentException("Поддержка сейчас недоступна");
      int id = plugin.moderation().createSupport(discord, nick, data.get("topic").toString(), reason);
      store.register("support", id, discord);
      // The channel is created on the io thread; "Мои обращения" shows its state meanwhile.
      create("support", id, discord).handle((channel, error) -> null);
      return id;
    }
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
    var previous=openReport(discord,target);
    if(data.containsKey("appendCase")) {
      int expected=((Number)data.get("appendCase")).intValue();
      var existing=previous.filter(t->t.id()==expected).orElseThrow(()->new IllegalArgumentException("Исходная жалоба уже завершена. Создайте новое обращение"));
      plugin.getDatabaseManager().addReportDiscussion(existing.id(),nick,false,reason,"discord-addition:"+draftId);
      int part=0;
      for(String quoted:LoveCoreDiscordBridge.quotedParts(nick,reason)) {
        String key="report-addition:"+draftId+":"+part++;
        plugin.moderation().enqueue(key,"report-ticket:"+existing.id(),JsonUtils.toJson(Map.of("type","REPORT_MESSAGE","reportId",existing.id(),"text",quoted,"nonce",me.lovelace.loveWebAdmin.database.ModerationStore.hash(key).substring(0,24))));
      }
      return existing.id();
    }
    if(previous.isPresent()) throw new IllegalArgumentException("Уже есть открытая жалоба. Подтвердите добавление информации в неё");
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
        caseLabel(kind)
            + " №"
            + id
            + " сохранена. "
            + (ticket.isPresent() && !ticket.get().channel().isBlank()
                ? "Ваш канал: <#" + ticket.get().channel() + ">"
                : "Приватный канал создаётся. Откройте «Мои обращения» через несколько секунд."),
        List.of(row(List.of(button("Открыть моё обращение","lwa:mine:open:"+kind+":"+id,1)))));
  }

  void replyFromOwner(String kind,int id,String owner,String interactionId,String text) throws Exception {
    if(!enabled()) throw new IllegalArgumentException("Обращения временно недоступны");
    var ticket=store.get(kind,id).filter(t->t.owner().equals(owner)).orElseThrow(()->new IllegalArgumentException("Обращение недоступно"));
    if(!ticket.state().equals("OPEN") || !domainOpen(ticket) || ticket.channel().isBlank()) throw new IllegalArgumentException("Переписка сейчас недоступна");
    if(text==null || text.trim().length()<5 || text.length()>1800) throw new IllegalArgumentException("Сообщение: от 5 до 1800 символов");
    String message=null;
    var parts=LoveCoreDiscordBridge.quotedParts("Автор обращения",text.trim());
    for(int index=0;index<parts.size();index++) {
      var payload=new LinkedHashMap<String,Object>(body(parts.get(index),List.of()));
      payload.put("nonce",me.lovelace.loveWebAdmin.database.ModerationStore.hash("owner-reply:"+interactionId+":"+index).substring(0,24));
      payload.put("enforce_nonce",true);
      message=service.sendMessage(ticket.channel(),JsonUtils.toJson(payload),null).get(45,TimeUnit.SECONDS);
      if(message==null) throw new IllegalStateException("Отправка не подтверждена; проверьте переписку перед повтором");
      store.archiveMessage(kind,id,message,owner,"Автор обращения",parts.get(index));
    }
    // A support conversation lives only in the channel and its archive; nothing to mirror.
    if (!isSupport(kind))
      actions.onTicketMessage(new TicketMessage(kind.equals("report")?"report:"+id:String.valueOf(id),ticket.channel(),message,owner,"Автор обращения",text.trim(),List.of()));
  }

  void notifyOwner(String kind,int id,String event,String title,String text) throws Exception {
    var ticket=store.get(kind,id);
    if(ticket.isEmpty() || ticket.get().owner().isBlank()) return;
    var card=DiscordCards.decorate(body("**"+title+"**\n"+DiscordCards.cap(text,1500),List.of(row(List.of(button("Открыть обращение","lwa:mine:open:"+kind+":"+id,1))))),DiscordCards.BLUE);
    plugin.moderation().enqueue("owner-notice:"+kind+":"+id+":"+event,ticket.get().owner(),JsonUtils.toJson(Map.of("type","STAFF_DM","body",JsonUtils.toJson(card))));
  }

  private void sendConversation(TicketDeskStore.Ticket ticket, String actor, String message) {
    try { notifyOwner(ticket.kind(),ticket.id(),"reply:"+UUID.randomUUID(),"Сотрудник ответил · №"+ticket.id(),message); }
    catch(Exception error) { throw new IllegalStateException(error); }
    if (isSupport(ticket.kind())) {
      postToChannel(ticket, actor, message);
      return;
    }
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

  /** Staff text for a support request goes straight into its private channel. */
  private void postToChannel(TicketDeskStore.Ticket ticket, String actor, String message) {
    if (ticket.channel().isBlank()) return;
    try {
      var parts = LoveCoreDiscordBridge.quotedParts(actor, message);
      for (int index = 0; index < parts.size(); index++) {
        var payload = new LinkedHashMap<String, Object>(body(parts.get(index), List.of()));
        payload.put(
            "nonce",
            me.lovelace.loveWebAdmin.database.ModerationStore.hash(
                    "support-staff:" + ticket.id() + ":" + UUID.randomUUID() + ":" + index)
                .substring(0, 24));
        payload.put("enforce_nonce", true);
        String sent =
            service.sendMessage(ticket.channel(), JsonUtils.toJson(payload), null).get(45, TimeUnit.SECONDS);
        if (sent == null) throw new IllegalStateException("Отправка не подтверждена");
        store.archiveMessage(ticket.kind(), ticket.id(), sent, "staff", actor, parts.get(index));
      }
    } catch (Exception error) {
      throw new IllegalStateException(error);
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
    if (isSupport(ticket.kind())) {
      plugin.moderation().closeSupport(ticket.id());
    } else if (ticket.kind().equals("appeal")) {
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
              "Закрытие переписки: " + reason + "." + punishmentNote(ticket.kind()));
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
                Boolean ok;
                try {
                  ok =
                      call(
                          "setTicketReadOnly",
                          new Class<?>[] {String.class, boolean.class},
                          channel,
                          true);
                } catch (Exception error) {
                  if (channelGone(error)) throw new DiscordTicketArchive.ChannelGoneException(error);
                  throw error;
                }
                if (!Boolean.TRUE.equals(ok))
                  throw new IllegalStateException("Канал не заблокирован");
              }

              public List<TicketMessage> history(String channel, String before) throws Exception {
                try {
                  return call(
                      "getTicketHistory",
                      new Class<?>[] {String.class, String.class},
                      channel,
                      before);
                } catch (Exception error) {
                  if (channelGone(error)) throw new DiscordTicketArchive.ChannelGoneException(error);
                  throw error;
                }
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
                Boolean ok;
                try {
                  ok = call("deleteTicketChannel", new Class<?>[] {String.class}, channel);
                } catch (Exception error) {
                  // Already deleted outside the bot: the goal of this step is reached.
                  if (channelGone(error)) return;
                  throw error;
                }
                if (!Boolean.TRUE.equals(ok))
                  throw new IllegalStateException("Удаление не подтверждено");
              }
            },
            () -> !closed);
    archive.finish(ticket);
    if (archive.lastChannelGone)
      plugin
          .moderation()
          .note(
              ticket.kind(),
              ticket.id(),
              "Система",
              "Канал обращения удалён вне бота: сохранена история до удаления, позднее сообщения недоступны");
    var current = store.get(ticket.kind(), ticket.id()).orElseThrow();
    if (current.state().equals("CLOSED")) {
      if (ticket.kind().equals("appeal"))
        plugin.getDatabaseManager().setAppealDiscordChannelId(ticket.id(), null);
      else if (!isSupport(ticket.kind()))
        plugin.moderation().ticketChannel(ticket.kind(), ticket.id(), "");
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
                            + " закрыто. Переписка сохранена."
                            + (isSupport(ticket.kind()) ? "" : " Закрытие канала не меняет наказание."),
                        ticket.reason().isBlank() ? "Переписка завершена" : ticket.reason(),
                        List.of(
                            row(
                                isSupport(ticket.kind())
                                    ? List.of(
                                        button("Открыть обращение","lwa:mine:open:"+ticket.kind()+":"+ticket.id(),1))
                                    : List.of(
                                        button("Открыть обращение","lwa:mine:open:"+ticket.kind()+":"+ticket.id(),1),
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
