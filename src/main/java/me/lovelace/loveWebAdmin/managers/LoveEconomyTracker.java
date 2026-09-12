package me.lovelace.loveWebAdmin.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.integration.LoveEconomyBridge;
import me.lovelace.loveWebAdmin.models.EconomyAnomalyRecord;
import me.lovelace.loveWebAdmin.models.EconomySnapshotRecord;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Фоновый трекер экономики LoveCore (LoveEconomy).
 * Каждые 5 минут производит замер балансов онлайн-игроков,
 * фиксирует оборот валюты и выявляет аномальные всплески богатства (защита от дюпов).
 */
public class LoveEconomyTracker {

    private final LoveWebAdmin plugin;
    private final LoveEconomyBridge bridge;
    private ScheduledTask scheduledTask;

    // Кэш предыдущих балансов для детектора аномалий
    private final Map<String, Long> lastKnownBalances = new ConcurrentHashMap<>();

    // Порог прироста за 5 минут, считающийся подозрительным аномальным скачком (дюп)
    private static final long DEFAULT_ANOMALY_THRESHOLD = 50_000L;

    public LoveEconomyTracker(LoveWebAdmin plugin, LoveEconomyBridge bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    public void start() {
        if (scheduledTask != null) return;
        scheduledTask = Bukkit.getAsyncScheduler().runAtFixedRate(
            plugin,
            task -> takeSnapshot(),
            1,
            5,
            TimeUnit.MINUTES
        );
    }

    public void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
    }

    public void takeSnapshot() {
        if (!bridge.isAvailable()) return;

        List<Map<String, Object>> playerBalances = new ArrayList<>();
        long totalCirculation = 0L;

        for (Player player : Bukkit.getOnlinePlayers()) {
            long currentBalance = bridge.balance(player);
            totalCirculation += currentBalance;
            String name = player.getName();

            Map<String, Object> entry = new HashMap<>();
            entry.put("name", name);
            entry.put("balance", currentBalance);
            playerBalances.add(entry);

            // Детекция резких скачков
            Long previousBalance = lastKnownBalances.get(name.toLowerCase());
            if (previousBalance != null) {
                long delta = currentBalance - previousBalance;
                if (delta >= DEFAULT_ANOMALY_THRESHOLD) {
                    plugin.getDatabaseManager().recordEconomyAnomaly(name, previousBalance, currentBalance, delta);
                    plugin.getLogManager().logWebAction(
                        "SYSTEM",
                        String.format("АНОМАЛИЯ LOVEEONOMY: Игрок %s получил +%d %s за 5 минут (Баланс: %d -> %d)",
                            name, delta, bridge.currencyName(), previousBalance, currentBalance)
                    );
                }
            }
            lastKnownBalances.put(name.toLowerCase(), currentBalance);
        }

        // Сортировка топа богатейших игроков
        playerBalances.sort((a, b) -> Long.compare((Long) b.get("balance"), (Long) a.get("balance")));
        List<Map<String, Object>> top20 = playerBalances.subList(0, Math.min(20, playerBalances.size()));

        String topJson = JsonUtils.toJson(top20);
        plugin.getDatabaseManager().saveEconomySnapshot(totalCirculation, playerBalances.size(), topJson);
    }

    public Map<String, Object> getOverview() {
        Map<String, Object> data = new HashMap<>();
        boolean available = bridge.isAvailable();
        data.put("available", available);
        data.put("currencyName", bridge.currencyName());

        if (!available) {
            data.put("totalCirculation", 0);
            data.put("trackedOnline", 0);
            data.put("topPlayers", List.of());
            data.put("snapshots", List.of());
            data.put("anomalies", List.of());
            return data;
        }

        List<EconomySnapshotRecord> snapshots = plugin.getDatabaseManager().getRecentEconomySnapshots(12); // За последний час (12 * 5мин)
        List<EconomyAnomalyRecord> anomalies = plugin.getDatabaseManager().getRecentEconomyAnomalies(20);

        long currentTotal = 0;
        int trackedCount = 0;
        List<Object> currentTop = new ArrayList<>();

        if (!snapshots.isEmpty()) {
            EconomySnapshotRecord latest = snapshots.get(0);
            currentTotal = latest.totalCoins();
            trackedCount = latest.trackedPlayers();
            currentTop = JsonUtils.parseList(latest.topBalancesJson());
        }

        data.put("totalCirculation", currentTotal);
        data.put("trackedOnline", trackedCount);
        data.put("topPlayers", currentTop);
        data.put("snapshots", snapshots);
        data.put("anomalies", anomalies);
        return data;
    }

    public LoveEconomyBridge getBridge() {
        return bridge;
    }
}
