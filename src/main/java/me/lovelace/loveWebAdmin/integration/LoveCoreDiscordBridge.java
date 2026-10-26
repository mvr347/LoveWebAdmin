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
  private final DiscordNotificationPolicy notificationPolicy;
  private long lastEscalationScan;

  private boolean initialized = false;
  private dev.lovelace.lovecore.api.discord.TicketMessageListener ticketListener;
  private volatile DiscordService registeredService;
  private DiscordActions actions;
  private org.bukkit.scheduler.BukkitTask maintenanceTask;
  private volatile boolean closed;
  private final java.util.concurrent.atomic.AtomicBoolean delivering =
      new java.util.concurrent.atomic.AtomicBoolean();

  public LoveCoreDiscordBridge(LoveWebAdmin plugin) {
    this.plugin = plugin;
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
                                actions.register();
                                registeredService = service;
                              }
                              for (var appeal :
                                  plugin.getDatabaseManager().getAllAppeals("PENDING", 200, 0))
                                if (appeal.discordChannelId() != null)
                                  service.restoreTicket(
                                      String.valueOf(appeal.id()), appeal.discordChannelId());
                            }
                            deliver(service);
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
      try {
        registeredService
            .getClass()
            .getMethod(
                "unregisterTicketMessageListener",
                dev.lovelace.lovecore.api.discord.TicketMessageListener.class)
            .invoke(registeredService, ticketListener);
      } catch (ReflectiveOperationException e) {
        plugin.getLogger().warning("LoveCore требует обновления для снятия слушателя Discord");
      }
    }
    registeredService = null;
  }

  public synchronized void close() {
    closed = true;
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
                plugin
                    .moderation()
                    .enqueue(
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
    if (!delivering.compareAndSet(false, true)) return;
    plugin
        .io()
        .supply(
            () -> {
              var pending = new ArrayList<CompletableFuture<Void>>();
              try {
                for (var item : plugin.moderation().due()) {
                  if (closed) break;
                  int id = ((Number) item.get("id")).intValue();
                  if (!plugin.moderation().takeDelivery(id)) continue;
                  try {
                    Map<String, Object> payload =
                        JsonUtils.parseObject(item.get("payload").toString());
                    if ("STAFF_DM".equals(payload.get("type"))) {
                      int adminId = ((Number) payload.get("adminId")).intValue();
                      var admin = plugin.getDatabaseManager().getAdminById(adminId);
                      String required = payload.get("permission").toString();
                      if (admin.isEmpty()
                          || !eligibleStaff(
                              admin.get(),
                              me.lovelace.loveWebAdmin.models.Permission.valueOf(required),
                              Boolean.TRUE.equals(payload.get("escalation")))
                          || !linkedDiscord(admin.get(), service)
                              .filter(item.get("recipient").toString()::equals)
                              .isPresent()) {
                        plugin
                            .moderation()
                            .skipDelivery(
                                id, "Смена завершена, привязка изменена или доступ отозван");
                        continue;
                      }
                      var prefs =
                          JsonUtils.parseObject(
                              admin.get().uiPreferences() == null
                                  ? "{}"
                                  : admin.get().uiPreferences());
                      if (!Boolean.TRUE.equals(prefs.get(payload.get("preference").toString()))) {
                        plugin.moderation().skipDelivery(id, "Сотрудник выключил уведомления");
                        continue;
                      }
                      String event = payload.get("event").toString();
                      if (event.startsWith("report:") || event.startsWith("escalate-report:")) {
                        int reportId = Integer.parseInt(event.substring(event.indexOf(':') + 1));
                        boolean stale =
                            plugin
                                .getDatabaseManager()
                                .getReportById(reportId)
                                .filter(r -> r.status().equals("PENDING"))
                                .isEmpty();
                        if (!stale && Boolean.TRUE.equals(payload.get("escalation")))
                          stale =
                              !plugin
                                  .moderation()
                                  .work("report", reportId)
                                  .get("assignee")
                                  .toString()
                                  .isBlank();
                        if (stale) {
                          plugin.moderation().skipDelivery(id, "Жалоба закрыта или уже назначена");
                          continue;
                        }
                      }
                      var next = notificationPolicy.nextDelivery(Instant.now());
                      if (next.isAfter(Instant.now())) {
                        plugin.moderation().deferDelivery(id, next.getEpochSecond());
                        continue;
                      }
                    }
                    CompletableFuture<String> sent;
                    if ("APPEAL_TICKET".equals(payload.get("type"))) {
                      int appealId = ((Number) payload.get("appealId")).intValue();
                      var appeal = plugin.getDatabaseManager().getAppealById(appealId);
                      if (appeal.isEmpty() || !"PENDING".equals(appeal.get().status()))
                        sent = CompletableFuture.completedFuture("closed");
                      else if (appeal.get().discordChannelId() != null)
                        sent = CompletableFuture.completedFuture(appeal.get().discordChannelId());
                      else sent = doCreateAppealTicket(appeal.get());
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
                                    plugin
                                        .io()
                                        .run(
                                            () -> {
                                              try {
                                                plugin
                                                    .moderation()
                                                    .delivered(
                                                        id,
                                                        error == null && message != null,
                                                        ((Number) item.get("attempts")).intValue()
                                                            + 1,
                                                        error == null && message != null
                                                            ? null
                                                            : "ЛС недоступны или ошибка Discord");
                                              } catch (java.sql.SQLException e) {
                                                throw new IllegalStateException(e);
                                              }
                                            }))
                            .thenCompose(java.util.function.Function.identity()));
                  } catch (RuntimeException invalid) {
                    plugin
                        .moderation()
                        .delivered(
                            id,
                            false,
                            ((Number) item.get("attempts")).intValue() + 1,
                            "Не удалось обработать доставку");
                  }
                }
                escalateUnclaimed(service);
              } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
              }
              return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
            })
        .thenCompose(java.util.function.Function.identity())
        .whenComplete((v, e) -> delivering.set(false));
  }

  /** Only the reporter's verified Core link is used; the text never contains staff notes. */
  public void requestReportClarification(
      PlayerReport report, int messageId, String actor, String text) {
    var service = discord();
    var recipient =
        service.flatMap(
            d -> d.getLinkedDiscordId(java.util.UUID.fromString(report.reporterUuid())));
    if (recipient.isPresent())
      try {
        String event = "clarify:" + messageId + ":" + recipient.get();
        plugin
            .moderation()
            .enqueue(
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
                                    "id",
                                    String.valueOf(report.id()),
                                    "actor",
                                    actor,
                                    "text",
                                    text)),
                        "nonce",
                        me.lovelace
                            .loveWebAdmin
                            .database
                            .ModerationStore
                            .hash(event)
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
                                        "Ответить",
                                        "custom_id",
                                        "lwa:clarify:new:" + report.id())))))));
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException("Вопрос сохранён, но доставка не поставлена в очередь", e);
      }
    plugin
        .main()
        .call(
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
              else
                plugin.getDatabaseManager().queueReportNotification(report.reporterUuid(), message);
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
    plugin
        .getDatabaseManager()
        .getReportById(event.getReportId())
        .ifPresent(this::notifyReportCreated);
  }

  public void notifyReportCreated(PlayerReport report) {
    notifyReportCreated(report, false);
  }

  private void notifyReportCreated(PlayerReport report, boolean freshNotification) {
    var currentRecord = plugin.getDatabaseManager().getReportById(report.id());
    if (currentRecord.isEmpty()) return;
    PlayerReport current = currentRecord.get();
    var opt = discord().filter(DiscordService::isEnabled);
    if (opt.isEmpty()) return;
    var service = opt.get();
    String channel = plugin.getConfig().getString("discord.reports-channel-id", "");
    try {
      var existing = plugin.moderation().card("report", current.id());
      String state =
          switch (current.status()) {
            case "ACCEPTED" -> "Подтверждена";
            case "REJECTED" -> "Отклонена";
            case "EXPIRED" -> "Истекла";
            case "MERGED" -> "Объединена с другим делом";
            default -> "На рассмотрении";
          };
      String assigned = plugin.moderation().work("report", current.id()).get("assignee").toString();
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
                "LoveWebAdmin",
                Instant.now()));
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
                  plugin
                      .io()
                      .run(
                          () -> {
                            try {
                              plugin.moderation().card("report", current.id(), channel, message);
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
      String owner = plugin.moderation().owner(appeal.id()).orElse("");
      plugin
          .moderation()
          .enqueue(
              "ticket:" + appeal.id(),
              owner,
              JsonUtils.toJson(Map.of("type", "APPEAL_TICKET", "appealId", appeal.id())));
      return CompletableFuture.completedFuture(null);
    } catch (java.sql.SQLException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private CompletableFuture<String> doCreateAppealTicket(WebBanAppeal appeal) {
    Optional<DiscordService> serviceOpt = discord();
    if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) {
      return CompletableFuture.completedFuture(null);
    }
    DiscordService service = serviceOpt.get();

    String owner;
    try {
      owner = plugin.moderation().owner(appeal.id()).orElse(null);
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
                plugin
                    .io()
                    .supply(
                        () -> {
                          if (channelId != null && !channelId.isBlank()) {
                            if (!plugin
                                .getDatabaseManager()
                                .setAppealDiscordChannelId(appeal.id(), channelId))
                              throw new IllegalStateException("Не сохранён канал апелляции");

                            // Отправляем вступительное сообщение в канал тикета
                            List<DiscordEmbed.Field> fields = new ArrayList<>();
                            fields.add(new DiscordEmbed.Field("Игрок", appeal.playerName(), true));
                            fields.add(
                                new DiscordEmbed.Field("Бан ID", "#" + appeal.banId(), true));
                            fields.add(
                                new DiscordEmbed.Field(
                                    "Причина апелляции", appeal.reason(), false));

                            DiscordEmbed embed =
                                new DiscordEmbed(
                                    "⚖ Апелляция бана #" + appeal.id(),
                                    "Канал тикета для обсуждения снятия блокировки игрока **"
                                        + appeal.playerName()
                                        + "**.\n"
                                        + "Все сообщения синхронизируются с панелью управления"
                                        + " WebAdmin.",
                                    0xF1C40F, // Gold
                                    fields,
                                    "LoveWebAdmin • Тикеты",
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
                                                            "lwa:decision:reject:"
                                                                + appeal.id())))))),
                                    null)
                                .thenAccept(
                                    message -> {
                                      if (message != null)
                                        plugin
                                            .io()
                                            .run(
                                                () -> {
                                                  try {
                                                    plugin
                                                        .moderation()
                                                        .card(
                                                            "appeal",
                                                            appeal.id(),
                                                            channelId,
                                                            message);
                                                  } catch (java.sql.SQLException e) {
                                                    throw new IllegalStateException(e);
                                                  }
                                                });
                                    });
                          }
                          // Оповещаем администраторов
                          if (channelId != null && !channelId.isBlank())
                            notifyAppealCreated(appeal);
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
            "LoveWebAdmin • Апелляции",
            Instant.now());

    // Канал апелляций если настроен
    String channelId = plugin.getConfig().getString("discord.appeals-channel-id", "");
    if (channelId != null && !channelId.isBlank()) {
      service.sendChannelMessage(channelId, null, embed);
    }

    // В ЛС администраторам
    dispatchAdminDirectMessages("discordNotifyTickets", "appeal:" + appeal.id(), false, embed);
  }

  public void sendTicketReply(
      WebBanAppeal appeal, String authorName, boolean isStaff, String message) {
    Optional<DiscordService> serviceOpt = discord();
    if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) return;

    String channelId = appeal.discordChannelId();
    if (channelId != null && !channelId.isBlank()) {
      String roleTag = isStaff ? "🛡 [Персонал]" : "👤 [Игрок]";
      String formatted = "**" + roleTag + " " + authorName + "**: " + message;
      serviceOpt.get().sendChannelMessage(channelId, formatted, null);
    }
  }

  public CompletableFuture<Boolean> finishAppeal(WebBanAppeal appeal, String reason) {
    var service = discord();
    String channel = appeal.discordChannelId();
    if (service.isEmpty() || channel == null) return CompletableFuture.completedFuture(false);
    Optional<Map<String, String>> card;
    try {
      card = plugin.moderation().card("appeal", appeal.id());
    } catch (java.sql.SQLException e) {
      return CompletableFuture.failedFuture(e);
    }
    return service
        .get()
        .sendChannelMessage(channel, reason, null)
        .thenCompose(
            sent -> {
              if (!sent) return CompletableFuture.completedFuture(false);
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
                updated
                    ? service.get().closeTicketChannel(channel, reason)
                    : CompletableFuture.completedFuture(false));
  }

  public void closeAppealTicket(WebBanAppeal appeal, String reason) {
    Optional<DiscordService> serviceOpt = discord();
    if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) return;

    String channelId = appeal.discordChannelId();
    if (channelId != null && !channelId.isBlank()) {
      serviceOpt.get().closeTicketChannel(channelId, reason);
    }
  }

  private Optional<String> linkedDiscord(WebAdmin admin, DiscordService service) {
    return plugin
        .getDatabaseManager()
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
    var role = plugin.getDatabaseManager().getRoleById(admin.roleId());
    if (role.isEmpty() || (!role.get().isOwner() && !role.get().permissions().contains(permission)))
      return false;
    if (escalation
        && !role.get().isOwner()
        && !notificationPolicy.escalationRoles().contains(role.get().name())) return false;
    return !notificationPolicy.onlyOnShift()
        || plugin.getDatabaseManager().getStaffOnShift().stream()
            .anyMatch(a -> ((Number) a.get("id")).intValue() == admin.id());
  }

  private void dispatchAdminDirectMessages(
      String preference, String event, boolean escalation, DiscordEmbed embed) {
    var service = discord();
    if (service.isEmpty()) return;
    var permission =
        preference.equals("discordNotifyReports")
            ? me.lovelace.loveWebAdmin.models.Permission.VIEW_REPORTS
            : me.lovelace.loveWebAdmin.models.Permission.VIEW_APPEALS;
    for (var admin : plugin.getDatabaseManager().getAllAdmins()) {
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
                  plugin
                      .moderation()
                      .enqueue(
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

  private void escalateUnclaimed(DiscordService service) throws java.sql.SQLException {
    long now = System.currentTimeMillis() / 1000;
    if (notificationPolicy.escalationMinutes() == 0 || now - lastEscalationScan < 60) return;
    lastEscalationScan = now;
    for (var report : plugin.getDatabaseManager().getAllReports("PENDING", null, 200, 0)) {
      if (now - report.createdAt() < notificationPolicy.escalationMinutes() * 60L
          || !plugin.moderation().work("report", report.id()).get("assignee").toString().isBlank())
        continue;
      dispatchAdminDirectMessages(
          "discordNotifyReports",
          "escalate-report:" + report.id(),
          true,
          new DiscordEmbed(
              "Жалоба №" + report.id() + " ожидает сотрудника",
              "Игрок: "
                  + report.targetName()
                  + ". Жалоба не назначена более "
                  + notificationPolicy.escalationMinutes()
                  + " мин. Откройте дело в панели.",
              0xE74C3C,
              List.of(),
              "LoveWebAdmin",
              Instant.now()));
    }
  }
}
