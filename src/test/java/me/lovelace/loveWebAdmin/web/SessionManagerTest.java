package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import me.lovelace.loveWebAdmin.models.*;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

class SessionManagerTest {
    private final Map<String, WebSession> saved = new ConcurrentHashMap<>();
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    private final AtomicBoolean blockLookup = new AtomicBoolean(), failSave = new AtomicBoolean();
    private final SessionRepository repository =
            (SessionRepository)
                    Proxy.newProxyInstance(
                            SessionRepository.class.getClassLoader(),
                            new Class<?>[] {SessionRepository.class},
                            (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "getSession":
                                        {
                                            var snapshot = saved.get(args[0]);
                                            if (blockLookup.get()) {
                                                entered.countDown();
                                                assertTrue(release.await(3, TimeUnit.SECONDS));
                                            }
                                            return Optional.ofNullable(snapshot);
                                        }
                                    case "saveSession":
                                        if (failSave.get())
                                            throw new IllegalStateException("disk unavailable");
                                        var session = (WebSession) args[0];
                                        saved.put(session.token(), session);
                                        return null;
                                    case "deleteSession":
                                        saved.remove(args[0]);
                                        return null;
                                    case "deleteSessionsForAdmin":
                                        saved.values().removeIf(s -> s.adminId() == (int) args[0]);
                                        return null;
                                    case "getAllActiveSessions":
                                        return List.copyOf(saved.values());
                                    case "deleteExpiredSessions":
                                    case "updateSessionLastUsed":
                                        return null;
                                    default:
                                        throw new UnsupportedOperationException(method.getName());
                                }
                            });

    WebSession sample(long expiry) {
        return new WebSession("token", 1, "Staff", 1, expiry, "ip", "agent", 1, 1);
    }

    @Test
    void concurrentLookupCannotResurrectARevokedSession() throws Exception {
        var manager = new SessionManager(repository, () -> 100);
        saved.put("token", sample(200));
        blockLookup.set(true);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var validation = threads.submit(() -> manager.validate("token"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var revoke = threads.submit(() -> manager.invalidate("token"));
            try {
                assertThrows(TimeoutException.class, () -> revoke.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            validation.get(3, TimeUnit.SECONDS);
            revoke.get(3, TimeUnit.SECONDS);
        }
        blockLookup.set(false);
        assertTrue(
                manager.validate("token").isEmpty(),
                "revocation must also remove a concurrently loaded cached value");
        assertFalse(saved.containsKey("token"));
    }

    @Test
    void expiryHasNoOneSecondGraceAndStoppingReleasesCachedSessions() {
        var manager = new SessionManager(repository, () -> 100);
        saved.put("token", sample(100));
        assertTrue(manager.validate("token").isEmpty());
        saved.put("token", sample(200));
        assertTrue(manager.validate("token").isPresent());
        manager.stopCleanupTask();
        saved.clear();
        assertTrue(manager.validate("token").isEmpty());
    }

    @Test
    void cacheIsBoundedWithoutRevokingEvictedSessions() throws Exception {
        var manager = new SessionManager(repository, () -> 100);
        for (int i = 0; i < 5000; i++) {
            String token = "token-" + i;
            saved.put(token, new WebSession(token, 1, "Staff", 1, 200, "ip", "agent", 1, 100));
        }
        manager.loadFromDatabase();
        var field = SessionManager.class.getDeclaredField("cache");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(manager)).size() <= 4096);
        for (String token : saved.keySet()) assertTrue(manager.validate(token).isPresent());
        assertTrue(((Map<?, ?>) field.get(manager)).size() <= 4096);
    }

    @Test
    void failedPersistenceNeverPublishesALogin() {
        failSave.set(true);
        var manager = new SessionManager(repository, () -> 100);
        // The repository is the authoritative publication boundary: no success on storage failure.
        var admin = new WebAdmin(1, "Staff", "hash", 1, 100, 0);
        var role = new WebRole(1, "Staff", "", Set.of(), false);
        assertThrows(IllegalStateException.class, () -> manager.createSession(admin, role));
        assertTrue(saved.isEmpty());
    }
}
