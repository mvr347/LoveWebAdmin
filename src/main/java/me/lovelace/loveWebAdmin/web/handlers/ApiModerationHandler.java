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
    var session = requirePermission(req, resp, Permission.VIEW_BANS);
    if (session.isEmpty()) return;
    var m = plugin.getModerationService();
    var pending =
        m.pending().stream()
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
                        hasPermission(
                            session.get(),
                            r.kind().equals("MUTE")
                                ? Permission.MANAGE_MUTES
                                : Permission.FREEZE_PLAYERS))
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
          case "ban", "unban" -> Permission.MANAGE_BANS;
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
        case "unmute" -> m.remove("MUTE", actor, name, reason);
        case "freeze" -> m.freeze(actor, name, reason);
        case "unfreeze" -> m.remove("FREEZE", actor, name, reason);
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
}
