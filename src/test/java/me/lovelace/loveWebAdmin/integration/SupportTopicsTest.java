package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SupportTopicsTest {
  static Map<String, Object> topic(String id, String label, String description) {
    return Map.of("id", id, "label", label, "description", description);
  }

  @Test
  void validTopicsAreKeptInOrder() {
    var parsed =
        SupportTopics.parse(true, List.of(topic("bug", "Баг", "Сломалось"), topic("other", "Другое", "")));
    assertTrue(parsed.enabled());
    assertEquals(List.of("bug", "other"), parsed.topics().stream().map(SupportTopics.Topic::id).toList());
    assertEquals("Другое", parsed.find("other").orElseThrow().label());
    assertTrue(parsed.find("missing").isEmpty());
  }

  @Test
  void switchedOffIsSilentlyDisabled() {
    var parsed = SupportTopics.parse(false, List.of(topic("bug", "Баг", "")));
    assertFalse(parsed.enabled());
    assertEquals("", parsed.problem());
  }

  @Test
  void brokenListsDisableSupportWithAReasonInsteadOfThrowing() {
    assertFalse(SupportTopics.parse(true, List.of()).enabled());
    assertTrue(SupportTopics.parse(true, null).problem().contains("Не заданы"));
    assertTrue(
        SupportTopics.parse(true, List.of(topic("a", "A", ""), topic("a", "B", "")))
            .problem()
            .contains("Повторяется"));
    assertTrue(SupportTopics.parse(true, List.of(topic("Bad Id", "X", ""))).problem().contains("латиницей"));
    assertTrue(SupportTopics.parse(true, List.of(topic("ok", "", ""))).problem().contains("название"));
    assertTrue(
        SupportTopics.parse(true, List.of(topic("ok", "X", "d".repeat(101)))).problem().contains("описание"));
    var tooMany = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 26; i++) tooMany.add(topic("t" + i, "T", ""));
    assertTrue(SupportTopics.parse(true, tooMany).problem().contains("25"));
  }
}
