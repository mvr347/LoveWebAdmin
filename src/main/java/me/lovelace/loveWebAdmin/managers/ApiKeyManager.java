package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.ApiKeyRecord;
import me.lovelace.loveWebAdmin.models.Permission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Управление API ключами для разработчиков, ботов и внешних сервисов.
 */
public class ApiKeyManager {

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LoveWebAdmin plugin;
    private final Map<String, ApiKeyRecord> activeKeyCache = new ConcurrentHashMap<>();

    public ApiKeyManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    /**
     * Создает новый API ключ и возвращает сгенерированный секретный токен.
     */
    public GeneratedKey createKey(String name, List<String> permissions, String creator) {
        String secretToken = generateToken();
        String hash = sha256(secretToken);
        String prefix = secretToken.substring(0, 14) + "...";

        ApiKeyRecord record = plugin.getDatabaseManager().saveApiKey(name, hash, prefix, permissions, creator);
        if (record != null) {
            activeKeyCache.put(hash, record);
        }
        return new GeneratedKey(secretToken, record);
    }

    /**
     * Валидирует переданный в запросе токен (из X-API-Key или Bearer).
     */
    public Optional<ApiKeyRecord> validateKey(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) return Optional.empty();

        String hash = sha256(plainKey.trim());
        ApiKeyRecord cached = activeKeyCache.get(hash);
        if (cached != null) {
            if (!cached.isActive()) return Optional.empty();
            touchUsage(cached);
            return Optional.of(cached);
        }

        Optional<ApiKeyRecord> fromDb = plugin.getDatabaseManager().getApiKeyByHash(hash);
        if (fromDb.isPresent()) {
            ApiKeyRecord record = fromDb.get();
            activeKeyCache.put(hash, record);
            touchUsage(record);
            return Optional.of(record);
        }

        return Optional.empty();
    }

    /**
     * Проверяет, обладает ли API ключ данным разрешением.
     */
    public boolean hasPermission(String tokenOrHash, Permission permission) {
        String hash = tokenOrHash.length() == 64 ? tokenOrHash : sha256(tokenOrHash);
        ApiKeyRecord key = activeKeyCache.get(hash);
        if (key == null) {
            var opt = plugin.getDatabaseManager().getApiKeyByHash(hash);
            if (opt.isPresent()) {
                key = opt.get();
                activeKeyCache.put(hash, key);
            }
        }
        if (key == null || !key.isActive()) return false;
        return key.hasPermission(permission.name());
    }

    public List<ApiKeyRecord> getAllKeys() {
        return plugin.getDatabaseManager().getAllApiKeys();
    }

    public boolean revokeKey(int id) {
        boolean ok = plugin.getDatabaseManager().deleteApiKey(id);
        activeKeyCache.values().removeIf(k -> k.id() == id);
        return ok;
    }

    private void touchUsage(ApiKeyRecord record) {
        long now = System.currentTimeMillis() / 1000L;
        // Обновляем асинхронно
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            plugin.getDatabaseManager().updateApiKeyLastUsed(record.id(), now);
        });
    }

    private String generateToken() {
        StringBuilder sb = new StringBuilder("lwa_live_");
        for (int i = 0; i < 32; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 недоступен в JVM", e);
        }
    }

    public record GeneratedKey(String secretToken, ApiKeyRecord record) {}
}
