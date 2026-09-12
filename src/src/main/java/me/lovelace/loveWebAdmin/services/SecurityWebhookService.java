package me.lovelace.loveWebAdmin.services;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Асинхронный сервис отправки тревожных уведомлений безопасности в Discord и Telegram.
 */
public class SecurityWebhookService {

    private final LoveWebAdmin plugin;
    private final HttpClient httpClient;

    public SecurityWebhookService(LoveWebAdmin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public void notifyLogin(String username, String ip, boolean isNewIp) {
        sendLoginAlert(username, ip, null, isNewIp);
    }

    public void sendLoginAlert(String username, String ip, String userAgent, boolean isNewIp) {
        if (!isNewIp && !plugin.getConfig().getBoolean("security.webhooks.notify-on.all-logins", false)) {
            return;
        }

        String title = isNewIp ? "⚠️ Вход администратора с нового IP" : "🔐 Успешный вход в WebAdmin";
        String ua = (userAgent != null && !userAgent.isBlank()) ? userAgent : "Неизвестный браузер";
        String desc = "**Сотрудник:** `" + username + "`\n**IP-адрес:** `" + ip + "`\n**Устройство:** `" + ua + "`\n**Время:** " + Instant.now();
        int color = isNewIp ? 0xf59e0b : 0x10b981; // Оранжевый или зеленый

        sendAlert(title, desc, color);
    }

    public void notifyBruteForce(String ip, String username, int attempts) {
        String title = "🚨 Защита: Блокировка перебора паролей (Brute-Force)";
        String desc = "**IP-адрес:** `" + ip + "`\n**Атакованный аккаунт:** `" + username + "`\n**Неудачных попыток:** " + attempts;
        sendAlert(title, desc, 0xef4444); // Красный
    }

    public void sendBruteForceAlert(String ip, String username, int attempts) {
        notifyBruteForce(ip, username, attempts);
    }

    public void notifyCandidateRegistered(String username) {
        sendRegistrationAlert(username, "Модератор");
    }

    public void sendRegistrationAlert(String username, String role) {
        String title = "👤 Новая заявка на регистрацию в WebAdmin";
        String desc = "**Кандидат:** `" + username + "`\n**Желаемая роль:** `" + role + "`\nТребуется подтверждение Управляющего в панели управления.";
        sendAlert(title, desc, 0x8b5cf6); // Фиолетовый
    }

    public void notifyCommandExecuted(String actor, String command) {
        if (!plugin.getConfig().getBoolean("security.webhooks.notify-on.commands", true)) return;

        String title = "⌨️ Выполнение команды из веб-консоли";
        String desc = "**Администратор:** `" + actor + "`\n**Команда:** `/" + command + "`";
        sendAlert(title, desc, 0x06b6d4); // Голубой
    }

    public void notifyDestructiveAction(String actor, String actionDetails) {
        String title = "⚡ Деструктивное действие в WebAdmin";
        String desc = "**Администратор:** `" + actor + "`\n**Действие:** " + actionDetails;
        sendAlert(title, desc, 0xdc2626); // Темно-красный
    }

    private void sendAlert(String title, String description, int colorHex) {
        boolean enabled = plugin.getConfig().getBoolean("security.webhooks.enabled", false);
        if (!enabled) return;

        String discordUrl = plugin.getConfig().getString("security.webhooks.discord-url");
        String tgToken = plugin.getConfig().getString("security.webhooks.telegram.bot-token");
        String tgChatId = plugin.getConfig().getString("security.webhooks.telegram.chat-id");

        if (discordUrl != null && !discordUrl.isBlank() && discordUrl.startsWith("http")) {
            sendDiscord(discordUrl, title, description, colorHex);
        }

        if (tgToken != null && !tgToken.isBlank() && tgChatId != null && !tgChatId.isBlank()) {
            sendTelegram(tgToken, tgChatId, title, description);
        }
    }

    private void sendDiscord(String webhookUrl, String title, String description, int colorHex) {
        try {
            Map<String, Object> embed = Map.of(
                "title", title,
                "description", description,
                "color", colorHex,
                "timestamp", Instant.now().toString(),
                "footer", Map.of("text", "LoveWebAdmin Security")
            );

            Map<String, Object> payload = Map.of(
                "username", "WebAdmin Security",
                "embeds", List.of(embed)
            );

            String json = JsonUtils.toJson(payload);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Ошибка отправки Discord вебхука: " + ex.getMessage());
                    return null;
                });
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка отправки Discord вебхука: " + e.getMessage());
        }
    }

    private void sendTelegram(String botToken, String chatId, String title, String description) {
        try {
            String text = "*" + escapeTg(title) + "*\n\n" + description;
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage?chat_id=" +
                URLEncoder.encode(chatId, StandardCharsets.UTF_8) +
                "&parse_mode=Markdown&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Ошибка отправки Telegram уведомления: " + ex.getMessage());
                    return null;
                });
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка отправки Telegram уведомления: " + e.getMessage());
        }
    }

    private String escapeTg(String s) {
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[");
    }
}
