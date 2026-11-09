package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.web.handlers.ApiVesuvioHandler;
import org.junit.jupiter.api.*;

class BanReviewQueueTest {
  SupportApiTest f;
  ApiVesuvioHandler handler;
  Map<String, String> staff;

  @BeforeEach
  void setup() throws Exception {
    f = new SupportApiTest();
    f.setup();
    handler = new ApiVesuvioHandler(f.plugin);
    staff =
        f.session(
            "AntiStaff",
            Set.of(
                Permission.VIEW_BANS, Permission.VIEW_VESUVIO_ADVANCED, Permission.MANAGE_VESUVIO));
  }

  @AfterEach
  void close() {
    f.stop();
  }

  WebBan ban(String reason, String status) {
    return f.plugin
        .getDatabaseManager()
        .saveBan(
            new WebBan(
                0,
                "Walker",
                UUID.randomUUID().toString(),
                null,
                "Employee",
                reason,
                "Evidence",
                "[]",
                false,
                status,
                -1,
                1000,
                -1));
  }

  @Test
  void queueContainsOnlyFinalCheatBansAndWorksWithoutAProvider() throws Exception {
    ban("Читы", "ACTIVE");
    ban("Не чит", "ACTIVE");
    ban("Реклама", "ACTIVE");
    ban("Читы", "DRAFT");
    var result = f.call(handler, "GET", "/ban-reviews", Map.of(), null, staff);
    assertEquals(200, result.status);
    var page = f.plugin.getDatabaseManager().banReviews().list(Set.of("Читы"), 0);
    assertEquals(1L, page.get("total"));
    assertFalse(result.body.toString().contains("Реклама"));
    assertFalse(result.body.toString().contains("Не чит"));
  }

  @Test
  void reviewUsesTheAuthenticatedEmployeeAndDoesNotChangeTheBan() throws Exception {
    var ban = ban("Читы", "ACTIVE");
    String body =
        "{\"verdict\":\"legit\",\"reason\":\"Проверена игровая"
            + " сессия\",\"revision\":0,\"reviewer\":\"Fake\"}";
    assertEquals(
        200, f.call(handler, "POST", "/ban-review/" + ban.id(), Map.of(), body, staff).status);
    var page = f.plugin.getDatabaseManager().banReviews().list(Set.of("Читы"), 0);
    var row = (Map<?, ?>) ((List<?>) page.get("items")).getFirst();
    assertEquals("AntiStaff", row.get("reviewer"));
    assertEquals("legit", row.get("verdict"));
    assertEquals(
        "ACTIVE", f.plugin.getDatabaseManager().getBanById(ban.id()).orElseThrow().status());
    assertEquals(
        409, f.call(handler, "POST", "/ban-review/" + ban.id(), Map.of(), body, staff).status);
  }

  @Test
  void deniedRolesAndWrongReasonsCannotSupplyTrainingLabels() throws Exception {
    var viewer =
        f.session(
            "NoBanAccess", Set.of(Permission.MANAGE_VESUVIO, Permission.VIEW_VESUVIO_ADVANCED));
    assertEquals(403, f.call(handler, "GET", "/ban-reviews", Map.of(), null, viewer).status);
    var ban = ban("Реклама", "ACTIVE");
    String body = "{\"verdict\":\"cheat\",\"reason\":\"Проверка основания\",\"revision\":0}";
    assertEquals(
        403, f.call(handler, "POST", "/ban-review/" + ban.id(), Map.of(), body, viewer).status);
    assertEquals(
        409, f.call(handler, "POST", "/ban-review/" + ban.id(), Map.of(), body, staff).status);
  }

  @Test
  void unbanAndCleanReviewQueueDurableTrainingRevocations() throws Exception {
    var first=ban("Читы","ACTIVE");var second=ban("Читы","ACTIVE");
    var database=f.plugin.getDatabaseManager();var store=database.banReviews();
    assertTrue(database.unban(first.id()));
    assertTrue(store.review(second.id(),0,"legit","Reviewer","Проверена запись игры",Set.of("Читы")));
    assertEquals(List.of(first.id(),second.id()),store.pendingTrainingRevocations());
    store.acknowledgeTrainingRevocation(first.id());assertEquals(List.of(second.id()),store.pendingTrainingRevocations());
    assertFalse(database.unban(first.id())); // replay must not manufacture a new delivery
    assertEquals(List.of(second.id()),store.pendingTrainingRevocations());
  }

  @Test
  void trainingManagementRequiresPermissionsAndValidEvidence() throws Exception {
    var viewer=f.session("ReadOnly",Set.of(Permission.VIEW_VESUVIO_ADVANCED));
    assertEquals(403,f.call(handler,"POST","/training/verify",Map.of(),"{}",viewer).status);
    String body="{\"ids\":[1],\"reason\":\"Проверена запись игры\",\"banId\":1}";
    ban("Читы","UNBANNED");
    assertEquals(409,f.call(handler,"POST","/training/verify",Map.of(),body,staff).status);
    assertEquals(400,f.call(handler,"POST","/training/verify",Map.of(),"{\"ids\":[1],\"reason\":\"x\"}",staff).status);
  }

  @Test
  void auditFailureRollsBackAndQueueIsPaged() throws Exception {
    for (int i = 0; i < 31; i++) ban("Читы", "ACTIVE");
    var store = f.plugin.getDatabaseManager().banReviews();
    assertEquals(6, ((List<?>) store.list(Set.of("Читы"), 25).get("items")).size());
    var field = f.plugin.getDatabaseManager().getClass().getDeclaredField("connection");
    field.setAccessible(true);
    var connection = (java.sql.Connection) field.get(f.plugin.getDatabaseManager());
    try (var s = connection.createStatement()) {
      s.execute(
          "CREATE TEMP TRIGGER fail_ban_review_history BEFORE INSERT ON"
              + " anticheat_ban_review_history BEGIN SELECT RAISE(ABORT,'test audit failure');END");
    }
    assertThrows(
        java.sql.SQLException.class,
        () -> store.review(1, 0, "legit", "Staff", "Проверка транзакции", Set.of("Читы")));
    assertTrue(connection.getAutoCommit());
    try (var s = connection.createStatement();
        var r = s.executeQuery("SELECT COUNT(*) FROM anticheat_ban_reviews")) {
      r.next();
      assertEquals(0, r.getInt(1));
    }
  }
}
