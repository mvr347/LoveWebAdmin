package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DiscordConversationTest {
  @Test
  void longMessagesKeepEveryCharacterWithoutSplittingEmoji() {
    String text = "Привет! " + "😀".repeat(2000) + " Конец сообщения";
    var parts = LoveCoreDiscordBridge.conversationParts(text);
    assertEquals(text, String.join("", parts));
    assertTrue(parts.size() > 1);
    for (String part : parts) {
      assertTrue(part.length() <= 1900);
      assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
      assertFalse(Character.isLowSurrogate(part.charAt(0)));
    }
  }
}
