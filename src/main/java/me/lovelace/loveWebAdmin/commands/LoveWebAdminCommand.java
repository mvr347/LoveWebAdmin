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

    private static final List<String> SUBCOMMANDS = List.of("info", "reload", "resetowner", "help");
    private static final List<String> RESETOWNER_CONFIRM = List.of("confirm");

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

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> handleInfo(sender);
            case "reload" -> handleReload(sender);
            case "resetowner" -> handleResetOwner(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleInfo(CommandSender sender) {
        int port = plugin.getConfig().getInt("web.port", 8080);
        boolean lpAvailable = plugin.getLuckPermsManager().isAvailable();

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            int adminCount = plugin.getDatabaseManager().getAllAdmins().size();
            sender.sendMessage("§7Порт веб-панели: §f" + port);
            sender.sendMessage("§7Количество администраторов: §f" + adminCount);
            sender.sendMessage("§7Интеграция с LuckPerms: §f" + (lpAvailable ? "включена" : "отключена"));
        });
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage("§aКонфигурация перезагружена.");
    }

    private void handleResetOwner(CommandSender sender, String[] args) {
        if (args.length < 2 || !"confirm".equalsIgnoreCase(args[1])) {
            sender.sendMessage("§cОПАСНО: эта команда удалит всех Управляющих. Для подтверждения введите: /lovewebadmin resetowner confirm");
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            plugin.getAdminManager().resetOwners();
            sender.sendMessage("§aВсе Управляющие удалены. При следующем открытии панели можно назначить нового.");
        });
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§8========== §bLoveWebAdmin §8==========");
        sender.sendMessage("§b/lovewebadmin info §7- Порт панели, число админов, статус LuckPerms");
        sender.sendMessage("§b/lovewebadmin reload §7- Перезагрузить конфигурацию");
        sender.sendMessage("§b/lovewebadmin resetowner confirm §7- Удалить всех Управляющих (для назначения нового)");
        sender.sendMessage("§8=========================================");
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("lovewebadmin.admin")) return Collections.emptyList();

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("resetowner")) {
            return StringUtil.copyPartialMatches(args[1], RESETOWNER_CONFIRM, new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
