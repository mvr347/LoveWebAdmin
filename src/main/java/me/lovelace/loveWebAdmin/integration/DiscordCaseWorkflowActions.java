package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.*;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/** Template selection and confirmation are private; no displayed draft performs a punishment. */
final class DiscordCaseWorkflowActions {
  private final LoveWebAdmin plugin;
  private final DiscordTicketDesk desk;

  DiscordCaseWorkflowActions(LoveWebAdmin plugin, DiscordTicketDesk desk) {
    this.plugin = plugin;
    this.desk = desk;
  }

  private static Map<String, Object> formField(String id, String label, String value, int max) {
    var field = new LinkedHashMap<String, Object>();
    field.put("type", 4);
    field.put("custom_id", id);
    field.put("label", label);
    field.put("style", max > 100 ? 2 : 1);
    field.put("max_length", max);
    field.put("required", true);
    if (!value.isBlank()) field.put("value", value);
    return Map.of("type", 1, "components", List.of(field));
  }

  boolean onInteraction(DiscordInteraction i, DiscordService service, DiscordActions actions) {
    if (!i.name().startsWith("lwa:flow:")) return false;
    if (!i.guildId().isBlank() && !i.guildId().equals(CoreDiscordExtensions.guild(service))) {
      service.respond(
          i,
          JsonUtils.toJson(
              Map.of(
                  "type",
                  4,
                  "data",
                  Map.of("flags", 64, "content", "Обращения доступны на сервере сообщества"))));
      return true;
    }
    try {
      var p = i.name().split(":");
      if (p[2].equals("review") && p[3].equals("new")) {
        String kind = p[4];
        int id = Integer.parseInt(p[5]);
        if (!desk.reviewAuthor(kind, id, i.userId()))
          throw new IllegalArgumentException("Дополнение доступно только автору обращения");
        service.respond(
            i,
            JsonUtils.toJson(
                Map.of(
                    "type",
                    9,
                    "data",
                    Map.of(
                        "custom_id",
                        "lwa:flow:review:submit:" + kind + ":" + id,
                        "title",
                        "Новые доказательства",
                        "components",
                        List.of(formField("text", "Что нового? Описание и ссылки", "", 2000))))));
        return true;
      }
      if (p[2].equals("review") && Set.of("acceptform", "rejectform").contains(p[3])) {
        String kind = p[4];
        int id = Integer.parseInt(p[5]);
        int review = Integer.parseInt(p[6]);
        reviewStaff(i, actions, kind, id);
        service.respond(
            i,
            JsonUtils.toJson(
                Map.of(
                    "type",
                    9,
                    "data",
                    Map.of(
                        "custom_id",
                        "lwa:flow:review:"
                            + (p[3].equals("acceptform") ? "accept" : "reject")
                            + ":"
                            + kind
                            + ":"
                            + id
                            + ":"
                            + review,
                        "title",
                        "Рассмотрение новых доказательств",
                        "components",
                        List.of(formField("reason", "Объяснение решения", "", 2000))))));
        return true;
      }
      if (p[2].equals("apply")) {
        String action = p[3], kind = p[4];
        int id = Integer.parseInt(p[5]), template = Integer.parseInt(p[6]);
        var staff =
            actions
                .staffMember(i.userId(), DiscordTicketDesk.permission(kind))
                .orElseThrow(() -> new IllegalArgumentException("Нужны права сотрудника"));
        if (!desk.workflowChannel(kind, id, i.values().getOrDefault("_channelId", "")))
          throw new IllegalArgumentException("Откройте соответствующее обращение");
        var value =
            plugin.workflows().store().templates("staff:" + staff.id()).stream()
                .filter(t -> ((Number) t.get("id")).intValue() == template)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Шаблон недоступен"));
        boolean clarify = action.equals("clarify");
        if (!(clarify ? "CLARIFY" : "VERDICT").equals(value.get("purpose"))
            || !Set.of("clarify", "approve", "reject", "reduce").contains(action))
          throw new IllegalArgumentException("Этот шаблон не подходит действию");
        var fields = new ArrayList<Map<String, Object>>();
        if (action.equals("reduce"))
          fields.add(formField("minutes", "Оставшийся срок в минутах", "", 10));
        fields.add(
            formField(
                "reason",
                "Отредактируйте текст перед отправкой",
                value.get("text").toString(),
                2000));
        service.respond(
            i,
            JsonUtils.toJson(
                Map.of(
                    "type",
                    9,
                    "data",
                    Map.of(
                        "custom_id",
                        "lwa:desk:submit:" + action + ":" + kind + ":" + id,
                        "title",
                        "Редактирование шаблона",
                        "components",
                        fields))));
        return true;
      }
    } catch (Exception error) {
      service.respond(
          i,
          JsonUtils.toJson(
              Map.of(
                  "type",
                  4,
                  "data",
                  Map.of(
                      "flags",
                      64,
                      "content",
                      Objects.toString(error.getMessage(), "Действие недоступно")))));
      return true;
    }
    service
        .respond(i, JsonUtils.toJson(Map.of("type", 5, "data", Map.of("flags", 64))))
        .thenAccept(
            ok -> {
              if (!ok) return;
              plugin
                  .io()
                  .run(
                      () -> {
                        Map<String, Object> answer;
                        try {
                          answer = execute(i, actions);
                        } catch (Exception error) {
                          answer =
                              DiscordTicketDesk.body(
                                  error instanceof IllegalArgumentException
                                      ? error.getMessage()
                                      : "Действие не подтверждено; проверьте дело в панели",
                                  List.of());
                        }
                        CoreDiscordExtensions.managed(
                            service,
                            "editInteractionResponse",
                            new Class<?>[] {DiscordInteraction.class, String.class},
                            i,
                            JsonUtils.toJson(answer));
                      });
            });
    return true;
  }

  private WebAdmin reviewStaff(DiscordInteraction i, DiscordActions actions, String kind, int id)
      throws Exception {
    if (!desk.queueChannel().equals(i.values().getOrDefault("_channelId", ""))
        || desk.queueChannel().isBlank())
      throw new IllegalArgumentException("Откройте служебную очередь");
    var staff =
        actions
            .staffMember(i.userId(), DiscordTicketDesk.permission(kind))
            .orElseThrow(() -> new IllegalArgumentException("Нужны права сотрудника"));
    plugin.workflows().independent(kind, id, staff.username());
    return staff;
  }

  private Map<String, Object> execute(DiscordInteraction i, DiscordActions actions)
      throws Exception {
    if (!plugin.operations().allow("discord:" + i.userId()))
      throw new IllegalArgumentException("Слишком много нажатий");
    var p = i.name().split(":");
    if (p[2].equals("review") && !p[3].equals("submit")) {
      String kind = p[4];
      int id = Integer.parseInt(p[5]), request = Integer.parseInt(p[6]);
      var staff = reviewStaff(i, actions, kind, id);
      var review =
          plugin.workflows().store().reviews(kind, id).stream()
              .filter(r -> ((Number) r.get("id")).intValue() == request)
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Дополнение не найдено"));
      if (p[3].equals("view")) {
        var links = new ArrayList<Map<String, Object>>();
        if (!desk.publicUrl().isBlank())
          links.add(
              DiscordTicketDesk.row(
                  List.of(
                      Map.of(
                          "type",
                          2,
                          "style",
                          5,
                          "label",
                          "Открыть материалы в панели",
                          "url",
                          desk.publicUrl() + "/case.html?kind=" + kind + "&id=" + id))));
        return DiscordTicketDesk.explainedBody(
            "**Новые доказательства №"
                + request
                + "**"
                + (Boolean.TRUE.equals(review.get("hasImage"))
                    ? "\nПриложен скриншот, доступный в панели."
                    : ""),
            review.get("body").toString(),
            links);
      }
      var result =
          plugin
              .workflows()
              .review(
                  kind,
                  id,
                  request,
                  staff.username(),
                  p[3].equals("accept"),
                  i.values().getOrDefault("reason", ""));
      return DiscordTicketDesk.body(
          result.get("state").equals("ACCEPTED")
              ? "Открыто новое рассмотрение №" + result.get("id") + ". Прежнее решение сохранено."
              : "Дополнение отклонено с объяснением.",
          List.of());
    }
    if (p[2].equals("review")) {
      String kind = p[4];
      int id = Integer.parseInt(p[5]);
      if (!desk.reviewAuthor(kind, id, i.userId()))
        throw new IllegalArgumentException("Дополнение доступно только автору");
      int request =
          plugin
              .workflows()
              .requestReview(
                  kind,
                  id,
                  "discord:" + i.userId(),
                  Map.of("text", i.values().getOrDefault("text", "")));
      return DiscordTicketDesk.body(
          "Новые доказательства №"
              + request
              + " переданы сотрудникам. Прежнее решение не изменено. Скриншот можно приложить через личный кабинет.",
          List.of());
    }
    if (p[2].equals("confirm") || p[2].equals("cancel")) {
      String key = p[3], actor = "discord:" + i.userId();
      var prepared =
          plugin.workflows().store().prepared(key, actor, System.currentTimeMillis() / 1000);
      var data = prepared.payload();
      if (p[2].equals("cancel")) {
        plugin.workflows().store().cancelDecision(key, actor);
        return DiscordTicketDesk.body(
            "Применение отменено. Текст решения не отправлен игроку.", List.of());
      }
      String kind = data.get("kind").toString();
      int id = ((Number) data.get("id")).intValue();
      var staff = desk.workflowStaff(i, kind, id, prepared.state().equals("DONE"));
      if (kind.equals("appeal")
          && !data.get("action").equals("reject")
          && actions.staffMember(i.userId(), Permission.MANAGE_BANS).isEmpty())
        throw new IllegalArgumentException("Нужно право управления наказаниями");
      return DiscordTicketDesk.body(
          plugin.workflows().confirm(key, actor, staff.username()), List.of());
    }
    String purpose = p[3], kind = p[4];
    int id = Integer.parseInt(p[5]), page = p.length > 6 ? Integer.parseInt(p[6]) : 0;
    var staff = desk.workflowStaff(i, kind, id, false);
    var templates =
        plugin.workflows().store().templates("staff:" + staff.id()).stream()
            .filter(t -> t.get("purpose").equals(purpose))
            .toList();
    if (p[2].equals("choose")) {
      int selected = Integer.parseInt(i.values().getOrDefault("selected", "0"));
      var template =
          templates.stream()
              .filter(t -> ((Number) t.get("id")).intValue() == selected)
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Шаблон недоступен"));
      var buttons = new ArrayList<Map<String, Object>>();
      if (purpose.equals("CLARIFY"))
        buttons.add(
            DiscordTicketDesk.button(
                "Уточнить", "lwa:flow:apply:clarify:" + kind + ":" + id + ":" + selected, 1));
      else {
        buttons.add(
            DiscordTicketDesk.button(
                "Одобрить", "lwa:flow:apply:approve:" + kind + ":" + id + ":" + selected, 3));
        buttons.add(
            DiscordTicketDesk.button(
                "Отклонить", "lwa:flow:apply:reject:" + kind + ":" + id + ":" + selected, 4));
        if (kind.equals("appeal"))
          buttons.add(
              DiscordTicketDesk.button(
                  "Сократить срок",
                  "lwa:flow:apply:reduce:" + kind + ":" + id + ":" + selected,
                  1));
      }
      return DiscordTicketDesk.explainedBody(
          "**"
              + template.get("name")
              + "**\nСледующий шаг откроет редактируемую форму. Решение пока не применяется.",
          template.get("text").toString(),
          List.of(DiscordTicketDesk.row(buttons)));
    }
    int start = Math.max(0, page) * 25;
    if (start >= templates.size())
      return DiscordTicketDesk.body(
          "Шаблонов пока нет. Добавьте личный или общий шаблон в карточке дела панели.", List.of());
    var options =
        templates.subList(start, Math.min(start + 25, templates.size())).stream()
            .map(t -> Map.of("label", t.get("name"), "value", t.get("id").toString()))
            .toList();
    var components = new ArrayList<Map<String, Object>>();
    components.add(
        Map.of(
            "type",
            1,
            "components",
            List.of(
                Map.of(
                    "type",
                    3,
                    "custom_id",
                    "lwa:flow:choose:" + purpose + ":" + kind + ":" + id + ":" + page,
                    "placeholder",
                    "Выберите шаблон",
                    "options",
                    options))));
    var paging = new ArrayList<Map<String, Object>>();
    if (page > 0)
      paging.add(
          DiscordTicketDesk.button(
              "Назад",
              "lwa:flow:templates:" + purpose + ":" + kind + ":" + id + ":" + (page - 1),
              2));
    if (start + 25 < templates.size())
      paging.add(
          DiscordTicketDesk.button(
              "Далее",
              "lwa:flow:templates:" + purpose + ":" + kind + ":" + id + ":" + (page + 1),
              2));
    if (!paging.isEmpty()) components.add(DiscordTicketDesk.row(paging));
    return DiscordTicketDesk.body(
        "Выберите текст. Перед отправкой его можно отредактировать.", components);
  }

  Map<String, Object> preview(
      DiscordInteraction i, String kind, int id, WebAdmin staff, String action, String reason)
      throws Exception {
    if (kind.equals("appeal")
        && !action.equals("reject")
        && desk.workflowActions().staffMember(i.userId(), Permission.MANAGE_BANS).isEmpty())
      throw new IllegalArgumentException("Нужно право управления наказаниями");
    long seconds =
        action.equals("reduce")
            ? DiscordActions.minutes(i.values().get("minutes")) * 60
            : 0;
    var value =
        plugin
            .workflows()
            .preview(kind, id, "discord:" + i.userId(), staff.username(), action, reason, seconds);
    long newEnd = ((Number) value.get("targetExpiry")).longValue();
    var before = (Map<?, ?>) value.get("before");
    String text =
        "**Предпросмотр решения**\nИгрок: "
            + before.get("target")
            + "\n"
            + value.get("effect")
            + (before.containsKey("expiry")
                ? "\nПрежнее окончание: "
                    + (((Number) before.get("expiry")).longValue() > 0
                        ? "<t:" + before.get("expiry") + ":F>"
                        : "Бессрочно")
                : "")
            + (newEnd > 0 ? "\nНовое окончание: <t:" + newEnd + ":F>" : "")
            + "\nОбращение закроется после применения.";
    var response =
        new LinkedHashMap<String, Object>(
            DiscordTicketDesk.body(
                text,
                List.of(
                    DiscordTicketDesk.row(
                        List.of(
                            DiscordTicketDesk.button(
                                "Подтвердить решение", "lwa:flow:confirm:" + value.get("key"), 3),
                            DiscordTicketDesk.button(
                                "Отмена", "lwa:flow:cancel:" + value.get("key"), 2))))));
    response.put(
        "embeds",
        List.of(
            Map.of(
                "title",
                "Объяснение, доступное игроку",
                "description",
                value.get("playerMessage"),
                "color",
                0xD8A74B)));
    return response;
  }
}
