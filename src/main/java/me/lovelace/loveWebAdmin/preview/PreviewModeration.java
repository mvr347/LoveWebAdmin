package me.lovelace.loveWebAdmin.preview;

import static me.lovelace.loveWebAdmin.preview.PreviewRuntime.*;

import java.nio.file.Path;
import java.util.*;
import me.lovelace.loveWebAdmin.models.WebBan;
import me.lovelace.loveWebAdmin.moderation.*;

/** Uses the production restriction DAO with isolated data and an adjustable test clock. */
final class PreviewModeration implements AutoCloseable {
  final RestrictionsStore store;
  private final PreviewRuntime r;

  PreviewModeration(PreviewRuntime r, Path root) throws Exception {
    this.r = r;
    store = new RestrictionsStore(root.resolve("restrictions.db"));
  }

  void expire() throws Exception {
    for (var p : store.pending())
      if (store.transition(p.id(), "EXPIRED", r.now())) {
        var b = r.database().getBanById(p.id()).orElseThrow();
        r.database()
            .saveBan(
                new WebBan(
                    b.id(),
                    b.targetName(),
                    b.targetUuid(),
                    b.targetIp(),
                    b.creatorName(),
                    b.ruleReason(),
                    b.description(),
                    b.proofUrls(),
                    true,
                    "EXPIRED",
                    b.durationSeconds(),
                    b.createdAt(),
                    b.expiresAt(),
                    b.linkedReportId()));
      }
    for (var restriction : store.restrictions())
      if (!restriction.active(r.now()))
        store.remove(restriction.kind(), restriction.uuid(), "Система", "Истёк срок", r.now());
  }

  Object route(boolean read, Map<String, Object> body) throws Exception {
    expire();
    if (read) {
      if (java.util.stream.Stream.of(
              "VIEW_BANS", "MANAGE_BANS", "MANAGE_MUTES", "FREEZE_PLAYERS", "KICK_PLAYERS")
          .noneMatch(r::has)) throw new Rejected(403, "Нет права просмотра ограничений");
      return Map.of(
          "pending",
          store.pending().stream()
              .filter(p -> r.has("VIEW_BANS") || r.has("MANAGE_BANS"))
              .map(
                  p ->
                      Map.of(
                          "id",
                          p.id(),
                          "name",
                          p.name(),
                          "actor",
                          p.actor(),
                          "createdAt",
                          p.created(),
                          "deadline",
                          p.deadline()))
              .toList(),
          "restrictions",
          store.restrictions().stream()
              .filter(v -> r.has(v.kind().equals("MUTE") ? "MANAGE_MUTES" : "FREEZE_PLAYERS"))
              .map(RestrictionsStore.Restriction::view)
              .toList(),
          "serverTime",
          r.now());
    }
    String action = text(body, "action", "");
    String permission =
        switch (action) {
          case "ban", "unban", "cancel-ban" -> "MANAGE_BANS";
          case "mute", "unmute" -> "MANAGE_MUTES";
          case "freeze", "unfreeze" -> "FREEZE_PLAYERS";
          case "kick" -> "KICK_PLAYERS";
          default -> throw new Rejected(400, "Неизвестное действие");
        };
    r.require(permission);
    if (action.equals("cancel-ban")) {
      String reason = text(body, "reason", "");
      if (reason.trim().length() < 5 || reason.length() > 1000)
        throw new Rejected(400, "Объясните отмену: от 5 до 1000 символов");
      int id = integer(body.get("id"));
      if (!store.transition(id, "CANCELLED", r.now()))
        throw new Rejected(409, "Оформление уже завершено или отменено");
      var b = r.database().getBanById(id).orElseThrow();
      r.database()
          .saveBan(
              new WebBan(
                  b.id(),
                  b.targetName(),
                  b.targetUuid(),
                  b.targetIp(),
                  b.creatorName(),
                  b.ruleReason(),
                  b.description(),
                  b.proofUrls(),
                  b.isIpBan(),
                  "CANCELLED",
                  b.durationSeconds(),
                  b.createdAt(),
                  b.expiresAt(),
                  b.linkedReportId()));
      r.audit(r.actor(), "Отменён предварительный бан №" + id + ": " + reason);
      return Map.of("message", "Оформление отменено");
    }
    String name = text(body, "player", "");
    if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new Rejected(400, "Укажите ник игрока");
    String uuid =
        UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    if (action.equals("ban")) {
      var existing =
          store.pending().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
      if (existing.isPresent())
        return Map.of("id", existing.get().id(), "deadline", existing.get().deadline());
      var b =
          r.database()
              .saveBan(
                  new WebBan(
                      0,
                      name,
                      uuid,
                      "127.0.0.1",
                      r.actor(),
                      "Ожидает оформления",
                      "",
                      "[]",
                      true,
                      "DRAFT",
                      0,
                      r.now(),
                      -1));
      store.pending(
          new RestrictionsStore.Pending(
              b.id(),
              name,
              uuid,
              "127.0.0.1",
              r.actor(),
              r.now(),
              r.now() + 300,
              "PENDING",
              java.time.Instant.ofEpochSecond(r.now())
                  .atZone(java.time.ZoneOffset.UTC)
                  .toLocalDate()
                  .toString()));
      r.audit(r.actor(), "Предварительный бан №" + b.id() + ": игрок отключён");
      return Map.of("id", b.id(), "deadline", r.now() + 300);
    }
    String reason = text(body, "reason", "");
    if (reason.isBlank()) throw new Rejected(400, "Укажите причину");
    if (action.equals("mute") || action.equals("freeze")) {
      long seconds =
          action.equals("freeze") ? 1800 : ModerationService.duration(text(body, "duration", ""));
      if (seconds != -1 && (seconds < 1 || seconds > 315360000))
        throw new Rejected(400, "Недопустимый срок");
      store.put(
          new RestrictionsStore.Restriction(
              action.equals("mute") ? "MUTE" : "FREEZE",
              uuid,
              name,
              r.actor(),
              reason,
              r.now(),
              seconds < 0 ? -1 : r.now() + seconds));
    } else if (action.equals("unmute") || action.equals("unfreeze")) {
      String kind = action.equals("unmute") ? "MUTE" : "FREEZE";
      var current =
          store.restrictions().stream()
              .filter(v -> v.kind().equals(kind) && v.uuid().equals(uuid))
              .findFirst()
              .orElseThrow(() -> new Rejected(409, "Ограничение уже снято"));
      if (body.get("expectedRevision") instanceof String revision
          && !current.revision().equals(revision))
        throw new Rejected(409, "Ограничение изменилось. Обновите список.");
      store.remove(kind, uuid, r.actor(), reason, r.now());
    } else if (action.equals("unban")) {
      for (var p : store.pending())
        if (p.name().equalsIgnoreCase(name)) store.transition(p.id(), "CANCELLED", r.now());
      for (var b : r.database().getAllBans(null))
        if (b.targetName().equalsIgnoreCase(name) && Set.of("ACTIVE", "DRAFT").contains(b.status()))
          r.database()
              .saveBan(
                  new WebBan(
                      b.id(),
                      b.targetName(),
                      b.targetUuid(),
                      b.targetIp(),
                      b.creatorName(),
                      b.ruleReason(),
                      b.description(),
                      b.proofUrls(),
                      true,
                      "UNBANNED",
                      b.durationSeconds(),
                      b.createdAt(),
                      b.expiresAt(),
                      b.linkedReportId()));
    }
    r.audit(r.actor(), action + ": " + name + " — " + reason);
    return Map.of("message", "Действие выполнено в Preview");
  }

  Object history(Map<String, String> params) throws Exception {
    var kinds = new HashSet<String>();
    if (r.has("MANAGE_MUTES")) kinds.add("MUTE");
    if (r.has("FREEZE_PLAYERS")) kinds.add("FREEZE");
    if (kinds.isEmpty() && !r.has("VIEW_BANS") && !r.has("MANAGE_BANS"))
      throw new Rejected(403, "Нет права просмотра ограничений");
    int offset;
    try {
      offset = Integer.parseInt(params.getOrDefault("offset", "0"));
    } catch (NumberFormatException invalid) {
      offset = 0;
    }
    return store.history(kinds, params.getOrDefault("search", ""), Math.clamp(offset, 0, 1000000));
  }

  WebBan finalizeBan(Map<String, Object> body) throws Exception {
    r.require("MANAGE_BANS");
    expire();
    int id = integer(body.get("id"));
    var p =
        store
            .findPending(id)
            .filter(v -> v.state().equals("PENDING") && v.deadline() > r.now())
            .orElseThrow(() -> new Rejected(409, "Срок оформления истёк"));
    long seconds = ((Number) body.getOrDefault("durationSeconds", -1)).longValue();
    if (seconds != -1 && (seconds < 60 || seconds > 315360000 || p.created() + seconds <= r.now()))
      throw new Rejected(400, "Недопустимый срок");
    String reason = text(body, "ruleReason", "");
    if (reason.isBlank()) throw new Rejected(400, "Укажите причину");
    var b =
        r.database()
            .saveBan(
                new WebBan(
                    id,
                    p.name(),
                    p.uuid(),
                    p.ip(),
                    p.actor(),
                    reason,
                    text(body, "description", ""),
                    me.lovelace.loveWebAdmin.utils.JsonUtils.toJson(
                        body.getOrDefault("proofUrls", List.of())),
                    true,
                    "ACTIVE",
                    seconds,
                    p.created(),
                    seconds < 0 ? -1 : p.created() + seconds));
    store.transition(id, "FINALIZED", r.now());
    return b;
  }

  @Override
  public void close() throws Exception {
    store.close();
  }
}
