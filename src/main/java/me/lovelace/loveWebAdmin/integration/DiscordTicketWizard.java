package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.*;
import java.net.URI;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.database.TicketDeskStore;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/** Three owner-bound, durable steps. Discord modals are acknowledged before any network I/O. */
final class DiscordTicketWizard {
  private final LoveWebAdmin plugin;
  private final DiscordTicketDesk desk;
  private final TicketDeskStore store;

  DiscordTicketWizard(LoveWebAdmin plugin, DiscordTicketDesk desk, TicketDeskStore store) {
    this.plugin = plugin;
    this.desk = desk;
    this.store = store;
  }

  boolean modal(DiscordInteraction i, DiscordService service) {
    String name = i.name();
    boolean start =
        Set.of("жалоба", "апелляция").contains(name)
            || name.startsWith("lwa:desk:new:")
            || name.startsWith("lwa:appeal:new:");
    boolean details = name.startsWith("lwa:desk:details:");
    boolean identity = name.startsWith("lwa:desk:identity-edit:");
    if (!start && !details && !identity) return false;
    plugin
        .io()
        .supply(
            () -> {
              try {
                if (!desk.enabled())
                  throw new IllegalArgumentException("Обращения временно недоступны");
                var fields = new ArrayList<Map<String, Object>>();
                String custom, title;
                if (start || identity) {
                  String kind;
                  Map<String, Object> data;
                  if (identity) {
                    String id = name.substring("lwa:desk:identity-edit:".length());
                    var draft = store.draft(id, i.userId());
                    if (!draft.state().equals("DRAFT"))
                      throw new IllegalArgumentException("Форма уже отправлена");
                    kind = draft.kind();
                    data = draft.data();
                    custom = "lwa:desk:identity-edit-submit:" + id;
                  } else {
                    kind = name.equals("жалоба") || name.endsWith(":report") ? "report" : "appeal";
                    if (!Set.of("жалоба", "апелляция").contains(name)
                        && !name.startsWith("lwa:appeal:new:")
                        && !Set.of("lwa:desk:new:report", "lwa:desk:new:appeal").contains(name))
                      throw new IllegalArgumentException("Неизвестная форма");
                    data = Map.of();
                    custom = "lwa:desk:identity:" + kind;
                  }
                  String nick =
                      service
                          .getLinkedPlayer(i.userId())
                          .flatMap(
                              uuid -> plugin.getDatabaseManager().getPlayerStats(uuid.toString()))
                          .map(p -> p.get("name").toString())
                          .orElse(Objects.toString(data.get("nick"), ""));
                  if (kind.equals("report"))
                    fields.add(
                        field(
                            "target",
                            "Ник игрока, на которого жалуетесь",
                            16,
                            true,
                            Objects.toString(data.get("target"), ""),
                            1));
                  fields.add(
                      field(
                          "nick",
                          nick.isBlank()
                              ? "Ваш ник Minecraft"
                              : "Ваш аккаунт (подставлен автоматически)",
                          16,
                          true,
                          nick,
                          1));
                  if (kind.equals("appeal"))
                    fields.add(
                        field(
                            "ban",
                            "Номер наказания, если знаете",
                            10,
                            false,
                            Objects.toString(
                                data.get("banId"),
                                name.startsWith("lwa:appeal:new:")
                                    ? name.substring("lwa:appeal:new:".length())
                                    : ""),
                            1));
                  title = "Шаг 1 из 3 · Аккаунт и участник";
                } else {
                  String id = name.substring("lwa:desk:details:".length());
                  var draft = store.draft(id, i.userId());
                  if (!draft.state().equals("DRAFT"))
                    throw new IllegalArgumentException("Форма уже отправлена");
                  var data = draft.data();
                  custom = "lwa:desk:details-submit:" + id;
                  title = "Шаг 2 из 3 · Что произошло";
                  fields.add(
                      field(
                          "reason",
                          "Что произошло и что нужно проверить?",
                          1500,
                          true,
                          Objects.toString(data.get("detailReason"), ""),
                          2));
                  fields.add(
                      field(
                          "when",
                          "Когда это произошло? (необязательно)",
                          120,
                          false,
                          Objects.toString(data.get("when"), ""),
                          1));
                  fields.add(
                      field(
                          "where",
                          "Где? Мир, место или режим",
                          120,
                          false,
                          Objects.toString(data.get("where"), ""),
                          1));
                  fields.add(
                      field(
                          "evidence",
                          "Ссылка на доказательства (необязательно)",
                          400,
                          false,
                          Objects.toString(data.get("evidence"), ""),
                          1));
                }
                return Map.<String, Object>of(
                    "type",
                    9,
                    "data",
                    Map.of("custom_id", custom, "title", title, "components", fields));
              } catch (Exception error) {
                return Map.<String, Object>of(
                    "type",
                    4,
                    "data",
                    Map.of(
                        "flags",
                        64,
                        "content",
                        error instanceof IllegalArgumentException
                            ? error.getMessage()
                            : "Не удалось открыть форму. Повторите попытку.",
                        "allowed_mentions",
                        Map.of("parse", List.of())));
              }
            })
        .completeOnTimeout(
            Map.of(
                "type",
                4,
                "data",
                Map.of(
                    "flags",
                    64,
                    "content",
                    "Форма открывается слишком долго. Нажмите кнопку ещё раз.",
                    "allowed_mentions",
                    Map.of("parse", List.of()))),
            2,
            java.util.concurrent.TimeUnit.SECONDS)
        .thenAccept(
            payload -> {
              if (desk.enabled()) service.respond(i, JsonUtils.toJson(payload));
            });
    return true;
  }

  Map<String, Object> submit(DiscordInteraction i, DiscordService service) throws Exception {
    String name = i.name();
    if (name.startsWith("lwa:desk:identity:")
        || name.startsWith("lwa:desk:identity-edit-submit:")) {
      boolean edit = name.startsWith("lwa:desk:identity-edit-submit:");
      String id = edit ? name.substring("lwa:desk:identity-edit-submit:".length()) : null;
      var old = edit ? store.draft(id, i.userId()) : null;
      String kind = edit ? old.kind() : name.substring("lwa:desk:identity:".length());
      if (!Set.of("report", "appeal").contains(kind))
        throw new IllegalArgumentException("Неизвестная форма");
      var input = new HashMap<String, Object>(i.values());
      input.put("reason", "Черновик обращения");
      var data = new LinkedHashMap<String, Object>(desk.validate(kind, i.userId(), input));
      if (old != null)
        for (String key : List.of("detailReason", "when", "where", "evidence"))
          if (old.data().containsKey(key)) data.put(key, old.data().get(key));
      data.remove("reason");
      data.put("_step", 2);
      if (edit) store.updateDraft(id, i.userId(), data);
      else id = store.draft(i.userId(), kind, data);
      return card(
          "Шаг 2 из 3 · Описание",
          "Аккаунт: **"
              + data.get("nick")
              + "**\n"
              + (kind.equals("report")
                  ? "Жалоба на **" + data.get("target") + "**"
                  : "Апелляция на наказание №" + data.get("banId"))
              + "\n\n"
              + "Расскажите, что произошло. Можно указать время, место и ссылку. Файлы можно"
              + " добавить в закрытом канале после отправки.",
          List.of(
              button("Заполнить описание", "lwa:desk:details:" + id, 1),
              button("Изменить участника", "lwa:desk:identity-edit:" + id, 2),
              button("Отменить", "lwa:desk:cancel:" + id, 2)));
    }
    if (!name.startsWith("lwa:desk:details-submit:")) return null;
    String id = name.substring("lwa:desk:details-submit:".length());
    var draft = store.draft(id, i.userId());
    var data = new LinkedHashMap<String, Object>(draft.data());
    for (String key : List.of("when", "where", "evidence")) {
      String value = i.values().getOrDefault(key, "").trim();
      if (value.length() > (key.equals("evidence") ? 400 : 120))
        throw new IllegalArgumentException(
            "Сократите поле «"
                + switch (key) {
                  case "when" -> "Когда";
                  case "where" -> "Где";
                  default -> "Доказательства";
                }
                + "»");
      data.put(key, value);
    }
    data.put("detailReason", i.values().getOrDefault("reason", "").trim());
    data.put("_step", 2);
    store.updateDraft(id, i.userId(), data);
    String reason = data.get("detailReason").toString();
    if (reason.length() < 5 || reason.length() > 1500)
      throw new IllegalArgumentException("Описание: от 5 до 1500 символов");
    String link = data.get("evidence").toString();
    if (!link.isBlank()) {
      URI uri;
      try {
        uri = URI.create(link);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException(
            "Ссылка на доказательства должна начинаться с https:// или http://");
      }
      if (!Set.of("http", "https")
              .contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT))
          || uri.getHost() == null
          || uri.getUserInfo() != null)
        throw new IllegalArgumentException(
            "Укажите обычную ссылку http:// или https:// без логина и пароля");
    }
    for (String key : List.of("when", "where", "evidence"))
      if (!data.get(key).toString().isBlank())
        reason +=
            "\n"
                + switch (key) {
                  case "when" -> "Когда: ";
                  case "where" -> "Где: ";
                  default -> "Доказательства: ";
                }
                + data.get(key);
    if (reason.length() > 2000)
      throw new IllegalArgumentException(
          "Общее описание слишком длинное. Сократите его до 2000 символов");
    data.put("reason", reason);
    desk.validate(draft.kind(), i.userId(), data);
    data.put("_step", 3);
    store.updateDraft(id, i.userId(), data);
    return card(
        "Шаг 3 из 3 · Проверьте обращение",
        "Ваш ник: **"
            + data.get("nick")
            + "**\n"
            + (draft.kind().equals("report")
                ? "Игрок: **" + data.get("target") + "**"
                : "Наказание №" + data.get("banId"))
            + "\n\n"
            + reason
            + "\n\n"
            + "После подтверждения создастся закрытый канал. Его увидите вы и допущенные"
            + " сотрудники.",
        List.of(
            button("Отправить обращение", "lwa:desk:confirm:" + id, 3),
            button("Изменить описание", "lwa:desk:details:" + id, 2),
            button("Изменить участника", "lwa:desk:identity-edit:" + id, 2),
            button("Отменить", "lwa:desk:cancel:" + id, 2)));
  }

  List<Map<String, Object>> recovery(DiscordInteraction i) {
    if (!i.name().startsWith("lwa:desk:details-submit:")) return List.of();
    String id = i.name().substring("lwa:desk:details-submit:".length());
    return List.of(
        Map.of(
            "type",
            1,
            "components",
            List.of(button("Исправить описание", "lwa:desk:details:" + id, 1))));
  }

  private static Map<String, Object> field(
      String id, String label, int max, boolean required, String value, int style) {
    var input = new LinkedHashMap<String, Object>();
    input.put("type", 4);
    input.put("custom_id", id);
    input.put("label", label);
    input.put("style", style);
    input.put("required", required);
    input.put("max_length", max);
    if (id.equals("reason")) input.put("min_length", 5);
    if (!value.isBlank()) input.put("value", DiscordCards.cap(value, max));
    return Map.of("type", 1, "components", List.of(input));
  }

  private static Map<String, Object> button(String label, String id, int style) {
    return Map.of("type", 2, "label", label, "custom_id", id, "style", style);
  }

  private static Map<String, Object> card(
      String title, String description, List<Map<String, Object>> buttons) {
    return DiscordCards.decorate(
        Map.of(
            "content",
            "**" + title + "**\n" + description,
            "components",
            List.of(Map.of("type", 1, "components", buttons))),
        DiscordCards.BLUE);
  }
}
