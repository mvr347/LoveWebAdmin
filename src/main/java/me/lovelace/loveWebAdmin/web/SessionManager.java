package me.lovelace.loveWebAdmin.web;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebRole;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Ограниченный LRU-кэш сессий поверх SQLite. Валидация и отзыв сериализованы одним монитором. */
public class SessionManager {

    private final LoveWebAdmin plugin;
    private final SessionRepository repository;
    private final java.util.function.LongSupplier clock;
    // Eviction only drops a hot copy; a still-valid session remains available from SQLite.
    private final Map<String, WebSession> cache =
            new LinkedHashMap<>(128, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WebSession> entry) {
                    return size() > 4096;
                }
            };
    private ScheduledTask cleanupTask;

    public SessionManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
        this.repository = plugin.getDatabaseManager();
        this.clock = () -> System.currentTimeMillis() / 1000;
    }

    SessionManager(SessionRepository repository, java.util.function.LongSupplier clock) {
        this.plugin = null;
        this.repository = repository;
        this.clock = clock;
    }

    public synchronized void loadFromDatabase() {
        repository.getAllActiveSessions().forEach(session -> cache.put(session.token(), session));
    }

    public synchronized void startCleanupTask() {
        if (cleanupTask != null) return;
        cleanupTask =
                plugin.getServer()
                        .getAsyncScheduler()
                        .runAtFixedRate(
                                plugin,
                                task -> {
                                    synchronized (this) {
                                        repository.deleteExpiredSessions();
                                        long now = clock.getAsLong();
                                        cache.values()
                                                .removeIf(session -> session.expiresAt() <= now);
                                    }
                                },
                                10,
                                10,
                                TimeUnit.MINUTES);
    }

    public synchronized void stopCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        cache.clear();
    }

    public synchronized WebSession createSession(WebAdmin admin, WebRole role) {
        return createSession(admin, role, null, null);
    }

    public synchronized WebSession createSession(
            WebAdmin admin, WebRole role, String ip, String userAgent) {
        long lifetimeMinutes =
                plugin == null
                        ? 60
                        : Math.clamp(
                                plugin.getConfig().getLong("web.session-lifetime-minutes", 60),
                                1,
                                43200);
        long now = clock.getAsLong();
        long expiresAt = now + lifetimeMinutes * 60;
        WebSession session =
                new WebSession(
                        UUID.randomUUID().toString(),
                        admin.id(),
                        admin.username(),
                        role.id(),
                        expiresAt,
                        ip,
                        userAgent,
                        now,
                        now);

        repository.saveSession(session);
        cache.put(session.token(), session);
        return session;
    }

    public synchronized Optional<WebSession> validate(String token) {
        if (token == null) return Optional.empty();

        WebSession session = cache.get(token);
        long now = clock.getAsLong();

        if (session != null) {
            if (session.expiresAt() <= now) {
                invalidate(token);
                return Optional.empty();
            }
            // Обновляем активность раз в 2 минуты для экономии I/O
            if (now - session.lastUsedAt() > 120) {
                WebSession updated =
                        new WebSession(
                                session.token(),
                                session.adminId(),
                                session.adminUsername(),
                                session.roleId(),
                                session.expiresAt(),
                                session.ip(),
                                session.userAgent(),
                                session.createdAt(),
                                now);
                cache.put(token, updated);
                repository.updateSessionLastUsed(token, now);
                return Optional.of(updated);
            }
            return Optional.of(session);
        }

        Optional<WebSession> fromDb = repository.getSession(token);
        if (fromDb.isEmpty()) return Optional.empty();

        session = fromDb.get();
        if (session.expiresAt() <= now) {
            invalidate(token);
            return Optional.empty();
        }

        cache.put(token, session);
        return Optional.of(session);
    }

    public synchronized Optional<WebSession> validate(String token, String currentIp) {
        Optional<WebSession> sessionOpt = validate(token);
        if (sessionOpt.isPresent() && currentIp != null && !currentIp.isBlank()) {
            boolean strictIp =
                    plugin != null && plugin.getConfig().getBoolean("security.strict-ip", false);
            WebSession session = sessionOpt.get();
            if (strictIp
                    && session.ip() != null
                    && !session.ip().isBlank()
                    && !session.ip().equals(currentIp)) {
                plugin.getLogger()
                        .warning(
                                "[SECURITY] Сессия "
                                        + session.adminUsername()
                                        + " аннулирована: обнаружена смена IP с "
                                        + session.ip()
                                        + " на "
                                        + currentIp);
                invalidate(token);
                return Optional.empty();
            }
        }
        return sessionOpt;
    }

    public synchronized void invalidate(String token) {
        cache.remove(token);
        repository.deleteSession(token);
    }

    public synchronized void invalidateSessionsForAdmin(int adminId) {
        cache.values().removeIf(session -> session.adminId() == adminId);
        repository.deleteSessionsForAdmin(adminId);
    }

    public synchronized void terminateAllSessionsExcept(String currentToken) {
        cache.values().removeIf(session -> !session.token().equals(currentToken));
        repository.deleteAllSessionsExcept(currentToken);
    }

    public synchronized java.util.List<WebSession> getSessionsForAdmin(int adminId) {
        return repository.getSessionsForAdmin(adminId);
    }

    public synchronized void invalidateOtherSessions(int adminId, String currentToken) {
        cache.values()
                .removeIf(
                        session ->
                                session.adminId() == adminId
                                        && !session.token().equals(currentToken));
        repository.deleteOtherSessions(adminId, currentToken);
    }
}
