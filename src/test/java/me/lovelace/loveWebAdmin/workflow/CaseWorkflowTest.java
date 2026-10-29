package me.lovelace.loveWebAdmin.workflow;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import me.lovelace.loveWebAdmin.database.*;
import me.lovelace.loveWebAdmin.models.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CaseWorkflowTest {
  @TempDir Path dir;

  @Test
  void waitingTracksEachSideWithoutSpamExtendingTheDeadline() throws Exception {
    try (var store = new CaseWorkflowStore(dir.resolve("flow.db"))) {
      var first = store.observe("report", 1, true, "IN_PROGRESS", "m1", false, 100);
      assertEquals("STAFF", first.mode());
      assertEquals(100, store.observe("report", 1, true, "IN_PROGRESS", "m2", false, 200).since());
      var question = store.observe("report", 1, true, "WAITING", "m3", true, 300);
      assertEquals("PLAYER", question.mode());
      assertEquals(300, question.since());
      var answer = store.observe("report", 1, true, "WAITING", "m4", false, 500);
      assertEquals("STAFF", answer.mode());
      assertEquals(500, answer.since());
      assertEquals(500, store.observe("report", 1, true, "WAITING", "m4", false, 900).since());
      assertTrue(store.advance("report", 1, answer.version(), 1));
      assertFalse(store.advance("report", 1, question.version(), 2));
      assertEquals("NONE", store.observe("report", 1, false, "WAITING", "m4", false, 1000).mode());
    }
  }

  @Test
  void templatesRemainPrivateAndDeletedDefaultsAreNotRestored() throws Exception {
    var file = dir.resolve("flow.db");
    try (var store = new CaseWorkflowStore(file)) {
      var global = store.templates("Alice");
      assertEquals(4, global.size());
      for (var template : global)
        assertTrue(store.removeTemplate("*", ((Number) template.get("id")).intValue(), 0));
      store.template("Alice", 0, 0, "CLARIFY", "Личный", "Уточните дату ситуации");
      assertEquals(1, store.templates("Alice").size());
      assertTrue(store.templates("Bob").isEmpty());
      int id = ((Number) store.templates("Alice").getFirst().get("id")).intValue();
      assertThrows(
          IllegalArgumentException.class,
          () -> store.template("Bob", id, 0, "CLARIFY", "Чужой", "Нельзя изменить чужой шаблон"));
      store.template("Alice", id, 0, "CLARIFY", "Исправлен", "Уточните место ситуации");
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.template(
                  "Alice", id, 0, "CLARIFY", "Устарел", "Старый черновик не заменяет новый"));
    }
    try (var store = new CaseWorkflowStore(file)) {
      assertEquals(1, store.templates("Alice").size());
      assertTrue(store.templates("Bob").isEmpty());
    }
  }

  @Test
  void previewDoesNotMutateAndConfirmationIsOwnedAndExactlyOnce() throws Exception {
    try (var fixture = new Fixture(dir)) {
      var preview =
          fixture.engine.preview(
              "appeal",
              fixture.appeal,
              "staff:1",
              "Reviewer",
              "approve",
              "Наказание применено ошибочно",
              0);
      String key = preview.get("key").toString();
      assertEquals(0, fixture.calls.get());
      assertEquals("ACTIVE", fixture.db.getBanById(fixture.ban).orElseThrow().status());
      assertThrows(
          IllegalArgumentException.class, () -> fixture.engine.confirm(key, "staff:2", "Reviewer"));
      try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        var tasks = new ArrayList<Future<?>>();
        for (int i = 0; i < 20; i++)
          tasks.add(
              pool.submit(
                  () -> {
                    try {
                      fixture.engine.confirm(key, "staff:1", "Reviewer");
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  }));
        for (var task : tasks) task.get();
      }
      assertEquals(1, fixture.calls.get());
      assertEquals("UNBANNED", fixture.db.getBanById(fixture.ban).orElseThrow().status());
    }
  }

  @Test
  void stalePunishmentAndChangedAssignmentRequireANewPreview() throws Exception {
    try (var f = new Fixture(dir)) {
      var p =
          f.engine.preview(
              "appeal",
              f.appeal,
              "staff:1",
              "Reviewer",
              "reject",
              "Обоснование решения сотрудника",
              0);
      f.mod.claim("appeal", f.appeal, "Other");
      assertThrows(
          IllegalArgumentException.class,
          () -> f.engine.confirm(p.get("key").toString(), "staff:1", "Reviewer"));
      assertEquals(0, f.calls.get());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.engine.preview(
                  "appeal",
                  f.appeal,
                  "staff:3",
                  "Issuer",
                  "reject",
                  "Нельзя рассматривать своё наказание",
                  0));
    }
  }

  @Test
  void expiryPreviewKeepsTheChosenDateDespiteConfirmationDelay() throws Exception {
    try (var f = new Fixture(dir)) {
      var preview =
          f.engine.preview(
              "appeal", f.appeal, "staff:1", "Reviewer", "reduce", "Срок наказания уменьшаем", 600);
      long expiry = ((Number) preview.get("targetExpiry")).longValue();
      f.now.addAndGet(30);
      f.engine.confirm(preview.get("key").toString(), "staff:1", "Reviewer");
      assertEquals(expiry, f.now.get() + f.seconds.get());
    }
  }

  @Test
  void approvedNewEvidenceCreatesAChildAndPreservesTheOldVerdictAndCapability() throws Exception {
    try (var f = new Fixture(dir)) {
      f.db.updateAppealStatus(f.appeal, "REJECTED");
      int request =
          f.engine.requestReview(
              "appeal",
              f.appeal,
              "cap:original",
              Map.of("text", "Новое видео меняет контекст ситуации"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.engine.requestReview(
                  "appeal",
                  f.appeal,
                  "cap:original",
                  Map.of("text", "Повторная заявка не создаёт дубль")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.engine.review(
                  "appeal",
                  f.appeal,
                  request,
                  "Issuer",
                  true,
                  "Нельзя рассматривать своё наказание"));
      var result =
          f.engine.review(
              "appeal", f.appeal, request, "Reviewer", true, "Новые доказательства нужно изучить");
      int child = ((Number) result.get("id")).intValue();
      assertNotEquals(f.appeal, child);
      assertEquals("REJECTED", f.db.getAppealById(f.appeal).orElseThrow().status());
      assertEquals("PENDING", f.db.getAppealById(child).orElseThrow().status());
      assertEquals("ACTIVE", f.db.getBanById(f.ban).orElseThrow().status());
      assertTrue(f.mod.author(child, f.token));
      assertFalse(f.mod.author(child, "other-token"));
      assertEquals(f.appeal, f.engine.store().parent("appeal", child).orElseThrow().get("id"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              f.engine.review(
                  "appeal",
                  f.appeal,
                  request,
                  "Reviewer",
                  true,
                  "Повторное принятие не создаёт дубль"));
    }
  }

  @Test
  void rejectedNewEvidenceDoesNotChangeTheOriginalCase() throws Exception {
    try (var f = new Fixture(dir)) {
      f.db.updateAppealStatus(f.appeal, "REJECTED");
      int request =
          f.engine.requestReview(
              "appeal", f.appeal, "discord:10000", Map.of("text", "Новые пояснения к ситуации"));
      f.engine.review(
          "appeal", f.appeal, request, "Reviewer", false, "Материалы не относятся к ситуации");
      assertEquals(1, f.db.getAllAppeals(null, 200, 0).size());
      assertEquals("REJECTED", f.db.getAppealById(f.appeal).orElseThrow().status());
      assertEquals("REJECTED", f.engine.publicReviews("appeal", f.appeal).getFirst().get("state"));
      assertFalse(f.engine.publicReviews("appeal", f.appeal).getFirst().containsKey("actor"));
    }
  }

  @Test
  void reminderVersionsAndWaitingSurviveRestartWithoutNativeActions() throws Exception {
    try (var f = new Fixture(dir)) {
      f.engine.waiting("appeal", f.appeal);
      f.now.addAndGet(1201);
      var due = f.engine.due().getFirst();
      assertEquals(1, due.stage());
      assertEquals(0, f.calls.get());
      assertTrue(f.engine.reminderCurrent("appeal", f.appeal, due.version(), "STAFF"));
      f.mod.claim("appeal", f.appeal, "Reviewer");
      f.mod.updateWork("appeal", f.appeal, "Reviewer", "Reviewer", "NORMAL", "WAITING");
      f.engine.waiting("appeal", f.appeal);
      assertFalse(f.engine.reminderCurrent("appeal", f.appeal, due.version(), "STAFF"));
      assertEquals("PENDING", f.db.getAppealById(f.appeal).orElseThrow().status());
    }
  }

  static class Fixture implements AutoCloseable {
    final DatabaseManager db;
    final ModerationStore mod;
    final CaseWorkflowEngine engine;
    final AtomicLong now = new AtomicLong(100000), seconds = new AtomicLong();
    final AtomicInteger calls = new AtomicInteger();
    final int ban, appeal;
    final String token;

    Fixture(Path dir) throws Exception {
      db = new DatabaseManager(dir);
      db.initialize();
      mod = new ModerationStore(dir.resolve("moderation.db"));
      ban =
          db.saveBan(
                  new WebBan(
                      0,
                      "Player",
                      UUID.randomUUID().toString(),
                      null,
                      "Issuer",
                      "Причина",
                      "",
                      "[]",
                      false,
                      "ACTIVE",
                      3600,
                      now.get(),
                      now.get() + 3600))
              .id();
      mod.source(ban, "STAFF", "Issuer", null);
      appeal =
          db.createAppeal(
              ban,
              db.getBanById(ban).orElseThrow().targetUuid(),
              "Player",
              "Прошу пересмотреть наказание");
      token = mod.createAccess(appeal, "10000", true);
      engine =
          new CaseWorkflowEngine(
              db,
              mod,
              dir.resolve("flows.db"),
              CaseWaitPolicy.defaults(),
              (kind, id, action, staff, reason, left) -> {
                calls.incrementAndGet();
                seconds.set(left);
                if (action.equals("approve")) db.unban(ban);
                return "Применено";
              },
              (kind, id) -> {},
              now::get);
    }

    public void close() throws Exception {
      engine.close();
      mod.close();
      db.close();
    }
  }
}
