package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscordTemplatesTest {
  @TempDir Path dir;

  @Test
  void valuesAreNotInterpretedAsMoreTemplateCode() {
    assertEquals(
        "{reason} $\\",
        DiscordTemplates.preview("ban", "{player}", Map.of("player", "{reason} $\\")));
  }

  @Test
  void unknownTokensAreRejectedBeforeChangingFile() throws Exception {
    var file = dir.resolve("templates.yml");
    var templates = new DiscordTemplates(file);
    String before = Files.readString(file);
    assertThrows(IllegalArgumentException.class, () -> templates.save("ban", "{password}"));
    assertEquals(before, Files.readString(file));
  }

  @Test
  void editedTemplatePersistsAndPreviousFileIsBackedUp() throws Exception {
    var file = dir.resolve("templates.yml");
    var templates = new DiscordTemplates(file);
    String before = Files.readString(file);
    templates.save("ban", "Игрок: {player}");
    assertEquals("Игрок: Ada", new DiscordTemplates(file).render("ban", Map.of("player", "Ada")));
    assertEquals(before, Files.readString(dir.resolve("templates.yml.bak")));
  }

  @Test
  void longValuesRespectDiscordContentLimitAndSurrogates() {
    String text = DiscordTemplates.preview("ban", "{reason}", Map.of("reason", "😀".repeat(3000)));
    assertTrue(text.length() <= 1900);
    assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 2)));
  }
}
