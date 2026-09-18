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

        if ("/api/staff/shifts".equals(servletPath) || ("/api/staff".equals(servletPath) && "/shifts".equals(pathInfo))) {
            handleStaffShifts(req, resp);
            return;
        }

        if ("/api/me".equals(servletPath)) {
            if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
                handleMe(req, resp);
                return;
            }
            if ("/sessions".equals(pathInfo)) {
                handleSessions(req, resp);
                return;
            }
            if ("/shift".equals(pathInfo)) {
                handleMyShift(req, resp);
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
            if ("/preferences".equals(pathInfo) || "/".equals(pathInfo) || pathInfo == null || pathInfo.isEmpty()) {
                handleUpdatePreferences(req, resp);
                return;
            }
            if ("/password".equals(pathInfo)) {
                handleChangePassword(req, resp);
                return;
            }
            if ("/shift".equals(pathInfo)) {
                handleToggleShift(req, resp);
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
            case "/validate-invite" -> handleValidateInvite(req, resp);
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
        boolean debugMode = plugin.isDebugMode();
        boolean maintenance = plugin.isMaintenanceMode();
        String maintenanceMsg = plugin.getMaintenanceMessage();
        boolean strictIp = plugin.getConfig().getBoolean("security.strict-ip", false);
        sendSuccess(resp, Map.of(
            "ownerExists", ownerExists,
            "initialSetupNeeded", initialSetup,
            "debugMode", debugMode,
            "maintenance", maintenance,
            "maintenanceMessage", maintenanceMsg != null ? maintenanceMsg : "Ведутся технические работы",
            "strictIp", strictIp
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
        String setupToken = stringOrNull(body.get("setupToken"));
        String username = stringOrNull(body.get("username"));
        String password = stringOrNull(body.get("password"));
        String totpSecret = stringOrNull(body.get("totpSecret"));
        String totpCode = stringOrNull(body.get("totpCode"));

        if (setupToken == null || !plugin.getAdminManager().validateAndConsumeSetupToken(setupToken)) {
            sendError(resp, 403, "Неверный или просроченный одноразовый токен настройки. Сгенерируйте его в консоли сервера командой: /lovewebadmin generatetoken");
            return;
        }

        if (username == null || password == null) {
            sendError(resp, 400, "Заполните логин и пароль");
            return;
        }

        if (password.length() < 10) {
            sendError(resp, 400, "Минимальная длина пароля управляющего — 10 символов");
            return;
        }

        if (!plugin.isDebugMode() && (totpSecret == null || totpCode == null)) {
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

    private void handleValidateInvite(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String code = stringOrNull(body.get("code"));
        if (code == null || code.isBlank()) {
            sendError(resp, 400, "Укажите код приглашения");
            return;
        }

        var inviteOpt = plugin.getDatabaseManager().getInviteByCode(code.trim());
        if (inviteOpt.isEmpty()) {
            sendError(resp, 404, "Код приглашения не найден");
            return;
        }

        var invite = inviteOpt.get();
        if (!"PENDING".equalsIgnoreCase(invite.status())) {
            sendError(resp, 400, "Этот код приглашения уже был использован или отменён");
            return;
        }

        long now = System.currentTimeMillis() / 1000L;
        if (invite.expiresAt() > 0 && now > invite.expiresAt()) {
            sendError(resp, 400, "Срок действия кода приглашения истёк");
            return;
        }

        String roleName = plugin.getDatabaseManager().getRoleById(invite.roleId())
            .map(WebRole::name).orElse("Сотрудник");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", invite.username());
        data.put("roleId", invite.roleId());
        data.put("roleName", roleName);
        data.put("roleExpiresAt", invite.roleExpiresAt());
        sendSuccess(resp, data);
    }

    private void handleRegister(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String inviteCode = stringOrNull(body.get("inviteCode"));
        String password = stringOrNull(body.get("password"));
        String totpSecret = stringOrNull(body.get("totpSecret"));
        String totpCode = stringOrNull(body.get("totpCode"));

        if (inviteCode == null || inviteCode.isBlank()) {
            sendError(resp, 400, "Регистрация возможна только по коду приглашения");
            return;
        }

        var inviteOpt = plugin.getDatabaseManager().getInviteByCode(inviteCode.trim());
        if (inviteOpt.isEmpty() || !"PENDING".equalsIgnoreCase(inviteOpt.get().status())) {
            sendError(resp, 400, "Неверный или недействительный код приглашения");
            return;
        }

        var invite = inviteOpt.get();
        long now = System.currentTimeMillis() / 1000L;
        if (invite.expiresAt() > 0 && now > invite.expiresAt()) {
            sendError(resp, 400, "Срок действия кода приглашения истёк");
            return;
        }

        String username = invite.username();
        if (plugin.getDatabaseManager().getAdminByUsername(username).isPresent()) {
            sendError(resp, 400, "Сотрудник с таким никнеймом уже зарегистрирован");
            return;
        }

        if (password == null || password.isBlank()) {
            sendError(resp, 400, "Заполните пароль");
            return;
        }

        String strengthErr = PasswordUtils.validateStrength(password);
        if (strengthErr != null) {
            sendError(resp, 400, strengthErr);
            return;
        }

        if (!plugin.isDebugMode()) {
            if (totpSecret == null || totpCode == null) {
                sendError(resp, 400, "Необходимо привязать Google Authenticator и ввести 6-значный код");
                return;
            }
            if (!TotpUtils.verifyCode(totpSecret, totpCode)) {
                sendError(resp, 400, "Неверный код Google Authenticator");
                return;
            }
        }

        List<String> plainBackupCodes = TotpUtils.generateBackupCodes(8);
        List<String> hashedCodes = plainBackupCodes.stream().map(TotpUtils::hashBackupCode).toList();
        String backupCodesJson = JsonUtils.toJson(hashedCodes);

        WebAdmin admin = new WebAdmin(
            0,
            username,
            PasswordUtils.hash(password),
            invite.roleId(),
            now,
            now,
            totpSecret,
            true,
            now,
            req.getRemoteAddr(),
            "ACTIVE",
            "{}",
            backupCodesJson
        );

        plugin.getDatabaseManager().saveAdmin(admin);
        plugin.getDatabaseManager().markInviteUsed(invite.id());
        if (invite.roleExpiresAt() > 0) {
            plugin.getDatabaseManager().getAdminByUsername(username)
                .ifPresent(saved -> plugin.getDatabaseManager().setAdminRoleExpiry(saved.id(), invite.roleExpiresAt()));
        }

        String roleName = plugin.getDatabaseManager().getRoleById(invite.roleId())
            .map(WebRole::name).orElse("Сотрудник");
        plugin.getLogManager().logWebAction(username, "Завершил регистрацию по приглашению (роль: " + roleName + ")");

        sendSuccess(resp, Map.of(
            "status", "ACTIVE",
            "message", "Регистрация успешно завершена",
            "backupCodes", plainBackupCodes
        ));
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
                boolean locked = attemptTracker.recordFailureAndCheckNewlyLocked(ipKey, userKey);
                plugin.getLogManager().logWebAction(username, "Неудачная попытка входа с IP " + ip);
                if (locked) {
                    if (plugin.getSecurityWebhookService() != null) {
                        plugin.getSecurityWebhookService().sendBruteForceAlert(ip, username, 5);
                    }
                    if (plugin.getNotificationManager() != null) {
                        plugin.getNotificationManager().broadcast(
                            "Брутфорс атака!",
                            "IP-адрес " + ip + " временно заблокирован после серии неудачных попыток входа под пользователем '" + username + "'.",
                            "CRITICAL",
                            "security"
                        );
                    }
                }
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

        // Отдельные ключи от пароля (handleLogin) - неверный TOTP/резервный код не должен
        // блокировать логин по паролю и наоборот, но перебор 6-значного кода/резервного ключа
        // здесь раньше вообще не имел лимита попыток, в отличие от пароля выше.
        String ipKey = "2fa-ip:" + ip;
        String userKey = "2fa-user:" + username.toLowerCase();
        LoginAttemptTracker attemptTracker = plugin.getLoginAttemptTracker();

        long lockedSeconds = attemptTracker.getLockedRemainingSeconds(ipKey, userKey);
        if (lockedSeconds > 0) {
            long minutes = Math.max(1, (lockedSeconds + 59) / 60);
            if (plugin.getSecurityWebhookService() != null) {
                plugin.getSecurityWebhookService().sendBruteForceAlert(ip, username, 5);
            }
            sendError(resp, 429, "Слишком много неудачных попыток 2FA. Повторите через " + minutes + " мин.");
            return;
        }

        AdminManager.LoginResult result = plugin.getAdminManager().verify2fa(username, code, ip, userAgent);
        if (result.status() == AdminManager.LoginStatus.SUCCESS) {
            attemptTracker.recordSuccess(ipKey, userKey);
            sendLoginSuccess(resp, result);
        } else {
            attemptTracker.recordFailure(ipKey, userKey);
            plugin.getLogManager().logWebAction(username, "Неверный код 2FA или резервный код");
            sendError(resp, 401, "Неверный код Google Authenticator или резервный ключ восстановления.");
        }
    }

    private void sendLoginSuccess(HttpServletResponse resp, AdminManager.LoginResult result) throws IOException {
        if (plugin.isMaintenanceMode()) {
            boolean canBypass = result.role().isOwner()
                || result.role().permissions().contains(Permission.MANAGE_ADMINS)
                || result.role().permissions().contains(Permission.BYPASS_MAINTENANCE);
            if (!canBypass) {
                if (result.session() != null) {
                    plugin.getSessionManager().invalidate(result.session().token());
                }
                sendError(resp, 503, plugin.getMaintenanceMessage() != null ? plugin.getMaintenanceMessage() : "Ведутся технические работы. Доступ разрешён только руководству.");
                return;
            }
        }

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
        String confirmPassword = stringOrNull(body.get("confirmPassword"));
        String totpCode = stringOrNull(body.get("totpCode"));

        if (oldPassword == null || newPassword == null) {
            sendError(resp, 400, "Заполните старый и новый пароли");
            return;
        }

        Optional<WebAdmin> adminOpt = plugin.getDatabaseManager().getAdminById(sessionOpt.get().adminId());
        if (adminOpt.isEmpty()) {
            sendError(resp, 404, "Администратор не найден");
            return;
        }

        WebAdmin admin = adminOpt.get();
        // МГНОВЕННЫЙ КИК С САЙТА ПРИ НЕВЕРНОМ СТАРОМ ПАРОЛЕ
        if (admin.passwordHash() != null && !PasswordUtils.verify(oldPassword, admin.passwordHash())) {
            plugin.getSessionManager().invalidate(sessionOpt.get().token());
            plugin.getDatabaseManager().deleteSession(sessionOpt.get().token());
            plugin.getLogManager().logWebAction(admin.username(), "Введён неверный старый пароль при попытке смены. Сессия принудительно аннулирована.");
            sendError(resp, 401, "Неверный текущий пароль! Ваша сессия аннулирована.");
            return;
        }

        if (confirmPassword != null && !newPassword.equals(confirmPassword)) {
            sendError(resp, 400, "Новые пароли не совпадают");
            return;
        }

        if (newPassword.length() < 10) {
            sendError(resp, 400, "Новый пароль должен содержать не менее 10 символов");
            return;
        }

        if (admin.totpEnabled() && admin.totpSecret() != null && !admin.totpSecret().isBlank()) {
            if (totpCode == null || totpCode.trim().isBlank()) {
                sendError(resp, 400, "Требуется 6-значный код Google Authenticator (2FA)");
                return;
            }
            if (!TotpUtils.verifyCode(admin.totpSecret(), totpCode.trim())) {
                sendError(resp, 400, "Неверный код 2FA");
                return;
            }
        }

        plugin.getDatabaseManager().setAdminPassword(admin.id(), PasswordUtils.hash(newPassword));
        plugin.getLogManager().logWebAction(admin.username(), "Успешно изменил пароль");
        sendSuccess(resp, Map.of("message", "Пароль успешно изменён"));
    }

    private void handleToggleShift(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        Map<String, Object> body = readJsonBody(req);
        boolean onShift = Boolean.TRUE.equals(body.get("onShift"));
        plugin.getDatabaseManager().setAdminShiftStatus(sessionOpt.get().adminId(), onShift);
        plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(), onShift ? "Заступил на смену" : "Завершил смену");
        sendSuccess(resp, Map.of("onShift", onShift, "message", onShift ? "Вы заступили на смену" : "Вы завершили смену"));
    }

    private void handleMyShift(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        List<Map<String, Object>> list = plugin.getDatabaseManager().getStaffOnShift();
        boolean onShift = list.stream().anyMatch(m -> sessionOpt.get().adminUsername().equalsIgnoreCase(String.valueOf(m.get("username"))));
        sendSuccess(resp, Map.of("onShift", onShift));
    }

    private void handleStaffShifts(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        List<Map<String, Object>> staffOnShift = plugin.getDatabaseManager().getStaffOnShift();
        sendSuccess(resp, staffOnShift);
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
