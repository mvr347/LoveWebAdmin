package me.lovelace.loveWebAdmin.preview;

import java.util.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static me.lovelace.loveWebAdmin.preview.PreviewRuntime.*;
import me.lovelace.loveWebAdmin.utils.*;

/** Synthetic game adapters. Only PreviewRuntime's isolated data can be changed here. */
final class PreviewFeatures {
  private PreviewFeatures() {}

  static Object stats(PreviewRuntime r) {
    r.require("VIEW_STATS");
    var online =
        r.list("players").stream().filter(p -> Boolean.TRUE.equals(p.get("online"))).toList();
    return Map.of(
        "tps",
        List.of(20, 20, 20),
        "mspt",
        8,
        "memory",
        Map.of("usedMb", 512, "totalMb", 1024, "maxMb", 2048, "freeMb", 512, "percent", 25),
        "players",
        online,
        "onlinePlayers",
        online.size(),
        "maxPlayers",
        100,
        "serverVersion",
        "Paper 26.2 · ПРЕВЬЮ",
        "uptime",
        3600);
  }

  static Object accessList(PreviewRuntime r, String id, boolean read, Map<String, Object> body) {
    if (id.equals("ops")) {
      @SuppressWarnings("unchecked")
      var ops = (List<Object>) r.state.computeIfAbsent("ops", k -> new ArrayList<>());
      if (!read) {
        String name = playerName(body);
        if (text(body, "action", "").equals("add")) {
          if (!ops.contains(name)) ops.add(name);
        } else if (text(body, "action", "").equals("remove")) ops.remove(name);
        else throw new Rejected(400, "Неизвестное действие");
      }
      return Map.of(
          "players",
          ops,
          "operators",
          ops.stream().map(n -> Map.of("name", n, "isOnline", false)).toList(),
          "count",
          ops.size());
    }
    var whitelist = map(r.state.get("whitelist"));
    @SuppressWarnings("unchecked")
    var players = (List<Object>) whitelist.computeIfAbsent("players", k -> new ArrayList<>());
    if (!read)
      switch (text(body, "action", "")) {
        case "enable", "disable" ->
            whitelist.put("enabled", text(body, "action", "").equals("enable"));
        case "add" -> {
          String name = playerName(body);
          if (!players.contains(name)) players.add(name);
        }
        case "remove" -> players.remove(playerName(body));
        default -> throw new Rejected(400, "Неизвестное действие");
      }
    return Map.of(
        "enabled",
        Boolean.TRUE.equals(whitelist.get("enabled")),
        "count",
        players.size(),
        "players",
        players.stream().map(n -> Map.of("name", n, "isOnline", false)).toList());
  }

  private static String playerName(Map<String, Object> body) {
    String value = text(body, "player", "");
    if (!value.matches("[A-Za-z0-9_]{1,16}")) throw new Rejected(400, "Укажите ник игрока");
    return value;
  }

  static Object tools(PreviewRuntime r, String method, String[] parts, Map<String, Object> body)
      throws Exception {
    r.require("MANAGE_API");
    String id = parts[1], action = parts.length > 2 ? parts[2] : "";
    if (id.equals("deliveries")) {
      if (method.equals("GET")) return r.deliveryStatus();
      if (parts.length > 3 && parts[3].equals("resolve")) {
        if (!r.moderation()
            .resolveDelivery(
                integer(action),
                Boolean.TRUE.equals(body.get("delivered")),
                r.actor(),
                reason(body, "explanation"))) throw new Rejected(409, "Результат уже установлен");
        return Map.of();
      }
      if (!r.moderation().retryDelivery(integer(action)))
        throw new Rejected(409, "Повтор доступен для неудачной доставки");
      return Map.of();
    }
    var tokens =
        Map.of(
            "ban",
            List.of("player", "reason", "id", "source", "duration", "expiry"),
            "report",
            List.of("player", "id", "reporter", "reason", "assignee", "status"),
            "appeal",
            List.of("id", "player", "reason", "ban"),
            "clarification",
            List.of("id", "text", "actor"));
    if (method.equals("GET"))
      return Map.of("templates", r.state.get("templates"), "tokens", tokens);
    String kind = text(body, "kind", ""), template = text(body, "text", "");
    if (!tokens.containsKey(kind)) throw new Rejected(400, "Неизвестный шаблон");
    String preview =
        me.lovelace.loveWebAdmin.integration.DiscordTemplates.preview(
            kind,
            template,
            Map.of(
                "player",
                "Ada123",
                "reason",
                "Тестовая причина",
                "id",
                "12",
                "source",
                "Сотрудник",
                "text",
                "Уточнение",
                "actor",
                "PreviewOwner"));
    if (action.equals("save")) map(r.state.get("templates")).put(kind, template);
    return Map.of("preview", preview);
  }

  static Object route(
      PreviewRuntime r, String method, String raw, String[] p, Map<String, Object> body)
      throws Exception {
    String scope = p[0], id = p.length > 1 ? p[1] : "", action = p.length > 2 ? p[2] : "";
    boolean read = method.equals("GET");
    Map<String, String> query = new HashMap<>();
    if (raw.contains("?"))
      for (String pair : raw.split("\\?", 2)[1].split("&")) {
        String[] kv = pair.split("=", 2);
        query.put(kv[0], kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
      }
    if (scope.equals("players")) return players(r, method, id, action, p, body, query);
    if (Set.of("admins", "roles").contains(scope)) return staff(r, method, scope, id, action, body);
    if (scope.equals("auth")) {
      r.require("MANAGE_ADMINS");
      return id.equals("login-history") ? r.list("logs") : r.list("sessions");
    }
    if (scope.equals("logs")) {
      r.require(id.equals("server") ? "VIEW_SERVER_LOGS" : "VIEW_WEB_LOGS");
      var logs = new ArrayList<>(r.list("logs"));
      Collections.reverse(logs);
      return logs;
    }
    if (scope.equals("staff-audit")) {
      r.require("VIEW_STAFF_AUDIT");
      return Map.of(
          "logs",
          r.list("logs"),
          "stats",
          Map.of("total", r.list("logs").size()),
          "admins",
          r.list("admins"));
    }
    if (scope.equals("notifications")) {
      var notices = r.list("notifications");
      if (read)
        return id.equals("unread-count")
            ? Map.of(
                "count", notices.stream().filter(n -> !Boolean.TRUE.equals(n.get("read"))).count())
            : Map.of("notifications", notices);
      if (id.isEmpty()) {
        r.require("MANAGE_ADMINS");
        var notice = new LinkedHashMap<>(body);
        notice.put("id", r.nextId());
        notice.put("createdAt", r.now());
        notice.put("read", false);
        notices.add(notice);
      } else
        for (var notice : notices)
          if (id.equals("read-all") || integer(notice.get("id")) == integer(id))
            notice.put("read", true);
      return Map.of();
    }
    if (scope.equals("loveauth")) {
      r.require("MANAGE_LOVEAUTH");
      if (id.equals("status")) return Map.of("available", true, "preview", true);
      if (action.equals("system") && p.length > 3 && p[3].equals("unblock-ip"))
        return Map.of("message", "Смоделирована разблокировка IP");
      var player = player(r, action);
      String verb = p.length > 3 ? p[3] : "";
      if (!read) {
        switch (verb) {
          case "unlock" -> player.put("authLocked", false);
          case "delete" -> player.put("authDeleted", true);
          case "reset-session", "password" -> player.put("authSession", false);
          default -> throw new Rejected(404, "Действие не найдено");
        }
        r.audit(r.actor(), "LoveAuth · смоделировано " + verb);
        return Map.of();
      }
      return Map.of(
          "available",
          true,
          "found",
          true,
          "username",
          player.get("name"),
          "uuid",
          player.get("uuid"),
          "isRegistered",
          !Boolean.TRUE.equals(player.get("authDeleted")),
          "isLocked",
          Boolean.TRUE.equals(player.get("authLocked")),
          "isIpBlocked",
          false,
          "hasDiscord",
          Boolean.TRUE.equals(player.get("hasDiscord")),
          "alts",
          List.of());
    }
    if (scope.equals("vesuvio")) return vesuvio(r, read, id, action, p, body);
    if (scope.equals("analytics")) {
      r.require("VIEW_ANALYTICS");
      if (id.equals("top-players")) return r.list("players");
      if (id.equals("commands")) return List.of(Map.of("command", "spawn", "count", 12));
      return Map.ofEntries(
          Map.entry("totalPlayers", r.list("players").size()),
          Map.entry("uniquePlayers", 3),
          Map.entry("newPlayers", 1),
          Map.entry("totalPlaytimeHours", 30),
          Map.entry("avgPlaytimeMinutes", 600),
          Map.entry("peakOnline", 2),
          Map.entry("avgOnline", 1.5),
          Map.entry("currentOnline", 2),
          Map.entry("history", List.of()),
          Map.entry("hourlyActivity", Collections.nCopies(24, 1)),
          Map.entry("prevHourlyActivity", Collections.nCopies(24, 0)),
          Map.entry("retentionRate", 66),
          Map.entry("periodLabel", "Песочница"),
          Map.entry("prevPeriodLabel", "Тестовый период"),
          Map.entry("totalPlayersDatabase", 3),
          Map.entry("monthlyUniquePlayers", 3));
    }
    if (scope.equals("economy")) {
      r.require(read ? "VIEW_ECONOMY" : "MANAGE_ECONOMY");
      if (Set.of("history", "transactions", "anomalies").contains(id))
        return r.list("economyHistory");
      if (!read) {
        var player = player(r, text(body, "player", text(body, "playerName", "")));
        double amount = ((Number) body.getOrDefault("amount", 0)).doubleValue();
        if (!Double.isFinite(amount) || amount < 0) throw new Rejected(400, "Неверная сумма");
        String verb = text(body, "action", id);
        double current = ((Number) player.getOrDefault("balance", 0)).doubleValue();
        double next =
            verb.equals("add")
                ? current + amount
                : verb.equals("remove") ? current - amount : amount;
        if (next < 0) throw new Rejected(409, "Недостаточно средств");
        player.put("balance", next);
        r.list("economyHistory")
            .add(
                Map.of(
                    "player",
                    player.get("name"),
                    "amount",
                    amount,
                    "action",
                    verb,
                    "createdAt",
                    r.now()));
        return Map.of();
      }
      return Map.of(
          "available",
          true,
          "scaleVersion",
          1,
          "totalOnline",
          r.list("players").stream()
              .mapToDouble(x -> ((Number) x.getOrDefault("balance", 0)).doubleValue())
              .sum(),
          "players",
          r.list("players"),
          "history",
          r.list("economyHistory"));
    }
    if (Set.of("keys", "api-keys", "webhooks").contains(scope)) {
      r.require("MANAGE_API");
      var values = r.list(!scope.equals("webhooks") ? "keys" : "webhooks");
      if (read) return values;
      if (method.equals("DELETE")) {
        values.removeIf(v -> integer(v.get("id")) == integer(id));
        return Map.of();
      }
      var value = new LinkedHashMap<>(body);
      value.put("id", r.nextId());
      value.put("key", "PREVIEW-NOT-A-REAL-API-KEY");
      value.put("createdAt", r.now());
      value.put("isActive", true);
      values.add(value);
      return value;
    }
    if (scope.equals("lockdown")) {
      r.require("MANAGE_LOCKDOWN");
      if (!read) r.state.put("lockdown", Boolean.TRUE.equals(body.get("enabled")));
      return Map.of("active", Boolean.TRUE.equals(r.state.get("lockdown")), "preview", true);
    }
    if (scope.equals("developer")) {
      r.require("MANAGE_API");
      return route(
          r, method, raw.replace("/developer", ""), Arrays.copyOfRange(p, 1, p.length), body);
    }
    throw new Rejected(404, "Маршрут песочницы не найден: " + method + " " + raw);
  }

  private static Map<String, Object> player(PreviewRuntime r, String name) {
    return r.list("players").stream()
        .filter(p -> text(p, "name", "").equalsIgnoreCase(name) || text(p, "uuid", "").equals(name))
        .findFirst()
        .orElseThrow(() -> new Rejected(404, "Тестовый игрок не найден"));
  }

  private static Object players(
      PreviewRuntime r,
      String method,
      String id,
      String action,
      String[] parts,
      Map<String, Object> body,
      Map<String, String> query) {
    r.require("VIEW_PLAYERS");
    if (id.isEmpty() || Set.of("all", "search").contains(id))
      return r.list("players").stream()
          .filter(
              p ->
                  text(p, "name", "")
                      .toLowerCase(Locale.ROOT)
                      .contains(query.getOrDefault("q", "").toLowerCase(Locale.ROOT)))
          .map(
              p -> {
                var out = new LinkedHashMap<>(p);
                out.remove("inventory");
                if (!r.has("VIEW_PLAYER_IP")) out.remove("ip");
                return out;
              })
          .toList();
    var player = player(r, URLDecoder.decode(id, StandardCharsets.UTF_8));
    @SuppressWarnings("unchecked")
    var inventory = (List<Map<String, Object>>) player.get("inventory");
    if (action.equals("inventory")) {
      r.require("VIEW_PLAYER_INVENTORY");
      return Map.of(
          "storage",
          inventory,
          "armor",
          Map.of(),
          "offhand",
          Map.of("empty", true),
          "enderchest",
          List.of(),
          "health",
          player.get("health"),
          "maxHealth",
          20,
          "foodLevel",
          20,
          "level",
          16,
          "gameMode",
          player.get("gameMode"));
    }
    if (action.equals("confiscated")) {
      r.require("RESTORE_ITEMS");
      return r.list("evidence").stream()
          .filter(e -> player.get("name").equals(e.get("owner")))
          .toList();
    }
    if (action.equals("chat"))
      return List.of(
          Map.of(
              "playerName",
              player.get("name"),
              "message",
              "Тестовое сообщение",
              "createdAt",
              r.now()));
    if (action.equals("alts")) {
      r.require("VIEW_PLAYER_IP");
      return Map.of(
          "primaryIp",
          player.get("ip"),
          "alts",
          List.of(),
          "sharedIps",
          List.of(),
          "directAlts",
          List.of(),
          "subnetAlts",
          List.of());
    }
    if (action.equals("notes")) {
      @SuppressWarnings("unchecked")
      var notes =
          (List<Map<String, Object>>) player.computeIfAbsent("notes", k -> new ArrayList<>());
      if (method.equals("GET")) return notes;
      r.require("MANAGE_PLAYERS");
      if (method.equals("DELETE")) {
        int note = integer(parts[3]);
        notes.removeIf(n -> integer(n.get("id")) == note);
      } else
        notes.add(
            Map.of(
                "id",
                r.nextId(),
                "author",
                r.actor(),
                "note",
                reason(body, "note"),
                "createdAt",
                r.now()));
      return Map.of();
    }
    if (action.equals("action")) {
      if (method.equals("GET")) throw new Rejected(405, "Используйте POST");
      String verb = text(body, "action", "");
      r.require(
          switch (verb) {
            case "remove_item", "clear_inventory" -> "CONFISCATE_ITEMS";
            case "restore_item" -> "RESTORE_ITEMS";
            case "freeze", "unfreeze" -> "FREEZE_PLAYERS";
            case "kick" -> "KICK_PLAYERS";
            case "kill" -> "KILL_PLAYERS";
            case "gamemode" -> "CHANGE_GAMEMODE";
            case "teleport_spawn" -> "TELEPORT_PLAYERS";
            default -> "MANAGE_PLAYERS";
          });
      if (!Boolean.TRUE.equals(player.get("online"))) throw new Rejected(409, "Игрок не в сети");
      switch (verb) {
        case "remove_item", "clear_inventory" -> {
          String why = reason(body, "reason");
          var targets =
              verb.equals("clear_inventory")
                  ? new ArrayList<>(inventory)
                  : inventory.stream()
                      .filter(
                          i ->
                              integer(i.get("slot")) == integer(body.get("slot"))
                                  && Objects.equals(i.get("fingerprint"), body.get("fingerprint")))
                      .toList();
          if (verb.equals("remove_item") && targets.size() != 1)
            throw new Rejected(409, "Снимок предмета устарел");
          for (var item : targets) {
            var held = new LinkedHashMap<>(item);
            held.put("id", String.valueOf(r.nextId()));
            held.put("owner", player.get("name"));
            held.put("actor", r.actor());
            held.put("reason", why);
            held.put("state", "HELD");
            held.put("createdAt", r.now());
            r.list("evidence").add(held);
            inventory.remove(item);
          }
        }
        case "restore_item" -> {
          var held =
              r.list("evidence").stream()
                  .filter(
                      i ->
                          text(i, "id", "").equals(text(body, "id", ""))
                              && i.get("owner").equals(player.get("name"))
                              && i.get("state").equals("HELD"))
                  .findFirst()
                  .orElseThrow(() -> new Rejected(409, "Предмет уже возвращён"));
          if (inventory.size() >= 36) throw new Rejected(409, "Нет свободного места");
          int slot = 0;
          var used = new HashSet<Integer>();
          inventory.forEach(i -> used.add(integer(i.get("slot"))));
          while (used.contains(slot)) slot++;
          var restored = new LinkedHashMap<>(held);
          restored.put("slot", slot);
          restored.put("fingerprint", "preview-" + r.nextId());
          inventory.add(restored);
          held.put("state", "RETURNED");
          held.put("returnedBy", r.actor());
        }
        case "freeze", "unfreeze" -> player.put("isFrozen", verb.equals("freeze"));
        case "kick" -> {
          player.put("online", false);
          player.put("isOnline", false);
        }
        case "kill" -> player.put("health", 0);
        case "heal" -> {
          player.put("health", 20);
          player.put("foodLevel", 20);
        }
        case "feed" -> player.put("foodLevel", 20);
        case "gamemode" -> {
          String mode =
              text(body, "gameMode", text(body, "mode", "SURVIVAL")).toUpperCase(Locale.ROOT);
          if (!Set.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR").contains(mode))
            throw new Rejected(400, "Неизвестный режим");
          player.put("gameMode", mode);
        }
        case "vanish" -> player.put("vanished", !Boolean.TRUE.equals(player.get("vanished")));
        case "teleport_spawn", "message", "mute" -> {}
        default -> throw new Rejected(400, "Неизвестное действие");
      }
      r.audit(r.actor(), "Смоделировано " + verb + " над " + player.get("name"));
      return Map.of("message", "Тестовое действие выполнено");
    }
    var profile = new LinkedHashMap<>(player);
    profile.remove("inventory");
    if (!r.has("VIEW_PLAYER_IP")) profile.remove("ip");
    var bans =
        r.database().getAllBans(null).stream()
            .filter(b -> b.targetName().equals(player.get("name")))
            .toList();
    profile.putAll(
        Map.of(
            "isBanned",
            bans.stream().anyMatch(b -> b.status().equals("ACTIVE")),
            "bans",
            bans,
            "activeBans",
            bans.stream().filter(b -> b.status().equals("ACTIVE")).toList(),
            "staffNotes",
            player.getOrDefault("notes", List.of()),
            "trustScore",
            75,
            "trustGrade",
            "B",
            "trustDescription",
            "Тестовый показатель доверия",
            "stats",
            Map.of("deaths", 4, "kills", 2, "blocksBroken", 320),
            "loveAuth",
            Map.of(
                "available",
                true,
                "isRegistered",
                true,
                "isLocked",
                Boolean.TRUE.equals(player.get("authLocked"))),
            "vesuvio",
            Map.of("available", true, "vl", 0, "risk", 10, "trust", 90)));
    return profile;
  }

  private static Object staff(
      PreviewRuntime r,
      String method,
      String scope,
      String id,
      String action,
      Map<String, Object> body) {
    boolean roles = scope.equals("roles"), read = method.equals("GET");
    r.require(roles ? "MANAGE_ROLES" : "MANAGE_ADMINS");
    String key = roles ? "roles" : "admins";
    var values = r.list(key);
    if (id.equals("pending") && read)
      return values.stream().filter(value -> "PENDING".equals(value.get("status"))).toList();
    if (id.equals("terminate-all")) return Map.of("message", "Тестовые сессии завершены");
    if (id.equals("invites")) {
      var invites = r.list("invites");
      if (read) return invites;
      if (method.equals("DELETE")) {
        invites.removeIf(i -> integer(i.get("id")) == integer(action));
        return Map.of();
      }
      if (Boolean.TRUE.equals(
          r.find("roles", String.valueOf(integer(body.get("roleId")))).get("isOwner")))
        throw new Rejected(403, "Управляющего нельзя назначить приглашением");
      var invite =
          new LinkedHashMap<String, Object>(
              Map.of(
                  "id",
                  r.nextId(),
                  "code",
                  "PREVIEW-" + UUID.randomUUID(),
                  "username",
                  text(body, "username", "NewModerator"),
                  "roleId",
                  integer(body.get("roleId")),
                  "status",
                  "PENDING",
                  "createdAt",
                  r.now(),
                  "expiresAt",
                  r.now() + 86400));
      invites.add(invite);
      return invite;
    }
    if (id.isEmpty()) {
      if (read) return values;
      var row = new LinkedHashMap<String, Object>();
      for (String field :
          roles ? List.of("name", "color", "permissions") : List.of("username", "roleId"))
        if (body.containsKey(field)) row.put(field, body.get(field));
      if (!roles) {
        var role = r.find("roles", String.valueOf(integer(row.get("roleId"))));
        if (Boolean.TRUE.equals(role.get("isOwner")))
          throw new Rejected(403, "Нельзя создать управляющего через список сотрудников");
        r.setPassword(text(row, "username", ""), text(body, "password", "PreviewOnly!25"));
      }
      row.put("id", r.nextId());
      row.put("isOwner", false);
      row.put("status", "ACTIVE");
      row.put("createdAt", r.now());
      values.add(row);
      return row;
    }
    var value = r.find(key, id);
    if (read) return value;
    if (Boolean.TRUE.equals(
        (roles ? value : r.find("roles", String.valueOf(integer(value.get("roleId")))))
            .get("isOwner"))) throw new Rejected(409, "Управляющий защищён");
    if (method.equals("DELETE")) {
      if (roles && r.list("admins").stream().anyMatch(a -> integer(a.get("roleId")) == integer(id)))
        throw new Rejected(409, "Роль используется сотрудником");
      values.remove(value);
    } else if (action.equals("role")) {
      var role = r.find("roles", String.valueOf(integer(body.get("roleId"))));
      if (Boolean.TRUE.equals(role.get("isOwner")))
        throw new Rejected(403, "Нельзя назначить роль управляющего");
      value.put("roleId", body.get("roleId"));
    } else if (method.equals("PUT")) {
      for (String field : roles ? List.of("name", "color", "permissions") : List.of("status"))
        if (body.containsKey(field)) value.put(field, body.get(field));
    } else if (action.equals("approve")) value.put("status", "ACTIVE");
    else if (action.equals("reject")) value.put("status", "REJECTED");
    else if (action.equals("password")) r.require("MANAGE_PASSWORDS");
    else if (!action.equals("terminate-sessions")) throw new Rejected(404, "Действие не найдено");
    r.audit(r.actor(), "Изменена тестовая запись " + scope + " №" + id);
    return Map.of();
  }

  private static Object vesuvio(
      PreviewRuntime r,
      boolean read,
      String id,
      String action,
      String[] p,
      Map<String, Object> body) {
    r.require("VIEW_VESUVIO");
    if (id.equals("reviews")) {
      r.require("REVIEW_VESUVIO_SAMPLES");
      return r.list("vesuvioReviews");
    }
    if (id.equals("review")) {
      r.require("REVIEW_VESUVIO_SAMPLES");
      if (read) throw new Rejected(405, "Используйте POST");
      String verdict = text(body, "verdict", "");
      if (!Set.of("legit", "cheat").contains(verdict))
        throw new Rejected(400, "Неизвестный вердикт");
      var sample =
          r.list("vesuvioReviews").stream()
              .filter(s -> text(s, "id", "").equals(action))
              .findFirst()
              .orElseThrow(() -> new Rejected(409, "Образец уже рассмотрен"));
      r.list("vesuvioReviews").remove(sample);
      return Map.of("accepted", true, "preview", true);
    }
    if (id.equals("models")) {
      r.require("MANAGE_VESUVIO_MODELS");
      if (read) throw new Rejected(405, "Используйте POST");
      return Map.of("message", "Перезагрузка моделей смоделирована", "preview", true);
    }
    if (id.equals("engine") || id.equals("selflearn")) {
      r.require("VIEW_VESUVIO_ADVANCED");
      return Map.of(
          "available",
          true,
          "preview",
          true,
          "modelLoaded",
          true,
          "mlAvailable",
          true,
          "webEnabled",
          false,
          "capabilities",
          List.of("reviews", "models-reload"));
    }
    if (id.equals("suspects"))
      return r.list("players").stream()
          .filter(s -> Boolean.TRUE.equals(s.get("suspect")))
          .map(
              s -> {
                var out = new LinkedHashMap<>(s);
                out.remove("inventory");
                out.remove("ip");
                out.put("vl", 20);
                out.put("risk", 60);
                out.put("trust", 40);
                return out;
              })
          .toList();
    if (id.equals("violations") || id.equals("punishments")) return List.of();
    if (id.equals("player")) {
      var player = player(r, action);
      if (read) {
        r.require("VIEW_VESUVIO_ADVANCED");
        return Map.of(
            "uuid",
            player.get("uuid"),
            "name",
            player.get("name"),
            "vl",
            0,
            "risk",
            10,
            "trust",
            90,
            "preview",
            true);
      }
      r.require("MANAGE_VESUVIO");
      if (p.length > 3 && p[3].equals("suspect"))
        player.put("suspect", Boolean.TRUE.equals(body.get("suspect")));
      return Map.of();
    }
    return Map.of(
        "available",
        true,
        "preview",
        true,
        "integrationVersion",
        2,
        "capabilities",
        List.of("reviews", "models-reload"));
  }
}
