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

/**
 * Holds every reference to {@code net.luckperms.api.*} types. Kept in its own class
 * (separate from {@link LuckPermsManager}) and only ever instantiated when LuckPerms
 * is confirmed present: the JVM resolves every type in a class's method signatures
 * when that class is loaded/verified, not lazily per-call, so a class with LuckPerms
 * types in its own signatures crashes with {@code NoClassDefFoundError} the moment
 * it's loaded — even behind an {@code if (available)} check — when LuckPerms isn't
 * installed. Splitting this code into a class that's simply never loaded in that case
 * is the fix, not the runtime check alone.
 */
class LuckPermsBridge {

    private final LoveWebAdmin plugin;

    LuckPermsBridge(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    private LuckPerms api() {
        return LuckPermsProvider.get();
    }

    void ensureGroupExists(String groupName) {
        api().getGroupManager().createAndLoadGroup(groupName.toLowerCase());
    }

    void assignGroup(String username, String lpGroup) {
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
}
