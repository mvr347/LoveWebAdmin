package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Управление режимом экстренной защиты («Красная кнопка» / Режим ЧС).
 *
 * GET  /api/lockdown/status     — текущий статус ЧС и настройки (любой авторизованный)
 * POST /api/lockdown/activate   — включить режим ЧС             [MANAGE_LOCKDOWN]
 * POST /api/lockdown/deactivate — выключить режим ЧС            [MANAGE_LOCKDOWN]
 */
public class ApiLockdownHandler extends ApiHandlerSupport {

    public ApiLockdownHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> session = authenticate(req);
        if (session.isEmpty()) {
            sendError(resp, 401, "Необходима авторизация");
            return;
        }

        sendSuccess(resp, plugin.getLockdownManager().getStatusMap());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> session = authenticate(req);
        if (session.isEmpty()) {
            sendError(resp, 401, "Необходима авторизация");
            return;
        }

        if (!hasPermission(session.get(), Permission.MANAGE_LOCKDOWN) && !hasPermission(session.get(), Permission.MANAGE_ADMINS)) {
            sendError(resp, 403, "Недостаточно прав для управления режимом ЧС");
            return;
        }

        String pathInfo = req.getPathInfo();
        if (pathInfo == null) pathInfo = "";

        String adminName = session.get().adminUsername();

        if ("/activate".equals(pathInfo)) {
            String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> map = JsonUtils.parseObject(body);
            String reason = map != null && map.containsKey("reason") ? String.valueOf(map.get("reason")) : "Экстренная изоляция";
            boolean kickNewbies = map == null || !Boolean.FALSE.equals(map.get("kickNewbies"));
            boolean muteChat = map == null || !Boolean.FALSE.equals(map.get("muteChat"));
            boolean blockCommands = map != null && Boolean.TRUE.equals(map.get("blockCommands"));
            boolean whitelistOnly = map == null || !Boolean.FALSE.equals(map.get("whitelistOnly"));

            plugin.getLockdownManager().activate(adminName, reason, kickNewbies, muteChat, blockCommands, whitelistOnly);
            sendSuccess(resp, Map.of("message", "Режим ЧС активирован", "status", plugin.getLockdownManager().getStatusMap()));
            return;
        }

        if ("/deactivate".equals(pathInfo)) {
            plugin.getLockdownManager().deactivate(adminName);
            sendSuccess(resp, Map.of("message", "Режим ЧС деактивирован", "status", plugin.getLockdownManager().getStatusMap()));
            return;
        }

        sendError(resp, 404, "Маршрут не найден");
    }
}
