package me.lovelace.loveWebAdmin.integration;

import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/**
 * Sends through the durable outbox; quiet hours and current authority are rechecked on delivery.
 */
final class CaseReminderDelivery {
  private final LoveWebAdmin plugin;
  private final DiscordTicketDesk desk;

  CaseReminderDelivery(LoveWebAdmin plugin, DiscordTicketDesk desk) {
    this.plugin = plugin;
    this.desk = desk;
  }

  void scan() throws Exception {
    for (var due : plugin.workflows().due()) {
      var recipients = new LinkedHashSet<String>();
      boolean staff = due.mode().equals("STAFF");
      if (!staff) {
        var ticket = desk.store().get(due.kind(), due.id());
        ticket.map(t -> t.owner()).filter(s -> !s.isBlank()).ifPresent(recipients::add);
        if (recipients.isEmpty() && due.kind().equals("appeal"))
          plugin.moderation().owner(due.id()).ifPresent(recipients::add);
      } else {
        for (var admin : plugin.getDatabaseManager().getAllAdmins()) {
          if (!admin.status().equals("ACTIVE")) continue;
          var linked = desk.linked(admin.username());
          if (linked.isEmpty()) continue;
          if (due.stage() == 1 && !due.assignee().isBlank()) {
            if (admin.username().equals(due.assignee())) recipients.add(linked.get());
          } else if (desk.supervisor(admin, linked.get())) recipients.add(linked.get());
        }
      }
      String text =
          (staff
                  ? due.stage() == 2
                      ? "Обращение требует внимания руководства"
                      : "Ожидается ответ сотрудника"
                  : "Ожидается ваш ответ")
              + " · "
              + (due.kind().equals("appeal") ? "апелляция" : "жалоба")
              + " №"
              + due.id()
              + ".\nСрок ожидания истёк. Это напоминание, решение автоматически не принимается.";
      for (String recipient : recipients) {
        String event =
            "wait:"
                + due.kind()
                + ":"
                + due.id()
                + ":"
                + due.version()
                + ":"
                + due.stage()
                + ":"
                + recipient;
        plugin
            .moderation()
            .enqueue(
                event,
                recipient,
                JsonUtils.toJson(
                    Map.of(
                        "type",
                        "CASE_REMINDER",
                        "kind",
                        due.kind(),
                        "caseId",
                        due.id(),
                        "version",
                        due.version(),
                        "stage",
                        due.stage(),
                        "mode",
                        due.mode(),
                        "staff",
                        staff,
                        "content",
                        text)));
      }
      plugin.workflows().store().advance(due.kind(), due.id(), due.version(), due.stage());
    }
  }

  boolean allowed(Map<String, Object> payload, String recipient) throws Exception {
    String kind = payload.get("kind").toString(), mode = payload.get("mode").toString();
    int id = ((Number) payload.get("caseId")).intValue();
    if (!plugin
        .workflows()
        .reminderCurrent(kind, id, ((Number) payload.get("version")).longValue(), mode))
      return false;
    if (!Boolean.TRUE.equals(payload.get("staff"))) return desk.reviewAuthor(kind, id, recipient);
    var admin = desk.workflowActions().staffMember(recipient, DiscordTicketDesk.permission(kind));
    if (admin.isEmpty()) return false;
    var prefs = JsonUtils.parseObject(Objects.toString(admin.get().uiPreferences(), "{}"));
    String key = kind.equals("appeal") ? "discordNotifyTickets" : "discordNotifyReports";
    if (!Boolean.TRUE.equals(prefs.get(key))) return false;
    String assigned = plugin.moderation().work(kind, id).get("assignee").toString();
    return ((Number) payload.get("stage")).intValue() == 1 && !assigned.isBlank()
        ? assigned.equals(admin.get().username())
        : desk.supervisor(admin.get(), recipient);
  }

  void reviews() throws Exception {
    for (var request : plugin.workflows().store().notices()) {
      String kind = request.get("kind").toString();
      int id = ((Number) request.get("case_id")).intValue(),
          review = ((Number) request.get("id")).intValue();
      String state = request.get("state").toString();
      String status =
          switch (state) {
            case "PENDING" -> "Ожидает рассмотрения";
            case "PROCESSING" -> "Применение требует сверки";
            case "ACCEPTED" -> "Принято";
            default -> "Отклонено";
          };
      var buttons = new ArrayList<Map<String, Object>>();
      if (state.equals("PENDING")) {
        buttons.add(
            DiscordTicketDesk.button(
                "Посмотреть дополнение",
                "lwa:flow:review:view:" + kind + ":" + id + ":" + review,
                1));
        buttons.add(
            DiscordTicketDesk.button(
                "Принять к рассмотрению",
                "lwa:flow:review:acceptform:" + kind + ":" + id + ":" + review,
                3));
        buttons.add(
            DiscordTicketDesk.button(
                "Отклонить", "lwa:flow:review:rejectform:" + kind + ":" + id + ":" + review, 4));
      }
      if (!state.equals("PENDING") && !state.equals("PROCESSING")) {
        String owner = desk.store().get(kind, id).map(t -> t.owner()).orElse("");
        if (owner.isBlank() && kind.equals("appeal"))
          owner = plugin.moderation().owner(id).orElse("");
        if (!owner.isBlank()) {
          var detail =
              plugin.workflows().store().reviews(kind, id).stream()
                  .filter(r -> ((Number) r.get("id")).intValue() == review)
                  .findFirst()
                  .orElseThrow();
          String message =
              "Новые доказательства №"
                  + review
                  + ": "
                  + status
                  + ".\n"
                  + detail.get("explanation")
                  + (((Number) detail.get("new_id")).intValue() > 0
                      ? "\nНовое рассмотрение №"
                          + detail.get("new_id")
                          + " доступно в «Моих обращениях»."
                      : "");
          plugin
              .moderation()
              .enqueue(
                  "review-result:" + review + ":" + owner,
                  owner,
                  JsonUtils.toJson(
                      DiscordTicketDesk.explainedBody(
                          "Результат рассмотрения новых доказательств", message, List.of())));
        }
      }
      if (!desk.queueChannel().isBlank())
        desk.workflowPublish(
            "review:" + review,
            desk.queueChannel(),
            DiscordTicketDesk.body(
                "Новые доказательства №" + review + " · исходное обращение №" + id + "\n" + status,
                buttons.isEmpty() ? List.of() : List.of(DiscordTicketDesk.row(buttons))));
      plugin.workflows().store().noticeDone(review, state);
    }
  }
}
