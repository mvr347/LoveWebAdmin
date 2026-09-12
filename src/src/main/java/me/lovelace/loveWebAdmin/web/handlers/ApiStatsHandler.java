package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GET /api/stats — TPS, онлайн игроки, аптайм. Bukkit API доступен только на main thread.
 */
public class ApiStatsHandler extends ApiHandlerSupport {

    public ApiStatsHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_STATS);
        if (sessionOpt.isEmpty()) return;

        try {
            Map<String, Object> stats = plugin.getServer().getScheduler()
                .callSyncMethod(plugin, this::collectStats)
                .get();
            sendSuccess(resp, stats);
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка получения статистики: " + e.getMessage());
            sendError(resp, 500, "Внутренняя ошибка сервера");
        }
    }

    private Map<String, Object> collectStats() {
        double[] tps = plugin.getServer().getTPS();
        List<Object> tpsList = new ArrayList<>();
        for (double value : tps) {
            tpsList.add(Math.round(value * 100) / 100.0);
        }

        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", player.getName());
            p.put("uuid", player.getUniqueId().toString());
            p.put("ping", player.getPing());
            players.add(p);
        }

        double mspt = 0.0;
        try {
            mspt = Math.round(plugin.getServer().getAverageTickTime() * 10.0) / 10.0;
        } catch (Throwable ignored) {
            // fallback
            if (!tpsList.isEmpty() && (double) tpsList.get(0) > 0) {
                mspt = Math.round((1000.0 / Math.min(20.0, (double) tpsList.get(0))) * 10.0) / 10.0;
            }
        }

        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.totalMemory() / (1024 * 1024);
        long freeMem = rt.freeMemory() / (1024 * 1024);
        long maxMem = rt.maxMemory() / (1024 * 1024);
        long usedMem = totalMem - freeMem;

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMb", usedMem);
        memory.put("totalMb", totalMem);
        memory.put("maxMb", maxMem);
        memory.put("freeMb", freeMem);
        memory.put("percent", maxMem > 0 ? (int) Math.round((usedMem * 100.0) / maxMem) : 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tps", tpsList);
        result.put("mspt", mspt);
        result.put("memory", memory);
        result.put("onlinePlayers", plugin.getServer().getOnlinePlayers().size());
        result.put("maxPlayers", plugin.getServer().getMaxPlayers());
        result.put("players", players);
        result.put("serverVersion", plugin.getServer().getVersion());
        result.put("uptime", plugin.getUptimeSeconds());
        return result;
    }
}
