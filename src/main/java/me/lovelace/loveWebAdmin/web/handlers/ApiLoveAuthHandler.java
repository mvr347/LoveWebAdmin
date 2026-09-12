package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.integration.LoveAuthBridge;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Интеграция с LoveAuth:
 * GET  /api/loveauth/status             — доступность плагина LoveAuth
 * GET  /api/loveauth/player/{name}      — статус аккаунта (регистрация, блокировка, IP, альты)
 * POST /api/loveauth/player/{name}/password — сброс/установка пароля
 * POST /api/loveauth/player/{name}/delete   — удаление аккаунта
 * POST /api/loveauth/player/{name}/unlock   — разблокировка аккаунта
 * POST /api/loveauth/player/{name}/unblock-ip — разблокировка IP
 * POST /api/loveauth/player/{name}/reset-session — завершение сессии игрока
 */
public class ApiLoveAuthHandler extends ApiHandlerSupport {

    private final LoveAuthBridge bridge;

    public ApiLoveAuthHandler(LoveWebAdmin plugin) {
        super(plugin);
        this.bridge = new LoveAuthBridge();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo) || "/status".equals(pathInfo)) {
            sendSuccess(resp, Map.of("available", bridge.isAvailable()));
            return;
        }

        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_LOVEAUTH);
        if (sessionOpt.isEmpty()) return;

        if (pathInfo.startsWith("/player/")) {
            String name = pathInfo.substring("/player/".length()).trim();
            if (name.isBlank()) {
                sendError(resp, 400, "Укажите имя игрока");
                return;
            }

            try {
                Map<String, Object> info = bridge.getPlayerAuthInfo(name).get();
                sendSuccess(resp, info);
            } catch (Exception e) {
                sendError(resp, 500, "Ошибка получения данных LoveAuth: " + e.getMessage());
            }
            return;
        }

        sendError(resp, 404, "Не найдено");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_LOVEAUTH);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || !pathInfo.startsWith("/player/")) {
            sendError(resp, 404, "Не найдено");
            return;
        }

        String rest = pathInfo.substring("/player/".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            sendError(resp, 404, "Не найдено");
            return;
        }

        String playerName = rest.substring(0, slash).trim();
        String action = rest.substring(slash + 1).trim();

        if (playerName.isBlank()) {
            sendError(resp, 400, "Не указано имя игрока");
            return;
        }

        String adminName = sessionOpt.get().adminUsername();

        try {
            switch (action) {
                case "password" -> {
                    Map<String, Object> body = readJsonBody(req);
                    String newPass = stringOrNull(body.get("password"));
                    if (newPass == null || newPass.length() < 4) {
                        sendError(resp, 400, "Пароль должен содержать минимум 4 символа");
                        return;
                    }
                    boolean ok = bridge.changePassword(playerName, newPass).get();
                    plugin.getLogManager().logWebAction(adminName, "LoveAuth: изменил пароль игроку " + playerName);
                    sendSuccess(resp, Map.of("success", ok, "message", "Пароль игрока обновлён"));
                }
                case "delete" -> {
                    boolean ok = bridge.deleteAccount(playerName).get();
                    plugin.getLogManager().logWebAction(adminName, "LoveAuth: удалил аккаунт игрока " + playerName);
                    sendSuccess(resp, Map.of("success", ok, "message", "Аккаунт игрока удалён"));
                }
                case "unlock" -> {
                    boolean ok = bridge.unlockAccount(playerName).get();
                    plugin.getLogManager().logWebAction(adminName, "LoveAuth: разблокировал аккаунт игрока " + playerName);
                    sendSuccess(resp, Map.of("success", ok, "message", "Аккаунт разблокирован"));
                }
                case "unblock-ip" -> {
                    Map<String, Object> body = readJsonBody(req);
                    String ip = stringOrNull(body.get("ip"));
                    if (ip == null || ip.isBlank()) {
                        sendError(resp, 400, "Укажите IP для разблокировки");
                        return;
                    }
                    boolean ok = bridge.unblockIp(ip).get();
                    plugin.getLogManager().logWebAction(adminName, "LoveAuth: разблокировал IP " + ip);
                    sendSuccess(resp, Map.of("success", ok, "message", "IP разблокирован"));
                }
                case "reset-session" -> {
                    boolean ok = bridge.resetSession(playerName).get();
                    plugin.getLogManager().logWebAction(adminName, "LoveAuth: сбросил сессию игроку " + playerName);
                    sendSuccess(resp, Map.of("success", ok, "message", "Сессия игрока сброшена"));
                }
                default -> sendError(resp, 404, "Неизвестное действие: " + action);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка вызова действия LoveAuth: " + e.getMessage());
            sendError(resp, 500, "Ошибка выполнения операции: " + e.getMessage());
        }
    }
}
