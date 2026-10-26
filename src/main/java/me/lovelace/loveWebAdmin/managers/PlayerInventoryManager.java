package me.lovelace.loveWebAdmin.managers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Читает и сериализует содержимое инвентаря и EnderChest игрока, а также выполняет операции изъятия
 * предметов и очистки в главном потоке.
 */
public class PlayerInventoryManager {

  private final LoveWebAdmin plugin;

  public PlayerInventoryManager(LoveWebAdmin plugin) {
    this.plugin = plugin;
  }

  public static String fingerprint(ItemStack item) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256").digest(item.serializeAsBytes()));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public CompletableFuture<Map<String, Object>> getInventoryData(Player player) {
    return plugin
        .main()
        .call(
            () -> {
              if (!player.isOnline()) throw new IllegalArgumentException("Игрок не в сети");
              Map<String, Object> data = new LinkedHashMap<>();
              var inv = player.getInventory();
              var storage = new ArrayList<Map<String, Object>>();
              for (int slot = 0; slot < 36; slot++)
                storage.add(serializeItem(inv.getItem(slot), slot));
              data.put("storage", storage);
              data.put(
                  "armor",
                  Map.of(
                      "helmet",
                      serializeItem(inv.getHelmet(), 39),
                      "chestplate",
                      serializeItem(inv.getChestplate(), 38),
                      "leggings",
                      serializeItem(inv.getLeggings(), 37),
                      "boots",
                      serializeItem(inv.getBoots(), 36)));
              data.put("offhand", serializeItem(inv.getItemInOffHand(), 40));
              var ender = new ArrayList<Map<String, Object>>();
              for (int slot = 0; slot < 27; slot++)
                ender.add(serializeItem(player.getEnderChest().getItem(slot), slot));
              data.put("enderchest", ender);
              data.put("health", player.getHealth());
              data.put("maxHealth", player.getMaxHealth());
              data.put("foodLevel", player.getFoodLevel());
              data.put("level", player.getLevel());
              data.put("exp", player.getExp());
              data.put("gameMode", player.getGameMode().name());
              data.put("isFrozen", plugin.getFreezeManager().isFrozen(player));
              return data;
            });
  }

  /** Kept for source compatibility. Web clients must provide the fingerprint overload. */
  public CompletableFuture<Boolean> removeItem(
      Player player, String container, int slot, String moderator) {
    return CompletableFuture.failedFuture(
        new IllegalArgumentException("Обновите инвентарь и передайте fingerprint предмета"));
  }

  public CompletableFuture<Boolean> removeItem(
      Player player, String container, int slot, String moderator, String expected) {
    return removeItem(player, container, slot, moderator, expected, "Изъятие через API WebAdmin");
  }

  public CompletableFuture<Boolean> removeItem(
      Player player, String container, int slot, String moderator, String expected, String reason) {
    return plugin
        .main()
        .call(
            () -> {
              if (!player.isOnline() || player.isDead()) return false;
              me.lovelace.loveWebAdmin.integration.GameplayPolicy.check(player);
              org.bukkit.inventory.Inventory inv;
              switch (container) {
                case "enderchest" -> {
                  if (slot < 0 || slot >= 27)
                    throw new IllegalArgumentException("Недопустимый слот");
                  inv = player.getEnderChest();
                }
                case "armor" -> {
                  if (slot < 36 || slot > 39)
                    throw new IllegalArgumentException("Недопустимый слот");
                  inv = player.getInventory();
                }
                case "offhand" -> {
                  slotCheck(slot, 40, 40);
                  inv = player.getInventory();
                }
                case "storage" -> {
                  slotCheck(slot, 0, 35);
                  inv = player.getInventory();
                }
                default -> throw new IllegalArgumentException("Неизвестный контейнер");
              }
              ItemStack item = inv.getItem(slot);
              if (item == null
                  || item.getType().isAir()
                  || expected == null
                  || !fingerprint(item).equals(expected)) return false;
              try {
                String id =
                    plugin
                        .moderation()
                        .evidence(
                            player.getUniqueId().toString(),
                            moderator,
                            item.serializeAsBytes(),
                            reason);
                ItemStack before = item.clone();
                try {
                  inv.setItem(slot, null);
                  player.saveData();
                } catch (RuntimeException error) {
                  ItemStack now = inv.getItem(slot);
                  if (now == null || now.getType().isAir()) inv.setItem(slot, before);
                  throw error;
                }
                plugin.moderation().evidenceState(id, "PENDING", "HELD");
                plugin
                    .getLogManager()
                    .logWebAction(
                        moderator,
                        "Изъял "
                            + item.getType()
                            + " x"
                            + item.getAmount()
                            + " у "
                            + player.getName()
                            + "; запись "
                            + id);
                return true;
              } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Ошибка хранилища изъятых предметов", e);
              }
            });
  }

  private static void slotCheck(int slot, int min, int max) {
    if (slot < min || slot > max) throw new IllegalArgumentException("Недопустимый слот");
  }

  public CompletableFuture<Boolean> clearInventory(Player player, String moderator) {
    return plugin
        .main()
        .call(
            () -> {
              if (!player.isOnline() || player.isDead()) return false;
              me.lovelace.loveWebAdmin.integration.GameplayPolicy.check(player);
              var snapshot =
                  new java.util.ArrayList<
                      me.lovelace.loveWebAdmin.database.ModerationStore.Evidence>();
              int totalBytes = 0;
              for (int slot = 0; slot <= 40; slot++) {
                ItemStack item = player.getInventory().getItem(slot);
                if (item == null || item.getType().isAir()) continue;
                byte[] bytes = item.serializeAsBytes();
                totalBytes += bytes.length;
                if (totalBytes > 8 * 1024 * 1024)
                  throw new IllegalArgumentException(
                      "Снимок превышает 8 МиБ. Изымайте предметы по отдельности");
                snapshot.add(
                    new me.lovelace.loveWebAdmin.database.ModerationStore.Evidence(
                        java.util.UUID.randomUUID().toString(),
                        player.getUniqueId().toString(),
                        moderator,
                        bytes));
              }
              if (snapshot.isEmpty()) return true;
              try {
                plugin.moderation().evidenceBatch(snapshot);
                ItemStack[] before = player.getInventory().getContents();
                for (int i = 0; i < before.length; i++)
                  if (before[i] != null) before[i] = before[i].clone();
                try {
                  player.getInventory().clear();
                  player.saveData();
                } catch (RuntimeException error) {
                  player.getInventory().setContents(before);
                  throw error;
                }
                plugin.moderation().holdBatch(snapshot);
                plugin
                    .getLogManager()
                    .logWebAction(
                        moderator,
                        "Изъял содержимое инвентаря "
                            + player.getName()
                            + " (стопок: "
                            + snapshot.size()
                            + ")");
              } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Не удалось сохранить снимок инвентаря", e);
              }
              return true;
            });
  }

  public CompletableFuture<Boolean> restore(Player player, String id, String moderator) {
    return plugin
        .main()
        .call(
            () -> {
              if (!player.isOnline() || player.isDead()) return false;
              me.lovelace.loveWebAdmin.integration.GameplayPolicy.check(player);
              try {
                var bytes = plugin.moderation().evidenceItem(id, player.getUniqueId().toString());
                if (bytes.isEmpty()) return false;
                ItemStack item = ItemStack.deserializeBytes(bytes.get());
                // An empty storage slot guarantees the whole original stack fits without dropping
                // anything.
                int slot = player.getInventory().firstEmpty();
                if (slot < 0 || slot >= 36) return false;
                if (!plugin.moderation().evidenceState(id, "HELD", "RETURNING")) return false;
                player.getInventory().setItem(slot, item);
                player.saveData();
                if (!plugin.moderation().confirmReturn(id, moderator))
                  throw new IllegalStateException("Возврат требует проверки персоналом");
                plugin
                    .getLogManager()
                    .logWebAction(
                        moderator, "Вернул изъятое игроку " + player.getName() + "; запись " + id);
                return true;
              } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
              }
            });
  }

  private Map<String, Object> serializeItem(ItemStack item, int slot) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("slot", slot);

    if (item == null || item.getType() == Material.AIR) {
      map.put("empty", true);
      map.put("material", "AIR");
      map.put("amount", 0);
      return map;
    }

    map.put("empty", false);
    map.put("fingerprint", fingerprint(item));
    map.put("material", item.getType().name());
    map.put("amount", item.getAmount());

    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      // Название
      String displayName = null;
      if (meta.hasDisplayName()) {
        if (meta.displayName() != null) {
          displayName = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        } else {
          displayName = meta.getDisplayName();
        }
      }
      map.put(
          "displayName", displayName != null ? displayName : formatMaterialName(item.getType()));

      // Описание (Lore)
      List<String> lore = new ArrayList<>();
      if (meta.hasLore()) {
        if (meta.lore() != null) {
          for (var component : meta.lore()) {
            lore.add(PlainTextComponentSerializer.plainText().serialize(component));
          }
        } else if (meta.getLore() != null) {
          lore.addAll(meta.getLore());
        }
      }
      map.put("lore", lore);

      // Зачарования
      Map<String, Integer> enchants = new LinkedHashMap<>();
      meta.getEnchants()
          .forEach(
              (enchant, lvl) -> {
                String name = enchant.getKey().getKey().toUpperCase();
                enchants.put(name, lvl);
              });
      map.put("enchantments", enchants);

      // Прочность
      if (meta instanceof Damageable dmg) {
        int damage = dmg.getDamage();
        int maxDurability = item.getType().getMaxDurability();
        map.put("damage", damage);
        map.put("maxDurability", maxDurability);
        map.put("durabilityLeft", Math.max(0, maxDurability - damage));
      }

      map.put("isUnbreakable", meta.isUnbreakable());
      if (meta.hasCustomModelData()) {
        map.put("customModelData", meta.getCustomModelData());
      }
    } else {
      map.put("displayName", formatMaterialName(item.getType()));
      map.put("lore", List.of());
      map.put("enchantments", Map.of());
    }

    return map;
  }

  private String formatMaterialName(Material material) {
    String[] parts = material.name().toLowerCase().split("_");
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (!part.isEmpty()) {
        sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(" ");
      }
    }
    return sb.toString().trim();
  }
}
