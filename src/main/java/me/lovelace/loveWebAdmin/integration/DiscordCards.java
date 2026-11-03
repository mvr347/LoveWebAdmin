package me.lovelace.loveWebAdmin.integration;

import java.util.*;

/** Shared private cards: text carries meaning independently of colour and emoji. */
final class DiscordCards {
  static final int BLUE = 0x5865F2, GREEN = 0x57F287, AMBER = 0xFEE75C, GRAY = 0x95A5A6;

  static Map<String, Object> decorate(Map<String, Object> original, int color) {
    var result = new LinkedHashMap<String, Object>(original);
    String text = Objects.toString(original.get("content"), "");
    int split = text.indexOf('\n');
    String title = (split < 0 ? text : text.substring(0, split)).replace("**", "");
    String description = split < 0 ? "Выберите действие ниже." : text.substring(split + 1).strip();
    result.put("content", "");
    result.put(
        "embeds",
        List.of(
            Map.of(
                "title",
                cap(title, 256),
                "description",
                cap(description.isEmpty() ? "Выберите действие ниже." : description, 3800),
                "color",
                color,
                "footer",
                Map.of("text", "Личный кабинет • Видно только вам"))));
    result.put("allowed_mentions", Map.of("parse", List.of()));
    return result;
  }

  static String cap(String text, int max) {
    int end = Math.min(text.length(), max);
    if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
    return text.substring(0, end);
  }
}
