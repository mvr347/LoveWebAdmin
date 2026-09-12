package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.managers.AdminManager;
import me.lovelace.loveWebAdmin.managers.LoginAttemptTracker;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebRole;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import me.lovelace.loveWebAdmin.utils.PasswordUtils;
import me.lovelace.loveWebAdmin.utils.TotpUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Аутентификация, регистрация, 2FA Google Authenticator, резервные коды, сессии и профиль.
 */
public class ApiAuthHandler extends ApiHandlerSupport {

    public ApiAuthHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();

        if ("/api/me".equals(servletPath)) {
            if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
                handleMe(req, resp);
                return;
            }
            if ("/sessions".equals(pathInfo)) {
                handleSessions(req, resp);
                return;
            }
            sendError(resp, 404, "Не найдено");
            return;
        }

        if ("/status".equals(pathInfo)) {
            handleStatus(resp);
            return;
        }

        if ("/check-username".equals(pathInfo)) {
            handleCheckUsername(req, resp);
            return;
        }

        if ("/totp-setup".equals(pathInfo)) {
            handleTotpSetup(req, resp);
            return;
        }

        if ("/sessions/all".equals(pathInfo)) {
            handleAllSessions(req, resp);
            return;
        }

        if ("/login-history".equals(pathInfo)) {
            handleLoginHistory(req, resp);
            return;
        }

        sendError(resp, 404, "Не найдено");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();

        if ("/api/me".equals(servletPath)) {
            if ("/password".equals(pathInfo)) {
                handleChangePassword(req, resp);
                return;
            }
            if ("/backup-codes/regenerate".equals(pathInfo)) {
                handleRegenerateBackupCodes(req, resp);
                return;
            }
            if ("/sessions/other".equals(pathInfo) || "/sessions/other/terminate".equals(pathInfo)) {
                handleTerminateOtherSessions(req, resp);
                return;
            }
            sendError(resp, 404, "Не найдено");
            return;
        }

        if (pathInfo == null) {
            sendError(resp, 404, "Не найдено");
            return;
        }

        if (pathInfo.startsWith("/sessions/") && pathInfo.endsWith("/terminate")) {
            handleTerminateSpecificSession(req, resp);
            return;
        }

        switch (pathInfo) {
            case "/login" -> handleLogin(req, resp);
            case "/verify-2fa" -> handleVerify2fa(req, resp);
            case "/logout" -> handleLogout(req, resp);
            case "/setup-owner" -> handleSetupOwner(req, resp);
            case "/register" -> handleRegister(req, resp);
            default -> sendError(resp, 404, "Не найдено");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();

        if ("/api/me".equals(servletPath) && "/sessions/other".equals(pathInfo)) {
            handleTerminateOtherSessions(req, resp);
            return;
        }
        sendError(resp, 404, "Не найдено");
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();

        if ("/api/me".equals(servletPath) && ("/preferences".equals(pathInfo) || "/".equals(pathInfo) || pathInfo == null || pathInfo.isEmpty())) {
            handleUpdatePreferences(req, resp);
            return;
        }
        sendError(resp, 404, "Не найдено");
    }

    private void handleStatus(HttpServletResponse resp) throws IOException {
        boolean ownerExists = plugin.getAdminManager().hasOwner();
        boolean initialSetup = plugin.getAdminManager().isInitialSetupNeeded();
        sendSuccess(resp, Map.of(
            "ownerExists", ownerExists,
            "initialSetupNeeded", initialSetup
        ));
    }

    private void handleCheckUsername(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username = req.getParameter("username");
        String mode = req.getParameter("mode");

        if (username == null || username.trim().isBlank()) {
            sendError(resp, 400, "Укажите имя пользователя");
            return;
        }

        String clean = username.trim();
        if (clean.length() < 3 || clean.length() > 24) {
            sendError(resp, 400, "Ник должен содержать от 3 до 24 символов");
            return;
        }

        if (!clean.matches("^[a-zA-Z0-9_]+$")) {
            sendError(resp, 400, "Ник может содержать только латинские буквы, цифры и символ _");
            return;
        }

        if ("onboarding".equalsIgnoreCase(mode) && clean.equalsIgnoreCase("admin")) {
            sendError(resp, 400, "Выберите персональный никнейм (не 'admin')");
            return;
        }

        boolean exists = plugin.getDatabaseManager().getAdminByUsername(clean).isPresent();
        if (exists) {
            sendError(resp, 409, "Пользователь с таким ником уже зарегистрирован");
            return;
        }

        sendSuccess(resp, Map.of(
            "available", true,
            "username", clean,
            "message", "Никнейм свободен"
        ));
    }

    private void handleTotpSetup(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username = req.getParameter("username");
        if (username == null || username.isBlank()) {
            username = "admin";
        }
        String secret = TotpUtils.generateSecret();
        String otpUrl = TotpUtils.getOtpAuthUrl("WebAdmin", username, secret);
        sendSuccess(resp, Map.of(
            "secret", secret,
            "otpUrl", otpUrl
        ));
    }

    private void handleSetupOwner(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (plugin.getAdminManager().hasOwner()) {
            sendError(resp, 400, "Управляющий уже настроен");
            return;
        }

        Map<String, Object> body = readJsonBody(req);
        String username = stringOrNull(body.get("username"));
        String password = stringOrNull(body.get("password"));
        String totpSecret = stringOrNull(body.get("totpSecret"));
        String totpCode = stringOrNull(body.get("totpCode"));

        if (username == null || password == null || totpSecret == null || totpCode == null) {
            sendError(resp, 400, "Заполните все поля (ник, пароль, секрет и код подтверждения 2FA)");
            return;
        }

        String ip = req.getRemoteAddr();
        AdminManager.OnboardingResult result = plugin.getAdminManager().completeMasterOnboarding(
            username, password, totpSecret, totpCode, ip
        );

        if (!result.success()) {
            sendError(resp, 400, result.message());
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", result.session().token());
        data.put("role", result.role().name());
        data.put("permissions", result.role().permissions().stream().map(Enum::name).toList());
        data.put("uiPreferences", "{}");
        data.put("backupCodes", result.backupCodes());
        sendSuccess(resp, data);
    }

    private void handleRegister(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String username = stringOrNull(body.get("username"));
        String password = stringOrNull(body.get("password"));
        String totpSecret = stringOrNull(body.get("totpSecret"));
        String totpCode = stringOrNull(body.get("totpCode"));

        if (username == null || password == null || totpSecret == null || totpCode == null) {
            sendError(resp, 400, "Заполните все поля, включая привязку Google Authenticator");
            return;
        }

        AdminManager.RegisterResult result = plugin.getAdminManager().registerCandidate(
            username, password, totpSecret, totpCode
        );

        switch (result.status()) {
            case OK, PENDING -> sendSuccess(resp, Map.of(
                "status", "PENDING_APPROVAL",
                "message", result.message(),
                "backupCodes", result.backupCodes()
            ));
            case ALREADY_EXISTS -> sendError(resp, 400, result.message());
            case INVALID_TOTP -> sendError(resp, 400, result.message());
            case INVALID_INPUT -> sendError(resp, 400, result.message());
        }
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
        String userAgent = req.getHeader("User-Agent");
        String ipKey = "ip:" + ip;
        String userKey = "user:" + username.toLowerCase();
        LoginAttemptTracker attemptTracker = plugin.getLoginAttemptTracker();

        long lockedSeconds = attemptTracker.getLockedRemainingSeconds(ipKey, userKey);
        if (lockedSeconds > 0) {
            long minutes = Math.max(1, (lockedSeconds + 59) / 60);
            if (plugin.getSecurityWebhookService() != null) {
                plugin.getSecurityWebhookService().sendBruteForceAlert(ip, username, 5);
            }
            sendError(resp, 429, "Слишком много неудачных попыток входа. Повторите через " + minutes + " мин.");
            return;
        }

        AdminManager.LoginResult result = plugin.getAdminManager().login(username, password, ip, userAgent);
        switch (result.status()) {
            case NOT_FOUND, INVALID_CREDENTIALS -> {
                attemptTracker.recordFailure(ipKey, userKey);
                plugin.getLogManager().logWebAction(username, "Неудачная попытка входа");
                sendError(resp, 401, "Неверный ник или пароль");
            }
            case NEED_ONBOARDING -> {
                sendSuccess(resp, Map.of("status", "NEED_ONBOARDING"));
            }
            case PENDING_APPROVAL -> {
                sendError(resp, 403, "Ваш аккаунт ожидает подтверждения главным администратором");
            }
            case NEED_2FA -> {
                sendSuccess(resp, Map.of(
                    "status", "NEED_2FA",
                    "username", result.admin().username()
                ));
            }
            case NEED_SET_PASSWORD -> {
                sendSuccess(resp, Map.of("status", "NEED_SET_PASSWORD"));
            }
            case SUCCESS -> {
                attemptTracker.recordSuccess(ipKey, userKey);
                sendLoginSuccess(resp, result);
            }
        }
    }

    private void handleVerify2fa(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String username = stringOrNull(body.get("username"));
        String code = stringOrNull(body.get("code"));

        if (username == null || code == null) {
            sendError(resp, 400, "Не указаны ник или код подтверждения");
            return;
        }

        String ip = req.getRemoteAddr();
        String userAgent = req.getHeader("User-Agent");
        AdminManager.LoginResult result = plugin.getAdminManager().verify2fa(username, code, ip, userAgent);
        if (result.status() == AdminManager.LoginStatus.SUCCESS) {
            sendLoginSuccess(resp, result);
        } else {
            plugin.getLogManager().logWebAction(username, "Неверный код 2FA или резервный код");
            sendError(resp, 401, "Неверный код Google Authenticator или резервный ключ восстановления.");
        }
    }

    private void sendLoginSuccess(HttpServletResponse resp, AdminManager.LoginResult result) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", result.session().token());
        data.put("username", result.session().adminUsername());
        data.put("role", result.role().name());
        data.put("permissions", result.role().permissions().stream().map(Enum::name).toList());
        data.put("uiPreferences", result.admin() != null ? result.admin().uiPreferences() : "{}");
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
        Optional<WebAdmin> adminOpt = plugin.getDatabaseManager().getAdminById(session.adminId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", session.adminId());
        data.put("username", session.adminUsername());
        data.put("role", role.name());
        data.put("isOwner", role.isOwner());
        data.put("permissions", role.permissions().stream().map(Enum::name).toList());
        data.put("uiPreferences", adminOpt.map(WebAdmin::uiPreferences).orElse("{}"));
        data.put("totpEnabled", adminOpt.map(WebAdmin::totpEnabled).orElse(false));
        data.put("last2faIp", adminOpt.map(WebAdmin::last2faIp).orElse("—"));

        int remainingCodes = 0;
        if (adminOpt.isPresent() && adminOpt.get().backupCodes() != null) {
            try {
                remainingCodes = JsonUtils.fromJsonList(adminOpt.get().backupCodes(), String.class).size();
            } catch (Exception ignored) {}
        }
        data.put("backupCodesCount", remainingCodes);

        sendSuccess(resp, data);
    }

    private void handleSessions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        WebSession current = sessionOpt.get();
        List<WebSession> list = plugin.getDatabaseManager().getSessionsForAdmin(current.adminId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (WebSession s : list) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tokenPrefix", s.token().length() > 8 ? s.token().substring(0, 8) + "..." : s.token());
            map.put("ip", s.ip() != null ? s.ip() : "—");
            map.put("userAgent", s.userAgent() != null ? s.userAgent() : "Неизвестный браузер");
            map.put("createdAt", s.createdAt());
            map.put("lastUsedAt", s.lastUsedAt());
            map.put("expiresAt", s.expiresAt());
            map.put("isCurrent", s.token().equals(current.token()));
            result.add(map);
        }
        sendSuccess(resp, result);
    }

    private void handleTerminateOtherSessions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        WebSession current = sessionOpt.get();
        plugin.getDatabaseManager().deleteOtherSessions(current.adminId(), current.token());
        plugin.getSessionManager().invalidateOtherSessions(current.adminId(), current.token());
        plugin.getLogManager().logWebAction(current.adminUsername(), "Завершил все другие активные сессии");
        sendSuccess(resp, Map.of("message", "Все остальные сессии успешно завершены"));
    }

    private void handleRegenerateBackupCodes(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        WebSession current = sessionOpt.get();
        List<String> plainCodes = plugin.getAdminManager().regenerateBackupCodes(current.adminId());
        sendSuccess(resp, Map.of(
            "backupCodes", plainCodes,
            "message", "Новые резервные коды успешно созданы. Сохраните их в надёжном месте!"
        ));
    }

    private void handleUpdatePreferences(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        Map<String, Object> body = readJsonBody(req);
        Object prefObj = body.get("uiPreferences");
        String json = (prefObj instanceof Map<?, ?>) ? JsonUtils.toJson(prefObj) : String.valueOf(prefObj);

        plugin.getDatabaseManager().updateAdminPreferences(sessionOpt.get().adminId(), json);
        sendSuccess(resp, Map.of("message", "Настройки сохранены"));
    }

    private void handleChangePassword(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        Map<String, Object> body = readJsonBody(req);
        String oldPassword = stringOrNull(body.get("oldPassword"));
        String newPassword = stringOrNull(body.get("newPassword"));

        if (oldPassword == null || newPassword == null || newPassword.length() < 4) {
            sendError(resp, 400, "Новый пароль должен содержать не менее 4 символов");
            return;
        }

        Optional<WebAdmin> adminOpt = plugin.getDatabaseManager().getAdminById(sessionOpt.get().adminId());
        if (adminOpt.isEmpty()) {
            sendError(resp, 404, "Администратор не найден");
            return;
        }

        WebAdmin admin = adminOpt.get();
        if (admin.passwordHash() != null && !PasswordUtils.verify(oldPassword, admin.passwordHash())) {
            sendError(resp, 400, "Неверный текущий пароль");
            return;
        }

        plugin.getDatabaseManager().setAdminPassword(admin.id(), PasswordUtils.hash(newPassword));
        plugin.getLogManager().logWebAction(admin.username(), "Изменил свой пароль");
        sendSuccess(resp, Map.of("message", "Пароль успешно изменён"));
    }

    private void handleAllSessions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ADMINS);
        if (sessionOpt.isEmpty()) return;

        List<Map<String, Object>> sessions = plugin.getDatabaseManager().getAllActiveSessionsDetailed();
        sendSuccess(resp, sessions);
    }

    private void handleLoginHistory(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ADMINS);
        if (sessionOpt.isEmpty()) return;

        List<me.lovelace.loveWebAdmin.models.LogEntry> logs = plugin.getDatabaseManager().getWebLogs(100, 0);
        List<Map<String, Object>> loginLogs = new ArrayList<>();
        for (var l : logs) {
            String act = l.action().toLowerCase();
            if (act.contains("вход") || act.contains("login") || act.contains("вышел") || act.contains("2fa") || act.contains("парол")) {
                loginLogs.add(Map.of(
                    "id", l.id(),
                    "username", l.actor(),
                    "action", l.action(),
                    "timestamp", l.timestamp()
                ));
            }
        }
        sendSuccess(resp, loginLogs);
    }

    private void handleTerminateSpecificSession(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ADMINS);
        if (sessionOpt.isEmpty()) return;

        String path = req.getPathInfo();
        String token = path.substring("/sessions/".length(), path.indexOf("/terminate"));
        plugin.getSessionManager().invalidate(token);
        plugin.getDatabaseManager().terminateSession(token);
        plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(), "Завершил сессию токена " + (token.length() > 8 ? token.substring(0, 8) + "..." : token));
        sendSuccess(resp, Map.of("message", "Сессия успешно завершена"));
    }
}
