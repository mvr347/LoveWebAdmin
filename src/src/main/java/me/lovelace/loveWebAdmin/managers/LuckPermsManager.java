package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;

import java.util.List;

/**
 * softdepend — если LuckPerms не установлен, всё работает без синхронизации.
 *
 * <p>Ни один метод/поле этого класса не ссылается на типы {@code net.luckperms.api.*}
 * напрямую — вся такая логика вынесена в {@link LuckPermsBridge}, который создаётся
 * только когда LuckPerms точно установлен. Иначе сама загрузка/верификация класса,
 * упоминающего LuckPerms-типы в сигнатурах методов, роняет плагин с
 * {@code NoClassDefFoundError} ещё до вызова любого метода — рантайм-проверка
 * {@code available} тут не спасает.</p>
 */
public class LuckPermsManager {

    private final boolean available;
    private final LuckPermsBridge bridge;

    public LuckPermsManager(LoveWebAdmin plugin) {
        this.available = plugin.getServer().getPluginManager().getPlugin("LuckPerms") != null;
        this.bridge = available ? new LuckPermsBridge(plugin) : null;
    }

    public boolean isAvailable() {
        return available;
    }

    public void ensureGroupExists(String groupName) {
        if (!available || groupName == null || groupName.isBlank()) return;
        bridge.ensureGroupExists(groupName);
    }

    /**
     * Снимает все текущие группы (кроме default) и назначает lpGroup из роли.
     * Должен вызываться из async контекста.
     */
    public void assignGroup(String username, String lpGroup) {
        if (!available || lpGroup == null || lpGroup.isBlank()) return;
        bridge.assignGroup(username, lpGroup);
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
