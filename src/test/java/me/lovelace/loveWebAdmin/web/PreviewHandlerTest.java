package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import me.lovelace.loveWebAdmin.web.handlers.PreviewHandler;
import org.junit.jupiter.api.Test;

class PreviewHandlerTest {
  @Test
  void disabledAndUnknownRoutesStayClosed() throws Exception {
    assertEquals(404, get(false, "/preview/", "/").status);
    assertEquals(404, get(true, "/preview/api/bans", "/api/bans").status);
    assertEquals(404, get(true, "/preview/../config.yml", "/../config.yml").status);
  }

  @Test
  void previewAssetsAreIsolatedAndCspBlocksLiveConnections() throws Exception {
    var r = get(true, "/preview/", "/");
    assertEquals(200, r.status);
    assertTrue(r.body.toString().contains("/preview/preview-bootstrap.js"));
    assertTrue(r.body.toString().contains("/preview/app.js"));
    assertFalse(r.body.toString().contains("href=\"/style.css\""));
    assertTrue(r.headers.get("Content-Security-Policy").contains("connect-src 'self'"));
    assertEquals("no-store", r.headers.get("Cache-Control"));
  }

  @Test
  void localAvatarIsServedAndScriptUsesIt() throws Exception {
    assertEquals(200, get(true, "/preview/avatar.svg", "/avatar.svg").status);
    var js =
        get(true, "/preview/js/features/vesuvio.js", "/js/features/vesuvio.js").body.toString();
    assertFalse(js.contains("https://mc-heads.net/"));
    assertTrue(js.contains("/preview/avatar.svg?name="));
  }

  @Test
  void packagedJavaScriptRetainsItsOwnTemplateLiterals() throws Exception {
    try (var input = getClass().getResourceAsStream("/web/js/features/moderation.js")) {
      assertNotNull(input);
      String js = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(js.contains("/api/reports/${id}/accept"));
      assertFalse(js.contains("me.lovelace:LoveWebAdmin:jar:"));
    }
  }

  Result get(boolean enabled, String uri, String path) throws Exception {
    var result = new Result();
    var req =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {HttpServletRequest.class},
                (p, m, a) ->
                    switch (m.getName()) {
                      case "getMethod" -> "GET";
                      case "getProtocol" -> "HTTP/1.1";
                      case "getPathInfo" -> path;
                      case "getRequestURI" -> uri;
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
                      case "setStatus", "sendError" -> {
                        result.status = (int) a[0];
                        yield null;
                      }
                      case "setHeader" -> {
                        result.headers.put((String) a[0], (String) a[1]);
                        yield null;
                      }
                      default -> null;
                    });
    new PreviewHandler(() -> enabled).service(req, resp);
    return result;
  }

  static class Result {
    int status = 200;
    StringWriter body = new StringWriter();
    Map<String, String> headers = new HashMap<>();
  }
}
