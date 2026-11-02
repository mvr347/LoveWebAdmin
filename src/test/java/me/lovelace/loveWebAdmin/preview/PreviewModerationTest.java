package me.lovelace.loveWebAdmin.preview;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewModerationTest {
  @TempDir Path root;

  @Test
  void pendingBanExpiresAndCannotBeFinalizedLater() throws Exception {
    try (var r = new PreviewRuntime(root, "moderation")) {
      r.control("role", Map.of("id", 1));
      var result =
          PreviewRuntime.map(
              r.execute(
                  "POST",
                  "/api/moderation",
                  Map.of("action", "ban", "player", "Ada123"),
                  Map.of()));
      int id = ((Number) result.get("id")).intValue();
      assertEquals("DRAFT", r.database().getBanById(id).orElseThrow().status());
      r.control("advance-time", Map.of("seconds", 301));
      assertEquals("EXPIRED", r.database().getBanById(id).orElseThrow().status());
      assertThrows(
          PreviewRuntime.Rejected.class,
          () ->
              r.execute(
                  "POST",
                  "/api/bans",
                  Map.of(
                      "id",
                      id,
                      "targetName",
                      "Ada123",
                      "ruleReason",
                      "Поздно",
                      "durationSeconds",
                      3600),
                  Map.of()));
    }
  }

  @Test
  void finalizationKeepsFirstKickTimeAndMutePersistsAcrossRestart() throws Exception {
    try (var r = new PreviewRuntime(root, "moderation")) {
      r.control("role", Map.of("id", 1));
      var result =
          PreviewRuntime.map(
              r.execute(
                  "POST",
                  "/api/moderation",
                  Map.of("action", "ban", "player", "Ada123"),
                  Map.of()));
      int id = ((Number) result.get("id")).intValue();
      long began = r.database().getBanById(id).orElseThrow().createdAt();
      r.control("advance-time", Map.of("seconds", 120));
      r.execute(
          "POST",
          "/api/bans",
          Map.of(
              "id", id, "targetName", "Ada123", "ruleReason", "Проверка", "durationSeconds", 3600),
          Map.of());
      var ban = r.database().getBanById(id).orElseThrow();
      assertEquals("ACTIVE", ban.status());
      assertEquals(began + 3600, ban.expiresAt());
      r.execute(
          "POST",
          "/api/moderation",
          Map.of("action", "mute", "player", "Ada123", "reason", "Тест", "duration", "1h"),
          Map.of());
    }
    try (var r = new PreviewRuntime(root, "moderation")) {
      assertEquals(1, r.restrictions().store.restrictions().size());
    }
  }
}
