package me.lovelace.loveWebAdmin.models;

import java.util.Set;

public record WebRole(
    int id,
    String name,
    String lpGroup,
    Set<Permission> permissions,
    boolean isOwner
) {}
