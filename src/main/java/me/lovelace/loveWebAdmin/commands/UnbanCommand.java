package me.lovelace.loveWebAdmin.commands;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Игровая команда /разбан <игрок> (алиасы: /unban, /webunban, /lunban, /pardon).
 * Снимает блокировку нарушителя в базе данных LoveWebAdmin и на сервере Minecraft.
 */
public class UnbanCommand implements CommandExecutor, TabCompleter {

    private final LoveWebAdmin plugin;

    public UnbanCommand(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("lovewebadmin.unban")) {
            sender.sendMessage("§cУ вас нет прав для выполнения этой команды!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§cИспользование: /" + label + " <игрок>");
            return true;
        }

        String target = args[0].trim();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            boolean success = plugin.getBanManager().unban(target, sender.getName());
            if (success) {
                sender.sendMessage("§a[WebAdmin] Игрок §e" + target + " §aуспешно разбанен!");
            } else {
                sender.sendMessage("§e[WebAdmin] Активный бан для игрока §f" + target + " §eне найден в системе.");
            }
        });

        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("lovewebadmin.unban")) return Collections.emptyList();
        if (args.length == 1) {
            Set<String> bannedNames = new HashSet<>();

            // Получаем ники из активных банов WebAdmin
            try {
                for (WebBan b : plugin.getDatabaseManager().getAllBans("ACTIVE")) {
                    if (b.targetName() != null && !b.targetName().isBlank()) {
                        bannedNames.add(b.targetName());
                    }
                }
            } catch (Exception ignored) {}

            // Получаем ники из ванильного Bukkit BanList
            try {
                var nameBanList = Bukkit.getBanList(BanList.Type.NAME);
                for (var entry : nameBanList.getEntries()) {
                    if (entry != null && entry.getTarget() != null) {
                        bannedNames.add(String.valueOf(entry.getTarget()));
                    }
                }
            } catch (Exception ignored) {}

            return StringUtil.copyPartialMatches(args[0], bannedNames, new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
