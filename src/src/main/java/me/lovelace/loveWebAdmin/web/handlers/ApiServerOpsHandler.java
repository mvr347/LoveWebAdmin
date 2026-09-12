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

        // Проверяем роль: только Администраторы
        var roleOpt = plugin.getDatabaseManager().getRoleById(session.roleId());
        boolean isAdmin = roleOpt.isPresent() && (roleOpt.get().isOwner() || "Администратор".equalsIgnoreCase(roleOpt.get().name()));
        if (!isAdmin) {
            sendError(resp, 403, "Управление whitelist и операторами доступно только Администраторам");
            return;
        }

        String pathInfo = req.getPathInfo();
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
