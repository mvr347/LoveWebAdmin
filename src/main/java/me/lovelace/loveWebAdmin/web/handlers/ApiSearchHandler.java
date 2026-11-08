package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;

/** Bounded, permission-aware search. Private case contents never cross a view permission. */
public final class ApiSearchHandler extends ApiHandlerSupport {
  public ApiSearchHandler(LoveWebAdmin plugin) {
    super(plugin);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    var session = requireSession(req, resp);
    if (session.isEmpty()) return;
    String query = Objects.toString(req.getParameter("q"), "").trim();
    if (query.length() > 128) {
      sendError(resp, 400, "Слишком длинный запрос");
      return;
    }
    if (query.isBlank()) {
      sendSuccess(resp, Map.of("items", List.of()));
      return;
    }
    var result = new ArrayList<Map<String, Object>>();
    var db = plugin.getDatabaseManager();
    try {
      if (hasPermission(session.get(), Permission.VIEW_PLAYERS))
        for (var player : db.searchPlayers(query, 6))
          result.add(
              item("player", 0, Objects.toString(player.get("name"), "Игрок"), "Досье игрока"));
      if (hasPermission(session.get(), Permission.VIEW_BANS))
        for (var ban : db.searchBans("ALL", query, "", 0, Long.MAX_VALUE, 0, 5).items())
          result.add(item("ban", ban.id(), ban.targetName(), ban.ruleReason()));
      if (hasPermission(session.get(), Permission.VIEW_REPORTS)) {
        for (var report : db.getAllReports(null, query, 5, 0))
          result.add(item("report", report.id(), report.targetName(), report.description()));
        for (var support : plugin.moderation().searchSupport(query))
          result.add(
              item(
                  "support",
                  ((Number) support.get("id")).intValue(),
                  Objects.toString(support.get("nick")),
                  Objects.toString(support.get("body"))));
      }
      if (hasPermission(session.get(), Permission.VIEW_APPEALS))
        for (var appeal : db.searchAppeals(query, 5))
          result.add(item("appeal", appeal.id(), appeal.playerName(), appeal.reason()));
      sendSuccess(resp, Map.of("items", result));
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Map<String, Object> item(String kind, int id, String name, String summary) {
    return Map.of(
        "kind",
        kind,
        "id",
        id,
        "name",
        Objects.toString(name, "Игрок"),
        "summary",
        Objects.toString(summary, ""));
  }
}
