package me.lovelace.loveWebAdmin.web.handlers;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StaticCacheTest {
  @Test
  void revalidationAvoidsResendingAssetsAndRestartInvalidatesTheTag() throws Exception {
    var handler = new StaticHandler();
    var first = fetch(handler, null);
    assertTrue(first.data.size() > 0);
    assertEquals("no-cache", first.headers.get("Cache-Control"));
    var cached = fetch(handler, first.headers.get("ETag"));
    assertEquals(304, cached.status);
    assertEquals(0, cached.data.size());
    assertFalse(cached.opened);
    var afterRestart = fetch(new StaticHandler(), first.headers.get("ETag"));
    assertEquals(200, afterRestart.status);
    assertTrue(afterRestart.opened);
    assertNotEquals(first.headers.get("ETag"), afterRestart.headers.get("ETag"));
  }

  private Result fetch(StaticHandler handler, String tag) throws Exception {
    var result = new Result();
    var request =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {HttpServletRequest.class},
                (p, m, a) ->
                    switch (m.getName()) {
                      case "getRequestURI" -> "/style.css";
                      case "getHeader" -> tag;
                      default -> null;
                    });
    var response =
        (HttpServletResponse)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {HttpServletResponse.class},
                (p, m, a) ->
                    switch (m.getName()) {
                      case "setHeader" -> {
                        result.headers.put((String) a[0], (String) a[1]);
                        yield null;
                      }
                      case "setStatus" -> {
                        result.status = (int) a[0];
                        yield null;
                      }
                      case "getOutputStream" -> {
                        result.opened = true;
                        yield new ServletOutputStream() {
                          public boolean isReady() {
                            return true;
                          }

                          public void setWriteListener(WriteListener listener) {}

                          public void write(int b) {
                            result.data.write(b);
                          }
                        };
                      }
                      default -> null;
                    });
    handler.doGet(request, response);
    return result;
  }

  private static class Result {
    int status = 200;
    boolean opened;
    final Map<String, String> headers = new HashMap<>();
    final ByteArrayOutputStream data = new ByteArrayOutputStream();
  }
}
