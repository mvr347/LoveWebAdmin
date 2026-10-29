package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.integration.VesuvioBridge;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.bukkit.Bukkit;

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
      int limit = Math.clamp(parseIntOrDefault(req.getParameter("limit"), 100), 1, 200);
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
      int limit = Math.clamp(parseIntOrDefault(req.getParameter("limit"), 200), 1, 200);
            handleAdvanced(req, resp, () -> {
                Object result = bridge.call("getRecentViolations", new Class<?>[]{int.class}, limit);
                if (filterLower != null && result instanceof List<?> list) {
                    return list.stream().filter(item -> matchesPlayer(item, filterLower)).toList();
                }
                return result;
            });
            return;
        }

    if ("/reviews".equals(pathInfo)) {
      handleAdvanced(
          req,
          resp,
          () ->
              bridge.call(
                  "getPendingReviews",
                  new Class<?>[] {int.class},
                  Math.clamp(parseIntOrDefault(req.getParameter("limit"), 50), 1, 200)));
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

    var online =
        plugin
            .main()
            .call(
                () ->
                    Bukkit.getPlayerExact(input) == null
                        ? null
                        : Bukkit.getPlayerExact(input).getUniqueId())
            .join();
    if (online != null) return online;
    var stats = plugin.getDatabaseManager().getPlayerStats(input);
    if (stats.isPresent())
      try {
        return UUID.fromString(String.valueOf(stats.get().get("uuid")));
      } catch (IllegalArgumentException ignored) {
      }

        return null;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
    if (pathInfo != null
        && (pathInfo.equals("/models/reload") || pathInfo.startsWith("/review/"))) {
      var session =
          requirePermission(
              req,
              resp,
              pathInfo.equals("/models/reload")
                  ? Permission.MANAGE_VESUVIO_MODELS
                  : Permission.REVIEW_VESUVIO_SAMPLES);
      if (session.isEmpty()) return;
      try {
        Object result;
        if (pathInfo.equals("/models/reload")) result = bridge.call("reloadModels");
        else {
          var body = readJsonBody(req);
          String verdict = String.valueOf(body.get("verdict"));
          if (!java.util.Set.of("cheat", "legit").contains(verdict)) {
            sendError(resp, 400, "Укажите вердикт");
            return;
          }
          result =
              bridge.call(
                  "submitReview",
                  new Class<?>[] {String.class, String.class, String.class},
                  pathInfo.substring("/review/".length()),
                  verdict,
                  session.get().adminUsername());
        }
        if (!Boolean.TRUE.equals(result)) {
          sendError(resp, 409, "Образец уже рассмотрен, истёк или операция не выполнена");
          return;
        }
        plugin.getLogManager().logWebAction(session.get().adminUsername(), "Vesuvio: " + pathInfo);
        sendSuccess(resp, result);
      } catch (VesuvioBridge.VesuvioUnavailableException error) {
        sendError(resp, 503, error.getMessage());
      }
      return;
    }
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
          var body = readJsonBody(req);
                    bridge.call("resetViolationLevel", new Class<?>[]{UUID.class}, uuid);
          if (Boolean.TRUE.equals(body.get("reduceRisk")))
            bridge.call("adjustRisk", new Class<?>[] {UUID.class, double.class}, uuid, -30d);
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
    boolean available = bridge.isAvailable();
    var data = new java.util.LinkedHashMap<String, Object>();
    data.put("available", available);
    if (available) {
      try {
        data.put("version", bridge.call("getIntegrationVersion"));
        data.put("capabilities", bridge.call("getCapabilities"));
      } catch (VesuvioBridge.VesuvioUnavailableException old) {
        data.put("version", 1);
        data.put("capabilities", List.of("overview", "players", "violations"));
      }
    }
    sendSuccess(resp, data);
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
