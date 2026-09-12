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
        // Навигация по разделам больше не загрязняет веб-аудит лог по требованию пользователя
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
            String action = entry.action();
            if (action != null && (action.startsWith("Открыл раздел:") || action.startsWith("Открыл раздел "))) {
                continue; // Исключаем просмотры разделов из вывода веб-аудита
            }
            if (action != null && action.contains("(IP: ")) {
                action = action.replaceAll("\\s*\\(IP:\\s*[^)]+\\)", "");
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", entry.id());
            map.put("actor", entry.actor());
            map.put("action", action);
            map.put("timestamp", entry.timestamp());
            result.add(map);
        }
        return result;
    }
}
