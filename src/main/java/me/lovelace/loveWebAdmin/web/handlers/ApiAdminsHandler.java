package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.managers.AdminManager;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GET    /api/admins
 * POST   /api/admins
 * DELETE /api/admins/{id}
 * DELETE /api/admins/{id}/password
 * PUT    /api/admins/{id}/role
 */
public class ApiAdminsHandler extends ApiHandlerSupport {

    public ApiAdminsHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ADMINS);
        if (sessionOpt.isEmpty()) return;

        List<Map<String, Object>> admins = new ArrayList<>();
        for (WebAdmin admin : plugin.getDatabaseManager().getAllAdmins()) {
            admins.add(toAdminMap(admin));
        }
        sendSuccess(resp, admins);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ADMINS);
        if (sessionOpt.isEmpty()) return;

        Map<String, Object> body = readJsonBody(req);
        String username = stringOrNull(body.get("username"));
        Object roleIdObj = body.get("roleId");
        if (username == null || username.isBlank() || roleIdObj == null) {
            sendError(resp, 400, "Не указаны ник или роль");
            return;
        }
        int roleId = (int) Double.parseDouble(String.valueOf(roleIdObj));

        AdminManager.AddResult result = plugin.getAdminManager().addAdmin(sessionOpt.get().adminUsername(), username, roleId);
        switch (result) {
            case OK -> sendSuccess(resp, null);
            case ALREADY_EXISTS -> sendError(resp, 400, "Такой администратор уже существует");
            case ROLE_NOT_FOUND -> sendError(resp, 400, "Роль не найдена");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = splitPath(req.getPathInfo());
        if (parts == null) {
            sendError(resp, 404, "Не найдено");
            return;
        }

        if (parts.length == 1) {
            handleDeleteAdmin(req, resp, parts[0]);
        } else if (parts.length == 2 && "password".equals(parts[1])) {
            handleResetPassword(req, resp, parts[0]);
        } else {
            sendError(resp, 404, "Не найдено");
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = splitPath(req.getPathInfo());
        if (parts == null || parts.length != 2 || !"role".equals(parts[1])) {
            sendError(resp, 404, "Не найдено");
            return;
        }
        handleUpdateRole(req, resp, parts[0]);
    }

    private void handleDeleteAdmin(HttpServletRequest req, HttpServletResponse resp, String idStr) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ADMINS);
        if (sessionOpt.isEmpty()) return;

        int targetId = Integer.parseInt(idStr);
        AdminManager.DeleteAdminResult result = plugin.getAdminManager()
            .deleteAdmin(sessionOpt.get().adminUsername(), sessionOpt.get().adminId(), targetId);
        switch (result) {
            case OK -> sendSuccess(resp, null);
            case NOT_FOUND -> sendError(resp, 404, "Администратор не найден");
            case CANNOT_DELETE_SELF -> sendError(resp, 400, "Нельзя удалить самого себя");
            case CANNOT_DELETE_LAST_OWNER -> sendError(resp, 400, "Нельзя удалить единственного Управляющего");
        }
    }

    private void handleResetPassword(HttpServletRequest req, HttpServletResponse resp, String idStr) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_PASSWORDS);
        if (sessionOpt.isEmpty()) return;

        int targetId = Integer.parseInt(idStr);
        boolean ok = plugin.getAdminManager().resetPassword(sessionOpt.get().adminUsername(), targetId);
        if (ok) {
            sendSuccess(resp, null);
        } else {
            sendError(resp, 404, "Администратор не найден");
        }
    }

    private void handleUpdateRole(HttpServletRequest req, HttpServletResponse resp, String idStr) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ADMINS);
        if (sessionOpt.isEmpty()) return;

        Map<String, Object> body = readJsonBody(req);
        Object roleIdObj = body.get("roleId");
        if (roleIdObj == null) {
            sendError(resp, 400, "Не указана роль");
            return;
        }
        int targetId = Integer.parseInt(idStr);
        int roleId = (int) Double.parseDouble(String.valueOf(roleIdObj));

        boolean ok = plugin.getAdminManager().updateAdminRole(sessionOpt.get().adminUsername(), targetId, roleId);
        if (ok) {
            sendSuccess(resp, null);
        } else {
            sendError(resp, 400, "Не удалось изменить роль");
        }
    }

    private Map<String, Object> toAdminMap(WebAdmin admin) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", admin.id());
        map.put("username", admin.username());
        map.put("roleId", admin.roleId());
        plugin.getDatabaseManager().getRoleById(admin.roleId()).ifPresent(role -> map.put("roleName", role.name()));
        map.put("createdAt", admin.createdAt());
        map.put("lastLoginAt", admin.lastLoginAt());
        map.put("hasPassword", admin.passwordHash() != null);
        return map;
    }

    private String[] splitPath(String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.isEmpty()) return null;
        String trimmed = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        return trimmed.split("/");
    }
}
