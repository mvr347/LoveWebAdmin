package me.lovelace.loveWebAdmin.models;

import dev.lovelace.lovecore.api.tickets.TicketStatus;
import dev.lovelace.lovecore.api.tickets.TicketType;

import java.util.UUID;

public record Ticket(
        long id,
        TicketType type,
        TicketStatus status,
        UUID playerUuid,
        String playerName,
        String subject,
        UUID targetUuid,
        String targetName,
        String discordChannelId,
        long createdAt,
        long closedAt
) {}
