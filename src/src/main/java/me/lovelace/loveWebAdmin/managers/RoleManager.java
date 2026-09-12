package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebRole;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Управляющий (is_owner=true) — единственная фиксированная роль.
 * Все остальные роли — кастомные, создаются через веб-панель.
 * Все методы вызываются только из async контекста (работа с БД).
 */
public class RoleManager {

    private final LoveWebAdmin plugin;

    public RoleManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public WebRole createRole(String name, String lpGroup, Set<Permission> permissions) {
        WebRole role = new WebRole(0, name, lpGroup, permissions, false);
        plugin.getDatabaseManager().saveRole(role);
        if (lpGroup != null && !lpGroup.isBlank()) {
            plugin.getLuckPermsManager().ensureGroupExists(lpGroup);
        }
        return plugin.getDatabaseManager().getRoleByName(name).orElse(role);
    }

    public boolean updateRole(int id, String lpGroup, Set<Permission> permissions) {
        return updateRole(id, null, lpGroup, permissions);
    }

    public boolean updateRole(int id, String name, String lpGroup, Set<Permission> permissions) {
        Optional<WebRole> existing = plugin.getDatabaseManager().getRoleById(id);
        if (existing.isEmpty() || existing.get().isOwner()) return false;

        String roleName = (name != null && !name.isBlank()) ? name.trim() : existing.get().name();

        // Проверка на дублирование имени с другой ролью
        Optional<WebRole> byName = plugin.getDatabaseManager().getRoleByName(roleName);
        if (byName.isPresent() && byName.get().id() != id) {
            return false;
        }

        boolean lpGroupChanged = !Objects.equals(existing.get().lpGroup(), lpGroup);

        WebRole updated = new WebRole(id, roleName, lpGroup, permissions, false);
        plugin.getDatabaseManager().saveRole(updated);

        if (lpGroupChanged && lpGroup != null && !lpGroup.isBlank()) {
            plugin.getLuckPermsManager().ensureGroupExists(lpGroup);
            List<String> usernames = plugin.getDatabaseManager().getAllAdmins().stream()
                .filter(admin -> admin.roleId() == id)
                .map(WebAdmin::username)
                .toList();
            plugin.getLuckPermsManager().syncUsersForRole(usernames, lpGroup);
        }
        return true;
    }

    public DeleteResult deleteRole(int id) {
        Optional<WebRole> role = plugin.getDatabaseManager().getRoleById(id);
        if (role.isEmpty()) return DeleteResult.NOT_FOUND;
        if (role.get().isOwner()) return DeleteResult.IS_OWNER;

        boolean hasAdmins = plugin.getDatabaseManager().getAllAdmins().stream()
            .anyMatch(admin -> admin.roleId() == id);
        if (hasAdmins) return DeleteResult.HAS_ADMINS;

        plugin.getDatabaseManager().deleteRole(id);
        return DeleteResult.OK;
    }

    public enum DeleteResult {
        OK, NOT_FOUND, IS_OWNER, HAS_ADMINS
    }
}
