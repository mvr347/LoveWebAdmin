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
        return createRole(name, lpGroup, permissions, "#8b5cf6");
    }

    public WebRole createRole(String name, String lpGroup, Set<Permission> permissions, String color) {
        return createRole(name, "", "CUSTOM", 50, lpGroup, permissions, color);
    }

    public WebRole createRole(String name, String description, String category, int sortOrder, String lpGroup, Set<Permission> permissions, String color) {
        String roleColor = (color != null && !color.isBlank()) ? color.trim() : "#8b5cf6";
        String roleDesc = description != null ? description.trim() : "";
        String roleCategory = (category != null && !category.isBlank()) ? category.trim() : "CUSTOM";
        int order = sortOrder > 0 ? sortOrder : 50;
        WebRole role = new WebRole(0, name, roleDesc, roleCategory, order, lpGroup, permissions, false, roleColor);
        plugin.getDatabaseManager().saveRole(role);
        if (lpGroup != null && !lpGroup.isBlank()) {
            plugin.getLuckPermsManager().ensureGroupExists(lpGroup);
        }
        return plugin.getDatabaseManager().getRoleByName(name).orElse(role);
    }

    public boolean updateRole(int id, String lpGroup, Set<Permission> permissions) {
        return updateRole(id, null, null, null, 0, lpGroup, permissions, null);
    }

    public boolean updateRole(int id, String name, String lpGroup, Set<Permission> permissions) {
        return updateRole(id, name, null, null, 0, lpGroup, permissions, null);
    }

    public boolean updateRole(int id, String name, String lpGroup, Set<Permission> permissions, String color) {
        return updateRole(id, name, null, null, 0, lpGroup, permissions, color);
    }

    public boolean updateRole(int id, String name, String description, String category, int sortOrder, String lpGroup, Set<Permission> permissions, String color) {
        Optional<WebRole> existing = plugin.getDatabaseManager().getRoleById(id);
        if (existing.isEmpty() || existing.get().isOwner()) return false;

        String roleName = (name != null && !name.isBlank()) ? name.trim() : existing.get().name();
        String roleColor = (color != null && !color.isBlank()) ? color.trim() : existing.get().color();
        String roleDesc = description != null ? description.trim() : existing.get().description();
        String roleCat = (category != null && !category.isBlank()) ? category.trim() : existing.get().category();
        int roleSort = sortOrder > 0 ? sortOrder : existing.get().sortOrder();

        // Проверка на дублирование имени с другой ролью
        Optional<WebRole> byName = plugin.getDatabaseManager().getRoleByName(roleName);
        if (byName.isPresent() && byName.get().id() != id) {
            return false;
        }

        boolean lpGroupChanged = !Objects.equals(existing.get().lpGroup(), lpGroup);

        WebRole updated = new WebRole(id, roleName, roleDesc, roleCat, roleSort, lpGroup, permissions, false, roleColor);
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
