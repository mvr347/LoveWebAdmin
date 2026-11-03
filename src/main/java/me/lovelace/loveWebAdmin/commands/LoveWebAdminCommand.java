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

    private static final List<String> SUBCOMMANDS = List.of("reload", "info", "generatetoken", "resetowner", "discord");
    private static final long CONFIRM_WINDOW_MS = 60_000L;

    /** One-time codes for destructive console commands: operation -> code and its expiry. */
    private final java.util.Map<String, String> confirmCodes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> confirmExpiry = new java.util.concurrent.ConcurrentHashMap<>();

    private final LoveWebAdmin plugin;

    public LoveWebAdminCommand(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("lovewebadmin.admin")) {
            return true;
        }

        if (args.length == 0 || "reload".equalsIgnoreCase(args[0])) {
            plugin.reloadConfig();
            sender.sendMessage("§8[§bLoveWebAdmin§8] §aКонфигурация успешно перезагружена.");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(sender);
            case "generatetoken" -> generateToken(sender);
            case "resetowner" -> resetOwner(sender, args);
            case "discord" -> discord(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§8========== §bLoveWebAdmin §8==========");
        sender.sendMessage("§b/lovewebadmin reload §7- Перезагрузить конфигурацию плагина");
        sender.sendMessage("§b/lovewebadmin info §7- Адрес панели и состояние входа");
        sender.sendMessage("§b/lovewebadmin generatetoken §7- Новый токен первичной настройки (пока нет Управляющего)");
        sender.sendMessage("§b/lovewebadmin resetowner §7- Сбросить Управляющего (только консоль, с подтверждением)");
        sender.sendMessage("§b/lovewebadmin discord purge §7- Удалить все сообщения бота (только консоль, для тестов)");
        sender.sendMessage("§8=========================================");
    }

    private void info(CommandSender sender) {
        var config = plugin.getConfig();
        String publicUrl = config.getString("web.public-url", "");
        sender.sendMessage("§8[§bLoveWebAdmin§8] §7Панель: §f" + (config.getBoolean("web.enabled", true) ? "включена" : "ВЫКЛЮЧЕНА")
                + " §7адрес: §f" + (publicUrl.isBlank() ? "http://<IP сервера>:" + config.getInt("web.port", 8080) : publicUrl)
                + " §7слушает: §f" + config.getString("web.host", "0.0.0.0"));
        boolean owner = plugin.getAdminManager().hasOwner();
        sender.sendMessage("§8[§bLoveWebAdmin§8] §7Управляющий: " + (owner
                ? "§aсоздан §7(забыли данные входа - §f/lovewebadmin resetowner§7)"
                : "§eне создан §7(токен настройки - §f/lovewebadmin generatetoken§7)"));
        sender.sendMessage("§8[§bLoveWebAdmin§8] §7Preview: §f" + (plugin.isPreviewEnabled() ? "включён, /preview/" : "выключен (preview.enabled)"));
    }

    private void generateToken(CommandSender sender) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage("§8[§bLoveWebAdmin§8] §cТокен можно получить только из консоли сервера.");
            return;
        }
        if (plugin.getAdminManager().hasOwner()) {
            sender.sendMessage("§8[§bLoveWebAdmin§8] §eУправляющий уже создан. Забыли данные входа - §f/lovewebadmin resetowner§e.");
            return;
        }
        sender.sendMessage("§8[§bLoveWebAdmin§8] §7Токен первичной настройки: §f" + plugin.getAdminManager().generateSetupToken());
    }

    private void resetOwner(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage("§8[§bLoveWebAdmin§8] §cСброс Управляющего доступен только из консоли сервера.");
            return;
        }
        if (!confirmed(sender, "resetowner", args, 1,
                "Будут удалены учётные записи Управляющих и их сессии. Остальной персонал, дела и наказания не меняются.")) return;
        plugin.getAdminManager().resetOwners();
        sender.sendMessage("§8[§bLoveWebAdmin§8] §aУправляющий сброшен. Токен первичной настройки: §f" + plugin.getAdminManager().generateSetupToken());
    }

    private void discord(CommandSender sender, String[] args) {
        if (args.length < 2 || !"purge".equalsIgnoreCase(args[1])) {
            sender.sendMessage("§8[§bLoveWebAdmin§8] §7Использование: §f/lovewebadmin discord purge");
            return;
        }
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            sender.sendMessage("§8[§bLoveWebAdmin§8] §cОчистка доступна только из консоли сервера.");
            return;
        }
        if (!confirmed(sender, "discord-purge", args, 2,
                "Бот удалит ВСЕ свои сообщения во всех текстовых каналах сервера и в известных ЛС: панели, карточки, вступления и ответы в каналах обращений. Только для тестового сервера.")) return;
        sender.sendMessage("§8[§bLoveWebAdmin§8] §7Очистка запущена, это может занять несколько минут...");
        plugin.io().run(() -> {
            try {
                int total = plugin.getDiscordBridge().ticketDesk().purgeBotMessages(
                        line -> plugin.getLogger().info("Очистка Discord: " + line));
                plugin.getLogger().info("Очистка Discord завершена, удалено сообщений: " + total
                        + ". Панель и карточки опубликуются заново сами.");
            } catch (Exception error) {
                plugin.getLogger().warning("Очистка Discord не выполнена: " + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + error.getMessage()));
            }
        });
    }

    /**
     * Two-step guard for destructive console commands. The first call prints a warning and a code;
     * the same command with "confirm <code>" within a minute performs it.
     */
    private boolean confirmed(CommandSender sender, String operation, String[] args, int codeIndex, String warning) {
        boolean confirming = args.length > codeIndex + 1 && "confirm".equalsIgnoreCase(args[codeIndex]);
        String code = confirmCodes.get(operation);
        long expiry = confirmExpiry.getOrDefault(operation, 0L);
        if (confirming && code != null && System.currentTimeMillis() <= expiry && code.equalsIgnoreCase(args[codeIndex + 1])) {
            confirmCodes.remove(operation);
            confirmExpiry.remove(operation);
            return true;
        }
        String fresh = String.format("%04d", new java.security.SecureRandom().nextInt(10_000));
        confirmCodes.put(operation, fresh);
        confirmExpiry.put(operation, System.currentTimeMillis() + CONFIRM_WINDOW_MS);
        String base = "/lovewebadmin " + (operation.equals("discord-purge") ? "discord purge" : operation);
        sender.sendMessage("§8[§bLoveWebAdmin§8] §c" + warning);
        sender.sendMessage("§8[§bLoveWebAdmin§8] §7Для подтверждения в течение минуты: §f" + base + " confirm " + fresh);
        return false;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("lovewebadmin.admin")) return Collections.emptyList();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }
        if (args.length == 2 && "discord".equalsIgnoreCase(args[0])) {
            return StringUtil.copyPartialMatches(args[1], List.of("purge"), new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
