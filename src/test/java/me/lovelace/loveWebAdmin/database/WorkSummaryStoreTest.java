package me.lovelace.loveWebAdmin.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.workflow.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WorkSummaryStoreTest {
  @TempDir Path folder;
  DatabaseManager database;
  ModerationStore moderation;
  CaseWorkflowStore workflows;

  @BeforeEach
  void start() throws Exception {
    database = new DatabaseManager(folder);
    database.initialize();
    moderation = new ModerationStore(folder.resolve("moderation.db"));
    workflows = new CaseWorkflowStore(folder.resolve("case-workflows.db"));
  }

  @AfterEach
  void stop() throws Exception {
    workflows.close();
    moderation.close();
    database.close();
  }

  int report(String name) {
    return database.createPlayerReport(
        UUID.randomUUID().toString(),
        "Reporter",
        null,
        UUID.randomUUID().toString(),
        name,
        List.of("Причина"),
        "PRIVATE BODY",
        false,
        0);
  }

  @SuppressWarnings("unchecked")
  Map<String, Map<String, Object>> cards(Map<String, Object> result) {
    var map = new HashMap<String, Map<String, Object>>();
    for (var card : (List<Map<String, Object>>) result.get("cards"))
      map.put(card.get("id").toString(), card);
    return map;
  }

  @Test
  void permissionFilteringOwnershipAndPageCountsAreExact() throws Exception {
    for (int i = 0; i < 25; i++) report("Player" + i);
    int mine = report("Mine"), other = report("Other");
    moderation.claim("report", mine, "Alice");
    moderation.claim("report", other, "Bob");
    try (var store = new WorkSummaryStore(folder)) {
      assertTrue(
          cards(
                  store.summary(
                      "Alice",
                      true,
                      Set.of(Permission.VIEW_STATS),
                      1000,
                      CaseWaitPolicy.defaults()))
              .isEmpty());
      var result =
          cards(
              store.summary(
                  "Alice", true, Set.of(Permission.VIEW_REPORTS), 1000, CaseWaitPolicy.defaults()));
      assertEquals(25L, result.get("unassigned").get("count"));
      assertEquals(1L, result.get("reply").get("count"));
      assertFalse(result.toString().contains("PRIVATE BODY"));
      assertEquals(
          2L,
          cards(
                  store.summary(
                      "Alice",
                      false,
                      Set.of(Permission.VIEW_REPORTS),
                      1000,
                      CaseWaitPolicy.defaults()))
              .get("reply")
              .get("count"));
      var first =
          cards(
                  store.summary(
                      "Alice",
                      true,
                      Set.of(Permission.VIEW_REPORTS),
                      1000,
                      CaseWaitPolicy.defaults(),
                      "unassigned",
                      0))
              .get("unassigned");
      var second =
          cards(
                  store.summary(
                      "Alice",
                      true,
                      Set.of(Permission.VIEW_REPORTS),
                      1000,
                      CaseWaitPolicy.defaults(),
                      "unassigned",
                      20))
              .get("unassigned");
      assertEquals(20, ((List<?>) first.get("items")).size());
      assertEquals(20, first.get("nextOffset"));
      assertEquals(5, ((List<?>) second.get("items")).size());
      assertEquals(-1, second.get("nextOffset"));
    }
  }

  @Test
  void waitingAndClosedCasesDoNotBecomeReplyTasks() throws Exception {
    int id = report("Waiting");
    moderation.claim("report", id, "Alice");
    workflows.observe("report", id, true, "WAITING", "", true, 100);
    try (var store = new WorkSummaryStore(folder)) {
      var cards =
          cards(
              store.summary(
                  "Alice",
                  true,
                  Set.of(Permission.VIEW_REPORTS),
                  2000,
                  new CaseWaitPolicy(1, 2, 1)));
      assertFalse(cards.containsKey("reply"));
      assertEquals(1L, cards.get("waiting").get("count"));
      assertEquals(1L, cards.get("overdue").get("count"));
      database.resolveReport(id, "REJECTED", "Alice");
      assertTrue(
          cards(
                  store.summary(
                      "Alice",
                      true,
                      Set.of(Permission.VIEW_REPORTS),
                      2000,
                      CaseWaitPolicy.defaults()))
              .isEmpty());
    }
  }

  @Test
  void snapshotsAreBoundedMonotonicAndSurviveReopening() {
    database.saveServerMetrics("clans", "LoveClans", Map.of("Кланов", 8), 200);
    database.saveServerMetrics("clans", "LoveClans", Map.of("Кланов", 2), 100);
    assertEquals(200L, database.serverMetrics().get("clans").get("observedAt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> database.saveServerMetrics("bad", "test", Map.of("text", "x".repeat(17000)), 300));
    assertFalse(database.serverMetrics().containsKey("bad"));
    database.close();
    database = new DatabaseManager(folder);
    database.initialize();
    assertEquals(
        8,
        ((Number) ((Map<?, ?>) database.serverMetrics().get("clans").get("fields")).get("Кланов"))
            .intValue());
  }
}
