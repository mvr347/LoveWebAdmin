package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.gui.ReportGuiSession;
import me.lovelace.loveWebAdmin.gui.ReportInventoryHolder;
import me.lovelace.loveWebAdmin.integration.LoveBehaviorBridge;
import me.lovelace.loveWebAdmin.models.PlayerReport;
import me.lovelace.loveWebAdmin.utils.HeadUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Менеджер жалоб (репортов), GUI сессий и интеграции с LoveBehavior.
 */
public class ReportManager {

    public static final String HEAD_TEXT = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTRhNTkxNGQ2YzM0ZjRjMTc3NDk5MjkxNmQ0OWZmNDM3YWNiYWE5ZmQ5ZDY3YWE1MDM0Njg3YTNmNjkwMmNmYyJ9fX0=";
    public static final String HEAD_CHEATS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDY0YWM5M2MyMTQ2MmNiMmYyNzA2NGZmZGM5MjIzNWY2YzA3YjZlNDk4ODhiNzEzZjAzNWRkY2Q1ZjRhY2Y0In19fQ==";
    public static final String HEAD_FAMILY = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTA4ZWU5Yzg0OGFkOTc5MDcwODU4OTQ4OTBiZGVmZTAzY2E2YmY0N2E2ZGVmZmVlYWI1N2QxYjc1ZmJmODkxIn19fQ==";
    public static final String HEAD_ADS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjg0ZTNmOTFhNjcyZTFiYjc5Njc1NWQ5M2Y5ZjE3YTVhYzg2MTVkMmJkMmUxMzc4Y2VkOWRjY2ExZTIwMjIifX19";
    public static final String HEAD_TOXIC = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGU4Y2VmMjQ1YWU0MjhmYTkyMzg4ZGMzNmE1NDljNTliNGE2ZGE4ZGFhNGFmYWQ3YjViZWVmY2IxY2M4Y2Y2In19fQ==";
    public static final String HEAD_BUGS = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZWQ3YmFhZjNlM2YwZjkyMGU1NDMzMjdiMmJiZjhlMmJmNTI1ZTQ1NDFmYmQ2Y2Q0ODk4MGRkMTE0NTNhYjMifX19";
    public static final String HEAD_GRIEF = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjI1OTliZDljMDg3OTE3OGQ3NmYyOGRhMmFhYjczY2ZlZTkzZDAyZDA5NTQ2MmVkMmVkM2U1MTVlMTk5YjIifX19";
    public static final String HEAD_CLOCK = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTljZGI5YWYzOGNmNDFkYWE1M2JjOGNkYTc2NjVjNTA5NjMyZDE0Zzk3OGYwYWMyOTA2MTgzMmY0MmNmNTI2YyJ9fX0=";
    public static final String HEAD_INFO = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmFkYjA0OGEyYzU1OWFkNTM3ZmFIY2I0MjEyOGI1OTZhNTExNmM1ODc0MmE3Zjc1YjkyN2RiMTgzNDlmNzIifX19";
    public static final String HEAD_CONFIRM = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTkyZTMxZmZiNTg3NzAxNjkwNzg5OTQ5NTBlZmMyNWMyMjk4NmQxNTM1OTgyMzZkNzM5N2MzYzdlNmVkN2M0InV9fX0=";
    public static final String HEAD_CANCEL = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmViNTg4YjIxYTZmOThhZDFmZjRlNzAzNzQ3ODkzODcxNDA5MWU1NmU3MmVhOWUxNmQ1NTkwNDE3NCJ9fX0=";
    public static final String HEAD_DISABLED = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvM2VkMWFiYTczZjRmNGVjNDE0ZTcxMmFiZDhlMVk5MzhjNTQ0YmQyN2IxZjgzODc3MzJlNjBiMTE4ODAyIn19fQ==";

    public static final String REASON_CHEATS = "Читы / Запрещенный софт";
    public static final String REASON_FAMILY = "Оскорбление родных / Близких";
    public static final String REASON_ADS = "Реклама / Спам";
    public static final String REASON_TOXIC = "Токсичность / Оскорбления";
    public static final String REASON_BUGS = "Багоюз / Дюпы";
    public static final String REASON_GRIEF = "Гриферство / Помеха игре";

    public static final int REPUTATION_DEDUCTION = 25;
    public static final int REPUTATION_BONUS_REPORTER = 35;

    private final LoveWebAdmin plugin;
    private final LoveBehaviorBridge behaviorBridge;
    private final Map<UUID, ReportGuiSession> activeSessions = new ConcurrentHashMap<>();

    public ReportManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
        this.behaviorBridge = new LoveBehaviorBridge();
        startAutoExpiryTask();
    }

    public ReportGuiSession getSession(UUID playerUuid) {
        return activeSessions.get(playerUuid);
    }

    public void removeSession(UUID playerUuid) {
        activeSessions.remove(playerUuid);
    }

    /**
     * Запуск проверки кулдауна и открытие GUI меню жалобы.
     */
    public void startReportFlow(Player reporter, OfflinePlayer target) {
        if (reporter.getUniqueId().equals(target.getUniqueId())) {
            reporter.sendMessage(Component.text("§cВы не можете отправить жалобу на самого себя!"));
            return;
        }

        String ip = (reporter.getAddress() != null && reporter.getAddress().getAddress() != null)
            ? reporter.getAddress().getAddress().getHostAddress()
            : "";

        // Асинхронная проверка кулдауна в БД (1 раз в неделю на конкретного игрока по UUID и IP)
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            boolean canReport = plugin.getDatabaseManager().canPlayerReportTarget(
                reporter.getUniqueId().toString(),
                ip,
                target.getUniqueId().toString()
            );

            if (!canReport) {
                plugin.getServer().getRegionScheduler().run(plugin, reporter.getLocation(), t -> {
                    reporter.sendMessage(Component.text("§cВы уже отправляли жалобу на игрока §e" + target.getName() + " §cна этой неделе!"));
                });
                return;
            }

            // Создаем или обновляем сессию
            ReportGuiSession session = new ReportGuiSession(
                reporter.getUniqueId(),
                reporter.getName(),
                ip,
                target.getUniqueId(),
                target.getName() != null ? target.getName() : "Unknown"
            );
            activeSessions.put(reporter.getUniqueId(), session);

            plugin.getServer().getRegionScheduler().run(plugin, reporter.getLocation(), t -> {
                renderAndOpenGui(reporter, target, session);
            });
        });
    }

    /**
     * Отрисовывает и открывает 36-слотовое GUI меню по стандарту gui-gen-5.
     */
    public void renderAndOpenGui(Player reporter, OfflinePlayer target, ReportGuiSession session) {
        ReportInventoryHolder holder = new ReportInventoryHolder(session);
        String targetName = session.getTargetName();
        Inventory inv = Bukkit.createInventory(holder, 36, Component.text("Жалоба: " + targetName));
        holder.setInventory(inv);

        // Стандарт gui-gen-5:
        // Header (0-8) и Footer (27-35) - GRAY_STAINED_GLASS_PANE.
        // Рабочая зона (9-26) - НИКАКОГО СТЕКЛА, только кнопки контента!
        ItemStack borderPane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta paneMeta = borderPane.getItemMeta();
        if (paneMeta != null) {
            paneMeta.displayName(Component.text(" "));
            borderPane.setItemMeta(paneMeta);
        }

        // Header
        for (int i = 0; i <= 8; i++) {
            inv.setItem(i, borderPane.clone());
        }
        // Footer
        for (int i = 27; i <= 35; i++) {
            inv.setItem(i, borderPane.clone());
        }

        // Кнопка 0 (Header): Голова цели
        boolean isOnline = target.isOnline();
        List<String> targetLore = new ArrayList<>();
        targetLore.add("&7Нарушитель: &f" + targetName);
        targetLore.add("&7Статус: " + (isOnline ? "&aВ сети" : "&7Не в сети"));
        targetLore.add("&7UUID: &8" + session.getTargetUuid().toString().substring(0, 8) + "...");
        inv.setItem(0, HeadUtils.createPlayerHead(target, "&6&lНарушитель: &e" + targetName, targetLore));

        // Кнопка 4 (Header): Инструкция
        List<String> infoLore = List.of(
            "&7Заполните форму для отправки жалобы:",
            "&f1. Нажмите &e'Текст'&f для описания инцидента",
            "&f2. Выберите &eдо 3-х причин&f нарушения",
            "&f3. Укажите, случилось ли это за &e< 5 мин",
            "&f4. Нажмите &a'Подтвердить'&f внизу"
        );
        inv.setItem(4, HeadUtils.createCustomHead(HEAD_INFO, "&e&lИнформация о жалобе", infoLore));

        // Рабочая зона:
        // Слот 10: "Текст / Описание"
        List<String> textLore = new ArrayList<>();
        textLore.add("&7Текущее описание:");
        if (session.getDescription() != null && !session.getDescription().isBlank()) {
            textLore.add("&f\"" + session.getDescription() + "\"");
            textLore.add("");
            textLore.add("&e▶ Нажмите, чтобы изменить описание");
        } else {
            textLore.add("&8не указано");
            textLore.add("");
            textLore.add("&e▶ Нажмите, чтобы ввести описание в чат");
        }
        inv.setItem(10, HeadUtils.createCustomHead(HEAD_TEXT, "&b&lТекст / Описание", textLore));

        // Кнопки причин (Слоты 12, 13, 14, 21, 22, 23)
        inv.setItem(12, createReasonItem(HEAD_CHEATS, REASON_CHEATS, session));
        inv.setItem(13, createReasonItem(HEAD_FAMILY, REASON_FAMILY, session));
        inv.setItem(14, createReasonItem(HEAD_ADS, REASON_ADS, session));
        inv.setItem(21, createReasonItem(HEAD_TOXIC, REASON_TOXIC, session));
        inv.setItem(22, createReasonItem(HEAD_BUGS, REASON_BUGS, session));
        inv.setItem(23, createReasonItem(HEAD_GRIEF, REASON_GRIEF, session));

        // Слот 16: Кнопка "Случилось < 5 минут назад" (Toggle)
        List<String> clockLore = new ArrayList<>();
        clockLore.add("&7Инцидент произошел недавно?");
        clockLore.add(session.isRecent() ? " &a✔ Да (< 5 минут назад)" : " &c✖ Нет (Более 5 минут назад)");
        clockLore.add("");
        clockLore.add("&e▶ Нажмите для переключения");
        inv.setItem(16, HeadUtils.createCustomHead(HEAD_CLOCK, "&6&lВремя нарушения", clockLore));

        // Footer:
        // Слот 30: "Отмена"
        List<String> cancelLore = List.of(
            "&7Закрыть окно подачи жалобы",
            "",
            "&c▶ Нажмите для отмены"
        );
        inv.setItem(30, HeadUtils.createCustomHead(HEAD_CANCEL, "&c&lОтменить", cancelLore));

        // Слот 32: "Подтвердить" (активна ТОЛЬКО если выбрана хотя бы одна причина!)
        boolean hasReasons = !session.getSelectedCauses().isEmpty();
        List<String> confirmLore = new ArrayList<>();
        if (hasReasons) {
            confirmLore.add("&7Выбрано причин: &a" + session.getSelectedCauses().size() + "/3");
            confirmLore.add("&7Описание: " + (session.getDescription() != null ? "&aУказано" : "&8не указано"));
            confirmLore.add("&7Свежий инцидент: " + (session.isRecent() ? "&aДа" : "&7Нет"));
            confirmLore.add("");
            confirmLore.add("&a▶ Нажмите для отправки жалобы модераторам");
            inv.setItem(32, HeadUtils.createCustomHead(HEAD_CONFIRM, "&a&lПодтвердить жалобу", confirmLore));
        } else {
            confirmLore.add("&cВыберите хотя бы одну причину,");
            confirmLore.add("&cчтобы активировать отправку!");
            inv.setItem(32, HeadUtils.createCustomHead(HEAD_DISABLED, "&8&lПодтвердить (Неактивно)", confirmLore));
        }

        reporter.openInventory(inv);
    }

    private ItemStack createReasonItem(String headBase64, String reasonName, ReportGuiSession session) {
        boolean selected = session.getSelectedCauses().contains(reasonName);
        boolean atMax = session.getSelectedCauses().size() >= 3;

        List<String> lore = new ArrayList<>();
        if (selected) {
            lore.add("&a[ ВЫБРАНО ]");
            lore.add("&7Нажмите, чтобы отменить выбор.");
        } else if (atMax) {
            lore.add("&8[ Недоступно ]");
            lore.add("&cВыбрано максимальное число причин (3).");
            lore.add("&7Снимите выбор с другой причины.");
        } else {
            lore.add("&7Нажмите, чтобы выбрать эту причину.");
        }

        String displayName = selected ? "&a✔ " + reasonName : "&f" + reasonName;
        return HeadUtils.createCustomHead(headBase64, displayName, lore);
    }

    /**
     * Обработка отправки жалобы (клик на Подтвердить).
     */
    public void submitReport(Player reporter, ReportGuiSession session) {
        if (session.getSelectedCauses().isEmpty()) {
            reporter.sendMessage(Component.text("§cВыберите хотя бы одну причину жалобы!"));
            return;
        }

        reporter.closeInventory();
        removeSession(reporter.getUniqueId());

        List<String> reasons = new ArrayList<>(session.getSelectedCauses());
        String description = session.getDescription();
        boolean isRecent = session.isRecent();
        UUID targetUuid = session.getTargetUuid();
        String targetName = session.getTargetName();

        reporter.sendMessage(Component.text("§7[Жалоба] Отправка данных администрации..."));

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            // 1. Создаем запись в БД
            int reportId = plugin.getDatabaseManager().createPlayerReport(
                session.getReporterUuid().toString(),
                session.getReporterName(),
                session.getReporterIp(),
                targetUuid.toString(),
                targetName,
                reasons,
                description,
                isRecent,
                REPUTATION_DEDUCTION
            );

            if (reportId > 0) {
                // 2. Слегка опускаем репутацию цели в LoveBehavior
                if (behaviorBridge.isAvailable()) {
                    behaviorBridge.modifyPoliteness(targetUuid, -REPUTATION_DEDUCTION);
                }

                // 3. Проверяем, не набрал ли нарушитель > 4 репортов (подозрительный)
                int activeCount = plugin.getDatabaseManager().getActiveReportsCountForTarget(targetUuid.toString());
                if (activeCount > 4) {
                    plugin.getLogger().warning("[LoveWebAdmin] Игрок " + targetName + " помечен как ПОДОЗРИТЕЛЬНЫЙ (" + activeCount + " жалоб)");
                }

                // 4. Вызываем Bukkit Event и отправляем вебхук
                Optional<PlayerReport> createdOpt = plugin.getDatabaseManager().getReportById(reportId);
                if (createdOpt.isPresent()) {
                    PlayerReport createdReport = createdOpt.get();
                    try {
                        var reportEvent = new me.lovelace.loveWebAdmin.api.events.PlayerReportCreatedEvent(createdReport);
                        Bukkit.getPluginManager().callEvent(reportEvent);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[ReportManager] Ошибка вызова PlayerReportCreatedEvent: " + e.getMessage());
                    }

                    if (plugin.getWebhookManager() != null) {
                        Map<String, Object> hookData = new java.util.LinkedHashMap<>();
                        hookData.put("reportId", reportId);
                        hookData.put("reporterName", session.getReporterName());
                        hookData.put("targetName", targetName);
                        hookData.put("reasons", reasons);
                        hookData.put("description", description != null ? description : "");
                        hookData.put("isRecent", isRecent);
                        plugin.getWebhookManager().dispatch("REPORT", hookData);
                    }
                }

                plugin.getServer().getRegionScheduler().run(plugin, reporter.getLocation(), t -> {
                    reporter.sendMessage(Component.text("§a✔ Ваша жалоба на игрока §e" + targetName + " §aуспешно отправлена модераторам сервера!"));
                    reporter.sendMessage(Component.text("§7Мы рассмотрим её в ближайшее время. Спасибо за помощь проекту!"));
                });
            } else {
                plugin.getServer().getRegionScheduler().run(plugin, reporter.getLocation(), t -> {
                    reporter.sendMessage(Component.text("§cОшибка отправки жалобы. Пожалуйста, попробуйте позже."));
                });
            }
        });
    }

    /**
     * Программное создание жалобы (API).
     */
    public PlayerReport createReport(String reporter, String target, List<String> reasons, String description, boolean isRecent) {
        OfflinePlayer reporterPlayer = Bukkit.getOfflinePlayer(reporter);
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(target);
        String reporterUuid = (reporterPlayer.getUniqueId() != null) ? reporterPlayer.getUniqueId().toString() : UUID.nameUUIDFromBytes(reporter.getBytes()).toString();
        String targetUuid = (targetPlayer.getUniqueId() != null) ? targetPlayer.getUniqueId().toString() : UUID.nameUUIDFromBytes(target.getBytes()).toString();

        int reportId = plugin.getDatabaseManager().createPlayerReport(
            reporterUuid,
            reporter,
            "",
            targetUuid,
            target,
            reasons != null ? reasons : List.of(),
            description != null ? description : "",
            isRecent,
            REPUTATION_DEDUCTION
        );

        if (reportId <= 0) return null;

        if (behaviorBridge.isAvailable()) {
            try {
                behaviorBridge.modifyPoliteness(UUID.fromString(targetUuid), -REPUTATION_DEDUCTION);
            } catch (Exception ignored) {}
        }

        Optional<PlayerReport> reportOpt = plugin.getDatabaseManager().getReportById(reportId);
        if (reportOpt.isPresent()) {
            PlayerReport r = reportOpt.get();
            try {
                var reportEvent = new me.lovelace.loveWebAdmin.api.events.PlayerReportCreatedEvent(r);
                Bukkit.getPluginManager().callEvent(reportEvent);
            } catch (Exception e) {
                plugin.getLogger().warning("[ReportManager] Ошибка вызова PlayerReportCreatedEvent: " + e.getMessage());
            }

            if (plugin.getWebhookManager() != null) {
                Map<String, Object> hookData = new java.util.LinkedHashMap<>();
                hookData.put("reportId", reportId);
                hookData.put("reporterName", reporter);
                hookData.put("targetName", target);
                hookData.put("reasons", reasons);
                hookData.put("description", description != null ? description : "");
                hookData.put("isRecent", isRecent);
                plugin.getWebhookManager().dispatch("REPORT", hookData);
            }
            return r;
        }
        return null;
    }

    /**
     * Получить все активные (ожидающие рассмотрения) жалобы.
     */
    public List<PlayerReport> getPendingReports() {
        return plugin.getDatabaseManager().getAllReports("PENDING", null, 100, 0);
    }

    /**
     * Получить все активные жалобы на конкретного игрока.
     */
    public List<PlayerReport> getPendingReportsForTarget(String target) {
        return plugin.getDatabaseManager().getAllReports("PENDING", target, 50, 0);
    }

    /**
     * Программное разрешение жалобы (API).
     */
    public boolean resolveReport(int reportId, String status, String resolvedBy, Integer linkedBanId) {
        if ("ACCEPTED".equalsIgnoreCase(status)) {
            return acceptReport(reportId, resolvedBy, linkedBanId);
        } else if ("REJECTED".equalsIgnoreCase(status)) {
            return rejectReport(reportId, resolvedBy);
        } else {
            return plugin.getDatabaseManager().resolveReport(reportId, status, resolvedBy, linkedBanId);
        }
    }

    /**
     * Администратор принял жалобу и наказал нарушителя (ACCEPTED), опционально привязав выданный бан.
     */
    public boolean acceptReport(int reportId, String adminName, Integer linkedBanId) {
        Optional<PlayerReport> reportOpt = plugin.getDatabaseManager().getReportById(reportId);
        if (reportOpt.isEmpty()) return false;
        PlayerReport report = reportOpt.get();

        boolean resolved = plugin.getDatabaseManager().resolveReport(reportId, "ACCEPTED", adminName, linkedBanId);
        if (!resolved) return false;

        UUID reporterUuid = UUID.fromString(report.reporterUuid());

        // Награждаем репортера небольшой репутацией
        if (behaviorBridge.isAvailable()) {
            behaviorBridge.modifyPoliteness(reporterUuid, REPUTATION_BONUS_REPORTER);
        }

        // Оповещаем репортера
        String message = "§a✔ Ваша жалоба была рассмотрена. Наказание принято";
        Player reporterPlayer = Bukkit.getPlayer(reporterUuid);
        if (reporterPlayer != null && reporterPlayer.isOnline()) {
            reporterPlayer.sendMessage(Component.text(message));
            reporterPlayer.showTitle(Title.title(
                Component.text("Жалоба рассмотрена", NamedTextColor.GREEN),
                Component.text("Наказание принято", NamedTextColor.WHITE),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(800))
            ));
        } else {
            plugin.getDatabaseManager().queueReportNotification(report.reporterUuid(), message);
        }

        String banNote = (linkedBanId != null && linkedBanId > 0) ? " (выдан бан #" + linkedBanId + ")" : "";
        plugin.getLogManager().logWebAction(adminName, "Принял жалобу #" + reportId + " на " + report.targetName() + " (от " + report.reporterName() + ")" + banNote);

        // Событие и вебхук
        try {
            var resolvedEvent = new me.lovelace.loveWebAdmin.api.events.PlayerReportResolvedEvent(reportId, "ACCEPTED", adminName, linkedBanId);
            Bukkit.getPluginManager().callEvent(resolvedEvent);
        } catch (Exception e) {
            plugin.getLogger().warning("[ReportManager] Ошибка вызова PlayerReportResolvedEvent: " + e.getMessage());
        }

        if (plugin.getWebhookManager() != null) {
            Map<String, Object> hookData = new java.util.LinkedHashMap<>();
            hookData.put("reportId", reportId);
            hookData.put("targetName", report.targetName());
            hookData.put("reporterName", report.reporterName());
            hookData.put("status", "ACCEPTED");
            hookData.put("resolvedBy", adminName);
            hookData.put("linkedBanId", linkedBanId);
            plugin.getWebhookManager().dispatch("REPORT_RESOLVED", hookData);
        }

        return true;
    }

    public boolean acceptReport(int reportId, String adminName) {
        return acceptReport(reportId, adminName, null);
    }

    /**
     * Администратор пометил жалобу как ложную (REJECTED).
     */
    public boolean rejectReport(int reportId, String adminName) {
        Optional<PlayerReport> reportOpt = plugin.getDatabaseManager().getReportById(reportId);
        if (reportOpt.isEmpty()) return false;
        PlayerReport report = reportOpt.get();

        boolean resolved = plugin.getDatabaseManager().resolveReport(reportId, "REJECTED", adminName);
        if (!resolved) return false;

        // Возвращаем цели снятую ранее репутацию
        UUID targetUuid = UUID.fromString(report.targetUuid());
        if (behaviorBridge.isAvailable() && report.reputationDeducted() > 0) {
            behaviorBridge.modifyPoliteness(targetUuid, report.reputationDeducted());
        }

        // Оповещаем репортера с формулировкой из ТЗ: "Ваша жалоба была рассмотрена. Притензий не выявлено"
        UUID reporterUuid = UUID.fromString(report.reporterUuid());
        String message = "§eℹ Ваша жалоба была рассмотрена. Притензий не выявлено";
        Player reporterPlayer = Bukkit.getPlayer(reporterUuid);
        if (reporterPlayer != null && reporterPlayer.isOnline()) {
            reporterPlayer.sendMessage(Component.text(message));
        } else {
            plugin.getDatabaseManager().queueReportNotification(report.reporterUuid(), message);
        }

        plugin.getLogManager().logWebAction(adminName, "Отклонил жалобу #" + reportId + " как ложную (цель: " + report.targetName() + ")");

        // Событие и вебхук
        try {
            var resolvedEvent = new me.lovelace.loveWebAdmin.api.events.PlayerReportResolvedEvent(reportId, "REJECTED", adminName, null);
            Bukkit.getPluginManager().callEvent(resolvedEvent);
        } catch (Exception e) {
            plugin.getLogger().warning("[ReportManager] Ошибка вызова PlayerReportResolvedEvent: " + e.getMessage());
        }

        if (plugin.getWebhookManager() != null) {
            Map<String, Object> hookData = new java.util.LinkedHashMap<>();
            hookData.put("reportId", reportId);
            hookData.put("targetName", report.targetName());
            hookData.put("reporterName", report.reporterName());
            hookData.put("status", "REJECTED");
            hookData.put("resolvedBy", adminName);
            plugin.getWebhookManager().dispatch("REPORT_RESOLVED", hookData);
        }

        return true;
    }

    /**
     * Удалить жалобу.
     */
    public boolean deleteReport(int reportId, String adminName) {
        Optional<PlayerReport> reportOpt = plugin.getDatabaseManager().getReportById(reportId);
        if (reportOpt.isEmpty()) return false;
        PlayerReport report = reportOpt.get();

        // Если жалоба была PENDING, возвращаем цели репутацию при удалении
        if ("PENDING".equalsIgnoreCase(report.status()) && report.reputationDeducted() > 0) {
            UUID targetUuid = UUID.fromString(report.targetUuid());
            if (behaviorBridge.isAvailable()) {
                behaviorBridge.modifyPoliteness(targetUuid, report.reputationDeducted());
            }
        }

        boolean deleted = plugin.getDatabaseManager().deleteReport(reportId);
        if (deleted) {
            plugin.getLogManager().logWebAction(adminName, "Удалил жалобу #" + reportId + " (цель: " + report.targetName() + ")");
        }
        return deleted;
    }

    /**
     * Автоматическое снятие репортов через 1 неделю (7 дней) и возврат репутации.
     */
    private void startAutoExpiryTask() {
        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            try {
                long weekAgo = (System.currentTimeMillis() / 1000L) - (7L * 86400L);
                List<PlayerReport> expired = plugin.getDatabaseManager().getExpiredActiveReports(weekAgo);
                for (PlayerReport r : expired) {
                    plugin.getDatabaseManager().resolveReport(r.id(), "EXPIRED", "SYSTEM");
                    if (behaviorBridge.isAvailable() && r.reputationDeducted() > 0) {
                        try {
                            behaviorBridge.modifyPoliteness(UUID.fromString(r.targetUuid()), r.reputationDeducted());
                        } catch (Exception e) {
                            plugin.getLogger().warning("[LoveWebAdmin] Ошибка восстановления репутации для " + r.targetName() + ": " + e.getMessage());
                        }
                    }
                    plugin.getLogger().info("[LoveWebAdmin] Репорт #" + r.id() + " на игрока " + r.targetName() + " автоматически истек через неделю. Репутация возвращена.");
                }

                // Очистка зависших сессий (> 10 минут неактивности)
                long now = System.currentTimeMillis();
                activeSessions.entrySet().removeIf(entry -> now - entry.getValue().getLastActiveTime() > 600_000L);

            } catch (Exception e) {
                plugin.getLogger().warning("[LoveWebAdmin] Ошибка в таймере автоснятия репортов: " + e.getMessage());
            }
        }, 1, 60, TimeUnit.MINUTES);
    }
}
