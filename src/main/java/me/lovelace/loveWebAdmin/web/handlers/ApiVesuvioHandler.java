package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.integration.VesuvioBridge;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Vesuvio AntiCheat tab.
 *
 * GET  /api/vesuvio/status              — доступность плагина (любая авторизованная сессия)
 * GET  /api/vesuvio/suspects            — базовый: подозреваемые                      [VIEW_VESUVIO]
 * GET  /api/vesuvio/punishments?limit=  — базовый: лог наказаний                      [VIEW_VESUVIO]
 * GET  /api/vesuvio/violations?limit=   — расширенный: сырой лог флагов               [VIEW_VESUVIO_ADVANCED]
 * GET  /api/vesuvio/engine              — расширенный: статус движка/датасета/моделей [VIEW_VESUVIO_ADVANCED]
 * GET  /api/vesuvio/player/{uuid}       — расширенный: полный профиль игрока          [VIEW_VESUVIO_ADVANCED]
 * POST /api/vesuvio/player/{uuid}/reset-vl               — сброс VL                   [MANAGE_VESUVIO]
 * POST /api/vesuvio/player/{uuid}/suspect { suspect }    — пометить/снять подозрение  [MANAGE_VESUVIO]
 */
public class ApiVesuvioHandler extends ApiHandlerSupport {

    private final VesuvioBridge bridge;

    public ApiVesuvioHandler(LoveWebAdmin plugin) {
        super(plugin);
        this.bridge = plugin.getVesuvioBridge();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null) pathInfo = "";

        if ("/status".equals(pathInfo)) {
            handleStatus(req, resp);
            return;
        }
        if ("/suspects".equals(pathInfo)) {
            handleBasic(req, resp, () -> bridge.call("getSuspects"));
            return;
        }
        if ("/punishments".equals(pathInfo)) {
            int limit = parseIntOrDefault(req.getParameter("limit"), 100);
            handleBasic(req, resp, () -> bridge.call("getRecentPunishments", new Class<?>[]{int.class}, limit));
            return;
        }
        if ("/violations".equals(pathInfo)) {
            int limit = parseIntOrDefault(req.getParameter("limit"), 200);
            handleAdvanced(req, resp, () -> bridge.call("getRecentViolations", new Class<?>[]{int.class}, limit));
            return;
        }
        if ("/engine".equals(pathInfo)) {
            handleAdvanced(req, resp, () -> bridge.call("getEngineStatus"));
            return;
        }
        if (pathInfo.startsWith("/player/")) {
            String uuidStr = pathInfo.substring("/player/".length());
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                sendError(resp, 400, "Некорректный UUID");
                return;
            }
            final UUID finalUuid = uuid;
            handleAdvanced(req, resp, () -> bridge.call("getPlayerDetail", new Class<?>[]{UUID.class}, finalUuid));
            return;
        }

        sendError(resp, 404, "Не найдено");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
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
        String uuidStr = rest.substring(0, slash);
        String action = rest.substring(slash + 1);

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            sendError(resp, 400, "Некорректный UUID");
            return;
        }

        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_VESUVIO);
        if (sessionOpt.isEmpty()) return;
        WebSession session = sessionOpt.get();

        try {
            switch (action) {
                case "reset-vl" -> {
                    bridge.call("resetViolationLevel", new Class<?>[]{UUID.class}, uuid);
                    plugin.getLogManager().logWebAction(session.adminUsername(), "Vesuvio: сброс VL игроку " + uuid);
                    sendSuccess(resp, null);
                }
                case "suspect" -> {
                    Map<String, Object> body = readJsonBody(req);
                    boolean suspect = Boolean.TRUE.equals(body.get("suspect"));
                    bridge.call("setManualSuspect", new Class<?>[]{UUID.class, boolean.class}, uuid, suspect);
                    plugin.getLogManager().logWebAction(session.adminUsername(),
                            "Vesuvio: " + (suspect ? "пометил подозреваемым " : "снял подозрение с ") + uuid);
                    sendSuccess(resp, null);
                }
                default -> sendError(resp, 404, "Не найдено");
            }
        } catch (VesuvioBridge.VesuvioUnavailableException e) {
            sendError(resp, 503, e.getMessage());
        }
    }

    private void handleStatus(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;
        sendSuccess(resp, Map.of("available", bridge.isAvailable()));
    }

    private void handleBasic(HttpServletRequest req, HttpServletResponse resp, BridgeCall call) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_VESUVIO);
        if (sessionOpt.isEmpty()) return;
        respondWithBridge(resp, call);
    }

    private void handleAdvanced(HttpServletRequest req, HttpServletResponse resp, BridgeCall call) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_VESUVIO_ADVANCED);
        if (sessionOpt.isEmpty()) return;
        respondWithBridge(resp, call);
    }

    private void respondWithBridge(HttpServletResponse resp, BridgeCall call) throws IOException {
        try {
            sendJson(resp, 200, JsonUtils.success(call.get()));
        } catch (VesuvioBridge.VesuvioUnavailableException e) {
            sendError(resp, 503, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface BridgeCall {
        Object get();
    }
}
