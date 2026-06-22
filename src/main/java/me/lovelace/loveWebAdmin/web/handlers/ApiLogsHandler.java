package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.LogEntry;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GET /api/logs/server?limit=&offset=&mode=recent
 * GET /api/logs/web?limit=&offset=
 * POST /api/logs/section { section } — лог открытия раздела панели
 */
public class ApiLogsHandler extends ApiHandlerSupport {

    public ApiLogsHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if ("/server".equals(pathInfo)) {
            handleServerLogs(req, resp);
        } else if ("/web".equals(pathInfo)) {
            handleWebLogs(req, resp);
        } else {
            sendError(resp, 404, "Не найдено");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"/section".equals(req.getPathInfo())) {
            sendError(resp, 404, "Не найдено");
            return;
        }
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        Map<String, Object> body = readJsonBody(req);
        String section = stringOrNull(body.get("section"));
        if (section == null || section.isBlank()) {
            sendError(resp, 400, "Не указан раздел");
            return;
        }
        plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(), "Открыл раздел: " + section);
        sendSuccess(resp, null);
    }

    private void handleServerLogs(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_SERVER_LOGS);
        if (sessionOpt.isEmpty()) return;

        if ("recent".equals(req.getParameter("mode"))) {
            sendSuccess(resp, toLogList(plugin.getLogManager().getRecentServerLogs()));
            return;
        }
        int limit = parseIntOrDefault(req.getParameter("limit"), 100);
        int offset = parseIntOrDefault(req.getParameter("offset"), 0);
        sendSuccess(resp, toLogList(plugin.getDatabaseManager().getServerLogs(limit, offset)));
    }

    private void handleWebLogs(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_WEB_LOGS);
        if (sessionOpt.isEmpty()) return;

        int limit = parseIntOrDefault(req.getParameter("limit"), 100);
        int offset = parseIntOrDefault(req.getParameter("offset"), 0);
        sendSuccess(resp, toLogList(plugin.getDatabaseManager().getWebLogs(limit, offset)));
    }

    private List<Map<String, Object>> toLogList(List<LogEntry> entries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LogEntry entry : entries) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", entry.id());
            map.put("actor", entry.actor());
            map.put("action", entry.action());
            map.put("timestamp", entry.timestamp());
            result.add(map);
        }
        return result;
    }
}
