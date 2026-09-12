package me.lovelace.loveWebAdmin.api;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.PlayerReport;
import me.lovelace.loveWebAdmin.models.WebBan;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Внутренняя реализация LoveWebAdminAPI.
 */
public class LoveWebAdminAPIImpl implements LoveWebAdminAPI {

    private final LoveWebAdmin plugin;

    public LoveWebAdminAPIImpl(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isPlayerBanned(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        return plugin.getDatabaseManager().isPlayerBanned(playerName.trim());
    }

    @Override
    public Optional<WebBan> getActiveBan(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        return plugin.getDatabaseManager().getActiveBan(playerName.trim());
    }

    @Override
    public WebBan banPlayer(String target, String reason, String creator, String description, String proofUrl, Integer linkedReportId) {
        return plugin.getBanManager().finalizeBan(
            0,
            target,
            reason,
            creator,
            description,
            proofUrl != null ? List.of(proofUrl) : List.of(),
            false,
            linkedReportId
        );
    }

    @Override
    public boolean unbanPlayer(String target, String adminName) {
        Optional<WebBan> banOpt = plugin.getDatabaseManager().getActiveBan(target);
        if (banOpt.isEmpty()) return false;

        var ban = banOpt.get();
        return plugin.getBanManager().unban(ban.id(), adminName);
    }

    @Override
    public List<WebBan> getActiveBans() {
        return plugin.getDatabaseManager().getAllBans("ACTIVE");
    }

    @Override
    public PlayerReport createReport(String reporter, String target, List<String> reasons, String description, boolean isRecent) {
        return plugin.getReportManager().createReport(reporter, target, reasons, description, isRecent);
    }

    @Override
    public List<PlayerReport> getPendingReports() {
        return plugin.getReportManager().getPendingReports();
    }

    @Override
    public List<PlayerReport> getPendingReportsForTarget(String target) {
        return plugin.getReportManager().getPendingReportsForTarget(target);
    }

    @Override
    public boolean resolveReport(int reportId, String status, String resolvedBy, Integer linkedBanId) {
        return plugin.getReportManager().resolveReport(reportId, status, resolvedBy, linkedBanId);
    }

    @Override
    public boolean isPlayerFrozen(UUID playerUuid) {
        return plugin.getFreezeManager() != null && plugin.getFreezeManager().isFrozen(playerUuid);
    }

    @Override
    public void setPlayerFrozen(Player player, boolean freeze, String adminName) {
        if (plugin.getFreezeManager() != null && player != null) {
            plugin.getFreezeManager().setFrozen(player, freeze, adminName);
        }
    }

    @Override
    public Optional<Map<String, Object>> getPlayerStats(String playerName) {
        return plugin.getDatabaseManager().getPlayerStats(playerName);
    }

    @Override
    public void logStaffAction(String actor, String action) {
        plugin.getStaffAuditManager().recordAction(actor, action);
    }

    @Override
    public void dispatchWebhook(String event, Map<String, Object> data) {
        if (plugin.getWebhookManager() != null) {
            plugin.getWebhookManager().dispatch(event, data);
        }
    }
}
