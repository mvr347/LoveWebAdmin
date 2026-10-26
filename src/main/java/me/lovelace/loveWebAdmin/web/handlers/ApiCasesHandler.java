package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;

/** Staff-only workspace. Internal notes are never included in public appeal responses. */
public final class ApiCasesHandler extends ApiHandlerSupport {
  public ApiCasesHandler(LoveWebAdmin plugin) {
    super(plugin);
  }

  private String[] path(HttpServletRequest req) {
    String[] p = Objects.toString(req.getPathInfo(), "").split("/");
    if (p.length < 3 || !Set.of("report", "appeal").contains(p[1]))
      throw new IllegalArgumentException("Укажите тип и номер дела");
    return p;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if ("/deliveries".equals(req.getPathInfo())) {
      if (requirePermission(req, resp, Permission.MANAGE_API).isEmpty()) return;
      try {
        sendSuccess(resp, plugin.moderation().deliveries());
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
      return;
    }
    String[] p = path(req);
    int id = Integer.parseInt(p[2]);
    var auth =
        requirePermission(
            req, resp, p[1].equals("report") ? Permission.VIEW_REPORTS : Permission.VIEW_APPEALS);
    if (auth.isEmpty()) return;
    boolean exists =
        p[1].equals("report")
            ? plugin.getDatabaseManager().getReportById(id).isPresent()
            : plugin.getDatabaseManager().getAppealById(id).isPresent();
    if (!exists) {
      sendError(resp, 404, "Дело не найдено");
      return;
    }
    try {
      sendSuccess(
          resp,
          Map.of(
              "work",
              plugin.moderation().work(p[1], id),
              "notes",
              plugin.moderation().notes(p[1], id)));
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String[] p = path(req);
    int id = Integer.parseInt(p[2]);
    if (p.length != 4) throw new IllegalArgumentException("Укажите действие");
    var auth =
        requirePermission(
            req,
            resp,
            p[1].equals("report") ? Permission.MANAGE_REPORTS : Permission.MANAGE_APPEALS);
    if (auth.isEmpty()) return;
    String actor = auth.get().adminUsername();
    boolean open =
        p[1].equals("report")
            ? plugin
                .getDatabaseManager()
                .getReportById(id)
                .filter(r -> r.status().equals("PENDING"))
                .isPresent()
            : plugin
                .getDatabaseManager()
                .getAppealById(id)
                .filter(a -> a.status().equals("PENDING"))
                .isPresent();
    if (!open) {
      sendError(resp, 409, "Дело закрыто или не найдено");
      return;
    }
    var body = readJsonBody(req);
    boolean ok;
    try {
      switch (p[3]) {
        case "claim" -> ok = plugin.moderation().claim(p[1], id, actor);
        case "note" -> {
          plugin.moderation().note(p[1], id, actor, stringOrNull(body.get("text")));
          ok = true;
        }
        case "work" -> {
          String assignee = Objects.toString(body.get("assignee"), actor);
          if (!assignee.equals(actor)
              && plugin
                  .getDatabaseManager()
                  .getAdminByUsername(assignee)
                  .filter(a -> a.status().equals("ACTIVE"))
                  .isEmpty()) {
            sendError(resp, 400, "Сотрудник не найден");
            return;
          }
          ok =
              plugin
                  .moderation()
                  .updateWork(
                      p[1],
                      id,
                      actor,
                      assignee,
                      Objects.toString(body.get("priority"), "NORMAL"),
                      Objects.toString(body.get("state"), "IN_PROGRESS"));
        }
        default -> throw new IllegalArgumentException("Неизвестное действие");
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    if (!ok) {
      sendError(resp, 409, "Дело уже занято другим сотрудником");
      return;
    }
    plugin.getLogManager().logWebAction(actor, "Дело " + p[1] + " №" + id + ": " + p[3]);
    sendSuccess(resp, Map.of("message", "Изменения сохранены"));
  }
}
