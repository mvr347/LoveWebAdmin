package me.lovelace.loveWebAdmin.commands;

import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.moderation.ModerationService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class ModerationCommand implements CommandExecutor, TabCompleter {
  private final LoveWebAdmin plugin;
  private final String action;

  public ModerationCommand(LoveWebAdmin plugin, String action) {
    this.plugin = plugin;
    this.action = action;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("lovewebadmin." + action)) return true;
    if (args.length == 0) {
      sender.sendMessage(
          Component.text(
              "/"
                  + label
                  + " <ник>"
                  + (action.equals("mute") ? " <срок|permanent> <причина>" : " [причина]")));
      return true;
    }
    String actor = sender.getName();
    String name = args[0];
    String[] copy = args.clone();
    plugin
        .io()
        .supply(
            () -> {
              var service = plugin.getModerationService();
              switch (action) {
                case "ban" -> {
                  var ban = service.ban(actor, name);
                  return "Предварительная блокировка #"
                      + ban.id()
                      + ". Оформите бан в панели в течение 5 минут.";
                }
                case "mute" -> {
                  if (copy.length < 3)
                    throw new IllegalArgumentException("/mute <ник> <срок|permanent> <причина>");
                  service.mute(
                      actor,
                      name,
                      ModerationService.duration(copy[1]),
                      String.join(" ", Arrays.copyOfRange(copy, 2, copy.length)));
                }
                case "unmute" -> service.remove("MUTE", actor, name, reason(copy));
                case "freeze" -> service.freeze(actor, name, reason(copy));
                case "unfreeze" -> service.remove("FREEZE", actor, name, reason(copy));
                case "kick" -> service.kick(actor, name, reason(copy));
                case "unban" -> {
                  if (!plugin.getBanManager().unban(name, actor))
                    throw new IllegalArgumentException("Действующий бан не найден");
                }
                default -> throw new IllegalArgumentException("Действие");
              }
              return "Действие выполнено для " + name;
            })
        .whenComplete(
            (message, error) ->
                plugin
                    .main()
                    .call(
                        () -> {
                          sender.sendMessage(
                              Component.text(
                                  error == null
                                      ? message
                                      : "Не выполнено: " + root(error).getMessage()));
                          return true;
                        }));
    return true;
  }

  private static String reason(String[] args) {
    return args.length > 1
        ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
        : "Проверка сотрудником";
  }

  private static Throwable root(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    return e;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command cmd, String label, String[] args) {
    if (!sender.hasPermission("lovewebadmin." + action)) return List.of();
    if (args.length == 1)
      return Bukkit.getOnlinePlayers().stream()
          .map(Player::getName)
          .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
          .toList();
    if (action.equals("mute") && args.length == 2)
      return List.of("15m", "30m", "1h", "1d", "permanent").stream()
          .filter(v -> v.startsWith(args[1]))
          .toList();
    return List.of();
  }
}
