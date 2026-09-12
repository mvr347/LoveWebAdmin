package me.lovelace.loveWebAdmin.commands;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Команда подачи жалобы: /репорт, /жалоба, /report <никнейм>.
 */
public class ReportCommand implements CommandExecutor, TabCompleter {

    private final LoveWebAdmin plugin;

    public ReportCommand(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("§cКоманда доступна только игрокам в игре!"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(Component.text("§cИспользование: §e/" + label + " <никнейм>"));
            return true;
        }

        String targetName = args[0].trim();
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(Component.text("§cВы не можете подать жалобу на самого себя!"));
            return true;
        }

        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        OfflinePlayer target = onlineTarget != null ? onlineTarget : Bukkit.getOfflinePlayer(targetName);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(Component.text("§cИгрок с ником §e" + targetName + " §cникогда не играл на сервере!"));
            return true;
        }

        plugin.getReportManager().startReportFlow(player, target);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            String query = args[0].toLowerCase();
            List<String> list = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player senderPlayer && senderPlayer.getUniqueId().equals(p.getUniqueId())) {
                    continue;
                }
                if (p.getName().toLowerCase().startsWith(query)) {
                    list.add(p.getName());
                }
            }
            return list;
        }
        return Collections.emptyList();
    }
}
