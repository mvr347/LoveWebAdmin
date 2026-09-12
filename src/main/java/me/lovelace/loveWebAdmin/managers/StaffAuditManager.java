package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.StaffCommandLog;
import me.lovelace.loveWebAdmin.models.StaffKpiRecord;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Отслеживает команды персонала, выявляет подозрительные действия
 * (выдача креатива, прав OP, изменение пермишенов, выдача валюты) и агрегирует метрики KPI.
 */
public class StaffAuditManager {

    private final LoveWebAdmin plugin;

    // Паттерны критических команд
    private static final Pattern CRITICAL_PATTERN = Pattern.compile(
        "^/?(op|deop|stop|restart|reload|plugman|sudo)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Паттерны подозрительных команд (выдача ресурсов, креатива, пермишенов)
    private static final Pattern SUSPICIOUS_PATTERN = Pattern.compile(
        "^/?(gamemode\\s+(creative|c|1)|give|eco(nomy)?\\s+give|lp\\s+(user|group).*permission|kill\\s+@)\\b",
        Pattern.CASE_INSENSITIVE
    );

    public StaffAuditManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public void processStaffCommand(String username, String command) {
        if (username == null || command == null) return;
        String clean = command.trim();
        if (clean.startsWith("/")) clean = clean.substring(1);

        boolean isCritical = CRITICAL_PATTERN.matcher(clean).find();
        boolean isSuspicious = isCritical || SUSPICIOUS_PATTERN.matcher(clean).find();
        String riskLevel = isCritical ? "CRITICAL" : (isSuspicious ? "SUSPICIOUS" : "INFO");

        plugin.getDatabaseManager().recordStaffCommand(username, clean, isSuspicious, riskLevel);

        if (isSuspicious) {
            String alert = String.format("ПОДОЗРИТЕЛЬНАЯ КОМАНДА [%s]: сотрудник %s выполнил: /%s", riskLevel, username, clean);
            plugin.getLogManager().logWebAction(username, alert);
        }
    }

    public List<StaffCommandLog> getLogs(int limit, int offset, boolean suspiciousOnly, String staffFilter) {
        return plugin.getDatabaseManager().getStaffCommandLogs(limit, offset, suspiciousOnly, staffFilter);
    }

    public List<StaffKpiRecord> getKpiStats() {
        return plugin.getDatabaseManager().getStaffKpiStats();
    }

    public void recordAction(String actor, String action) {
        plugin.getLogManager().logWebAction(actor, action);
    }
}
