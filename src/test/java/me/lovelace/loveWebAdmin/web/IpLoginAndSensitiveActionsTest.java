package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.utils.*;
import me.lovelace.loveWebAdmin.web.handlers.ApiHandlerSupport;
import org.junit.jupiter.api.*;

class IpLoginAndSensitiveActionsTest {
  SupportApiTest fixture;
  WebAdmin admin;
  WebSession session;
  String secret;

  @BeforeEach
  void setup() throws Exception {
    fixture = new SupportApiTest();
    fixture.setup();
    secret = TotpUtils.generateSecret();
    var role =
        fixture
            .plugin
            .getRoleManager()
            .createRole("SecurityStaff", "", Set.of(Permission.MANAGE_ROLES));
    fixture
        .plugin
        .getDatabaseManager()
        .saveAdmin(
            new WebAdmin(
                0,
                "SecurityStaff",
                PasswordUtils.hash("LongSecure!Password2026"),
                role.id(),
                1,
                0,
                secret,
                true,
                1,
                "192.0.2.1",
                "ACTIVE",
                "{}",
                "[]"));
    admin = fixture.plugin.getDatabaseManager().getAdminByUsername("SecurityStaff").orElseThrow();
    session = fixture.plugin.getSessionManager().createSession(admin, role, "192.0.2.1", "test");
  }

  @AfterEach
  void stop() {
    fixture.stop();
  }

  @Test
  void oldConfirmedIpNeedsOnlyPasswordButNewIpNeedsSecondFactor() {
    var manager = fixture.plugin.getAdminManager();
    assertEquals(
        me.lovelace.loveWebAdmin.managers.AdminManager.LoginStatus.SUCCESS,
        manager.login(admin.username(), "LongSecure!Password2026", "192.0.2.1").status());
    assertEquals(
        1, fixture.plugin.getDatabaseManager().getAdminById(admin.id()).orElseThrow().last2faAt());
    assertEquals(
        me.lovelace.loveWebAdmin.managers.AdminManager.LoginStatus.NEED_2FA,
        manager.login(admin.username(), "LongSecure!Password2026", "192.0.2.2").status());
    assertEquals(
        me.lovelace.loveWebAdmin.managers.AdminManager.LoginStatus.INVALID_CREDENTIALS,
        manager.login(admin.username(), "wrong", "192.0.2.1").status());
  }

  @Test
  void aMatchingButNeverConfirmedIpIsNotTrusted() {
    fixture.plugin.getDatabaseManager().updateAdmin2faSuccess(admin.id(), 0, "192.0.2.1");
    assertEquals(
        me.lovelace.loveWebAdmin.managers.AdminManager.LoginStatus.NEED_2FA,
        fixture
            .plugin
            .getAdminManager()
            .login(admin.username(), "LongSecure!Password2026", "192.0.2.1")
            .status());
  }

  @Test
  void sensitiveMutationCannotRunWithoutAValidFreshFactor() throws Exception {
    var handler = new Probe();
    assertEquals(403, call(handler, null).status);
    assertEquals(0, handler.calls);
    assertEquals(403, call(handler, "not-a-code").status);
    assertEquals(0, handler.calls);
    String code = AuthenticationPolicyTest.code(secret);
    assertEquals(200, call(handler, code).status);
    assertEquals(1, handler.calls);
    assertEquals(403, call(handler, code).status);
    assertEquals(1, handler.calls);
  }

  @Test
  void onlyAccountAndAccessChangesAreSensitive() {
    for (String path :
        List.of(
            "/api/roles/2",
            "/api/admins/4/password",
            "/api/server/config",
            "/api/server/ops",
            "/api/me/backup-codes/regenerate",
            "/api/me/sessions/other/terminate"))
      assertTrue(SensitiveActions.requiresSecondFactor(path), path);
    for (String path :
        List.of(
            "/api/moderation",
            "/api/me/preferences",
            "/api/auth/login",
            "/api/me/password",
            "/api/cases/report/1/claim"))
      assertFalse(SensitiveActions.requiresSecondFactor(path), path);
  }

  private Result call(Probe handler, String code) throws Exception {
    var result = new Result();
    var request =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {HttpServletRequest.class},
                (p, m, a) ->
                    switch (m.getName()) {
                      case "getMethod" -> "POST";
                      // URI spelling is deliberately encoded; policy must use the servlet's decoded
                      // route.
                      case "getRequestURI" -> "/api/auth/sessions/%74est/terminate";
                      case "getServletPath" -> "/api/auth";
                      case "getPathInfo" -> "/sessions/test/terminate";
                      case "getRemoteAddr" -> "192.0.2.1";
                      case "getHeader" ->
                          switch ((String) a[0]) {
                            case "Authorization" -> "Bearer " + session.token();
                            case "X-LWA-2FA" -> code;
                            default -> null;
                          };
                      default -> null;
                    });
    var response =
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
    handler.call(request, response);
    return result;
  }

  class Probe extends ApiHandlerSupport {
    int calls;

    Probe() {
      super(fixture.plugin);
    }

    void call(HttpServletRequest req, HttpServletResponse resp) throws Exception {
      service(req, resp);
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      calls++;
      sendSuccess(resp, Map.of("ok", true));
    }
  }

  static class Result {
    int status = 200;
    StringWriter body = new StringWriter();
  }
}
