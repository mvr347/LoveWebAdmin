package me.lovelace.loveWebAdmin.utils;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Утилиты для создания голов игроков с Base64 текстурами (Paper API).
 */
public final class HeadUtils {

    private HeadUtils() {}

    /**
     * Создает голову с кастомной Base64 текстурой.
     */
    public static ItemStack createCustomHead(String base64, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) return item;

        if (base64 != null && !base64.isBlank()) {
            try {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new ProfileProperty("textures", base64));
                meta.setPlayerProfile(profile);
            } catch (Throwable t) {
                Bukkit.getLogger().fine("[LoveWebAdmin] Fallback setting head texture: " + t.getMessage());
            }
        }

        if (displayName != null) {
            Component nameComp = LegacyComponentSerializer.legacyAmpersand().deserialize(displayName)
                .decoration(TextDecoration.ITALIC, false);
            meta.displayName(nameComp);
        }

        if (lore != null && !lore.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>();
            for (String line : lore) {
                loreComponents.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line)
                    .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(loreComponents);
        }

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Создает голову существующего игрока по его OfflinePlayer.
     */
    public static ItemStack createPlayerHead(OfflinePlayer player, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) return item;

        if (player != null) {
            meta.setOwningPlayer(player);
        }

        if (displayName != null) {
            Component nameComp = LegacyComponentSerializer.legacyAmpersand().deserialize(displayName)
                .decoration(TextDecoration.ITALIC, false);
            meta.displayName(nameComp);
        }

        if (lore != null && !lore.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>();
            for (String line : lore) {
                loreComponents.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line)
                    .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(loreComponents);
        }

        item.setItemMeta(meta);
        return item;
    }
}
