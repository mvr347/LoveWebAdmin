package me.lovelace.loveWebAdmin.listeners;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Блокирует действия замороженных игроков (перемещение, взаимодействие, ломание блоков, дроп
 * вещей).
 */
public class PlayerFreezeListener implements Listener {

  private final LoveWebAdmin plugin;

  public PlayerFreezeListener(LoveWebAdmin plugin) {
    this.plugin = plugin;
  }

  private boolean frozen(Player player) {
    if (!plugin.getFreezeManager().isFrozen(player)) return false;
    var auth = org.bukkit.Bukkit.getPluginManager().getPlugin("LoveAuth");
    if (auth != null && auth.isEnabled())
      try {
        var manager = auth.getClass().getMethod("getAuthManager").invoke(auth);
        // During login LoveAuth owns movement, commands and its password GUI.
        return Boolean.TRUE.equals(
            manager
                .getClass()
                .getMethod("isAuthenticated", java.util.UUID.class)
                .invoke(manager, player.getUniqueId()));
      } catch (ReflectiveOperationException unavailable) {
        /* Preserve the restriction. */
      }
    return true;
  }

  private boolean authTeleport(Player player) {
    var auth = org.bukkit.Bukkit.getPluginManager().getPlugin("LoveAuth");
    if (auth == null || !auth.isEnabled()) return false;
    try {
      var limbo = auth.getClass().getMethod("getLimboManager").invoke(auth);
      return Boolean.TRUE.equals(
          limbo
              .getClass()
              .getMethod("isTeleportBypassed", java.util.UUID.class)
              .invoke(limbo, player.getUniqueId()));
    } catch (ReflectiveOperationException unavailable) {
      return false;
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    Player player = event.getPlayer();
    if (!frozen(player)) return;

    Location from = event.getFrom();
    Location to = event.getTo();
    if (to == null) return;

    if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
      event.setTo(
          new Location(
              from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
      player.sendActionBar(Component.text("§c§lВЫ ЗАМОРОЖЕНЫ ДЛЯ ПРОВЕРКИ АДМИНИСТРАТОРОМ!"));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    if (frozen(event.getPlayer())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    if (frozen(event.getPlayer())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockPlace(BlockPlaceEvent event) {
    if (frozen(event.getPlayer())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onDrop(PlayerDropItemEvent event) {
    if (frozen(event.getPlayer())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onDamage(EntityDamageByEntityEvent event) {
    if (event.getDamager() instanceof Player player && frozen(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryOpen(InventoryOpenEvent event) {
    if (event.getPlayer() instanceof Player player && frozen(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    if (plugin.getFreezeManager().isFrozen(player)) {
      plugin
          .getLogManager()
          .logWebAction(
              "Античит/Модерация",
              "ВНИМАНИЕ: Замороженный игрок "
                  + player.getName()
                  + " покинул сервер во время проверки!");
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onAnyDamage(org.bukkit.event.entity.EntityDamageEvent e) {
    if (e.getEntity() instanceof Player p && frozen(p)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityInteract(org.bukkit.event.player.PlayerInteractEntityEvent e) {
    if (frozen(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent e) {
    if (frozen(e.getPlayer()) && !authTeleport(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent e) {
    if (e.getWhoClicked() instanceof Player p && frozen(p)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
    if (e.getWhoClicked() instanceof Player p && frozen(p)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
    if (frozen(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onCommand(PlayerCommandPreprocessEvent e) {
    if (!frozen(e.getPlayer())) return;
    String command =
        e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT);
    int colon = command.indexOf(':');
    if (colon >= 0) command = command.substring(colon + 1);
    if (!java.util.Set.of("discord", "дискорд", "report", "жалоба").contains(command))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player && frozen(player)) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onProjectile(org.bukkit.event.entity.ProjectileLaunchEvent event) {
    if (event.getEntity().getShooter() instanceof Player player && frozen(player))
      event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onProjectileDamage(EntityDamageByEntityEvent event) {
    if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
        && projectile.getShooter() instanceof Player player
        && frozen(player)) event.setCancelled(true);
  }
}
