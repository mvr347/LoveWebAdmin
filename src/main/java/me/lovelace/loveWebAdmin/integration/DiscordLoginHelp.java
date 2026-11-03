package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.DiscordService;
import java.util.*;
import java.util.concurrent.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;

/** Private, read-only diagnosis. Missing integration is not reported as a successful check. */
final class DiscordLoginHelp {
  private final LoveWebAdmin plugin;
  private final DiscordService service;

  DiscordLoginHelp(LoveWebAdmin plugin, DiscordService service) {
    this.plugin = plugin;
    this.service = service;
  }

  Map<String, Object> render(String owner) {
    var context = new DiscordPlayerContext(plugin, service);
    Optional<DiscordPlayerContext.Identity> identity;
    try {
      identity = context.linked(owner);
    } catch (IllegalArgumentException error) {
      return card(
          error.getMessage(),
          List.of(
              button("Мой аккаунт", "lc:menu:account"),
              button("Обновить проверку", "lc:menu:loginhelp")));
    }
    Map<String, Object> auth;
    try {
      auth =
          plugin
              .main()
              .call(
                  () -> {
                    var authPlugin = plugin.getServer().getPluginManager().getPlugin("LoveAuth");
                    if (authPlugin == null || !authPlugin.isEnabled())
                      return CompletableFuture.<Map<String, Object>>completedFuture(
                          Map.of("available", false));
                    try {
                      Object manager =
                          authPlugin
                              .getClass()
                              .getMethod("getDiscordAuthManager")
                              .invoke(authPlugin);
                      @SuppressWarnings("unchecked")
                      var result =
                          (CompletableFuture<Map<String, Object>>)
                              manager
                                  .getClass()
                                  .getMethod("getDiscordLoginStatus", String.class)
                                  .invoke(manager, owner);
                      return result;
                    } catch (ReflectiveOperationException missing) {
                      return CompletableFuture.<Map<String, Object>>completedFuture(
                          Map.of("available", false));
                    }
                  })
              .thenCompose(future -> future)
              .get(3, TimeUnit.SECONDS);
    } catch (Exception unavailable) {
      auth = Map.of("available", false);
    }
    return snapshot(identity, identity.map(context::bans).orElse(List.of()), auth);
  }

  static Map<String, Object> snapshot(
      Optional<DiscordPlayerContext.Identity> identity,
      List<WebBan> bans,
      Map<String, Object> auth) {
    var text = new StringBuilder();
    var buttons = new ArrayList<Map<String, Object>>();
    if (identity.isEmpty()) {
      text.append(
          "**Привязка:** не подтверждена.\\n"
              + "Откройте «Мой аккаунт» и привяжите свой Minecraft-профиль. Без привязки бот не"
              + " показывает чужие наказания и состояние входа.");
      buttons.add(button("Привязать аккаунт", "lc:menu:account"));
    } else {
      var player = identity.get();
      text.append("Аккаунт: **").append(player.name()).append("**\n**Привязка:** подтверждена.\n");
      if (bans.isEmpty())
        text.append("**Блокировка сервера:** действующих блокировок этого аккаунта не найдено.\n");
      else {
        text.append(
            "**Блокировка сервера:** есть действующее наказание. Откройте список, чтобы выбрать"
                + " апелляцию.\n");
        buttons.add(button("Обжаловать наказание", "lwa:desk:new:appeal"));
      }
      boolean available = Boolean.TRUE.equals(auth.get("available"));
      boolean same =
          Boolean.TRUE.equals(auth.get("linked"))
              && player.uuid().toString().equalsIgnoreCase(Objects.toString(auth.get("uuid"), ""));
      if (!available)
        text.append(
            "**Безопасность и подтверждение входа:** проверка недоступна. Состояние не"
                + " подтверждено; повторите позже.\n");
      else if (!same) {
        text.append(
            "**Безопасность:** привязки ожидают синхронизации. Проверьте «Мой аккаунт»; чужой"
                + " профиль не проверяется.\n");
        buttons.add(button("Мой аккаунт", "lc:menu:account"));
      } else if (Boolean.TRUE.equals(auth.get("locked"))) {
        text.append(
            "**Защита аккаунта:** аккаунт заблокирован владельцем или системой. Откройте"
                + " безопасность для доступных действий.\n");
        buttons.add(button("Безопасность аккаунта", "lc:menu:security"));
      } else if (Boolean.TRUE.equals(auth.get("authenticated")))
        text.append(
            "**Вход:** вы уже авторизованы на сервере. Если проблема осталась, опишите её"
                + " сотруднику.\n");
      else if (Boolean.TRUE.equals(auth.get("confirmationPending")))
        text.append(
            "**Вход:** ожидается ваше подтверждение. В личке есть сообщение «Это вы входите?» —"
                + " используйте его кнопки. Новый запрос не создаётся.\n");
      else if (Boolean.TRUE.equals(auth.get("online"))) {
        text.append(
            "**Вход:** подключение есть, авторизация ещё не завершена. Проверьте подсказку в игре и"
                + " настройки своего аккаунта.\n");
        buttons.add(button("Мой аккаунт", "lc:menu:account"));
      } else
        text.append(
            "**Вход:** текущая попытка не обнаружена. Подключитесь к серверу, затем обновите"
                + " проверку.\n");
    }
    buttons.add(button("Обновить проверку", "lc:menu:loginhelp"));
    return card(text.toString().replace("\\n", "\n"), buttons);
  }

  private static Map<String, Object> button(String text, String id) {
    return Map.of("type", 2, "style", 1, "label", text, "custom_id", id);
  }

  private static Map<String, Object> card(String description, List<Map<String, Object>> buttons) {
    return DiscordCards.decorate(
        Map.of(
            "content",
            "**Не могу войти на сервер**\n" + description,
            "components",
            List.of(Map.of("type", 1, "components", buttons))),
        DiscordCards.BLUE);
  }
}
