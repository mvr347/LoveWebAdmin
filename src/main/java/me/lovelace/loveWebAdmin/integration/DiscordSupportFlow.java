package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.*;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/**
 * Support requests: a topic select, one modal and the usual review-then-confirm step. They are
 * kept apart from the report/appeal wizard because nothing about them depends on a punishment, a
 * target player or a ban.
 *
 * <p>The first two steps (the panel button and the topic select) are answered immediately and do
 * no I/O, as Discord needs a response within three seconds.
 */
final class DiscordSupportFlow {
  static final String OPEN = "lwa:desk:new:support";
  static final String TOPIC = "lwa:desk:support:topic";
  static final String DRAFT = "lwa:desk:sdraft:";

  private final LoveWebAdmin plugin;
  private final DiscordTicketDesk desk;

  DiscordSupportFlow(LoveWebAdmin plugin, DiscordTicketDesk desk) {
    this.plugin = plugin;
    this.desk = desk;
  }

  boolean onInteraction(DiscordInteraction i, DiscordService service) {
    String name = i.name();
    if (!name.equals(OPEN) && !name.equals(TOPIC)) return false;
    if (!desk.enabled()) return false;
    var topics = desk.supportTopics();
    if (!topics.enabled()) {
      reply(service, i, "Поддержка сейчас недоступна.");
      return true;
    }
    if (name.equals(OPEN)) {
      var options = new ArrayList<Map<String, Object>>();
      for (var topic : topics.topics()) {
        var option = new LinkedHashMap<String, Object>();
        option.put("label", topic.label());
        option.put("value", topic.id());
        if (!topic.description().isBlank()) option.put("description", topic.description());
        options.add(option);
      }
      var select =
          Map.of(
              "type", 3,
              "custom_id", TOPIC,
              "placeholder", "Выберите тему обращения",
              "min_values", 1,
              "max_values", 1,
              "options", options);
      service.respond(
          i,
          JsonUtils.toJson(
              Map.of(
                  "type", 4,
                  "data",
                  Map.of(
                      "flags", 64,
                      "content", "**Поддержка**\nВыберите тему, затем опишите вопрос.",
                      "components", List.of(Map.of("type", 1, "components", List.of(select))),
                      "allowed_mentions", Map.of("parse", List.of())))));
      return true;
    }
    var topic = topics.find(i.values().getOrDefault("selected", ""));
    if (topic.isEmpty()) {
      reply(service, i, "Эта тема больше недоступна. Откройте поддержку заново.");
      return true;
    }
    var fields = new ArrayList<Map<String, Object>>();
    if (service.getLinkedPlayer(i.userId()).isEmpty())
      fields.add(field("nick", "Ваш ник Minecraft", 1, 3, 16));
    fields.add(field("reason", "Опишите вопрос", 2, 10, 2000));
    service.respond(
        i,
        JsonUtils.toJson(
            Map.of(
                "type", 9,
                "data",
                Map.of(
                    "custom_id", DRAFT + topic.get().id(),
                    "title", "Поддержка · " + DiscordCards.cap(topic.get().label(), 30),
                    "components", fields))));
    return true;
  }

  /** Modal submit: validates and stores the draft, then asks for confirmation. */
  Map<String, Object> draft(DiscordInteraction i) throws java.sql.SQLException {
    String topic = i.name().substring(DRAFT.length());
    var data = validate(i.userId(), topic, i.values());
    String id = desk.store().draft(i.userId(), "support", data);
    String label = desk.supportTopics().find(topic).map(SupportTopics.Topic::label).orElse(topic);
    String text = data.get("reason").toString();
    return DiscordTicketDesk.body(
        "**Проверьте обращение**\nПоддержка · "
            + label
            + "\nВаш ник: "
            + data.get("nick")
            + "\n\n"
            + text.substring(0, Math.min(text.length(), 1400))
            + "\n\nПосле подтверждения появится приватный канал.",
        List.of(
            DiscordTicketDesk.row(
                List.of(
                    DiscordTicketDesk.button("Отправить обращение", "lwa:desk:confirm:" + id, 3),
                    DiscordTicketDesk.button("Отменить", "lwa:desk:cancel:" + id, 2)))));
  }

  Map<String, Object> validate(String discord, String topic, Map<String, ?> input) {
    if (!desk.supportTopics().enabled())
      throw new IllegalArgumentException("Поддержка сейчас недоступна");
    if (desk.supportTopics().find(topic).isEmpty())
      throw new IllegalArgumentException("Тема поддержки недоступна. Откройте поддержку заново");
    var identity = new DiscordPlayerContext(plugin, desk.service()).linked(discord);
    String linked = identity.map(found -> found.uuid().toString()).orElse("");
    if (input.containsKey("linkedUuid")
        && !Objects.toString(input.get("linkedUuid"), "").equals(linked))
      throw new IllegalArgumentException(
          "Привязка аккаунта изменилась. Откройте поддержку заново");
    String nick =
        identity
            .map(DiscordPlayerContext.Identity::name)
            .orElse(Objects.toString(input.get("nick"), "").trim());
    if (!nick.matches("[A-Za-z0-9_]{1,16}"))
      throw new IllegalArgumentException("Укажите корректный ник Minecraft");
    String reason = Objects.toString(input.get("reason"), "").trim();
    if (reason.length() < 10 || reason.length() > 2000)
      throw new IllegalArgumentException("Описание: от 10 до 2000 символов");
    var data = new LinkedHashMap<String, Object>();
    data.put("nick", nick);
    data.put("linkedUuid", linked);
    data.put("topic", topic);
    data.put("reason", reason);
    return data;
  }

  private static void reply(DiscordService service, DiscordInteraction i, String text) {
    service.respond(
        i,
        JsonUtils.toJson(
            Map.of(
                "type", 4,
                "data",
                Map.of(
                    "flags", 64,
                    "content", text,
                    "allowed_mentions", Map.of("parse", List.of())))));
  }

  private static Map<String, Object> field(String id, String label, int style, int min, int max) {
    return Map.of(
        "type",
        1,
        "components",
        List.of(
            Map.of(
                "type", 4,
                "custom_id", id,
                "label", label,
                "style", style,
                "required", true,
                "min_length", min,
                "max_length", max)));
  }
}
