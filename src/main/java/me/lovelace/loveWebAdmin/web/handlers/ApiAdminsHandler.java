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
 * Управление администраторами и утверждение заявок:
 * GET    /api/admins         — список активных администраторов
 * GET    /api/admins/pending — список заявок на регистрацию
 * POST   /api/admins         — создание администратора
 * POST   /api/admins/{id}/approve — утверждение заявки с назначением роли
 * POST   /api/admins/{id}/reject  — отклонение заявки
 * DELETE /api/admins/{id}    — удаление администратора
 * DELETE /api/admins/{id}/password — сброс пароля
 * PUT    /api/admins/{id}/role — изменение роли
 */
public class ApiAdminsHandler extends ApiHandlerSupport {

    public ApiAdminsHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ADMINS);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if ("/invites".equals(pathInfo)) {
            List<Map<String, Object>> invites = new ArrayList<>();
            for (var inv : plugin.getDatabaseManager().getPendingInvites()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", inv.id());
                m.put("code", inv.code());
                m.put("username", inv.username());
                m.put("roleId", inv.roleId());
                plugin.getDatabaseManager().getRoleById(inv.roleId()).ifPresent(r -> m.put("roleName", r.name()));
                m.put("probationDays", inv.probationDays());
                m.put("roleExpiresAt", inv.roleExpiresAt());
                m.put("createdBy", inv.createdBy());
                m.put("createdAt", inv.createdAt());
                m.put("expiresAt", inv.expiresAt());
                m.put("status", inv.status());
                invites.add(m);
            }
            sendSuccess(resp, invites);
            return;
        }
        if ("/pending".equals(pathInfo)) {
            List<Map<String, Object>> pending = new ArrayList<>();
            for (WebAdmin admin : plugin.getDatabaseManager().getPendingAdmins()) {
                pending.add(toAdminMap(admin));
            }
            sendSuccess(resp, pending);
            return;
        }

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

        String pathInfo = req.getPathInfo();
        if ("/invites".equals(pathInfo)) {
            handleCreateInvite(req, resp, sessionOpt.get());
            return;
        }
        if (pathInfo != null && pathInfo.contains("/approve")) {
            handleApprove(req, resp, sessionOpt.get());
            return;
        }
        if (pathInfo != null && pathInfo.contains("/reject")) {
            handleReject(req, resp, sessionOpt.get());
            return;
        }
        if (pathInfo != null && (pathInfo.contains("/terminate-sessions") || pathInfo.contains("/sessions/terminate-all"))) {
            handleTerminateAdminSessions(req, resp, sessionOpt.get());
            return;
        }

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

    private void handleCreateInvite(HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String username = stringOrNull(body.get("username"));
        Object roleIdObj = body.get("roleId");
        if (username == null || username.isBlank() || roleIdObj == null) {
            sendError(resp, 400, "Не указаны никнейм или роль сотрудника");
            return;
        }

        int roleId = (int) Double.parseDouble(String.valueOf(roleIdObj));
        Optional<me.lovelace.loveWebAdmin.models.WebRole> roleOpt = plugin.getDatabaseManager().getRoleById(roleId);
        if (roleOpt.isEmpty()) {
            sendError(resp, 400, "Указанная роль не найдена");
            return;
        }
        if (roleOpt.get().isOwner()) {
            sendError(resp, 400, "Нельзя выдать приглашение с ролью Управляющего");
            return;
        }

        int probationDays = 0;
        if (body.get("probationDays") != null) {
            try {
                probationDays = (int) Double.parseDouble(String.valueOf(body.get("probationDays")));
            } catch (Exception ignored) {}
        }

        long now = System.currentTimeMillis() / 1000L;
        long roleExpiresAt = (probationDays > 0) ? (now + (long) probationDays * 86400L) : 0L;
        long expiresAt = now + (7L * 86400L); // инвайт действителен 7 дней

        // Генерация кода LWA-XXXXXXXX (8 случайных буквенно-цифровых символов)
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rng = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder("LWA-");
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        String code = sb.toString();

        var invite = plugin.getDatabaseManager().createAdminInvite(
            code, username.trim(), roleId, probationDays, roleExpiresAt, session.adminUsername(), expiresAt
        );
        if (invite == null) {
            sendError(resp, 500, "Не удалось сохранить приглашение");
            return;
        }

        plugin.getLogManager().logWebAction(session.adminUsername(),
            "Приглашение сотрудника: " + username.trim() + " (роль: " + roleOpt.get().name() + ")");

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", invite.id());
        res.put("code", invite.code());
        res.put("username", invite.username());
        res.put("roleId", invite.roleId());
        res.put("roleName", roleOpt.get().name());
        res.put("probationDays", invite.probationDays());
        res.put("roleExpiresAt", invite.roleExpiresAt());
        res.put("expiresAt", invite.expiresAt());
        sendSuccess(resp, res);
    }

    private void handleApprove(HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
        String pathInfo = req.getPathInfo();
        String idStr = pathInfo.replace("/approve", "").replace("/", "");
        try {
            int targetId = Integer.parseInt(idStr);
            Map<String, Object> body = readJsonBody(req);
            int roleId = 1;
            if (body.get("roleId") != null) {
                roleId = (int) Double.parseDouble(String.valueOf(body.get("roleId")));
            }
            boolean ok = plugin.getAdminManager().approvePendingAdmin(session.adminUsername(), targetId, roleId);
            if (ok) {
                sendSuccess(resp, Map.of("message", "Заявка утверждена"));
            } else {
                sendError(resp, 400, "Не удалось утвердить заявку");
            }
        } catch (NumberFormatException e) {
            sendError(resp, 400, "Некорректный ID");
        }
    }

    private void handleReject(HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
        String pathInfo = req.getPathInfo();
        String idStr = pathInfo.replace("/reject", "").replace("/", "");
        try {
            int targetId = Integer.parseInt(idStr);
            boolean ok = plugin.getAdminManager().rejectPendingAdmin(session.adminUsername(), targetId);
            if (ok) {
                sendSuccess(resp, Map.of("message", "Заявка отклонена"));
            } else {
                sendError(resp, 400, "Не удалось отклонить заявку");
            }
        } catch (NumberFormatException e) {
            sendError(resp, 400, "Некорректный ID");
        }
    }

    private void handleTerminateAdminSessions(HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
        String pathInfo = req.getPathInfo();
        String idStr = pathInfo.replace("/terminate-sessions", "").replace("/sessions/terminate-all", "").replace("/", "").trim();
        try {
            int targetId = Integer.parseInt(idStr);
            Optional<WebAdmin> targetOpt = plugin.getDatabaseManager().getAdminById(targetId);
            if (targetOpt.isEmpty()) {
                sendError(resp, 404, "Администратор не найден");
                return;
            }
            plugin.getSessionManager().invalidateSessionsForAdmin(targetId);
            plugin.getLogManager().logWebAction(session.adminUsername(), "Принудительно завершил все сессии администратора " + targetOpt.get().username());
            sendSuccess(resp, Map.of("message", "Все активные сессии администратора " + targetOpt.get().username() + " завершены"));
        } catch (NumberFormatException e) {
            sendError(resp, 400, "Некорректный ID");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo != null && pathInfo.startsWith("/invites/")) {
            Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ADMINS);
            if (sessionOpt.isEmpty()) return;
            String idStr = pathInfo.substring("/invites/".length()).replace("/", "").trim();
            try {
                int inviteId = Integer.parseInt(idStr);
                boolean ok = plugin.getDatabaseManager().cancelInvite(inviteId);
                if (ok) {
                    plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(), "Отменил приглашение #" + inviteId);
                    sendSuccess(resp, Map.of("message", "Приглашение отменено"));
                } else {
                    sendError(resp, 404, "Приглашение не найдено или уже использовано");
                }
            } catch (NumberFormatException e) {
                sendError(resp, 400, "Некорректный ID приглашения");
            }
            return;
        }

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

        // Владелец не может менять себе роль
        Optional<WebAdmin> targetOpt = plugin.getDatabaseManager().getAdminById(targetId);
        if (targetOpt.isEmpty()) {
            sendError(resp, 404, "Администратор не найден");
            return;
        }
        boolean targetIsOwner = plugin.getDatabaseManager().getRoleById(targetOpt.get().roleId()).map(me.lovelace.loveWebAdmin.models.WebRole::isOwner).orElse(false);
        if (targetIsOwner && targetId == sessionOpt.get().adminId()) {
            sendError(resp, 400, "Управляющий не может изменить собственную роль");
            return;
        }

        boolean ok = plugin.getAdminManager().updateAdminRole(sessionOpt.get().adminUsername(), targetId, roleId);
        if (ok) {
            if (body.get("expiresAt") != null) {
                try {
                    long exp = (long) Double.parseDouble(String.valueOf(body.get("expiresAt")));
                    plugin.getDatabaseManager().setAdminRoleExpiry(targetId, exp);
                } catch (Exception ignored) {}
            }
            if (Boolean.TRUE.equals(body.get("clearPendingRoleReview")) || body.get("expiresAt") != null) {
                String curPrefs = targetOpt.get().uiPreferences();
                if (curPrefs != null && curPrefs.contains("pendingRoleReview")) {
                    String updated = curPrefs.replace("\"pendingRoleReview\":true", "\"pendingRoleReview\":false");
                    plugin.getDatabaseManager().updateAdminPreferences(targetId, updated);
                }
            }
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
        map.put("roleExpiresAt", plugin.getDatabaseManager().getAdminRoleExpiry(admin.id()));
        map.put("createdAt", admin.createdAt());
        map.put("lastLoginAt", admin.lastLoginAt());
        map.put("hasPassword", admin.passwordHash() != null);
        map.put("status", admin.status());
        map.put("totpEnabled", admin.totpEnabled());
        String prefsStr = admin.uiPreferences();
        map.put("uiPreferences", prefsStr != null ? prefsStr : "{}");
        boolean pendingReview = false;
        long expiry = plugin.getDatabaseManager().getAdminRoleExpiry(admin.id());
        if (expiry > 0 && expiry <= System.currentTimeMillis() / 1000L) {
            pendingReview = true;
        } else if (prefsStr != null && prefsStr.contains("\"pendingRoleReview\":true")) {
            pendingReview = true;
        }
        map.put("pendingRoleReview", pendingReview);
        return map;
    }

    private String[] splitPath(String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.isEmpty()) return null;
        String trimmed = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        return trimmed.split("/");
    }
}

