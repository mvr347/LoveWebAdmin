package me.lovelace.loveWebAdmin.managers;

import dev.lovelace.lovecore.api.tickets.MessageSource;
import dev.lovelace.lovecore.api.tickets.TicketClosedEvent;
import dev.lovelace.lovecore.api.tickets.TicketCreatedEvent;
import dev.lovelace.lovecore.api.tickets.TicketMessageAddedEvent;
import dev.lovelace.lovecore.api.tickets.TicketType;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Ticket;
import me.lovelace.loveWebAdmin.models.TicketMessage;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for tickets (appeals/support/report): owns the DB writes and fires the
 * LoveCore events LoveAuth's Discord bot listens for. Firing an event with zero listeners
 * (LoveCore/LoveAuth not installed) is a harmless no-op via Bukkit's own HandlerList - same
 * assumption the rest of this ecosystem already makes about LoveCore's classes being present
 * (see e.g. ItemDropLossListener#terriblePolitenessMultiplier in LoveTweaks).
 */
public final class TicketManager {

    private final LoveWebAdmin plugin;

    public TicketManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    /** subject doubles as the ticket's opening message. targetUuid/targetName required only for REPORT. */
    public Ticket createTicket(TicketType type, UUID playerUuid, String playerName, String subject,
                                UUID targetUuid, String targetName) {
        long id = plugin.getDatabaseManager().insertTicket(type, playerUuid, playerName, subject, targetUuid, targetName);
        plugin.getDatabaseManager().insertTicketMessage(id, playerName, subject, MessageSource.PANEL);
        Bukkit.getPluginManager().callEvent(
                new TicketCreatedEvent(id, type, playerUuid, playerName, subject, targetUuid, targetName));
        return plugin.getDatabaseManager().getTicket(id).orElseThrow();
    }

    public void addMessage(long ticketId, String authorName, String body, MessageSource source) {
        plugin.getDatabaseManager().insertTicketMessage(ticketId, authorName, body, source);
        Bukkit.getPluginManager().callEvent(new TicketMessageAddedEvent(ticketId, authorName, body, source));
    }

    public void setDiscordChannel(long ticketId, String discordChannelId) {
        plugin.getDatabaseManager().setTicketDiscordChannel(ticketId, discordChannelId);
    }

    public void closeTicket(long ticketId) {
        plugin.getDatabaseManager().closeTicket(ticketId);
        Bukkit.getPluginManager().callEvent(new TicketClosedEvent(ticketId));
    }

    public void reopenTicket(long ticketId) {
        plugin.getDatabaseManager().reopenTicket(ticketId);
    }

    public Optional<Ticket> getTicket(long ticketId) {
        return plugin.getDatabaseManager().getTicket(ticketId);
    }

    public List<TicketMessage> getMessages(long ticketId) {
        return plugin.getDatabaseManager().getTicketMessages(ticketId);
    }

    /** statusFilter — "OPEN"/"CLOSED", либо null для всех. */
    public List<Ticket> getAllTickets(String statusFilter) {
        return plugin.getDatabaseManager().getAllTickets(statusFilter);
    }
}
