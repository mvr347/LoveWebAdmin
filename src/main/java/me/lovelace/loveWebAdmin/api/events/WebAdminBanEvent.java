package me.lovelace.loveWebAdmin.api.events;

import me.lovelace.loveWebAdmin.models.WebBan;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Событие выдачи бана через систему LoveWebAdmin.
 */
public class WebAdminBanEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String targetName;
    private final String reason;
    private final String creatorName;
    private final boolean isIpBan;
    private final Integer linkedReportId;
    private WebBan createdBan;
    private boolean cancelled;

    public WebAdminBanEvent(String targetName, String reason, String creatorName, boolean isIpBan, Integer linkedReportId) {
        super(true); // асинхронное событие
        this.targetName = targetName;
        this.reason = reason;
        this.creatorName = creatorName;
        this.isIpBan = isIpBan;
        this.linkedReportId = linkedReportId;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getReason() {
        return reason;
    }

    public String getCreatorName() {
        return creatorName;
    }

    public boolean isIpBan() {
        return isIpBan;
    }

    public Integer getLinkedReportId() {
        return linkedReportId;
    }

    public WebBan getCreatedBan() {
        return createdBan;
    }

    public void setCreatedBan(WebBan createdBan) {
        this.createdBan = createdBan;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
