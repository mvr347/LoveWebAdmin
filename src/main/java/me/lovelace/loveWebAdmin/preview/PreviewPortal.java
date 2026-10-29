package me.lovelace.loveWebAdmin.preview;

import java.util.*;
import static me.lovelace.loveWebAdmin.preview.PreviewRuntime.*;

/** Same one-use capability store as production; all cases and files remain in the sandbox. */
final class PreviewPortal {
  static Object route(
      PreviewRuntime r,
      String method,
      String[] p,
      Map<String, Object> body,
      Map<String, String> headers)
      throws Exception {
    String verb = p.length > 1 ? p[1] : "cases";
    boolean read = method.equals("GET");
    if (verb.equals("exchange") && !read)
      return r.moderation()
          .exchangePortal(text(body, "code", ""))
          .orElseThrow(() -> new Rejected(401, "Ссылка использована или истекла"));
    String token = headers.get("x-player-session");
    var session =
        r.moderation()
            .portalSession(token)
            .orElseThrow(() -> new Rejected(401, "Откройте тестовую одноразовую ссылку"));
    String owner = session.get("discordId");
    if (verb.equals("logout") && !read) {
      r.moderation().portalLogout(token);
      return Map.of();
    }
    if (verb.equals("cases") && read) {
      var out = new ArrayList<Map<String, Object>>();
      for (var a : r.database().getAllAppeals(null, 200, 0))
        if (r.moderation().owner(a.id()).filter(owner::equals).isPresent())
          out.add(
              Map.of(
                  "kind",
                  "appeal",
                  "id",
                  a.id(),
                  "player",
                  a.playerName(),
                  "status",
                  a.status(),
                  "reason",
                  a.reason(),
                  "verified",
                  r.moderation().verified(a.id())));
      return out;
    }
    if (p.length < 3 || !verb.equals("appeal")) throw new Rejected(404, "Обращение не найдено");
    int id = integer(p[2]);
    if (r.moderation().owner(id).filter(owner::equals).isEmpty())
      throw new Rejected(404, "Обращение не найдено");
    if (!read && p.length > 3 && p[3].equals("review"))
      return Map.of("id", r.workflows.requestReview("appeal", id, "discord:" + owner, body));
    if (read) {
      if (p.length == 5 && p[3].equals("evidence")) {
        var bytes =
            r.moderation()
                .evidenceBytes("appeal", id, integer(p[4]), true)
                .orElseThrow(() -> new Rejected(404, "Доказательство не найдено"));
        return Map.of(
            "mime",
            me.lovelace.loveWebAdmin.managers.CaseEvidence.imageMime(bytes),
            "base64",
            Base64.getEncoder().encodeToString(bytes));
      }
      return Map.of(
          "reviews",
          r.workflows.publicReviews("appeal", id),
          "messages",
          r.database().getAppealMessages(id),
          "evidence",
          r.moderation().evidence("appeal", id, true));
    }
    var appeal = r.database().getAppealById(id).orElseThrow();
    String message = reason(body, "text");
    int result = r.database().addOpenAppealMessage(id, appeal.playerName(), false, message);
    if (result < 1) throw new Rejected(409, "Обращение закрыто");
    r.delivery("APPEAL_MESSAGE", Map.of("appealId", id, "message", message));
    return Map.of();
  }
}
