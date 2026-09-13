package me.lovelace.loveWebAdmin.integration;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reflection bridge к LoveAuth API и внутренним сервисам.
 * Не требует compile-time зависимости и безопасно работает, если плагин не установлен.
 */
public class LoveAuthBridge {

    public boolean isAvailable() {
        Plugin p = Bukkit.getPluginManager().getPlugin("LoveAuth");
        return p != null && p.isEnabled();
    }

    private Plugin getPlugin() {
        return Bukkit.getPluginManager().getPlugin("LoveAuth");
    }

    /**
     * Получает статус аккаунта игрока в LoveAuth:
     * UUID, зарегистрирован ли, заблокирован ли, последний IP, привязан ли Discord, альты.
     */
    public CompletableFuture<Map<String, Object>> getPlayerAuthInfo(String playerName) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        if (!isAvailable()) {
            future.complete(Map.of("available", false));
            return future;
        }

        try {
            Plugin loveAuth = getPlugin();
            Method getDbMethod = loveAuth.getClass().getMethod("getDatabaseManager");
            Object db = getDbMethod.invoke(loveAuth);

            Method findByNameMethod = db.getClass().getMethod("findPlayerByName", String.class);
            @SuppressWarnings("unchecked")
            CompletableFuture<Optional<?>> recordFuture = (CompletableFuture<Optional<?>>) findByNameMethod.invoke(db, playerName);

            recordFuture.thenAccept(recordOpt -> {
                if (recordOpt.isEmpty()) {
                    future.complete(Map.of("available", true, "found", false));
                    return;
                }

                try {
                    Object record = recordOpt.get();
                    Class<?> recClass = record.getClass();

                    UUID uuid = (UUID) invokeGetter(recClass, record, "uuid", "getUuid", "uniqueId");
                    String username = (String) invokeGetter(recClass, record, "username", "getUsername", "getName");
                    String lastIp = (String) invokeGetter(recClass, record, "lastIp", "getLastIp", "ip");
                    boolean hasDiscord = Boolean.TRUE.equals(invokeGetter(recClass, record, "hasDiscord", "getHasDiscord", "isDiscordLinked", "hasDiscordLinked"));
                    boolean isLocked = Boolean.TRUE.equals(invokeGetter(recClass, record, "locked", "isLocked", "isAccountLocked", "getIsLocked"));

                    if (uuid == null) {
                        future.complete(Map.of("available", true, "found", false, "error", "Не удалось извлечь UUID"));
                        return;
                    }

                    Method isRegMethod = db.getClass().getMethod("isRegistered", UUID.class);
                    @SuppressWarnings("unchecked")
                    CompletableFuture<Boolean> regFuture = (CompletableFuture<Boolean>) isRegMethod.invoke(db, uuid);

                    Method altsMethod = db.getClass().getMethod("getAlts", String.class);
                    @SuppressWarnings("unchecked")
                    CompletableFuture<List<String>> altsFuture = (lastIp != null)
                            ? (CompletableFuture<List<String>>) altsMethod.invoke(db, lastIp)
                            : CompletableFuture.completedFuture(new ArrayList<>());

                    CompletableFuture<Boolean> ipBlockFuture = isIpBlocked(lastIp);

                    regFuture.thenCombine(altsFuture, (isRegistered, alts) -> Map.entry(isRegistered, alts))
                            .thenCombine(ipBlockFuture, (entry, isIpBlocked) -> {
                                Map<String, Object> map = new LinkedHashMap<>();
                                map.put("available", true);
                                map.put("found", true);
                                map.put("uuid", uuid.toString());
                                map.put("username", username);
                                map.put("lastIp", lastIp != null ? lastIp : "—");
                                map.put("hasDiscord", hasDiscord);
                                map.put("isRegistered", entry.getKey());
                                map.put("isLocked", isLocked);
                                map.put("isIpBlocked", isIpBlocked);
                                map.put("alts", entry.getValue() != null ? entry.getValue() : List.of());
                                return map;
                            }).thenAccept(future::complete).exceptionally(err -> {
                                future.completeExceptionally(err);
                                return null;
                            });

                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }).exceptionally(err -> {
                future.completeExceptionally(err);
                return null;
            });

        } catch (Exception e) {
            future.complete(Map.of("available", false, "error", e.getMessage()));
        }

        return future;
    }

    /**
     * Проверяет, заблокирован ли данный IP адрес в LoveAuth.
     */
    public CompletableFuture<Boolean> isIpBlocked(String ip) {
        if (!isAvailable() || ip == null || ip.isBlank() || ip.equals("—")) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            Plugin loveAuth = getPlugin();
            Method getDbMethod = loveAuth.getClass().getMethod("getDatabaseManager");
            Object db = getDbMethod.invoke(loveAuth);
            Method getIpBlockMethod = db.getClass().getMethod("getIpBlock", String.class);
            @SuppressWarnings("unchecked")
            CompletableFuture<Optional<?>> blockFuture = (CompletableFuture<Optional<?>>) getIpBlockMethod.invoke(db, ip.trim());
            return blockFuture.thenApply(opt -> {
                if (opt.isEmpty()) return false;
                Object rec = opt.get();
                Object blockedUntil = invokeGetter(rec.getClass(), rec, "blockedUntil", "getBlockedUntil");
                if (blockedUntil instanceof Number n) {
                    return n.longValue() > (System.currentTimeMillis() / 1000L);
                }
                return false;
            }).exceptionally(e -> false);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Сброс / смена пароля через LoveAuth.
     */
    public CompletableFuture<Boolean> changePassword(String playerName, String newPassword) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!isAvailable()) {
            future.complete(false);
            return future;
        }

        try {
            Plugin loveAuth = getPlugin();
            Method getAuthMgr = loveAuth.getClass().getMethod("getAuthManager");
            Object authMgr = getAuthMgr.invoke(loveAuth);

            OfflinePlayer op = Bukkit.getOfflinePlayer(playerName);
            UUID uuid = op.getUniqueId();

            Method forceRegister = authMgr.getClass().getMethod("forceRegister", UUID.class, String.class);
            @SuppressWarnings("unchecked")
            CompletableFuture<Void> frFuture = (CompletableFuture<Void>) forceRegister.invoke(authMgr, uuid, newPassword);

            frFuture.thenRun(() -> future.complete(true)).exceptionally(err -> {
                future.complete(false);
                return null;
            });
        } catch (Exception e) {
            future.complete(false);
        }
        return future;
    }

    /**
     * Удаление / дерегистрация аккаунта в LoveAuth.
     */
    public CompletableFuture<Boolean> deleteAccount(String playerName) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!isAvailable()) {
            future.complete(false);
            return future;
        }

        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("LoveWebAdmin"), () -> {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "loveauthadmin delete " + playerName);
            future.complete(ok);
        });
        return future;
    }

    /**
     * Разблокировка аккаунта (снятие блокировки брутфорса).
     */
    public CompletableFuture<Boolean> unlockAccount(String playerName) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!isAvailable()) {
            future.complete(false);
            return future;
        }

        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("LoveWebAdmin"), () -> {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "loveauthadmin unlock " + playerName);
            future.complete(ok);
        });
        return future;
    }

    /**
     * Разблокировка IP-адреса.
     */
    public CompletableFuture<Boolean> unblockIp(String ip) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!isAvailable() || ip == null || ip.isBlank() || ip.equals("—")) {
            future.complete(false);
            return future;
        }

        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("LoveWebAdmin"), () -> {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "loveauthadmin unblockip " + ip.trim());
            future.complete(ok);
        });
        return future;
    }

    /**
     * Сброс активной сессии игрока и кик с сервера.
     */
    public CompletableFuture<Boolean> resetSession(String playerName) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!isAvailable()) {
            future.complete(false);
            return future;
        }

        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("LoveWebAdmin"), () -> {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "loveauthadmin session reset " + playerName);
            Player p = Bukkit.getPlayerExact(playerName);
            if (p != null && p.isOnline()) {
                p.kickPlayer("§cВаша сессия авторизации была сброшена администратором.");
            }
            future.complete(ok);
        });
        return future;
    }

    private Object invokeGetter(Class<?> clazz, Object instance, String... candidateMethods) {
        for (String mName : candidateMethods) {
            try {
                Method m = clazz.getMethod(mName);
                return m.invoke(instance);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                // Игнорируем и пробуем следующий вариант
            }
        }
        for (String mName : candidateMethods) {
            try {
                Method m = clazz.getDeclaredMethod(mName);
                m.setAccessible(true);
                return m.invoke(instance);
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
