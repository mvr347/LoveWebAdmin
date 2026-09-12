package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.tickets.MessageSource;
import dev.lovelace.lovecore.api.tickets.TicketOracle;
import dev.lovelace.lovecore.api.tickets.TicketSnapshot;
import me.lovelace.loveWebAdmin.managers.TicketManager;
import me.lovelace.loveWebAdmin.models.Ticket;

import java.util.Optional;
import java.util.UUID;

/**
 * Registered with LoveCore's ServicesManager so LoveAuth's Discord bot can create tickets,
 * relay Discord messages in, and close/attach a channel - the actual storage lives in
 * {@link TicketManager}/DatabaseManager, this is just the LoveCore-facing adapter.
 */
public final class WebAdminTicketOracle implements TicketOracle {

    private final TicketManager ticketManager;

    public WebAdminTicketOracle(TicketManager ticketManager) {
        this.ticketManager = ticketManager;
    }

    @Override
    public long createTicket(dev.lovelace.lovecore.api.tickets.TicketType type, UUID playerUuid, String playerName,
                              String subject, UUID targetUuid, String targetName) {
        return ticketManager.createTicket(type, playerUuid, playerName, subject, targetUuid, targetName).id();
    }

    @Override
    public void addMessage(long ticketId, String authorName, String body, MessageSource source) {
        ticketManager.addMessage(ticketId, authorName, body, source);
    }

    @Override
    public void setDiscordChannel(long ticketId, String discordChannelId) {
        ticketManager.setDiscordChannel(ticketId, discordChannelId);
    }

    @Override
    public void closeTicket(long ticketId) {
        ticketManager.closeTicket(ticketId);
    }

    @Override
    public Optional<TicketSnapshot> getTicket(long ticketId) {
        return ticketManager.getTicket(ticketId).map(WebAdminTicketOracle::toSnapshot);
    }

    private static TicketSnapshot toSnapshot(Ticket t) {
        return new TicketSnapshot(t.id(), t.type(), t.status(), t.playerUuid(), t.playerName(), t.subject(),
                t.targetUuid(), t.targetName(), t.discordChannelId(), t.createdAt(), t.closedAt());
    }
}
