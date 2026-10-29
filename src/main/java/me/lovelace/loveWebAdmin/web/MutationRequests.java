package me.lovelace.loveWebAdmin.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import me.lovelace.loveWebAdmin.database.OperationStore;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

public final class MutationRequests {
  private MutationRequests() {}

  @FunctionalInterface
  public interface Action {
    void run(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException;
  }

  static boolean sensitiveResult(String route) {
    return route.startsWith("/api/developer/keys")
        || route.startsWith("/api/admins/invites")
        || route.startsWith("/api/me/backup-codes")
        || route.startsWith("/api/preview-sessions");
  }

  public static String fingerprint(String method, String route, byte[] body) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      hash.update((method + " " + route + "\n").getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash.digest(body));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static void execute(
      OperationStore store,
      String actor,
      HttpServletRequest req,
      HttpServletResponse resp,
      java.util.function.Predicate<String> canReplay,
      Action action)
      throws ServletException, IOException {
    if (!store.allow(actor)) {
      resp.setStatus(429);
      resp.setHeader("Retry-After", "60");
      resp.setContentType("application/json;charset=utf-8");
      resp.getWriter().write(JsonUtils.error("Слишком много действий; повторите через минуту"));
      return;
    }
    String key = req.getHeader("Idempotency-Key");
    if (key == null || key.isBlank()) {
      action.run(req, resp);
      return;
    }
    byte[] body = req.getInputStream().readNBytes(1048577);
    if (body.length > 1048576) {
      resp.sendError(413);
      return;
    }
    String route =
        req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
    try {
      OperationStore.Operation previous =
          store.reserve(actor, key, route, fingerprint(req.getMethod(), route, body));
      if (previous != null) {
        if (previous.permission() != null && !canReplay.test(previous.permission())) {
          resp.setStatus(403);
          resp.setContentType("application/json;charset=utf-8");
          resp.getWriter().write(JsonUtils.error("Права на действие изменились"));
          return;
        }
        resp.setContentType("application/json;charset=utf-8");
        resp.setHeader("Idempotency-Replayed", "true");
        resp.setHeader("Operation-State", previous.state());
        if (previous.state().equals("COMPLETE")) {
          resp.setStatus(previous.status());
          resp.getWriter().write(previous.response());
        } else {
          resp.setStatus(409);
          resp.getWriter()
              .write(
                  JsonUtils.error(
                      previous.state().equals("UNCERTAIN")
                          ? "Результат требует проверки; действие не повторено"
                          : "Действие ещё выполняется"));
        }
        return;
      }
      var request =
          new HttpServletRequestWrapper(req) {
            @Override
            public ServletInputStream getInputStream() {
              var in = new ByteArrayInputStream(body);
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

                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException();
                }
              };
            }

            @Override
            public BufferedReader getReader() {
              return new BufferedReader(
                  new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
          };
      var buffer = new StringWriter();
      var writer = new PrintWriter(buffer);
      var response =
          new HttpServletResponseWrapper(resp) {
            @Override
            public PrintWriter getWriter() {
              return writer;
            }
          };
      try {
        action.run(request, response);
        writer.flush();
        resp.setHeader("Operation-State", resp.getStatus() >= 500 ? "UNCERTAIN" : "COMPLETE");
        if (resp.getStatus() >= 500) store.uncertain(actor, key);
        else if (req.getMethod().equals("POST")
            && sensitiveResult(route)
            && resp.getStatus() < 300) {
          var metadata = new LinkedHashMap<String, Object>();
          var value = JsonUtils.parseObject(buffer.toString());
          if (value.get("data") instanceof Map<?, ?> data)
            for (String field : List.of("id", "prefix", "name"))
              if (data.containsKey(field)) metadata.put(field, data.get(field));
          store.complete(
              actor,
              key,
              409,
              JsonUtils.toJson(
                  Map.of(
                      "success",
                      false,
                      "data",
                      metadata,
                      "error",
                      "Действие выполнено; секрет был показан один раз и не сохраняется в журнале. Проверьте созданную запись перед новым действием.")),
              (String) request.getAttribute("lwa.permission"));
        } else
          store.complete(
              actor,
              key,
              resp.getStatus(),
              buffer.toString(),
              (String) request.getAttribute("lwa.permission"));
        resp.getWriter().write(buffer.toString());
      } catch (Exception failure) {
        store.uncertain(actor, key);
        throw failure;
      }
    } catch (IllegalArgumentException error) {
      resp.setStatus(400);
      resp.setContentType("application/json;charset=utf-8");
      resp.getWriter().write(JsonUtils.error(error.getMessage()));
    } catch (java.sql.SQLException error) {
      throw new ServletException("Не удалось сохранить состояние операции", error);
    }
  }
}
