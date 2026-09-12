package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebRole;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import me.lovelace.loveWebAdmin.utils.PasswordUtils;
import me.lovelace.loveWebAdmin.utils.TotpUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Управление веб-администраторами: онбординг, регистрация, 2FA Google Authenticator,
 * вход, утверждение заявок, роли и сброс паролей.
 */
public class AdminManager {

    private static final long SEVEN_DAYS_SECONDS = 7L * 24L * 3600L;

    private final LoveWebAdmin plugin;

    public AdminManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public boolean hasOwner() {
        return plugin.getDatabaseManager().hasActiveOwner();
    }

    public boolean isInitialSetupNeeded() {
        return plugin.getDatabaseManager().isInitialSetupNeeded();
    }

    /**
     * Первичный онбординг главного администратора.
     * Сменяет дефолтный логин 'admin' на новый ник, устанавливает новый пароль
     * и подтверждает привязку Google Authenticator 6-значным кодом.
     */
    public synchronized OnboardingResult completeMasterOnboarding(
            String newUsername, String newPassword, String totpSecret, String totpCode, String ip) {

        if (newUsername == null || newUsername.trim().equalsIgnoreCase("admin") || newUsername.trim().length() < 2) {
            return new OnboardingResult(false, "Выберите персональный никнейм (не 'admin')", null, null);
        }
        if (newPassword == null || newPassword.length() < 4) {
            return new OnboardingResult(false, "Пароль слишком короткий (минимум 4 символа)", null, null);
        }
        if (!TotpUtils.verifyCode(totpSecret, totpCode)) {
            return new OnboardingResult(false, "Неверный код из Google Authenticator. Проверьте время на телефоне.", null, null);
        }

        // Ищем мастер-аккаунт
        Optional<WebAdmin> masterOpt = plugin.getDatabaseManager().getAdminByUsername("admin");
        if (masterOpt.isEmpty()) {
            masterOpt = plugin.getDatabaseManager().getAllAdmins().stream().findFirst();
        }

        int adminId = masterOpt.map(WebAdmin::id).orElse(0);
        Optional<WebRole> ownerRole = plugin.getDatabaseManager().getRoleByName("Управляющий");
        if (ownerRole.isEmpty()) {
            return new OnboardingResult(false, "Роль Управляющего не найдена", null, null, List.of());
        }

        List<String> plainBackupCodes = TotpUtils.generateBackupCodes(8);
        List<String> hashedCodes = plainBackupCodes.stream().map(TotpUtils::hashBackupCode).toList();
        String backupCodesJson = JsonUtils.toJson(hashedCodes);

        long now = System.currentTimeMillis() / 1000L;
        WebAdmin updated = new WebAdmin(
            adminId,
            newUsername.trim(),
            PasswordUtils.hash(newPassword),
            ownerRole.get().id(),
            now,
            now,
            totpSecret,
            true,
            now,
            ip,
            "ACTIVE",
            "{}",
            backupCodesJson
        );
        plugin.getDatabaseManager().saveAdmin(updated);

        // Получаем сохранённую запись
        WebAdmin savedAdmin = plugin.getDatabaseManager().getAdminByUsername(newUsername.trim()).orElse(updated);

        if (plugin.getConfig().getBoolean("luckperms.sync-enabled", true)) {
            String ownerLpGroup = plugin.getConfig().getString("luckperms.owner-lp-group", "owner");
            plugin.getLuckPermsManager().assignGroup(newUsername.trim(), ownerLpGroup);
        }
        plugin.getCommandLogListener().refreshCache();
        plugin.getLogManager().logWebAction(newUsername.trim(), "Завершил первичную настройку панели и активировал 2FA");

        WebSession session = plugin.getSessionManager().createSession(savedAdmin, ownerRole.get(), ip, null);
        return new OnboardingResult(true, "Успешно", session, ownerRole.get(), plainBackupCodes);
    }

    /**
     * Подача заявки на регистрацию нового администратора/модератора.
     * Требует обязательной привязки Google Authenticator сразу при регистрации.
     */
    public synchronized RegisterResult registerCandidate(String username, String password, String totpSecret, String totpCode) {
        if (username == null || username.trim().length() < 2) {
            return new RegisterResult(RegisterStatus.INVALID_INPUT, "Слишком короткий никнейм", List.of());
        }
        if (password == null || password.length() < 4) {
            return new RegisterResult(RegisterStatus.INVALID_INPUT, "Пароль слишком короткий (минимум 4 символа)", List.of());
        }
        if (plugin.getDatabaseManager().getAdminByUsername(username.trim()).isPresent()) {
            return new RegisterResult(RegisterStatus.ALREADY_EXISTS, "Пользователь с таким ником уже зарегистрирован", List.of());
        }
        if (!TotpUtils.verifyCode(totpSecret, totpCode)) {
            return new RegisterResult(RegisterStatus.INVALID_TOTP, "Неверный код Google Authenticator", List.of());
        }

        Optional<WebRole> modRole = plugin.getDatabaseManager().getRoleByName("Модератор");
        int roleId = modRole.map(WebRole::id).orElse(1);

        List<String> plainBackupCodes = TotpUtils.generateBackupCodes(8);
        List<String> hashedCodes = plainBackupCodes.stream().map(TotpUtils::hashBackupCode).toList();
        String backupCodesJson = JsonUtils.toJson(hashedCodes);

        long now = System.currentTimeMillis() / 1000L;
        WebAdmin candidate = new WebAdmin(
            0,
            username.trim(),
            PasswordUtils.hash(password),
            roleId,
            now,
            0,
            totpSecret,
            true,
            0,
            null,
            "PENDING_APPROVAL",
            "{}",
            backupCodesJson
        );
        plugin.getDatabaseManager().saveAdmin(candidate);
        plugin.getLogManager().logWebAction(username.trim(), "Подал заявку на регистрацию в WebAdmin");

        if (plugin.getSecurityWebhookService() != null) {
            plugin.getSecurityWebhookService().sendRegistrationAlert(username.trim(), modRole.map(WebRole::name).orElse("Модератор"));
        }

        return new RegisterResult(RegisterStatus.PENDING, "Заявка успешно отправлена и ожидает утверждения главным администратором", plainBackupCodes);
    }

    /**
     * Вход в систему с проверкой пароля, статуса и необходимости 2FA.
     */
    public LoginResult login(String username, String password, String ip) {
        return login(username, password, ip, null);
    }

    public LoginResult login(String username, String password, String ip, String userAgent) {
        Optional<WebAdmin> adminOpt = plugin.getDatabaseManager().getAdminByUsername(username);
        if (adminOpt.isEmpty()) return new LoginResult(LoginStatus.NOT_FOUND, null, null, null);

        WebAdmin admin = adminOpt.get();

        if ("NEED_ONBOARDING".equalsIgnoreCase(admin.status())) {
            if (!PasswordUtils.verify(password, admin.passwordHash())) {
                return new LoginResult(LoginStatus.INVALID_CREDENTIALS, null, null, null);
            }
            return new LoginResult(LoginStatus.NEED_ONBOARDING, null, null, admin);
        }

        if ("PENDING_APPROVAL".equalsIgnoreCase(admin.status())) {
            return new LoginResult(LoginStatus.PENDING_APPROVAL, null, null, admin);
        }

        if (admin.passwordHash() == null) {
            return new LoginResult(LoginStatus.NEED_SET_PASSWORD, null, null, admin);
        }

        if (!PasswordUtils.verify(password, admin.passwordHash())) {
            return new LoginResult(LoginStatus.INVALID_CREDENTIALS, null, null, null);
        }

        // Проверка 2FA (раз в неделю или при смене IP)
        if (admin.totpEnabled()) {
            long now = System.currentTimeMillis() / 1000L;
            boolean ipChanged = admin.last2faIp() == null || !admin.last2faIp().equals(ip);
            boolean weekPassed = (now - admin.last2faAt()) >= SEVEN_DAYS_SECONDS;

            if (ipChanged || weekPassed) {
                return new LoginResult(LoginStatus.NEED_2FA, null, null, admin);
            }
        }

        return completeLogin(admin, ip, userAgent);
    }

    /**
     * Подтверждение входа 6-значным кодом TOTP или 8-значным резервным кодом.
     */
    public LoginResult verify2fa(String username, String code, String ip) {
        return verify2fa(username, code, ip, null);
    }

    public LoginResult verify2fa(String username, String code, String ip, String userAgent) {
        Optional<WebAdmin> adminOpt = plugin.getDatabaseManager().getAdminByUsername(username);
        if (adminOpt.isEmpty()) return new LoginResult(LoginStatus.NOT_FOUND, null, null, null);

        WebAdmin admin = adminOpt.get();
        if (!admin.totpEnabled() || admin.totpSecret() == null) {
            return completeLogin(admin, ip, userAgent);
        }

        boolean codeValid = TotpUtils.verifyCode(admin.totpSecret(), code);

        if (!codeValid && code != null) {
            String norm = TotpUtils.normalizeBackupCode(code);
            if (norm.length() == 8) {
                String hashed = TotpUtils.hashBackupCode(norm);
                List<String> list = new ArrayList<>(JsonUtils.fromJsonList(admin.backupCodes(), String.class));
                if (list.remove(hashed)) {
                    codeValid = true;
                    plugin.getDatabaseManager().updateAdminBackupCodes(admin.id(), JsonUtils.toJson(list));
                    plugin.getLogManager().logWebAction(admin.username(), "Использовал резервный код восстановления 2FA (осталось: " + list.size() + ")");
                }
            }
        }

        if (!codeValid) {
            return new LoginResult(LoginStatus.INVALID_CREDENTIALS, null, null, null);
        }

        long now = System.currentTimeMillis() / 1000L;
        plugin.getDatabaseManager().updateAdmin2faSuccess(admin.id(), now, ip);
        return completeLogin(admin, ip, userAgent);
    }

    public LoginResult completeLogin(WebAdmin admin, String ip) {
        return completeLogin(admin, ip, null);
    }

    public LoginResult completeLogin(WebAdmin admin, String ip, String userAgent) {
        WebRole role = plugin.getDatabaseManager().getRoleById(admin.roleId()).orElseThrow();
        WebSession session = plugin.getSessionManager().createSession(admin, role, ip, userAgent);

        long now = System.currentTimeMillis() / 1000L;
        plugin.getDatabaseManager().updateAdminLastLogin(admin.id(), now);
        plugin.getLogManager().logWebAction(admin.username(), "Вошёл в систему");

        if (plugin.getSecurityWebhookService() != null) {
            boolean isNewIp = admin.last2faIp() == null || !admin.last2faIp().equals(ip);
            plugin.getSecurityWebhookService().sendLoginAlert(admin.username(), ip, userAgent, isNewIp);
        }

        return new LoginResult(LoginStatus.SUCCESS, session, role, admin);
    }

    public List<String> regenerateBackupCodes(int adminId) {
        Optional<WebAdmin> adminOpt = plugin.getDatabaseManager().getAdminById(adminId);
        if (adminOpt.isEmpty()) return List.of();

        List<String> plainCodes = TotpUtils.generateBackupCodes(8);
        List<String> hashedCodes = plainCodes.stream().map(TotpUtils::hashBackupCode).toList();
        plugin.getDatabaseManager().updateAdminBackupCodes(adminId, JsonUtils.toJson(hashedCodes));
        plugin.getLogManager().logWebAction(adminOpt.get().username(), "Сгенерировал новый набор резервных кодов восстановления 2FA");
        return plainCodes;
    }

    public boolean approvePendingAdmin(String actorUsername, int targetId, int roleId) {
        Optional<WebAdmin> targetOpt = plugin.getDatabaseManager().getAdminById(targetId);
        if (targetOpt.isEmpty()) return false;
        Optional<WebRole> roleOpt = plugin.getDatabaseManager().getRoleById(roleId);
        if (roleOpt.isEmpty()) return false;

        boolean ok = plugin.getDatabaseManager().approveAdmin(targetId, roleId);
        if (ok) {
            plugin.getLogManager().logWebAction(actorUsername,
                "Утвердил заявку администратора " + targetOpt.get().username() + " с ролью " + roleOpt.get().name());
            if (plugin.getConfig().getBoolean("luckperms.sync-enabled", true) && roleOpt.get().lpGroup() != null) {
                plugin.getLuckPermsManager().assignGroup(targetOpt.get().username(), roleOpt.get().lpGroup());
            }
            plugin.getCommandLogListener().refreshCache();
        }
        return ok;
    }

    public boolean rejectPendingAdmin(String actorUsername, int targetId) {
        Optional<WebAdmin> targetOpt = plugin.getDatabaseManager().getAdminById(targetId);
        if (targetOpt.isEmpty()) return false;

        boolean ok = plugin.getDatabaseManager().rejectAdmin(targetId);
        if (ok) {
            plugin.getLogManager().logWebAction(actorUsername, "Отклонил заявку администратора " + targetOpt.get().username());
        }
        return ok;
    }

    public AddResult addAdmin(String actorUsername, String username, int roleId) {
        if (plugin.getDatabaseManager().getAdminByUsername(username).isPresent()) {
            return AddResult.ALREADY_EXISTS;
        }
        Optional<WebRole> roleOpt = plugin.getDatabaseManager().getRoleById(roleId);
        if (roleOpt.isEmpty()) return AddResult.ROLE_NOT_FOUND;

        long now = System.currentTimeMillis() / 1000L;
        WebAdmin admin = new WebAdmin(
            0, username, null, roleId, now, 0,
            null, false, 0, null, "ACTIVE", "{}"
        );
        plugin.getDatabaseManager().saveAdmin(admin);
        plugin.getLogManager().logWebAction(actorUsername,
            "Добавил администратора " + username + " с ролью " + roleOpt.get().name());

        if (plugin.getConfig().getBoolean("luckperms.sync-enabled", true) && roleOpt.get().lpGroup() != null) {
            plugin.getLuckPermsManager().assignGroup(username, roleOpt.get().lpGroup());
        }
        plugin.getCommandLogListener().refreshCache();
        return AddResult.OK;
    }

    public DeleteAdminResult deleteAdmin(String actorUsername, int actorId, int targetId) {
        if (actorId == targetId) return DeleteAdminResult.CANNOT_DELETE_SELF;

        Optional<WebAdmin> targetOpt = plugin.getDatabaseManager().getAdminById(targetId);
        if (targetOpt.isEmpty()) return DeleteAdminResult.NOT_FOUND;

        WebAdmin target = targetOpt.get();
        Optional<WebRole> roleOpt = plugin.getDatabaseManager().getRoleById(target.roleId());
        if (roleOpt.isPresent() && roleOpt.get().isOwner() && isLastOwner(targetId)) {
            return DeleteAdminResult.CANNOT_DELETE_LAST_OWNER;
        }

        plugin.getDatabaseManager().deleteAdmin(targetId);
        plugin.getDatabaseManager().deleteSessionsForAdmin(targetId);
        plugin.getSessionManager().invalidateSessionsForAdmin(targetId);
        plugin.getLogManager().logWebAction(actorUsername, "Удалил администратора " + target.username());
        plugin.getCommandLogListener().refreshCache();
        return DeleteAdminResult.OK;
    }

    private boolean isLastOwner(int excludingAdminId) {
        return plugin.getDatabaseManager().getAllAdmins().stream()
            .filter(admin -> admin.id() != excludingAdminId)
            .noneMatch(admin -> plugin.getDatabaseManager().getRoleById(admin.roleId())
                .map(WebRole::isOwner).orElse(false));
    }

    public boolean resetPassword(String actorUsername, int targetId) {
        Optional<WebAdmin> targetOpt = plugin.getDatabaseManager().getAdminById(targetId);
        if (targetOpt.isEmpty()) return false;

        plugin.getDatabaseManager().setAdminPassword(targetId, null);
        plugin.getDatabaseManager().deleteSessionsForAdmin(targetId);
        plugin.getSessionManager().invalidateSessionsForAdmin(targetId);
        plugin.getLogManager().logWebAction(actorUsername,
            "Сбросил пароль администратора " + targetOpt.get().username());
        return true;
    }

    public boolean updateAdminRole(String actorUsername, int targetId, int newRoleId) {
        Optional<WebAdmin> targetOpt = plugin.getDatabaseManager().getAdminById(targetId);
        if (targetOpt.isEmpty()) return false;
        Optional<WebRole> newRoleOpt = plugin.getDatabaseManager().getRoleById(newRoleId);
        if (newRoleOpt.isEmpty()) return false;

        String oldRoleName = plugin.getDatabaseManager().getRoleById(targetOpt.get().roleId())
            .map(WebRole::name).orElse("?");

        plugin.getDatabaseManager().updateAdminRole(targetId, newRoleId);
        plugin.getLogManager().logWebAction(actorUsername,
            "Изменил роль " + targetOpt.get().username() + ": " + oldRoleName + " → " + newRoleOpt.get().name());

        if (plugin.getConfig().getBoolean("luckperms.sync-enabled", true) && newRoleOpt.get().lpGroup() != null) {
            plugin.getLuckPermsManager().assignGroup(targetOpt.get().username(), newRoleOpt.get().lpGroup());
        }
        return true;
    }

    public void resetOwners() {
        plugin.getDatabaseManager().getAllRoles().stream()
            .filter(WebRole::isOwner)
            .findFirst()
            .ifPresent(ownerRole -> {
                plugin.getDatabaseManager().getAllAdmins().stream()
                    .filter(admin -> admin.roleId() == ownerRole.id())
                    .forEach(owner -> {
                        plugin.getDatabaseManager().deleteAdmin(owner.id());
                        plugin.getDatabaseManager().deleteSessionsForAdmin(owner.id());
                        plugin.getSessionManager().invalidateSessionsForAdmin(owner.id());
                    });
                plugin.getCommandLogListener().refreshCache();
            });
    }

    public enum LoginStatus {
        SUCCESS, NEED_ONBOARDING, NEED_2FA, PENDING_APPROVAL, NEED_SET_PASSWORD, INVALID_CREDENTIALS, NOT_FOUND
    }

    public record LoginResult(LoginStatus status, WebSession session, WebRole role, WebAdmin admin) {}

    public record OnboardingResult(boolean success, String message, WebSession session, WebRole role, List<String> backupCodes) {
        public OnboardingResult(boolean success, String message, WebSession session, WebRole role) {
            this(success, message, session, role, List.of());
        }
    }

    public enum RegisterStatus { OK, PENDING, ALREADY_EXISTS, INVALID_INPUT, INVALID_TOTP }

    public record RegisterResult(RegisterStatus status, String message, List<String> backupCodes) {
        public RegisterResult(RegisterStatus status, String message) {
            this(status, message, List.of());
        }
    }

    public enum AddResult { OK, ALREADY_EXISTS, ROLE_NOT_FOUND }

    public enum DeleteAdminResult { OK, NOT_FOUND, CANNOT_DELETE_SELF, CANNOT_DELETE_LAST_OWNER }
}
