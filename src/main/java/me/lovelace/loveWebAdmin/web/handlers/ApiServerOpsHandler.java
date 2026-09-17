package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Управление белым списком (Whitelist) и операторами сервера (OPs).
 * Доступно только Администраторам (или Управляющему).
 *
 * GET  /api/server/whitelist — статус и список игроков
 * POST /api/server/whitelist { action: "enable"|"disable"|"add"|"remove", player: "name" }
 * GET  /api/server/ops       — список операторов
 * POST /api/server/ops       { action: "add"|"remove", player: "name" }
 */
public class ApiServerOpsHandler extends ApiHandlerSupport {

    public ApiServerOpsHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.EXECUTE_COMMANDS);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if ("/config".equals(pathInfo)) {
            handleGetConfig(resp);
            return;
        }
        if ("/maintenance".equals(pathInfo)) {
            sendSuccess(resp, Map.of(
                "enabled", plugin.isMaintenanceMode(),
                "message", plugin.getMaintenanceMessage() != null ? plugin.getMaintenanceMessage() : "Ведутся технические работы"
            ));
            return;
        }
        if ("/whitelist".equals(pathInfo)) {
            handleGetWhitelist(resp);
            return;
        }
        if ("/ops".equals(pathInfo)) {
            handleGetOps(resp);
            return;
        }

        sendError(resp, 404, "Не найдено");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.EXECUTE_COMMANDS);
        if (sessionOpt.isEmpty()) return;
        WebSession session = sessionOpt.get();

        String pathInfo = req.getPathInfo();
        if ("/config".equals(pathInfo)) {
            handlePostConfig(req, resp, session);
            return;
        }
        if ("/maintenance".equals(pathInfo)) {
            handlePostMaintenance(req, resp, session);
            return;
        }

        // Проверяем роль: только Администраторы
        var roleOpt = plugin.getDatabaseManager().getRoleById(session.roleId());
        boolean isAdmin = roleOpt.isPresent() && (roleOpt.get().isOwner() || "Администратор".equalsIgnoreCase(roleOpt.get().name()));
        if (!isAdmin) {
            sendError(resp, 403, "Управление whitelist и операторами доступно только Администраторам");
            return;
        }

        if ("/whitelist".equals(pathInfo)) {
            handlePostWhitelist(req, resp, session);
            return;
        }
        if ("/ops".equals(pathInfo)) {
            handlePostOps(req, resp, session);
            return;
        }

        sendError(resp, 404, "Не найдено");
    }

    private void handlePostMaintenance(HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
        var roleOpt = plugin.getDatabaseManager().getRoleById(session.roleId());
        boolean canManage = roleOpt.isPresent() && (roleOpt.get().isOwner() || roleOpt.get().permissions().contains(Permission.MANAGE_ADMINS));
        if (!canManage) {
            sendError(resp, 403, "Управление режимом технических работ доступно только высшим ролям");
            return;
        }

        Map<String, Object> body = readJsonBody(req);
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String message = stringOrNull(body.get("message"));
        if (message == null || message.isBlank()) {
            message = "Ведутся технические работы на сервере. Пожалуйста, зайдите позже.";
        }

        plugin.setMaintenanceMode(enabled);
        plugin.setMaintenanceMessage(message);

        plugin.getConfig().set("security.maintenance.enabled", enabled);
        plugin.getConfig().set("security.maintenance.message", message);
        plugin.saveConfig();

        plugin.getLogManager().logWebAction(session.adminUsername(), (enabled ? "Включил" : "Выключил") + " режим технических работ");
        if (plugin.getNotificationManager() != null) {
            plugin.getNotificationManager().broadcast(
                "Технические работы",
                (enabled ? "Включён" : "Выключен") + " режим технических работ: " + message,
                enabled ? "WARNING" : "INFO",
                "system"
            );
        }

        sendSuccess(resp, Map.of(
            "enabled", enabled,
            "message", message
        ));
    }

    private void handleGetConfig(HttpServletResponse resp) throws IOException {
        var cfg = plugin.getConfig();
        Map<String, Object> data = new LinkedHashMap<>();

        // Web
        data.put("webPort", cfg.getInt("web.port", 8080));
        data.put("webHost", cfg.getString("web.host", "0.0.0.0"));
        data.put("sessionLifetimeMinutes", cfg.getLong("web.session-lifetime-minutes", 60));

        // System
        data.put("debugMode", plugin.isDebugMode());
        data.put("keepLogCount", cfg.getInt("logs.keep-count", 5000));

        // Security
        data.put("strictIp", cfg.getBoolean("security.strict-ip", false));
        data.put("minPasswordLength", cfg.getInt("security.password.min-length", 10));
        data.put("loginMaxAttempts", cfg.getInt("security.login.max-attempts", 5));
        data.put("loginLockoutMinutes", cfg.getLong("security.login.lockout-minutes", 15));
        data.put("commandBlacklist", cfg.getStringList("security.command-blacklist"));
        data.put("corsAllowedOrigins", cfg.getStringList("security.cors.allowed-origins"));

        // Webhooks
        data.put("webhooksEnabled", cfg.getBoolean("security.webhooks.enabled", false));
        data.put("discordWebhookUrl", cfg.getString("security.webhooks.discord.webhook-url", cfg.getString("security.webhooks.discord-url", "")));
        data.put("telegramEnabled", cfg.getBoolean("security.webhooks.telegram.enabled", false));
        data.put("telegramBotToken", cfg.getString("security.webhooks.telegram.bot-token", ""));
        data.put("telegramChatId", cfg.getString("security.webhooks.telegram.chat-id", ""));

        // Maintenance
        data.put("maintenanceEnabled", plugin.isMaintenanceMode());
        data.put("maintenanceMessage", plugin.getMaintenanceMessage() != null ? plugin.getMaintenanceMessage() : "Ведутся технические работы");

        sendSuccess(resp, data);
    }

    private void handlePostConfig(HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
        var roleOpt = plugin.getDatabaseManager().getRoleById(session.roleId());
        boolean canManage = roleOpt.isPresent() && (roleOpt.get().isOwner() || roleOpt.get().permissions().contains(Permission.MANAGE_ADMINS));
        if (!canManage) {
            sendError(resp, 403, "Управление настройками сервера доступно только управляющему или администраторам");
            return;
        }

        Map<String, Object> body = readJsonBody(req);
        var cfg = plugin.getConfig();

        if (body.containsKey("webPort") && body.get("webPort") instanceof Number n) {
            int port = n.intValue();
            if (port > 0 && port <= 65535) cfg.set("web.port", port);
        }
        if (body.containsKey("webHost") && body.get("webHost") instanceof String h && !h.isBlank()) {
            cfg.set("web.host", h.trim());
        }
        if (body.containsKey("sessionLifetimeMinutes") && body.get("sessionLifetimeMinutes") instanceof Number n) {
            long mins = Math.max(5, n.longValue());
            cfg.set("web.session-lifetime-minutes", mins);
        }
        if (body.containsKey("keepLogCount") && body.get("keepLogCount") instanceof Number n) {
            int count = Math.max(500, Math.min(50000, n.intValue()));
            cfg.set("logs.keep-count", count);
        }
        if (body.containsKey("debugMode")) {
            boolean dbg = Boolean.TRUE.equals(body.get("debugMode"));
            cfg.set("debug-mode", dbg);
        }
        if (body.containsKey("strictIp")) {
            boolean strict = Boolean.TRUE.equals(body.get("strictIp"));
            cfg.set("security.strict-ip", strict);
        }
        if (body.containsKey("minPasswordLength") && body.get("minPasswordLength") instanceof Number n) {
            int len = Math.max(8, Math.min(32, n.intValue()));
            cfg.set("security.password.min-length", len);
        }
        if (body.containsKey("loginMaxAttempts") && body.get("loginMaxAttempts") instanceof Number n) {
            int att = Math.max(1, Math.min(20, n.intValue()));
            cfg.set("security.login.max-attempts", att);
        }
        if (body.containsKey("loginLockoutMinutes") && body.get("loginLockoutMinutes") instanceof Number n) {
            long lock = Math.max(1, Math.min(1440, n.longValue()));
            cfg.set("security.login.lockout-minutes", lock);
        }
        if (body.containsKey("commandBlacklist") && body.get("commandBlacklist") instanceof List<?> list) {
            List<String> strList = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) strList.add(item.toString().trim().toLowerCase());
            }
            cfg.set("security.command-blacklist", strList);
        }
        if (body.containsKey("webhooksEnabled")) {
            cfg.set("security.webhooks.enabled", Boolean.TRUE.equals(body.get("webhooksEnabled")));
        }
        if (body.containsKey("discordWebhookUrl")) {
            String url = stringOrNull(body.get("discordWebhookUrl"));
            cfg.set("security.webhooks.discord-url", url != null ? url : "");
            cfg.set("security.webhooks.discord.webhook-url", url != null ? url : "");
            cfg.set("security.webhooks.discord.enabled", url != null && !url.isBlank());
        }
        if (body.containsKey("telegramBotToken")) {
            String tok = stringOrNull(body.get("telegramBotToken"));
            cfg.set("security.webhooks.telegram.bot-token", tok != null ? tok : "");
        }
        if (body.containsKey("telegramChatId")) {
            String cid = stringOrNull(body.get("telegramChatId"));
            cfg.set("security.webhooks.telegram.chat-id", cid != null ? cid : "");
            cfg.set("security.webhooks.telegram.enabled", cid != null && !cid.isBlank());
        }

        plugin.saveConfig();
        plugin.getLogManager().logWebAction(session.adminUsername(), "Обновил параметры конфигурации сервера (config.yml)");

        handleGetConfig(resp);
    }

    private void handleGetWhitelist(HttpServletResponse resp) throws IOException {
        try {
            boolean enabled = Bukkit.hasWhitelist();
            List<Map<String, Object>> players = new ArrayList<>();
            for (OfflinePlayer p : Bukkit.getWhitelistedPlayers()) {
                if (p.getName() != null) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", p.getName());
                    map.put("uuid", p.getUniqueId().toString());
                    map.put("isOnline", p.isOnline());
                    players.add(map);
                }
            }
            sendSuccess(resp, Map.of(
                "enabled", enabled,
                "count", players.size(),
                "players", players
            ));
        } catch (Exception e) {
            sendError(resp, 500, "Ошибка получения whitelist: " + e.getMessage());
        }
    }

    private void handlePostWhitelist(HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String action = stringOrNull(body.get("action"));
        String player = stringOrNull(body.get("player"));

        if (action == null) {
            sendError(resp, 400, "Не указано действие (action)");
            return;
        }

        try {
            plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                switch (action.toLowerCase()) {
                    case "enable" -> {
                        Bukkit.setWhitelist(true);
                        plugin.getLogManager().logWebAction(session.adminUsername(), "Включил whitelist");
                    }
                    case "disable" -> {
                        Bukkit.setWhitelist(false);
                        plugin.getLogManager().logWebAction(session.adminUsername(), "Выключил whitelist");
                    }
                    case "add" -> {
                        if (player != null && !player.isBlank()) {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(player.trim());
                            op.setWhitelisted(true);
                            plugin.getLogManager().logWebAction(session.adminUsername(), "Добавил в whitelist: " + player);
                        }
                    }
                    case "remove" -> {
                        if (player != null && !player.isBlank()) {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(player.trim());
                            op.setWhitelisted(false);
                            plugin.getLogManager().logWebAction(session.adminUsername(), "Удалил из whitelist: " + player);
                        }
                    }
                }
                return true;
            }).get();

            sendSuccess(resp, Map.of("message", "Настройки whitelist обновлены"));
        } catch (Exception e) {
            sendError(resp, 500, "Ошибка изменения whitelist: " + e.getMessage());
        }
    }

    private void handleGetOps(HttpServletResponse resp) throws IOException {
        try {
            List<Map<String, Object>> ops = new ArrayList<>();
            for (OfflinePlayer p : Bukkit.getOperators()) {
                if (p.getName() != null) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", p.getName());
                    map.put("uuid", p.getUniqueId().toString());
                    map.put("isOnline", p.isOnline());
                    ops.add(map);
                }
            }
            sendSuccess(resp, Map.of(
                "count", ops.size(),
                "operators", ops
            ));
        } catch (Exception e) {
            sendError(resp, 500, "Ошибка получения операторов: " + e.getMessage());
        }
    }

    private void handlePostOps(HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String action = stringOrNull(body.get("action"));
        String player = stringOrNull(body.get("player"));

        if (action == null || player == null || player.isBlank()) {
            sendError(resp, 400, "Укажите action (add|remove) и имя игрока");
            return;
        }

        try {
            plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                OfflinePlayer target = Bukkit.getOfflinePlayer(player.trim());
                if ("add".equalsIgnoreCase(action)) {
                    target.setOp(true);
                    plugin.getLogManager().logWebAction(session.adminUsername(), "Выдал права OP игроку " + player);
                } else if ("remove".equalsIgnoreCase(action)) {
                    target.setOp(false);
                    plugin.getLogManager().logWebAction(session.adminUsername(), "Снял права OP с игрока " + player);
                }
                return true;
            }).get();

            sendSuccess(resp, Map.of("message", "Права оператора обновлены для " + player));
        } catch (Exception e) {
            sendError(resp, 500, "Ошибка изменения операторов: " + e.getMessage());
        }
    }
}
