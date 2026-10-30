package me.lovelace.loveWebAdmin.integration;

import java.util.*;

/** Shared production/test panel: the same three actions, with separate interaction namespaces. */
public final class DiscordPanelMessages {
  private DiscordPanelMessages() {}

  public static Map<String, Object> body(String prefix, boolean preview) {
    return Map.of(
        "content",
        (preview ? "**[ТЕСТ] Обращения**" : "**Обращения**")
            + "\nСообщите о нарушении, обжалуйте наказание или откройте свои обращения. Переписка будет приватной."
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
        Map.of("parse", List.of()));
  }

  private static Map<String, Object> button(String text, String id) {
    return Map.of("type", 2, "style", 1, "label", text, "custom_id", id);
  }
}
