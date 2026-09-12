package me.lovelace.loveWebAdmin.api.events;

import me.lovelace.loveWebAdmin.models.PlayerReport;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Событие подачи новой внутриигровой жалобы (/репорт, /жалоба).
 */
public class PlayerReportCreatedEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PlayerReport report;
    private boolean cancelled;

    public PlayerReportCreatedEvent(PlayerReport report) {
        super(true);
        this.report = report;
    }

    public PlayerReport getReport() {
        return report;
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
