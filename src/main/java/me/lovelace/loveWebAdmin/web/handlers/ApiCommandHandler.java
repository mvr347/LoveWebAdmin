package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * POST /api/command { command: "say Hello" }
 * Выполняется от консоли, но в логах фиксируется кто запустил. Только main thread.
 */
public class ApiCommandHandler extends ApiHandlerSupport {

    public ApiCommandHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.EXECUTE_COMMANDS);
        if (sessionOpt.isEmpty()) return;
        WebSession session = sessionOpt.get();

        Map<String, Object> body = readJsonBody(req);
        String command = stringOrNull(body.get("command"));
        if (command == null || command.isBlank()) {
            sendError(resp, 400, "Не указана команда");
            return;
        }

        if (isBlacklisted(command)) {
            plugin.getLogManager().logWebAction(session.adminUsername(), "Попытка выполнить запрещённую команду: /" + command);
            sendError(resp, 403, "Эта команда запрещена к выполнению через веб-панель");
            return;
        }

        try {
            boolean dispatched = plugin.getServer().getScheduler()
                .callSyncMethod(plugin, () ->
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command))
                .get();

            plugin.getLogManager().logWebAction(session.adminUsername(), "Выполнил команду: /" + command);

            if (dispatched) {
                sendSuccess(resp, Map.of("message", "Команда выполнена"));
            } else {
                sendError(resp, 400, "Команда не распознана сервером");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка выполнения команды: " + e.getMessage());
            sendError(resp, 500, "Внутренняя ошибка сервера");
        }
    }

    private boolean isBlacklisted(String command) {
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int spaceIdx = trimmed.indexOf(' ');
        String label = (spaceIdx == -1 ? trimmed : trimmed.substring(0, spaceIdx)).toLowerCase(Locale.ROOT);
        int colonIdx = label.indexOf(':');
        if (colonIdx != -1) {
            label = label.substring(colonIdx + 1);
        }

        List<String> blacklist = plugin.getConfig().getStringList("security.command-blacklist");
        for (String blocked : blacklist) {
            if (blocked != null && blocked.equalsIgnoreCase(label)) {
                return true;
            }
        }
        return false;
    }
}
