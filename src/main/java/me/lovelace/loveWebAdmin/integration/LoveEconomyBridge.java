package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Безопасный мост к модулю LoveEconomy из LoveCore.
 * Если LoveCore отсутствует, методы возвращают безопасные заглушки.
 *
 * <p>LoveEconomy читает и пишет {@code player.getInventory()} без внутренней синхронизации, а
 * вызывается этот мост из потоков Jetty и async-планировщика. Поэтому каждая операция, трогающая
 * живого игрока или инвентарь, исполняется на главном потоке сервера, а вызывающий поток ждёт
 * результат.</p>
 */
public class LoveEconomyBridge {

    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 10L;

    private final Plugin plugin;

    public LoveEconomyBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        try {
            return LoveCore.service(LoveEconomy.class).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    private Optional<LoveEconomy> economy() {
        try {
            return LoveCore.service(LoveEconomy.class);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Runs the task on the main thread (directly if already there) and waits for its result. */
    private <T> T onMainThread(Callable<T> task, T fallback) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "LoveEconomy call failed", e);
                return fallback;
            }
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "LoveEconomy call on the main thread failed or timed out", e);
            return fallback;
        }
    }

    /** Exact-name lookup of an online player; {@code null} if not online. */
    public Player findOnline(String name) {
        return onMainThread(() -> {
            Player p = Bukkit.getPlayerExact(name);
            return p != null && p.isOnline() ? p : null;
        }, null);
    }

    public long balance(Player player) {
        if (player == null) return 0;
        return onMainThread(() -> economy().map(eco -> eco.balance(player)).orElse(0L), 0L);
    }

    /** Balance of every online player, keyed by name, read in a single main-thread pass. */
    public Map<String, Long> onlineBalances() {
        return onMainThread(() -> {
            Map<String, Long> result = new LinkedHashMap<>();
            Optional<LoveEconomy> eco = economy();
            if (eco.isEmpty()) return result;
            for (Player player : Bukkit.getOnlinePlayers()) {
                result.put(player.getName(), eco.get().balance(player));
            }
            return result;
        }, new LinkedHashMap<>());
    }

    public String currencyName() {
        return economy().map(LoveEconomy::currencyName).orElse("монет");
    }

    public boolean give(Player player, long amount) {
        if (player == null || amount <= 0) return false;
        return onMainThread(() -> {
            var eco = economy();
            if (eco.isEmpty() || !player.isOnline()) return false;
            eco.get().give(player, amount);
            return true;
        }, false);
    }

    public boolean charge(Player player, long amount) {
        if (player == null || amount <= 0) return false;
        return onMainThread(() -> {
            var eco = economy();
            if (eco.isEmpty() || !player.isOnline()) return false;
            return eco.get().charge(player, amount);
        }, false);
    }
}
