package me.lovelace.loveWebAdmin.models;

import dev.lovelace.lovecore.api.tickets.MessageSource;

public record TicketMessage(
        long id,
        long ticketId,
        String authorName,
        String body,
        MessageSource source,
        long createdAt
) {}
