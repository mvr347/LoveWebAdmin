package me.lovelace.loveWebAdmin.managers;

import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.*;

/** Delegation is checked against live roles, never the role cached in a session. */
public final class RolePermissionPolicy {
  private RolePermissionPolicy() {}

  public static boolean canDelegate(LoveWebAdmin plugin, String actor, WebRole target) {
    var admin = plugin.getDatabaseManager().getAdminByUsername(actor);
    if (admin.isEmpty()) return false;
    var role = plugin.getDatabaseManager().getRoleById(admin.get().roleId());
    if (role.isEmpty()) return false;
    return role.get().isOwner()
        || (!target.isOwner()
            && (target.lpGroup() == null
                || target.lpGroup().isBlank()
                || Objects.equals(target.lpGroup(), role.get().lpGroup()))
            && role.get().permissions().containsAll(target.permissions()));
  }

  public static boolean owner(LoveWebAdmin plugin, String actor) {
    return plugin
        .getDatabaseManager()
        .getAdminByUsername(actor)
        .flatMap(a -> plugin.getDatabaseManager().getRoleById(a.roleId()))
        .map(WebRole::isOwner)
        .orElse(false);
  }

  public static Set<Permission> withNewDependencies(
      Set<Permission> requested, Set<Permission> previous) {
    var result = new LinkedHashSet<>(requested);
    var pairs =
        Map.ofEntries(
            Map.entry(Permission.MANAGE_BANS, Permission.VIEW_BANS),
            Map.entry(Permission.MANAGE_REPORTS, Permission.VIEW_REPORTS),
            Map.entry(Permission.MANAGE_APPEALS, Permission.VIEW_APPEALS),
            Map.entry(Permission.MANAGE_VESUVIO, Permission.VIEW_VESUVIO),
            Map.entry(Permission.VIEW_VESUVIO_ADVANCED, Permission.VIEW_VESUVIO),
            Map.entry(Permission.REVIEW_VESUVIO_SAMPLES, Permission.VIEW_VESUVIO_ADVANCED),
            Map.entry(Permission.MANAGE_VESUVIO_MODELS, Permission.VIEW_VESUVIO_ADVANCED),
            Map.entry(Permission.MANAGE_LOVEAUTH, Permission.VIEW_PLAYERS),
            Map.entry(Permission.KICK_PLAYERS, Permission.VIEW_PLAYERS),
            Map.entry(Permission.FREEZE_PLAYERS, Permission.VIEW_PLAYERS),
            Map.entry(Permission.MANAGE_ECONOMY, Permission.VIEW_ECONOMY));
    var newlyRequired = new HashSet<>(requested);
    newlyRequired.removeAll(previous);
    boolean changed;
    do {
      changed = false;
      for (var pair : pairs.entrySet())
        if (result.contains(pair.getKey())
            && (!previous.contains(pair.getKey())
                || previous.contains(pair.getValue())
                || newlyRequired.contains(pair.getKey()))) {
          changed |= result.add(pair.getValue());
          if (newlyRequired.contains(pair.getKey())) changed |= newlyRequired.add(pair.getValue());
        }
    } while (changed);
    return result;
  }
}
