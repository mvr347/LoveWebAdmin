package me.lovelace.loveWebAdmin.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.gui.ReportGuiSession;
import me.lovelace.loveWebAdmin.gui.ReportInventoryHolder;
import me.lovelace.loveWebAdmin.managers.ReportManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Обработчик кликов в GUI жалоб, текстового ввода в чат и очистки сессий при выходе.
 */
public class ReportMenuListener implements Listener {

    private final LoveWebAdmin plugin;

    public ReportMenuListener(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ReportInventoryHolder holder)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 36) {
            return;
        }

        ReportGuiSession session = holder.getSession();
        ReportManager rm = plugin.getReportManager();
        OfflinePlayer target = Bukkit.getOfflinePlayer(session.getTargetUuid());

        switch (slot) {
            case 10 -> { // "Текст / Описание"
                session.setAwaitingChatInput(true);
                player.closeInventory();
                player.sendMessage(Component.text("§e§l[Жалоба] §fВведите описание ситуации в чат (или напишите §cотмена§f):"));
            }
            case 12 -> { // Читы
                handleCauseToggle(player, target, session, ReportManager.REASON_CHEATS);
            }
            case 13 -> { // Оскорбление родных
                handleCauseToggle(player, target, session, ReportManager.REASON_FAMILY);
            }
            case 14 -> { // Реклама
                handleCauseToggle(player, target, session, ReportManager.REASON_ADS);
            }
            case 21 -> { // Токсичность
                handleCauseToggle(player, target, session, ReportManager.REASON_TOXIC);
            }
            case 22 -> { // Багоюз
                handleCauseToggle(player, target, session, ReportManager.REASON_BUGS);
            }
            case 23 -> { // Гриферство
                handleCauseToggle(player, target, session, ReportManager.REASON_GRIEF);
            }
            case 16 -> { // Время (< 5 мин)
                session.setRecent(!session.isRecent());
                rm.renderAndOpenGui(player, target, session);
            }
            case 30 -> { // Отмена
                player.closeInventory();
                rm.removeSession(player.getUniqueId());
                player.sendMessage(Component.text("§cПодача жалобы отменена."));
            }
            case 32 -> { // Подтвердить
                if (session.getSelectedCauses().isEmpty()) {
                    player.sendMessage(Component.text("§cВыберите хотя бы одну причину для отправки жалобы!"));
                    return;
                }
                rm.submitReport(player, session);
            }
            default -> {
                // Игнорируем клики по стеклу или информационным элементам
            }
        }
    }

    private void handleCauseToggle(Player player, OfflinePlayer target, ReportGuiSession session, String reason) {
        boolean toggled = session.toggleCause(reason);
        if (!toggled) {
            player.sendMessage(Component.text("§cВы можете выбрать максимум 3 причины! Снимите выбор с другой причины."));
            return;
        }
        plugin.getReportManager().renderAndOpenGui(player, target, session);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ReportManager rm = plugin.getReportManager();
        ReportGuiSession session = rm.getSession(player.getUniqueId());

        if (session != null && session.isAwaitingChatInput()) {
            event.setCancelled(true);
            session.setAwaitingChatInput(false);

            String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            handleChatInput(player, session, text);
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ReportManager rm = plugin.getReportManager();
        ReportGuiSession session = rm.getSession(player.getUniqueId());

        if (session != null && session.isAwaitingChatInput()) {
            event.setCancelled(true);
            session.setAwaitingChatInput(false);

            String text = event.getMessage() != null ? event.getMessage().trim() : "";
            handleChatInput(player, session, text);
        }
    }

    private void handleChatInput(Player player, ReportGuiSession session, String text) {
        if ("отмена".equalsIgnoreCase(text) || "cancel".equalsIgnoreCase(text)) {
            player.sendMessage(Component.text("§cВвод описания отменен."));
        } else {
            session.setDescription(text);
            player.sendMessage(Component.text("§a✔ Описание сохранено: §f\"" + text + "\""));
        }

        // Открываем GUI заново в потоке региона игрока
        OfflinePlayer target = Bukkit.getOfflinePlayer(session.getTargetUuid());
        plugin.getServer().getRegionScheduler().run(plugin, player.getLocation(), task -> {
            plugin.getReportManager().renderAndOpenGui(player, target, session);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Защита от утечек памяти: удаляем сессию вышедшего игрока
        plugin.getReportManager().removeSession(event.getPlayer().getUniqueId());
    }
}
