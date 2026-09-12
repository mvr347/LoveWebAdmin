package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.discord.DiscordEmbed;
import dev.lovelace.lovecore.api.discord.DiscordService;
import dev.lovelace.lovecore.api.discord.TicketType;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.api.events.PlayerReportCreatedEvent;
import me.lovelace.loveWebAdmin.models.PlayerReport;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebBanAppeal;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Интеграция с Discord через единый сервис LoveCore (DiscordService).
 * Обеспечивает:
 * - Создание приватных тикетов Discord для апелляций (ticket-бан-<player>)
 * - Двустороннюю синхронизацию сообщений между Discord и WebAdmin
 * - Уведомления администраторам в ЛС Discord (по настройкам в профиле)
 * - Уведомления о жалобах (Reports) и апелляциях (Tickets)
 */
public class LoveCoreDiscordBridge implements Listener {

    private final LoveWebAdmin plugin;
    private boolean initialized = false;

    public LoveCoreDiscordBridge(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        if (initialized) return;
        initialized = true;

        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        discord().ifPresent(service -> {
            try {
                service.registerTicketMessageListener((ticketId, authorName, isStaff, message) -> {
                    onDiscordTicketMessage(ticketId, authorName, isStaff, message);
                });
                plugin.getLogger().info("[DiscordBridge] Успешно подключен к LoveCore DiscordService!");
            } catch (Exception e) {
                plugin.getLogger().warning("[DiscordBridge] Ошибка регистрации слушателя тикетов: " + e.getMessage());
            }
        });
    }

    public boolean isAvailable() {
        try {
            return LoveCore.service(DiscordService.class).map(DiscordService::isEnabled).orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    public Optional<DiscordService> discord() {
        try {
            return LoveCore.service(DiscordService.class);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private void onDiscordTicketMessage(String ticketId, String authorName, boolean isStaff, String message) {
        try {
            int appealId = -1;
            try {
                appealId = Integer.parseInt(ticketId);
            } catch (NumberFormatException e) {
                Optional<WebBanAppeal> appealOpt = plugin.getDatabaseManager().getAppealByDiscordChannel(ticketId);
                if (appealOpt.isPresent()) {
                    appealId = appealOpt.get().id();
                }
            }

            if (appealId <= 0) {
                Optional<WebBanAppeal> appealOpt = plugin.getDatabaseManager().getAppealById(appealId);
                if (appealOpt.isEmpty()) return;
            }

            plugin.getDatabaseManager().addAppealMessage(appealId, authorName, isStaff, message);
            plugin.getLogger().info("[DiscordBridge] Новое сообщение из тикета Discord #" + appealId + " от " + authorName);
        } catch (Exception e) {
            plugin.getLogger().warning("[DiscordBridge] Ошибка обработки входящего сообщения тикета: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onReportCreated(PlayerReportCreatedEvent event) {
        notifyReportCreated(event.getReport());
    }

    public void notifyReportCreated(PlayerReport report) {
        Optional<DiscordService> serviceOpt = discord();
        if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) return;
        DiscordService service = serviceOpt.get();

        String title = "🚨 Новая жалоба #" + report.id();
        String desc = "Поступила жалоба на игрока **" + report.targetName() + "** от **" + report.reporterName() + "**";
        List<DiscordEmbed.Field> fields = new ArrayList<>();
        fields.add(new DiscordEmbed.Field("Нарушитель", report.targetName(), true));
        fields.add(new DiscordEmbed.Field("Отправитель", report.reporterName(), true));
        fields.add(new DiscordEmbed.Field("Причины", String.join(", ", report.reasons()), false));
        if (report.description() != null && !report.description().isBlank()) {
            fields.add(new DiscordEmbed.Field("Описание", report.description(), false));
        }

        DiscordEmbed embed = new DiscordEmbed(
            title,
            desc,
            0xE74C3C, // Red
            fields,
            "LoveWebAdmin • Жалобы",
            Instant.now()
        );

        // Канал жалоб
        String channelId = plugin.getConfig().getString("discord.reports-channel-id", "");
        if (channelId != null && !channelId.isBlank()) {
            service.sendChannelMessage(channelId, null, embed);
        }

        // Уведомления в ЛС администраторам
        dispatchAdminDirectMessages("discordNotifyReports", embed);
    }

    public CompletableFuture<String> createAppealTicket(WebBanAppeal appeal) {
        Optional<DiscordService> serviceOpt = discord();
        if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        DiscordService service = serviceOpt.get();

        return service.createTicketChannel(
            TicketType.BAN_APPEAL,
            String.valueOf(appeal.id()),
            appeal.playerName(),
            appeal.reason()
        ).thenApply(channelId -> {
            if (channelId != null && !channelId.isBlank()) {
                plugin.getDatabaseManager().setAppealDiscordChannelId(appeal.id(), channelId);

                // Отправляем вступительное сообщение в канал тикета
                List<DiscordEmbed.Field> fields = new ArrayList<>();
                fields.add(new DiscordEmbed.Field("Игрок", appeal.playerName(), true));
                fields.add(new DiscordEmbed.Field("Бан ID", "#" + appeal.banId(), true));
                fields.add(new DiscordEmbed.Field("Причина апелляции", appeal.reason(), false));

                DiscordEmbed embed = new DiscordEmbed(
                    "⚖ Апелляция бана #" + appeal.id(),
                    "Канал тикета для обсуждения снятия блокировки игрока **" + appeal.playerName() + "**.\nВсе сообщения синхронизируются с панелью управления WebAdmin.",
                    0xF1C40F, // Gold
                    fields,
                    "LoveWebAdmin • Тикеты",
                    Instant.now()
                );
                service.sendChannelMessage(channelId, "Здравствуйте, @" + appeal.playerName() + "! Ваша апелляция принята к рассмотрению персоналом сервера.", embed);
            }
            // Оповещаем администраторов
            notifyAppealCreated(appeal);
            return channelId;
        });
    }

    public void notifyAppealCreated(WebBanAppeal appeal) {
        Optional<DiscordService> serviceOpt = discord();
        if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) return;
        DiscordService service = serviceOpt.get();

        String title = "⚖ Новая апелляция бана #" + appeal.id();
        String desc = "Игрок **" + appeal.playerName() + "** подал апелляцию на снятие бана #" + appeal.banId();
        List<DiscordEmbed.Field> fields = List.of(
            new DiscordEmbed.Field("Игрок", appeal.playerName(), true),
            new DiscordEmbed.Field("Бан ID", "#" + appeal.banId(), true),
            new DiscordEmbed.Field("Аргумент", appeal.reason(), false)
        );

        DiscordEmbed embed = new DiscordEmbed(
            title,
            desc,
            0x3498DB, // Blue
            fields,
            "LoveWebAdmin • Апелляции",
            Instant.now()
        );

        // Канал апелляций если настроен
        String channelId = plugin.getConfig().getString("discord.appeals-channel-id", "");
        if (channelId != null && !channelId.isBlank()) {
            service.sendChannelMessage(channelId, null, embed);
        }

        // В ЛС администраторам
        dispatchAdminDirectMessages("discordNotifyTickets", embed);
    }

    public void sendTicketReply(WebBanAppeal appeal, String authorName, boolean isStaff, String message) {
        Optional<DiscordService> serviceOpt = discord();
        if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) return;

        String channelId = appeal.discordChannelId();
        if (channelId != null && !channelId.isBlank()) {
            String roleTag = isStaff ? "🛡 [Персонал]" : "👤 [Игрок]";
            String formatted = "**" + roleTag + " " + authorName + "**: " + message;
            serviceOpt.get().sendChannelMessage(channelId, formatted, null);
        }
    }

    public void closeAppealTicket(WebBanAppeal appeal, String reason) {
        Optional<DiscordService> serviceOpt = discord();
        if (serviceOpt.isEmpty() || !serviceOpt.get().isEnabled()) return;

        String channelId = appeal.discordChannelId();
        if (channelId != null && !channelId.isBlank()) {
            serviceOpt.get().closeTicketChannel(channelId, reason);
        }
    }

    private void dispatchAdminDirectMessages(String notifyPrefKey, DiscordEmbed embed) {
        Optional<DiscordService> serviceOpt = discord();
        if (serviceOpt.isEmpty()) return;
        DiscordService service = serviceOpt.get();

        for (WebAdmin admin : plugin.getDatabaseManager().getAllAdmins()) {
            try {
                String prefsJson = admin.uiPreferences();
                if (prefsJson == null || prefsJson.isBlank() || "{}".equals(prefsJson)) continue;

                Map<String, Object> prefs = JsonUtils.parseObject(prefsJson);
                Object notifyObj = prefs.get(notifyPrefKey);
                boolean notify = false;
                if (notifyObj instanceof Boolean b) {
                    notify = b;
                } else if (notifyObj != null) {
                    notify = Boolean.parseBoolean(String.valueOf(notifyObj));
                }

                if (!notify) continue;

                Object discordIdObj = prefs.get("discordId");
                if (discordIdObj != null) {
                    String discordId = String.valueOf(discordIdObj).trim();
                    if (!discordId.isBlank() && !discordId.equalsIgnoreCase("null")) {
                        service.sendDirectMessage(discordId, null, embed);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }
}
