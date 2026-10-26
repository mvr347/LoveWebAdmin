package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Профили и управление игроками: GET /api/players/search?q= — поиск игроков GET /api/players/{name}
 * — полный профиль GET /api/players/{name}/chat — расширенная история чата игрока GET
 * /api/players/{name}/inventory — живой инвентарь и эндер-сундук GET /api/players/{name}/alts —
 * поиск твинков и связанных аккаунтов (IP Graph) POST /api/players/{name}/action — интерактивные
 * действия (heal, feed, gamemode, teleport_spawn, freeze, unfreeze, kick, remove_item,
 * clear_inventory)
 */
public class ApiPlayersHandler extends ApiHandlerSupport {

  public ApiPlayersHandler(LoveWebAdmin plugin) {
    super(plugin);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.VIEW_PLAYERS);
    if (sessionOpt.isEmpty()) return;

    String pathInfo = req.getPathInfo();
    if (pathInfo == null
        || pathInfo.isEmpty()
        || "/".equals(pathInfo)
        || "/all".equals(pathInfo)
        || "/search".equals(pathInfo)) {
      String query = req.getParameter("q");
      if (query == null) query = "";
      int limit = parseIntOrDefault(req.getParameter("limit"), 50);
      List<Map<String, Object>> list =
          plugin.getDatabaseManager().searchPlayers(query.trim(), limit);
      sendSuccess(resp, list);
      return;
    }

    String cleanPath = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;

    if (cleanPath.contains("/chat")) {
      String playerName = cleanPath.substring(0, cleanPath.indexOf("/chat"));
      int limit = parseIntOrDefault(req.getParameter("limit"), 50);
      List<Map<String, Object>> chat =
          plugin.getDatabaseManager().getPlayerChatLogs(playerName, limit);
      sendSuccess(resp, chat);
      return;
    }

    if (cleanPath.contains("/inventory")) {
      if (!hasPermission(sessionOpt.get(), Permission.VIEW_PLAYER_INVENTORY)) {
        sendError(resp, 403, "Нет права просмотра инвентаря");
        return;
      }
      String playerName = cleanPath.substring(0, cleanPath.indexOf("/inventory"));
      Player player =
          plugin
              .main()
              .call(
                  () -> {
                    Player found = Bukkit.getPlayerExact(playerName);
                    return found != null && found.isOnline() ? found : null;
                  })
              .join();
      if (player == null) {
        sendError(resp, 404, "Игрок не в сети или не найден");
        return;
      }
      try {
        Map<String, Object> invData =
            plugin.getPlayerInventoryManager().getInventoryData(player).get();
        sendSuccess(resp, invData);
      } catch (Exception e) {
        sendError(resp, 500, "Ошибка получения инвентаря: " + e.getMessage());
      }
      return;
    }

    if (cleanPath.contains("/alts")) {
      if (!hasPermission(sessionOpt.get(), Permission.VIEW_PLAYER_IP)) {
        sendError(resp, 403, "Нет права просмотра связей IP");
        return;
      }
      String playerName = cleanPath.substring(0, cleanPath.indexOf("/alts"));
      Map<String, Object> alts = plugin.getDatabaseManager().getAssociatedAccounts(playerName);
      sendSuccess(resp, alts);
      return;
    }

    if (cleanPath.contains("/notes")) {
      String playerName = cleanPath.substring(0, cleanPath.indexOf("/notes"));
      List<Map<String, Object>> notes = plugin.getDatabaseManager().getPlayerStaffNotes(playerName);
      sendSuccess(resp, notes);
      return;
    }

    if (cleanPath.endsWith("/confiscated")) {
      if (!hasPermission(sessionOpt.get(), Permission.RESTORE_ITEMS)) {
        sendError(resp, 403, "Нет права возврата вещей");
        return;
      }
      String name = cleanPath.substring(0, cleanPath.indexOf("/confiscated"));
      var record = plugin.getDatabaseManager().getPlayerStats(name);
      if (record.isEmpty()) {
        sendError(resp, 404, "Игрок не найден");
        return;
      }
      try {
        sendSuccess(resp, plugin.moderation().evidenceList(record.get().get("uuid").toString()));
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException(e);
      }
      return;
    }
    // Запрос полного профиля
    String playerName = cleanPath;
    if (playerName.isBlank()) {
      sendError(resp, 400, "Укажите имя игрока");
      return;
    }

    Map<String, Object> profile = plugin.getPlayerProfileManager().getFullProfile(playerName);
    if (!hasPermission(sessionOpt.get(), Permission.VIEW_PLAYER_IP)) profile.remove("ip");
    sendSuccess(resp, profile);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    Optional<WebSession> sessionOpt = requireSession(req, resp);
    if (sessionOpt.isEmpty()) return;
    WebSession session = sessionOpt.get();

    String pathInfo = req.getPathInfo();
    if (pathInfo == null) {
      sendError(resp, 400, "Неверный путь запроса");
      return;
    }

    String cleanPath = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;

    if (cleanPath.contains("/notes")) {
      if (!hasPermission(session, Permission.MANAGE_PLAYERS)) {
        sendError(resp, 403, "Нет права добавления заметок");
        return;
      }
      String playerName = cleanPath.substring(0, cleanPath.indexOf("/notes"));
      Map<String, Object> body = readJsonBody(req);
      String note = stringOrNull(body.get("note"));
      if (note == null || note.trim().isBlank()) {
        sendError(resp, 400, "Текст заметки не может быть пустым");
        return;
      }
      plugin
          .getDatabaseManager()
          .addPlayerStaffNote(playerName, session.adminUsername(), note.trim());
      plugin
          .getLogManager()
          .logWebAction(session.adminUsername(), "Добавил заметку для игрока " + playerName);
      sendSuccess(resp, Map.of("message", "Заметка успешно сохранена"));
      return;
    }

    if (!cleanPath.contains("/action")) {
      sendError(resp, 404, "Действие не найдено");
      return;
    }

    String playerName = cleanPath.substring(0, cleanPath.indexOf("/action"));
    Player player =
        plugin
            .main()
            .call(
                () -> {
                  Player found = Bukkit.getPlayerExact(playerName);
                  return found != null && found.isOnline() ? found : null;
                })
            .join();
    if (player == null) {
      sendError(resp, 404, "Игрок не в сети или не найден");
      return;
    }

    Map<String, Object> body = readJsonBody(req);
    String action = stringOrNull(body.get("action"));
    if (action == null || action.isBlank()) {
      sendError(resp, 400, "Не указано действие (action)");
      return;
    }

    Permission needed =
        switch (action.toLowerCase(java.util.Locale.ROOT)) {
          case "remove_item", "clear_inventory" -> Permission.CONFISCATE_ITEMS;
          case "restore_item" -> Permission.RESTORE_ITEMS;
          case "freeze", "unfreeze" -> Permission.FREEZE_PLAYERS;
          case "gamemode" -> Permission.CHANGE_GAMEMODE;
          case "teleport_spawn" -> Permission.TELEPORT_PLAYERS;
          case "kick" -> Permission.KICK_PLAYERS;
          case "kill" -> Permission.KILL_PLAYERS;
          default -> Permission.MANAGE_PLAYERS;
        };
    if (!hasPermission(session, needed)) {
      sendError(resp, 403, "Недостаточно прав для этого действия");
      return;
    }
    try {
      switch (action.toLowerCase()) {
        case "heal" -> {
          plugin
              .main()
              .call(
                  () -> {
                    me.lovelace.loveWebAdmin.integration.GameplayPolicy.check(player);
                    if (!player.isOnline())
                      throw new IllegalArgumentException("Игрок вышел с сервера");
                    player.setHealth(player.getMaxHealth());
                    player.setFoodLevel(20);
                    player.sendMessage(
                        Component.text(
                            "§a[WebAdmin] Ваше здоровье и сытость были восстановлены"
                                + " администратором."));
                    return true;
                  })
              .get();
          plugin
              .getLogManager()
              .logWebAction(
                  session.adminUsername(), "Восстановил здоровье игроку " + player.getName());
          sendSuccess(resp, Map.of("message", "Здоровье игрока восстановлено"));
        }
        case "feed" -> {
          plugin
              .main()
              .call(
                  () -> {
                    me.lovelace.loveWebAdmin.integration.GameplayPolicy.check(player);
                    if (!player.isOnline())
                      throw new IllegalArgumentException("Игрок вышел с сервера");
                    player.setFoodLevel(20);
                    player.setSaturation(20f);
                    player.sendMessage(
                        Component.text(
                            "§a[WebAdmin] Ваша сытость была восстановлена администратором."));
                    return true;
                  })
              .get();
          plugin
              .getLogManager()
              .logWebAction(
                  session.adminUsername(), "Восстановил сытость игроку " + player.getName());
          sendSuccess(resp, Map.of("message", "Сытость игрока восстановлена"));
        }
        case "gamemode" -> {
          String modeStr = stringOrNull(body.get("gameMode"));
          if (modeStr == null) {
            sendError(resp, 400, "Не указан игровой режим");
            return;
          }
          GameMode gm;
          try {
            gm = GameMode.valueOf(modeStr.toUpperCase());
          } catch (IllegalArgumentException e) {
            sendError(resp, 400, "Недопустимый игровой режим: " + modeStr);
            return;
          }
          plugin
              .main()
              .call(
                  () -> {
                    me.lovelace.loveWebAdmin.integration.GameplayPolicy.check(player);
                    if (!player.isOnline())
                      throw new IllegalArgumentException("Игрок вышел с сервера");
                    player.setGameMode(gm);
                    player.sendMessage(
                        Component.text("§e[WebAdmin] Ваш игровой режим изменён на " + gm.name()));
                    return true;
                  })
              .get();
          plugin
              .getLogManager()
              .logWebAction(
                  session.adminUsername(),
                  "Изменил режим игры " + player.getName() + " на " + gm.name());
          sendSuccess(resp, Map.of("message", "Режим игры изменён на " + gm.name()));
        }
        case "teleport_spawn" -> {
          plugin
              .main()
              .call(
                  () -> {
                    me.lovelace.loveWebAdmin.integration.GameplayPolicy.check(player);
                    if (!player.isOnline())
                      throw new IllegalArgumentException("Игрок вышел с сервера");
                    if (!Bukkit.dispatchCommand(player, "loveessential:le spawn"))
                      throw new IllegalArgumentException("Команда спавна LoveEssential недоступна");
                    return true;
                  })
              .get();
          plugin
              .getLogManager()
              .logWebAction(
                  session.adminUsername(),
                  "Телепортировал игрока " + player.getName() + " на спавн");
          sendSuccess(resp, Map.of("message", "Запрос телепортации передан LoveEssential"));
        }
        case "freeze" -> {
          plugin
              .main()
              .call(
                  () -> {
                    plugin.getFreezeManager().setFrozen(player, true, session.adminUsername());
                    return true;
                  })
              .join();
          sendSuccess(resp, Map.of("message", "Игрок заморожен для проверки", "isFrozen", true));
        }
        case "unfreeze" -> {
          plugin
              .main()
              .call(
                  () -> {
                    plugin.getFreezeManager().setFrozen(player, false, session.adminUsername());
                    return true;
                  })
              .join();
          sendSuccess(resp, Map.of("message", "Игрок разморожен", "isFrozen", false));
        }
        case "kick" -> {
          String reason = stringOrNull(body.get("reason"));
          if (reason == null || reason.isBlank())
            reason = "Кикнут администратором через веб-панель";
          final String finalReason = reason;
          plugin
              .main()
              .call(
                  () -> {
                    if (!player.isOnline())
                      throw new IllegalArgumentException("Игрок вышел с сервера");
                    player.kick(Component.text("§c[WebAdmin] " + finalReason));
                    return true;
                  })
              .get();
          plugin
              .getLogManager()
              .logWebAction(
                  session.adminUsername(),
                  "Кикнул игрока " + player.getName() + " по причине: " + reason);
          sendSuccess(resp, Map.of("message", "Игрок кикнут с сервера"));
        }
        case "remove_item" -> {
          String reason = stringOrNull(body.get("reason"));
          if (reason == null || reason.trim().length() < 5 || reason.length() > 2000) {
            sendError(resp, 400, "Укажите причину изъятия (от 5 до 2000 символов)");
            return;
          }
          String container = stringOrNull(body.get("container"));
          int slot = parseIntOrDefault(String.valueOf(body.get("slot")), -1);
          if (container == null || slot < 0) {
            sendError(resp, 400, "Укажите container и slot");
            return;
          }
          boolean removed =
              plugin
                  .getPlayerInventoryManager()
                  .removeItem(
                      player,
                      container,
                      slot,
                      session.adminUsername(),
                      stringOrNull(body.get("fingerprint")), reason.trim())
                  .get();
          if (removed) {
            plugin
                .getLogManager()
                .logWebAction(
                    session.adminUsername(),
                    "Причина изъятия у " + playerName + ": " + reason.trim());
            sendSuccess(resp, Map.of("message", "Предмет успешно изъят"));
          } else {
            sendError(
                resp, 400, "Состояние игрока или предмета изменилось. Обновите снимок инвентаря");
          }
        }
        case "restore_item" -> {
          boolean restored =
              plugin
                  .getPlayerInventoryManager()
                  .restore(player, stringOrNull(body.get("id")), session.adminUsername())
                  .join();
          if (!restored) {
            sendError(resp, 409, "Предмет недоступен, игрок вышел или нет свободного места");
            return;
          }
          sendSuccess(resp, Map.of("message", "Предмет возвращён"));
        }
        case "clear_inventory" -> {
          var roleOpt = plugin.getDatabaseManager().getRoleById(session.roleId());
          boolean isHighRole =
              roleOpt.isPresent()
                  && (roleOpt.get().isOwner()
                      || "Управляющий".equalsIgnoreCase(roleOpt.get().name()));
          if (!isHighRole) {
            sendError(
                resp, 403, "Очистка инвентаря разрешена только для высших ролей (Управляющий)");
            return;
          }
          boolean cleared =
              plugin
                  .getPlayerInventoryManager()
                  .clearInventory(player, session.adminUsername())
                  .get();
          if (cleared) {
            sendSuccess(resp, Map.of("message", "Инвентарь игрока очищен"));
          } else {
            sendError(resp, 400, "Не удалось очистить инвентарь");
          }
        }
        case "kill" -> {
          plugin
              .main()
              .call(
                  () -> {
                    me.lovelace.loveWebAdmin.integration.GameplayPolicy.check(player);
                    if (!player.isOnline())
                      throw new IllegalArgumentException("Игрок вышел с сервера");
                    player.setHealth(0.0);
                    return true;
                  })
              .get();
          plugin
              .getLogManager()
              .logWebAction(session.adminUsername(), "Убил игрока " + player.getName());
          sendSuccess(resp, Map.of("message", "Игрок убит"));
        }
        case "message" -> {
          String msg = stringOrNull(body.get("message"));
          if (msg == null || msg.isBlank()) {
            sendError(resp, 400, "Укажите текст сообщения");
            return;
          }
          plugin
              .main()
              .call(
                  () -> {
                    if (!player.isOnline())
                      throw new IllegalArgumentException("Игрок вышел с сервера");
                    player.sendMessage(
                        Component.text("§d[Персонал " + session.adminUsername() + "] §f" + msg));
                    return true;
                  })
              .get();
          plugin
              .getLogManager()
              .logWebAction(
                  session.adminUsername(),
                  "Отправил сообщение игроку " + player.getName() + ": " + msg);
          sendSuccess(resp, Map.of("message", "Сообщение отправлено"));
        }
        case "mute" -> {
          String reason = stringOrNull(body.get("reason"));
          if (reason == null || reason.isBlank()) reason = "Нарушение правил общения";
          final String finalMuteReason = reason;
          boolean cmdDispatched =
              plugin
                  .main()
                  .call(
                      () -> {
                        if (!player.isOnline())
                          throw new IllegalArgumentException("Игрок вышел с сервера");
                        try {
                          boolean ok =
                              Bukkit.dispatchCommand(
                                  Bukkit.getConsoleSender(),
                                  "mute " + player.getName() + " " + finalMuteReason);
                          player.sendMessage(
                              Component.text(
                                  "§c[WebAdmin] Вы получили блокировку чата. Причина: "
                                      + finalMuteReason));
                          return ok;
                        } catch (Exception ex) {
                          return false;
                        }
                      })
                  .get();
          if (!cmdDispatched) {
            sendError(
                resp, 422, "Команда /mute не поддерживается или не зарегистрирована на сервере");
            return;
          }
          plugin
              .getLogManager()
              .logWebAction(
                  session.adminUsername(),
                  "Замутил игрока " + player.getName() + " по причине: " + reason);
          sendSuccess(resp, Map.of("message", "Игрок замучен"));
        }
        case "vanish" -> {
          boolean cmdDispatched =
              plugin
                  .main()
                  .call(
                      () -> {
                        me.lovelace.loveWebAdmin.integration.GameplayPolicy.check(player);
                        if (!player.isOnline())
                          throw new IllegalArgumentException("Игрок вышел с сервера");
                        try {
                          return Bukkit.dispatchCommand(player, "loveessential:le vanish toggle");
                        } catch (Exception ex) {
                          return false;
                        }
                      })
                  .get();
          if (!cmdDispatched) {
            sendError(resp, 422, "Команда vanish LoveEssential недоступна");
            return;
          }
          plugin
              .getLogManager()
              .logWebAction(
                  session.adminUsername(), "Переключил режим Vanish игроку " + player.getName());
          sendSuccess(
              resp, Map.of("message", "Запрос переключения невидимости передан LoveEssential"));
        }
        default -> sendError(resp, 400, "Неизвестное действие: " + action);
      }
    } catch (Exception e) {
      plugin
          .getLogger()
          .warning("Ошибка выполнения действия над игроком " + playerName + ": " + e.getMessage());
      Throwable cause = e;
      while ((cause instanceof java.util.concurrent.CompletionException
              || cause instanceof java.util.concurrent.ExecutionException)
          && cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof IllegalArgumentException) sendError(resp, 409, cause.getMessage());
      else sendError(resp, 500, "Операция не выполнена. Проверьте журнал сервера");
    }
  }

  @Override
  protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_PLAYERS);
    if (sessionOpt.isEmpty()) return;

    String pathInfo = req.getPathInfo();
    if (pathInfo != null && pathInfo.contains("/notes/")) {
      String idStr = pathInfo.substring(pathInfo.lastIndexOf('/') + 1);
      try {
        int id = Integer.parseInt(idStr);
        plugin.getDatabaseManager().deletePlayerStaffNote(id);
        plugin
            .getLogManager()
            .logWebAction(sessionOpt.get().adminUsername(), "Удалил заметку #" + id);
        sendSuccess(resp, Map.of("message", "Заметка удалена"));
        return;
      } catch (NumberFormatException ignored) {
      }
    }
    sendError(resp, 404, "Не найдено");
  }
}
