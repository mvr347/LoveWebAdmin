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
    assertTrue(response.body.toString().contains("discordWebhookConfigured"));
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
        "{\"slot\":\"panel:10000\",\"messageId\":\"20000\",\"explanation\":\"Сообщение найдено в канале\"}";
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

  static class Response {
    int status = 200;
    StringWriter body = new StringWriter();
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
