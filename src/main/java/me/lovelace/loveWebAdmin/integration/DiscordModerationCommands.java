package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.*;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.moderation.ModerationService;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/** Discord invokes the same service as game commands, never console command strings. */
final class DiscordModerationCommands {
  private final LoveWebAdmin plugin;
  private final DiscordService service;
  private final DiscordActions authority;
  private static final Set<String> ACTIONS =
      Set.of("ban", "mute", "unban", "unmute", "freeze", "unfreeze", "kick");

  DiscordModerationCommands(LoveWebAdmin p, DiscordService s, DiscordActions a) {
    plugin = p;
    service = s;
    authority = a;
  }

  void register() {
    for (String command : ACTIONS) {
      var options = new LinkedHashMap<String, String>();
      options.put("игрок", "Ник игрока");
      if (command.equals("mute")) options.put("срок", "30m, 1h, 7d или permanent");
      if (!command.equals("ban")) options.put("причина", "Причина действия");
      service.registerCommand(command, "Модерация Minecraft: " + command, options);
    }
  }

  boolean handle(DiscordInteraction i) {
    if (!ACTIONS.contains(i.name())) return false;
    service
        .respond(i, JsonUtils.toJson(Map.of("type", 5, "data", Map.of("flags", 64))))
        .thenAccept(
            ok -> {
              if (!ok) return;
              plugin
                  .io()
                  .supply(
                      () -> {
                        try {
                          Permission permission =
                              switch (i.name()) {
                                case "kick" -> Permission.KICK_PLAYERS;
                                case "freeze", "unfreeze" -> Permission.FREEZE_PLAYERS;
                                case "mute", "unmute" -> Permission.MANAGE_MUTES;
                                default -> Permission.MANAGE_BANS;
                              };
                          var staff =
                              authority
                                  .staffMember(i.userId(), permission)
                                  .orElseThrow(
                                      () ->
                                          new IllegalArgumentException(
                                              "Недостаточно прав сотрудника"));
                          String operationActor = "discord:" + i.userId();
                          if (!plugin.operations().allow(operationActor))
                            throw new IllegalArgumentException(
                                "Слишком много действий; подождите минуту");
                          String fingerprint =
                              me.lovelace.loveWebAdmin.web.MutationRequests.fingerprint(
                                  "DISCORD",
                                  i.name(),
                                  JsonUtils.toJson(i.values())
                                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                          if (plugin
                                  .operations()
                                  .reserve(operationActor, i.id(), i.name(), fingerprint)
                              != null) return "Действие уже принято; обновите состояние";
                          try {
                            String name = i.values().getOrDefault("игрок", "");
                            String reason = i.values().getOrDefault("причина", "");
                            String actor = staff.username();
                            var m = plugin.getModerationService();
                            switch (i.name()) {
                              case "ban" -> {
                                var ban = m.ban(actor, name);
                                plugin
                                    .operations()
                                    .complete(
                                        operationActor,
                                        i.id(),
                                        200,
                                        "Обработано",
                                        permission.name());
                                return "Игрок временно заблокирован. Оформите бан №"
                                    + ban.id()
                                    + " в панели за 5 минут.";
                              }
                              case "mute" -> {
                                if (reason.isBlank())
                                  throw new IllegalArgumentException("Укажите причину");
                                m.mute(
                                    actor,
                                    name,
                                    ModerationService.duration(i.values().getOrDefault("срок", "")),
                                    reason);
                              }
                              case "unmute" -> m.remove("MUTE", actor, name, reason);
                              case "freeze" ->
                                  m.freeze(
                                      actor,
                                      name,
                                      reason.isBlank() ? "Проверка сотрудником" : reason);
                              case "unfreeze" -> m.remove("FREEZE", actor, name, reason);
                              case "kick" -> {
                                if (reason.isBlank())
                                  throw new IllegalArgumentException("Укажите причину");
                                m.kick(actor, name, reason);
                              }
                              case "unban" -> {
                                if (!plugin.getBanManager().unban(name, actor))
                                  throw new IllegalArgumentException("Бан не найден");
                              }
                            }
                            plugin
                                .operations()
                                .complete(
                                    operationActor, i.id(), 200, "Обработано", permission.name());
                            return "Действие выполнено для " + name;
                          } catch (Exception error) {
                            plugin.operations().uncertain(operationActor, i.id());
                            throw new java.util.concurrent.CompletionException(error);
                          }
                        } catch (Exception error) {
                          throw new java.util.concurrent.CompletionException(error);
                        }
                      })
                  .whenComplete(
                      (text, error) -> {
                        if (error != null) {
                          while (error.getCause() != null) error = error.getCause();
                          text = "Не выполнено: " + error.getMessage();
                        }
                        service.editResponse(i, text);
                      });
            });
    return true;
  }
}
