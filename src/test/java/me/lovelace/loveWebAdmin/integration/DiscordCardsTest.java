package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DiscordCardsTest {
  @Test
  void privateCardKeepsControlsAndDoesNotPingAnyone() {
    var controls =
        List.of(
            Map.of(
                "type",
                1,
                "components",
                List.of(
                    Map.of(
                        "type",
                        2,
                        "style",
                        1,
                        "label",
                        "Ответить",
                        "custom_id",
                        "lwa:mine:reply:report:1"))));
    var body =
        DiscordCards.decorate(
            Map.of(
                "content",
                "**Жалоба №1**\nСтатус: Нужен ваш ответ\n@everyone",
                "components",
                controls),
            DiscordCards.AMBER);
    assertEquals("", body.get("content"));
    assertEquals(controls, body.get("components"));
    assertEquals(List.of(), ((Map<?, ?>) body.get("allowed_mentions")).get("parse"));
    var embed = (Map<?, ?>) ((List<?>) body.get("embeds")).getFirst();
    assertEquals("Жалоба №1", embed.get("title"));
    assertTrue(embed.get("description").toString().contains("Нужен ваш ответ"));
  }

  @Test
  void limitsNeverCutASurrogatePair() {
    String value = "😀".repeat(2000);
    String clipped = DiscordCards.cap(value, 3799);
    assertTrue(clipped.length() < 3800);
    assertFalse(Character.isHighSurrogate(clipped.charAt(clipped.length() - 1)));
  }
}
