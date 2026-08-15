package me.lovelace.loveWebAdmin.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.lovelace.loveWebAdmin.LoveWebAdmin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Отслеживает неудачные попытки входа (по IP и/или по нику) и блокирует вход
 * после превышения security.login.max-attempts в течение security.login.lockout-minutes.
 *
 * <p>Записи удаляются при успешном входе, но злоумышленник может присылать неудачные
 * попытки с постоянно новыми никами (userKey каждый раз новый), поэтому без периодической
 * очистки attemptsByKey рос бы неограниченно — это DoS через утечку памяти. startCleanupTask()
 * убирает записи, чья окно+блокировка давно истекли.</p>
 */
public class LoginAttemptTracker {

    private final LoveWebAdmin plugin;
    private final Map<String, Attempts> attemptsByKey = new ConcurrentHashMap<>();
    private ScheduledTask cleanupTask;

    public LoginAttemptTracker(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public void startCleanupTask() {
        cleanupTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            long now = System.currentTimeMillis();
            long window = lockoutMillis();
            attemptsByKey.entrySet().removeIf(entry -> {
                Attempts attempts = entry.getValue();
                boolean lockoutExpired = attempts.lockedUntil <= now;
                boolean windowExpired = now - attempts.windowStart > window;
                return lockoutExpired && windowExpired;
            });
        }, 10, 10, TimeUnit.MINUTES);
    }

    public void stopCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    private int maxAttempts() {
        return plugin.getConfig().getInt("security.login.max-attempts", 5);
    }

    private long lockoutMillis() {
        return plugin.getConfig().getLong("security.login.lockout-minutes", 15) * 60_000L;
    }

    /**
     * Возвращает количество секунд до снятия блокировки для любого из ключей, либо 0 если не заблокирован.
     */
    public synchronized long getLockedRemainingSeconds(String... keys) {
        long now = System.currentTimeMillis();
        long maxRemaining = 0;
        for (String key : keys) {
            if (key == null) continue;
            Attempts attempts = attemptsByKey.get(key);
            if (attempts != null && attempts.lockedUntil > now) {
                maxRemaining = Math.max(maxRemaining, (attempts.lockedUntil - now + 999) / 1000);
            }
        }
        return maxRemaining;
    }

    public synchronized boolean isLocked(String... keys) {
        return getLockedRemainingSeconds(keys) > 0;
    }

    public synchronized void recordFailure(String... keys) {
        long now = System.currentTimeMillis();
        int max = maxAttempts();
        long window = lockoutMillis();
        long lockout = lockoutMillis();
        for (String key : keys) {
            if (key == null) continue;
            Attempts attempts = attemptsByKey.computeIfAbsent(key, k -> new Attempts());
            if (now - attempts.windowStart > window) {
                attempts.windowStart = now;
                attempts.count = 0;
            }
            attempts.count++;
            if (attempts.count >= max) {
                attempts.lockedUntil = now + lockout;
            }
        }
    }

    public synchronized void recordSuccess(String... keys) {
        for (String key : keys) {
            if (key == null) continue;
            attemptsByKey.remove(key);
        }
    }

    private static class Attempts {
        long windowStart = System.currentTimeMillis();
        int count = 0;
        long lockedUntil = 0;
    }
}
