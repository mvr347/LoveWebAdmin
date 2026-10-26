package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.*;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/** All authority is derived from the authenticated Discord user, never a submitted nickname. */
public final class DiscordActions implements DiscordInteractionListener, TicketMessageListenerV2 {
  private final LoveWebAdmin plugin;
  private final DiscordService service;
  private final me.lovelace.loveWebAdmin.utils.SubmissionLimiter submissions =
      new me.lovelace.loveWebAdmin.utils.SubmissionLimiter();

  public DiscordActions(LoveWebAdmin plugin, DiscordService service) {
    this.plugin = plugin;
    this.service = service;
  }

  public void register() {
    service.registerInteractionListener(this);
    service.registerTicketMessageListenerV2(this);
    service.registerCommand("апелляция", "Обжаловать блокировку Minecraft", Map.of());
    service.registerCommand("дело", "Открыть дело", Map.of("номер", "Номер апелляции"));
    service.registerCommand("жалобы", "Мои жалобы в работе", Map.of());
    service.registerCommand("смена", "Включить или выключить смену", Map.of());
    service.registerCommand("игрок", "Сводка игрока для персонала", Map.of("ник", "Ник игрока"));
  }

  public void close() {
    service.unregisterInteractionListener(this);
    service.unregisterTicketMessageListenerV2(this);
  }

  private Optional<WebAdmin> staff(String user, Permission perm) {
    return service
        .getLinkedPlayer(user)
        .flatMap(uuid -> plugin.getDatabaseManager().getPlayerStats(uuid.toString()))
        .flatMap(
            stats -> plugin.getDatabaseManager().getAdminByUsername(stats.get("name").toString()))
        .filter(a -> a.status().equals("ACTIVE"))
        .filter(
            a ->
                plugin
                    .getDatabaseManager()
                    .getRoleById(a.roleId())
                    .map(r -> r.isOwner() || r.permissions().contains(perm))
                    .orElse(false));
  }

  public Optional<WebAdmin> staffMember(String user, Permission perm) {
    return staff(user, perm);
  }

  private java.util.concurrent.CompletableFuture<Boolean> response(
      DiscordInteraction i, int type, Map<String, Object> data) {
    return service.respond(i, JsonUtils.toJson(Map.of("type", type, "data", data)));
  }

  @Override
  public boolean onInteraction(DiscordInteraction i) {
    if (!i.name().startsWith("lwa:")
        && !Set.of("апелляция", "дело", "жалобы", "смена", "игрок").contains(i.name()))
      return false;
    if (i.name().equals("апелляция") || i.name().startsWith("lwa:appeal:new:")) {
      String ban =
          i.name().startsWith("lwa:") ? i.name().substring("lwa:appeal:new:".length()) : "0";
      var fields = new ArrayList<Map<String, Object>>();
      fields.add(input("nick", "Ник Minecraft (если аккаунт не привязан)", false, 1));
      fields.add(input("reason", "Почему нужно пересмотреть блокировку?", true, 2));
      response(
          i,
          9,
          Map.of(
              "custom_id",
              "lwa:appeal:submit:" + ban,
              "title",
              "Апелляция блокировки",
              "components",
              fields));
      return true;
    }
    if (i.name().startsWith("lwa:decision:")) {
      response(
          i,
          9,
          Map.of(
              "custom_id",
              i.name().replace("lwa:decision:", "lwa:verdict:"),
              "title",
              "Решение по апелляции",
              "components",
              List.of(input("reason", "Обоснование решения", true, 2))));
      return true;
    }
    response(i, 5, Map.of("flags", 64))
        .thenAccept(
            accepted -> {
              if (!accepted) return;
              plugin
                  .io()
                  .run(
                      () -> {
                        String result;
                        try {
                          result = execute(i);
                        } catch (Exception e) {
                          plugin
                              .getLogger()
                              .warning(
                                  "Discord: действие не выполнено: "
                                      + e.getClass().getSimpleName());
                          result =
                              "Не удалось выполнить действие. Проверьте состояние дела в панели.";
                        }
                        service.editResponse(i, result);
                      });
            });
    return true;
  }

  private Map<String, Object> input(String id, String label, boolean required, int style) {
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
                style,
                "required",
                required,
                "max_length",
                style == 1 ? 16 : 2000)));
  }

  private String execute(DiscordInteraction i) throws Exception {
    if (i.name().startsWith("lwa:appeal:submit:")) return createAppeal(i);
    Permission permission =
        i.name().startsWith("lwa:report:") || i.name().equals("жалобы")
            ? Permission.MANAGE_REPORTS
            : i.name().equals("игрок") ? Permission.VIEW_PLAYERS : Permission.MANAGE_APPEALS;
    var auth = staff(i.userId(), permission);
    if (auth.isEmpty()) return "Недостаточно прав. Нужен привязанный аккаунт сотрудника WebAdmin.";
    WebAdmin admin = auth.get();
    if (i.name().startsWith("lwa:report:claim:")) {
      int id = Integer.parseInt(i.name().substring("lwa:report:claim:".length()));
      if (plugin
          .getDatabaseManager()
          .getReportById(id)
          .filter(r -> r.status().equals("PENDING"))
          .isEmpty()) return "Жалоба закрыта или не найдена.";
      boolean ok = plugin.moderation().claim("report", id, admin.username());
      if (ok)
        plugin
            .getLogManager()
            .logWebAction(admin.username(), "Взял жалобу №" + id + " через Discord");
      return ok ? "Жалоба №" + id + " назначена вам." : "Жалоба уже занята.";
    }
    if (i.name().startsWith("lwa:verdict:")) {
      String[] parts = i.name().split(":");
      int id = Integer.parseInt(parts[3]);
      String reason = i.values().get("reason");
      return plugin.appeals().decide(id, admin.username(), parts[2].equals("approve"), reason);
    }
    if (i.name().equals("смена")) {
      boolean on =
          plugin.getDatabaseManager().getStaffOnShift().stream()
              .anyMatch(a -> ((Number) a.get("id")).intValue() == admin.id());
      plugin.getDatabaseManager().setAdminShiftStatus(admin.id(), !on);
      return on ? "Смена завершена." : "Смена начата.";
    }
    if (i.name().equals("жалобы")) {
      var list = plugin.getDatabaseManager().getAllReports("PENDING", null, 200, 0);
      var text = new StringBuilder("Ваши жалобы в работе:\n");
      for (var report : list)
        if (plugin
            .moderation()
            .work("report", report.id())
            .get("assignee")
            .equals(admin.username()))
          text.append("№")
              .append(report.id())
              .append(" — ")
              .append(report.targetName())
              .append('\n');
      return text.toString();
    }
    if (i.name().equals("игрок")) {
      String nick = i.values().get("ник");
      if (nick == null) return "Укажите ник.";
      var p = plugin.getDatabaseManager().getPlayerStats(nick);
      if (p.isEmpty()) return "Игрок не найден.";
      return "Игрок: "
          + p.get().get("name")
          + "\nНаиграно: "
          + p.get().get("playtimeSeconds")
          + " сек.\n"
          + url()
          + "/#player="
          + java.net.URLEncoder.encode(nick, java.nio.charset.StandardCharsets.UTF_8);
    }
    return "Открыть дело: " + url() + "/#appeal=" + i.values().getOrDefault("номер", "");
  }

  private String createAppeal(DiscordInteraction i) throws Exception {
    if (!submissions.take(i.userId())) return "Слишком много попыток. Повторите через 5 минут.";
    var linked = service.getLinkedPlayer(i.userId());
    String nick;
    if (linked.isPresent()) {
      var record = plugin.getDatabaseManager().getPlayerStats(linked.get().toString());
      if (record.isEmpty()) return "Для привязанного аккаунта нет данных Minecraft.";
      nick = record.get().get("name").toString();
    } else nick = i.values().getOrDefault("nick", "").trim();
    if (!nick.matches("[A-Za-z0-9_]{1,16}")) return "Введите корректный ник Minecraft.";
    String reason = i.values().getOrDefault("reason", "").trim();
    if (reason.length() < 5) return "Опишите причину подробнее (минимум 5 символов).";
    var banned = plugin.getDatabaseManager().getActiveBan(nick);
    if (banned.isEmpty()) return "Активная блокировка не найдена.";
    var ban = banned.get();
    var existing = plugin.getDatabaseManager().getAppealByBanId(ban.id());
    if (existing.filter(a -> Set.of("PENDING", "DECIDING").contains(a.status())).isPresent()) {
      var appeal = existing.get();
      if (!plugin.moderation().owner(appeal.id()).filter(i.userId()::equals).isPresent())
        return "По блокировке уже есть обращение. Обратитесь к персоналу.";
      return appeal.discordChannelId() == null
          ? "Апелляция уже открыта; тикет создаётся."
          : "Ваш тикет: <#" + appeal.discordChannelId() + ">";
    }
    int id =
        plugin
            .getDatabaseManager()
            .createAppeal(ban.id(), ban.targetUuid(), ban.targetName(), reason);
    if (id <= 0) return "Апелляция уже существует или не сохранена.";
    String token = plugin.moderation().createAccess(id, i.userId(), linked.isPresent());
    plugin.getDatabaseManager().addAppealMessage(id, ban.targetName(), false, reason);
    var appeal = plugin.getDatabaseManager().getAppealById(id).orElseThrow();
    plugin.getDiscordBridge().createAppealTicket(appeal);
    return "Апелляция №"
        + id
        + " создана. "
        + (linked.isPresent()
            ? "Аккаунт подтверждён."
            : "Аккаунт не подтверждён; сотрудник может запросить проверку.")
        + (url().isBlank()
            ? ""
            : "\nПереписка в панели: " + url() + "/appeal.html#" + id + ":" + token);
  }

  private String url() {
    return plugin.getConfig().getString("web.public-url", "").replaceAll("/+$", "");
  }

  @Override
  public void onTicketMessage(TicketMessage msg) {
    var found = plugin.getDatabaseManager().getAppealByDiscordChannel(msg.channelId());
    if (found.isEmpty() || !found.get().status().equals("PENDING")) return;
    var appeal = found.get();
    try {
      boolean author =
          plugin.moderation().owner(appeal.id()).filter(msg.authorId()::equals).isPresent();
      var admin = staff(msg.authorId(), Permission.MANAGE_APPEALS);
      if (!author && admin.isEmpty()) return;
      StringBuilder content = new StringBuilder(msg.content());
      for (var attachment : msg.attachments())
        content.append("\n").append(attachment.name()).append(": ").append(attachment.url());
      if (content.isEmpty()) return;
      plugin
          .getDatabaseManager()
          .addDiscordAppealMessage(
              appeal.id(),
              msg.messageId(),
              admin.map(WebAdmin::username).orElse(appeal.playerName()),
              admin.isPresent(),
              content.toString());
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
