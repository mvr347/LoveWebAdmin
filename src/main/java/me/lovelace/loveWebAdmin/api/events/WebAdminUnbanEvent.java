package me.lovelace.loveWebAdmin.api.events;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Событие снятия бана (разбана) через LoveWebAdmin.
 */
public class WebAdminUnbanEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int banId;
    private final String targetName;
    private final String adminName;
    private boolean cancelled;

    public WebAdminUnbanEvent(int banId, String targetName, String adminName) {
        super(true);
        this.banId = banId;
        this.targetName = targetName;
        this.adminName = adminName;
    }

    public int getBanId() {
        return banId;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getAdminName() {
        return adminName;
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
