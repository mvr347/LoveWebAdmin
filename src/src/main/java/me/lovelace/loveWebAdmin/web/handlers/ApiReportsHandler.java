package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.PlayerReport;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API для работы с жалобами игроков:
 * GET  /api/reports           — Список жалоб с фильтрацией и общей статистикой
 * GET  /api/reports/{id}      — Детали жалобы и 1-минутный контекст чата участников
 * POST /api/reports/{id}/accept — Принять жалобу (наказать, наградить репортера)
 * POST /api/reports/{id}/reject — Пометить жалобу ложной (вернуть репутацию цели)
 * DELETE /api/reports/{id}    — Удалить жалобу
 */
public class ApiReportsHandler extends ApiHandlerSupport {

    public ApiReportsHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_REPORTS);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo) || "/all".equals(pathInfo)) {
            handleListReports(req, resp);
            return;
        }

        // GET /api/reports/{id}
        String idStr = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        try {
            int id = Integer.parseInt(idStr);
            handleGetReportDetails(id, resp);
        } catch (NumberFormatException e) {
            sendError(resp, 400, "Некорректный ID жалобы");
        }
    }

    private void handleListReports(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String status = req.getParameter("status");
        String search = req.getParameter("search");
        String target = req.getParameter("target");
        int limit = 50;
        int offset = 0;

        if (target != null && !target.isBlank()) {
            List<PlayerReport> list = plugin.getDatabaseManager().getPendingReportsForPlayer(target);
            List<Map<String, Object>> mapped = new ArrayList<>();
            for (PlayerReport r : list) {
                mapped.add(toReportMap(r));
            }
            sendSuccess(resp, Map.of("reports", mapped));
            return;
        }

        if (req.getParameter("limit") != null) {
            try {
                limit = Math.min(200, Integer.parseInt(req.getParameter("limit")));
            } catch (NumberFormatException e) {
                limit = 50;
            }
        }
        if (req.getParameter("offset") != null) {
            try {
                offset = Math.max(0, Integer.parseInt(req.getParameter("offset")));
            } catch (NumberFormatException e) {
                offset = 0;
            }
        }

        List<PlayerReport> reports = plugin.getDatabaseManager().getAllReports(status, search, limit, offset);
        Map<String, Object> stats = plugin.getDatabaseManager().getReportsStats();

        List<Map<String, Object>> mappedReports = new ArrayList<>();
        for (PlayerReport r : reports) {
            Map<String, Object> map = toReportMap(r);
            map.put("targetSuspicious", plugin.getDatabaseManager().isPlayerSuspicious(r.targetUuid()));
            mappedReports.add(map);
        }

        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("reports", mappedReports);
        responseData.put("stats", stats);

        sendSuccess(resp, responseData);
    }

    private void handleGetReportDetails(int id, HttpServletResponse resp) throws IOException {
        Optional<PlayerReport> reportOpt = plugin.getDatabaseManager().getReportById(id);
        if (reportOpt.isEmpty()) {
            sendError(resp, 404, "Жалоба #" + id + " не найдена");
            return;
        }

        PlayerReport report = reportOpt.get();
        Map<String, Object> reportData = toReportMap(report);
        boolean suspicious = plugin.getDatabaseManager().isPlayerSuspicious(report.targetUuid());
        int activeCount = plugin.getDatabaseManager().getActiveReportsCountForTarget(report.targetUuid());

        // Контекст переписки и логов за 1 минуту до подачи жалобы (60 секунд)
        List<Map<String, Object>> chatLogs = plugin.getDatabaseManager().getSurroundingChatLogs(
            report.reporterName(),
            report.targetName(),
            report.createdAt(),
            60
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("report", reportData);
        result.put("targetSuspicious", suspicious);
        result.put("targetActiveReportsCount", activeCount);
        result.put("chatLogs", chatLogs);

        // Если жалоба привела к бану — прикрепляем информацию о бане
        if (report.linkedBanId() != null && report.linkedBanId() > 0) {
            var banOpt = plugin.getDatabaseManager().getBanById(report.linkedBanId());
            if (banOpt.isPresent()) {
                var b = banOpt.get();
                Map<String, Object> banMap = new LinkedHashMap<>();
                banMap.put("id", b.id());
                banMap.put("ruleReason", b.ruleReason());
                banMap.put("creatorName", b.creatorName());
                banMap.put("status", b.status());
                banMap.put("createdAt", b.createdAt());
                result.put("linkedBan", banMap);
            }
        }

        sendSuccess(resp, result);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_REPORTS);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.length() <= 1) {
            sendError(resp, 400, "Укажите ID жалобы и действие");
            return;
        }

        // /api/reports/{id}/accept  или  /api/reports/{id}/reject
        String[] parts = pathInfo.substring(1).split("/");
        if (parts.length < 2) {
            sendError(resp, 400, "Некорректный маршрут действия с жалобой");
            return;
        }

        int id;
        try {
            id = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            sendError(resp, 400, "Некорректный ID жалобы");
            return;
        }

        String action = parts[1].toLowerCase();
        String adminName = sessionOpt.get().adminUsername();

        switch (action) {
            case "accept" -> {
                boolean ok = plugin.getReportManager().acceptReport(id, adminName);
                if (ok) {
                    sendSuccess(resp, Map.of("message", "Жалоба #" + id + " успешно принята, нарушитель помечен, отправителю начислена репутация"));
                } else {
                    sendError(resp, 500, "Не удалось принять жалобу #" + id);
                }
            }
            case "reject" -> {
                boolean ok = plugin.getReportManager().rejectReport(id, adminName);
                if (ok) {
                    sendSuccess(resp, Map.of("message", "Жалоба #" + id + " помечена как ложная. Репутация возвращена цели"));
                } else {
                    sendError(resp, 500, "Не удалось отклонить жалобу #" + id);
                }
            }
            default -> sendError(resp, 400, "Неизвестное действие: " + action);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_REPORTS);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.length() <= 1) {
            sendError(resp, 400, "Не указан ID жалобы");
            return;
        }

        String idStr = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        try {
            int id = Integer.parseInt(idStr);
            boolean ok = plugin.getReportManager().deleteReport(id, sessionOpt.get().adminUsername());
            if (ok) {
                sendSuccess(resp, Map.of("message", "Жалоба #" + id + " удалена"));
            } else {
                sendError(resp, 404, "Жалоба #" + id + " не найдена");
            }
        } catch (NumberFormatException e) {
            sendError(resp, 400, "Некорректный ID жалобы");
        }
    }

    private Map<String, Object> toReportMap(PlayerReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("reporterUuid", r.reporterUuid());
        m.put("reporterName", r.reporterName());
        m.put("reporterIp", r.reporterIp());
        m.put("targetUuid", r.targetUuid());
        m.put("targetName", r.targetName());
        m.put("reasons", r.reasons());
        m.put("description", (r.description() != null && !r.description().isBlank()) ? r.description() : "не указано");
        m.put("isRecent", r.isRecent());
        m.put("createdAt", r.createdAt());
        m.put("status", r.status());
        m.put("resolvedBy", r.resolvedBy());
        m.put("resolvedAt", r.resolvedAt());
        m.put("reputationDeducted", r.reputationDeducted());
        m.put("linkedBanId", r.linkedBanId());
        return m;
    }
}
