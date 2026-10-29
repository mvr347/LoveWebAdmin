package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.discord.DiscordEmbed;
import dev.lovelace.lovecore.api.discord.DiscordService;
import dev.lovelace.lovecore.api.discord.TicketType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.api.events.PlayerReportCreatedEvent;
import me.lovelace.loveWebAdmin.models.PlayerReport;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebBanAppeal;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Интеграция с Discord через единый сервис LoveCore (DiscordService). Обеспечивает: - Создание
 * приватных тикетов Discord для апелляций (ticket-бан-<player>) - Двустороннюю синхронизацию
 * сообщений между Discord и WebAdmin - Уведомления администраторам в ЛС Discord (по настройкам в
 * профиле) - Уведомления о жалобах (Reports) и апелляциях (Tickets)
 */
public class LoveCoreDiscordBridge implements Listener {

  private final LoveWebAdmin plugin;
  private final me.lovelace.loveWebAdmin.database.ModerationStore store;
  private final me.lovelace.loveWebAdmin.database.DatabaseManager database;
  private final me.lovelace.loveWebAdmin.concurrent.VirtualIoExecutor io;
  private final me.lovelace.loveWebAdmin.concurrent.MainDispatcher main;
  private final DiscordNotificationPolicy notificationPolicy;

  private boolean initialized = false;
  private dev.lovelace.lovecore.api.discord.TicketMessageListener ticketListener;
  private volatile DiscordService registeredService;
  // Resolved while the service's plugin is alive: on shutdown that plugin may already be disabled
  // and its jar closed, so reflecting on the service class then throws "zip file closed".
  private java.lang.reflect.Method unregisterMethod;
  private DiscordActions actions;
  private final me.lovelace.loveWebAdmin.database.TicketDeskStore ticketDeskStore;
  private final DiscordTicketDesk ticketDesk;

  public DiscordTicketDesk ticketDesk() {
    return ticketDesk;
  }

  public me.lovelace.loveWebAdmin.database.TicketDeskStore ticketDeskStore() {
    return ticketDeskStore;
  }

  private org.bukkit.scheduler.BukkitTask maintenanceTask;
  private volatile boolean closed;
  private final java.util.concurrent.atomic.AtomicBoolean delivering =
      new java.util.concurrent.atomic.AtomicBoolean();

  public LoveCoreDiscordBridge(LoveWebAdmin plugin) {
    this.plugin = plugin;
    try {
      this.ticketDeskStore =
          new me.lovelace.loveWebAdmin.database.TicketDeskStore(
              plugin.getDataFolder().toPath().resolve("discord-desk.db"));
    } catch (Exception error) {
      throw new IllegalStateException("Не открыто хранилище обращений", error);
    }
    try {
      this.ticketDesk = new DiscordTicketDesk(plugin, ticketDeskStore);
    } catch (RuntimeException error) {
      try {
        ticketDeskStore.close();
      } catch (java.sql.SQLException close) {
        error.addSuppressed(close);
      }
      throw error;
    }
    this.store = plugin.moderation();
    this.database = plugin.getDatabaseManager();
    this.io = plugin.io();
    this.main = plugin.main();
    this.notificationPolicy = DiscordNotificationPolicy.from(plugin.getConfig());
  }

  public void initialize() {
    if (initialized) return;
    initialized = true;

    plugin.getServer().getPluginManager().registerEvents(this, plugin);

    ticketListener = this::onDiscordTicketMessage;
    maintenanceTask =
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimerAsynchronously(
                plugin,
                () -> {
                  if (closed) return;
                  discord()
                      .filter(DiscordService::isEnabled)
                      .ifPresent(
                          service -> {
                            if (registeredService != service) {
                              unregister();
                              synchronized (this) {
                                if (closed) return;
                                actions = new DiscordActions(plugin, service);
                                ticketDesk.attach(service, actions);
                                actions.register();
                                registeredService = service;
                                unregisterMethod = resolveUnregister(service);
                              }
                              try {
                                for (var ticket : store.tickets())
                                  if (database
                                      .getReportById(((Number) ticket.get("id")).intValue())
                                      .filter(report -> report.status().equals("PENDING"))
                                      .isPresent())
                                    service.restoreTicket(
                                        TicketType.REPORT,
                                        "report:" + ticket.get("id"),
                                        ticket.get("channel").toString());
                              } catch (java.sql.SQLException error) {
                                throw new IllegalStateException(error);
                              }
                              for (var appeal : database.getAllAppeals("PENDING", 200, 0))
                                if (appeal.discordChannelId() != null)
                                  service.restoreTicket(
                                      String.valueOf(appeal.id()), appeal.discordChannelId());
                            }
                            deliver(service);
                            ticketDesk.tick();
                          });
                },
                20L,
                100L);
  }

  private synchronized void unregister() {
    if (actions != null) {
      actions.close();
      actions = null;
    }
    if (registeredService != null && ticketListener != null) {
      if (unregisterMethod == null) {
        plugin.getLogger().warning("LoveCore требует обновления для снятия слушателя Discord");
      } else {
        try {
          unregisterMethod.invoke(registeredService, ticketListener);
        } catch (Throwable e) {
          // The service's plugin can be gone already while the server stops; nothing left to detach from.
          plugin.getLogger().fine("Слушатель Discord не снят: " + e);
        }
      }
    }
    unregisterMethod = null;
    registeredService = null;
  }

  private static java.lang.reflect.Method resolveUnregister(DiscordService service) {
    try {
      return service
          .getClass()
          .getMethod(
              "unregisterTicketMessageListener",
              dev.lovelace.lovecore.api.discord.TicketMessageListener.class);
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }

  public synchronized void close() {
    closed = true;
    ticketDesk.close();
    if (maintenanceTask != null) maintenanceTask.cancel();
    unregister();
  }

  public void notifyBanned(
      me.lovelace.loveWebAdmin.models.WebBan ban,
      me.lovelace.loveWebAdmin.models.BanOrigin origin) {
    if (ban.targetUuid() == null) return;
    discord()
        .flatMap(service -> service.getLinkedDiscordId(java.util.UUID.fromString(ban.targetUuid())))
        .ifPresent(
            id -> {
              String end = ban.expiresAt() > 0 ? "<t:" + ban.expiresAt() + ":F>" : "Бессрочно";
              String text =
                  plugin
                      .discordTemplates()
                      .render(
                          "ban",
                          Map.of(
                              "player",
                              ban.targetName(),
                              "reason",
                              ban.ruleReason(),
                              "source",
                              origin.display(),
                              "duration",
                              ban.durationSeconds() > 0
                                  ? ban.durationSeconds() + " сек."
                                  : "Бессрочно",
                              "expiry",
                              end,
                              "id",
                              String.valueOf(ban.id())));
              String url = plugin.getConfig().getString("web.public-url", "");
              if (!url.isBlank())
                text +=
                    "\nПодать апелляцию: "
                        + url.replaceAll("/+$", "")
                        + "/appeal.html?ban="
                        + ban.id();
              try {
                store.enqueue(
                    "ban:" + ban.id() + ":" + id,
                    id,
                    JsonUtils.toJson(
                        Map.of(
                            "content",
                            text,
                            "nonce",
                            me.lovelace
                                .loveWebAdmin
                                .database
                                .ModerationStore
                                .hash("ban:" + ban.id() + ":" + id)
                                .substring(0, 24),
                            "enforce_nonce",
                            true,
                            "components",
                            List.of(
                                Map.of(
                                    "type",
                                    1,
                                    "components",
                                    List.of(
                                        Map.of(
                                            "type",
                                            2,
                                            "style",
                                            1,
                                            "label",
                                            "Подать апелляцию",
                                            "custom_id",
                                            "lwa:appeal:new:" + ban.id())))))));
              } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Не удалось сохранить уведомление", e);
              }
            });
  }

  private void deliver(DiscordService service) {
    if (closed) return;
    if (!delivering.compareAndSet(false, true)) return;
    io.supply(
            () -> {
              var pending = new ArrayList<CompletableFuture<Void>>();
              try {
                for (var item : store.due()) {
                  if (closed) break;
                  int id = ((Number) item.get("id")).intValue();
                  if (!store.takeDelivery(id)) continue;
                  try {
                    Map<String, Object> payload =
                        JsonUtils.parseObject(item.get("payload").toString());
                    if ("STAFF_DM".equals(payload.get("type"))) {
                      int adminId = ((Number) payload.get("adminId")).intValue();
                      var admin = database.getAdminById(adminId);
                      String required = payload.get("permission").toString();
                      if (admin.isEmpty()
                          || !eligibleStaff(
                              admin.get(),
                              me.lovelace.loveWebAdmin.models.Permission.valueOf(required),
                              Boolean.TRUE.equals(payload.get("escalation")))
                          || !linkedDiscord(admin.get(), service)
                              .filter(item.get("recipient").toString()::equals)
                              .isPresent()) {
                        store.skipDelivery(id, "Привязка изменена или доступ отозван");
                        continue;
                      }
                      var prefs =
                          JsonUtils.parseObject(
                              admin.get().uiPreferences() == null
                                  ? "{}"
                                  : admin.get().uiPreferences());
                      if (!Boolean.TRUE.equals(prefs.get(payload.get("preference").toString()))) {
                        store.skipDelivery(id, "Сотрудник выключил уведомления");
                        continue;
                      }
                      String event = payload.get("event").toString();
                      if (event.startsWith("escalate-report:")) {
                        store.skipDelivery(id, "Заменено на настроенные сроки ожидания");
                        continue;
                      }
                      if (event.startsWith("report:") || event.startsWith("escalate-report:")) {
                        int reportId = Integer.parseInt(event.substring(event.indexOf(':') + 1));
                        boolean stale =
                            database
                                .getReportById(reportId)
                                .filter(r -> r.status().equals("PENDING"))
                                .isEmpty();
                        if (!stale && Boolean.TRUE.equals(payload.get("escalation")))
                          stale =
                              !store.work("report", reportId).get("assignee").toString().isBlank();
                        if (stale) {
                          store.skipDelivery(id, "Жалоба закрыта или уже назначена");
                          continue;
                        }
                      }
                      var next = notificationPolicy.nextDelivery(Instant.now());
                      if (next.isAfter(Instant.now())) {
                        store.deferDelivery(id, next.getEpochSecond());
                        continue;
                      }
                    }
                    CompletableFuture<String> sent;
                    if ("CASE_REMINDER".equals(payload.get("type"))) {
                      if (!ticketDesk.reminderAllowed(payload, item.get("recipient").toString())) {
                        store.skipDelivery(id, "Ожидание, доступ или настройки изменились");
                        continue;
                      }
                      if (Boolean.TRUE.equals(payload.get("staff"))) {
                        var next = notificationPolicy.nextDelivery(Instant.now());
                        if (next.isAfter(Instant.now())) {
                          store.deferDelivery(id, next.getEpochSecond());
                          continue;
                        }
                      }
                      sent =
                          service.directMessage(
                              item.get("recipient").toString(),
                              JsonUtils.toJson(Map.of("content", payload.get("content"))));
                    } else if ("CASE_IMAGE".equals(payload.get("type"))) {
                      String kind = payload.get("kind").toString();
                      int caseId = ((Number) payload.get("caseId")).intValue(),
                          imageId = ((Number) payload.get("evidenceId")).intValue();
                      String destination =
                          kind.equals("appeal")
                              ? database
                                  .getAppealById(caseId)
                                  .map(WebBanAppeal::discordChannelId)
                                  .orElse(null)
                              : store
                                  .ticket("report", caseId)
                                  .map(t -> t.get("channel").toString())
                                  .orElse(null);
                      var metadata =
                          store.evidence(kind, caseId, true).stream()
                              .filter(e -> ((Number) e.get("id")).intValue() == imageId)
                              .findFirst();
                      var bytes = store.evidenceBytes(kind, caseId, imageId, true);
                      if (metadata.isEmpty() || bytes.isEmpty()) {
                        store.skipDelivery(
                            id, "Изображение недоступно; проверьте доказательства в панели");
                        continue;
                      }
                      if (destination == null || destination.isBlank()) {
                        store.deferDelivery(id, System.currentTimeMillis() / 1000 + 60);
                        continue;
                      }
                      sent =
                          CoreDiscordExtensions.sendImage(
                              service,
                              destination,
                              "Доказательство №" + imageId + " · " + metadata.get().get("author"),
                              metadata.get().get("value").toString(),
                              metadata.get().get("mime").toString(),
                              bytes.get());
                    } else if ("REPORT_FINISH".equals(payload.get("type"))) {
                      int reportId = ((Number) payload.get("reportId")).intValue();
                      var ticket = store.ticket("report", reportId);
                      if (ticket.isEmpty() || ticket.get().get("channel").toString().isBlank())
                        sent = CompletableFuture.completedFuture("closed");
                      else
                        sent =
                            ticketDesk
                                .finish("report", reportId, payload.get("text").toString())
                                .thenCompose(
                                    ok ->
                                        io.supply(
                                            () -> {
                                              if (ok)
                                                try {
                                                  store.ticketChannel("report", reportId, "");
                                                } catch (java.sql.SQLException error) {
                                                  throw new IllegalStateException(error);
                                                }
                                              return ok ? "closed" : null;
                                            }));
                    } else if ("REPORT_TICKET".equals(payload.get("type"))) {
                      int reportId = ((Number) payload.get("reportId")).intValue();
                      var report = database.getReportById(reportId);
                      var owner = store.ticket("report", reportId);
                      if (report.isEmpty()
                          || owner.isEmpty()
                          || !report.get().status().equals("PENDING"))
                        sent = CompletableFuture.completedFuture("closed");
                      else if (!owner.get().get("channel").toString().isBlank())
                        sent =
                            CompletableFuture.completedFuture(
                                owner.get().get("channel").toString());
                      else
                        sent =
                            ticketDesk
                                .create("report", reportId, owner.get().get("discordId").toString())
                                .thenCompose(
                                    channel ->
                                        io.supply(
                                            () -> {
                                              if (channel != null && !closed)
                                                try {
                                                  store.ticketChannel("report", reportId, channel);
                                                } catch (java.sql.SQLException error) {
                                                  throw new IllegalStateException(error);
                                                }
                                              return channel;
                                            }));
                    } else if ("REPORT_MESSAGE".equals(payload.get("type"))) {
                      int reportId = ((Number) payload.get("reportId")).intValue();
                      var ticket = store.ticket("report", reportId);
                      if (ticket.isEmpty() || ticket.get().get("channel").toString().isBlank()) {
                        store.skipDelivery(
                            id, "Приватный тикет жалобы недоступен; ответ сохранён в панели");
                        continue;
                      }
                      sent =
                          service.sendMessage(
                              ticket.get().get("channel").toString(),
                              JsonUtils.toJson(
                                  Map.of(
                                      "content",
                                      payload.get("text"),
                                      "nonce",
                                      payload.get("nonce"),
                                      "enforce_nonce",
                                      true,
                                      "allowed_mentions",
                                      Map.of("parse", List.of()))),
                              null);
                    } else if ("APPEAL_TICKET".equals(payload.get("type"))) {
                      int appealId = ((Number) payload.get("appealId")).intValue();
                      var appeal = database.getAppealById(appealId);
                      if (appeal.isEmpty() || !"PENDING".equals(appeal.get().status()))
                        sent = CompletableFuture.completedFuture("closed");
                      else if (appeal.get().discordChannelId() != null)
                        sent = CompletableFuture.completedFuture(appeal.get().discordChannelId());
                      else sent = doCreateAppealTicket(appeal.get());
                    } else if ("APPEAL_MESSAGE".equals(payload.get("type"))
                        || "APPEAL_FINISH".equals(payload.get("type"))) {
                      int appealId = ((Number) payload.get("appealId")).intValue();
                      var appeal = database.getAppealById(appealId);
                      if (appeal.isEmpty() || appeal.get().discordChannelId() == null) {
                        store.skipDelivery(id, "Тикет не создан; переписка сохранена в панели");
                        continue;
                      }
                      if ("APPEAL_FINISH".equals(payload.get("type")))
                        sent =
                            doFinishAppeal(
                                    appeal.get(),
                                    payload.get("text").toString(),
                                    payload.get("nonce").toString())
                                .thenApply(ok -> ok ? "closed" : null);
                      else
                        sent =
                            service.sendMessage(
                                appeal.get().discordChannelId(),
                                JsonUtils.toJson(
                                    Map.of(
                                        "content",
                                        payload.get("text"),
                                        "nonce",
                                        payload.get("nonce"),
                                        "enforce_nonce",
                                        true)),
                                null);
                    } else if ("STAFF_DM".equals(payload.get("type"))) {
                      sent =
                          service.directMessage(
                              item.get("recipient").toString(), payload.get("body").toString());
                    } else
                      sent =
                          service.directMessage(
                              item.get("recipient").toString(), item.get("payload").toString());
                    pending.add(
                        sent.handle(
                                (message, error) ->
                                    io.run(
                                        () -> {
                                          try {
                                            if (error != null || message == null) {
                                              store.uncertainDelivery(
                                                  id,
                                                  "Discord не подтвердил результат. Сверьте канал или личные сообщения перед повтором");
                                              return;
                                            }
                                            store.delivered(
                                                id,
                                                error == null && message != null,
                                                ((Number) item.get("attempts")).intValue() + 1,
                                                error == null && message != null
                                                    ? null
                                                    : "ЛС недоступны или ошибка Discord");
                                          } catch (java.sql.SQLException e) {
                                            throw new IllegalStateException(e);
                                          }
                                        }))
                            .thenCompose(java.util.function.Function.identity()));
                  } catch (RuntimeException invalid) {
                    store.delivered(
                        id,
                        false,
                        ((Number) item.get("attempts")).intValue() + 1,
                        "Не удалось обработать доставку");
                  }
                }
                // Waiting/escalation now belongs to the durable case workflow, without legacy
                // duplicates.
              } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
              }
              return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
            })
        .thenCompose(java.util.function.Function.identity())
        .whenComplete((v, e) -> delivering.set(false));
  }

  public void notifyCaseEvidence(String kind, int caseId, int evidenceId) {
    try {
      var evidence =
          store.evidence(kind, caseId, true).stream()
              .filter(e -> ((Number) e.get("id")).intValue() == evidenceId)
              .findFirst();
      if (evidence.isEmpty()) return;
      if (kind.equals("report") && store.ticket("report", caseId).isEmpty()) {
        var report = database.getReportById(caseId);
        if (report.isPresent()) {
          var owner =
              discord()
                  .flatMap(
                      service ->
                          service.getLinkedDiscordId(
                              java.util.UUID.fromString(report.get().reporterUuid())));
          if (owner.isPresent()) {
            store.ticketOwner("report", caseId, owner.get());
            createReportTicket(report.get());
          }
        }
      }
      if (evidence.get().get("type").equals("IMAGE"))
        store.enqueue(
            "case-image:" + kind + ":" + evidenceId,
            kind.equals("appeal") ? "ticket:" + caseId : "report-ticket:" + caseId,
            JsonUtils.toJson(
                Map.of(
                    "type",
                    "CASE_IMAGE",
                    "kind",
                    kind,
                    "caseId",
                    caseId,
                    "evidenceId",
                    evidenceId)));
      else {
        String text =
            "Доказательство · " + evidence.get().get("author") + ": " + evidence.get().get("value");
        if (kind.equals("appeal"))
          database
              .getAppealById(caseId)
              .ifPresent(
                  appeal ->
                      sendTicketReply(
                          appeal,
                          evidence.get().get("author").toString(),
                          true,
                          text,
                          "evidence:" + evidenceId));
        else sendReportConversation(caseId, evidence.get().get("author").toString(), text);
      }
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(
          "Доказательство сохранено, но доставка требует проверки", error);
    }
  }

  public void createReportTicket(PlayerReport report) {
    try {
      store.enqueue(
          "report-ticket:" + report.id(),
          "report-ticket:" + report.id(),
          JsonUtils.toJson(Map.of("type", "REPORT_TICKET", "reportId", report.id())));
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  public void sendReportConversation(int id, String actor, String text) {
    try {
      if (store.ticket("report", id).isEmpty()) return;
      String key = "report-reply:" + id + ":" + java.util.UUID.randomUUID();
      int index = 0;
      for (String part : conversationParts(actor + ": " + text)) {
        String event = key + ":" + index++;
        store.enqueue(
            event,
            "report-ticket:" + id,
            JsonUtils.toJson(
                Map.of(
                    "type",
                    "REPORT_MESSAGE",
                    "reportId",
                    id,
                    "text",
                    part,
                    "nonce",
                    me.lovelace
                        .loveWebAdmin
                        .database
                        .ModerationStore
                        .hash(event)
                        .substring(0, 24))));
      }
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  /** Only the reporter's verified Core link is used; the text never contains staff notes. */
  public void requestReportClarification(
      PlayerReport report, int messageId, String actor, String text) {
    sendReportConversation(report.id(), actor, text);
    var service = discord();
    var recipient =
        service.flatMap(
            d -> d.getLinkedDiscordId(java.util.UUID.fromString(report.reporterUuid())));
    if (recipient.isPresent())
      try {
        String event = "clarify:" + messageId + ":" + recipient.get();
        store.enqueue(
            event,
            recipient.get(),
            JsonUtils.toJson(
                Map.of(
                    "content",
                    plugin
                        .discordTemplates()
                        .render(
                            "clarification",
                            Map.of(
                                "id", String.valueOf(report.id()), "actor", actor, "text", text)),
                    "nonce",
                    me.lovelace.loveWebAdmin.database.ModerationStore.hash(event).substring(0, 24),
                    "enforce_nonce",
                    true,
                    "components",
                    List.of(
                        Map.of(
                            "type",
                            1,
                            "components",
                            List.of(
                                Map.of(
                                    "type",
                                    2,
                                    "style",
                                    1,
                                    "label",
                                    "Ответить",
                                    "custom_id",
                                    "lwa:clarify:new:" + report.id())))))));
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException("Вопрос сохранён, но доставка не поставлена в очередь", e);
      }
    main.call(
        () -> {
          var player =
              org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(report.reporterUuid()));
          String message =
              "§eУточнение по вашей жалобе №"
                  + report.id()
                  + ": §f"
                  + text
                  + (recipient.isPresent()
                      ? " §7Ответьте в личном сообщении Discord."
                      : " §7Для ответа свяжитесь с сотрудником " + actor + ".");
          if (player != null && player.isOnline()) player.sendMessage(message);
          else database.queueReportNotification(report.reporterUuid(), message);
          return null;
        });
  }

  public boolean isAvailable() {
    try {
      return LoveCore.service(DiscordService.class).map(DiscordService::isEnabled).orElse(false);
    } catch (Throwable t) {
      return false;
    }
  }

  public Optional<DiscordService> discord() {
    try {
      return LoveCore.service(DiscordService.class);
    } catch (Throwable t) {
      return Optional.empty();
    }
  }

  private void onDiscordTicketMessage(
      String ticketId, String authorName, boolean isStaff, String message) {
    // Legacy callbacks carry a display name only; no ownership or staff authorization can be
    // proven.
    // Authenticated callbacks are registered by the v2 bridge when the upgraded LoveCore is
    // present.
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onReportCreated(PlayerReportCreatedEvent event) {
    notifyReportCreated(event.getReport(), true);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onReportResolved(
      me.lovelace.loveWebAdmin.api.events.PlayerReportResolvedEvent event) {
    // Refresh from persisted state rather than trusting a client-provided status.
    database
        .getReportById(event.getReportId())
        .ifPresent(
            report -> {
              notifyReportCreated(report);
              try {
                if (store.ticket("report", report.id()).isPresent())
                  store.enqueue(
                      "report-finish:" + report.id(),
                      "report-ticket:" + report.id(),
                      JsonUtils.toJson(
                          Map.of(
                              "type",
                              "REPORT_FINISH",
                              "reportId",
                              report.id(),
                              "text",
                              "Жалоба №"
                                  + report.id()
                                  + ": "
                                  + switch (report.status()) {
                                    case "ACCEPTED" -> "Подтверждена";
                                    case "REJECTED" -> "Отклонена";
                                    case "EXPIRED" -> "Истекла";
                                    default -> "Закрыта";
                                  }
                                  + ". Сотрудник: "
                                  + java.util.Objects.toString(report.resolvedBy(), "Система"))));
              } catch (java.sql.SQLException error) {
                throw new IllegalStateException(error);
              }
            });
  }

  public void notifyTicketCreated(String kind, int id) {
    if (kind.equals("appeal")) database.getAppealById(id).ifPresent(this::notifyAppealCreated);
    else database.getReportById(id).ifPresent(report -> notifyReportCreated(report, true));
  }

  public void notifyReportCreated(PlayerReport report) {
    notifyReportCreated(report, false);
  }

  private void notifyReportCreated(PlayerReport report, boolean freshNotification) {
    var currentRecord = database.getReportById(report.id());
    if (currentRecord.isEmpty()) return;
    PlayerReport current = currentRecord.get();
    var opt = discord().filter(DiscordService::isEnabled);
    if (opt.isEmpty()) return;
    var service = opt.get();
    String channel = plugin.getConfig().getString("discord.reports-channel-id", "");
    try {
      var existing = store.card("report", current.id());
      String state =
          switch (current.status()) {
            case "ACCEPTED" -> "Подтверждена";
            case "REJECTED" -> "Отклонена";
            case "EXPIRED" -> "Истекла";
            case "MERGED" -> "Объединена с другим делом";
            default -> "На рассмотрении";
          };
      String assigned = store.work("report", current.id()).get("assignee").toString();
      String content =
          plugin
              .discordTemplates()
              .render(
                  "report",
                  Map.of(
                      "id",
                      String.valueOf(current.id()),
                      "status",
                      state,
                      "player",
                      current.targetName(),
                      "reporter",
                      current.reporterName(),
                      "reason",
                      String.join(", ", current.reasons()),
                      "assignee",
                      assigned.isBlank() ? "Не назначен" : assigned));
      if (freshNotification)
        dispatchAdminDirectMessages(
            "discordNotifyReports",
            "report:" + current.id(),
            false,
            new DiscordEmbed(
                "Новая жалоба №" + current.id(),
                content,
                0xE74C3C,
                List.of(),
                "Модерация",
                Instant.now()));
      if (ticketDesk.enabled()) return;
      if (channel.isBlank()
          || "webhook"
              .equalsIgnoreCase(plugin.getConfig().getString("discord.reports-transport", "auto")))
        return;
      var buttons = new ArrayList<Map<String, Object>>();
      if (current.status().equals("PENDING") && assigned.isBlank())
        buttons.add(
            Map.of(
                "type",
                2,
                "style",
                1,
                "label",
                "Взять в работу",
                "custom_id",
                "lwa:report:claim:" + current.id()));
      String url = plugin.getConfig().getString("web.public-url", "");
      if (!url.isBlank())
        buttons.add(
            Map.of(
                "type",
                2,
                "style",
                5,
                "label",
                "Открыть дело",
                "url",
                url.replaceAll("/+$", "") + "/case.html?kind=report&id=" + current.id()));
      var body = new java.util.LinkedHashMap<String, Object>();
      body.put("content", content);
      body.put(
          "components",
          buttons.isEmpty() ? List.of() : List.of(Map.of("type", 1, "components", buttons)));
      service
          .sendMessage(
              channel, JsonUtils.toJson(body), existing.map(c -> c.get("message")).orElse(null))
          .thenAccept(
              message -> {
                if (message != null)
                  io.run(
                      () -> {
                        try {
                          store.card("report", current.id(), channel, message);
                        } catch (java.sql.SQLException e) {
                          throw new IllegalStateException(e);
                        }
                      });
              });

    } catch (java.sql.SQLException e) {
      plugin.getLogger().warning("Не удалось обновить карточку жалобы");
    }
  }

  public CompletableFuture<String> createAppealTicket(WebBanAppeal appeal) {
    try {
      store.enqueue(
          "ticket:" + appeal.id(),
          "ticket:" + appeal.id(),
          JsonUtils.toJson(Map.of("type", "APPEAL_TICKET", "appealId", appeal.id())));
      return CompletableFuture.completedFuture(null);
    } catch (java.sql.SQLException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private CompletableFuture<String> doCreateAppealTicket(WebBanAppeal appeal) {
    if (ticketDesk.enabled())
      try {
        return ticketDesk.create("appeal", appeal.id(), store.owner(appeal.id()).orElse(null));
      } catch (java.sql.SQLException error) {
        return CompletableFuture.failedFuture(error);
      }
    Optional<DiscordService> serviceOpt = discord();
    if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) {
      return CompletableFuture.completedFuture(null);
    }
    DiscordService service = serviceOpt.get();

    String owner;
    try {
      owner = store.owner(appeal.id()).orElse(null);
    } catch (java.sql.SQLException e) {
      return CompletableFuture.failedFuture(e);
    }
    return service
        .createTicketChannel(
            TicketType.BAN_APPEAL,
            String.valueOf(appeal.id()),
            appeal.playerName(),
            appeal.reason(),
            owner)
        .thenCompose(
            channelId ->
                io.supply(
                    () -> {
                      if (closed) return null;
                      if (channelId != null && !channelId.isBlank()) {
                        if (!database.setAppealDiscordChannelId(appeal.id(), channelId))
                          throw new IllegalStateException("Не сохранён канал апелляции");

                        // Отправляем вступительное сообщение в канал тикета
                        List<DiscordEmbed.Field> fields = new ArrayList<>();
                        fields.add(new DiscordEmbed.Field("Игрок", appeal.playerName(), true));
                        fields.add(new DiscordEmbed.Field("Бан ID", "#" + appeal.banId(), true));
                        fields.add(
                            new DiscordEmbed.Field("Причина апелляции", appeal.reason(), false));

                        DiscordEmbed embed =
                            new DiscordEmbed(
                                "⚖ Апелляция бана #" + appeal.id(),
                                "Канал тикета для обсуждения снятия блокировки игрока **"
                                    + appeal.playerName()
                                    + "**.\n"
                                    + "Все сообщения синхронизируются с панелью управления"
                                    + ".",
                                0xF1C40F, // Gold
                                fields,
                                "Обращения",
                                Instant.now());
                        service.sendChannelMessage(
                            channelId,
                            "Ваша апелляция принята к рассмотрению персоналом сервера.",
                            embed);
                        service
                            .sendMessage(
                                channelId,
                                JsonUtils.toJson(
                                    Map.of(
                                        "content",
                                        "Действия персонала",
                                        "components",
                                        List.of(
                                            Map.of(
                                                "type",
                                                1,
                                                "components",
                                                List.of(
                                                    Map.of(
                                                        "type",
                                                        2,
                                                        "style",
                                                        3,
                                                        "label",
                                                        "Одобрить",
                                                        "custom_id",
                                                        "lwa:decision:approve:" + appeal.id()),
                                                    Map.of(
                                                        "type",
                                                        2,
                                                        "style",
                                                        1,
                                                        "label",
                                                        "Сократить срок",
                                                        "custom_id",
                                                        "lwa:decision:reduce:" + appeal.id()),
                                                    Map.of(
                                                        "type",
                                                        2,
                                                        "style",
                                                        4,
                                                        "label",
                                                        "Отклонить",
                                                        "custom_id",
                                                        "lwa:decision:reject:" + appeal.id())))))),
                                null)
                            .thenAccept(
                                message -> {
                                  if (message != null)
                                    io.run(
                                        () -> {
                                          try {
                                            store.card("appeal", appeal.id(), channelId, message);
                                          } catch (java.sql.SQLException e) {
                                            throw new IllegalStateException(e);
                                          }
                                        });
                                });
                      }
                      // Оповещаем администраторов
                      if (channelId != null && !channelId.isBlank()) notifyAppealCreated(appeal);
                      return channelId;
                    }));
  }

  public void notifyAppealCreated(WebBanAppeal appeal) {
    Optional<DiscordService> serviceOpt = discord();
    if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) return;
    DiscordService service = serviceOpt.get();

    String title = "⚖ Новая апелляция бана #" + appeal.id();
    String desc =
        plugin
            .discordTemplates()
            .render(
                "appeal",
                Map.of(
                    "id",
                    String.valueOf(appeal.id()),
                    "ban",
                    String.valueOf(appeal.banId()),
                    "player",
                    appeal.playerName(),
                    "reason",
                    appeal.reason()));
    List<DiscordEmbed.Field> fields =
        List.of(
            new DiscordEmbed.Field("Игрок", appeal.playerName(), true),
            new DiscordEmbed.Field("Бан ID", "#" + appeal.banId(), true),
            new DiscordEmbed.Field("Аргумент", appeal.reason(), false));

    DiscordEmbed embed =
        new DiscordEmbed(
            title,
            desc,
            0x3498DB, // Blue
            fields,
            "Обращения",
            Instant.now());

    // Канал апелляций если настроен
    String channelId = plugin.getConfig().getString("discord.appeals-channel-id", "");
    if (!ticketDesk.enabled() && channelId != null && !channelId.isBlank()) {
      service.sendChannelMessage(channelId, null, embed);
    }

    // В ЛС администраторам
    dispatchAdminDirectMessages("discordNotifyTickets", "appeal:" + appeal.id(), false, embed);
  }

  public void sendTicketReply(
      WebBanAppeal appeal, String authorName, boolean isStaff, String message) {
    sendTicketReply(appeal, authorName, isStaff, message, java.util.UUID.randomUUID().toString());
  }

  public void sendTicketReply(
      WebBanAppeal appeal, String authorName, boolean isStaff, String message, String messageId) {
    String role = isStaff ? "🛡 [Персонал]" : "👤 [Игрок]";
    enqueueConversation(
        "reply:" + appeal.id() + ":" + messageId,
        appeal,
        "APPEAL_MESSAGE",
        "**" + role + " " + authorName + "**: " + message);
  }

  static List<String> conversationParts(String text) {
    var parts = new ArrayList<String>();
    for (int start = 0; start < text.length(); ) {
      int end = Math.min(text.length(), start + 1900);
      if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
      parts.add(text.substring(start, end));
      start = end;
    }
    if (parts.isEmpty()) parts.add("");
    return List.copyOf(parts);
  }

  private void enqueueConversation(String key, WebBanAppeal appeal, String type, String text) {
    var parts = conversationParts(text);
    var deliveries = new ArrayList<me.lovelace.loveWebAdmin.database.ModerationStore.Delivery>();
    for (int i = 0; i < parts.size(); i++) {
      String partKey = key + ":part:" + i;
      String partType = i == parts.size() - 1 ? type : "APPEAL_MESSAGE";
      deliveries.add(
          new me.lovelace.loveWebAdmin.database.ModerationStore.Delivery(
              partKey,
              "ticket:" + appeal.id(),
              JsonUtils.toJson(
                  Map.of(
                      "type",
                      partType,
                      "appealId",
                      appeal.id(),
                      "text",
                      parts.get(i),
                      "nonce",
                      me.lovelace
                          .loveWebAdmin
                          .database
                          .ModerationStore
                          .hash(partKey)
                          .substring(0, 24)))));
    }
    try {
      store.enqueueBatch(deliveries);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(
          "Переписка сохранена, но доставка в Discord требует проверки", e);
    }
  }

  public CompletableFuture<Boolean> finishAppeal(WebBanAppeal appeal, String reason) {
    enqueueConversation("finish:" + appeal.id(), appeal, "APPEAL_FINISH", reason);
    return CompletableFuture.completedFuture(true);
  }

  private CompletableFuture<Boolean> doFinishAppeal(
      WebBanAppeal appeal, String reason, String nonce) {
    if (ticketDesk.enabled()) return ticketDesk.finish("appeal", appeal.id(), reason);
    var service = discord();
    String channel = appeal.discordChannelId();
    if (service.isEmpty() || channel == null) return CompletableFuture.completedFuture(false);
    Optional<Map<String, String>> card;
    try {
      card = store.card("appeal", appeal.id());
    } catch (java.sql.SQLException e) {
      return CompletableFuture.failedFuture(e);
    }
    return service
        .get()
        .sendMessage(
            channel,
            JsonUtils.toJson(Map.of("content", reason, "nonce", nonce, "enforce_nonce", true)),
            null)
        .thenCompose(
            sent -> {
              if (closed || sent == null) return CompletableFuture.completedFuture(false);
              if (card.isEmpty()) return CompletableFuture.completedFuture(true);
              return service
                  .get()
                  .sendMessage(
                      channel,
                      JsonUtils.toJson(
                          Map.of("content", "Рассмотрение завершено", "components", List.of())),
                      card.get().get("message"))
                  .thenApply(id -> id != null);
            })
        .thenCompose(
            updated ->
                updated && !closed
                    ? service.get().closeTicketChannel(channel, reason)
                    : CompletableFuture.completedFuture(false));
  }

  public void closeAppealTicket(WebBanAppeal appeal, String reason) {
    if (ticketDesk.enabled()) {
      ticketDesk.finish("appeal", appeal.id(), reason);
      return;
    }
    Optional<DiscordService> serviceOpt = discord();
    if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) return;

    String channelId = appeal.discordChannelId();
    if (channelId != null && !channelId.isBlank()) {
      serviceOpt.get().closeTicketChannel(channelId, reason);
    }
  }

  private Optional<String> linkedDiscord(WebAdmin admin, DiscordService service) {
    return database
        .getPlayerStats(admin.username())
        .map(p -> p.get("uuid"))
        .flatMap(
            uuid -> {
              try {
                return service.getLinkedDiscordId(java.util.UUID.fromString(uuid.toString()));
              } catch (IllegalArgumentException error) {
                return Optional.empty();
              }
            });
  }

  private boolean eligibleStaff(
      WebAdmin admin, me.lovelace.loveWebAdmin.models.Permission permission, boolean escalation) {
    if (!"ACTIVE".equals(admin.status())) return false;
    var role = database.getRoleById(admin.roleId());
    if (role.isEmpty() || (!role.get().isOwner() && !role.get().permissions().contains(permission)))
      return false;
    if (escalation
        && !role.get().isOwner()
        && !notificationPolicy.escalationRoles().contains(role.get().name())) return false;
    return true;
  }

  private void dispatchAdminDirectMessages(
      String preference, String event, boolean escalation, DiscordEmbed embed) {
    var service = discord();
    if (service.isEmpty()) return;
    var permission =
        preference.equals("discordNotifyReports")
            ? me.lovelace.loveWebAdmin.models.Permission.VIEW_REPORTS
            : me.lovelace.loveWebAdmin.models.Permission.VIEW_APPEALS;
    for (var admin : database.getAllAdmins()) {
      if (!eligibleStaff(admin, permission, escalation)) continue;
      var prefs =
          JsonUtils.parseObject(admin.uiPreferences() == null ? "{}" : admin.uiPreferences());
      if (!Boolean.TRUE.equals(prefs.get(preference))) continue;
      linkedDiscord(admin, service.get())
          .ifPresent(
              recipient -> {
                try {
                  String key = "staff:" + event + ":" + admin.id();
                  String body =
                      JsonUtils.toJson(
                          Map.of(
                              "content",
                              embed.title() + "\n" + embed.description(),
                              "nonce",
                              me.lovelace
                                  .loveWebAdmin
                                  .database
                                  .ModerationStore
                                  .hash(key)
                                  .substring(0, 24),
                              "enforce_nonce",
                              true));
                  store.enqueue(
                      key,
                      recipient,
                      JsonUtils.toJson(
                          Map.of(
                              "type",
                              "STAFF_DM",
                              "adminId",
                              admin.id(),
                              "permission",
                              permission.name(),
                              "body",
                              body,
                              "preference",
                              preference,
                              "event",
                              event,
                              "escalation",
                              escalation)));
                } catch (java.sql.SQLException e) {
                  throw new IllegalStateException("Уведомление сотруднику не сохранено", e);
                }
              });
    }
  }

}
