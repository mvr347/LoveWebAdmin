package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;

/**
 * Support requests for the staff panel. Read-only: assignment, notes, replies and closing go
 * through {@code /api/cases/support/{id}/...}, like every other kind of case.
 *
 * <pre>
 * GET /api/support?status=open|closed|all&offset=N   list, newest first, 20 per page
 * GET /api/support/{id}                              one request with its text
 * </pre>
 */
public final class ApiSupportHandler extends ApiHandlerSupport {
  static final int PAGE = 20;

  public ApiSupportHandler(LoveWebAdmin plugin) {
    super(plugin);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (requirePermission(req, resp, Permission.VIEW_REPORTS).isEmpty()) return;
    String path = Objects.toString(req.getPathInfo(), "");
    try {
      if (path.isEmpty() || path.equals("/")) {
        String status = status(req.getParameter("status"));
        int offset = Math.clamp(parseIntOrDefault(req.getParameter("offset"), 0), 0, 1_000_000);
        var items = new ArrayList<Map<String, Object>>();
        for (var row : plugin.moderation().supportList(status, offset, PAGE))
          items.add(view(row, false));
        sendSuccess(
            resp,
            Map.of(
                "items", items,
                "total", plugin.moderation().supportCount(status),
                "open", plugin.moderation().supportCount("OPEN"),
                "pageSize", PAGE,
                "offset", offset));
        return;
      }
      if (!path.matches("/[0-9]{1,9}")) {
        sendError(resp, 404, "Обращение не найдено");
        return;
      }
      var row = plugin.moderation().support(Integer.parseInt(path.substring(1)));
      if (row.isEmpty()) {
        sendError(resp, 404, "Обращение не найдено");
        return;
      }
      sendSuccess(resp, view(row.get(), true));
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String status(String raw) {
    String value = Objects.toString(raw, "all").toUpperCase(Locale.ROOT);
    return value.equals("OPEN") || value.equals("CLOSED") ? value : "ALL";
  }

  /** The list leaves the full text out; the card needs it. */
  private Map<String, Object> view(Map<String, Object> row, boolean full)
      throws java.sql.SQLException {
    int id = ((Number) row.get("id")).intValue();
    var out = new LinkedHashMap<String, Object>();
    out.put("id", id);
    out.put("nick", row.get("nick"));
    out.put("discordId", row.get("discordId"));
    out.put("topic", row.get("topic"));
    out.put("topicLabel", plugin.getDiscordBridge().ticketDesk().supportTopicLabel(row.get("topic").toString()));
    out.put("status", row.get("status"));
    out.put("createdAt", row.get("createdAt"));
    var ticket = plugin.getDiscordBridge().ticketDeskStore().get("support", id);
    out.put("deskState", ticket.map(t -> t.state()).orElse("NONE"));
    out.put("hasChannel", ticket.map(t -> !t.channel().isBlank()).orElse(false));
    out.put("assignee", plugin.moderation().work("support", id).get("assignee"));
    if (full) out.put("body", row.get("body"));
    return out;
  }
}
