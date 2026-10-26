package me.lovelace.loveWebAdmin.database;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ModerationStoreTest {
  @TempDir Path dir;
  ModerationStore store;

  @BeforeEach
  void setup() throws Exception {
    store = new ModerationStore(dir.resolve("moderation.db"));
  }

  @AfterEach
  void close() throws Exception {
    store.close();
  }

  @Test
  void nicknameNeverAuthorizesAnAppeal() throws Exception {
    String token = store.createAccess(1, null, false);
    assertFalse(store.author(1, "Ada123"));
    assertTrue(store.author(1, token));
    assertFalse(store.author(2, token));
    assertFalse(store.verified(1));
  }

  @Test
  void tokensAreHashedAtRest() throws Exception {
    String token = store.createAccess(1, "1234", true);
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("moderation.db"));
        var s = db.createStatement();
        var r = s.executeQuery("SELECT token_hash FROM appeal_access")) {
      assertTrue(r.next());
      assertNotEquals(token, r.getString(1));
      assertEquals(ModerationStore.hash(token), r.getString(1));
    }
    assertEquals("1234", store.owner(1).orElseThrow());
    assertTrue(store.verified(1));
  }

  @Test
  void simultaneousClaimsHaveOneWinner() throws Exception {
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < 30; i++) {
        String actor = "staff" + i;
        futures.add(pool.submit(() -> store.claim("report", 1, actor)));
      }
      int count = 0;
      for (var f : futures) if (f.get()) count++;
      assertEquals(1, count);
    }
  }

  @Test
  void onlyAssigneeCanTransfer() throws Exception {
    store.claim("appeal", 1, "A");
    assertFalse(store.updateWork("appeal", 1, "B", "B", "HIGH", "WAITING"));
    assertTrue(store.updateWork("appeal", 1, "A", "B", "HIGH", "WAITING"));
    assertEquals("B", store.work("appeal", 1).get("assignee"));
  }

  @Test
  void notesSeparateByKindAndId() throws Exception {
    store.note("report", 1, "A", "Внутренняя заметка");
    assertEquals(1, store.notes("report", 1).size());
    assertTrue(store.notes("appeal", 1).isEmpty());
    assertTrue(store.notes("report", 2).isEmpty());
  }

  @Test
  void rejectsInvalidState() throws Exception {
    store.claim("report", 1, "A");
    assertThrows(
        IllegalArgumentException.class,
        () -> store.updateWork("report", 1, "A", "A", "INVALID", "OPEN"));
  }

  @Test
  void incomingMessageDeduplication() throws Exception {
    assertTrue(store.acceptMessage(1, "123"));
    assertFalse(store.acceptMessage(1, "123"));
    store.forgetMessage("123");
    assertTrue(store.acceptMessage(1, "123"));
  }

  @Test
  void outgoingEventDeduplication() throws Exception {
    store.enqueue("ban:1", "user", "a");
    store.enqueue("ban:1", "user", "b");
    assertEquals(1, store.due().size());
  }

  @Test
  void deliveryCannotBeClaimedTwice() throws Exception {
    store.enqueue("ban:1", "user", "a");
    int id = ((Number) store.due().getFirst().get("id")).intValue();
    assertTrue(store.takeDelivery(id));
    assertFalse(store.takeDelivery(id));
    assertTrue(store.due().isEmpty());
    store.delivered(id, true, 1, null);
    assertEquals("DELIVERED", store.deliveries().getFirst().get("state"));
  }

  @Test
  void interruptedDeliveryRecoveredAfterRestart() throws Exception {
    store.enqueue("ban:1", "user", "a");
    store.takeDelivery(((Number) store.due().getFirst().get("id")).intValue());
    store.close();
    store = new ModerationStore(dir.resolve("moderation.db"));
    assertEquals(1, store.due().size());
  }

  @Test
  void failedDeliveryIsVisible() throws Exception {
    store.enqueue("ban:1", "user", "a");
    int id = ((Number) store.due().getFirst().get("id")).intValue();
    store.takeDelivery(id);
    store.delivered(id, false, 6, "ЛС закрыты");
    assertTrue(store.due().isEmpty());
    assertEquals("FAILED", store.deliveries().getFirst().get("state"));
  }

  @Test
  void retryCannotResendSuccessfulOrRunningDelivery() throws Exception {
    store.enqueue("ban:1", "user", "body");
    int id = ((Number) store.due().getFirst().get("id")).intValue();
    store.takeDelivery(id);
    assertFalse(store.retryDelivery(id));
    store.delivered(id, true, 1, null);
    assertFalse(store.retryDelivery(id));
    store.delivered(id, false, 6, "Ошибка");
    assertTrue(store.retryDelivery(id));
    assertEquals(1, store.due().size());
  }

  @Test
  void evidenceReturnCanBeReservedOnce() throws Exception {
    String id = store.evidence("uuid", "A", new byte[] {1, 2});
    assertTrue(store.evidenceItem(id, "uuid").isEmpty());
    assertTrue(store.evidenceState(id, "PENDING", "HELD"));
    assertArrayEquals(new byte[] {1, 2}, store.evidenceItem(id, "uuid").orElseThrow());
    assertTrue(store.evidenceItem(id, "other").isEmpty());
    assertTrue(store.evidenceState(id, "HELD", "RETURNING"));
    assertFalse(store.evidenceState(id, "HELD", "RETURNING"));
    assertTrue(store.evidenceItem(id, "uuid").isEmpty());
  }

  @Test
  void legacySourceIsNotGuessed() throws Exception {
    assertEquals("UNKNOWN", store.source(1).get("kind"));
    store.source(1, "ANTICHEAT", "Vesuvio", "unique-1");
    assertEquals("Vesuvio", store.source(1).get("actor"));
    assertThrows(SQLException.class, () -> store.source(2, "ANTICHEAT", "Vesuvio", "unique-1"));
  }
}
