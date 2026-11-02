package me.lovelace.loveWebAdmin.moderation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RestrictionsStoreTest {
  @TempDir Path directory;

  RestrictionsStore.Pending pending(int id, String name, String actor) {
    return new RestrictionsStore.Pending(
        id, name, "uuid", "ip", actor, 100, 400, "PENDING", "2026-11-02");
  }

  @Test
  void restartPreservesBlocksAndRealDeadlines() throws Exception {
    var file = directory.resolve("test.db");
    try (var s = new RestrictionsStore(file)) {
      s.pending(pending(1, "Ada", "Staff"));
    }
    try (var s = new RestrictionsStore(file)) {
      assertEquals(400, s.pending().getFirst().deadline());
      assertFalse(s.transition(1, "EXPIRED", 399));
      assertTrue(s.transition(1, "EXPIRED", 400));
      assertTrue(s.pending().isEmpty());
    }
  }

  @Test
  void expiryAndFinalizationAreMutuallyExclusive() throws Exception {
    try (var s = new RestrictionsStore(directory.resolve("test.db"))) {
      s.pending(pending(1, "Ada", "Staff"));
      assertTrue(s.transition(1, "FINALIZED", 399));
      assertFalse(s.transition(1, "EXPIRED", 500));
      s.pending(pending(2, "Bob", "Staff"));
      assertFalse(s.transition(2, "FINALIZED", 400));
      assertTrue(s.transition(2, "EXPIRED", 400));
      assertFalse(s.transition(2, "FINALIZED", 399));
    }
  }

  @Test
  void duplicateNickDoesNotRestartTheTimer() throws Exception {
    try (var s = new RestrictionsStore(directory.resolve("test.db"))) {
      s.pending(pending(1, "Ada", "Staff"));
      assertThrows(java.sql.SQLException.class, () -> s.pending(pending(2, "aDA", "Other")));
      assertEquals(1, s.pending().size());
      assertEquals("Staff", s.pending().getFirst().actor());
    }
  }

  @Test
  void thirdOverdueIsCountedPerEmployeeAndCalendarDay() throws Exception {
    try (var s = new RestrictionsStore(directory.resolve("test.db"))) {
      for (int id = 1; id <= 3; id++) {
        s.pending(pending(id, "P" + id, "Staff"));
        s.transition(id, "EXPIRED", 400);
      }
      assertEquals(3, s.overdue("Staff", "2026-11-02"));
      assertEquals(0, s.overdue("Other", "2026-11-02"));
      assertEquals(0, s.overdue("Staff", "2026-11-03"));
      assertTrue(s.claimAlert("Staff", "2026-11-02"));
      assertFalse(s.claimAlert("Staff", "2026-11-02"));
    }
  }

  @Test
  void restrictionReplacementsAndRemovalSurviveRestart() throws Exception {
    var file = directory.resolve("test.db");
    try (var s = new RestrictionsStore(file)) {
      s.put(new RestrictionsStore.Restriction("MUTE", "uuid", "Ada", "Staff", "reason", 100, 500));
      s.put(new RestrictionsStore.Restriction("MUTE", "uuid", "Ada", "Staff", "changed", 110, -1));
      s.put(new RestrictionsStore.Restriction("FREEZE", "uuid", "Ada", "Staff", "check", 110, 400));
    }
    try (var s = new RestrictionsStore(file)) {
      assertEquals(2, s.restrictions().size());
      assertTrue(
          s.restrictions().stream()
              .filter(r -> r.kind().equals("MUTE"))
              .findFirst()
              .orElseThrow()
              .active(999999));
      assertTrue(s.remove("MUTE", "uuid", "Staff", "appeal", 120));
      assertFalse(s.remove("MUTE", "uuid", "Staff", "duplicate", 121));
      assertEquals("FREEZE", s.restrictions().getFirst().kind());
    }
  }

  @Test
  void invalidDurationsCannotOverflowOrBecomePermanent() {
    assertEquals(1800, ModerationService.duration("30m"));
    assertEquals(-1, ModerationService.duration("permanent"));
    for (String invalid : java.util.List.of("0m", "-1h", "999999999999999d", "forever", "1x"))
      assertThrows(IllegalArgumentException.class, () -> ModerationService.duration(invalid));
  }
}
