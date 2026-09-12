package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Читает и сериализует содержимое инвентаря и EnderChest игрока,
 * а также выполняет операции изъятия предметов и очистки в главном потоке.
 */
public class PlayerInventoryManager {

    private final LoveWebAdmin plugin;

    public PlayerInventoryManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Map<String, Object>> getInventoryData(Player player) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                if (!player.isOnline()) {
                    future.complete(Map.of("error", "Игрок не в сети"));
                    return;
                }

                Map<String, Object> data = new LinkedHashMap<>();
                PlayerInventory inv = player.getInventory();

                // 1. Основной инвентарь (36 слотов: 0-8 хотбар, 9-35 основные)
                List<Map<String, Object>> storage = new ArrayList<>();
                ItemStack[] storageContents = inv.getStorageContents();
                for (int i = 0; i < storageContents.length; i++) {
                    storage.add(serializeItem(storageContents[i], i));
                }
                data.put("storage", storage);

                // 2. Броня (Шлем, Нагрудник, Поножи, Ботинки)
                Map<String, Object> armor = new LinkedHashMap<>();
                armor.put("helmet", serializeItem(inv.getHelmet(), 39));
                armor.put("chestplate", serializeItem(inv.getChestplate(), 38));
                armor.put("leggings", serializeItem(inv.getLeggings(), 37));
                armor.put("boots", serializeItem(inv.getBoots(), 36));
                data.put("armor", armor);

                // 3. Оффхенд (вторая рука)
                data.put("offhand", serializeItem(inv.getItemInOffHand(), 40));

                // 4. Эндер-сундук (27 слотов)
                List<Map<String, Object>> enderChest = new ArrayList<>();
                ItemStack[] ecContents = player.getEnderChest().getContents();
                for (int i = 0; i < ecContents.length; i++) {
                    enderChest.add(serializeItem(ecContents[i], i));
                }
                data.put("enderchest", enderChest);

                // Дополнительные параметры состояния
                data.put("health", Math.round(player.getHealth() * 10.0) / 10.0);
                data.put("maxHealth", player.getMaxHealth());
                data.put("foodLevel", player.getFoodLevel());
                data.put("level", player.getLevel());
                data.put("exp", Math.round(player.getExp() * 100.0) / 100.0);
                data.put("gameMode", player.getGameMode().name());
                data.put("isFrozen", plugin.getFreezeManager().isFrozen(player));

                future.complete(data);
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка получения инвентаря игрока " + player.getName() + ": " + e.getMessage());
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> removeItem(Player player, String containerType, int slot, String moderator) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                if (!player.isOnline()) {
                    future.complete(false);
                    return;
                }

                String removedItemName = "предмет";
                if ("enderchest".equalsIgnoreCase(containerType)) {
                    ItemStack item = player.getEnderChest().getItem(slot);
                    if (item != null && item.getType() != Material.AIR) {
                        removedItemName = item.getType().name() + " x" + item.getAmount();
                        player.getEnderChest().setItem(slot, null);
                    }
                } else if ("armor".equalsIgnoreCase(containerType)) {
                    PlayerInventory inv = player.getInventory();
                    switch (slot) {
                        case 39 -> {
                            if (inv.getHelmet() != null) removedItemName = inv.getHelmet().getType().name();
                            inv.setHelmet(null);
                        }
                        case 38 -> {
                            if (inv.getChestplate() != null) removedItemName = inv.getChestplate().getType().name();
                            inv.setChestplate(null);
                        }
                        case 37 -> {
                            if (inv.getLeggings() != null) removedItemName = inv.getLeggings().getType().name();
                            inv.setLeggings(null);
                        }
                        case 36 -> {
                            if (inv.getBoots() != null) removedItemName = inv.getBoots().getType().name();
                            inv.setBoots(null);
                        }
                    }
                } else if ("offhand".equalsIgnoreCase(containerType)) {
                    if (player.getInventory().getItemInOffHand().getType() != Material.AIR) {
                        removedItemName = player.getInventory().getItemInOffHand().getType().name();
                        player.getInventory().setItemInOffHand(null);
                    }
                } else {
                    ItemStack item = player.getInventory().getItem(slot);
                    if (item != null && item.getType() != Material.AIR) {
                        removedItemName = item.getType().name() + " x" + item.getAmount();
                        player.getInventory().setItem(slot, null);
                    }
                }

                plugin.getLogManager().logWebAction(moderator != null ? moderator : "WebAdmin",
                    "Изъял " + removedItemName + " у игрока " + player.getName() + " (Слот: " + slot + ", " + containerType + ")");
                future.complete(true);
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка удаления предмета: " + e.getMessage());
                future.complete(false);
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> clearInventory(Player player, String moderator) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                if (!player.isOnline()) {
                    future.complete(false);
                    return;
                }

                player.getInventory().clear();
                player.getInventory().setArmorContents(null);
                player.getInventory().setItemInOffHand(null);

                plugin.getLogManager().logWebAction(moderator != null ? moderator : "WebAdmin",
                    "Очистил полный инвентарь игрока " + player.getName());
                future.complete(true);
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка очистки инвентаря: " + e.getMessage());
                future.complete(false);
            }
        });

        return future;
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
            map.put("displayName", displayName != null ? displayName : formatMaterialName(item.getType()));

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
            meta.getEnchants().forEach((enchant, lvl) -> {
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
