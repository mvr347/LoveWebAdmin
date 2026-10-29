package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.*;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.*;

/** Called only after case existence and domain permissions were checked by ApiCasesHandler. */
final class CaseWorkflowRoutes extends ApiHandlerSupport {
  CaseWorkflowRoutes(LoveWebAdmin plugin) {
    super(plugin);
  }

  boolean get(
      HttpServletRequest req, HttpServletResponse resp, String[] path, int id, WebSession actor)
      throws Exception {
    if (path.length == 4 && path[3].equals("workflow")) {
      sendSuccess(resp, plugin.workflows().view(path[1], id, operationActor(actor)));
      return true;
    }
    if (path.length == 5 && path[3].equals("review-image")) {
      var bytes = plugin.workflows().store().reviewImage(path[1], id, Integer.parseInt(path[4]));
      if (bytes.isEmpty()) sendError(resp, 404, "Скриншот не найден");
      else
        sendSuccess(
            resp,
            Map.of(
                "mime",
                me.lovelace.loveWebAdmin.managers.CaseEvidence.imageMime(bytes.get()),
                "base64",
                Base64.getEncoder().encodeToString(bytes.get())));
      return true;
    }
    return false;
  }

  boolean post(
      HttpServletRequest req, HttpServletResponse resp, String[] path, int id, WebSession actor)
      throws Exception {
    String action = path[3];
    if (!Set.of(
            "decision-preview",
            "decision-confirm",
            "decision-cancel",
            "reply-template",
            "reply-template-delete",
            "review-accept",
            "review-reject")
        .contains(action)) return false;
    var engine = plugin.workflows();
    var body = readJsonBody(req);
    String who = actor.adminUsername(), owner = operationActor(actor);
    if (action.equals("decision-preview")) {
      String verdict = Objects.toString(body.get("action"), "");
      if (path[1].equals("appeal")
          && !verdict.equals("reject")
          && requirePermission(req, resp, Permission.MANAGE_BANS).isEmpty()) return true;
      sendSuccess(
          resp,
          engine.preview(
              path[1],
              id,
              owner,
              who,
              verdict,
              Objects.toString(body.get("reason"), ""),
              ((Number) body.getOrDefault("remainingSeconds", 0)).longValue()));
      return true;
    }
    if (action.startsWith("decision-")) {
      String key = Objects.toString(body.get("key"), "");
      var prepared = engine.store().prepared(key, owner, System.currentTimeMillis() / 1000);
      if (!prepared.payload().get("kind").equals(path[1])
          || ((Number) prepared.payload().get("id")).intValue() != id)
        throw new IllegalArgumentException("Предпросмотр относится к другому делу");
      if (action.equals("decision-cancel")) {
        engine.store().cancelDecision(key, owner);
        sendSuccess(resp, Map.of());
        return true;
      }
      if (path[1].equals("appeal")
          && !prepared.payload().get("action").equals("reject")
          && requirePermission(req, resp, Permission.MANAGE_BANS).isEmpty()) return true;
      sendSuccess(resp, Map.of("message", engine.confirm(key, owner, who)));
      return true;
    }
    if (action.startsWith("reply-template")) {
      boolean shared = Boolean.TRUE.equals(body.get("shared"));
      if (shared && requirePermission(req, resp, Permission.MANAGE_INTEGRATIONS).isEmpty())
        return true;
      String scope = shared ? "*" : owner;
      int template = ((Number) body.getOrDefault("id", 0)).intValue();
      long version = ((Number) body.getOrDefault("revision", 0)).longValue();
      if (action.endsWith("delete")) {
        if (!engine.store().removeTemplate(scope, template, version))
          throw new IllegalArgumentException("Шаблон изменён или недоступен");
      } else
        engine
            .store()
            .template(
                scope,
                template,
                version,
                Objects.toString(body.get("purpose"), "CLARIFY"),
                Objects.toString(body.get("name"), ""),
                Objects.toString(body.get("text"), ""));
      plugin
          .getLogManager()
          .logWebAction(who, "Изменил " + (shared ? "общий" : "личный") + " шаблон ответа");
      sendSuccess(resp, Map.of());
      return true;
    }
    var result =
        engine.review(
            path[1],
            id,
            ((Number) body.get("requestId")).intValue(),
            who,
            action.equals("review-accept"),
            Objects.toString(body.get("reason"), ""));
    plugin.getLogManager().logWebAction(who, "Рассмотрел новые доказательства по делу №" + id);
    sendSuccess(resp, result);
    return true;
  }
}
