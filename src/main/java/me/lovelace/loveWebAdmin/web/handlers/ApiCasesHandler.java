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
    if ("/discord-messages".equals(req.getPathInfo())) {
      if (requirePermission(req, resp, Permission.MANAGE_INTEGRATIONS).isEmpty()) return;
      try {
        sendSuccess(resp, plugin.getDiscordBridge().ticketDeskStore().messages());
      } catch (SQLException error) {
        throw new IllegalStateException(error);
      }
      return;
    }
    if ("/templates".equals(req.getPathInfo())) {
      if (requirePermission(req, resp, Permission.MANAGE_API).isEmpty()) return;
      sendSuccess(
          resp,
          Map.of(
              "templates",
              plugin.discordTemplates().templates(),
              "tokens",
              plugin.discordTemplates().tokens()));
      return;
    }
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
      if (new CaseWorkflowRoutes(plugin).get(req, resp, p, id, auth.get())) return;
      if (p.length >= 4 && p[3].equals("transcript")) {
        resp.setHeader("Cache-Control", "no-store");
        var archive = plugin.getDiscordBridge().ticketDeskStore();
        if (p.length == 5) {
          var bytes = archive.file(p[1], id, Integer.parseInt(p[4]));
          if (bytes.isEmpty()) {
            sendError(resp, 404, "Вложение не найдено");
            return;
          }
          resp.setContentType("application/octet-stream");
          resp.setHeader("Content-Disposition", "attachment; filename=\"attachment-" + p[4] + "\"");
          resp.setHeader("Cache-Control", "no-store");
          resp.setHeader("X-Content-Type-Options", "nosniff");
          resp.getOutputStream().write(bytes.get());
          return;
        }
        int offset =
            Math.max(
                0,
                Math.min(
                    10000, Integer.parseInt(Objects.toString(req.getParameter("offset"), "0"))));
        var messages = archive.transcript(p[1], id, offset);
        sendSuccess(
            resp,
            Map.of(
                "messages",
                messages,
                "files",
                archive.files(p[1], id),
                "nextOffset",
                messages.size() == 100 ? offset + 100 : -1,
                "state",
                archive.get(p[1], id).map(t -> t.state()).orElse("NONE"),
                "closedBy",
                archive.get(p[1], id).map(t -> t.actor()).orElse(""),
                "closeReason",
                archive.get(p[1], id).map(t -> t.reason()).orElse("")));
        return;
      }
      if (p.length == 5 && p[3].equals("evidence")) {
        int evidenceId = Integer.parseInt(p[4]);
        var bytes = plugin.moderation().evidenceBytes(p[1], id, evidenceId, false);
        if (bytes.isEmpty()) {
          sendError(resp, 404, "Доказательство не найдено");
          return;
        }
        resp.setHeader("Cache-Control", "no-store");
        sendSuccess(
            resp,
            Map.of(
                "mime",
                me.lovelace.loveWebAdmin.managers.CaseEvidence.imageMime(bytes.get()),
                "base64",
                Base64.getEncoder().encodeToString(bytes.get())));
        return;
      }
      sendSuccess(
          resp,
          Map.of(
              "revision",
              plugin.moderation().revision(p[1], id),
              "evidence",
              plugin.moderation().evidence(p[1], id, false),
              "work",
              plugin.moderation().work(p[1], id),
              "notes",
              plugin.moderation().notes(p[1], id),
              "related",
              p[1].equals("report") ? plugin.getDatabaseManager().relatedReports(id) : List.of(),
              "discussion",
              p[1].equals("report")
                  ? plugin.getDatabaseManager().reportDiscussion(id)
                  : List.of()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if ("/discord-messages/resolve".equals(req.getPathInfo())) {
      var auth = requirePermission(req, resp, Permission.MANAGE_INTEGRATIONS);
      if (auth.isEmpty()) return;
      var body = readJsonBody(req);
      String slot = Objects.toString(body.get("slot"), ""),
          message = Objects.toString(body.get("messageId"), ""),
          explanation = Objects.toString(body.get("explanation"), "");
      try {
        if (!plugin
            .getDiscordBridge()
            .ticketDeskStore()
            .resolveMessage(slot, message, explanation)) {
          sendError(resp, 409, "Сообщение уже сверено или ещё отправляется");
          return;
        }
      } catch (SQLException error) {
        throw new IllegalStateException(error);
      }
      plugin.getDiscordBridge().ticketDesk().refreshMessage(slot);
      plugin
          .getLogManager()
          .logWebAction(
              auth.get().adminUsername(), "Сверил сообщение Discord " + slot + ": " + explanation);
      sendSuccess(
          resp, Map.of("message", "Результат проверки сохранён; сообщение обновится в Discord"));
      return;
    }
    if (Set.of("/templates/preview", "/templates/save")
        .contains(Objects.toString(req.getPathInfo(), ""))) {
      var auth = requirePermission(req, resp, Permission.MANAGE_API);
      if (auth.isEmpty()) return;
      var body = readJsonBody(req);
      String kind = Objects.toString(body.get("kind"), ""),
          text = Objects.toString(body.get("text"), "");
      String preview =
          me.lovelace.loveWebAdmin.integration.DiscordTemplates.preview(
              kind,
              text,
              Map.ofEntries(
                  Map.entry("id", "12"),
                  Map.entry("ban", "42"),
                  Map.entry("player", "Ada123"),
                  Map.entry("reporter", "Игрок"),
                  Map.entry("reason", "Пример причины"),
                  Map.entry("source", "Сотрудник: Moderator"),
                  Map.entry("duration", "1 час"),
                  Map.entry("expiry", "27.10.2026 12:00"),
                  Map.entry("status", "На рассмотрении"),
                  Map.entry("assignee", "Moderator"),
                  Map.entry("actor", "Moderator"),
                  Map.entry("text", "Расскажите подробнее")));
      if (req.getPathInfo().endsWith("/save")) {
        plugin.discordTemplates().save(kind, text);
        plugin
            .getLogManager()
            .logWebAction(auth.get().adminUsername(), "Изменил шаблон Discord: " + kind);
      }
      sendSuccess(resp, Map.of("preview", preview));
      return;
    }
    if (Objects.toString(req.getPathInfo(), "").matches("/deliveries/[0-9]+/resolve")) {
      var auth = requirePermission(req, resp, Permission.MANAGE_API);
      if (auth.isEmpty()) return;
      int id = Integer.parseInt(req.getPathInfo().split("/")[2]);
      var body = readJsonBody(req);
      String explanation = Objects.toString(body.get("explanation"), "");
      try {
        if (!plugin
            .moderation()
            .resolveDelivery(
                id,
                Boolean.TRUE.equals(body.get("delivered")),
                auth.get().adminUsername(),
                explanation)) {
          sendError(resp, 409, "Результат этой доставки уже установлен");
          return;
        }
      } catch (SQLException error) {
        throw new IllegalStateException(error);
      }
      plugin
          .getLogManager()
          .logWebAction(
              auth.get().adminUsername(), "Сверил доставку Discord №" + id + ": " + explanation);
      sendSuccess(resp, Map.of("message", "Результат доставки сохранён"));
      return;
    }
    if (Objects.toString(req.getPathInfo(), "").matches("/deliveries/[0-9]+/retry")) {
      var auth = requirePermission(req, resp, Permission.MANAGE_API);
      if (auth.isEmpty()) return;
      int id = Integer.parseInt(req.getPathInfo().split("/")[2]);
      try {
        if (!plugin.moderation().retryDelivery(id)) {
          sendError(resp, 409, "Повторить можно только неудачную доставку");
          return;
        }
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
      plugin
          .getLogManager()
          .logWebAction(auth.get().adminUsername(), "Повторил доставку Discord №" + id);
      sendSuccess(resp, Map.of("message", "Доставка поставлена в очередь"));
      return;
    }

    String[] p = path(req);
    int id = Integer.parseInt(p[2]);
    if (p.length != 4) throw new IllegalArgumentException("Укажите действие");
    var auth =
        requirePermission(
            req,
            resp,
            p[1].equals("report") ? Permission.MANAGE_REPORTS : Permission.MANAGE_APPEALS);
    if (auth.isEmpty()) return;
    try {
      if (new CaseWorkflowRoutes(plugin).post(req, resp, p, id, auth.get())) return;
    } catch (me.lovelace.loveWebAdmin.utils.ActionRejectedException error) {
      throw error;
    } catch (IllegalArgumentException error) {
      sendError(resp, 400, error.getMessage());
      return;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
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
      if (body.get("revision") instanceof Number revision
          && !plugin.moderation().reserveRevision(p[1], id, revision.longValue())) {
        sendError(
            resp,
            409,
            "Дело изменилось другим сотрудником. Черновик сохранён; обновите данные перед применением.");
        return;
      }
      switch (p[3]) {
        case "evidence" -> {
          int evidenceId =
              me.lovelace.loveWebAdmin.managers.CaseEvidence.add(
                  plugin.moderation(), p[1], id, actor, body);
          if ("AUTHOR".equals(body.get("visibility")))
            plugin.getDiscordBridge().notifyCaseEvidence(p[1], id, evidenceId);
          ok = true;
        }

        case "claim" -> ok = plugin.moderation().claim(p[1], id, actor);
        case "merge" -> {
          if (!p[1].equals("report"))
            throw new IllegalArgumentException("Объединяются только жалобы");
          int target =
              Integer.parseInt(
                  Objects.toString(body.get("targetId"), "").replaceFirst("\\.0$", ""));
          ok = plugin.getDatabaseManager().mergeReports(id, target, actor);
          plugin
              .getDatabaseManager()
              .getReportById(target)
              .ifPresent(plugin.getDiscordBridge()::notifyReportCreated);
        }
        case "clarify" -> {
          if (!p[1].equals("report"))
            throw new IllegalArgumentException(
                "Уточнения по апелляциям отправляются в их переписку");
          var report = plugin.getDatabaseManager().getReportById(id).orElseThrow();
          String text = stringOrNull(body.get("text"));
          int messageId =
              plugin.getDatabaseManager().addReportDiscussion(id, actor, true, text, null);
          plugin.getDiscordBridge().requestReportClarification(report, messageId, actor, text);
          ok = true;
        }
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
    if (p[1].equals("report"))
      plugin
          .getDatabaseManager()
          .getReportById(id)
          .ifPresent(plugin.getDiscordBridge()::notifyReportCreated);
    plugin.getLogManager().logWebAction(actor, "Дело " + p[1] + " №" + id + ": " + p[3]);
    sendSuccess(resp, Map.of("message", "Изменения сохранены"));
  }
}
