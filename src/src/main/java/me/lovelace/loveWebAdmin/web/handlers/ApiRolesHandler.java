package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.managers.RoleManager;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebRole;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * GET    /api/roles  — любой авторизованный
 * POST   /api/roles  — MANAGE_ROLES
 * PUT    /api/roles/{id} — MANAGE_ROLES
 * DELETE /api/roles/{id} — MANAGE_ROLES
 */
public class ApiRolesHandler extends ApiHandlerSupport {

    public ApiRolesHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        List<Map<String, Object>> roles = new ArrayList<>();
        for (WebRole role : plugin.getDatabaseManager().getAllRoles()) {
            roles.add(toRoleMap(role));
        }
        sendSuccess(resp, roles);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ROLES);
        if (sessionOpt.isEmpty()) return;

        Map<String, Object> body = readJsonBody(req);
        String name = stringOrNull(body.get("name"));
        String lpGroup = stringOrNull(body.get("lpGroup"));
        Set<Permission> permissions = parsePermissions(body.get("permissions"));

        if (name == null || name.isBlank()) {
            sendError(resp, 400, "Не указано название роли");
            return;
        }
        if (plugin.getDatabaseManager().getRoleByName(name).isPresent()) {
            sendError(resp, 400, "Роль с таким названием уже существует");
            return;
        }

        WebRole role = plugin.getRoleManager().createRole(name, lpGroup, permissions);
        plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(), "Создал роль " + name);
        sendSuccess(resp, toRoleMap(role));
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ROLES);
        if (sessionOpt.isEmpty()) return;

        Integer roleId = parseRoleId(req.getPathInfo());
        if (roleId == null) {
            sendError(resp, 404, "Не найдено");
            return;
        }

        Optional<WebRole> existing = plugin.getDatabaseManager().getRoleById(roleId);
        if (existing.isEmpty()) {
            sendError(resp, 404, "Роль не найдена");
            return;
        }
        if (existing.get().isOwner()) {
            sendError(resp, 400, "Роль Управляющего нельзя редактировать");
            return;
        }

        Map<String, Object> body = readJsonBody(req);
        String name = stringOrNull(body.get("name"));
        String lpGroup = stringOrNull(body.get("lpGroup"));
        Set<Permission> permissions = parsePermissions(body.get("permissions"));

        boolean ok = plugin.getRoleManager().updateRole(roleId, name, lpGroup, permissions);
        if (!ok) {
            sendError(resp, 400, "Не удалось обновить роль (возможно, такое имя уже занято)");
            return;
        }
        plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(), "Изменил параметры роли " + existing.get().name());
        sendSuccess(resp, null);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ROLES);
        if (sessionOpt.isEmpty()) return;

        Integer roleId = parseRoleId(req.getPathInfo());
        if (roleId == null) {
            sendError(resp, 404, "Не найдено");
            return;
        }

        Optional<WebRole> existing = plugin.getDatabaseManager().getRoleById(roleId);
        RoleManager.DeleteResult result = plugin.getRoleManager().deleteRole(roleId);
        switch (result) {
            case OK -> {
                plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(),
                    "Удалил роль " + existing.map(WebRole::name).orElse("?"));
                sendSuccess(resp, null);
            }
            case NOT_FOUND -> sendError(resp, 404, "Роль не найдена");
            case IS_OWNER -> sendError(resp, 400, "Роль Управляющего нельзя удалить");
            case HAS_ADMINS -> sendError(resp, 400, "Нельзя удалить роль с привязанными администраторами");
        }
    }

    private Map<String, Object> toRoleMap(WebRole role) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", role.id());
        map.put("name", role.name());
        map.put("lpGroup", role.lpGroup());
        map.put("permissions", role.permissions().stream().map(Enum::name).toList());
        map.put("isOwner", role.isOwner());
        return map;
    }

    private Set<Permission> parsePermissions(Object raw) {
        Set<Permission> result = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                try {
                    result.add(Permission.valueOf(String.valueOf(item)));
                } catch (IllegalArgumentException ignored) {
                    // неизвестное право - игнорируем
                }
            }
        }
        return result;
    }

    private Integer parseRoleId(String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.isEmpty()) return null;
        String trimmed = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
