package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SteamDB-style аналитика сервера:
 * GET /api/analytics/summary   — сводка посещений за месяц, новых игроков, среднее время, пиковый онлайн, график
 * GET /api/analytics/commands  — рейтинг самых популярных команд сервера
 */
public class ApiAnalyticsHandler extends ApiHandlerSupport {

    public ApiAnalyticsHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_ANALYTICS);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo) || "/summary".equals(pathInfo)) {
            String period = req.getParameter("period");
            Long from = null, to = null;
            try {
                if (req.getParameter("from") != null) from = Long.parseLong(req.getParameter("from"));
                if (req.getParameter("to") != null) to = Long.parseLong(req.getParameter("to"));
            } catch (Exception ignored) {}

            Map<String, Object> data = plugin.getDatabaseManager().getMonthlyAnalytics();
            data.putAll(plugin.getDatabaseManager().getPeriodDatabaseStats(period != null ? period : "today", from, to));
            data.put("currentOnline", plugin.getServer().getOnlinePlayers().size());
            data.put("maxPlayers", plugin.getServer().getMaxPlayers());
            data.put("uptime", plugin.getUptimeSeconds());
            sendSuccess(resp, data);
            return;
        }

        if ("/commands".equals(pathInfo)) {
            int limit = parseIntOrDefault(req.getParameter("limit"), 15);
            var commands = plugin.getDatabaseManager().getTopCommands(limit);
            sendSuccess(resp, commands);
            return;
        }

        if ("/top-players".equals(pathInfo)) {
            int limit = parseIntOrDefault(req.getParameter("limit"), 20);
            var top = plugin.getDatabaseManager().getTopPlaytimePlayers(limit);
            sendSuccess(resp, top);
            return;
        }

        sendError(resp, 404, "Не найдено");
    }
}
