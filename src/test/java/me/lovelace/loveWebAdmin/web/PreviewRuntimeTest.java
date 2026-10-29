package me.lovelace.loveWebAdmin.web;

import me.lovelace.loveWebAdmin.preview.PreviewRuntime;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PreviewRuntimeTest {
  @TempDir Path dir;

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return JsonUtils.parseObject(JsonUtils.toJson(value));
  }

  private static Map<String, String> headers() {
    return Map.of();
  }

  @Test
  void reportDecisionPreservesExactAuthorVisibleExplanationOnce() throws Exception {
    try (var runtime = new PreviewRuntime(dir, "report-verdict")) {
      var report = map(runtime.control("report", Map.of()));
      int id = ((Number) report.get("id")).intValue();
      String route = "/api/cases/report/" + id;
      String reason = "Контекст проверен, нарушение подтверждается записью";
      var draft =
          map(
              runtime.execute(
                  "POST",
                  route + "/decision-preview",
                  Map.of("action", "approve", "reason", reason),
                  headers()));
      assertTrue(runtime.database().reportDiscussion(id).isEmpty());
      runtime.execute(
          "POST", route + "/decision-confirm", Map.of("key", draft.get("key")), headers());
      runtime.execute(
          "POST", route + "/decision-confirm", Map.of("key", draft.get("key")), headers());
      assertEquals(1, runtime.database().reportDiscussion(id).size());
      assertEquals(reason, runtime.database().reportDiscussion(id).getFirst().get("body"));
    }
  }

  @Test
  void sandboxRunsDurablePreviewsTemplatesAndClosedCaseReview() throws Exception {
    try (var runtime = new PreviewRuntime(dir, "workflow")) {
      var appeal = map(runtime.control("appeal", Map.of()));
      int id = ((Number) appeal.get("id")).intValue();
      String route = "/api/cases/appeal/" + id;
      assertThrows(
          IllegalArgumentException.class,
          () ->
              runtime.execute(
                  "POST",
                  route + "/decision-preview",
                  Map.of("action", "reject", "reason", "Своё наказание нельзя рассматривать"),
                  headers()));
      runtime.control("role", Map.of("id", 2));
      runtime.execute(
          "POST",
          route + "/reply-template",
          Map.of(
              "name",
              "Мой ответ",
              "purpose",
              "VERDICT",
              "text",
              "Проверенные материалы не меняют решение"),
          headers());
      var view = map(runtime.execute("GET", route + "/workflow", Map.of(), headers()));
      assertTrue(JsonUtils.toJson(view.get("templates")).contains("Мой ответ"));
      var draft =
          map(
              runtime.execute(
                  "POST",
                  route + "/decision-preview",
                  Map.of("action", "reject", "reason", "Исходное решение подтверждено материалами"),
                  headers()));
      assertEquals("PENDING", runtime.database().getAppealById(id).orElseThrow().status());
      runtime.execute(
          "POST", route + "/decision-cancel", Map.of("key", draft.get("key")), headers());
      var cancelled = Map.<String, Object>of("key", draft.get("key"));
      assertThrows(
          IllegalArgumentException.class,
          () -> runtime.execute("POST", route + "/decision-confirm", cancelled, headers()));
      draft =
          map(
              runtime.execute(
                  "POST",
                  route + "/decision-preview",
                  Map.of("action", "reject", "reason", "Исходное решение подтверждено материалами"),
                  headers()));
      var confirmation = Map.<String, Object>of("key", draft.get("key"));
      runtime.execute("POST", route + "/decision-confirm", confirmation, headers());
      runtime.execute("POST", route + "/decision-confirm", confirmation, headers());
      assertEquals("REJECTED", runtime.database().getAppealById(id).orElseThrow().status());
      var author = Map.of("x-appeal-token", appeal.get("accessToken").toString());
      var request =
          map(
              runtime.execute(
                  "POST",
                  "/api/appeals/" + id + "/review",
                  Map.of("text", "Появилась запись с полным контекстом ситуации"),
                  author));
      var child =
          map(
              runtime.execute(
                  "POST",
                  route + "/review-accept",
                  Map.of(
                      "requestId",
                      request.get("id"),
                      "reason",
                      "Запись содержит ранее недоступный контекст"),
                  headers()));
      int childId = ((Number) child.get("id")).intValue();
      assertEquals("REJECTED", runtime.database().getAppealById(id).orElseThrow().status());
      assertEquals("PENDING", runtime.database().getAppealById(childId).orElseThrow().status());
      assertNotNull(runtime.execute("GET", "/api/appeals/" + childId, Map.of(), author));
    }
  }

  @Test
  void isolatedStoresAndRestartKeepData() throws Exception {
    try (var one = new PreviewRuntime(dir.resolve("one"), "one");
        var two = new PreviewRuntime(dir.resolve("two"), "two")) {
      one.execute("POST", "/api/control", Map.of("scenario", "ban"), headers());
      assertEquals(1, ((List<?>) one.snapshot().get("bans")).size());
      assertTrue(((List<?>) two.snapshot().get("bans")).isEmpty());
    }
    try (var restored = new PreviewRuntime(dir.resolve("one"), "one")) {
      assertEquals(1, ((List<?>) restored.snapshot().get("bans")).size());
    }
  }

  @Test
  void serverDecidesVirtualPermissionsAndRejectsRoleInjection() throws Exception {
    try (var runtime = new PreviewRuntime(dir, "permissions")) {
      runtime.control("role", Map.of("id", 3));
      runtime.execute(
          "POST",
          "/api/me/preferences",
          Map.of("roleId", 1, "isOwner", true, "uiPreferences", Map.of("theme", "dark")),
          headers());
      var denied =
          assertThrows(
              PreviewRuntime.Rejected.class,
              () ->
                  runtime.execute("POST", "/api/bans", Map.of("targetName", "Ada123"), headers()));
      assertEquals(403, denied.status);
      assertThrows(
          PreviewRuntime.Rejected.class,
          () -> runtime.execute("GET", "/api/control", Map.of("scenario", "ban"), headers()));
    }
  }

  @Test
  void concurrentSameIntentCreatesOneBan() throws Exception {
    try (var runtime = new PreviewRuntime(dir, "dedup");
        var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<Object>>();
      for (int i = 0; i < 20; i++)
        tasks.add(
            pool.submit(
                () ->
                    runtime.execute(
                        "POST",
                        "/api/bans",
                        Map.of("targetName", "Ada123", "ruleReason", "Тестовая причина"),
                        Map.of("idempotency-key", "same-ban-intent"))));
      for (var task : tasks) assertNotNull(task.get());
      assertEquals(1, ((List<?>) runtime.snapshot().get("bans")).size());
    }
  }

  @Test
  void verifiedAppealUsesSharedDecisionAndCannotBeDecidedTwice() throws Exception {
    try (var runtime = new PreviewRuntime(dir, "appeals")) {
      var appeal = map(runtime.control("appeal", Map.of()));
      int id = ((Number) appeal.get("id")).intValue();
      assertThrows(
          me.lovelace.loveWebAdmin.utils.ActionRejectedException.class,
          () ->
              runtime.execute(
                  "POST",
                  "/api/appeals/" + id + "/approve",
                  Map.of("verdictMessage", "Собственное решение недопустимо"),
                  headers()));
      runtime.control("role", Map.of("id", 2));
      runtime.execute(
          "POST",
          "/api/appeals/" + id + "/approve",
          Map.of("verdictMessage", "Тестовое решение сотрудника"),
          headers());
      assertEquals("APPROVED", runtime.database().getAppealById(id).orElseThrow().status());
      assertEquals(
          "UNBANNED",
          runtime
              .database()
              .getBanById(((Number) appeal.get("banId")).intValue())
              .orElseThrow()
              .status());
      assertThrows(
          RuntimeException.class,
          () ->
              runtime.execute(
                  "POST",
                  "/api/appeals/" + id + "/reject",
                  Map.of("verdictMessage", "Повторное решение невозможно"),
                  headers()));
    }
  }

  @Test
  void authorCapabilityCannotReadInternalCaseOrAnotherAppeal() throws Exception {
    try (var runtime = new PreviewRuntime(dir, "author")) {
      var appeal = map(runtime.control("unverified", Map.of()));
      int id = ((Number) appeal.get("id")).intValue();
      runtime.control("login", Map.of());
      var token = Map.of("x-appeal-token", String.valueOf(appeal.get("accessToken")));
      assertNotNull(runtime.execute("GET", "/api/appeals/" + id, Map.of(), token));
      assertThrows(
          PreviewRuntime.Rejected.class,
          () -> runtime.execute("GET", "/api/cases/appeal/" + id, Map.of(), token));
      assertThrows(
          PreviewRuntime.Rejected.class,
          () -> runtime.execute("GET", "/api/appeals/" + (id + 1), Map.of(), token));
    }
  }

  @Test
  void inventoryFingerprintAndReturnAreSingleUse() throws Exception {
    try (var runtime = new PreviewRuntime(dir, "items")) {
      var inventory =
          map(runtime.execute("GET", "/api/players/Ada123/inventory", Map.of(), headers()));
      @SuppressWarnings("unchecked")
      var items = (List<Map<String, Object>>) inventory.get("storage");
      assertFalse(items.isEmpty());
      var item = items.getFirst();
      var body =
          Map.<String, Object>of(
              "action",
              "remove_item",
              "slot",
              item.get("slot"),
              "fingerprint",
              item.get("fingerprint"),
              "reason",
              "Проверка изъятия предмета");
      runtime.execute("POST", "/api/players/Ada123/action", body, headers());
      assertThrows(
          PreviewRuntime.Rejected.class,
          () -> runtime.execute("POST", "/api/players/Ada123/action", body, headers()));
      @SuppressWarnings("unchecked")
      var held =
          (List<Map<String, Object>>)
              runtime.execute("GET", "/api/players/Ada123/confiscated", Map.of(), headers());
      var restore =
          Map.<String, Object>of("action", "restore_item", "id", held.getFirst().get("id"));
      runtime.execute("POST", "/api/players/Ada123/action", restore, headers());
      assertThrows(
          PreviewRuntime.Rejected.class,
          () -> runtime.execute("POST", "/api/players/Ada123/action", restore, headers()));
    }
  }

  @Test
  void testCentreChecksEveryDeclaredGroupAndMarksLiveDiscordUnverified() throws Exception {
    try (var runtime = new PreviewRuntime(dir, "centre")) {
      @SuppressWarnings("unchecked")
      var results = (List<Map<String, Object>>) runtime.control("run-checks", Map.of());
      assertTrue(results.size() >= 30);
      for (var check : results)
        if (!check.get("mode").equals("LIVE_REQUIRED"))
          assertEquals(true, check.get("passed"), check.get("name") + ": " + check.get("detail"));
      assertTrue(
          results.stream()
              .anyMatch(
                  c ->
                      c.get("mode").equals("LIVE_REQUIRED")
                          && Boolean.FALSE.equals(c.get("passed"))));
      assertTrue(
          ((List<?>) runtime.snapshot().get("bans")).isEmpty(),
          "Проверки не изменяют открытую песочницу");
      assertFalse(JsonUtils.toJson(runtime.snapshot()).contains("_passwordHashes"));
    }
  }

  @Test
  void authorCannotDecideOwnAppealAndMissingCasesRejectWrites() throws Exception {
    try (var runtime = new PreviewRuntime(dir, "decisions")) {
      var appeal = map(runtime.control("unverified", Map.of()));
      int id = ((Number) appeal.get("id")).intValue();
      runtime.control("role", Map.of("id", 3));
      var denied =
          assertThrows(
              PreviewRuntime.Rejected.class,
              () ->
                  runtime.execute(
                      "POST",
                      "/api/appeals/" + id + "/approve",
                      Map.of("verdictMessage", "Не является решением сотрудника"),
                      Map.of("x-appeal-token", appeal.get("accessToken").toString())));
      assertEquals(403, denied.status);
      runtime.control("role", Map.of("id", 1));
      var missing =
          assertThrows(
              PreviewRuntime.Rejected.class,
              () ->
                  runtime.execute(
                      "POST",
                      "/api/cases/report/999/note",
                      Map.of("text", "Дела не существует"),
                      Map.of()));
      assertEquals(404, missing.status);
    }
  }

  @Test
  void serverLoginUsesRealTotpAndSingleUseBackupCodes() throws Exception {
    try (var runtime = new PreviewRuntime(dir, "twofactor")) {
      @SuppressWarnings("unchecked")
      var backup =
          (List<String>)
              map(runtime.execute("POST", "/api/me/backup-codes/regenerate", Map.of(), Map.of()))
                  .get("backupCodes");
      var login = map(runtime.control("login", Map.of()));
      String secret = login.get("totpSecret").toString();
      runtime.execute(
          "POST",
          "/api/auth/login",
          Map.of("username", "PreviewOwner", "password", "PreviewOnly!25"),
          Map.of());
      var key = me.lovelace.loveWebAdmin.utils.TotpUtils.decodeBase32(secret);
      var mac = javax.crypto.Mac.getInstance("HmacSHA1");
      mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA1"));
      byte[] hash =
          mac.doFinal(
              java.nio.ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 30000).array());
      int offset = hash[hash.length - 1] & 15;
      int value =
          ((hash[offset] & 127) << 24)
              | ((hash[offset + 1] & 255) << 16)
              | ((hash[offset + 2] & 255) << 8)
              | (hash[offset + 3] & 255);
      String code = String.format("%06d", value % 1000000);
      assertNotNull(
          runtime.execute(
              "POST",
              "/api/auth/verify-2fa",
              Map.of("username", "PreviewOwner", "challenge", "preview-auth", "code", code),
              Map.of()));
      runtime.execute("POST", "/api/auth/logout", Map.of(), Map.of());
      runtime.execute(
          "POST",
          "/api/auth/login",
          Map.of("username", "PreviewOwner", "password", "PreviewOnly!25"),
          Map.of());
      assertNotNull(
          runtime.execute(
              "POST",
              "/api/auth/verify-2fa",
              Map.of(
                  "username",
                  "PreviewOwner",
                  "challenge",
                  "preview-auth",
                  "code",
                  backup.getFirst()),
              Map.of()));
      runtime.execute("POST", "/api/auth/logout", Map.of(), Map.of());
      runtime.execute(
          "POST",
          "/api/auth/login",
          Map.of("username", "PreviewOwner", "password", "PreviewOnly!25"),
          Map.of());
      assertThrows(
          PreviewRuntime.Rejected.class,
          () ->
              runtime.execute(
                  "POST",
                  "/api/auth/verify-2fa",
                  Map.of(
                      "username",
                      "PreviewOwner",
                      "challenge",
                      "preview-auth",
                      "code",
                      backup.getFirst()),
                  Map.of()));
    }
  }
}
