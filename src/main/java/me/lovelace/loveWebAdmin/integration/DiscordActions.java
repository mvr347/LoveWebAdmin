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
  private volatile boolean closed;
  private final me.lovelace.loveWebAdmin.utils.SubmissionLimiter submissions =
      new me.lovelace.loveWebAdmin.utils.SubmissionLimiter();

  public DiscordActions(LoveWebAdmin plugin, DiscordService service) {
    this.plugin = plugin;
    this.service = service;
  }

  public void register() {
    CoreDiscordExtensions.claimPrefix(service, "lwa:");
    service.registerInteractionListener(this);
    service.registerTicketMessageListenerV2(this);
    service.registerCommand("жалоба", "Подать жалобу на игрока", Map.of());
    service.registerCommand("мои-обращения", "Личная ссылка на ваши обращения", Map.of());
    service.registerCommand("апелляция", "Обжаловать блокировку Minecraft", Map.of());
    service.registerCommand("дело", "Открыть дело", Map.of("номер", "Номер апелляции"));
    CoreDiscordExtensions.unregister(service, "жалобы");
    CoreDiscordExtensions.unregister(service, "смена");
    service.registerCommand("игрок", "Сводка игрока для персонала", Map.of("ник", "Ник игрока"));
  }

  public void close() {
    closed = true;
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
                    .map(r -> r.isOwner() || perm == null || r.permissions().contains(perm))
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
    if (closed) return false;
    if (plugin.getDiscordBridge().ticketDesk().onInteraction(i)) return true;
    if (!i.name().startsWith("lwa:")
        && !Set.of("апелляция", "жалоба", "мои-обращения", "дело", "игрок").contains(i.name()))
      return false;
    if (i.name().equals("жалоба")) {
      response(
          i,
          9,
          Map.of(
              "custom_id",
              "lwa:report:submit",
              "title",
              "Жалоба на игрока",
              "components",
              List.of(
                  input("target", "Ник игрока-нарушителя", true, 1),
                  input("nick", "Ваш ник, если аккаунт не привязан", false, 1),
                  input("reason", "Что произошло?", true, 2))));
      return true;
    }
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
    if (i.name().startsWith("lwa:clarify:new:")) {
      try {
        int id = Integer.parseInt(i.name().substring("lwa:clarify:new:".length()));
        if (!reportAuthor(id, i.userId())) {
          response(
              i, 4, Map.of("flags", 64, "content", "Ответить может только автор этого обращения"));
          return true;
        }
      } catch (Exception error) {
        response(i, 4, Map.of("flags", 64, "content", "Обращение недоступно"));
        return true;
      }
    }
    if (i.name().startsWith("lwa:clarify:new:")) {
      response(
          i,
          9,
          Map.of(
              "custom_id",
              i.name().replace("lwa:clarify:new:", "lwa:clarify:submit:"),
              "title",
              "Ответ по жалобе",
              "components",
              List.of(input("text", "Уточнение для сотрудника", true, 2))));
      return true;
    }
    if (i.name().startsWith("lwa:decision:")
        && (staff(i.userId(), Permission.MANAGE_APPEALS).isEmpty()
            || (i.name().contains(":reduce:") || i.name().contains(":approve:"))
                && staff(i.userId(), Permission.MANAGE_BANS).isEmpty())) {
      response(i, 4, Map.of("flags", 64, "content", "Недостаточно прав для решения по апелляции"));
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
              i.name().contains(":reduce:")
                  ? List.of(
                      input("reason", "Обоснование решения", true, 2),
                      input("minutes", "Оставшийся срок в минутах", true, 1))
                  : List.of(input("reason", "Обоснование решения", true, 2))));
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
                          result = executeOnce(i);
                        } catch (me.lovelace.loveWebAdmin.utils.ActionRejectedException e) {
                          result = e.getMessage();
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

  private boolean reportAuthor(int id, String user) throws java.sql.SQLException {
    var report = plugin.getDatabaseManager().getReportById(id);
    if (report.isEmpty() || !report.get().status().equals("PENDING")) return false;
    return service
            .getLinkedPlayer(user)
            .map(UUID::toString)
            .filter(report.get().reporterUuid()::equals)
            .isPresent()
        || plugin
            .moderation()
            .ticket("report", id)
            .filter(ticket -> user.equals(ticket.get("discordId")))
            .isPresent();
  }

  private String executeOnce(DiscordInteraction interaction) throws Exception {
    if (interaction.name().equals("мои-обращения")) return execute(interaction);
    String actor = "discord:" + interaction.userId(), key = interaction.id();
    String fingerprint =
        me.lovelace.loveWebAdmin.web.MutationRequests.fingerprint(
            "DISCORD",
            interaction.name(),
            JsonUtils.toJson(interaction.values())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (!plugin.operations().allow(actor)) return "Слишком много действий. Повторите через минуту.";
    var previous = plugin.operations().reserve(actor, key, interaction.name(), fingerprint);
    if (previous != null)
      return previous.state().equals("COMPLETE")
          ? "Действие уже обработано. Обновите состояние обращения в панели."
          : "Результат действия требует проверки; повторно оно не выполнено.";
    try {
      String result = execute(interaction);
      plugin.operations().complete(actor, key, 200, "Обработано", null);
      return result;
    } catch (Exception error) {
      plugin.operations().uncertain(actor, key);
      throw error;
    }
  }

  private String execute(DiscordInteraction i) throws Exception {
    if (closed) throw new java.util.concurrent.CancellationException("Интеграция остановлена");
    if (i.name().equals("мои-обращения")) {
      if (!submissions.take(i.userId())) return "Слишком много запросов. Повторите позже.";
      String link =
          plugin
              .moderation()
              .portalLink(
                  i.userId(), service.getLinkedPlayer(i.userId()).map(UUID::toString).orElse(null));
      return url().isBlank()
          ? "Для личного кабинета администратор должен настроить web.public-url."
          : "Личная одноразовая ссылка, действует 5 минут: "
              + url()
              + "/portal.html#"
              + link
              + "\nНе передавайте её другим людям.";
    }
    if (i.name().equals("lwa:report:submit")) return createReport(i);
    if (i.name().startsWith("lwa:appeal:submit:")) return createAppeal(i);
    if (i.name().startsWith("lwa:clarify:submit:")) {
      if (!submissions.take(i.userId())) return "Слишком много попыток. Повторите позже.";
      int id = Integer.parseInt(i.name().substring("lwa:clarify:submit:".length()));
      var report = plugin.getDatabaseManager().getReportById(id).orElseThrow();
      var author = service.getLinkedPlayer(i.userId());
      if (!reportAuthor(id, i.userId()))
        return "Ответить может только автор жалобы с подтверждённой привязкой Minecraft.";
      int message =
          plugin
              .getDatabaseManager()
              .addReportDiscussion(
                  id, report.reporterName(), false, i.values().get("text"), i.id());
      return message > 0
          ? "Ответ сохранён. Сотрудник увидит его в деле №" + id + "."
          : "Ответ уже был сохранён.";
    }
    Permission permission =
        i.name().equals("дело")
            ? Permission.VIEW_APPEALS
            : i.name().startsWith("lwa:report:") || i.name().equals("жалобы")
                ? Permission.MANAGE_REPORTS
                : i.name().equals("игрок") ? Permission.VIEW_PLAYERS : Permission.MANAGE_APPEALS;
    var auth = staff(i.userId(), permission);
    if (auth.isEmpty()) return "Недостаточно прав. Нужен привязанный аккаунт сотрудника панели.";
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
            .getDatabaseManager()
            .getReportById(id)
            .ifPresent(plugin.getDiscordBridge()::notifyReportCreated);
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
      if (parts[2].equals("reduce")) {
        if (staff(i.userId(), Permission.MANAGE_BANS).isEmpty())
          return "Нужно право управления блокировками.";
        long minutes = Long.parseLong(i.values().getOrDefault("minutes", "0"));
        return plugin
            .appeals()
            .reduce(id, admin.username(), Math.multiplyExact(minutes, 60), reason);
      }
      if (parts[2].equals("approve") && staff(i.userId(), Permission.MANAGE_BANS).isEmpty())
        return "Нужно право управления наказаниями.";
      return plugin.appeals().decide(id, admin.username(), parts[2].equals("approve"), reason);
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

  private String createReport(DiscordInteraction interaction) throws Exception {
    if (!submissions.take(interaction.userId())) return "Слишком много обращений. Повторите позже.";
    String target = interaction.values().getOrDefault("target", "").trim(),
        reason = interaction.values().getOrDefault("reason", "").trim();
    if (!target.matches("[A-Za-z0-9_]{1,16}") || reason.length() < 5 || reason.length() > 2000)
      return "Укажите корректный ник нарушителя и описание от 5 до 2000 символов.";
    var identity = service.getLinkedPlayer(interaction.userId());
    String reporter =
        identity
            .flatMap(uuid -> plugin.getDatabaseManager().getPlayerStats(uuid.toString()))
            .map(record -> record.get("name").toString())
            .orElse(interaction.values().getOrDefault("nick", "Игрок Discord"));
    var targetRecord = plugin.getDatabaseManager().getPlayerStats(target);
    if (targetRecord.isEmpty()) return "Игрок не найден в истории сервера.";
    String uuid =
        identity
            .map(UUID::toString)
            .orElseGet(
                () ->
                    UUID.nameUUIDFromBytes(
                            ("discord:" + interaction.userId())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString());
    int id =
        plugin
            .getDatabaseManager()
            .createPlayerReport(
                uuid,
                reporter,
                "Discord",
                targetRecord.get().get("uuid").toString(),
                target,
                List.of("Обращение в Discord"),
                reason,
                false,
                0);
    if (id < 1) return "Жалоба не сохранена. Проверьте ранее созданные обращения.";
    plugin.moderation().ticketOwner("report", id, interaction.userId());
    plugin
        .getDatabaseManager()
        .getReportById(id)
        .ifPresent(
            report -> {
              plugin.getDiscordBridge().notifyReportCreated(report);
              plugin.getDiscordBridge().createReportTicket(report);
            });
    return "Жалоба №"
        + id
        + " сохранена. Приватный тикет создаётся. "
        + (identity.isPresent()
            ? "Аккаунт Minecraft подтверждён."
            : "Ник пока не подтверждён; сотрудник может запросить проверку.");
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
    if (closed) return;
    try {
      var ticket =
          plugin.moderation().tickets().stream()
              .filter(
                  t -> t.get("kind").equals("report") && t.get("channel").equals(msg.channelId()))
              .findFirst();
      if (ticket.isPresent()) {
        int id = ((Number) ticket.get().get("id")).intValue();
        var report = plugin.getDatabaseManager().getReportById(id);
        if (report.isEmpty() || !report.get().status().equals("PENDING")) return;
        boolean owner = ticket.get().get("discordId").equals(msg.authorId());
        var admin = staff(msg.authorId(), Permission.VIEW_REPORTS);
        if (!owner && admin.isEmpty()) return;
        if (!plugin.getDiscordBridge().ticketDesk().permitsMessage("report", id, msg.authorId()))
          return;
        String content = conversationText(msg);
        if (content.isBlank()) return;
        plugin
            .getDatabaseManager()
            .addReportDiscussion(
                id,
                owner
                    ? report.get().reporterName()
                    : admin.map(WebAdmin::username).orElse(report.get().reporterName()),
                !owner && admin.isPresent(),
                content,
                msg.messageId());
        return;
      }
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    var found = plugin.getDatabaseManager().getAppealByDiscordChannel(msg.channelId());
    if (found.isEmpty() || !found.get().status().equals("PENDING")) return;
    var appeal = found.get();
    try {
      boolean author =
          plugin.moderation().owner(appeal.id()).filter(msg.authorId()::equals).isPresent();
      var admin = staff(msg.authorId(), Permission.VIEW_APPEALS);
      if (!author && admin.isEmpty()) return;
      if (!plugin
          .getDiscordBridge()
          .ticketDesk()
          .permitsMessage("appeal", appeal.id(), msg.authorId())) return;
      String content = conversationText(msg);
      if (content.isBlank()) return;
      plugin
          .getDatabaseManager()
          .addDiscordAppealMessage(
              appeal.id(),
              msg.messageId(),
              author
                  ? appeal.playerName()
                  : admin.map(WebAdmin::username).orElse(appeal.playerName()),
              !author && admin.isPresent(),
              content);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String conversationText(TicketMessage message) {
    StringBuilder content = new StringBuilder(message.content() == null ? "" : message.content());
    for (var attachment : message.attachments().stream().limit(5).toList()) {
      try {
        var uri = java.net.URI.create(attachment.url());
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null) continue;
        content.append("\n").append(attachment.name()).append(": ").append(uri);
      } catch (IllegalArgumentException ignored) {
      }
    }
    return content.length() > 4000 ? content.substring(0, 4000) : content.toString();
  }
}
