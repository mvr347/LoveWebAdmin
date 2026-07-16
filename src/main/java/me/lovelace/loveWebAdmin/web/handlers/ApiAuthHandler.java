package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.managers.AdminManager;
import me.lovelace.loveWebAdmin.managers.LoginAttemptTracker;
import me.lovelace.loveWebAdmin.models.WebRole;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * POST /api/auth/login, /logout, /setup-owner, /set-password
 * GET  /api/auth/status, /api/me
 */
public class ApiAuthHandler extends ApiHandlerSupport {

    public ApiAuthHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if ("/api/me".equals(req.getServletPath())) {
            handleMe(req, resp);
            return;
        }
        if ("/status".equals(req.getPathInfo())) {
            handleStatus(resp);
            return;
        }
        sendError(resp, 404, "Не найдено");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) {
            sendError(resp, 404, "Не найдено");
            return;
        }
        switch (pathInfo) {
            case "/login" -> handleLogin(req, resp);
            case "/logout" -> handleLogout(req, resp);
            case "/setup-owner" -> handleSetupOwner(req, resp);
            case "/set-password" -> handleSetPassword(req, resp);
            default -> sendError(resp, 404, "Не найдено");
        }
    }

    private void handleStatus(HttpServletResponse resp) throws IOException {
        sendSuccess(resp, Map.of("ownerExists", plugin.getAdminManager().hasOwner()));
    }

    private void handleSetupOwner(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (plugin.getAdminManager().hasOwner()) {
            sendError(resp, 400, "Управляющий уже назначен");
            return;
        }
        Map<String, Object> body = readJsonBody(req);
        String username = stringOrNull(body.get("username"));
        if (username == null || username.isBlank()) {
            sendError(resp, 400, "Не указан ник");
            return;
        }
        // Финальная проверка от гонки запросов выполняется атомарно внутри AdminManager.setupOwner
        if (!plugin.getAdminManager().setupOwner(username)) {
            sendError(resp, 400, "Управляющий уже назначен");
            return;
        }
        sendSuccess(resp, Map.of("username", username));
    }

    private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String username = stringOrNull(body.get("username"));
        String password = stringOrNull(body.get("password"));
        if (username == null || password == null) {
            sendError(resp, 400, "Не указаны ник или пароль");
            return;
        }

        String ip = req.getRemoteAddr();
        String ipKey = "ip:" + ip;
        String userKey = "user:" + username.toLowerCase();
        LoginAttemptTracker attemptTracker = plugin.getLoginAttemptTracker();

        long lockedSeconds = attemptTracker.getLockedRemainingSeconds(ipKey, userKey);
        if (lockedSeconds > 0) {
            long minutes = Math.max(1, (lockedSeconds + 59) / 60);
            sendError(resp, 429, "Слишком много неудачных попыток входа. Повторите через " + minutes + " мин.");
            return;
        }

        AdminManager.LoginResult result = plugin.getAdminManager().login(username, password);
        switch (result.status()) {
            case NOT_FOUND, INVALID_CREDENTIALS -> {
                attemptTracker.recordFailure(ipKey, userKey);
                plugin.getLogManager().logWebAction(username, "Неудачная попытка входа (IP: " + ip + ")");
                sendError(resp, 401, "Неверный ник или пароль");
            }
            case NEED_SET_PASSWORD -> sendSuccess(resp, Map.of("status", "NEED_SET_PASSWORD"));
            case SUCCESS -> {
                attemptTracker.recordSuccess(ipKey, userKey);
                sendLoginSuccess(resp, result);
            }
        }
    }

    private void handleSetPassword(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String username = stringOrNull(body.get("username"));
        String password = stringOrNull(body.get("password"));
        if (username == null || password == null) {
            sendError(resp, 400, "Не указаны ник или пароль");
            return;
        }

        AdminManager.LoginResult result = plugin.getAdminManager().setPassword(username, password);
        if (result.status() != AdminManager.LoginStatus.SUCCESS) {
            sendError(resp, 404, "Администратор не найден");
            return;
        }
        sendLoginSuccess(resp, result);
    }

    private void sendLoginSuccess(HttpServletResponse resp, AdminManager.LoginResult result) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", result.session().token());
        data.put("role", result.role().name());
        data.put("permissions", result.role().permissions().stream().map(Enum::name).toList());
        sendSuccess(resp, data);
    }

    private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        WebSession session = sessionOpt.get();
        plugin.getSessionManager().invalidate(session.token());
        plugin.getLogManager().logWebAction(session.adminUsername(), "Вышел из системы");
        sendSuccess(resp, null);
    }

    private void handleMe(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        WebSession session = sessionOpt.get();
        WebRole role = plugin.getDatabaseManager().getRoleById(session.roleId()).orElseThrow();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", session.adminUsername());
        data.put("role", role.name());
        data.put("permissions", role.permissions().stream().map(Enum::name).toList());
        sendSuccess(resp, data);
    }
}
