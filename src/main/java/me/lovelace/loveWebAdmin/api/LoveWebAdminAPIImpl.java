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
    private final me.lovelace.loveWebAdmin.concurrent.MainDispatcher epoch;
    private void requireReady(){if(!plugin.isEnabled()||epoch==null||epoch!=plugin.main()||epoch.isClosed())throw new IllegalStateException("LoveWebAdmin выключен или был перезапущен");}

    public LoveWebAdminAPIImpl(LoveWebAdmin plugin) {
        this.plugin = plugin;this.epoch=plugin.main();
    }

    @Override
    public boolean isPlayerBanned(String playerName) {
        requireReady();
        if (playerName == null || playerName.isBlank()) return false;
        return plugin.getDatabaseManager().isPlayerBanned(playerName.trim());
    }

    @Override
    public Optional<WebBan> getActiveBan(String playerName) {
        requireReady();
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        return plugin.getDatabaseManager().getActiveBan(playerName.trim());
    }

    @Override
    public WebBan banPlayer(String target, String reason, String creator, String description, String proofUrl, Integer linkedReportId) {
        requireReady();
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

    @Override public WebBan banPlayer(String target,String reason,String description,long durationSeconds,
            me.lovelace.loveWebAdmin.models.BanOrigin origin) {
        boolean ok=plugin.getBanManager().finalizeBan(0,origin.actor(),target,reason,description,List.of(),false,durationSeconds,null,origin);
        return ok ? plugin.getDatabaseManager().getActiveBan(target).orElse(null) : null;
    }

    @Override
    public boolean unbanPlayer(String target, String adminName) {
        requireReady();
        Optional<WebBan> banOpt = plugin.getDatabaseManager().getActiveBan(target);
        if (banOpt.isEmpty()) return false;

        var ban = banOpt.get();
        return plugin.getBanManager().unban(ban.id(), adminName);
    }

    @Override
    public List<WebBan> getActiveBans() {
        requireReady();
        return plugin.getDatabaseManager().getAllBans("ACTIVE");
    }

    @Override
    public PlayerReport createReport(String reporter, String target, List<String> reasons, String description, boolean isRecent) {
        requireReady();
        return plugin.getReportManager().createReport(reporter, target, reasons, description, isRecent);
    }

    @Override
    public List<PlayerReport> getPendingReports() {
        requireReady();
        return plugin.getReportManager().getPendingReports();
    }

    @Override
    public List<PlayerReport> getPendingReportsForTarget(String target) {
        requireReady();
        return plugin.getReportManager().getPendingReportsForTarget(target);
    }

    @Override
    public boolean resolveReport(int reportId, String status, String resolvedBy, Integer linkedBanId) {
        requireReady();
        return plugin.getReportManager().resolveReport(reportId, status, resolvedBy, linkedBanId);
    }

    @Override
    public boolean isPlayerFrozen(UUID playerUuid) {
        requireReady();
        return plugin.getFreezeManager() != null && plugin.getFreezeManager().isFrozen(playerUuid);
    }

    @Override
    public void setPlayerFrozen(Player player, boolean freeze, String adminName) {
        requireReady();
        if (plugin.getFreezeManager() != null && player != null) {
            plugin.getFreezeManager().setFrozen(player, freeze, adminName);
        }
    }

    @Override
    public Optional<Map<String, Object>> getPlayerStats(String playerName) {
        requireReady();
        return plugin.getDatabaseManager().getPlayerStats(playerName);
    }

    @Override
    public void logStaffAction(String actor, String action) {
        requireReady();
        plugin.getStaffAuditManager().recordAction(actor, action);
    }

    @Override
    public void dispatchWebhook(String event, Map<String, Object> data) {
        requireReady();
        if (plugin.getWebhookManager() != null) {
            plugin.getWebhookManager().dispatch(event, data);
        }
    }
}
