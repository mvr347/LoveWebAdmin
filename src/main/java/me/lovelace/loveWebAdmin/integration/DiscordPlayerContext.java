package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.DiscordService;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;

/** The authenticated Discord owner supplies identity; submitted nicknames never override a link. */
final class DiscordPlayerContext {
  record Identity(UUID uuid, String name) {}

  private final LoveWebAdmin plugin;
  private final DiscordService service;

  DiscordPlayerContext(LoveWebAdmin plugin, DiscordService service) {
    this.plugin = plugin;
    this.service = service;
  }

  Optional<Identity> linked(String owner) {
    return service
        .getLinkedPlayer(owner)
        .map(
            uuid -> {
              String name =
                  plugin
                      .getDatabaseManager()
                      .getPlayerStats(uuid.toString())
                      .map(p -> Objects.toString(p.get("name"), ""))
                      .orElse("");
              if (!name.matches("[A-Za-z0-9_]{1,16}"))
                throw new IllegalArgumentException(
                    "Аккаунт привязан, но профиль ещё не синхронизирован. Откройте «Мой аккаунт»"
                        + " или зайдите на сервер.");
              return new Identity(uuid, name);
            });
  }

  List<WebBan> bans(Identity identity) {
    return plugin
        .getDatabaseManager()
        .getActiveBansForPlayer(identity.name(), identity.uuid().toString(), null)
        .stream()
        .filter(
            b ->
                b.targetUuid() == null || b.targetUuid().isBlank()
                    ? b.targetName().equalsIgnoreCase(identity.name())
                    : b.targetUuid().equalsIgnoreCase(identity.uuid().toString()))
        .sorted(
            Comparator.comparingLong(WebBan::createdAt)
                .reversed()
                .thenComparing(Comparator.comparingInt(WebBan::id).reversed()))
        .toList();
  }

  WebBan appealBan(String owner, Map<String, ?> input, String nick) {
    var identity = linked(owner);
    String requested =
        Objects.toString(input.get("banId"), Objects.toString(input.get("ban"), "")).trim();
    if (identity.isPresent()) {
      var bans = bans(identity.get());
      if (bans.isEmpty())
        throw new IllegalArgumentException("У привязанного аккаунта нет действующих блокировок");
      if (requested.isBlank() && bans.size() != 1)
        throw new IllegalArgumentException(
            "Выберите наказание из списка в разделе «Обжаловать наказание»");
      return bans.stream()
          .filter(b -> requested.isBlank() || requested.equals(String.valueOf(b.id())))
          .findFirst()
          .orElseThrow(
              () ->
                  new IllegalArgumentException(
                      "Это наказание недоступно или уже снято. Обновите список наказаний"));
    }
    var ban =
        plugin
            .getDatabaseManager()
            .getActiveBan(nick)
            .orElseThrow(() -> new IllegalArgumentException("Активное наказание не найдено"));
    if (!requested.isBlank() && !requested.equals(String.valueOf(ban.id())))
      throw new IllegalArgumentException(
          "Этот номер не соответствует активному наказанию указанного аккаунта");
    return ban;
  }
}
