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

public class ApiAuthHandler extends ApiHandlerSupport {

    public ApiAuthHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if ("/status".equals(pathInfo)) {
            handleStatus(resp);
            return;
        }
        if ("/totp-setup".equals(pathInfo)) {
            String username = req.getParameter("username");
            if (username == null || username.isBlank()) username = "admin";
            String secret = TotpUtils.generateSecret();
            String otpUrl = TotpUtils.getOtpAuthUrl("WebAdmin", username, secret);
            sendSuccess(resp, Map.of("secret", secret, "otpUrl", otpUrl));
            return;
        }
        if ("/api/me".equals(req.getServletPath()) && (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo))) {
            handleMe(req, resp);
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
            case "/verify-2fa" -> handleVerify2fa(req, resp);
            case "/logout" -> handleLogout(req, resp);
            case "/setup-owner" -> handleSetupOwner(req, resp);
            case "/prepare-setup-owner" -> handlePrepareSetupOwner(req, resp);
            case "/validate-invite" -> handleValidateInvite(req, resp);
            case "/register" -> handleRegister(req, resp);
            default -> sendError(resp, 404, "Не найдено");
        }
    }

    private void handleStatus(HttpServletResponse resp) throws IOException {
        sendSuccess(resp, Map.of(
            "ownerExists", plugin.getAdminManager().hasOwner(),
            "initialSetupNeeded", plugin.getAdminManager().isInitialSetupNeeded(),
            "debugMode", plugin.isDebugMode(),
            "maintenance", plugin.isMaintenanceMode(),
            "maintenanceMessage", plugin.getMaintenanceMessage() != null ? plugin.getMaintenanceMessage() : "",
            "strictIp", plugin.getConfig().getBoolean("security.strict-ip", false)
        ));
    }

    private void handlePrepareSetupOwner(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (plugin.getAdminManager().hasOwner()) {
            sendError(resp, 400, "Управляющий уже настроен");
            return;
        }
        Map<String, Object> body = readJsonBody(req);
        String setupToken = stringOrNull(body.get("setupToken"));
        String username = stringOrNull(body.get("username"));
        if (setupToken == null || setupToken.isBlank()) {
            sendError(resp, 400, "Укажите токен из консоли сервера");
            return;
        }
        if (!plugin.getAdminManager().peekSetupToken(setupToken)) {
            sendError(resp, 403, "Неверный токен");
            return;
        }
        if (username == null || username.isBlank()) username = "owner";
        String secret = plugin.getAdminManager().prepareOwnerTotp(setupToken, username);
        if (secret == null) {
            sendError(resp, 403, "Не удалось подготовить 2FA");
            return;
        }
        String otpUrl = TotpUtils.getOtpAuthUrl("WebAdmin", username, secret);
        sendSuccess(resp, Map.of("secret", secret, "otpUrl", otpUrl, "username", username));
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
        if (setupToken == null || !plugin.getAdminManager().peekSetupToken(setupToken)) {
            sendError(resp, 403, "Неверный токен из консоли сервера");
            return;
        }
        if (username == null || password == null) {
            sendError(resp, 400, "Заполните логин и пароль");
            return;
        }
        String pwdErr = PasswordUtils.validateStrength(password);
        if (pwdErr != null) {
            sendError(resp, 400, pwdErr);
            return;
        }
        String prepared = plugin.getAdminManager().getPreparedOwnerTotp(setupToken);
        if (prepared != null && !prepared.isBlank()) totpSecret = prepared;
        if (!plugin.isDebugMode() && (totpSecret == null || totpCode == null)) {
            sendError(resp, 400, "Нужен код 2FA");
            return;
        }
        if (!plugin.getAdminManager().validateAndConsumeSetupToken(setupToken)) {
            sendError(resp, 403, "Токен уже использован");
            return;
        }
        plugin.getAdminManager().clearPreparedOwnerTotp(setupToken);
        AdminManager.OnboardingResult result = plugin.getAdminManager().completeMasterOnboarding(
            username, password, totpSecret, totpCode, req.getRemoteAddr());
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
            sendError(resp, 404, "Код не найден");
            return;
        }
        var invite = inviteOpt.get();
        if (!"PENDING".equalsIgnoreCase(invite.status())) {
            sendError(resp, 400, "Код уже использован");
            return;
        }
        String roleName = plugin.getDatabaseManager().getRoleById(invite.roleId()).map(WebRole::name).orElse("Сотрудник");
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
            sendError(resp, 400, "Нужен код приглашения");
            return;
        }
        var inviteOpt = plugin.getDatabaseManager().getInviteByCode(inviteCode.trim());
        if (inviteOpt.isEmpty() || !"PENDING".equalsIgnoreCase(inviteOpt.get().status())) {
            sendError(resp, 400, "Неверный код");
            return;
        }
        var invite = inviteOpt.get();
        String username = invite.username();
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
            if (totpSecret == null || totpCode == null || !TotpUtils.verifyCode(totpSecret, totpCode)) {
                sendError(resp, 400, "Неверный код 2FA");
                return;
            }
        }
        long now = System.currentTimeMillis() / 1000L;
        List<String> plainBackupCodes = TotpUtils.generateBackupCodes(8);
        List<String> hashedCodes = plainBackupCodes.stream().map(TotpUtils::hashBackupCode).toList();
        WebAdmin admin = new WebAdmin(0, username, PasswordUtils.hash(password), invite.roleId(),
            now, now, totpSecret, true, now, req.getRemoteAddr(), "ACTIVE", "{}", JsonUtils.toJson(hashedCodes));
        plugin.getDatabaseManager().saveAdmin(admin);
        plugin.getDatabaseManager().markInviteUsed(invite.id());
        sendSuccess(resp, Map.of("status", "ACTIVE", "message", "OK", "backupCodes", plainBackupCodes));
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
        AdminManager.LoginResult result = plugin.getAdminManager().login(username, password, ip, userAgent);
        switch (result.status()) {
            case SUCCESS -> sendLoginSuccess(resp, result);
            case NEED_2FA -> sendSuccess(resp, Map.of("need2fa", true, "username", username));
            case NEED_ONBOARDING -> sendSuccess(resp, Map.of("needOnboarding", true));
            case PENDING_APPROVAL -> sendError(resp, 403, "Заявка ожидает утверждения");
            default -> sendError(resp, 401, "Неверный логин или пароль");
        }
    }

    private void handleVerify2fa(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String username = stringOrNull(body.get("username"));
        String code = stringOrNull(body.get("code"));
        if (username == null || code == null) {
            sendError(resp, 400, "Нужны username и code");
            return;
        }
        AdminManager.LoginResult result = plugin.getAdminManager().verify2fa(username, code, req.getRemoteAddr(), req.getHeader("User-Agent"));
        if (result.status() == AdminManager.LoginStatus.SUCCESS) {
            sendLoginSuccess(resp, result);
        } else {
            sendError(resp, 401, "Неверный код 2FA или резервный код");
        }
    }

    private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            plugin.getSessionManager().invalidate(auth.substring(7).trim());
        }
        sendSuccess(resp, Map.of("message", "OK"));
    }

    private void handleMe(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;
        WebSession session = sessionOpt.get();
        Optional<WebAdmin> adminOpt = plugin.getDatabaseManager().getAdminByUsername(session.adminUsername());
        Optional<WebRole> roleOpt = plugin.getDatabaseManager().getRoleById(session.roleId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", session.adminUsername());
        data.put("role", roleOpt.map(WebRole::name).orElse(""));
        data.put("permissions", roleOpt.map(r -> r.permissions().stream().map(Enum::name).toList()).orElse(List.of()));
        data.put("uiPreferences", adminOpt.map(WebAdmin::uiPreferences).orElse("{}"));
        sendSuccess(resp, data);
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
}
