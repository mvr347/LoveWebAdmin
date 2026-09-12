package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebhookRecord;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Управление исходящими вебхуками для Discord и внешних HTTP эндпоинтов.
 */
public class WebhookManager {

    private final LoveWebAdmin plugin;
    private final HttpClient httpClient;

    public WebhookManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();
    }

    /**
     * Асинхронно отправляет событие во все подписанные активные вебхуки.
     */
    public void dispatch(String event, Map<String, Object> data) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            List<WebhookRecord> hooks = plugin.getDatabaseManager().getActiveWebhooks();
            if (hooks.isEmpty()) return;

            long now = System.currentTimeMillis() / 1000L;
            for (WebhookRecord hook : hooks) {
                if (!hook.subscribesTo(event)) continue;

                try {
                    sendToHook(hook, event, data, now);
                } catch (Exception e) {
                    plugin.getLogger().warning("[WebhookManager] Ошибка отправки в вебхук #" + hook.id() + ": " + e.getMessage());
                }
            }
        });
    }

    /**
     * Отправляет тестовое уведомление в конкретный вебхук (для проверки в веб-панели).
     */
    public CompletableFuture<Integer> sendTestPing(WebhookRecord hook) {
        Map<String, Object> testData = Map.of(
            "message", "Тестовое оповещение из LoveWebAdmin",
            "source", "Панель управления / API",
            "timestamp", Instant.now().toString(),
            "status", "ONLINE"
        );
        return sendToHookAsync(hook, "TEST_PING", testData, System.currentTimeMillis() / 1000L);
    }

    private void sendToHook(WebhookRecord hook, String event, Map<String, Object> data, long timestamp) {
        sendToHookAsync(hook, event, data, timestamp);
    }

    private CompletableFuture<Integer> sendToHookAsync(WebhookRecord hook, String event, Map<String, Object> data, long timestamp) {
        String payloadJson;
        if (hook.isDiscord()) {
            payloadJson = buildDiscordPayload(event, data);
        } else {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("event", event);
            raw.put("timestamp", timestamp);
            raw.put("server", plugin.getServer().getName());
            raw.put("data", data);
            payloadJson = JsonUtils.toJson(raw);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(hook.url()))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .header("User-Agent", "LoveWebAdmin-Webhook/1.0")
            .header("X-LWA-Event", event)
            .header("X-LWA-Timestamp", String.valueOf(timestamp));

        if (hook.secret() != null && !hook.secret().isBlank()) {
            String signature = hmacSha256(payloadJson, hook.secret());
            builder.header("X-LWA-Signature", signature);
        }

        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8)).build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .thenApply(resp -> {
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    plugin.getDatabaseManager().updateWebhookLastTrigger(hook.id(), timestamp);
                }
                return status;
            })
            .exceptionally(ex -> {
                plugin.getLogger().warning("[WebhookManager] Сбой доставки в " + hook.url() + ": " + ex.getMessage());
                return 500;
            });
    }

    private String buildDiscordPayload(String event, Map<String, Object> data) {
        String title = "🔔 Событие сервера: " + event;
        String desc = "Произошло событие в LoveWebAdmin";
        int color = 0x8b5cf6; // Фиолетовый

        List<Map<String, Object>> fields = new ArrayList<>();

        switch (event.toUpperCase()) {
            case "BAN" -> {
                title = "🔨 Новая блокировка игрока";
                color = 0xef4444; // Красный
                fields.add(Map.of("name", "Нарушитель", "value", "`" + data.getOrDefault("targetName", "—") + "`", "inline", true));
                fields.add(Map.of("name", "Администратор", "value", "`" + data.getOrDefault("creatorName", "—") + "`", "inline", true));
                fields.add(Map.of("name", "Причина", "value", String.valueOf(data.getOrDefault("ruleReason", "—")), "inline", false));
                if (data.get("description") != null && !String.valueOf(data.get("description")).isBlank()) {
                    fields.add(Map.of("name", "Описание", "value", String.valueOf(data.get("description")), "inline", false));
                }
            }
            case "UNBAN" -> {
                title = "🔓 Снятие блокировки (Разбан)";
                color = 0x10b981; // Зеленый
                fields.add(Map.of("name", "Игрок", "value", "`" + data.getOrDefault("targetName", "—") + "`", "inline", true));
                Object who = data.get("adminName") != null ? data.get("adminName") : data.get("actorUsername");
                fields.add(Map.of("name", "Разбанил", "value", "`" + (who != null ? who : "—") + "`", "inline", true));
            }
            case "REPORT" -> {
                title = "📋 Подана новая жалоба (/репорт)";
                color = 0xc084fc; // Светло-фиолетовый
                fields.add(Map.of("name", "Обвиняемый", "value", "`" + data.getOrDefault("targetName", "—") + "`", "inline", true));
                fields.add(Map.of("name", "Заявитель", "value", "`" + data.getOrDefault("reporterName", "—") + "`", "inline", true));
                fields.add(Map.of("name", "Причины", "value", String.valueOf(data.getOrDefault("reasons", "Не указаны")), "inline", false));
                if (data.get("description") != null) {
                    fields.add(Map.of("name", "Описание", "value", String.valueOf(data.get("description")), "inline", false));
                }
            }
            case "REPORT_RESOLVED" -> {
                title = "✔ Жалоба рассмотрена";
                color = "ACCEPTED".equals(data.get("status")) ? 0x10b981 : 0x64748b;
                fields.add(Map.of("name", "Номер жалобы", "value", "#" + data.getOrDefault("reportId", "0"), "inline", true));
                fields.add(Map.of("name", "Статус", "value", String.valueOf(data.getOrDefault("status", "—")), "inline", true));
                fields.add(Map.of("name", "Рассмотрел", "value", String.valueOf(data.getOrDefault("resolvedBy", "—")), "inline", true));
            }
            case "ANTICHEAT" -> {
                title = "⚠️ Срабатывание античита (Vesuvio)";
                color = 0xf59e0b; // Оранжевый
                fields.add(Map.of("name", "Игрок", "value", "`" + data.getOrDefault("player", "—") + "`", "inline", true));
                fields.add(Map.of("name", "Чек", "value", String.valueOf(data.getOrDefault("check", "—")), "inline", true));
                fields.add(Map.of("name", "VL", "value", String.valueOf(data.getOrDefault("vl", "1")), "inline", true));
            }
            case "LOCKDOWN" -> {
                title = "🚨 Режим изоляции (Lockdown)";
                color = 0xdc2626;
                fields.add(Map.of("name", "Статус", "value", Boolean.TRUE.equals(data.get("active")) ? "ВКЛЮЧЕН" : "ВЫКЛЮЧЕН", "inline", true));
                fields.add(Map.of("name", "Администратор", "value", String.valueOf(data.getOrDefault("admin", "Консоль")), "inline", true));
                fields.add(Map.of("name", "Причина", "value", String.valueOf(data.getOrDefault("reason", "—")), "inline", false));
            }
            case "TEST_PING" -> {
                title = "⚡ Тестовый вебхук LoveWebAdmin";
                color = 0x06b6d4;
                desc = "Вебхук успешно настроен и готов принимать события сервера!";
                fields.add(Map.of("name", "Статус интеграции", "value", "✅ Подключение установлено", "inline", true));
            }
            default -> {
                for (var entry : data.entrySet()) {
                    fields.add(Map.of("name", entry.getKey(), "value", String.valueOf(entry.getValue()), "inline", true));
                }
            }
        }

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", title);
        embed.put("description", desc);
        embed.put("color", color);
        embed.put("fields", fields);
        embed.put("timestamp", Instant.now().toString());
        embed.put("footer", Map.of("text", "LoveWebAdmin Server Telemetry"));

        return JsonUtils.toJson(Map.of(
            "username", "LoveWebAdmin",
            "avatar_url", "https://mc-heads.net/avatar/MHF_Exclamation/64",
            "embeds", List.of(embed)
        ));
    }

    private String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }
}
