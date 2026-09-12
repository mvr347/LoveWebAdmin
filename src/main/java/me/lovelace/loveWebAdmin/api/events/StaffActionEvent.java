package me.lovelace.loveWebAdmin.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Событие аудита действий стаффа на сервере или в панели управления.
 */
public class StaffActionEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String actor;
    private final String action;
    private final String details;

    public StaffActionEvent(String actor, String action, String details) {
        super(true);
        this.actor = actor;
        this.action = action;
        this.details = details;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getDetails() {
        return details;
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
