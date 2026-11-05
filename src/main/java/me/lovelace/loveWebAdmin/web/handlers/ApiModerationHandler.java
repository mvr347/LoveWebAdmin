package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.moderation.ModerationService;

public final class ApiModerationHandler extends ApiHandlerSupport {
  public ApiModerationHandler(LoveWebAdmin plugin) {
    super(plugin);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    var session = requireSession(req, resp);
    if (session.isEmpty()) return;
    var rights = currentPermissions(session.get());
    if (java.util.Collections.disjoint(
        rights,
        Set.of(
            Permission.VIEW_BANS,
            Permission.MANAGE_BANS,
            Permission.MANAGE_MUTES,
            Permission.FREEZE_PLAYERS,
            Permission.KICK_PLAYERS))) {
      sendError(resp, 403, "Нет права просмотра ограничений");
      return;
    }
    if ("/history".equals(req.getPathInfo())) {
      var kinds = new HashSet<String>();
      if (rights.contains(Permission.MANAGE_MUTES)) kinds.add("MUTE");
      if (rights.contains(Permission.FREEZE_PLAYERS)) kinds.add("FREEZE");
      String query = Objects.toString(req.getParameter("search"), "").trim();
      if (query.length() > 100) {
        sendError(resp, 400, "Слишком длинный поиск");
        return;
      }
      sendSuccess(
          resp,
          plugin
              .getModerationService()
              .history(
                  kinds,
                  query,
                  Math.clamp(parseIntOrDefault(req.getParameter("offset"), 0), 0, 1000000)));
      return;
    }
    var m = plugin.getModerationService();
    var pending =
        m.pending().stream()
            .filter(
                p ->
                    rights.contains(Permission.VIEW_BANS)
                        || rights.contains(Permission.MANAGE_BANS))
            .map(
                p ->
                    Map.of(
                        "id",
                        p.id(),
                        "name",
                        p.name(),
                        "actor",
                        p.actor(),
                        "createdAt",
                        p.created(),
                        "deadline",
                        p.deadline()))
            .toList();
    sendSuccess(
        resp,
        Map.of(
            "pending",
            pending,
            "restrictions",
            m.restrictions().stream()
                .filter(r -> r.active(System.currentTimeMillis() / 1000))
                .filter(
                    r ->
                        rights.contains(
                            r.kind().equals("MUTE")
                                ? Permission.MANAGE_MUTES
                                : Permission.FREEZE_PLAYERS))
                .map(me.lovelace.loveWebAdmin.moderation.RestrictionsStore.Restriction::view)
                .toList(),
            "serverTime",
            System.currentTimeMillis() / 1000));
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    var body = readJsonBody(req);
    String action = Objects.toString(body.get("action"), "");
    Permission permission =
        switch (action) {
          case "ban", "unban", "cancel-ban" -> Permission.MANAGE_BANS;
          case "mute", "unmute" -> Permission.MANAGE_MUTES;
          case "freeze", "unfreeze" -> Permission.FREEZE_PLAYERS;
          case "kick" -> Permission.KICK_PLAYERS;
          default -> null;
        };
    if (permission == null) {
      sendError(resp, 400, "Неизвестное действие");
      return;
    }
    var session = requirePermission(req, resp, permission);
    if (session.isEmpty()) return;
    String name = Objects.toString(body.get("player"), "");
    String reason = Objects.toString(body.get("reason"), "");
    String actor = session.get().adminUsername();
    try {
      var m = plugin.getModerationService();
      String detail = "Действие выполнено";
      switch (action) {
        case "cancel-ban" -> {
          if (reason.trim().length() < 5 || reason.length() > 1000)
            throw new IllegalArgumentException("Объясните отмену: от 5 до 1000 символов");
          if (!(body.get("id") instanceof Number id)
              || id.doubleValue() != id.intValue()
              || id.intValue() < 1)
            throw new IllegalArgumentException("Укажите номер предварительного бана");
          if (!m.cancelPending(id.intValue(), actor)) {
            sendError(resp, 409, "Оформление уже завершено или отменено");
            return;
          }
          plugin
              .getLogManager()
              .logWebAction(
                  actor, "Причина отмены предварительного бана №" + id.intValue() + ": " + reason);
        }
        case "ban" -> {
          var ban = m.ban(actor, name);
          sendSuccess(
              resp,
              Map.of("id", ban.id(), "deadline", m.pending(ban.id()).orElseThrow().deadline()));
          return;
        }
        case "mute" ->
            m.mute(
                actor,
                name,
                ModerationService.duration(Objects.toString(body.get("duration"), "")),
                reason);
        case "unmute" -> remove(m, "MUTE", actor, name, reason, body);
        case "freeze" -> m.freeze(actor, name, reason);
        case "unfreeze" -> remove(m, "FREEZE", actor, name, reason, body);
        case "kick" -> m.kick(actor, name, reason);
        case "unban" -> {
          if (!plugin.getBanManager().unban(name, actor))
            throw new IllegalArgumentException("Бан не найден");
        }
      }
      sendSuccess(resp, Map.of("message", detail));
    } catch (IllegalArgumentException e) {
      sendError(resp, 400, e.getMessage());
    } catch (RuntimeException e) {
      sendError(resp, 409, "Действие не завершено; обновите состояние перед повтором");
    }
  }

  private static void remove(
      ModerationService service,
      String kind,
      String actor,
      String name,
      String reason,
      Map<String, Object> body) {
    if (reason.trim().length() < 5 || reason.length() > 1000)
      throw new IllegalArgumentException("Объясните снятие: от 5 до 1000 символов");
    boolean changed;
    if (body.get("expectedRevision") instanceof String revision
        && body.get("expectedUuid") instanceof String uuid)
      changed = service.removeExpected(kind, actor, name, reason, uuid, revision);
    else changed = service.remove(kind, actor, name, reason);
    if (!changed) throw new IllegalArgumentException("Ограничение уже снято. Обновите список.");
  }
}
