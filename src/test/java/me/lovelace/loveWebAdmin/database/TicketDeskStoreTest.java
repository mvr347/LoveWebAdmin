package me.lovelace.loveWebAdmin.database;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TicketDeskStoreTest {
  @TempDir Path dir;

  @Test
  void confirmationIsOwnedAndAdmittedOnce() throws Exception {
    try (var store = new TicketDeskStore(dir.resolve("desk.db"));
        var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      String id = store.draft("10000", "report", Map.of("reason", "Причина жалобы"));
      assertThrows(IllegalArgumentException.class, () -> store.draft(id, "20000"));
      assertFalse(store.consume(id, "20000", false));
      var won = new AtomicInteger();
      var tasks = new ArrayList<Future<?>>();
      for (int i = 0; i < 30; i++)
        tasks.add(
            pool.submit(
                () -> {
                  try {
                    if (store.consume(id, "10000", false)) won.incrementAndGet();
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }));
      for (var task : tasks) task.get();
      assertEquals(1, won.get());
      store.completeDraft(id, 12);
      assertEquals(12, store.draft(id, "10000").caseId());
      assertFalse(store.consume(id, "10000", false));
    }
  }

  @Test
  void uncertainCreationIsNotRetriedAfterRestart() throws Exception {
    String draft;
    try (var store = new TicketDeskStore(dir.resolve("desk.db"))) {
      draft = store.draft("10000", "appeal", Map.of("banId", 12));
      assertTrue(store.consume(draft, "10000", false));
      assertTrue(store.sending("panel", "20000"));
    }
    try (var store = new TicketDeskStore(dir.resolve("desk.db"))) {
      assertEquals("CREATING", store.draft(draft, "10000").state());
      assertFalse(store.consume(draft, "10000", false));
      assertEquals("UNCERTAIN", store.message("panel").orElseThrow().get("state"));
      assertFalse(store.sending("panel", "20000"));
    }
  }

  @Test
  void archivesAndFilesAreScopedAndSurviveRestart() throws Exception {
    try (var store = new TicketDeskStore(dir.resolve("desk.db"))) {
      store.register("report", 1, "10000");
      store.channel("report", 1, "20000");
      store.archiveMessage("report", 1, "100000000000000000", "10000", "Игрок", "Текст");
      store.archiveFile(
          "report",
          1,
          "100000000000000000",
          "https://cdn.discordapp.com/attachments/1/2/a.png",
          "a.png",
          new byte[] {1, 2});
      assertTrue(store.beginClose("report", 1, "Сотрудник", "Вопрос решён"));
      assertFalse(store.beginClose("report", 1, "Другой", "Повторное закрытие"));
      assertFalse(store.transition("report", 1, "ARCHIVED", "CLOSED"));
    }
    try (var store = new TicketDeskStore(dir.resolve("desk.db"))) {
      assertEquals("CLOSING", store.get("report", 1).orElseThrow().state());
      assertEquals("Текст", store.transcript("report", 1, 0).getFirst().get("text"));
      int file = ((Number) store.files("report", 1).getFirst().get("id")).intValue();
      assertArrayEquals(new byte[] {1, 2}, store.file("report", 1, file).orElseThrow());
      assertTrue(store.file("appeal", 1, file).isEmpty());
      assertTrue(store.file("report", 2, file).isEmpty());
    }
  }

  @Test
  void pendingFormsAreBoundedWithoutDiscardingIntents() throws Exception {
    try (var store = new TicketDeskStore(dir.resolve("desk.db"))) {
      for (int i = 0; i < 3; i++) store.draft("10000", "report", Map.of());
      assertThrows(IllegalArgumentException.class, () -> store.draft("10000", "report", Map.of()));
      assertNotNull(store.draft("20000", "report", Map.of()));
    }
  }

  @Test
  void recoveryRequiresAnUncertainResultAndExplanation() throws Exception {
    try (var store = new TicketDeskStore(dir.resolve("recover.db"))) {
      assertTrue(store.sending("panel", "10000"));
      assertFalse(store.resolveMessage("panel", "20000", "Проверено в Discord"));
      store.uncertain("panel", "Сеть прервана");
      assertThrows(
          IllegalArgumentException.class, () -> store.resolveMessage("panel", "20000", ""));
      assertTrue(store.resolveMessage("panel", "20000", "Сообщение найдено в канале"));
      assertFalse(store.resolveMessage("panel", "30000", "Повторная проверка"));
      assertEquals("20000", store.message("panel").orElseThrow().get("message"));
    }
  }

  @Test
  void recoveredClosedQueueIsPublishedAgainWithoutReopeningTheCase() throws Exception {
    try (var store = new TicketDeskStore(dir.resolve("queue.db"))) {
      store.register("report", 1, "10000");
      store.beginClose("report", 1, "Сотрудник", "Рассмотрение завершено");
      store.transition("report", 1, "CLOSING", "ARCHIVED");
      store.transition("report", 1, "ARCHIVED", "CLOSED");
      store.notified("report", 1);
      assertTrue(store.active().isEmpty());
      store.refreshClosedQueue("queue:report:1");
      assertEquals(1, store.active().size());
      assertEquals("CLOSED", store.get("report", 1).orElseThrow().state());
    }
  }
}
