package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * softdepend — если LuckPerms не установлен, всё работает без синхронизации.
 */
public class LuckPermsManager {

    private final LoveWebAdmin plugin;
    private final boolean available;

    public LuckPermsManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
        this.available = plugin.getServer().getPluginManager().getPlugin("LuckPerms") != null;
    }

    public boolean isAvailable() {
        return available;
    }

    private LuckPerms api() {
        return LuckPermsProvider.get();
    }

    public void ensureGroupExists(String groupName) {
        if (!available || groupName == null || groupName.isBlank()) return;
        api().getGroupManager().createAndLoadGroup(groupName.toLowerCase());
    }

    /**
     * Снимает все текущие группы (кроме default) и назначает lpGroup из роли.
     * Должен вызываться из async контекста.
     */
    public void assignGroup(String username, String lpGroup) {
        if (!available || lpGroup == null || lpGroup.isBlank()) return;

        UserManager userManager = api().getUserManager();
        api().getUserManager().lookupUniqueId(username).thenAccept(uuid -> {
            if (uuid == null) {
                plugin.getLogger().warning("LuckPerms: не найден UUID для ника " + username);
                return;
            }
            userManager.loadUser(uuid).thenAccept(user -> applyGroup(userManager, user, lpGroup));
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("LuckPerms: ошибка назначения группы для " + username + ": " + throwable.getMessage());
            return null;
        });
    }

    private void applyGroup(UserManager userManager, User user, String lpGroup) {
        List<InheritanceNode> toRemove = new ArrayList<>();
        user.getNodes(NodeType.INHERITANCE).forEach(node -> {
            if (!node.getGroupName().equalsIgnoreCase("default")) {
                toRemove.add(node);
            }
        });
        toRemove.forEach(node -> user.data().remove(node));

        InheritanceNode node = InheritanceNode.builder(lpGroup.toLowerCase()).build();
        user.data().add(node);

        userManager.saveUser(user);
    }

    /**
     * При смене lpGroup у роли — применить ко всем переданным никам.
     */
    public void syncUsersForRole(List<String> usernames, String lpGroup) {
        if (!available) return;
        for (String username : usernames) {
            assignGroup(username, lpGroup);
        }
    }
}
