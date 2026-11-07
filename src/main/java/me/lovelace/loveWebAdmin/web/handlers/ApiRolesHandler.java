package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.managers.RoleManager;
import me.lovelace.loveWebAdmin.managers.RolePermissionPolicy;
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

    private boolean allowed(HttpServletRequest req,HttpServletResponse resp,WebSession session,Set<Permission> requested,WebRole existing) throws IOException {
      if(RolePermissionPolicy.owner(plugin,session.adminUsername()))return true;
      if(existing!=null&&(existing.id()==plugin.getDatabaseManager().getAdminById(session.adminId()).map(a->a.roleId()).orElse(-1)
          ||existing.isOwner()||existing.permissions().stream().anyMatch(p->!hasPermission(session,p)))) {
        sendError(resp,403,"Нельзя изменять собственную роль или роль с правами выше ваших");return false;
      }
      if(requested.stream().anyMatch(p->!hasPermission(session,p))){sendError(resp,403,"Можно выдавать только собственные права");return false;}
      return true;
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
        String description = stringOrNull(body.get("description"));
        String category = stringOrNull(body.get("category"));
        int sortOrder = 50;
        if (body.get("sortOrder") != null) {
            try {
                sortOrder = (int) Double.parseDouble(String.valueOf(body.get("sortOrder")));
            } catch (Exception ignored) {}
        }
        String lpGroup = stringOrNull(body.get("lpGroup"));
        String color = stringOrNull(body.get("color"));
        Set<Permission> permissions = RolePermissionPolicy.withNewDependencies(parsePermissions(body.get("permissions")), Set.of());

        if (name == null || name.isBlank()) {
            sendError(resp, 400, "Не указано название роли");
            return;
        }
        if (plugin.getDatabaseManager().getRoleByName(name).isPresent()) {
            sendError(resp, 400, "Роль с таким названием уже существует");
            return;
        }

        if(!RolePermissionPolicy.owner(plugin,sessionOpt.get().adminUsername()) && lpGroup!=null && !lpGroup.isBlank()){sendError(resp,403,"Привязку внешней группы меняет Управляющий");return;}
        if (!allowed(req,resp,sessionOpt.get(),permissions,null)) return;
        WebRole role = plugin.getRoleManager().createRole(name, description, category, sortOrder, lpGroup, permissions, color);
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
        String description = stringOrNull(body.get("description"));
        String category = stringOrNull(body.get("category"));
        int sortOrder = existing.get().sortOrder();
        if (body.get("sortOrder") != null) {
            try {
                sortOrder = (int) Double.parseDouble(String.valueOf(body.get("sortOrder")));
            } catch (Exception ignored) {}
        }
        String lpGroup = body.containsKey("lpGroup") ? stringOrNull(body.get("lpGroup")) : existing.get().lpGroup();
        String color = stringOrNull(body.get("color"));
        Set<Permission> permissions = RolePermissionPolicy.withNewDependencies(body.containsKey("permissions") ? parsePermissions(body.get("permissions")) : existing.get().permissions(), existing.get().permissions());

        if(!RolePermissionPolicy.owner(plugin,sessionOpt.get().adminUsername()) && !java.util.Objects.equals(lpGroup,existing.get().lpGroup())){sendError(resp,403,"Привязку внешней группы меняет Управляющий");return;}
        if (!allowed(req,resp,sessionOpt.get(),permissions,existing.get())) return;
        boolean ok = plugin.getRoleManager().updateRole(roleId, name, description, category, sortOrder, lpGroup, permissions, color);
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
        if(existing.isPresent()&&!allowed(req,resp,sessionOpt.get(),existing.get().permissions(),existing.get()))return;
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
        map.put("description", role.description() != null ? role.description() : "");
        map.put("category", role.category() != null ? role.category() : "CUSTOM");
        map.put("sortOrder", role.sortOrder());
        map.put("lpGroup", role.lpGroup());
        map.put("permissions", role.permissions().stream().map(Enum::name).toList());
        map.put("isOwner", role.isOwner());
        map.put("color", role.color() != null ? role.color() : "#8b5cf6");
        return map;
    }

    private Set<Permission> parsePermissions(Object raw) {
        if(raw!=null && !(raw instanceof List<?>))throw new IllegalArgumentException("Права должны быть списком");
        Set<Permission> result = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                try {
                    result.add(Permission.valueOf(String.valueOf(item)));
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("Неизвестное право доступа: " + item);
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
