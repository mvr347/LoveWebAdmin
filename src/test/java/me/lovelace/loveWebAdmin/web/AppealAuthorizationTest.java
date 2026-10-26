package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import me.lovelace.loveWebAdmin.*;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.web.handlers.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class AppealAuthorizationTest {
  LoveWebAdmin plugin;
  int appeal;
  String key;

  @BeforeEach
  void setup() throws Exception {
    MockBukkit.mock();
    MockBukkit.createMockPlugin("LoveCore");
    MockBukkit.createMockPlugin("LoveEssential");
    var config = new YamlConfiguration();
    config.set("web.enabled", false);
    config.set("web.host", "127.0.0.1");
    plugin = MockBukkit.loadWithConfig(LoveWebAdmin.class, config);
    var ban =
        plugin
            .getDatabaseManager()
            .saveBan(
                new WebBan(
                    0,
                    "Ada123",
                    UUID.randomUUID().toString(),
                    null,
                    "Staff",
                    "Причина",
                    "",
                    "[]",
                    false,
                    "ACTIVE",
                    -1,
                    100,
                    -1));
    appeal =
        plugin
            .getDatabaseManager()
            .createAppeal(ban.id(), ban.targetUuid(), ban.targetName(), "Проверьте решение");
    key = plugin.moderation().createAccess(appeal, null, false);
    plugin
        .getDatabaseManager()
        .addAppealMessage(appeal, "Staff", true, "Конфиденциальная переписка");
  }

  @AfterEach
  void stop() {
    MockBukkit.unmock();
    if (plugin != null) {
      assertTrue(plugin.io().isShutdown());
      assertThrows(java.sql.SQLException.class, () -> plugin.moderation().work("appeal", appeal));
    }
  }

  @Test
  void knownNicknameCannotReadConversation() throws Exception {
    var result = get("/" + appeal, Map.of("player", "Ada123"), Map.of());
    assertEquals(403, result.status);
    assertFalse(result.body.toString().contains("Конфиденциальная"));
  }

  @Test
  void bearerAuthorTokenCanReadOwnConversation() throws Exception {
    var result = get("/" + appeal, Map.of(), Map.of("X-Appeal-Token", key));
    assertEquals(200, result.status);
    assertTrue(result.body.toString().contains("Конфиденциальная"));
  }

  @Test
  void tokenForAnotherAppealDoesNotAuthorize() throws Exception {
    String other = plugin.moderation().createAccess(appeal + 100, null, false);
    assertEquals(403, get("/" + appeal, Map.of(), Map.of("X-Appeal-Token", other)).status);
  }

  @Test
  void nicknameLookupRequiresStaffSession() throws Exception {
    assertEquals(401, get("/", Map.of("player", "Ada123"), Map.of()).status);
  }

  @Test
  void shutdownDetachesLogHandlerAfterSchedulerCancellationFailure() throws Exception {
    var field = me.lovelace.loveWebAdmin.managers.LogManager.class.getDeclaredField("logHandler");
    field.setAccessible(true);
    assertNotNull(field.get(plugin.getLogManager()));
    plugin.getServer().getPluginManager().disablePlugin(plugin);
    assertNull(field.get(plugin.getLogManager()));
    assertTrue(plugin.io().isShutdown());
    assertThrows(java.sql.SQLException.class, () -> plugin.moderation().work("appeal", appeal));
  }

  @Test
  void viewingBansDoesNotGrantPrivateAppealAccess() throws Exception {
    var role =
        plugin
            .getRoleManager()
            .createRole("Только баны", "", java.util.Set.of(Permission.VIEW_BANS));
    var admin = new WebAdmin(0, "BanReader", "hash", role.id(), 100, 0);
    plugin.getDatabaseManager().saveAdmin(admin);
    var saved = plugin.getDatabaseManager().getAdminByUsername("BanReader").orElseThrow();
    var session = plugin.getSessionManager().createSession(saved, role);
    assertEquals(
        403,
        get("/" + appeal, Map.of(), Map.of("Authorization", "Bearer " + session.token())).status);
  }

  @Test
  void roleRevocationAppliesToExistingSessionImmediately() throws Exception {
    var allowed =
        plugin
            .getRoleManager()
            .createRole("Апелляции", "", java.util.Set.of(Permission.VIEW_APPEALS));
    var denied =
        plugin
            .getRoleManager()
            .createRole("Без апелляций", "", java.util.Set.of(Permission.VIEW_BANS));
    plugin
        .getDatabaseManager()
        .saveAdmin(new WebAdmin(0, "StaffReader", "hash", allowed.id(), 100, 0));
    var saved = plugin.getDatabaseManager().getAdminByUsername("StaffReader").orElseThrow();
    var session = plugin.getSessionManager().createSession(saved, allowed);
    var headers = Map.of("Authorization", "Bearer " + session.token());
    assertEquals(200, get("/" + appeal, Map.of(), headers).status);
    plugin.getDatabaseManager().updateAdminRole(saved.id(), denied.id());
    assertEquals(403, get("/" + appeal, Map.of(), headers).status);
  }

  @Test
  void retainedPublicApiCannotMutateAfterDisable() {
    var api = plugin.getApi();
    plugin.getServer().getPluginManager().disablePlugin(plugin);
    assertThrows(
        IllegalStateException.class,
        () -> api.banPlayer("Ada123", "Причина", "Staff", "Описание", null, null));
  }

  @Test
  void oldActiveBansAndAppealHistorySurviveCleanup() {
    var db = plugin.getDatabaseManager();
    db.purgeOldBans(Long.MAX_VALUE);
    var ban = db.getAppealById(appeal).orElseThrow().banId();
    assertTrue(db.getBanById(ban).isPresent());
    db.unban(ban);
    db.purgeOldBans(Long.MAX_VALUE);
    db.deleteBan(ban);
    assertTrue(db.getBanById(ban).isPresent());
  }

  @Test
  void appealDecisionAndExplanationAreSavedOnce() {
    var db = plugin.getDatabaseManager();
    assertTrue(db.reserveAppealDecision(appeal));
    assertTrue(
        db.finishAppealDecision(appeal, "REDUCED", "Reviewer", "Срок сокращён с обоснованием"));
    int count = db.getAppealMessages(appeal).size();
    assertFalse(db.finishAppealDecision(appeal, "REJECTED", "Other", "Повторное решение"));
    assertEquals(count, db.getAppealMessages(appeal).size());
    assertEquals("REDUCED", db.getAppealById(appeal).orElseThrow().status());
  }

  @Test
  void mergedReportsCannotBeRewardedOrMergedAgain() {
    var db = plugin.getDatabaseManager();
    String target = UUID.randomUUID().toString();
    int first =
        db.createPlayerReport(
            UUID.randomUUID().toString(),
            "First",
            null,
            target,
            "Target",
            List.of("Причина"),
            "Описание",
            true,
            0);
    int second =
        db.createPlayerReport(
            UUID.randomUUID().toString(),
            "Second",
            null,
            target,
            "Target",
            List.of("Причина"),
            "Описание",
            true,
            0);
    assertTrue(db.mergeReports(second, first, "Reviewer"));
    assertFalse(db.resolveReport(second, "ACCEPTED", "Reviewer", null));
    assertThrows(IllegalStateException.class, () -> db.mergeReports(first, second, "Reviewer"));
    assertEquals("PENDING", db.getReportById(first).orElseThrow().status());
    assertEquals(1, db.relatedReports(first).size());
  }

  @Test
  void reportDiscussionRejectsClosedCasesAndRepeatedDiscordEvents() {
    var db = plugin.getDatabaseManager();
    String target = UUID.randomUUID().toString();
    int id =
        db.createPlayerReport(
            UUID.randomUUID().toString(),
            "First",
            null,
            target,
            "Target",
            List.of("Причина"),
            "Описание",
            true,
            0);
    assertTrue(
        db.addReportDiscussion(id, "First", false, "Уточняю время события", "discord-message-1")
            > 0);
    assertEquals(
        -1,
        db.addReportDiscussion(id, "First", false, "Уточняю время события", "discord-message-1"));
    assertEquals(1, db.reportDiscussion(id).size());
    db.resolveReport(id, "REJECTED", "Reviewer", null);
    assertThrows(
        IllegalArgumentException.class,
        () -> db.addReportDiscussion(id, "First", false, "Ещё сообщение", null));
  }

  @Test
  void analyticsDoesNotInventOnlineWhenMeasurementsAreMissing() {
    var db = plugin.getDatabaseManager();
    assertNull(db.onlineSamples(1, 2).get("peak"));
    long now = System.currentTimeMillis() / 1000;
    db.recordOnlineSample(now - 10, 3);
    db.recordOnlineSample(now, 7);
    assertEquals(7, db.onlineSamples(now - 10, now).get("peak"));
    assertEquals(5.0, db.onlineSamples(now - 10, now).get("average"));
  }

  @Test
  void failedReductionDoesNotPublishADecision() {
    var db = plugin.getDatabaseManager();
    assertThrows(
        IllegalArgumentException.class,
        () -> plugin.appeals().reduce(appeal, "Staff", 0, "Неправильный срок"));
    assertEquals("PENDING", db.getAppealById(appeal).orElseThrow().status());
    assertTrue(
        db.getAppealMessages(appeal).stream()
            .noneMatch(m -> m.message().contains("Срок сокращён")));
  }

  @Test
  void rejectedAppealKeepsTheBanAndCannotBeDecidedTwice() {
    var db = plugin.getDatabaseManager();
    int banId = db.getAppealById(appeal).orElseThrow().banId();
    plugin.appeals().decide(appeal, "Reviewer", false, "Решение остаётся в силе");
    assertEquals("ACTIVE", db.getBanById(banId).orElseThrow().status());
    assertEquals("REJECTED", db.getAppealById(appeal).orElseThrow().status());
    assertThrows(
        IllegalArgumentException.class,
        () -> plugin.appeals().decide(appeal, "Other", true, "Повторное решение"));
  }

  @Test
  void cancellationOfBanEventPreventsAnySavedPunishment() {
    var listener = new org.bukkit.event.Listener() {};
    plugin
        .getServer()
        .getPluginManager()
        .registerEvent(
            me.lovelace.loveWebAdmin.api.events.WebAdminBanEvent.class,
            listener,
            org.bukkit.event.EventPriority.NORMAL,
            (l, e) -> ((me.lovelace.loveWebAdmin.api.events.WebAdminBanEvent) e).setCancelled(true),
            plugin);
    int before = plugin.getDatabaseManager().getAllBans(null).size();
    assertFalse(
        plugin
            .getBanManager()
            .finalizeBan(0, "Staff", "Other", "Причина", "Описание", List.of(), false, 3600));
    assertEquals(before, plugin.getDatabaseManager().getAllBans(null).size());
  }

  @Test
  void responseCannotRaceWithAnAppealDecision() {
    var db = plugin.getDatabaseManager();
    assertTrue(db.reserveAppealDecision(appeal));
    int before = db.getAppealMessages(appeal).size();
    assertEquals(-1, db.addOpenAppealMessage(appeal, "Ada123", false, "Запоздалое сообщение"));
    assertEquals(before, db.getAppealMessages(appeal).size());
  }

  @Test
  void reportViewDoesNotDiscloseTheReportersIp() throws Exception {
    var db = plugin.getDatabaseManager();
    var role =
        plugin.getRoleManager().createRole("Читатель жалоб", "", Set.of(Permission.VIEW_REPORTS));
    db.saveAdmin(new WebAdmin(0, "ReportReader", "hash", role.id(), 100, 0));
    var admin = db.getAdminByUsername("ReportReader").orElseThrow();
    var session = plugin.getSessionManager().createSession(admin, role);
    int id =
        db.createPlayerReport(
            UUID.randomUUID().toString(),
            "Author",
            "198.51.100.42",
            UUID.randomUUID().toString(),
            "Target",
            List.of("Причина"),
            "Описание",
            true,
            0);
    var response =
        get("/" + id, Map.of(), Map.of("Authorization", "Bearer " + session.token()), true);
    assertEquals(200, response.status);
    assertFalse(response.body.toString().contains("198.51.100.42"));
    assertFalse(response.body.toString().contains("reporterIp"));
  }

  private Result get(String path, Map<String, String> params, Map<String, String> headers)
      throws Exception {
    return get(path, params, headers, false);
  }

  private Result get(
      String path, Map<String, String> params, Map<String, String> headers, boolean reports)
      throws Exception {
    var result = new Result();
    HttpServletRequest req =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {HttpServletRequest.class},
                (p, m, a) ->
                    switch (m.getName()) {
                      case "getPathInfo" -> path;
                      case "getParameter" -> params.get(a[0]);
                      case "getHeader" -> headers.get(a[0]);
                      case "getRemoteAddr" -> "127.0.0.1";
                      default -> null;
                    });
    HttpServletResponse resp =
        (HttpServletResponse)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {HttpServletResponse.class},
                (p, m, a) ->
                    switch (m.getName()) {
                      case "getWriter" -> new PrintWriter(result.body);
                      case "setStatus" -> {
                        result.status = (int) a[0];
                        yield null;
                      }
                      case "isCommitted" -> false;
                      default -> null;
                    });
    if (reports) new ExposedReports(plugin).get(req, resp);
    else new Exposed(plugin).get(req, resp);
    return result;
  }

  static class Result {
    int status = 200;
    StringWriter body = new StringWriter();
  }

  static class ExposedReports extends ApiReportsHandler {
    ExposedReports(LoveWebAdmin plugin) {
      super(plugin);
    }

    void get(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      super.doGet(req, resp);
    }
  }

  static class Exposed extends ApiAppealsHandler {
    Exposed(LoveWebAdmin plugin) {
      super(plugin);
    }

    void get(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      super.doGet(req, resp);
    }
  }
}
