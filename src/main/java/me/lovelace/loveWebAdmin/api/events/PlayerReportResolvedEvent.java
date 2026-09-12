package me.lovelace.loveWebAdmin.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Событие разрешения / закрытия жалобы администратором.
 */
public class PlayerReportResolvedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int reportId;
    private final String status;
    private final String resolvedBy;
    private final Integer linkedBanId;

    public PlayerReportResolvedEvent(int reportId, String status, String resolvedBy, Integer linkedBanId) {
        super(true);
        this.reportId = reportId;
        this.status = status;
        this.resolvedBy = resolvedBy;
        this.linkedBanId = linkedBanId;
    }

    public int getReportId() {
        return reportId;
    }

    public String getStatus() {
        return status;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public Integer getLinkedBanId() {
        return linkedBanId;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
