package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Общие хелперы для всех API хендлеров: аутентификация, проверка прав, JSON I/O.
 */
public abstract class ApiHandlerSupport extends HttpServlet {

    protected final LoveWebAdmin plugin;

    protected ApiHandlerSupport(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    protected Optional<WebSession> authenticate(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        String token = header.substring("Bearer ".length()).trim();
        return plugin.getSessionManager().validate(token);
    }

    protected boolean hasPermission(WebSession session, Permission permission) {
        return plugin.getDatabaseManager().getRoleById(session.roleId())
            .map(role -> role.permissions().contains(permission))
            .orElse(false);
    }

    protected Optional<WebSession> requireSession(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> session = authenticate(req);
        if (session.isEmpty()) {
            sendError(resp, 401, "Требуется авторизация");
        }
        return session;
    }

    protected Optional<WebSession> requirePermission(HttpServletRequest req, HttpServletResponse resp, Permission permission) throws IOException {
        Optional<WebSession> sessionOpt = authenticate(req);
        if (sessionOpt.isEmpty()) {
            sendError(resp, 401, "Требуется авторизация");
            return Optional.empty();
        }
        if (!hasPermission(sessionOpt.get(), permission)) {
            sendError(resp, 403, "Недостаточно прав");
            return Optional.empty();
        }
        return sessionOpt;
    }

    protected Map<String, Object> readJsonBody(HttpServletRequest req) throws IOException {
        String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) return Map.of();
        return JsonUtils.parseObject(body);
    }

    protected String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    protected int parseIntOrDefault(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected void sendJson(HttpServletResponse resp, int status, String json) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.getWriter().write(json);
    }

    protected void sendSuccess(HttpServletResponse resp, Object data) throws IOException {
        sendJson(resp, 200, JsonUtils.success(data));
    }

    protected void sendError(HttpServletResponse resp, int status, String message) throws IOException {
        sendJson(resp, status, JsonUtils.error(message));
    }
}
