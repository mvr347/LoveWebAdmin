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
        return createSession(admin, role, null, null);
    }

    public WebSession createSession(WebAdmin admin, WebRole role, String ip, String userAgent) {
        long lifetimeMinutes = plugin.getConfig().getLong("web.session-lifetime-minutes", 60);
        long now = System.currentTimeMillis() / 1000;
        long expiresAt = now + lifetimeMinutes * 60;
        WebSession session = new WebSession(
            UUID.randomUUID().toString(), admin.id(), admin.username(), role.id(), expiresAt,
            ip, userAgent, now, now
        );

        cache.put(session.token(), session);
        plugin.getDatabaseManager().saveSession(session);
        return session;
    }

    public Optional<WebSession> validate(String token) {
        if (token == null) return Optional.empty();

        WebSession session = cache.get(token);
        long now = System.currentTimeMillis() / 1000;

        if (session != null) {
            if (session.expiresAt() < now) {
                invalidate(token);
                return Optional.empty();
            }
            // Обновляем активность раз в 2 минуты для экономии I/O
            if (now - session.lastUsedAt() > 120) {
                WebSession updated = new WebSession(
                    session.token(), session.adminId(), session.adminUsername(),
                    session.roleId(), session.expiresAt(), session.ip(), session.userAgent(),
                    session.createdAt(), now
                );
                cache.put(token, updated);
                plugin.getDatabaseManager().updateSessionLastUsed(token, now);
                return Optional.of(updated);
            }
            return Optional.of(session);
        }

        Optional<WebSession> fromDb = plugin.getDatabaseManager().getSession(token);
        if (fromDb.isEmpty()) return Optional.empty();

        session = fromDb.get();
        if (session.expiresAt() < now) {
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
        plugin.getDatabaseManager().deleteSessionsForAdmin(adminId);
    }

    public java.util.List<WebSession> getSessionsForAdmin(int adminId) {
        return plugin.getDatabaseManager().getSessionsForAdmin(adminId);
    }

    public void invalidateOtherSessions(int adminId, String currentToken) {
        cache.values().removeIf(session -> session.adminId() == adminId && !session.token().equals(currentToken));
        plugin.getDatabaseManager().deleteOtherSessions(adminId, currentToken);
    }
}
