package me.lovelace.loveWebAdmin.web.handlers;

import dev.lovelace.lovecore.api.tickets.MessageSource;
import dev.lovelace.lovecore.api.tickets.TicketType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.Ticket;
import me.lovelace.loveWebAdmin.models.TicketMessage;
import me.lovelace.loveWebAdmin.models.WebSession;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * GET  /api/tickets?status=OPEN|CLOSED   — список тикетов (VIEW_TICKETS)
 * GET  /api/tickets/{id}                 — тикет + переписка (VIEW_TICKETS)
 * POST /api/tickets/public               — подать апелляцию, БЕЗ авторизации { playerName, subject }
 * POST /api/tickets/{id}/reply           — ответить в переписке (MANAGE_TICKETS) { message }
 * POST /api/tickets/{id}/close           — закрыть тикет (MANAGE_TICKETS)
 * POST /api/tickets/{id}/reopen          — переоткрыть тикет (MANAGE_TICKETS)
 *
 * Только апелляции (TicketType.APPEAL) пока подаются через публичную форму — Support/Report
 * заведены в модели данных, но собственных форм ещё не имеют (следующий шаг).
 */
public class ApiTicketsHandler extends ApiHandlerSupport {

    private static final int MAX_SUBJECT_LENGTH = 2000;
    private static final int MAX_NAME_LENGTH = 32;

    public ApiTicketsHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            handleList(req, resp);
            return;
        }
        String[] parts = pathInfo.substring(1).split("/");
        Optional<Long> ticketId = parseId(parts[0]);
        if (parts.length == 1 && ticketId.isPresent()) {
            handleGetOne(req, resp, ticketId.get());
            return;
        }
        sendError(resp, 404, "Не найдено");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        if ("/public".equals(pathInfo)) {
            handlePublicCreate(req, resp);
            return;
        }
        if (pathInfo == null) {
            sendError(resp, 404, "Не найдено");
            return;
        }
        String[] parts = pathInfo.substring(1).split("/");
        Optional<Long> ticketId = parts.length >= 1 ? parseId(parts[0]) : Optional.empty();
        if (ticketId.isEmpty()) {
            sendError(resp, 404, "Не найдено");
            return;
        }
        if (parts.length == 2 && "reply".equals(parts[1])) {
            handleReply(req, resp, ticketId.get());
        } else if (parts.length == 2 && "close".equals(parts[1])) {
            handleClose(req, resp, ticketId.get());
        } else if (parts.length == 2 && "reopen".equals(parts[1])) {
            handleReopen(req, resp, ticketId.get());
        } else {
            sendError(resp, 404, "Не найдено");
        }
    }

    private void handleList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_TICKETS);
        if (sessionOpt.isEmpty()) return;

        String status = req.getParameter("status");
        List<Ticket> tickets = plugin.getTicketManager().getAllTickets(status);
        sendSuccess(resp, tickets.stream().map(this::toTicketMap).toList());
    }

    private void handleGetOne(HttpServletRequest req, HttpServletResponse resp, long id) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_TICKETS);
        if (sessionOpt.isEmpty()) return;

        Optional<Ticket> ticketOpt = plugin.getTicketManager().getTicket(id);
        if (ticketOpt.isEmpty()) {
            sendError(resp, 404, "Тикет не найден");
            return;
        }
        Map<String, Object> data = toTicketMap(ticketOpt.get());
        List<TicketMessage> messages = plugin.getTicketManager().getMessages(id);
        data.put("messages", messages.stream().map(this::toMessageMap).toList());
        sendSuccess(resp, data);
    }

    private void handlePublicCreate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readJsonBody(req);
        String playerName = trimmedOrNull(stringOrNull(body.get("playerName")));
        String subject = trimmedOrNull(stringOrNull(body.get("subject")));

        if (playerName == null || playerName.isBlank() || playerName.length() > MAX_NAME_LENGTH) {
            sendError(resp, 400, "Укажите корректный ник игрока");
            return;
        }
        if (subject == null || subject.isBlank()) {
            sendError(resp, 400, "Опишите причину апелляции");
            return;
        }
        if (subject.length() > MAX_SUBJECT_LENGTH) {
            sendError(resp, 400, "Слишком длинное сообщение (максимум " + MAX_SUBJECT_LENGTH + " символов)");
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        UUID playerUuid = offlinePlayer.getUniqueId();

        Ticket ticket = plugin.getTicketManager().createTicket(
                TicketType.APPEAL, playerUuid, playerName, subject, null, null);
        plugin.getLogManager().logWebAction("PUBLIC", "Подана апелляция #" + ticket.id() + " от " + playerName);
        sendSuccess(resp, toTicketMap(ticket));
    }

    private void handleReply(HttpServletRequest req, HttpServletResponse resp, long id) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_TICKETS);
        if (sessionOpt.isEmpty()) return;
        if (plugin.getTicketManager().getTicket(id).isEmpty()) {
            sendError(resp, 404, "Тикет не найден");
            return;
        }

        Map<String, Object> body = readJsonBody(req);
        String message = trimmedOrNull(stringOrNull(body.get("message")));
        if (message == null || message.isBlank()) {
            sendError(resp, 400, "Сообщение не может быть пустым");
            return;
        }

        plugin.getTicketManager().addMessage(id, sessionOpt.get().adminUsername(), message, MessageSource.PANEL);
        plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(), "Ответил в тикете #" + id);
        sendSuccess(resp, null);
    }

    private void handleClose(HttpServletRequest req, HttpServletResponse resp, long id) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_TICKETS);
        if (sessionOpt.isEmpty()) return;
        if (plugin.getTicketManager().getTicket(id).isEmpty()) {
            sendError(resp, 404, "Тикет не найден");
            return;
        }
        plugin.getTicketManager().closeTicket(id);
        plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(), "Закрыл тикет #" + id);
        sendSuccess(resp, null);
    }

    private void handleReopen(HttpServletRequest req, HttpServletResponse resp, long id) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_TICKETS);
        if (sessionOpt.isEmpty()) return;
        if (plugin.getTicketManager().getTicket(id).isEmpty()) {
            sendError(resp, 404, "Тикет не найден");
            return;
        }
        plugin.getTicketManager().reopenTicket(id);
        plugin.getLogManager().logWebAction(sessionOpt.get().adminUsername(), "Переоткрыл тикет #" + id);
        sendSuccess(resp, null);
    }

    private Map<String, Object> toTicketMap(Ticket t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", t.id());
        map.put("type", t.type().name());
        map.put("status", t.status().name());
        map.put("playerUuid", t.playerUuid().toString());
        map.put("playerName", t.playerName());
        map.put("subject", t.subject());
        map.put("targetUuid", t.targetUuid() == null ? null : t.targetUuid().toString());
        map.put("targetName", t.targetName());
        map.put("discordChannelId", t.discordChannelId());
        map.put("createdAt", t.createdAt());
        map.put("closedAt", t.closedAt());
        return map;
    }

    private Map<String, Object> toMessageMap(TicketMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.id());
        map.put("authorName", m.authorName());
        map.put("body", m.body());
        map.put("source", m.source().name());
        map.put("createdAt", m.createdAt());
        return map;
    }

    private Optional<Long> parseId(String raw) {
        try {
            return Optional.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String trimmedOrNull(String value) {
        return value == null ? null : value.trim();
    }
}
