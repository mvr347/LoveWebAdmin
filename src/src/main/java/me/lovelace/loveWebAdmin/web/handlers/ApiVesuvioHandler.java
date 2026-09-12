package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.integration.VesuvioBridge;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Античит (Vesuvio):
 *
 * GET  /api/vesuvio/status              — доступность плагина (любая сессия)
 * GET  /api/vesuvio/suspects?player=    — подозреваемые с опциональным фильтром       [VIEW_VESUVIO]
 * GET  /api/vesuvio/punishments?player=&limit= — лог наказаний с фильтром             [VIEW_VESUVIO]
 * GET  /api/vesuvio/violations?player=&limit=  — сырой лог флагов с фильтром          [VIEW_VESUVIO_ADVANCED]
 * GET  /api/vesuvio/engine              — статус движка/датасета                     [VIEW_VESUVIO_ADVANCED]
 * GET  /api/vesuvio/player/{nameOrUuid} — детальный профиль античита                 [VIEW_VESUVIO_ADVANCED]
 * POST /api/vesuvio/player/{uuid}/reset-vl            — сброс VL                     [MANAGE_VESUVIO]
 * POST /api/vesuvio/player/{uuid}/suspect { suspect } — пометка/снятие подозрения    [MANAGE_VESUVIO]
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

        if (pathInfo.isEmpty() || "/".equals(pathInfo) || "/status".equals(pathInfo)) {
            handleStatus(req, resp);
            return;
        }

        String playerFilter = req.getParameter("player");
        final String filterLower = (playerFilter != null && !playerFilter.isBlank()) ? playerFilter.trim().toLowerCase() : null;

        if ("/suspects".equals(pathInfo)) {
            handleBasic(req, resp, () -> {
                Object result = bridge.call("getSuspects");
                if (filterLower != null && result instanceof List<?> list) {
                    return list.stream().filter(item -> matchesPlayer(item, filterLower)).toList();
                }
                return result;
            });
            return;
        }

        if ("/punishments".equals(pathInfo)) {
            int limit = parseIntOrDefault(req.getParameter("limit"), 100);
            handleBasic(req, resp, () -> {
                Object result = bridge.call("getRecentPunishments", new Class<?>[]{int.class}, limit);
                if (filterLower != null && result instanceof List<?> list) {
                    return list.stream().filter(item -> matchesPlayer(item, filterLower)).toList();
                }
                return result;
            });
            return;
        }

        if ("/violations".equals(pathInfo)) {
            int limit = parseIntOrDefault(req.getParameter("limit"), 200);
            handleAdvanced(req, resp, () -> {
                Object result = bridge.call("getRecentViolations", new Class<?>[]{int.class}, limit);
                if (filterLower != null && result instanceof List<?> list) {
                    return list.stream().filter(item -> matchesPlayer(item, filterLower)).toList();
                }
                return result;
            });
            return;
        }

        if ("/engine".equals(pathInfo)) {
            handleAdvanced(req, resp, () -> bridge.call("getEngineStatus"));
            return;
        }

        if (pathInfo.startsWith("/player/")) {
            String identifier = pathInfo.substring("/player/".length()).trim();
            UUID uuid = resolveUuid(identifier);
            if (uuid == null) {
                sendError(resp, 400, "Игрок не найден или некорректный UUID");
                return;
            }
            final UUID finalUuid = uuid;
            handleAdvanced(req, resp, () -> bridge.call("getPlayerDetail", new Class<?>[]{UUID.class}, finalUuid));
            return;
        }

        sendError(resp, 404, "Не найдено");
    }

    private boolean matchesPlayer(Object item, String queryLower) {
        if (item instanceof Map<?, ?> map) {
            Object name = map.get("name");
            if (name != null && String.valueOf(name).toLowerCase().contains(queryLower)) return true;
            Object uuid = map.get("uuid");
            if (uuid != null && String.valueOf(uuid).toLowerCase().contains(queryLower)) return true;
        }
        return false;
    }

    private UUID resolveUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {}

        var online = Bukkit.getPlayerExact(input);
        if (online != null) return online.getUniqueId();

        OfflinePlayer offline = Bukkit.getOfflinePlayer(input);
        if (offline.hasPlayedBefore() || offline.getName() != null) {
            return offline.getUniqueId();
        }
        return null;
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
        String identifier = rest.substring(0, slash);
        String action = rest.substring(slash + 1);

        UUID uuid = resolveUuid(identifier);
        if (uuid == null) {
            sendError(resp, 400, "Некорректный UUID или игрок не найден");
            return;
        }

        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_VESUVIO);
        if (sessionOpt.isEmpty()) return;
        WebSession session = sessionOpt.get();

        try {
            switch (action) {
                case "reset-vl" -> {
                    bridge.call("resetViolationLevel", new Class<?>[]{UUID.class}, uuid);
                    plugin.getLogManager().logWebAction(session.adminUsername(), "Античит: сброс VL игроку " + uuid);
                    sendSuccess(resp, null);
                }
                case "suspect" -> {
                    Map<String, Object> body = readJsonBody(req);
                    boolean suspect = Boolean.TRUE.equals(body.get("suspect"));
                    bridge.call("setManualSuspect", new Class<?>[]{UUID.class, boolean.class}, uuid, suspect);
                    plugin.getLogManager().logWebAction(session.adminUsername(),
                            "Античит: " + (suspect ? "пометил подозреваемым " : "снял подозрение с ") + uuid);
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
