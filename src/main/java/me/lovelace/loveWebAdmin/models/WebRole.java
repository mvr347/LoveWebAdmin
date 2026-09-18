package me.lovelace.loveWebAdmin.models;

import java.util.Set;

public record WebRole(
    int id,
    String name,
    String description,
    String category,
    int sortOrder,
    String lpGroup,
    Set<Permission> permissions,
    boolean isOwner,
    String color
) {
    public WebRole(int id, String name, String lpGroup, Set<Permission> permissions, boolean isOwner, String color) {
        this(id, name, "", "CUSTOM", 50, lpGroup, permissions, isOwner, color);
    }

    public WebRole(int id, String name, String lpGroup, Set<Permission> permissions, boolean isOwner) {
        this(id, name, "", "CUSTOM", 50, lpGroup, permissions, isOwner, "#8b5cf6");
    }
}
