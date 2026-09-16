package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GET  /api/notifications — список уведомлений и счетчик непрочитанных
 * POST /api/notifications — отправка кастомного объявления персоналу
 * POST /api/notifications/{id}/read — пометить как прочитанное
 * POST /api/notifications/read-all  — пометить все как прочитанные
 */
public class ApiNotificationsHandler extends ApiHandlerSupport {

    public ApiNotificationsHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        WebSession session = sessionOpt.get();
        int limit = parseIntOrDefault(req.getParameter("limit"), 50);
        int offset = parseIntOrDefault(req.getParameter("offset"), 0);

        List<Map<String, Object>> notifications = plugin.getNotificationManager().getNotifications(limit, offset, session.adminId());
        int unreadCount = plugin.getNotificationManager().getUnreadCount(session.adminId());

        int pendingReports = 0;
        try {
            pendingReports = plugin.getDatabaseManager().getAllReports("PENDING", null, 500, 0).size();
        } catch (Exception ignored) {}

        int pendingAppeals = 0;
        try {
            pendingAppeals = plugin.getDatabaseManager().getAllAppeals("PENDING", 500, 0).size();
        } catch (Exception ignored) {}

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notifications", notifications);
        data.put("unreadCount", unreadCount);
        data.put("pendingReportsCount", pendingReports);
        data.put("pendingAppealsCount", pendingAppeals);

        sendSuccess(resp, data);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        WebSession session = sessionOpt.get();
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) pathInfo = "";

        if ("/read-all".equals(pathInfo)) {
            plugin.getNotificationManager().markAllAsRead(session.adminId());
            sendSuccess(resp, Map.of("message", "Все уведомления помечены как прочитанные"));
            return;
        }

        if (pathInfo.endsWith("/read")) {
            String idStr = pathInfo.replace("/read", "").replace("/", "").trim();
            try {
                int id = Integer.parseInt(idStr);
                plugin.getNotificationManager().markAsRead(id, session.adminId());
                sendSuccess(resp, Map.of("message", "Уведомление прочитано"));
                return;
            } catch (NumberFormatException ignored) {}
        }

        // Создание нового уведомления: требует MANAGE_ADMINS или MANAGE_REPORTS или Owner
        boolean canBroadcast = hasPermission(session, Permission.MANAGE_ADMINS) || hasPermission(session, Permission.MANAGE_REPORTS);
        var roleOpt = plugin.getDatabaseManager().getRoleById(session.roleId());
        if (roleOpt.isPresent() && roleOpt.get().isOwner()) {
            canBroadcast = true;
        }

        if (!canBroadcast) {
            sendError(resp, 403, "У вас нет прав для отправки уведомлений персоналу");
            return;
        }

        Map<String, Object> body = readJsonBody(req);
        String title = stringOrNull(body.get("title"));
        String message = stringOrNull(body.get("message"));
        String type = stringOrNull(body.get("type"));

        if (title == null || title.isBlank() || message == null || message.isBlank()) {
            sendError(resp, 400, "Заполните заголовок и текст уведомления");
            return;
        }

        if (type == null || (!type.equals("INFO") && !type.equals("WARNING") && !type.equals("IMPORTANT") && !type.equals("CRITICAL"))) {
            type = "INFO";
        }

        plugin.getNotificationManager().createNotification(title.trim(), message.trim(), type, session.adminUsername());
        plugin.getLogManager().logWebAction(session.adminUsername(), "Отправил системное уведомление персоналу [" + type + "]: " + title.trim());
        sendSuccess(resp, Map.of("message", "Уведомление успешно отправлено персоналу"));
    }
}
