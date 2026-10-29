package me.lovelace.loveWebAdmin.database;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationStoreTest {
  @TempDir Path dir;

  @Test
  void sameOperationIsAdmittedOnceAndResultsSurviveRestart() throws Exception {
    var file = dir.resolve("ops.db");
    try (var store = new OperationStore(file);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var admitted = new AtomicInteger();
      var calls = new java.util.ArrayList<Future<?>>();
      for (int i = 0; i < 100; i++)
        calls.add(
            workers.submit(
                () -> {
                  try {
                    if (store.reserve("staff:1", "operation123", "/ban", "hash") == null)
                      admitted.incrementAndGet();
                  } catch (Exception error) {
                    throw new RuntimeException(error);
                  }
                }));
      for (var call : calls) call.get();
      assertEquals(1, admitted.get());
      store.complete("staff:1", "operation123", 200, "result", "MANAGE_BANS");
      assertThrows(
          IllegalArgumentException.class,
          () -> store.reserve("staff:1", "operation123", "/ban", "changed"));
      assertNull(store.reserve("staff:2", "operation123", "/ban", "hash"));
    }
    try (var store = new OperationStore(file)) {
      assertEquals("result", store.get("staff:1", "operation123").orElseThrow().response());
      assertEquals("MANAGE_BANS", store.get("staff:1", "operation123").orElseThrow().permission());
      assertEquals("UNCERTAIN", store.get("staff:2", "operation123").orElseThrow().state());
      assertEquals("UNCERTAIN", store.reserve("staff:2", "operation123", "/ban", "hash").state());
    }
  }

  @Test
  void limiterDoesNotBlockAnotherStaffMember() throws Exception {
    try (var store = new OperationStore(dir.resolve("ops.db"))) {
      for (int i = 0; i < 60; i++) assertTrue(store.allow("one"));
      assertFalse(store.allow("one"));
      assertTrue(store.allow("two"));
    }
  }

  @Test
  void reconciliationIsOwnedAndCannotOverrideRunningOrCompletedWork() throws Exception {
    try (var store = new OperationStore(dir.resolve("resolve.db"))) {
      store.reserve("staff:1", "operation123", "/ban", "hash");
      assertFalse(store.resolve("staff:1", "operation123", true, "Проверено вручную"));
      store.uncertain("staff:1", "operation123");
      assertFalse(store.resolve("staff:2", "operation123", true, "Проверено вручную"));
      assertThrows(
          IllegalArgumentException.class, () -> store.resolve("staff:1", "operation123", true, ""));
      assertTrue(store.resolve("staff:1", "operation123", true, "Блокировка присутствует"));
      assertFalse(store.resolve("staff:1", "operation123", false, "Поздний повтор"));
      var result = store.reserve("staff:1", "operation123", "/ban", "hash");
      assertEquals("COMPLETE", result.state());
      assertEquals(409, result.status());
      assertTrue(result.response().contains("Блокировка присутствует"));
    }
  }
}
