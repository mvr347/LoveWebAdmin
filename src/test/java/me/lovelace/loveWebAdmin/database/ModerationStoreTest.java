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
  void failedBatchDoesNotLeavePartOfSnapshotReturnable() throws Exception {
    var item = new ModerationStore.Evidence("duplicate", "uuid", "Staff", new byte[] {1});
    assertThrows(SQLException.class, () -> store.evidenceBatch(java.util.List.of(item, item)));
    assertFalse(store.evidenceState("duplicate", "PENDING", "HELD"));
  }

  @Test
  void snapshotRemainsNonReturnableUntilNativeSaveIsConfirmed() throws Exception {
    var item = new ModerationStore.Evidence("entry", "uuid", "Staff", new byte[] {1});
    store.evidenceBatch(java.util.List.of(item));
    assertTrue(store.evidenceItem("entry", "uuid").isEmpty());
    store.holdBatch(java.util.List.of(item));
    assertArrayEquals(new byte[] {1}, store.evidenceItem("entry", "uuid").orElseThrow());
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
  void interruptedDeliveryRequiresManualConfirmationAfterRestart() throws Exception {
    store.enqueue("ban:1", "user", "a");
    int id = ((Number) store.due().getFirst().get("id")).intValue();
    store.takeDelivery(id);
    store.close();
    store = new ModerationStore(dir.resolve("moderation.db"));
    assertTrue(store.due().isEmpty());
    assertEquals("UNCERTAIN", store.deliveries().getFirst().get("state"));
    assertFalse(store.retryDelivery(id));
    assertTrue(
        store.resolveDelivery(id, false, "Staff", "Проверил Discord: сообщение отсутствует"));
    assertFalse(store.resolveDelivery(id, true, "Staff", "Повторное подтверждение невозможно"));
    assertTrue(store.retryDelivery(id));
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
    store.enqueue("ban:2", "user", "a");
    id = ((Number) store.due().getFirst().get("id")).intValue();
    store.takeDelivery(id);
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
  void reasonAndReturnAuthorAreDurable() throws Exception {
    String id = store.evidence("uuid", "Staff", new byte[] {1}, "Проверка жалобы №12");
    assertEquals("Проверка жалобы №12", store.evidenceList("uuid").getFirst().get("reason"));
    store.evidenceState(id, "PENDING", "HELD");
    store.evidenceState(id, "HELD", "RETURNING");
    assertTrue(store.confirmReturn(id, "Reviewer"));
    assertFalse(store.confirmReturn(id, "Other"));
    assertEquals("Reviewer", store.evidenceList("uuid").getFirst().get("returnedBy"));
  }

  @Test
  void quietHoursDoNotConsumeAnAttemptAndRevocationCannotBeRetried() throws Exception {
    store.enqueue("staff:1", "discord", "{}");
    int id = ((Number) store.due().getFirst().get("id")).intValue();
    store.takeDelivery(id);
    store.deferDelivery(id, System.currentTimeMillis() / 1000 + 3600);
    assertTrue(store.due().isEmpty());
    assertEquals(0, store.deliveries().getFirst().get("attempts"));
    store.takeDelivery(id);
    store.skipDelivery(id, "Доступ отозван");
    assertEquals("SKIPPED", store.deliveries().getFirst().get("state"));
    assertFalse(store.retryDelivery(id));
  }

  @Test
  void ticketRepliesAndClosureAreDeliveredInOrder() throws Exception {
    store.enqueue("reply:1:1", "ticket:1", "first");
    store.enqueue("finish:1", "ticket:1", "last");
    store.enqueue("reply:2:1", "ticket:2", "independent");
    var due = store.due();
    assertEquals(2, due.size());
    int first = ((Number) due.getFirst().get("id")).intValue();
    store.takeDelivery(first);
    assertEquals(1, store.due().size());
    store.delivered(first, true, 1, null);
    assertEquals(2, store.due().size());
    assertEquals("last", store.due().getFirst().get("payload"));
  }

  @Test
  void quietStaffMessageDoesNotBlockBanDmToSameUser() throws Exception {
    store.enqueue("staff:1", "discord-id", "staff");
    int first = ((Number) store.due().getFirst().get("id")).intValue();
    store.takeDelivery(first);
    store.deferDelivery(first, System.currentTimeMillis() / 1000 + 3600);
    store.enqueue("ban:1", "discord-id", "ban");
    assertEquals("ban", store.due().getFirst().get("payload"));
  }

  @Test
  void multipartMessageIsEnqueuedAsOneTransaction() throws Exception {
    assertThrows(
        SQLException.class,
        () ->
            store.enqueueBatch(
                List.of(
                    new ModerationStore.Delivery("first", "ticket:1", "first"),
                    new ModerationStore.Delivery("bad", null, "second"))));
    assertTrue(store.due().isEmpty());
  }

  @Test
  void lateCallbackCannotChangeACompletedDelivery() throws Exception {
    store.enqueue("reply:1", "ticket:1", "text");
    int id = ((Number) store.due().getFirst().get("id")).intValue();
    store.takeDelivery(id);
    store.delivered(id, true, 1, null);
    store.delivered(id, false, 6, "Устаревший ответ");
    assertEquals("DELIVERED", store.deliveries().getFirst().get("state"));
    assertFalse(store.retryDelivery(id));
  }

  @Test
  void failedInitializationClosesTheOwnedConnection() {
    var closed = new java.util.concurrent.atomic.AtomicBoolean();
    var connection =
        (java.sql.Connection)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {java.sql.Connection.class},
                (p, m, a) -> {
                  if (m.getName().equals("createStatement"))
                    throw new SQLException("Schema unavailable");
                  if (m.getName().equals("close")) {
                    closed.set(true);
                    return null;
                  }
                  return null;
                });
    assertThrows(SQLException.class, () -> new ModerationStore(connection));
    assertTrue(closed.get());
  }

  @Test
  void legacySourceIsNotGuessed() throws Exception {
    assertEquals("UNKNOWN", store.source(1).get("kind"));
    store.source(1, "ANTICHEAT", "Vesuvio", "unique-1");
    assertEquals("Vesuvio", store.source(1).get("actor"));
    assertThrows(SQLException.class, () -> store.source(2, "ANTICHEAT", "Vesuvio", "unique-1"));
  }

  @org.junit.jupiter.api.Test
  void oneTimePortalAndEvidencePrivacy() throws Exception {
    try (var store =
        new ModerationStore(
            java.nio.file.Files.createTempDirectory("portal-test").resolve("moderation.db"))) {
      String code = store.portalLink("1234567890", null);
      var session = store.exchangePortal(code).orElseThrow();
      org.junit.jupiter.api.Assertions.assertTrue(store.exchangePortal(code).isEmpty());
      org.junit.jupiter.api.Assertions.assertTrue(
          store.portalSession(session.get("token")).isPresent());
      store.portalLogout(session.get("token"));
      org.junit.jupiter.api.Assertions.assertTrue(
          store.portalSession(session.get("token")).isEmpty());
      store.addEvidence(
          "appeal", 1, "Moderator", "TEXT", "Внутренний контекст", "STAFF", null, null);
      store.addEvidence(
          "appeal", 1, "Moderator", "LINK", "https://example.com/evidence", "AUTHOR", null, null);
      org.junit.jupiter.api.Assertions.assertEquals(2, store.evidence("appeal", 1, false).size());
      org.junit.jupiter.api.Assertions.assertEquals(1, store.evidence("appeal", 1, true).size());
      org.junit.jupiter.api.Assertions.assertTrue(store.evidence("appeal", 2, true).isEmpty());
    }
  }

  @org.junit.jupiter.api.Test
  void caseRevisionHasSingleWinner() throws Exception {
    try (var store =
            new ModerationStore(
                java.nio.file.Files.createTempDirectory("revision-test").resolve("moderation.db"));
        var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 40; i++)
        tasks.add(pool.submit(() -> store.reserveRevision("report", 12, 0)));
      int winners = 0;
      for (var task : tasks) if (task.get()) winners++;
      org.junit.jupiter.api.Assertions.assertEquals(1, winners);
      org.junit.jupiter.api.Assertions.assertEquals(1, store.revision("report", 12));
    }
  }
}
