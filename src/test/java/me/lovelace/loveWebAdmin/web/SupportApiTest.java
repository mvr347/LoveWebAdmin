package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.web.handlers.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class SupportApiTest {
  LoveWebAdmin plugin;
  int first;
  int second;
  Map<String, String> reader, manager, appealsOnly;

  @BeforeEach
  void setup() throws Exception {
    MockBukkit.mock();
    MockBukkit.createMockPlugin("LoveCore");
    MockBukkit.createMockPlugin("LoveEssential");
    var config = new YamlConfiguration();
    config.set("web.enabled", false);
    config.set("web.host", "127.0.0.1");
    config.set("discord.tickets.support.enabled", true);
    config.set(
        "discord.tickets.support.topics",
        List.of(Map.of("id", "bug", "label", "Ошибка / баг", "description", "")));
    plugin = MockBukkit.loadWithConfig(LoveWebAdmin.class, config);
    first = plugin.moderation().createSupport("10000", "Reporter", "bug", "Первое обращение");
    second = plugin.moderation().createSupport("20000", "Other", "removed", "Второе обращение");
    plugin.moderation().closeSupport(second);
    reader = session("Reader", Set.of(Permission.VIEW_REPORTS));
    manager = session("Manager", Set.of(Permission.VIEW_REPORTS, Permission.MANAGE_REPORTS));
    appealsOnly = session("Appeals", Set.of(Permission.VIEW_APPEALS, Permission.MANAGE_APPEALS));
  }

  @AfterEach
  void stop() {
    MockBukkit.unmock();
  }

  Map<String, String> session(String name, Set<Permission> permissions) {
    var role = plugin.getRoleManager().createRole(name, "", permissions);
    plugin.getDatabaseManager().saveAdmin(new WebAdmin(0, name, "hash", role.id(), 100, 0));
    var saved = plugin.getDatabaseManager().getAdminByUsername(name).orElseThrow();
    return Map.of("Authorization", "Bearer " + plugin.getSessionManager().createSession(saved, role).token());
  }

  @Test
  void listAndCardNeedTheReportViewPermission() throws Exception {
    assertEquals(401, listCall("", Map.of(), Map.of()).status);
    assertEquals(403, listCall("", Map.of(), appealsOnly).status);
    var all = listCall("", Map.of("status", "all"), reader);
    assertEquals(200, all.status);
    String body = all.body.toString();
    assertTrue(body.contains("Reporter") && body.contains("Other"));
    assertFalse(body.contains("Первое обращение"), "the list leaves the full text out");
    assertTrue(listCall("/" + first, Map.of(), reader).body.toString().contains("Первое обращение"));
    assertEquals(404, listCall("/9999", Map.of(), reader).status);
    assertEquals(404, listCall("/abc", Map.of(), reader).status);
  }

  @Test
  void statusFilterAndTopicLabels() throws Exception {
    String open = listCall("", Map.of("status", "open"), reader).body.toString();
    assertTrue(open.contains("Reporter") && !open.contains("Other"));
    String closed = listCall("", Map.of("status", "closed"), reader).body.toString();
    assertTrue(closed.contains("Other") && !closed.contains("Reporter"));
    // The label comes from config; a topic removed from config falls back to its id.
    assertTrue(open.contains("Ошибка / баг"));
    assertTrue(closed.contains("removed"));
  }

  @Test
  void casesApiServesSupportWithReportPermissionsOnly() throws Exception {
    assertEquals(200, cases("GET", "/support/" + first, null, reader).status);
    assertEquals(403, cases("GET", "/support/" + first, null, appealsOnly).status);
    assertEquals(404, cases("GET", "/support/9999", null, reader).status);
    assertEquals(403, cases("POST", "/support/" + first + "/claim", "{}", reader).status);
    assertEquals(403, cases("POST", "/support/" + first + "/claim", "{}", appealsOnly).status);
  }

  @Test
  void managerCanClaimAndNoteAndOnlyOnOpenRequests() throws Exception {
    assertEquals(200, cases("POST", "/support/" + first + "/claim", "{}", manager).status);
    assertEquals("Manager", plugin.moderation().work("support", first).get("assignee"));
    assertEquals(
        200, cases("POST", "/support/" + first + "/note", "{\"text\":\"Внутренняя заметка\"}", manager).status);
    assertTrue(plugin.moderation().notes("support", first).toString().contains("Внутренняя заметка"));
    assertEquals(409, cases("POST", "/support/" + second + "/claim", "{}", manager).status);
  }

  // ApiHandlerSupport.service() turns an IllegalArgumentException into HTTP 400.
  @Test
  void verdictEvidenceMergeAndReportClarificationDoNotApplyToSupport() throws Exception {
    for (String action : List.of("evidence", "merge", "clarify"))
      assertThrows(
          IllegalArgumentException.class,
          () -> cases("POST", "/support/" + first + "/" + action, "{\"targetId\":1}", manager),
          action);
    assertEquals("OPEN", plugin.moderation().support(first).orElseThrow().get("status"));
  }

  @Test
  void replyAndCloseNeedTheDiscordBotAndNeverTouchOtherKinds() throws Exception {
    // Without a connected bot the desk refuses instead of pretending the player was answered.
    assertThrows(
        IllegalArgumentException.class,
        () -> cases("POST", "/support/" + first + "/reply", "{\"text\":\"Здравствуйте, уже смотрим\"}", manager));
    assertThrows(
        IllegalArgumentException.class,
        () -> cases("POST", "/support/" + first + "/close", "{\"reason\":\"Вопрос решён\"}", manager));
    assertEquals("OPEN", plugin.moderation().support(first).orElseThrow().get("status"));
    int report =
        plugin
            .getDatabaseManager()
            .createPlayerReport(
                UUID.randomUUID().toString(), "R", null, UUID.randomUUID().toString(), "T", List.of("x"), "d", true, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> cases("POST", "/report/" + report + "/reply", "{\"text\":\"Здравствуйте\"}", manager));
  }

  Result listCall(String path, Map<String, String> params, Map<String, String> headers) throws Exception {
    return call(new ApiSupportHandler(plugin), "GET", path, params, null, headers);
  }

  Result cases(String method, String path, String body, Map<String, String> headers) throws Exception {
    return call(new ApiCasesHandler(plugin), method, path, Map.of(), body, headers);
  }

  Result call(Object handler, String method, String path, Map<String, String> params, String body, Map<String, String> headers)
      throws Exception {
    var result = new Result();
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    HttpServletRequest req =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {HttpServletRequest.class},
                (p, m, a) ->
                    switch (m.getName()) {
                      case "getPathInfo" -> path;
                      case "getMethod" -> method;
                      case "getParameter" -> params.get(a[0]);
                      case "getHeader" -> headers.get(a[0]);
                      case "getRemoteAddr" -> "127.0.0.1";
                      case "getContentLengthLong" -> (long) bytes.length;
                      case "getInputStream" -> stream(bytes);
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
    invoke(handler, method.equals("GET") ? "doGet" : "doPost", req, resp);
    return result;
  }

  static ServletInputStream stream(byte[] bytes) {
    var in = new ByteArrayInputStream(bytes);
    return new ServletInputStream() {
      public int read() {
        return in.read();
      }

      public boolean isFinished() {
        return in.available() == 0;
      }

      public boolean isReady() {
        return true;
      }

      public void setReadListener(ReadListener listener) {}
    };
  }

  static class Result {
    int status = 200;
    StringWriter body = new StringWriter();
  }

  /** The handlers are final and their entry points protected; call them reflectively. */
  static void invoke(Object handler, String name, HttpServletRequest req, HttpServletResponse resp)
      throws Exception {
    var method = handler.getClass().getDeclaredMethod(name, HttpServletRequest.class, HttpServletResponse.class);
    method.setAccessible(true);
    try {
      method.invoke(handler, req, resp);
    } catch (java.lang.reflect.InvocationTargetException error) {
      if (error.getCause() instanceof Exception cause) throw cause;
      throw error;
    }
  }
}
