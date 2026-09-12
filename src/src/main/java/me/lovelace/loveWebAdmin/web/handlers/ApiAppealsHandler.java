package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.AppealMessage;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;
import me.lovelace.loveWebAdmin.models.WebBanAppeal;
import me.lovelace.loveWebAdmin.models.WebSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API для системы апелляций банов и тикетов Discord:
 * GET  /api/appeals            — Список апелляций (для персонала) или по игроку (?player=...)
 * GET  /api/appeals/{id}       — Детали апелляции и история сообщений (тред)
 * POST /api/appeals            — Публичная подача апелляции заблокированным игроком
 * POST /api/appeals/{id}/reply — Ответ в тред апелляции (персонал или игрок)
 * POST /api/appeals/{id}/approve — Одобрение апелляции, разбан игрока, закрытие тикета
 * POST /api/appeals/{id}/reject  — Отклонение апелляции, закрытие тикета
 */
public class ApiAppealsHandler extends ApiHandlerSupport {

    public ApiAppealsHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();

        if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
            String player = req.getParameter("player");
            if (player != null && !player.isBlank()) {
                handleGetPlayerAppeal(player.trim(), resp);
                return;
            }

            // Для общего списка требуется право просмотра апелляций или банов
            Optional<WebSession> sessionOpt = authenticate(req);
            if (sessionOpt.isEmpty() || !hasViewPermission(sessionOpt.get())) {
                sendError(resp, 403, "Недостаточно прав для просмотра списка апелляций");
                return;
            }

            handleListAppeals(req, resp);
            return;
        }

        // GET /api/appeals/{id}
        String idStr = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        try {
            int id = Integer.parseInt(idStr);
            handleGetAppealDetails(id, req, resp);
        } catch (NumberFormatException e) {
            sendError(resp, 400, "Некорректный ID апелляции");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();

        if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
            handleCreateAppeal(req, resp);
            return;
        }

        String[] parts = splitPath(pathInfo);
        if (parts == null || parts.length == 0) {
            sendError(resp, 404, "Маршрут не найден");
            return;
        }

        int appealId;
        try {
            appealId = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            sendError(resp, 400, "Некорректный ID апелляции");
            return;
        }

        if (parts.length >= 2) {
            String action = parts[1];
            switch (action.toLowerCase()) {
                case "reply" -> handleReply(appealId, req, resp);
                case "approve" -> handleApprove(appealId, req, resp);
                case "reject" -> handleReject(appealId, req, resp);
                default -> sendError(resp, 404, "Неизвестное действие над апелляцией: " + action);
            }
            return;
        }

        sendError(resp, 404, "Маршрут не найден");
    }

    private void handleListAppeals(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String status = req.getParameter("status");
        int limit = 50;
        int offset = 0;

        if (req.getParameter("limit") != null) {
            try {
                limit = Math.min(200, Integer.parseInt(req.getParameter("limit")));
            } catch (NumberFormatException ignored) {}
        }
        if (req.getParameter("offset") != null) {
            try {
                offset = Math.max(0, Integer.parseInt(req.getParameter("offset")));
            } catch (NumberFormatException ignored) {}
        }

        List<WebBanAppeal> appeals = plugin.getDatabaseManager().getAllAppeals(status, limit, offset);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (WebBanAppeal appeal : appeals) {
            mapped.add(toAppealSummaryMap(appeal));
        }

        sendSuccess(resp, Map.of(
            "appeals", mapped,
            "total", mapped.size()
        ));
    }

    private void handleGetPlayerAppeal(String playerName, HttpServletResponse resp) throws IOException {
        Optional<WebBanAppeal> appealOpt = plugin.getDatabaseManager().getLatestAppealForPlayer(playerName);
        if (appealOpt.isEmpty()) {
            sendSuccess(resp, Map.of("hasAppeal", false));
            return;
        }

        WebBanAppeal appeal = appealOpt.get();
        Map<String, Object> data = toAppealDetailMap(appeal);
        data.put("hasAppeal", true);
        sendSuccess(resp, data);
    }

    private void handleGetAppealDetails(int id, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebBanAppeal> appealOpt = plugin.getDatabaseManager().getAppealById(id);
        if (appealOpt.isEmpty()) {
            sendError(resp, 404, "Апелляция не найдена");
            return;
        }

        WebBanAppeal appeal = appealOpt.get();

        // Проверка прав: персонал либо сам игрок
        Optional<WebSession> sessionOpt = authenticate(req);
        String playerParam = req.getParameter("player");
        boolean isStaff = sessionOpt.isPresent() && hasViewPermission(sessionOpt.get());
        boolean isAuthor = playerParam != null && playerParam.equalsIgnoreCase(appeal.playerName());

        if (!isStaff && !isAuthor) {
            sendError(resp, 403, "Доступ ограничен. Укажите ник автора или войдите как персонал.");
            return;
        }

        sendSuccess(resp, toAppealDetailMap(appeal));
    }

    private void handleCreateAppeal(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String playerName = stringOrNull(body.get("playerName"));
        String reason = stringOrNull(body.get("reason"));
        Object banIdObj = body.get("banId");

        if (reason == null || reason.trim().length() < 5) {
            sendError(resp, 400, "Опишите причину апелляции подробнее (минимум 5 символов)");
            return;
        }

        WebBan activeBan = null;
        if (banIdObj != null) {
            try {
                int banId = (int) Double.parseDouble(String.valueOf(banIdObj));
                Optional<WebBan> b = plugin.getDatabaseManager().getBanById(banId);
                if (b.isPresent() && "ACTIVE".equalsIgnoreCase(b.get().status())) {
                    activeBan = b.get();
                }
            } catch (Exception ignored) {}
        }

        if (activeBan == null && playerName != null && !playerName.isBlank()) {
            activeBan = plugin.getDatabaseManager().getActiveBan(playerName.trim()).orElse(null);
        }

        if (activeBan == null) {
            sendError(resp, 400, "Активная блокировка для указанного игрока не найдена");
            return;
        }

        // Проверяем, нет ли уже открытой апелляции
        Optional<WebBanAppeal> existingOpt = plugin.getDatabaseManager().getAppealByBanId(activeBan.id());
        if (existingOpt.isPresent() && "PENDING".equalsIgnoreCase(existingOpt.get().status())) {
            sendError(resp, 400, "По этой блокировке уже есть открытая апелляция на рассмотрении");
            return;
        }

        int appealId = plugin.getDatabaseManager().createAppeal(
            activeBan.id(),
            activeBan.targetUuid(),
            activeBan.targetName(),
            reason.trim()
        );

        if (appealId <= 0) {
            sendError(resp, 500, "Ошибка сохранения апелляции в базу данных");
            return;
        }

        // Добавляем первое сообщение в тред
        plugin.getDatabaseManager().addAppealMessage(appealId, activeBan.targetName(), false, reason.trim());

        Optional<WebBanAppeal> createdOpt = plugin.getDatabaseManager().getAppealById(appealId);
        if (createdOpt.isPresent()) {
            WebBanAppeal created = createdOpt.get();

            // Создаем тикет в Discord через LoveCore
            plugin.getDiscordBridge().createAppealTicket(created);

            plugin.getLogManager().logWebAction(activeBan.targetName(), "Подал апелляцию бана #" + activeBan.id());
            sendSuccess(resp, toAppealDetailMap(created));
        } else {
            sendSuccess(resp, Map.of("id", appealId, "status", "PENDING"));
        }
    }

    private void handleReply(int appealId, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebBanAppeal> appealOpt = plugin.getDatabaseManager().getAppealById(appealId);
        if (appealOpt.isEmpty()) {
            sendError(resp, 404, "Апелляция не найдена");
            return;
        }

        WebBanAppeal appeal = appealOpt.get();
        if (!"PENDING".equalsIgnoreCase(appeal.status())) {
            sendError(resp, 400, "Нельзя отправлять сообщения в закрытую апелляцию");
            return;
        }

        Map<String, Object> body = readJsonBody(req);
        String message = stringOrNull(body.get("message"));
        if (message == null || message.isBlank()) {
            sendError(resp, 400, "Сообщение не может быть пустым");
            return;
        }

        Optional<WebSession> sessionOpt = authenticate(req);
        boolean isStaff = sessionOpt.isPresent() && hasManagePermission(sessionOpt.get());
        String authorName;

        if (isStaff) {
            authorName = sessionOpt.get().adminUsername();
        } else {
            String authorParam = stringOrNull(body.get("authorName"));
            if (authorParam == null || !authorParam.equalsIgnoreCase(appeal.playerName())) {
                sendError(resp, 403, "У вас нет доступа к отправке сообщений в данную апелляцию");
                return;
            }
            authorName = appeal.playerName();
        }

        int msgId = plugin.getDatabaseManager().addAppealMessage(appealId, authorName, isStaff, message.trim());
        if (msgId <= 0) {
            sendError(resp, 500, "Не удалось сохранить сообщение");
            return;
        }

        // Пересылаем в Discord канал тикета
        plugin.getDiscordBridge().sendTicketReply(appeal, authorName, isStaff, message.trim());

        sendSuccess(resp, Map.of(
            "messageId", msgId,
            "authorName", authorName,
            "isStaff", isStaff,
            "message", message.trim(),
            "createdAt", System.currentTimeMillis() / 1000L
        ));
    }

    private void handleApprove(int appealId, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireManagePermission(req, resp);
        if (sessionOpt.isEmpty()) return;

        Optional<WebBanAppeal> appealOpt = plugin.getDatabaseManager().getAppealById(appealId);
        if (appealOpt.isEmpty()) {
            sendError(resp, 404, "Апелляция не найдена");
            return;
        }

        WebBanAppeal appeal = appealOpt.get();
        Map<String, Object> body = readJsonBody(req);
        String verdictMessage = stringOrNull(body.get("verdictMessage"));
        String admin = sessionOpt.get().adminUsername();

        // 1. Разбаниваем игрока
        plugin.getBanManager().unban(appeal.banId(), admin);

        // 2. Обновляем статус апелляции
        plugin.getDatabaseManager().updateAppealStatus(appealId, "APPROVED");

        // 3. Добавляем системное сообщение вердикта
        String finalMsg = (verdictMessage != null && !verdictMessage.isBlank())
            ? "✅ Апелляция ОДОБРЕНА администратором " + admin + ".\n" + verdictMessage.trim()
            : "✅ Апелляция ОДОБРЕНА. Игрок разблокирован на сервере.";
        plugin.getDatabaseManager().addAppealMessage(appealId, admin, true, finalMsg);

        // 4. Синхронизируем с Discord
        plugin.getDiscordBridge().sendTicketReply(appeal, admin, true, finalMsg);
        plugin.getDiscordBridge().closeAppealTicket(appeal, "Апелляция одобрена " + admin);

        plugin.getLogManager().logWebAction(admin, "Одобрил апелляцию #" + appealId + " для игрока " + appeal.playerName());
        sendSuccess(resp, Map.of("status", "APPROVED", "message", "Апелляция одобрена, игрок разбанен"));
    }

    private void handleReject(int appealId, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requireManagePermission(req, resp);
        if (sessionOpt.isEmpty()) return;

        Optional<WebBanAppeal> appealOpt = plugin.getDatabaseManager().getAppealById(appealId);
        if (appealOpt.isEmpty()) {
            sendError(resp, 404, "Апелляция не найдена");
            return;
        }

        WebBanAppeal appeal = appealOpt.get();
        Map<String, Object> body = readJsonBody(req);
        String verdictMessage = stringOrNull(body.get("verdictMessage"));
        String admin = sessionOpt.get().adminUsername();

        // 1. Обновляем статус апелляции
        plugin.getDatabaseManager().updateAppealStatus(appealId, "REJECTED");

        // 2. Добавляем системное сообщение вердикта
        String finalMsg = (verdictMessage != null && !verdictMessage.isBlank())
            ? "❌ Апелляция ОТКЛОНЕНА администратором " + admin + ".\n" + verdictMessage.trim()
            : "❌ Апелляция ОТКЛОНЕНА персоналом сервера. Наказание остаётся в силе.";
        plugin.getDatabaseManager().addAppealMessage(appealId, admin, true, finalMsg);

        // 3. Синхронизируем с Discord
        plugin.getDiscordBridge().sendTicketReply(appeal, admin, true, finalMsg);
        plugin.getDiscordBridge().closeAppealTicket(appeal, "Апелляция отклонена " + admin);

        plugin.getLogManager().logWebAction(admin, "Отклонил апелляцию #" + appealId + " для игрока " + appeal.playerName());
        sendSuccess(resp, Map.of("status", "REJECTED", "message", "Апелляция отклонена"));
    }

    private boolean hasViewPermission(WebSession session) {
        return hasPermission(session, Permission.VIEW_APPEALS) || hasPermission(session, Permission.VIEW_BANS);
    }

    private boolean hasManagePermission(WebSession session) {
        return hasPermission(session, Permission.MANAGE_APPEALS) || hasPermission(session, Permission.MANAGE_BANS);
    }

    private Optional<WebSession> requireManagePermission(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = authenticate(req);
        if (sessionOpt.isEmpty()) {
            sendError(resp, 401, "Необходима авторизация");
            return Optional.empty();
        }
        if (!hasManagePermission(sessionOpt.get())) {
            sendError(resp, 403, "Недостаточно прав (требуется MANAGE_APPEALS или MANAGE_BANS)");
            return Optional.empty();
        }
        return sessionOpt;
    }

    private Map<String, Object> toAppealSummaryMap(WebBanAppeal appeal) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", appeal.id());
        map.put("banId", appeal.banId());
        map.put("playerName", appeal.playerName());
        map.put("playerUuid", appeal.playerUuid());
        map.put("reason", appeal.reason());
        map.put("status", appeal.status());
        map.put("discordChannelId", appeal.discordChannelId());
        map.put("createdAt", appeal.createdAt());
        map.put("updatedAt", appeal.updatedAt());

        // Прикрепляем детали бана если есть
        plugin.getDatabaseManager().getBanById(appeal.banId()).ifPresent(ban -> {
            map.put("banReason", ban.ruleReason());
            map.put("banCreator", ban.creatorName());
            map.put("banStatus", ban.status());
            map.put("banExpiresAt", ban.expiresAt());
        });

        return map;
    }

    private Map<String, Object> toAppealDetailMap(WebBanAppeal appeal) {
        Map<String, Object> map = toAppealSummaryMap(appeal);
        List<AppealMessage> messages = plugin.getDatabaseManager().getAppealMessages(appeal.id());
        List<Map<String, Object>> mappedMsgs = new ArrayList<>();
        for (AppealMessage msg : messages) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", msg.id());
            mm.put("appealId", msg.appealId());
            mm.put("authorName", msg.authorName());
            mm.put("isStaff", msg.isStaff());
            mm.put("message", msg.message());
            mm.put("createdAt", msg.createdAt());
            mappedMsgs.add(mm);
        }
        map.put("messages", mappedMsgs);
        return map;
    }

    private String[] splitPath(String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.isEmpty()) return null;
        String trimmed = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        return trimmed.split("/");
    }
}
