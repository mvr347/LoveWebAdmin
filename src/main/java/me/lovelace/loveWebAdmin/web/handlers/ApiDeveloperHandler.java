package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.ApiKeyRecord;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebhookRecord;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST обработчик раздела разработчиков: управление API ключами, исходящими вебхуками
 * и интерактивная документация API.
 */
public class ApiDeveloperHandler extends ApiHandlerSupport {

    public ApiDeveloperHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        String path = req.getPathInfo();
        if (path == null || path.equals("/") || path.equals("/docs")) {
            sendSuccess(resp, getApiDocumentation());
            return;
        }

        if (path.equals("/keys")) {
            if (!hasPermission(sessionOpt.get(), Permission.MANAGE_ADMINS) && !hasPermission(sessionOpt.get(), Permission.MANAGE_API)) {
                sendError(resp, 403, "Управление API ключами доступно только руководству");
                return;
            }
            List<ApiKeyRecord> keys = plugin.getApiKeyManager().getAllKeys();
            List<Map<String, Object>> result = keys.stream().map(k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", k.id());
                m.put("name", k.name());
                m.put("prefix", k.prefix());
                m.put("permissions", k.permissions());
                m.put("creator", k.creator());
                m.put("createdAt", k.createdAt());
                m.put("lastUsedAt", k.lastUsedAt());
                m.put("isActive", k.isActive());
                return m;
            }).toList();
            sendSuccess(resp, result);
            return;
        }

        if (path.equals("/webhooks")) {
            if (!hasPermission(sessionOpt.get(), Permission.MANAGE_ADMINS) && !hasPermission(sessionOpt.get(), Permission.MANAGE_API)) {
                sendError(resp, 403, "Управление вебхуками доступно только руководству");
                return;
            }
            List<WebhookRecord> hooks = plugin.getDatabaseManager().getAllWebhooks();
            sendSuccess(resp, hooks);
            return;
        }

        sendError(resp, 404, "Эндпоинт не найден");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        if (!hasPermission(sessionOpt.get(), Permission.MANAGE_ADMINS) && !hasPermission(sessionOpt.get(), Permission.MANAGE_API)) {
            sendError(resp, 403, "Действия с API и вебхуками доступны только руководству");
            return;
        }

        String path = req.getPathInfo();
        if (path == null) path = "";

        if (path.equals("/keys")) {
            Map<String, Object> body = readJsonBody(req);
            String name = stringOrNull(body.get("name"));
            if (name == null || name.isBlank()) {
                sendError(resp, 400, "Укажите название API ключа (например, 'Discord Bot')");
                return;
            }

            List<String> perms = new ArrayList<>();
            if (body.get("permissions") instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) perms.add(String.valueOf(o));
                }
            }
            if (perms.isEmpty()) {
                perms.add("*"); // Полный доступ по умолчанию
            }

            var gen = plugin.getApiKeyManager().createKey(name.trim(), perms, sessionOpt.get().adminUsername());
            plugin.getStaffAuditManager().recordAction(sessionOpt.get().adminUsername(), "Создал API ключ '" + name + "'");

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("name", gen.record().name());
            res.put("token", gen.secretToken());
            res.put("secretToken", gen.secretToken());
            res.put("prefix", gen.record().prefix());
            res.put("record", gen.record());
            sendSuccess(resp, res);
            return;
        }

        if (path.equals("/webhooks")) {
            Map<String, Object> body = readJsonBody(req);
            String name = stringOrNull(body.get("name"));
            String url = stringOrNull(body.get("url"));
            String secret = stringOrNull(body.get("secret"));

            if (name == null || name.isBlank() || url == null || !url.startsWith("http")) {
                sendError(resp, 400, "Укажите корректное имя и URL вебхука (начинающийся с http/https)");
                return;
            }

            List<String> events = new ArrayList<>();
            if (body.get("events") instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) events.add(String.valueOf(o).toUpperCase());
                }
            }
            if (events.isEmpty()) {
                events.add("*");
            }

            WebhookRecord hook = plugin.getDatabaseManager().saveWebhook(name.trim(), url.trim(), events, secret);
            plugin.getStaffAuditManager().recordAction(sessionOpt.get().adminUsername(), "Создал вебхук '" + name + "' (" + url + ")");
            sendSuccess(resp, hook);
            return;
        }

        if (path.startsWith("/webhooks/") && path.endsWith("/test")) {
            String idStr = path.substring("/webhooks/".length(), path.length() - "/test".length());
            try {
                int id = Integer.parseInt(idStr);
                Optional<WebhookRecord> hookOpt = plugin.getDatabaseManager().getAllWebhooks().stream().filter(h -> h.id() == id).findFirst();
                if (hookOpt.isEmpty()) {
                    sendError(resp, 404, "Вебхук не найден");
                    return;
                }

                int statusCode = plugin.getWebhookManager().sendTestPing(hookOpt.get()).join();
                boolean isDelivered = statusCode >= 200 && statusCode < 300;
                sendSuccess(resp, Map.of("statusCode", statusCode, "delivered", isDelivered, "success", isDelivered));
                return;
            } catch (NumberFormatException e) {
                sendError(resp, 400, "Некорректный ID вебхука");
                return;
            }
        }

        sendError(resp, 404, "Эндпоинт не найден");
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireSession(req, resp);
        if (sessionOpt.isEmpty()) return;

        if (!hasPermission(sessionOpt.get(), Permission.MANAGE_ADMINS) && !hasPermission(sessionOpt.get(), Permission.MANAGE_API)) {
            sendError(resp, 403, "Удаление доступно только руководству");
            return;
        }

        String path = req.getPathInfo();
        if (path == null) path = "";

        if (path.startsWith("/keys/")) {
            try {
                int id = Integer.parseInt(path.substring("/keys/".length()));
                boolean ok = plugin.getApiKeyManager().revokeKey(id);
                plugin.getStaffAuditManager().recordAction(sessionOpt.get().adminUsername(), "Отозвал API ключ #" + id);
                sendSuccess(resp, Map.of("revoked", ok));
                return;
            } catch (NumberFormatException e) {
                sendError(resp, 400, "Некорректный ID ключа");
                return;
            }
        }

        if (path.startsWith("/webhooks/")) {
            try {
                int id = Integer.parseInt(path.substring("/webhooks/".length()));
                boolean ok = plugin.getDatabaseManager().deleteWebhook(id);
                plugin.getStaffAuditManager().recordAction(sessionOpt.get().adminUsername(), "Удалил вебхук #" + id);
                sendSuccess(resp, Map.of("deleted", ok));
                return;
            } catch (NumberFormatException e) {
                sendError(resp, 400, "Некорректный ID вебхука");
                return;
            }
        }

        sendError(resp, 404, "Эндпоинт не найден");
    }

    private Map<String, Object> getApiDocumentation() {
        Map<String, Object> docs = new LinkedHashMap<>();
        docs.put("name", "LoveWebAdmin REST API");
        docs.put("version", "v1.0");
        docs.put("auth_methods", List.of("X-API-Key: <token>", "Authorization: Bearer <token>"));

        List<Map<String, Object>> endpoints = new ArrayList<>();

        endpoints.add(createEndpointDoc("GET", "/api/stats", "Общая сводка онлайна сервера, TPS, активных банов и операторов", "VIEW_STATS", null, "{}"));
        endpoints.add(createEndpointDoc("GET", "/api/analytics/period?period=today", "Глубокая аналитика за период (сегодня/вчера/7d/30d) с процентными дельтами", "VIEW_STATS", "period: today, yesterday, 7d, 30d, custom", "{}"));
        endpoints.add(createEndpointDoc("GET", "/api/players/all", "Список игроков сервера (онлайн и база данных)", "VIEW_PLAYERS", null, "[]"));
        endpoints.add(createEndpointDoc("GET", "/api/players/{name}", "Полное досье игрока: статистика, сессии, инвентарь, IP-альты, заметки", "VIEW_PLAYERS", "name: никнейм игрока", "{}"));
        endpoints.add(createEndpointDoc("GET", "/api/bans/all", "Список всех блокировок на сервере", "VIEW_BANS", "status: ACTIVE или UNBANNED", "[]"));
        endpoints.add(createEndpointDoc("POST", "/api/bans", "Выдача перманентного бана игроку", "MANAGE_BANS", null, "{\"targetName\":\"Player\",\"ruleReason\":\"Читы\",\"description\":\"Доказательства...\",\"screenshotUrl\":\"https://...\"}"));
        endpoints.add(createEndpointDoc("POST", "/api/bans/{id}/unban", "Снятие бана с игрока", "MANAGE_BANS", "id: ID бана", "{}"));
        endpoints.add(createEndpointDoc("GET", "/api/reports/all", "Список внутриигровых жалоб (/репорт)", "VIEW_REPORTS", "status: PENDING, ACCEPTED, REJECTED", "[]"));
        endpoints.add(createEndpointDoc("POST", "/api/reports/{id}/accept", "Принятие жалобы и начисление репутации заявителю", "MANAGE_REPORTS", "id: ID жалобы", "{}"));
        endpoints.add(createEndpointDoc("POST", "/api/command", "Выполнение консольной команды на сервере", "EXECUTE_COMMANDS", null, "{\"command\":\"say Hello from API\"}"));
        endpoints.add(createEndpointDoc("GET", "/api/server/ops", "Оперативные метрики сервера (TPS, CPU, Heap RAM, Whitelist)", "VIEW_STATS", null, "{}"));
        endpoints.add(createEndpointDoc("GET", "/api/economy/overview", "Экономическая сводка LoveCore: общая масса монет, топ богачей", "VIEW_STATS", null, "{}"));
        endpoints.add(createEndpointDoc("GET", "/api/loveauth/status", "Статус плагина LoveAuth, шифрование и брутфорс защита", "VIEW_SECURITY", null, "{}"));
        endpoints.add(createEndpointDoc("GET", "/api/developer/keys", "Список активных API ключей разработчиков", "MANAGE_ADMINS", null, "[]"));
        endpoints.add(createEndpointDoc("POST", "/api/developer/keys", "Создание нового API ключа", "MANAGE_ADMINS", null, "{\"name\":\"Discord Bot\",\"permissions\":[\"*\"]}"));
        endpoints.add(createEndpointDoc("GET", "/api/developer/webhooks", "Список исходящих вебхуков Discord / HTTP", "MANAGE_ADMINS", null, "[]"));

        docs.put("endpoints", endpoints);
        return docs;
    }

    private Map<String, Object> createEndpointDoc(String method, String path, String desc, String permission, String params, String sampleBody) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", method);
        m.put("path", path);
        m.put("description", desc);
        m.put("required_permission", permission);
        if (params != null) m.put("params", params);
        if (sampleBody != null && !sampleBody.equals("{}") && !sampleBody.equals("[]")) m.put("sample_body", sampleBody);
        return m;
    }
}
