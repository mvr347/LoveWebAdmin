package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import me.lovelace.loveWebAdmin.web.handlers.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class PanelIsolationTest {
  LoveWebAdmin plugin;
  String requestMethod = "GET", requestBody = "";

  @BeforeEach
  void setup() {
    MockBukkit.mock();
    MockBukkit.createMockPlugin("LoveCore");
    MockBukkit.createMockPlugin("LoveEssential");
    var config = new YamlConfiguration();
    config.set("web.enabled", false);
    config.set("web.host", "127.0.0.1");
    config.set("preview.enabled", true);
    plugin = MockBukkit.loadWithConfig(LoveWebAdmin.class, config);
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  private WebSession account(String name, Set<Permission> permissions) {
    var role = plugin.getRoleManager().createRole(name, "", permissions);
    plugin.getDatabaseManager().saveAdmin(new WebAdmin(0, name, "hash", role.id(), 100, 0));
    return plugin
        .getSessionManager()
        .createSession(plugin.getDatabaseManager().getAdminByUsername(name).orElseThrow(), role);
  }

  private Map<String,String> confirmed(WebSession actor,int offset) throws Exception {
    var db=plugin.getDatabaseManager();var admin=db.getAdminById(actor.adminId()).orElseThrow();
    String secret=admin.totpSecret();
    if(secret==null){secret=me.lovelace.loveWebAdmin.utils.TotpUtils.generateSecret();db.saveAdmin(new WebAdmin(admin.id(),admin.username(),admin.passwordHash(),admin.roleId(),admin.createdAt(),admin.lastLoginAt(),secret,true,1,"127.0.0.1",admin.status(),admin.uiPreferences(),admin.backupCodes()));}
    var decode=me.lovelace.loveWebAdmin.utils.TotpUtils.class.getDeclaredMethod("decodeBase32",String.class);decode.setAccessible(true);
    var generate=me.lovelace.loveWebAdmin.utils.TotpUtils.class.getDeclaredMethod("generateCodeForStep",byte[].class,long.class);generate.setAccessible(true);
    String code=String.format("%06d",generate.invoke(null,decode.invoke(null,secret),System.currentTimeMillis()/30000+offset));
    return Map.of("Authorization","Bearer "+actor.token(),"X-LWA-2FA",code);
  }

  @Test
  void integrationOnlyRoleCanReadAndSaveChannelsButCannotChangePanelAddress() throws Exception {
    var actor = account("OnlyIntegrations", Set.of(Permission.MANAGE_INTEGRATIONS));
    Map<String,String> headers = Map.of("Authorization", "Bearer " + actor.token());
    var handler = new ApiServerOpsHandler(plugin);
    var get = call(handler, "/api/server", "/config", headers);
    assertEquals(200, get.status);
    assertTrue(get.body.toString().contains("discordPanelChannelId"));
    assertFalse(get.body.toString().contains("webPort"));
    requestMethod = "POST";
    headers = confirmed(actor, -1);
    requestBody = "{\"discordPanelChannelId\":\"123456789012345678\"}";
    var saved = call(handler, "/api/server", "/config", headers);
    assertEquals(200, saved.status);
    assertEquals(
        "123456789012345678", plugin.getConfig().getString("discord.tickets.panel-channel-id"));
    assertTrue(saved.body.toString().contains("restartRequiredFields"));
    headers = confirmed(actor, 0);
    requestBody = "{\"webPort\":9090}";
    assertEquals(403, call(handler, "/api/server", "/config", headers).status);
  }

  @Test
  void invalidSettingsBatchLeavesBothFileAndLiveValuesUntouched() throws Exception {
    var actor =
        account(
            "BothSettings", Set.of(Permission.MANAGE_PANEL_SETTINGS, Permission.MANAGE_PREVIEW));
    var file = plugin.getDataFolder().toPath().resolve("config.yml");
    String before = java.nio.file.Files.readString(file);
    int port = plugin.getConfig().getInt("web.port");
    requestMethod = "POST";
    requestBody =
        "{\"webPort\":9090,\"previewCategoryId\":\"123456789\",\"previewChannelId\":\"123456789\"}";
    assertEquals(
        400,
        call(
                new ApiServerOpsHandler(plugin),
                "/api/server",
                "/config",
                confirmed(actor, 0))
            .status);
    assertEquals(before, java.nio.file.Files.readString(file));
    assertEquals(port, plugin.getConfig().getInt("web.port"));
  }

  @Test
  void removedWebhooksCannotBeConfigured() throws Exception {
    var actor = account("WebhookSettings", Set.of(Permission.MANAGE_INTEGRATIONS));
    var headers = Map.of("Authorization", "Bearer " + actor.token());
    var handler = new ApiServerOpsHandler(plugin);
    requestMethod = "POST";
    for (String body :
        List.of(
            "{\"webhooksEnabled\":true}",
            "{\"discordWebhookUrl\":\"https://discord.com/api/webhooks/123456789/test-secret\"}",
            "{\"clearDiscordWebhookUrl\":true}")) {
      requestBody = body;
      assertTrue(
          Set.of(400, 403).contains(call(handler, "/api/server", "/config", headers).status));
    }
    assertFalse(plugin.getConfig().getBoolean("security.webhooks.enabled"));
  }

  @Test
  void panelDiagnosticAndPublishingRequireIntegrationRights() throws Exception {
    var staff = account("OnlyCaseRights", Set.of(Permission.MANAGE_REPORTS));
    var headers = Map.of("Authorization", "Bearer " + staff.token());
    var handler = new ApiCasesHandler(plugin);
    assertEquals(403, call(handler, "/api/cases", "/discord-panel", headers).status);
    requestMethod = "POST";
    requestBody = "{}";
    assertEquals(403, call(handler, "/api/cases", "/discord-panel/publish", headers).status);
    var manager = account("PanelIntegration", Set.of(Permission.MANAGE_INTEGRATIONS));
    headers = Map.of("Authorization", "Bearer " + manager.token());
    requestMethod = "GET";
    var status = call(handler, "/api/cases", "/discord-panel", headers);
    assertEquals(200, status.status);
    assertTrue(status.body.toString().contains("NOT_CONFIGURED"));
    requestMethod = "POST";
    assertEquals(409, call(handler, "/api/cases", "/discord-panel/publish", headers).status);
    assertTrue(plugin.getDiscordBridge().ticketDeskStore().messages().isEmpty());
  }

  @Test
  void previewDiscordApiRunsModelsButDoesNotPretendToDeliverWithoutConfiguration()
      throws Exception {
    var owner = account("PreviewLabOwner", Set.of(Permission.MANAGE_PREVIEW));
    var created = plugin.preview().create(owner.adminId());
    String id = created.get("id").toString();
    var headers = Map.of("X-Preview-Session", created.get("token").toString());
    var handler = new ApiPreviewHandler(plugin);
    var status = call(handler, "/preview/api", "/" + id + "/discord-lab", headers);
    assertEquals(200, status.status);
    assertTrue(status.body.toString().contains("\"configured\":false"));
    requestMethod = "POST";
    requestBody = "{\"action\":\"model\"}";
    var models = call(handler, "/preview/api", "/" + id + "/discord-lab/run", headers);
    assertEquals(200, models.status);
    assertTrue(models.body.toString().contains("TRANSPORT_MODEL"));
    assertFalse(models.body.toString().contains("\"passed\":false"));
    requestBody = "{\"action\":\"panel\"}";
    assertEquals(400, call(handler, "/preview/api", "/" + id + "/discord-lab/run", headers).status);
    assertTrue(plugin.getDiscordBridge().ticketDeskStore().messages().isEmpty());
  }

  @Test
  void managingStaffDoesNotGrantPanelSettings() throws Exception {
    var staff = account("OnlyStaff", Set.of(Permission.MANAGE_ADMINS));
    assertEquals(
        403,
        call(
                new ApiServerOpsHandler(plugin),
                "/api/server",
                "/config",
                Map.of("Authorization", "Bearer " + staff.token()))
            .status);
  }

  @Test
  void integrationSecretsAreMasked() throws Exception {
    plugin
        .getConfig()
        .set(
            "security.webhooks.discord.webhook-url",
            "https://discord.com/api/webhooks/123/top-secret-token");
    plugin.getConfig().set("security.webhooks.telegram.bot-token", "secret-telegram-token");
    var staff =
        account(
            "Integrations",
            Set.of(Permission.VIEW_SERVER_SETTINGS, Permission.MANAGE_INTEGRATIONS));
    var response =
        call(
            new ApiServerOpsHandler(plugin),
            "/api/server",
            "/config",
            Map.of("Authorization", "Bearer " + staff.token()));
    assertEquals(200, response.status);
    assertFalse(response.body.toString().contains("top-secret-token"));
    assertFalse(response.body.toString().contains("secret-telegram-token"));
    assertFalse(response.body.toString().contains("discordWebhookConfigured"));
  }

  @Test
  void liveBearerCannotAuthenticateSandboxAndRevocationTakesEffect() throws Exception {
    var staff = account("Tester", Set.of(Permission.MANAGE_PREVIEW));
    var created = plugin.preview().create(staff.adminId());
    String id = created.get("id").toString(), secret = created.get("token").toString();
    var handler = new ApiPreviewHandler(plugin);
    assertEquals(
        401,
        call(
                handler,
                "/preview/api",
                "/" + id + "/snapshot",
                Map.of("Authorization", "Bearer " + staff.token()))
            .status);
    assertEquals(
        200,
        call(handler, "/preview/api", "/" + id + "/snapshot", Map.of("X-Preview-Session", secret))
            .status);
    var admin = plugin.getDatabaseManager().getAdminById(staff.adminId()).orElseThrow();
    var role = plugin.getRoleManager().createRole("NoPreview", "", Set.of(Permission.VIEW_STATS));
    plugin.getDatabaseManager().updateAdminRole(admin.id(), role.id());
    assertEquals(
        403,
        call(handler, "/preview/api", "/" + id + "/snapshot", Map.of("X-Preview-Session", secret))
            .status);
    assertTrue(plugin.getDatabaseManager().getAllBans(null).isEmpty());
  }

  @Test
  void sandboxCreationIsIdempotentWithoutPersistingItsSecret() throws Exception {
    var staff = account("IdempotentTester", Set.of(Permission.MANAGE_PREVIEW));
    requestMethod = "POST";
    requestBody = "{}";
    var headers =
        Map.of(
            "Authorization", "Bearer " + staff.token(), "Idempotency-Key", "preview-create-unique");
    var first = call(new ApiPreviewHandler(plugin), "/api/preview-sessions", null, headers);
    assertEquals(200, first.status);
    @SuppressWarnings("unchecked")
    var data = (Map<String, Object>) JsonUtils.parseObject(first.body.toString()).get("data");
    String secret = data.get("token").toString(), id = data.get("id").toString();
    var operation =
        plugin.operations().get("staff:" + staff.adminId(), "preview-create-unique").orElseThrow();
    assertFalse(operation.response().contains(secret));
    var repeated = call(new ApiPreviewHandler(plugin), "/api/preview-sessions", null, headers);
    assertEquals(409, repeated.status);
    assertFalse(repeated.body.toString().contains(secret));
    assertEquals(1, plugin.preview().list(staff.adminId()).size());
    plugin.preview().removeOwned(id, staff.adminId());
    assertFalse(
        java.nio.file.Files.exists(plugin.getDataFolder().toPath().resolve("preview").resolve(id)));
  }

  @Test
  void ticketTranscriptsNeedCasePermissionsAndFilesCannotCrossCases() throws Exception {
    var db = plugin.getDatabaseManager();
    int first =
        db.createPlayerReport(
            "author", "Author", "", "target", "Target", List.of(), "Описание", false, 0);
    int second =
        db.createPlayerReport(
            "author2", "Author2", "", "target", "Target", List.of(), "Описание", false, 0);
    var archive = plugin.getDiscordBridge().ticketDeskStore();
    archive.register("report", first, "10000");
    archive.archiveMessage(
        "report", first, "100000000000000000", "10000", "Игрок", "Личная переписка");
    archive.archiveFile(
        "report",
        first,
        "100000000000000000",
        "https://cdn.discordapp.com/attachments/1/2/proof.png",
        "proof.png",
        new byte[] {1, 2});
    var viewer = account("CaseReader", Set.of(Permission.VIEW_REPORTS));
    var noRights = account("PlayerReader", Set.of(Permission.VIEW_PLAYERS));
    var handler = new ApiCasesHandler(plugin);
    assertEquals(
        403,
        call(
                handler,
                "/api/cases",
                "/report/" + first + "/transcript",
                Map.of("Authorization", "Bearer " + noRights.token()))
            .status);
    var allowed =
        call(
            handler,
            "/api/cases",
            "/report/" + first + "/transcript",
            Map.of("Authorization", "Bearer " + viewer.token()));
    assertEquals(200, allowed.status);
    assertTrue(allowed.body.toString().contains("Личная переписка"));
    int file = ((Number) archive.files("report", first).getFirst().get("id")).intValue();
    assertEquals(
        404,
        call(
                handler,
                "/api/cases",
                "/report/" + second + "/transcript/" + file,
                Map.of("Authorization", "Bearer " + viewer.token()))
            .status);
  }

  @Test
  void messageRecoveryRequiresIntegrationRights() throws Exception {
    var archive = plugin.getDiscordBridge().ticketDeskStore();
    archive.sending("panel:10000", "10000");
    archive.uncertain("panel:10000", "Сеть недоступна");
    var staffOnly = account("StaffOnlyDesk", Set.of(Permission.MANAGE_ADMINS));
    var integrations = account("DeskRecovery", Set.of(Permission.MANAGE_INTEGRATIONS));
    requestMethod = "POST";
    requestBody =
        "{\"slot\":\"panel:10000\",\"messageId\":\"20000\",\"explanation\":\"Сообщение найдено в"
            + " канале\"}";
    var handler = new ApiCasesHandler(plugin);
    assertEquals(
        403,
        call(
                handler,
                "/api/cases",
                "/discord-messages/resolve",
                Map.of("Authorization", "Bearer " + staffOnly.token()))
            .status);
    assertEquals(
        200,
        call(
                handler,
                "/api/cases",
                "/discord-messages/resolve",
                Map.of("Authorization", "Bearer " + integrations.token()))
            .status);
    assertEquals("20000", archive.message("panel:10000").orElseThrow().get("message"));
    assertEquals(
        409,
        call(
                handler,
                "/api/cases",
                "/discord-messages/resolve",
                Map.of("Authorization", "Bearer " + integrations.token()))
            .status);
  }

  @Test
  void appealManagementAloneCannotLiftAPunishment() throws Exception {
    var reviewer = account("Reviewer", Set.of(Permission.MANAGE_APPEALS));
    var ban =
        plugin
            .getDatabaseManager()
            .saveBan(
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
                    -1,
                    100,
                    -1));
    int appeal =
        plugin
            .getDatabaseManager()
            .createAppeal(ban.id(), ban.targetUuid(), ban.targetName(), "Проверьте наказание");
    requestMethod = "POST";
    requestBody = "{\"verdictMessage\":\"Обоснование решения\"}";
    assertEquals(
        403,
        call(
                new ApiAppealsHandler(plugin),
                "/api/appeals",
                "/" + appeal + "/approve",
                Map.of("Authorization", "Bearer " + reviewer.token()))
            .status);
    assertEquals("ACTIVE", plugin.getDatabaseManager().getBanById(ban.id()).orElseThrow().status());
  }

  @Test
  void decisionPreviewChecksRightsAndDoesNotApplyBeforeConfirmation() throws Exception {
    var reviewer = account("FlowReviewer", Set.of(Permission.MANAGE_APPEALS));
    var other = account("OtherFlowReviewer", Set.of(Permission.MANAGE_APPEALS));
    var ban =
        plugin
            .getDatabaseManager()
            .saveBan(
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
                    -1,
                    100,
                    -1));
    int appeal =
        plugin
            .getDatabaseManager()
            .createAppeal(ban.id(), ban.targetUuid(), ban.targetName(), "Прошу проверить решение");
    requestMethod = "POST";
    requestBody = "{\"action\":\"approve\",\"reason\":\"Уточнённое решение сотрудника\"}";
    var handler = new ApiCasesHandler(plugin);
    var headers = Map.of("Authorization", "Bearer " + reviewer.token());
    assertEquals(
        403,
        call(handler, "/api/cases", "/appeal/" + appeal + "/decision-preview", headers).status);
    assertEquals("ACTIVE", plugin.getDatabaseManager().getBanById(ban.id()).orElseThrow().status());
    requestBody = "{\"action\":\"reject\",\"reason\":\"Материалов для пересмотра недостаточно\"}";
    var response = call(handler, "/api/cases", "/appeal/" + appeal + "/decision-preview", headers);
    assertEquals(200, response.status);
    @SuppressWarnings("unchecked")
    var data = (Map<String, Object>) JsonUtils.parseObject(response.body.toString()).get("data");
    assertEquals(
        "PENDING", plugin.getDatabaseManager().getAppealById(appeal).orElseThrow().status());
    requestBody = JsonUtils.toJson(Map.of("key", data.get("key")));
    assertEquals(
        400,
        call(
                handler,
                "/api/cases",
                "/appeal/" + appeal + "/decision-confirm",
                Map.of("Authorization", "Bearer " + other.token()))
            .status);
    assertEquals(
        200,
        call(handler, "/api/cases", "/appeal/" + appeal + "/decision-confirm", headers).status);
    assertEquals(
        "REJECTED", plugin.getDatabaseManager().getAppealById(appeal).orElseThrow().status());
    assertEquals("ACTIVE", plugin.getDatabaseManager().getBanById(ban.id()).orElseThrow().status());
  }

  @Test
  void newEvidenceRequiresTheOriginalAuthorCapability() throws Exception {
    var ban =
        plugin
            .getDatabaseManager()
            .saveBan(
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
                    -1,
                    100,
                    -1));
    int appeal =
        plugin
            .getDatabaseManager()
            .createAppeal(ban.id(), ban.targetUuid(), ban.targetName(), "Прошу проверить решение");
    String cap = plugin.moderation().createAccess(appeal, null, false);
    plugin.getDatabaseManager().updateAppealStatus(appeal, "REJECTED");
    requestMethod = "POST";
    requestBody = "{\"text\":\"Новые доказательства изменяют контекст\"}";
    var handler = new ApiAppealsHandler(plugin);
    assertEquals(
        403,
        call(handler, "/api/appeals", "/" + appeal + "/review", Map.of("X-Appeal-Token", "wrong"))
            .status);
    assertEquals(
        200,
        call(handler, "/api/appeals", "/" + appeal + "/review", Map.of("X-Appeal-Token", cap))
            .status);
    assertEquals(
        409,
        call(handler, "/api/appeals", "/" + appeal + "/review", Map.of("X-Appeal-Token", cap))
            .status);
    assertEquals(
        "REJECTED", plugin.getDatabaseManager().getAppealById(appeal).orElseThrow().status());
  }

  static class Response {
    int status = 200;
    StringWriter body = new StringWriter();
  }

  @Test
  void sensitiveApiResponsesAreNotCachedAndPolicyBlocksBaseAndObjectInjection() throws Exception {
    var headers=new HashMap<String,String>();
    var request=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->switch(m.getName()) {
      case "getHeader" -> "Origin".equals(a[0]) ? "https://panel.example" : null;
      case "getScheme" -> "https"; case "getServerName" -> "panel.example";
      case "getServerPort" -> 443; case "getMethod" -> "GET"; case "getRequestURI" -> "/api/bans";
      default -> null;
    });
    var response=(HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletResponse.class},(p,m,a)->{
      if(m.getName().equals("setHeader"))headers.put((String)a[0],(String)a[1]);return null;
    });
    var called=new java.util.concurrent.atomic.AtomicBoolean();
    new CorsFilter(plugin).doFilter(request,response,(q,r)->called.set(true));
    assertTrue(called.get());assertEquals("no-store",headers.get("Cache-Control"));
    assertTrue(headers.get("Content-Security-Policy").contains("base-uri 'none'"));
    assertTrue(headers.get("Content-Security-Policy").contains("object-src 'none'"));
    assertTrue(headers.get("Access-Control-Allow-Headers").contains("Idempotency-Key"));
    assertEquals("https://panel.example",headers.get("Access-Control-Allow-Origin"));
  }

  @Test
  void serverStatisticsKeepPermissionBoundariesEvenWithSavedSensitiveSnapshots() throws Exception {
    var actor=account("AnalyticsOnly",Set.of(Permission.VIEW_ANALYTICS));
    plugin.getDatabaseManager().recordPlayerLogin(UUID.randomUUID().toString(),"KnownPlayer",null);
    plugin
        .getDatabaseManager()
        .saveServerMetrics("security", "LoveAuth", Map.of("SECRET_COUNT", 12345), 100);
    plugin
        .getDatabaseManager()
        .saveServerMetrics("economy", "LoveCore", Map.of("PRIVATE_BALANCE", 99999), 100);
    var response =
        call(
            new ApiAnalyticsHandler(plugin),
            "/api/analytics",
            "/general",
            Map.of("Authorization", "Bearer " + actor.token()));
    assertEquals(200,response.status);String body=response.body.toString();
    assertTrue(body.contains("Аккаунты и активность"));
    assertFalse(body.contains("SECRET_COUNT"));
    assertFalse(body.contains("PRIVATE_BALANCE"));
    assertFalse(body.contains("secret-token"));assertFalse(body.contains("password"));
    var denied=account("NoAnalytics",Set.of(Permission.VIEW_STATS));
    assertEquals(
        403,
        call(
                new ApiAnalyticsHandler(plugin),
                "/api/analytics",
                "/general",
                Map.of("Authorization", "Bearer " + denied.token()))
            .status);
  }

  @Test
  void dashboardWorkSummaryDoesNotExposeCasesWithoutTheirPermissions() throws Exception {
    report("PrivatePlayer");var actor=account("StatsOnly",Set.of(Permission.VIEW_STATS));
    var response =
        call(
            new ApiWorkSummaryHandler(plugin),
            "/api/work-summary",
            null,
            Map.of("Authorization", "Bearer " + actor.token()));
    assertEquals(200,response.status);
    assertFalse(response.body.toString().contains("PrivatePlayer"));
    var reviewer=account("SummaryReviewer",Set.of(Permission.VIEW_STATS,Permission.VIEW_REPORTS));
    var visible =
        call(
            new ApiWorkSummaryHandler(plugin),
            "/api/work-summary",
            null,
            Map.of("Authorization", "Bearer " + reviewer.token()));
    assertEquals(200,visible.status);assertTrue(visible.body.toString().contains("PrivatePlayer"));
  }

  @Test
  void serverMetricSnapshotsAreMarkedStaleWhenThePluginIsUnavailable() throws Exception {
    var actor=account("StatsSnapshots",Set.of(Permission.VIEW_ANALYTICS));
    plugin.getDatabaseManager().saveServerMetrics("clans","LoveClans",Map.of("Кланов",7),100);
    var response =
        call(
            new ApiAnalyticsHandler(plugin),
            "/api/analytics",
            "/general",
            Map.of("Authorization", "Bearer " + actor.token()));
    assertEquals(200,response.status);
    var data=(Map<?,?>)JsonUtils.parseObject(response.body.toString()).get("data");
    var sections=(List<Map<String,Object>>)data.get("sections");
    var clans =
        sections.stream().filter(row -> row.get("id").equals("clans")).findFirst().orElseThrow();
    assertEquals(true, clans.get("stale"));
    assertEquals(100, ((Number) clans.get("observedAt")).intValue());
    assertEquals(7,((Number)((Map<?,?>)clans.get("fields")).get("Кланов")).intValue());
  }

  private int report(String name) {
    return plugin
        .getDatabaseManager()
        .createPlayerReport(
            UUID.randomUUID().toString(),
            "Reporter",
            null,
            UUID.randomUUID().toString(),
            name,
            List.of("Причина"),
            "Описание",
            false,
            0);
  }

  private WebBan ban(String name) {
    return plugin
        .getDatabaseManager()
        .saveBan(
            new WebBan(
                0, name, null, null, "Issuer", "Причина", "", "[]", true, "ACTIVE", -1, 100, -1));
  }

  @Test
  void banRightsDoNotAllowClosingAReportByAttachingIt() throws Exception {
    var actor = account("BanOnly", Set.of(Permission.MANAGE_BANS));
    var ban = ban("Ada123"); int report = report("Ada123");
    var headers = Map.of("Authorization", "Bearer " + actor.token());
    requestMethod = "POST"; requestBody = "{\"reportId\":" + report + "}";
    assertEquals(
        403,
        call(new ApiBansHandler(plugin), "/api/bans", "/" + ban.id() + "/attach-report", headers)
            .status);
    requestBody =
        "{\"targetName\":\"Ada123\",\"ruleReason\":\"Причина\",\"description\":\"Доказательства\",\"linkedReportId\":"
            + report
            + "}";
    assertEquals(403, call(new ApiBansHandler(plugin), "/api/bans", null, headers).status);
    assertNull(plugin.getDatabaseManager().getBanById(ban.id()).orElseThrow().linkedReportId());
    assertEquals(
        "PENDING", plugin.getDatabaseManager().getReportById(report).orElseThrow().status());
  }

  @Test
  void wrongOrClosedReportCannotChangeAnExistingBan() throws Exception {
    var db = plugin.getDatabaseManager();
    var actor = account("BanAndReport", Set.of(Permission.MANAGE_BANS,Permission.MANAGE_REPORTS));
    var ban = ban("Ada123"); int wrong = report("OtherPlayer"), correct = report("Ada123");
    requestMethod = "POST"; requestBody = "{\"reportId\":"+wrong+"}";
    assertEquals(
        409,
        call(
                new ApiBansHandler(plugin),
                "/api/bans",
                "/" + ban.id() + "/attach-report",
                Map.of("Authorization", "Bearer " + actor.token()))
            .status);
    assertNull(db.getBanById(ban.id()).orElseThrow().linkedReportId());
    assertEquals("PENDING",db.getReportById(wrong).orElseThrow().status());
    assertFalse(db.linkBanAndReport(ban.id(),999999,"Staff"));
    assertNull(db.getBanById(ban.id()).orElseThrow().linkedReportId());
    assertTrue(db.linkBanAndReport(ban.id(),correct,"Staff"));
    assertFalse(db.linkBanAndReport(ban.id(),correct,"Staff"));
    assertEquals(correct,db.getBanById(ban.id()).orElseThrow().linkedReportId());
    assertEquals(ban.id(),db.getReportById(correct).orElseThrow().linkedBanId());
    assertFalse(db.linkBanAndReport(ban.id(),report("Ada123"),"Staff"));
  }

  @Test
  void reportWriteFailureRollsBackTheBanLink() throws Exception {
    var db=plugin.getDatabaseManager();var ban=ban("Ada123");int report=report("Ada123");
    var field=db.getClass().getDeclaredField("connection");field.setAccessible(true);
    var connection=(java.sql.Connection)field.get(db);
    try(var statement=connection.createStatement()) {
      statement.execute(
          "CREATE TEMP TRIGGER fail_report_update BEFORE UPDATE ON player_reports BEGIN SELECT"
              + " RAISE(ABORT, 'test write failure'); END");
    }
    assertFalse(db.linkBanAndReport(ban.id(),report,"Staff"));
    assertNull(db.getBanById(ban.id()).orElseThrow().linkedReportId());
    assertEquals("PENDING",db.getReportById(report).orElseThrow().status());
    assertTrue(connection.getAutoCommit());
  }

  @Test
  void invalidReportCannotApplyAPunishmentThroughTheManager() {
    int wrong=report("OtherPlayer");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            plugin
                .getBanManager()
                .finalizeBan(
                    0, "Staff", "Ada123", "Причина", "Описание", List.of(), true, 3600, wrong));
    assertTrue(plugin.getDatabaseManager().getAllBans(null).isEmpty());
    int closed=report("Ada123");plugin.getDatabaseManager().resolveReport(closed,"CLOSED","Staff");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            plugin
                .getBanManager()
                .finalizeBan(
                    0, "Staff", "Ada123", "Причина", "Описание", List.of(), true, 3600, closed));
    assertTrue(plugin.getDatabaseManager().getAllBans(null).isEmpty());
  }

  @Test
  void fractionalOrMalformedIdsDoNotCreateAnotherBan() throws Exception {
    var actor=account("StrictIds",Set.of(Permission.MANAGE_BANS));requestMethod="POST";
    for(String bad:List.of("1.5","-1","2147483648","\"broken\"")) {
      requestBody =
          "{\"id\":"
              + bad
              + ",\"targetName\":\"Ada123\",\"ruleReason\":\"Причина\",\"description\":\"Описание\"}";
      assertEquals(
          400,
          call(
                  new ApiBansHandler(plugin),
                  "/api/bans",
                  null,
                  Map.of("Authorization", "Bearer " + actor.token()))
              .status);
    }
    assertTrue(plugin.getDatabaseManager().getAllBans(null).isEmpty());
  }

  @Test
  void contextAndCompactReportQueriesStayScopedAndBounded() {
    var db=plugin.getDatabaseManager();ban("Ada123");ban("OtherPlayer");
    db.saveBan(new WebBan(0,"Ada123",null,null,"Staff","Expired","","[]",true,"ACTIVE",60,100,160));
    var summary = db.getBanContext("ada123");
    assertEquals(2L, summary.get("total"));
    assertEquals(1L, summary.get("active"));
    for(int i=0;i<53;i++)report("Ada123");report("OtherPlayer");
    var first=db.getPendingReportsForPlayer("ada123",null,51,0);
    var last=db.getPendingReportsForPlayer("ADA123",null,51,50);
    assertEquals(51,first.size());assertEquals(3,last.size());assertEquals(first.get(50).id(),last.getFirst().id());
    assertTrue(db.getPendingReportsForPlayer("ada123","does-not-exist",51,0).isEmpty());
    assertEquals(1,db.getPendingReportsForPlayer("ada123",null,-1,0).size());
  }


  @Test
  void muteOnlyRoleCanReadRestrictionsAndHistoryWithoutBanRights() throws Exception {
    var actor=account("MuteOnly",Set.of(Permission.MANAGE_MUTES));
    var headers=Map.of("Authorization","Bearer "+actor.token());
    var handler=new ApiModerationHandler(plugin);
    var response=call(handler,"/api/moderation",null,headers);
    assertEquals(200,response.status);assertTrue(response.body.toString().contains("restrictions"));
    assertEquals(200,call(handler,"/api/moderation","/history",headers).status);
    var stranger=account("NoRestrictions",Set.of(Permission.VIEW_STATS));
    assertEquals(
        403,
        call(
                handler,
                "/api/moderation",
                null,
                Map.of("Authorization", "Bearer " + stranger.token()))
            .status);
    requestMethod = "POST";
    requestBody = "{\"action\":\"freeze\",\"player\":\"Ada123\",\"reason\":\"Нет полномочий\"}";
    assertEquals(403,call(handler,"/api/moderation",null,headers).status);
  }

  private Response call(
      HttpServlet servlet, String servletPath, String path, Map<String, String> headers)
      throws Exception {
    var response = new Response();
    var req =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {HttpServletRequest.class},
                (proxy, m, args) ->
                    switch (m.getName()) {
                      case "getInputStream" -> {
                        var source =
                            new ByteArrayInputStream(
                                requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        yield new jakarta.servlet.ServletInputStream() {
                          public int read() {
                            return source.read();
                          }

                          public boolean isReady() {
                            return true;
                          }

                          public boolean isFinished() {
                            return source.available() == 0;
                          }

                          public void setReadListener(jakarta.servlet.ReadListener listener) {
                            throw new UnsupportedOperationException();
                          }
                        };
                      }
                      case "getMethod" -> requestMethod;
                      case "getServletPath" -> servletPath;
                      case "getPathInfo" -> path;
                      case "getRequestURI" -> servletPath + Objects.toString(path, "");
                      case "getHeader" -> headers.get(args[0]);
                      case "getHeaderNames" -> Collections.enumeration(headers.keySet());
                      case "getRemoteAddr" -> "127.0.0.1";
                      case "getProtocol" -> "HTTP/1.1";
                      case "getContentLengthLong" -> 0L;
                      default ->
                          m.getReturnType() == boolean.class
                              ? false
                              : m.getReturnType() == int.class ? 0 : null;
                    });
    var resp =
        (HttpServletResponse)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {HttpServletResponse.class},
                (proxy, m, args) ->
                    switch (m.getName()) {
                      case "getWriter" -> new PrintWriter(response.body);
                      case "setStatus", "sendError" -> {
                        response.status = (Integer) args[0];
                        yield null;
                      }
                      case "getStatus" -> response.status;
                      case "isCommitted" -> false;
                      default -> null;
                    });
    servlet.service(req, resp);
    return response;
  }
}
