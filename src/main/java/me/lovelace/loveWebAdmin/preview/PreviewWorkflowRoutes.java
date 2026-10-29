package me.lovelace.loveWebAdmin.preview;

import java.util.*;
import static me.lovelace.loveWebAdmin.preview.PreviewRuntime.*;

/** Same durable workflow engine, with synthetic native actions and isolated SQLite. */
final class PreviewWorkflowRoutes {
  static Object route(
      PreviewRuntime r, boolean read, String kind, int id, String action, Map<String, Object> body)
      throws Exception {
    var engine = r.workflows;
    String owner = "sandbox:" + integer(r.state.get("viewer"));
    if (read) return engine.view(kind, id, owner);
    if (action.equals("decision-preview")) {
      String verdict = text(body, "action", "");
      if (kind.equals("appeal") && !verdict.equals("reject")) r.require("MANAGE_BANS");
      return engine.preview(
          kind,
          id,
          owner,
          r.actor(),
          verdict,
          text(body, "reason", ""),
          ((Number) body.getOrDefault("remainingSeconds", 0)).longValue());
    }
    if (action.startsWith("decision-")) {
      String key = text(body, "key", "");
      var draft = engine.store().prepared(key, owner, r.now());
      if (!draft.payload().get("kind").equals(kind) || integer(draft.payload().get("id")) != id)
        throw new Rejected(400, "Предпросмотр другого дела");
      if (action.equals("decision-cancel")) {
        engine.store().cancelDecision(key, owner);
        return Map.of();
      }
      if (kind.equals("appeal") && !draft.payload().get("action").equals("reject"))
        r.require("MANAGE_BANS");
      return Map.of("message", engine.confirm(key, owner, r.actor()));
    }
    if (action.startsWith("reply-template")) {
      boolean shared = Boolean.TRUE.equals(body.get("shared"));
      if (shared) r.require("MANAGE_INTEGRATIONS");
      String actor = shared ? "*" : owner;
      int template = integer(body.getOrDefault("id", 0));
      long version = ((Number) body.getOrDefault("revision", 0)).longValue();
      if (action.endsWith("delete")) {
        if (!engine.store().removeTemplate(actor, template, version))
          throw new Rejected(409, "Шаблон изменён");
      } else
        engine
            .store()
            .template(
                actor,
                template,
                version,
                text(body, "purpose", "CLARIFY"),
                text(body, "name", ""),
                text(body, "text", ""));
      return Map.of();
    }
    return engine.review(
        kind,
        id,
        integer(body.get("requestId")),
        r.actor(),
        action.equals("review-accept"),
        text(body, "reason", ""));
  }
}
