package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.StaffCommandLog;
import me.lovelace.loveWebAdmin.models.StaffKpiRecord;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Обработчик аудита действий модераторов/администраторов и метрик KPI.
 *
 * GET /api/staff-audit/logs?limit=50&offset=0&suspiciousOnly=true&staff=Player
 * GET /api/staff-audit/kpi
 */
public class ApiStaffAuditHandler extends ApiHandlerSupport {

    public ApiStaffAuditHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_STAFF_AUDIT);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if (pathInfo == null) pathInfo = "";

        if ("/kpi".equals(pathInfo)) {
            List<StaffKpiRecord> kpiList = plugin.getStaffAuditManager().getKpiStats();
            sendSuccess(resp, Map.of("kpi", kpiList));
            return;
        }

        // По умолчанию или /logs
        int limit = Math.min(200, Math.max(1, parseIntOrDefault(req.getParameter("limit"), 50)));
        int offset = Math.max(0, parseIntOrDefault(req.getParameter("offset"), 0));
        boolean suspiciousOnly = "true".equalsIgnoreCase(req.getParameter("suspiciousOnly"));
        String staff = req.getParameter("staff");
        if (staff != null && staff.isBlank()) staff = null;

        List<StaffCommandLog> logs = plugin.getStaffAuditManager().getLogs(limit, offset, suspiciousOnly, staff);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("logs", logs);
        responseData.put("limit", limit);
        responseData.put("offset", offset);
        responseData.put("suspiciousOnly", suspiciousOnly);
        if (staff != null) responseData.put("staff", staff);

        sendSuccess(resp, responseData);
    }
}
