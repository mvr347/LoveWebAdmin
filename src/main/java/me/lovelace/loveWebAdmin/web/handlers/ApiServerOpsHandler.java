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
    Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if ("/config".equals(pathInfo)) {
      if (!me.lovelace.loveWebAdmin.config.PanelSettings.canRead(
          permission -> hasPermission(sessionOpt.get(), permission))) {
        sendError(resp, 403, "Нет права просмотра настроек сервера");
        return;
      }
      handleGetConfig(resp, sessionOpt.get());
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
      if (!allowed(sessionOpt.get(), Permission.MANAGE_WHITELIST)) {
        sendError(resp, 403, "Нужно право управления whitelist");
        return;
      }
            handleGetWhitelist(resp);
            return;
        }
        if ("/ops".equals(pathInfo)) {
      if (!allowed(sessionOpt.get(), Permission.MANAGE_OPERATORS)) {
        sendError(resp, 403, "Нужно право управления операторами");
        return;
      }
            handleGetOps(resp);
            return;
        }

        sendError(resp, 404, "Не найдено");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    Optional<WebSession> sessionOpt = requireSession(req, resp);
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

    req.setAttribute(
        "lwa.permission",
        ("/ops".equals(pathInfo) ? Permission.MANAGE_OPERATORS : Permission.MANAGE_WHITELIST)
            .name());
    // Explicit permission, independent of role name.
    boolean isAdmin =
        allowed(
            session,
            "/ops".equals(pathInfo) ? Permission.MANAGE_OPERATORS : Permission.MANAGE_WHITELIST);
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
    boolean canManage = allowed(session, Permission.MANAGE_MAINTENANCE);
        if (!canManage) {
            sendError(resp, 403, "Управление режимом технических работ доступно только высшим ролям");
            return;
        }

    req.setAttribute("lwa.permission", Permission.MANAGE_MAINTENANCE.name());
        Map<String, Object> body = readJsonBody(req);
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String message = stringOrNull(body.get("message"));
        if (message == null || message.isBlank()) {
            message = "Ведутся технические работы на сервере. Пожалуйста, зайдите позже.";
        }

    final String configuredMessage = message;
    plugin
        .main()
        .call(
            () -> {
              plugin.setMaintenanceMode(enabled);
              plugin.setMaintenanceMessage(configuredMessage);

              plugin.getConfig().set("security.maintenance.enabled", enabled);
              plugin.getConfig().set("security.maintenance.message", configuredMessage);
              plugin.saveConfig();
              return null;
            })
        .join();

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

  private boolean allowed(WebSession session, Permission permission) {
    return hasPermission(session, permission);
  }

  public static Permission configPermission(String field) {
    return me.lovelace.loveWebAdmin.config.PanelSettings.permission(field);
  }

  private Map<String, Object> configView(WebSession session) {
    var raw =
        plugin
            .main()
            .call(() -> me.lovelace.loveWebAdmin.config.PanelSettings.snapshot(plugin.getConfig()))
            .join();
    var rights = new java.util.EnumMap<Permission, Boolean>(Permission.class);
    return me.lovelace.loveWebAdmin.config.PanelSettings.view(
        raw, permission -> rights.computeIfAbsent(permission, key -> allowed(session, key)));
  }

  private void handleGetConfig(HttpServletResponse resp, WebSession session) throws IOException {
    sendSuccess(resp, configView(session));
  }

  private void handlePostConfig(
      HttpServletRequest req, HttpServletResponse resp, WebSession session) throws IOException {
    var body = readJsonBody(req);
    if (body.isEmpty()) {
      sendError(resp, 400, "Нет настроек для изменения");
      return;
    }
    for (String field : body.keySet())
      if (!allowed(session, configPermission(field))) {
        sendError(resp, 403, "Нет права изменять параметр: " + field);
        return;
      }
    req.setAttribute(
        "lwa.permission",
        body.keySet().stream()
            .map(f -> configPermission(f).name())
            .distinct()
            .collect(java.util.stream.Collectors.joining(",")));
    try {
      var restart =
          plugin
              .main()
              .call(
                  () -> {
                    var cfg = plugin.getConfig();
                    var before = me.lovelace.loveWebAdmin.config.PanelSettings.snapshot(cfg);
                    var patch =
                        me.lovelace.loveWebAdmin.config.PanelSettings.normalize(body, before);
                    var changed =
                        me.lovelace.loveWebAdmin.config.PanelSettings.restartFields(patch, before);
                    var candidate = new org.bukkit.configuration.file.YamlConfiguration();
                    try {
                      candidate.loadFromString(cfg.saveToString());
                      me.lovelace.loveWebAdmin.config.PanelSettings.apply(candidate, patch);
                      me.lovelace.loveWebAdmin.config.PanelSettings.persist(
                          plugin.getDataFolder().toPath().resolve("config.yml"),
                          candidate.saveToString());
                    } catch (Exception error) {
                      throw new IllegalStateException(
                          "Не удалось записать настройки; прежняя конфигурация сохранена", error);
                    }
                    plugin.reloadConfig();
                    cfg = plugin.getConfig();
                    if (patch.containsKey("maintenanceEnabled")
                        || patch.containsKey("maintenanceMessage"))
                      plugin.setMaintenanceMode(
                          cfg.getBoolean("security.maintenance.enabled", false),
                          cfg.getString(
                              "security.maintenance.message", "Ведутся технические работы"));
                    return changed;
                  })
              .join();
      plugin
          .getLogManager()
          .logWebAction(
              session.adminUsername(),
              "Изменил параметры конфигурации: " + String.join(", ", body.keySet()));
      var result = new LinkedHashMap<String, Object>(configView(session));
      result.put("restartRequiredFields", restart);
      sendSuccess(resp, result);
    } catch (java.util.concurrent.CompletionException error) {
      if (error.getCause() instanceof IllegalArgumentException invalid) {
        sendError(resp, 400, invalid.getMessage());
        return;
      }
      throw error;
    }
  }

    private void handleGetWhitelist(HttpServletResponse resp) throws IOException {
        try {
      var result =
          plugin
              .main()
              .call(
                  () -> {
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
                    return Map.of("enabled", enabled, "count", players.size(), "players", players);
                  })
              .join();
      sendSuccess(resp, result);
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
            plugin.main().call( () -> {
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
      var result =
          plugin
              .main()
              .call(
                  () -> {
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
                    return Map.of("count", ops.size(), "operators", ops);
                  })
              .join();
      sendSuccess(resp, result);
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
            plugin.main().call( () -> {
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
