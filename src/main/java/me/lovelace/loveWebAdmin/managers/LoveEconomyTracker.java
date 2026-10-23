package me.lovelace.loveWebAdmin.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.integration.LoveEconomyBridge;
import me.lovelace.loveWebAdmin.models.EconomyAnomalyRecord;
import me.lovelace.loveWebAdmin.models.EconomySnapshotRecord;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import me.lovelace.loveWebAdmin.utils.RunGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

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
    private record LastSeen(long balance, long at) {}

    private static final long LAST_SEEN_TTL_MILLIS = TimeUnit.HOURS.toMillis(24);

    private final Map<String, LastSeen> lastKnownBalances = new ConcurrentHashMap<>();

    // Замер, который уже идёт в момент выключения, успевает дописать снимок до закрытия БД и jar.
    private static final long SHUTDOWN_WAIT_SECONDS = 3L;
    private final RunGuard guard = new RunGuard();

    // Порог прироста за 5 минут, считающийся подозрительным скачком (дюп): ключ economy.anomaly-threshold,
    // читается при каждом срезе, поэтому после перезагрузки конфига новое значение действует сразу.
    // Запасное значение — 10 алмазных монет в стандартной шкале LoveCore (1/100/2000/20000).
    private static final long FALLBACK_ANOMALY_THRESHOLD = 200_000L;

    public LoveEconomyTracker(LoveWebAdmin plugin, LoveEconomyBridge bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    public void start() {
        if (scheduledTask != null) return;
        guard.reopen();
        scheduledTask = Bukkit.getAsyncScheduler().runAtFixedRate(
            plugin,
            task -> takeSnapshot(),
            1,
            5,
            TimeUnit.MINUTES
        );
    }

    /**
     * Отменяет расписание и ждёт, пока уже начатый замер (включая запись в БД) завершится.
     * Так jar плагина и база остаются открытыми до конца работы замера.
     */
    public void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
        if (!guard.stopAndAwait(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
            plugin.getLogger().warning("LoveEconomy: замер балансов не завершился за " + SHUTDOWN_WAIT_SECONDS
                + " с, выключение продолжается");
        }
    }

    /**
     * Запускается с async-расписания и ничего не ждёт. Балансы читаются на главном потоке (там живые
     * инвентари), обработка идёт обратно в async. Блокирующий вызов с async-потока во время выключения
     * зависал, а потом писал в уже закрытый jar плагина.
     */
    public void takeSnapshot() {
        if (!bridge.isAvailable() || !guard.enter()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, this::collectOnMain);
        } catch (IllegalPluginAccessException e) {
            // Плагин уже выключается: замера в этот раз не будет.
        } finally {
            guard.exit();
        }
    }

    /** Главный поток: один проход по онлайн-игрокам, затем обработка в async. */
    private void collectOnMain() {
        if (!guard.enter()) return;
        try {
            Map<String, Long> balances = bridge.onlineBalances();
            Bukkit.getAsyncScheduler().runNow(plugin, task -> record(balances));
        } catch (IllegalPluginAccessException e) {
            // Плагин уже выключается: замера в этот раз не будет.
        } finally {
            guard.exit();
        }
    }

    private void record(Map<String, Long> balances) {
        if (!guard.enter()) return;
        try {
            recordBalances(balances);
        } finally {
            guard.exit();
        }
    }

    private void recordBalances(Map<String, Long> balances) {
        List<Map<String, Object>> playerBalances = new ArrayList<>();
        long totalCirculation = 0L;

        long now = System.currentTimeMillis();
        long anomalyThreshold = anomalyThreshold();
        for (Map.Entry<String, Long> online : balances.entrySet()) {
            long currentBalance = online.getValue();
            totalCirculation += currentBalance;
            String name = online.getKey();

            Map<String, Object> entry = new HashMap<>();
            entry.put("name", name);
            entry.put("balance", currentBalance);
            playerBalances.add(entry);

            // Детекция резких скачков
            LastSeen previous = lastKnownBalances.get(name.toLowerCase());
            Long previousBalance = previous != null ? previous.balance() : null;
            if (previousBalance != null) {
                long delta = currentBalance - previousBalance;
                if (delta >= anomalyThreshold) {
                    plugin.getDatabaseManager().recordEconomyAnomaly(name, previousBalance, currentBalance, delta);
                    plugin.getLogManager().logWebAction(
                        "SYSTEM",
                        String.format("АНОМАЛИЯ LOVEEONOMY: Игрок %s получил +%d %s за 5 минут (Баланс: %d -> %d)",
                            name, delta, bridge.currencyName(), previousBalance, currentBalance)
                    );
                }
            }
            lastKnownBalances.put(name.toLowerCase(), new LastSeen(currentBalance, now));
        }

        // Drop players not seen for a day; the map would otherwise grow with every name that was ever online.
        long cutoff = now - LAST_SEEN_TTL_MILLIS;
        lastKnownBalances.values().removeIf(seen -> seen.at() < cutoff);

        // Сортировка топа богатейших игроков
        playerBalances.sort((a, b) -> Long.compare((Long) b.get("balance"), (Long) a.get("balance")));
        List<Map<String, Object>> top20 = playerBalances.subList(0, Math.min(20, playerBalances.size()));

        String topJson = JsonUtils.toJson(top20);
        plugin.getDatabaseManager().saveEconomySnapshot(totalCirculation, playerBalances.size(), topJson, bridge.scaleVersion());
    }

    /** economy.anomaly-threshold: a number (copper units) or a money string such as "10d"; unreadable -> fallback. */
    private long anomalyThreshold() {
        try {
            long value = dev.lovelace.lovecore.api.economy.MoneyConfig.get(
                    plugin.getConfig(), "economy.anomaly-threshold", FALLBACK_ANOMALY_THRESHOLD);
            return value > 0 ? value : FALLBACK_ANOMALY_THRESHOLD;
        } catch (Throwable t) {
            return plugin.getConfig().getLong("economy.anomaly-threshold", FALLBACK_ANOMALY_THRESHOLD);
        }
    }

    public Map<String, Object> getOverview() {
        Map<String, Object> data = new HashMap<>();
        boolean available = bridge.isAvailable();
        data.put("available", available);
        data.put("currencyName", bridge.currencyName());
        data.put("scaleVersion", bridge.scaleVersion());

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
