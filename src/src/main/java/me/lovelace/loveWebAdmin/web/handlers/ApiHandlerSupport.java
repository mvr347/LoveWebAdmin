package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.ServletException;
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

    /**
     * Ловит некорректный ввод (битый JSON тела запроса через {@link JsonUtils#parseObject},
     * нечисловые path-параметры вроде id и т.п.), который иначе всплывал бы как
     * необработанное исключение до контейнера — тот отдаёт свою страницу ошибки по умолчанию
     * (potentially раскрывая stacktrace) вместо аккуратного JSON 400/500 и не логировался бы
     * через plugin.getLogger(). Не меняет поведение при корректных запросах — проверки прав/сессии
     * внутри doGet/doPost/... выполняются как раньше, до разбора тела.
     */
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            super.service(req, resp);
        } catch (IllegalArgumentException e) {
            if (!resp.isCommitted()) {
                sendError(resp, 400, "Некорректный запрос");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Необработанная ошибка при обработке запроса " + req.getRequestURI() + ": " + e);
            if (!resp.isCommitted()) {
                sendError(resp, 500, "Внутренняя ошибка сервера");
            }
        }
    }

    protected Optional<WebSession> authenticate(HttpServletRequest req) {
        // 1. Проверяем заголовок X-API-Key
        String apiKey = req.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            if (plugin.getApiKeyManager() != null) {
                var opt = plugin.getApiKeyManager().validateKey(apiKey.trim());
                if (opt.isPresent()) {
                    return Optional.of(createApiKeySession(opt.get(), req));
                }
            }
        }

        // 2. Проверяем заголовок Authorization
        String header = req.getHeader("Authorization");
        if (header == null) return Optional.empty();

        if (header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            if (token.startsWith("lwa_live_") && plugin.getApiKeyManager() != null) {
                var opt = plugin.getApiKeyManager().validateKey(token);
                if (opt.isPresent()) {
                    return Optional.of(createApiKeySession(opt.get(), req));
                }
            }
            return plugin.getSessionManager().validate(token);
        }
        return Optional.empty();
    }

    private WebSession createApiKeySession(me.lovelace.loveWebAdmin.models.ApiKeyRecord apiKey, HttpServletRequest req) {
        long now = System.currentTimeMillis() / 1000L;
        String ip = req.getRemoteAddr();
        String ua = req.getHeader("User-Agent");
        return new WebSession(
            apiKey.keyHash(),
            -999, // Специальный маркер сессии API ключа
            apiKey.name() + " [API Key]",
            -999,
            now + 86400,
            ip,
            ua != null ? ua : "API Client",
            now,
            now
        );
    }

    protected boolean hasPermission(WebSession session, Permission permission) {
        if (session.adminId() == -999) {
            if (plugin.getApiKeyManager() != null) {
                return plugin.getApiKeyManager().hasPermission(session.token(), permission);
            }
            return false;
        }
        return plugin.getDatabaseManager().getRoleById(session.roleId())
            .map(role -> role.isOwner() || role.permissions().contains(permission))
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
