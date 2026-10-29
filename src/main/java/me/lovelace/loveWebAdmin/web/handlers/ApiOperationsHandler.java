package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;

/** Actor-scoped reconciliation. Never executes or retries an operation. */
public final class ApiOperationsHandler extends ApiHandlerSupport {
  public ApiOperationsHandler(LoveWebAdmin plugin) {
    super(plugin);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    var session = requireSession(req, resp);
    if (session.isEmpty()) return;
    String key = Objects.toString(req.getPathInfo(), "").replaceFirst("^/", "");
    if (!key.matches("[a-zA-Z0-9_-]{8,100}")) {
      sendError(resp, 400, "Некорректный ключ операции");
      return;
    }
    try {
      var operation = plugin.operations().get(operationActor(session.get()), key);
      if (operation.isEmpty()) {
        sendError(resp, 404, "Операция не найдена");
        return;
      }
      var value = operation.get();
      if (value.permission() != null
          && !Arrays.stream(value.permission().split(","))
              .allMatch(p -> p.isBlank() || hasPermission(session.get(), Permission.valueOf(p)))) {
        sendError(resp, 403, "Права на действие изменились");
        return;
      }
      sendSuccess(
          resp,
          Map.of(
              "state",
              value.state(),
              "status",
              value.status(),
              "response",
              value.state().equals("COMPLETE") ? value.response() : "",
              "route",
              value.route()));
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    var session = requireSession(req, resp);
    if (session.isEmpty()) return;
    String path = Objects.toString(req.getPathInfo(), "");
    if (!path.matches("/[a-zA-Z0-9_-]{8,100}/resolve")) {
      sendError(resp, 404, "Операция не найдена");
      return;
    }
    String key = path.split("/")[1], actor = operationActor(session.get());
    var body = readJsonBody(req);
    String explanation = Objects.toString(body.get("explanation"), "");
    try {
      var operation = plugin.operations().get(actor, key);
      if (operation.isEmpty()) {
        sendError(resp, 404, "Операция не найдена");
        return;
      }
      if (operation.get().permission() != null
          && !Arrays.stream(operation.get().permission().split(","))
              .filter(p -> !p.isBlank())
              .allMatch(p -> hasPermission(session.get(), Permission.valueOf(p)))) {
        sendError(resp, 403, "Права на действие изменились");
        return;
      }
      req.setAttribute("lwa.permission", operation.get().permission());
      if (!plugin
          .operations()
          .resolve(actor, key, Boolean.TRUE.equals(body.get("applied")), explanation)) {
        sendError(resp, 409, "Операция выполняется либо уже имеет подтверждённый результат");
        return;
      }
      plugin
          .getLogManager()
          .logWebAction(
              session.get().adminUsername(),
              "Сверил результат операции " + key + ": " + explanation);
      sendSuccess(resp, Map.of("message", "Результат проверки сохранён"));
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }
}
