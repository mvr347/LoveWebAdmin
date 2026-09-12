package me.lovelace.loveWebAdmin.commands;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Игровая команда /бан <игрок> (алиасы: /ban, /webban, /lban).
 * Создает черновик бана в WebAdmin и отправляет модератору уведомление со ссылкой.
 */
public class BanCommand implements CommandExecutor, TabCompleter {

    private final LoveWebAdmin plugin;

    public BanCommand(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("lovewebadmin.ban")) {
            sender.sendMessage("§cУ вас нет прав для выполнения этой команды!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§cИспользование: /" + label + " <игрок>");
            return true;
        }

        String target = args[0].trim();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            WebBan draft = plugin.getBanManager().createDraft(sender.getName(), target);
            sender.sendMessage("§a[WebAdmin] Черновик бана #" + draft.id() + " для игрока §e" + target + " §aсоздан!");
            sender.sendMessage("§7Откройте веб-панель §bWebAdmin §7(раздел §f«Баны»§7), чтобы прикрепить скриншоты, выбрать пункт правил и подтвердить бан.");
        });

        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("lovewebadmin.ban")) return Collections.emptyList();
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return StringUtil.copyPartialMatches(args[0], names, new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
