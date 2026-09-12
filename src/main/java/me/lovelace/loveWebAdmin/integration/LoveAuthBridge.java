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

                    regFuture.thenCombine(altsFuture, (isRegistered, alts) -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("available", true);
                        map.put("found", true);
                        map.put("uuid", uuid.toString());
                        map.put("username", username);
                        map.put("lastIp", lastIp != null ? lastIp : "—");
                        map.put("hasDiscord", hasDiscord);
                        map.put("isRegistered", isRegistered);
                        map.put("isLocked", isLocked);
                        map.put("alts", alts != null ? alts : List.of());
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
            // Fallback: выполнение через консольную команду если доступно
            Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("LoveWebAdmin"), () -> {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ladmin changepassword " + playerName + " " + newPassword);
                future.complete(ok);
            });
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
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ladmin delete " + playerName);
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
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ladmin unlock " + playerName);
            future.complete(ok);
        });
        return future;
    }

    /**
     * Разблокировка IP-адреса.
     */
    public CompletableFuture<Boolean> unblockIp(String ip) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (!isAvailable()) {
            future.complete(false);
            return future;
        }

        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("LoveWebAdmin"), () -> {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ladmin unblockip " + ip);
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
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ladmin session reset " + playerName);
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
