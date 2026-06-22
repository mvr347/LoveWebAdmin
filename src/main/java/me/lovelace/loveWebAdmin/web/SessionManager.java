package me.lovelace.loveWebAdmin.web;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebRole;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Хранит активные сессии в памяти (ConcurrentHashMap) с персистентностью в БД на случай рестарта.
 */
public class SessionManager {

    private final LoveWebAdmin plugin;
    private final Map<String, WebSession> cache = new ConcurrentHashMap<>();
    private ScheduledTask cleanupTask;

    public SessionManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public void loadFromDatabase() {
        plugin.getDatabaseManager().getAllActiveSessions().forEach(session -> cache.put(session.token(), session));
    }

    public void startCleanupTask() {
        cleanupTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            plugin.getDatabaseManager().deleteExpiredSessions();
            long now = System.currentTimeMillis() / 1000;
            cache.values().removeIf(session -> session.expiresAt() < now);
        }, 10, 10, TimeUnit.MINUTES);
    }

    public void stopCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    public WebSession createSession(WebAdmin admin, WebRole role) {
        long lifetimeMinutes = plugin.getConfig().getLong("web.session-lifetime-minutes", 60);
        long expiresAt = System.currentTimeMillis() / 1000 + lifetimeMinutes * 60;
        WebSession session = new WebSession(UUID.randomUUID().toString(), admin.id(), admin.username(), role.id(), expiresAt);

        cache.put(session.token(), session);
        plugin.getDatabaseManager().saveSession(session);
        return session;
    }

    public Optional<WebSession> validate(String token) {
        if (token == null) return Optional.empty();

        WebSession cached = cache.get(token);
        if (cached != null) {
            if (cached.expiresAt() < System.currentTimeMillis() / 1000) {
                invalidate(token);
                return Optional.empty();
            }
            return Optional.of(cached);
        }

        Optional<WebSession> fromDb = plugin.getDatabaseManager().getSession(token);
        if (fromDb.isEmpty()) return Optional.empty();

        WebSession session = fromDb.get();
        if (session.expiresAt() < System.currentTimeMillis() / 1000) {
            invalidate(token);
            return Optional.empty();
        }

        cache.put(token, session);
        return Optional.of(session);
    }

    public void invalidate(String token) {
        cache.remove(token);
        plugin.getDatabaseManager().deleteSession(token);
    }

    public void invalidateSessionsForAdmin(int adminId) {
        cache.values().removeIf(session -> session.adminId() == adminId);
    }
}
