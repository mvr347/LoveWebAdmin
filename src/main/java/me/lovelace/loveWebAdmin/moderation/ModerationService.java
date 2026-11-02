package me.lovelace.loveWebAdmin.moderation;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/** Shared game/Discord/web entry point. Bukkit mutations are serialized on the server thread. */
public final class ModerationService implements Listener, AutoCloseable {
  private final LoveWebAdmin plugin;
  private final RestrictionsStore store;
  private volatile List<RestrictionsStore.Pending> pending = List.of();
  private volatile Map<String, RestrictionsStore.Restriction> restrictions = Map.of();
  private org.bukkit.scheduler.BukkitTask timer;
  private volatile boolean closed;
  private int alertTicks;
  private final java.util.concurrent.atomic.AtomicBoolean notifying =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.ConcurrentHashMap<UUID, Long> muteNotices =
      new java.util.concurrent.ConcurrentHashMap<>();

  public ModerationService(LoveWebAdmin plugin) throws Exception {
    this.plugin = plugin;
    store = new RestrictionsStore(plugin.getDataFolder().toPath().resolve("restrictions.db"));
    reload();
  }

  private void reload() throws java.sql.SQLException {
    pending = store.pending();
    var map = new HashMap<String, RestrictionsStore.Restriction>();
    for (var r : store.restrictions()) map.put(r.kind() + ":" + r.uuid(), r);
    restrictions = Map.copyOf(map);
  }

  public void start() {
    expire();
    Bukkit.getPluginManager().registerEvents(this, plugin);
    timer =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  if (++alertTicks % 60 == 0 && notifying.compareAndSet(false, true))
                    plugin
                        .io()
                        .run(
                            () -> {
                              try {
                                for (var alert : store.alertsNeeded())
                                  if (plugin.getDiscordBridge() != null
                                      && plugin
                                          .getDiscordBridge()
                                          .notifyOverdue(
                                              alert.get("actor").toString(),
                                              alert.get("day").toString(),
                                              ((Number) alert.get("count")).intValue()))
                                    store.claimAlert(
                                        alert.get("actor").toString(), alert.get("day").toString());
                              } catch (Exception error) {
                                plugin.getLogger().warning("Уведомление старших будет повторено");
                              }
                            })
                        .whenComplete((result, error) -> notifying.set(false));
                  try {
                    if (pending.stream().anyMatch(p -> p.deadline() <= now())
                        || restrictions.values().stream().anyMatch(r -> !r.active(now()))) expire();
                  } catch (Exception e) {
                    plugin
                        .getLogger()
                        .log(java.util.logging.Level.SEVERE, "Ошибка таймеров модерации", e);
                  }
                },
                1,
                20);
  }

  public List<RestrictionsStore.Pending> pending() {
    return pending;
  }

  public List<RestrictionsStore.Restriction> restrictions() {
    return List.copyOf(restrictions.values());
  }

  public boolean isMuted(UUID id) {
    return active("MUTE", id);
  }

  public boolean isFrozen(UUID id) {
    return active("FREEZE", id);
  }

  private boolean active(String kind, UUID id) {
    var r = restrictions.get(kind + ":" + id);
    return r != null && r.active(now());
  }

  private static long now() {
    return System.currentTimeMillis() / 1000;
  }

  public Optional<RestrictionsStore.Pending> pending(int id) {
    return pending.stream().filter(p -> p.id() == id).findFirst();
  }

  public boolean cancelPending(int id, String actor) {
    var found = pending(id);
    if (found.isEmpty()) return false;
    try {
      if (!store.transition(id, "CANCELLED", now())) return false;
      reload();
      var p = found.get();
      plugin
          .getDatabaseManager()
          .getBanById(id)
          .filter(b -> b.status().equals("DRAFT"))
          .ifPresent(
              b ->
                  plugin
                      .getDatabaseManager()
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
                              b.linkedReportId())));
      plugin.getLogManager().logWebAction(actor, "Отменил предварительный бан #" + id);
      return true;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public boolean mayFinalize(int id) {
    try {
      return store
          .findPending(id)
          .map(p -> p.state().equals("PENDING") && p.deadline() > now())
          .orElse(true);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("Не подтверждён срок предварительной блокировки", e);
    }
  }

  public void finalized(int id) {
    try {
      store.transition(id, "FINALIZED", now());
      reload();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public WebBan ban(String actor, String name) {
    if (!Bukkit.isPrimaryThread()) return plugin.main().call(() -> ban(actor, name)).join();
    validateName(name);
    var existing =
        pending.stream()
            .filter(p -> p.name().equalsIgnoreCase(name) && p.deadline() > now())
            .findFirst();
    if (existing.isPresent())
      return plugin.getDatabaseManager().getBanById(existing.get().id()).orElseThrow();
    expire();
    WebBan draft = plugin.getBanManager().createDraft(actor, name);
    if (draft.id() <= 0) throw new IllegalStateException("Не сохранено дело");
    long at = now();
    String day = LocalDate.now(zone()).toString();
    var p =
        new RestrictionsStore.Pending(
            draft.id(),
            name,
            Objects.toString(draft.targetUuid(), ""),
            Objects.toString(draft.targetIp(), ""),
            actor,
            at,
            at + 300,
            "PENDING",
            day);
    try {
      store.pending(p);
      reload();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("Блокировка не сохранена", e);
    }
    Player target = Bukkit.getPlayerExact(name);
    if (target != null)
      target.kick(
          Component.text(
              "Вы временно заблокированы. Сотрудник оформляет дело №"
                  + draft.id()
                  + ". Срок оформления — 5 минут."));
    plugin
        .getLogManager()
        .logWebAction(
            actor,
            "Предварительный бан #"
                + draft.id()
                + " для "
                + name
                + ", оформление до "
                + p.deadline());
    return draft;
  }

  private ZoneId zone() {
    return ZoneId.of(plugin.getConfig().getString("moderation.timezone", "UTC"));
  }

  private void expire() {
    if (closed) return;
    try {
      for (var p : pending) {
        var legacy = plugin.getDatabaseManager().getBanById(p.id());
        if (legacy.isPresent() && legacy.get().status().equals("ACTIVE")) {
          store.transition(p.id(), "FINALIZED", p.deadline() - 1);
          continue;
        }
        if (p.deadline() > now() || !store.transition(p.id(), "EXPIRED", now())) continue;
        legacy
            .filter(b -> b.status().equals("DRAFT"))
            .ifPresent(
                b ->
                    plugin
                        .getDatabaseManager()
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
                                "EXPIRED",
                                b.durationSeconds(),
                                b.createdAt(),
                                b.expiresAt(),
                                b.linkedReportId())));
        int count = store.overdue(p.actor(), p.day());
        String notice =
            "Дело #" + p.id() + " просрочено, предварительная блокировка " + p.name() + " снята.";
        plugin.getLogManager().logWebAction(p.actor(), notice);
        for (var staff : Bukkit.getOnlinePlayers())
          if (staff.getName().equalsIgnoreCase(p.actor())
              || count >= 3 && staff.hasPermission("lovewebadmin.supervise"))
            staff.sendMessage(Component.text(notice + " Просрочек за день: " + count));
        if (count == 3)
          plugin
              .getLogger()
              .warning(
                  "Сотрудник "
                      + p.actor()
                      + ": "
                      + count
                      + " просроченных банов за "
                      + p.day()
                      + ". Требуется проверка старших.");
      }
      for (var r : restrictions.values())
        if (!r.active(now())) store.remove(r.kind(), r.uuid(), "Система", "Истёк срок", now());
      reload();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public void mute(String actor, String name, long seconds, String reason) {
    restrict("MUTE", actor, name, seconds, reason);
  }

  public void freeze(String actor, String name, String reason) {
    if (restrictions.values().stream()
        .anyMatch(
            r -> r.kind().equals("FREEZE") && r.name().equalsIgnoreCase(name) && r.active(now())))
      return;
    restrict(
        "FREEZE",
        actor,
        name,
        plugin.getConfig().getLong("moderation.freeze-max-seconds", 1800),
        reason);
  }

  private void restrict(String kind, String actor, String name, long seconds, String reason) {
    if (!Bukkit.isPrimaryThread()) {
      plugin
          .main()
          .call(
              () -> {
                restrict(kind, actor, name, seconds, reason);
                return true;
              })
          .join();
      return;
    }
    checkDuration(actor, kind, seconds);
    validateName(name);
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Укажите причину");
    Player target = Bukkit.getPlayerExact(name);
    String uuid =
        target == null
            ? plugin
                .getDatabaseManager()
                .getPlayerStats(name)
                .map(m -> Objects.toString(m.get("uuid"), ""))
                .orElse("")
            : target.getUniqueId().toString();
    if (uuid.isBlank()) throw new IllegalArgumentException("Игрок ещё не известен серверу");
    if (kind.equals("FREEZE") && target == null)
      throw new IllegalArgumentException("Игрок не в сети");
    if (seconds < -1 || seconds > 315360000L)
      throw new IllegalArgumentException("Недопустимый срок ограничения");
    long at = now();
    long expires = seconds < 0 ? -1 : Math.addExact(at, seconds);
    if (seconds == 0) throw new IllegalArgumentException("Срок должен быть положительным");
    try {
      store.put(new RestrictionsStore.Restriction(kind, uuid, name, actor, reason, at, expires));
      reload();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    plugin.getLogManager().logWebAction(actor, kind + ": " + name + " — " + reason);
    if (plugin.getDiscordBridge() != null)
      plugin
          .getDiscordBridge()
          .notifyRestriction(
              new RestrictionsStore.Restriction(kind, uuid, name, actor, reason, at, expires));
    if (target != null)
      target.sendMessage(
          Component.text(
              kind.equals("MUTE")
                  ? "Вас никто не слышит. Причина: " + reason
                  : "Вы заморожены для проверки. Свяжитесь с сотрудником через Discord. "
                      + plugin.getConfig().getString("moderation.discord-contact", "")));
  }

  public boolean remove(String kind, String actor, String name, String reason) {
    if (!Bukkit.isPrimaryThread())
      return plugin.main().call(() -> remove(kind, actor, name, reason)).join();
    var matches =
        restrictions.values().stream()
            .filter(r -> r.kind().equals(kind) && r.name().equalsIgnoreCase(name))
            .toList();
    boolean removed = false;
    try {
      for (var r : matches) removed |= store.remove(kind, r.uuid(), actor, reason, now());
      reload();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    return removed;
  }

  public void kick(String actor, String name, String reason) {
    if (!Bukkit.isPrimaryThread()) {
      plugin
          .main()
          .call(
              () -> {
                kick(actor, name, reason);
                return true;
              })
          .join();
      return;
    }
    var target = Bukkit.getPlayerExact(name);
    if (target == null) throw new IllegalArgumentException("Игрок не в сети");
    target.kick(Component.text("Вы отключены сотрудником " + actor + ". Причина: " + reason));
    plugin.getLogManager().logWebAction(actor, "Отключил " + name + ": " + reason);
  }

  /** Role-specific caps are independent from permission grants. -1 allows permanent penalties. */
  public boolean applyAnticheatWave(String event, String name, String reason, long seconds) {
    validateName(name);
    if (event == null || !event.matches("[a-zA-Z0-9_-]{1,80}"))
      throw new IllegalArgumentException("Некорректное событие волны");
    return plugin
        .getBanManager()
        .finalizeBan(
            0,
            "Античит",
            name,
            reason,
            "Автоматическая волна",
            List.of(),
            true,
            seconds,
            null,
            new me.lovelace.loveWebAdmin.models.BanOrigin(
                me.lovelace.loveWebAdmin.models.BanOrigin.Kind.ANTICHEAT,
                "Античит",
                "vesuvio-wave:" + event));
  }

  public void checkDuration(String actor, String kind, long seconds) {
    var admin = plugin.getDatabaseManager().getAdminByUsername(actor);
    String base = "moderation.duration-limits.";
    long cap = plugin.getConfig().getLong(base + "default." + kind.toLowerCase(Locale.ROOT), -1);
    if (admin.isPresent()) {
      var role = plugin.getDatabaseManager().getRoleById(admin.get().roleId());
      if (role.isPresent() && role.get().isOwner()) return;
      cap =
          plugin
              .getConfig()
              .getLong(
                  base + "roles." + admin.get().roleId() + "." + kind.toLowerCase(Locale.ROOT),
                  cap);
    }
    if (cap >= 0 && (seconds < 0 || seconds > cap))
      throw new IllegalArgumentException("Срок превышает разрешённый для вашей роли");
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void mutedChat(io.papermc.paper.event.player.AsyncChatEvent event) {
    var player = event.getPlayer();
    if (!isMuted(player.getUniqueId())) return;
    event.setCancelled(true);
    player.sendMessage(Component.text(player.getName() + ": ").append(event.message()));
    long at = System.currentTimeMillis();
    muteNotices.compute(
        player.getUniqueId(),
        (id, last) -> {
          if (last == null || at - last >= 3000) {
            player.sendActionBar(Component.text("Вас никто не слышит"));
            return at;
          }
          return last;
        });
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    muteNotices.remove(e.getPlayer().getUniqueId());
  }

  public static long duration(String input) {
    if (Set.of("permanent", "навсегда", "бессрочно").contains(input.toLowerCase(Locale.ROOT)))
      return -1;
    var m =
        java.util.regex.Pattern.compile("([1-9][0-9]{0,8})([smhdw])")
            .matcher(input.toLowerCase(Locale.ROOT));
    if (!m.matches()) throw new IllegalArgumentException("Срок: 30m, 2h, 7d или permanent");
    long unit =
        switch (m.group(2)) {
          case "m" -> 60;
          case "h" -> 3600;
          case "d" -> 86400;
          case "w" -> 604800;
          default -> 1;
        };
    return Math.multiplyExact(Long.parseLong(m.group(1)), unit);
  }

  private static void validateName(String name) {
    if (name == null || !name.matches("[A-Za-z0-9_]{1,16}"))
      throw new IllegalArgumentException("Некорректный ник");
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void login(AsyncPlayerPreLoginEvent e) {
    long at = now();
    String ip = e.getAddress().getHostAddress();
    if (!plugin
        .getDatabaseManager()
        .getActiveBansForPlayer(e.getName(), e.getUniqueId().toString(), ip)
        .isEmpty()) {
      e.disallow(
          AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
          Component.text("Вы заблокированы на сервере. Обжалование доступно через Discord."));
      return;
    }
    if (pending.stream()
        .anyMatch(
            p ->
                p.deadline() > at
                    && (p.name().equalsIgnoreCase(e.getName())
                        || p.uuid().equals(e.getUniqueId().toString())
                        || !p.ip().isBlank() && p.ip().equals(ip))))
      e.disallow(
          AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
          Component.text(
              "Предварительная блокировка: сотрудник оформляет наказание (до 5 минут)."));
  }

  @EventHandler
  public void joined(PlayerJoinEvent e) {
    if (isFrozen(e.getPlayer().getUniqueId()))
      e.getPlayer()
          .sendMessage(
              Component.text("Проверка продолжается. Свяжитесь с сотрудником через Discord."));
  }

  @Override
  public void close() throws Exception {
    closed = true;
    if (timer != null) timer.cancel();
    muteNotices.clear();
    HandlerList.unregisterAll(this);
    store.close();
  }
}
