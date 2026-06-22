package me.lovelace.loveWebAdmin.commands;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * /lwa info|reload|resetowner
 */
public class LwaCommand implements CommandExecutor {

    private final LoveWebAdmin plugin;

    public LwaCommand(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Использование: /lwa <info|reload|resetowner>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "info" -> handleInfo(sender);
            case "reload" -> handleReload(sender);
            case "resetowner" -> handleResetOwner(sender, args);
            default -> sender.sendMessage("Использование: /lwa <info|reload|resetowner>");
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
            sender.sendMessage("§cОПАСНО: эта команда удалит всех Управляющих. Для подтверждения введите: /lwa resetowner confirm");
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            plugin.getAdminManager().resetOwners();
            sender.sendMessage("§aВсе Управляющие удалены. При следующем открытии панели можно назначить нового.");
        });
    }
}
