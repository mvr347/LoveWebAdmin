package me.lovelace.loveWebAdmin.integration;

import java.util.*;

/** Shared production/test panel: the same three actions, with separate interaction namespaces. */
public final class DiscordPanelMessages {
  private DiscordPanelMessages() {}

  public static Map<String, Object> body(String prefix, boolean preview) {
    var body =
        new LinkedHashMap<String, Object>(
            Map.of(
                "content",
                (preview ? "**[ТЕСТ] Обращения**" : "**Обращения**")
                    + "\n"
                    + "Сообщите о нарушении, обжалуйте наказание или откройте свои обращения. Формы"
                    + " видны только вам."
                    + (preview ? "" : " «Мои обращения» открываются в личных сообщениях бота.")
                    + (preview ? "\nДействия изменяют только эту песочницу." : ""),
                "components",
                List.of(
                    Map.of(
                        "type",
                        1,
                        "components",
                        List.of(
                            button(
                                "Пожаловаться на игрока",
                                preview ? prefix + "report" : prefix + "new:report"),
                            button(
                                "Обжаловать наказание",
                                preview ? prefix + "appeal" : prefix + "new:appeal"),
                            button("Мои обращения", prefix + "mine")))),
                "allowed_mentions",
                Map.of("parse", List.of())));
    body.put(
        "embeds",
        List.of(
            Map.of(
                "title",
                preview ? "Тестовая приёмная" : "Приёмная сообщества",
                "description",
                "**Жалоба** — сообщить о нарушении.\n"
                    + "**Апелляция** — попросить пересмотреть наказание.\n"
                    + "**Мои обращения** — история и ответы сотрудников.\n\n"
                    + "Ваши формы не публикуются в общем канале. Закрытую переписку видят только"
                    + " участники и допущенные сотрудники.",
                "color",
                DiscordCards.BLUE)));
    return body;
  }

  private static Map<String, Object> button(String text, String id) {
    return Map.of(
        "type",
        2,
        "style",
        text.equals("Мои обращения") ? 2 : 1,
        "label",
        text,
        "custom_id",
        id,
        "emoji",
        Map.of(
            "name",
            text.equals("Мои обращения") ? "📬" : text.contains("Пожаловаться") ? "📝" : "⚖️"));
  }
}
