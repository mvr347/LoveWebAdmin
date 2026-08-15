package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebRole;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.PasswordUtils;

import java.util.Optional;

/**
 * Управление веб-админами: bootstrap владельца, вход, добавление/удаление, сброс пароля.
 * Все методы вызываются только из async контекста (работа с БД).
 */
public class AdminManager {

    private final LoveWebAdmin plugin;

    public AdminManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public boolean hasOwner() {
        return plugin.getDatabaseManager().hasOwner();
    }

    /**
     * Атомарно проверяет отсутствие владельца и создаёт его. synchronized гарантирует, что при
     * двух одновременных запросах /api/auth/setup-owner владелец будет создан только один раз.
     *
     * @return true если владелец был создан этим вызовом, false если он уже существовал
     */
    public synchronized boolean setupOwner(String username) {
        if (plugin.getDatabaseManager().hasOwner()) {
            return false;
        }
        plugin.getDatabaseManager().bootstrapOwner(username);
        if (plugin.getConfig().getBoolean("luckperms.sync-enabled", true)) {
            String ownerLpGroup = plugin.getConfig().getString("luckperms.owner-lp-group", "owner");
            plugin.getLuckPermsManager().assignGroup(username, ownerLpGroup);
        }
        plugin.getCommandLogListener().refreshCache();
        return true;
    }

    public LoginResult login(String username, String password) {
        Optional<WebAdmin> adminOpt = plugin.getDatabaseManager().getAdminByUsername(username);
        if (adminOpt.isEmpty()) return new LoginResult(LoginStatus.NOT_FOUND, null, null);

        WebAdmin admin = adminOpt.get();
        if (admin.passwordHash() == null) {
            return new LoginResult(LoginStatus.NEED_SET_PASSWORD, null, null);
        }
        if (!PasswordUtils.verify(password, admin.passwordHash())) {
            return new LoginResult(LoginStatus.INVALID_CREDENTIALS, null, null);
        }

        return completeLogin(admin);
    }

    /**
     * Устанавливает пароль ТОЛЬКО в рамках первичной настройки аккаунта (когда у админа ещё
     * нет пароля — statusом NEED_SET_PASSWORD при /login). Без этой проверки любой
     * неаутентифицированный запрос к /api/auth/set-password мог бы перезаписать пароль ЛЮБОГО
     * существующего администратора (включая Управляющего) и получить рабочую сессию —
     * захват аккаунта без знания текущего пароля.
     */
    public LoginResult setPassword(String username, String newPassword) {
        Optional<WebAdmin> adminOpt = plugin.getDatabaseManager().getAdminByUsername(username);
        if (adminOpt.isEmpty()) return new LoginResult(LoginStatus.NOT_FOUND, null, null);

        WebAdmin admin = adminOpt.get();
        if (admin.passwordHash() != null) {
            return new LoginResult(LoginStatus.NOT_FOUND, null, null);
        }
        plugin.getDatabaseManager().setAdminPassword(admin.id(), PasswordUtils.hash(newPassword));

        return completeLogin(admin);
    }

    private LoginResult completeLogin(WebAdmin admin) {
        WebRole role = plugin.getDatabaseManager().getRoleById(admin.roleId()).orElseThrow();
        WebSession session = plugin.getSessionManager().createSession(admin, role);

        long now = System.currentTimeMillis() / 1000;
        plugin.getDatabaseManager().updateAdminLastLogin(admin.id(), now);
        plugin.getLogManager().logWebAction(admin.username(), "Вошёл в систему");

        return new LoginResult(LoginStatus.SUCCESS, session, role);
    }

    public AddResult addAdmin(String actorUsername, String username, int roleId) {
        if (plugin.getDatabaseManager().getAdminByUsername(username).isPresent()) {
            return AddResult.ALREADY_EXISTS;
        }
        Optional<WebRole> roleOpt = plugin.getDatabaseManager().getRoleById(roleId);
        if (roleOpt.isEmpty()) return AddResult.ROLE_NOT_FOUND;

        long now = System.currentTimeMillis() / 1000;
        plugin.getDatabaseManager().saveAdmin(new WebAdmin(0, username, null, roleId, now, 0));
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

        plugin.getDatabaseManager().deleteAdminPassword(targetId);
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

    /**
     * /lovewebadmin resetowner confirm (алиас: /lwa resetowner confirm) — удаляет всех
     * Управляющих, чтобы можно было назначить нового.
     */
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

    public enum LoginStatus { SUCCESS, NEED_SET_PASSWORD, INVALID_CREDENTIALS, NOT_FOUND }

    public record LoginResult(LoginStatus status, WebSession session, WebRole role) {}

    public enum AddResult { OK, ALREADY_EXISTS, ROLE_NOT_FOUND }

    public enum DeleteAdminResult { OK, NOT_FOUND, CANNOT_DELETE_SELF, CANNOT_DELETE_LAST_OWNER }
}
