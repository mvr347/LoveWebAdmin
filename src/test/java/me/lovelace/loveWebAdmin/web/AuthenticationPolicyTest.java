package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import me.lovelace.loveWebAdmin.*;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.utils.*;
import me.lovelace.loveWebAdmin.web.handlers.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class AuthenticationPolicyTest {
  LoveWebAdmin plugin;
  Auth auth;

  @BeforeEach
  void start() {
    MockBukkit.mock();
    MockBukkit.createMockPlugin("LoveCore");
    MockBukkit.createMockPlugin("LoveEssential");
    var config = new YamlConfiguration();
    config.set("web.enabled", false);
    config.set("debug-mode", true);
    config.set("security.debug-mode", true);
    config.set("preview.enabled", true);
    plugin = MockBukkit.loadWithConfig(LoveWebAdmin.class, config);
    auth = new Auth(plugin);
  }

  @AfterEach
  void stop() {
    MockBukkit.unmock();
  }

  @Test
  void legacyFlagsCannotEnableBypass() {
    assertFalse(plugin.isDebugMode());
    assertNull(plugin.getConfig().get("debug-mode"));
    assertFalse(
        plugin
            .getAdminManager()
            .completeMasterOnboarding("NewOwner", "LongSecure!Password2026", null, "debug", "ip")
            .success());
    assertEquals(
        me.lovelace.loveWebAdmin.managers.AdminManager.RegisterStatus.INVALID_TOTP,
        plugin
            .getAdminManager()
            .registerCandidate("Candidate", "LongSecure!Password2026", null, "debug")
            .status());
  }

  @Test
  void previewDoesNotRelaxRegistrationOrOnboarding() throws Exception {
    String token = plugin.getAdminManager().getSetupToken();
    plugin.getAdminManager().prepareOwnerTotp(token, "Owner");
    var response =
        post(
            "/setup-owner",
            Map.of(
                "setupToken",
                token,
                "username",
                "Owner",
                "password",
                "LongSecure!Password2026",
                "totpCode",
                "debug"));
    assertEquals(400, response.status);
    assertTrue(plugin.getAdminManager().peekSetupToken(token));
  }

  @Test
  void twoFactorRequiresPriorPasswordChallenge() throws Exception {
    String secret = TotpUtils.generateSecret();
    var role = plugin.getDatabaseManager().getRoleByName("Модератор").orElseThrow();
    plugin
        .getDatabaseManager()
        .saveAdmin(
            new WebAdmin(
                0,
                "TestStaff",
                PasswordUtils.hash("LongSecure!Password2026"),
                role.id(),
                1,
                0,
                secret,
                true,
                0,
                null,
                "ACTIVE",
                "{}",
                "[]"));
    var response = post("/verify-2fa", Map.of("username", "TestStaff", "code", code(secret)));
    assertEquals(401, response.status);
    var login =
        post("/login", Map.of("username", "TestStaff", "password", "LongSecure!Password2026"));
    assertEquals(200, login.status);
    var data = (Map<?, ?>) JsonUtils.parseObject(login.body.toString()).get("data");
    assertEquals("NEED_2FA", data.get("status"));
    String challenge = (String) data.get("challenge");
    assertNotNull(challenge);
    var verified =
        post(
            "/verify-2fa",
            Map.of("username", "TestStaff", "code", code(secret), "challenge", challenge));
    assertEquals(200, verified.status);
    assertTrue(verified.body.toString().contains("token"));
    assertEquals(
        401,
        post(
                "/verify-2fa",
                Map.of("username", "TestStaff", "code", code(secret), "challenge", challenge))
            .status);
  }

  @Test
  void inviteCannotBeAcceptedWithDebugCode() throws Exception {
    var role = plugin.getDatabaseManager().getRoleByName("Модератор").orElseThrow();
    var invite =
        plugin
            .getDatabaseManager()
            .createAdminInvite(
                "INVITE",
                "Invited",
                role.id(),
                0,
                0,
                "Owner",
                System.currentTimeMillis() / 1000 + 3600);
    plugin.getDatabaseManager().bindInviteTotpSecret(invite.id(), TotpUtils.generateSecret());
    assertEquals(
        400,
        post(
                "/register",
                Map.of(
                    "inviteCode",
                    "INVITE",
                    "password",
                    "LongSecure!Password2026",
                    "totpCode",
                    "debug"))
            .status);
    assertTrue(plugin.getDatabaseManager().getAdminByUsername("Invited").isEmpty());
  }

  @Test
  void concurrentUseOfOneTotpCodeSucceedsOnce() throws Exception {
    String secret = TotpUtils.generateSecret(), code = code(secret);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var results = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < 30; i++)
        results.add(executor.submit(() -> TotpUtils.verifyCode(secret, code)));
      int success = 0;
      for (var f : results) if (f.get()) success++;
      assertEquals(1, success);
    }
  }

  static String code(String secret) throws Exception {
    var decode = TotpUtils.class.getDeclaredMethod("decodeBase32", String.class);
    decode.setAccessible(true);
    var generate =
        TotpUtils.class.getDeclaredMethod("generateCodeForStep", byte[].class, long.class);
    generate.setAccessible(true);
    return String.format(
        "%06d",
        generate.invoke(null, decode.invoke(null, secret), System.currentTimeMillis() / 30000));
  }

  Result post(String path, Map<String, Object> body) throws Exception {
    auth.body = body;
    var result = new Result();
    var req =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {HttpServletRequest.class},
                (p, m, a) ->
                    switch (m.getName()) {
                      case "getPathInfo" -> path;
                      case "getRemoteAddr" -> "192.0.2.1";
                      case "getHeader" -> null;
                      default -> null;
                    });
    var resp =
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
    auth.post(req, resp);
    return result;
  }

  static class Result {
    int status = 200;
    StringWriter body = new StringWriter();
  }

  static class Auth extends ApiAuthHandler {
    Map<String, Object> body;

    Auth(LoveWebAdmin p) {
      super(p);
    }

    protected Map<String, Object> readJsonBody(HttpServletRequest r) {
      return body;
    }

    void post(HttpServletRequest r, HttpServletResponse s) throws IOException {
      super.doPost(r, s);
    }
  }
}
