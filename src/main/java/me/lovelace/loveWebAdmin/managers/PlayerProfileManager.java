package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.integration.LoveAuthBridge;
import me.lovelace.loveWebAdmin.integration.LoveBehaviorBridge;
import me.lovelace.loveWebAdmin.integration.VesuvioBridge;
import me.lovelace.loveWebAdmin.models.WebBan;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Агрегирует полную информацию об игроке (онлайн статус, пинг, наигранное время,
 * чат-логи, репутацию LoveBehavior, показатели Античита, баны)
 * и вычисляет сводный рейтинг доверия (Trust Score).
 */
public class PlayerProfileManager {

    private final LoveWebAdmin plugin;
    private final LoveBehaviorBridge behaviorBridge;
    private final LoveAuthBridge authBridge;
    private final VesuvioBridge vesuvioBridge;

    public PlayerProfileManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
        this.behaviorBridge = new LoveBehaviorBridge();
        this.authBridge = new LoveAuthBridge();
        this.vesuvioBridge = (plugin != null && plugin.getVesuvioBridge() != null)
                ? plugin.getVesuvioBridge()
                : new VesuvioBridge();
    }

    public Map<String, Object> getFullProfile(String playerNameOrUuid) {
        Map<String, Object> profile = new LinkedHashMap<>();

        // Базовый поиск игрока
        String name = playerNameOrUuid.trim();
        UUID uuid = null;
        try {
            uuid = UUID.fromString(name);
        } catch (IllegalArgumentException ignored) {}

        Player onlinePlayer = (uuid != null) ? Bukkit.getPlayer(uuid) : Bukkit.getPlayerExact(name);
        OfflinePlayer offlinePlayer = null;

        if (onlinePlayer != null) {
            uuid = onlinePlayer.getUniqueId();
            name = onlinePlayer.getName();
        } else {
            offlinePlayer = (uuid != null) ? Bukkit.getOfflinePlayer(uuid) : Bukkit.getOfflinePlayer(name);
            if (offlinePlayer.getName() != null) {
                name = offlinePlayer.getName();
            }
            if (uuid == null) {
                uuid = offlinePlayer.getUniqueId();
            }
        }

        profile.put("name", name);
        profile.put("uuid", uuid.toString());
        profile.put("isOnline", onlinePlayer != null && onlinePlayer.isOnline());
        profile.put("ping", (onlinePlayer != null && onlinePlayer.isOnline()) ? onlinePlayer.getPing() : 0);

        // IP адрес
        String ip = "—";
        if (onlinePlayer != null && onlinePlayer.getAddress() != null && onlinePlayer.getAddress().getAddress() != null) {
            ip = onlinePlayer.getAddress().getAddress().getHostAddress();
        } else {
            var stats = plugin.getDatabaseManager().getPlayerStats(name);
            if (stats.isPresent() && stats.get().get("lastIp") != null) {
                ip = String.valueOf(stats.get().get("lastIp"));
            }
        }
        profile.put("ip", ip);

        // Статистика активности
        long firstJoined = 0;
        long lastSeen = 0;
        long playtimeSeconds = 0;

        var dbStats = plugin.getDatabaseManager().getPlayerStats(uuid.toString());
        if (dbStats.isPresent()) {
            Map<String, Object> s = dbStats.get();
            firstJoined = ((Number) s.getOrDefault("firstJoinedAt", 0)).longValue();
            lastSeen = ((Number) s.getOrDefault("lastSeenAt", 0)).longValue();
            playtimeSeconds = ((Number) s.getOrDefault("playtimeSeconds", 0)).longValue();
        } else if (offlinePlayer != null) {
            firstJoined = offlinePlayer.getFirstPlayed() / 1000L;
            lastSeen = offlinePlayer.getLastSeen() / 1000L;
        }
        profile.put("firstJoinedAt", firstJoined);
        profile.put("lastSeenAt", lastSeen);
        profile.put("playtimeSeconds", playtimeSeconds);

        // 1. Поведение LoveBehavior
        Map<String, Object> behaviorData = behaviorBridge.getPlayerBehavior(uuid);
        profile.put("behavior", behaviorData);

        // 2. Античит
        Map<String, Object> anticheatData = new LinkedHashMap<>();
        VesuvioBridge vb = (vesuvioBridge != null) ? vesuvioBridge : (plugin != null ? plugin.getVesuvioBridge() : null);
        boolean isVesuvioAvail = vb != null && vb.isAvailable();
        anticheatData.put("available", isVesuvioAvail);
        double anticheatRisk = 0.0;
        double anticheatVl = 0.0;
        boolean manualSuspect = false;

        if (isVesuvioAvail) {
            try {
                Object detail = vb.call("getPlayerDetail", new Class<?>[]{UUID.class}, uuid);
                if (detail instanceof Map<?, ?> dMap) {
                    anticheatData.putAll((Map<String, Object>) dMap);
                    Object r = dMap.get("risk");
                    if (r instanceof Number nr) anticheatRisk = nr.doubleValue();
                    Object v = dMap.get("vl");
                    if (v instanceof Number nv) anticheatVl = nv.doubleValue();
                    Object ms = dMap.get("manualSuspect");
                    if (ms instanceof Boolean mb) manualSuspect = mb;
                }
            } catch (Exception ignored) {}
        }
        profile.put("anticheat", anticheatData);

        // 3. Баны игрока
        List<WebBan> playerBans = plugin.getDatabaseManager().getActiveBansForPlayer(name, uuid.toString(), ip);
        profile.put("activeBansCount", playerBans.size());
        profile.put("hasActiveBan", !playerBans.isEmpty());

        // 4. Последние сообщения в чате
        List<Map<String, Object>> recentChat = plugin.getDatabaseManager().getPlayerChatLogs(name, 25);
        profile.put("recentChat", recentChat);

        // 5. Расчёт сводного рейтинга доверия (Trust Score)
        TrustScore trustScore = computeTrustScore(behaviorData, anticheatRisk, anticheatVl, manualSuspect, playerBans.size(), playtimeSeconds, firstJoined);
        profile.put("trustScore", trustScore.score());
        profile.put("trustGrade", trustScore.grade());
        profile.put("trustDescription", trustScore.description());
        profile.put("trustColor", trustScore.color());

        // 6. Баланс LoveEconomy
        Map<String, Object> ecoData = new LinkedHashMap<>();
        var ecoBridge = plugin != null ? plugin.getLoveEconomyBridge() : null;
        boolean isEcoAvail = ecoBridge != null && ecoBridge.isAvailable();
        ecoData.put("available", isEcoAvail);
        ecoData.put("currencyName", isEcoAvail ? ecoBridge.currencyName() : "монет");
        if (onlinePlayer != null && onlinePlayer.isOnline() && isEcoAvail) {
            ecoData.put("balance", ecoBridge.balance(onlinePlayer));
            ecoData.put("isLive", true);
        } else {
            ecoData.put("balance", 0);
            ecoData.put("isLive", false);
        }
        profile.put("economy", ecoData);

        // 7. Заметки персонала
        profile.put("staffNotes", plugin.getDatabaseManager().getPlayerStaffNotes(name));

        // 8. История банов
        profile.put("bans", playerBans.stream().map(b -> {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("id", b.id());
            bm.put("ruleReason", b.ruleReason());
            bm.put("description", b.description() != null ? b.description() : "");
            bm.put("creatorName", b.creatorName());
            bm.put("createdAt", b.createdAt());
            bm.put("status", b.status());
            return bm;
        }).toList());

        return profile;
    }

    private TrustScore computeTrustScore(
            Map<String, Object> behaviorData,
            double anticheatRisk,
            double anticheatVl,
            boolean manualSuspect,
            int activeBansCount,
            long playtimeSeconds,
            long firstJoinedTimestamp) {

        double baseScore = 70.0; // Базовый уровень

        // LoveBehavior влияние (+20 / -20)
        if (Boolean.TRUE.equals(behaviorData.get("available"))) {
            int pPoints = (int) behaviorData.getOrDefault("politenessPoints", 3500);
            int sPoints = (int) behaviorData.getOrDefault("playstylePoints", 3500);
            double behaviorBonus = ((pPoints - 3500) / 3500.0) * 15.0 + ((sPoints - 3500) / 3500.0) * 10.0;
            baseScore += behaviorBonus;
        }

        // Штрафы античита
        if (anticheatRisk > 0) {
            baseScore -= (anticheatRisk * 0.4); // до -40 баллов
        }
        if (anticheatVl > 0) {
            baseScore -= Math.min(anticheatVl * 0.2, 15.0);
        }
        if (manualSuspect) {
            baseScore -= 15.0;
        }

        // Штраф за активные баны
        if (activeBansCount > 0) {
            baseScore -= (activeBansCount * 40.0);
        }

        // Бонус за наигранное время (до +15 баллов)
        double hours = playtimeSeconds / 3600.0;
        baseScore += Math.min(hours * 0.5, 15.0);

        // Бонус за выдержку аккаунта (>30 дней)
        long now = System.currentTimeMillis() / 1000L;
        if (firstJoinedTimestamp > 0 && (now - firstJoinedTimestamp) > 30L * 86400L && activeBansCount == 0) {
            baseScore += 5.0;
        }

        int finalScore = (int) Math.round(Math.max(0, Math.min(100, baseScore)));

        String grade;
        String description;
        String color;

        if (finalScore >= 90) {
            grade = "S";
            description = "Безупречный (Высокое доверие)";
            color = "#10b981"; // Изумрудный
        } else if (finalScore >= 75) {
            grade = "A";
            description = "Надежный игрок";
            color = "#22c55e"; // Зеленый
        } else if (finalScore >= 50) {
            grade = "B";
            description = "Нейтральный (Норма)";
            color = "#eab308"; // Желтый
        } else if (finalScore >= 30) {
            grade = "C";
            description = "Подозрительный (Требует внимания)";
            color = "#f97316"; // Оранжевый
        } else {
            grade = "F";
            description = "Опасный (Высокий риск нарушений)";
            color = "#ef4444"; // Красный
        }

        return new TrustScore(finalScore, grade, description, color);
    }

    public record TrustScore(int score, String grade, String description, String color) {}
}
