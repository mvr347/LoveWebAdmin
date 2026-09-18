package me.lovelace.loveWebAdmin.commands;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Единая административная команда плагина: {@code /lovewebadmin <info|reload|resetowner>}.
 * <p>
 * Раньше это была {@code /lwa}, что не следует принятому в экосистеме Love* стилю
 * {@code love<plugin>admin}. Поскольку сам плагин уже называется "WebAdmin", дублировать
 * "admin" в имени команды ({@code lovewebadminadmin}) было бы неестественно — команда
 * называется просто {@code /lovewebadmin}. Старое имя {@code /lwa} оставлено алиасом
 * в plugin.yml, поэтому ничего не ломается для тех, кто набирает его по привычке.
 */
public class LoveWebAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload");

    private final LoveWebAdmin plugin;

    public LoveWebAdminCommand(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("lovewebadmin.admin")) {
            sender.sendMessage("§cУ вас нет прав для выполнения этой команды!");
            return true;
        }

        if (args.length == 0 || "reload".equalsIgnoreCase(args[0])) {
            plugin.reloadConfig();
            sender.sendMessage("§8[§bLoveWebAdmin§8] §aКонфигурация успешно перезагружена.");
            return true;
        }

        sender.sendMessage("§8========== §bLoveWebAdmin §8==========");
        sender.sendMessage("§b/lovewebadmin reload §7- Перезагрузить конфигурацию плагина");
        sender.sendMessage("§8=========================================");
        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("lovewebadmin.admin")) return Collections.emptyList();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
