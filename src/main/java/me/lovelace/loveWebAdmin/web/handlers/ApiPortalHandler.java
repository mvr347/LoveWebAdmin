package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;

/** Player-only capability authentication. A nickname never grants ownership. */
public final class ApiPortalHandler extends ApiHandlerSupport {
  private final me.lovelace.loveWebAdmin.utils.SubmissionLimiter limiter =
      new me.lovelace.loveWebAdmin.utils.SubmissionLimiter();

  public ApiPortalHandler(LoveWebAdmin plugin) {
    super(plugin);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    handle(req, resp, false);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    handle(req, resp, true);
  }

  private void handle(HttpServletRequest req, HttpServletResponse resp, boolean mutation)
      throws IOException {
    resp.setHeader("Cache-Control", "no-store");
    resp.setHeader("Referrer-Policy", "no-referrer");
    String path = Objects.toString(req.getPathInfo(), "/cases");
    try {
      if (path.equals("/exchange") && mutation) {
        if (!limiter.take(req.getRemoteAddr())) {
          sendError(resp, 429, "Слишком много попыток");
          return;
        }
        var value = plugin.moderation().exchangePortal(stringOrNull(readJsonBody(req).get("code")));
        if (value.isEmpty()) {
          sendError(resp, 401, "Ссылка использована или истекла. Запросите новую у бота");
          return;
        }
        sendSuccess(resp, value.get());
        return;
      }
      String token = req.getHeader("X-Player-Session");
      var session = plugin.moderation().portalSession(token);
      if (session.isEmpty()) {
        sendError(resp, 401, "Откройте одноразовую ссылку из команды /мои-обращения в Discord");
        return;
      }
      String discord = session.get().get("discordId"), storedUuid = session.get().get("playerUuid");
      String uuid =
          plugin
              .getDiscordBridge()
              .discord()
              .flatMap(service -> service.getLinkedPlayer(discord))
              .map(java.util.UUID::toString)
              .filter(storedUuid::equals)
              .orElse("");
      if (path.equals("/logout") && mutation) {
        plugin.moderation().portalLogout(token);
        sendSuccess(resp, Map.of());
        return;
      }
      if (path.equals("/cases") && !mutation) {
        var result = new ArrayList<Map<String, Object>>();
        for (var appeal : plugin.getDatabaseManager().getAllAppeals(null, 200, 0))
          if (owns("appeal", appeal.id(), discord, uuid))
            result.add(
                Map.of(
                    "kind",
                    "appeal",
                    "id",
                    appeal.id(),
                    "player",
                    appeal.playerName(),
                    "status",
                    appeal.status(),
                    "reason",
                    appeal.reason(),
                    "verified",
                    plugin.moderation().verified(appeal.id())));
        for (var report : plugin.getDatabaseManager().getAllReports(null, null, 200, 0))
          if (owns("report", report.id(), discord, uuid))
            result.add(
                Map.of(
                    "kind",
                    "report",
                    "id",
                    report.id(),
                    "player",
                    report.targetName(),
                    "status",
                    report.status(),
                    "reason",
                    Objects.toString(report.description(), ""),
                    "verified",
                    !uuid.isBlank()));
        sendSuccess(resp, result);
        return;
      }
      String[] parts = path.split("/");
      if (parts.length < 3 || !Set.of("appeal", "report").contains(parts[1])) {
        sendError(resp, 404, "Обращение не найдено");
        return;
      }
      String kind = parts[1];
      int id = Integer.parseInt(parts[2]);
      if (!owns(kind, id, discord, uuid)) {
        sendError(resp, 404, "Обращение не найдено");
        return;
      }
      if (!mutation) {
        if (parts.length == 5 && parts[3].equals("evidence")) {
          var image = plugin.moderation().evidenceBytes(kind, id, Integer.parseInt(parts[4]), true);
          if (image.isEmpty()) {
            sendError(resp, 404, "Доказательство не найдено");
            return;
          }
          sendSuccess(
              resp,
              Map.of(
                  "mime",
                  me.lovelace.loveWebAdmin.managers.CaseEvidence.imageMime(image.get()),
                  "base64",
                  Base64.getEncoder().encodeToString(image.get())));
          return;
        }
        sendSuccess(
            resp,
            Map.of(
                "messages",
                kind.equals("appeal")
                    ? plugin.getDatabaseManager().getAppealMessages(id)
                    : plugin.getDatabaseManager().reportDiscussion(id),
                "evidence",
                plugin.moderation().evidence(kind, id, true),
                "closure",
                plugin
                    .getDiscordBridge()
                    .ticketDeskStore()
                    .get(kind, id)
                    .filter(t -> !t.state().equals("OPEN"))
                    .map(t -> Map.of("actor", t.actor(), "reason", t.reason()))
                    .orElse(Map.of())));
        return;
      }
      if (!limiter.take(discord)) {
        sendError(resp, 429, "Слишком много сообщений");
        return;
      }
      var body = readJsonBody(req);
      String text = Objects.toString(body.get("text"), "").trim();
      if (text.length() < 1 || text.length() > 2000) {
        sendError(resp, 400, "Сообщение: от 1 до 2000 символов");
        return;
      }
      String author = "Игрок Discord";
      if (kind.equals("appeal")) {
        var appeal = plugin.getDatabaseManager().getAppealById(id).orElseThrow();
        int message =
            plugin.getDatabaseManager().addOpenAppealMessage(id, appeal.playerName(), false, text);
        if (message < 1) {
          sendError(resp, 409, "Обращение закрыто");
          return;
        }
        plugin.getDiscordBridge().sendTicketReply(appeal, appeal.playerName(), false, text);
      } else {
        var report = plugin.getDatabaseManager().getReportById(id).orElseThrow();
        if (!report.status().equals("PENDING")) {
          sendError(resp, 409, "Обращение закрыто");
          return;
        }
        plugin
            .getDatabaseManager()
            .addReportDiscussion(id, report.reporterName(), false, text, null);
        plugin.getDiscordBridge().sendReportConversation(id, report.reporterName(), text);
      }
      sendSuccess(resp, Map.of("message", "Ответ сохранён"));
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private boolean owns(String kind, int id, String discord, String uuid)
      throws java.sql.SQLException {
    if (kind.equals("appeal")) {
      if (plugin.moderation().owner(id).filter(discord::equals).isPresent()) return true;
      return !uuid.isBlank()
          && plugin
              .getDatabaseManager()
              .getAppealById(id)
              .filter(a -> uuid.equals(a.playerUuid()))
              .isPresent();
    }
    if (plugin
        .moderation()
        .ticket(kind, id)
        .filter(t -> discord.equals(t.get("discordId")))
        .isPresent()) return true;
    return !uuid.isBlank()
        && plugin
            .getDatabaseManager()
            .getReportById(id)
            .filter(r -> uuid.equals(r.reporterUuid()))
            .isPresent();
  }
}
