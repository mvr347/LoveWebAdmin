package me.lovelace.loveWebAdmin.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Управление банами через плагин и веб-панель. Поддерживает черновики, прикрепление доказательств,
 * бан по IP, выполнение бана на сервере и авто-очистку записей старше 2 месяцев (60 дней).
 */
public class BanManager {

  private static final long SIXTY_DAYS_SECONDS = 60L * 24L * 3600L;

  private final LoveWebAdmin plugin;
  private ScheduledTask cleanupTask;

  public BanManager(LoveWebAdmin plugin) {
    this.plugin = plugin;
  }

  public void startCleanupTask() {
    // Очистка каждые 24 часа
    cleanupTask =
        plugin
            .getServer()
            .getAsyncScheduler()
            .runAtFixedRate(
                plugin,
                task -> {
                  long threshold = (System.currentTimeMillis() / 1000L) - SIXTY_DAYS_SECONDS;
                  plugin.getDatabaseManager().purgeOldBans(threshold);
                  plugin.getDatabaseManager().purgeOldChatLogs(threshold);
                },
                1,
                24 * 60,
                TimeUnit.MINUTES);
  }

  public void stopCleanupTask() {
    if (cleanupTask != null) {
      cleanupTask.cancel();
      cleanupTask = null;
    }
  }

  /** Создает черновик бана из игры (/бан <игрок>) или веб-панели. */
  public WebBan createDraft(String creatorName, String targetName) {
    if (!Bukkit.isPrimaryThread())
      return plugin.main().call(() -> createDraft(creatorName, targetName)).join();
    String cleanTarget = targetName.trim();
    String targetUuid = null;
    String targetIp = null;

    Player online = Bukkit.getPlayerExact(cleanTarget);
    if (online != null) {
      targetUuid = online.getUniqueId().toString();
      InetSocketAddress address = online.getAddress();
      if (address != null && address.getAddress() != null) {
        targetIp = address.getAddress().getHostAddress();
      }
    } else {
      var identity = plugin.getDatabaseManager().getPlayerStats(cleanTarget);
      if (identity.isPresent() && identity.get().get("uuid") != null)
        targetUuid = identity.get().get("uuid").toString();
      // Пытаемся взять последний известный IP из статистики
      var stats = plugin.getDatabaseManager().getPlayerStats(cleanTarget);
      if (stats.isPresent() && stats.get().get("lastIp") != null) {
        targetIp = String.valueOf(stats.get().get("lastIp"));
      }
    }

    long now = System.currentTimeMillis() / 1000L;
    WebBan draft =
        new WebBan(
            0,
            cleanTarget,
            targetUuid,
            targetIp,
            creatorName,
            "Черновик (правило не выбрано)",
            "",
            "[]",
            false,
            "DRAFT",
            -1,
            now,
            -1);

    WebBan saved = plugin.getDatabaseManager().saveBan(draft);
    plugin
        .getLogManager()
        .logWebAction(creatorName, "Создал черновик бана для игрока " + cleanTarget);
    return saved;
  }

  /** Оформляет и применяет бан на сервере Bukkit с опциональной привязкой к жалобе. */
  public boolean finalizeBan(
      int banId,
      String actorUsername,
      String targetName,
      String ruleReason,
      String description,
      List<String> proofUrls,
      boolean isIpBan,
      long durationSeconds,
      Integer linkedReportId) {
    var kind =
        "CONSOLE".equalsIgnoreCase(actorUsername)
            ? me.lovelace.loveWebAdmin.models.BanOrigin.Kind.CONSOLE
            : me.lovelace.loveWebAdmin.models.BanOrigin.Kind.STAFF;
    return finalizeBan(
        banId,
        actorUsername,
        targetName,
        ruleReason,
        description,
        proofUrls,
        isIpBan,
        durationSeconds,
        linkedReportId,
        new me.lovelace.loveWebAdmin.models.BanOrigin(kind, actorUsername, null));
  }

  public boolean finalizeBan(
      int banId,
      String actorUsername,
      String targetName,
      String ruleReason,
      String description,
      List<String> proofUrls,
      boolean isIpBan,
      long durationSeconds,
      Integer linkedReportId,
      me.lovelace.loveWebAdmin.models.BanOrigin origin) {
    if (origin.kind() == me.lovelace.loveWebAdmin.models.BanOrigin.Kind.SECURITY)
      throw new IllegalArgumentException(
          "Ограничения LoveAuth нужно применять через его API безопасности, а не через игровой"
              + " BanList");
    if (!Bukkit.isPrimaryThread())
      return plugin
          .main()
          .call(
              () ->
                  finalizeBan(
                      banId,
                      actorUsername,
                      targetName,
                      ruleReason,
                      description,
                      proofUrls,
                      isIpBan,
                      durationSeconds,
                      linkedReportId,
                      origin))
          .join();
    try {
      var duplicate = plugin.moderation().externalBan(origin.externalEventId());
      if (duplicate.isPresent())
        return plugin
            .getDatabaseManager()
            .getBanById(duplicate.get())
            .filter(b -> b.status().equals("ACTIVE"))
            .isPresent();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    if (banId > 0
        && plugin
            .getDatabaseManager()
            .getBanById(banId)
            .filter(b -> b.status().equals("DRAFT"))
            .isEmpty()) return false;
    String cleanTarget = targetName.trim();
    String targetUuid = null;
    String targetIp = null;

    Player online = Bukkit.getPlayerExact(cleanTarget);
    if (online != null) {
      targetUuid = online.getUniqueId().toString();
      InetSocketAddress address = online.getAddress();
      if (address != null && address.getAddress() != null) {
        targetIp = address.getAddress().getHostAddress();
      }
    } else {
      var identity = plugin.getDatabaseManager().getPlayerStats(cleanTarget);
      if (identity.isPresent() && identity.get().get("uuid") != null)
        targetUuid = identity.get().get("uuid").toString();
      var stats = plugin.getDatabaseManager().getPlayerStats(cleanTarget);
      if (stats.isPresent() && stats.get().get("lastIp") != null) {
        targetIp = String.valueOf(stats.get().get("lastIp"));
      }
    }

    long now = System.currentTimeMillis() / 1000L;
    long expiresAt = (durationSeconds > 0) ? (now + durationSeconds) : -1L;
    String proofJson = JsonUtils.toJson(proofUrls != null ? proofUrls : List.of());

    WebBan ban =
        new WebBan(
            banId,
            cleanTarget,
            targetUuid,
            targetIp,
            actorUsername,
            ruleReason,
            description != null ? description : "",
            proofJson,
            isIpBan,
            "ACTIVE",
            durationSeconds,
            now,
            expiresAt,
            linkedReportId);

    var before =
        new me.lovelace.loveWebAdmin.api.events.WebAdminBanEvent(
            cleanTarget, ruleReason, actorUsername, isIpBan, linkedReportId);
    before.setCreatedBan(ban);
    Bukkit.getPluginManager().callEvent(before);
    if (before.isCancelled()) return false;
    WebBan savedBan = plugin.getDatabaseManager().saveBan(ban);

    if (savedBan == null || savedBan.id() <= 0) return false;
    try {
      plugin
          .moderation()
          .source(savedBan.id(), origin.kind().name(), origin.actor(), origin.externalEventId());
    } catch (java.sql.SQLException e) {
      plugin.getDatabaseManager().failBanApplication(savedBan.id());
      throw new IllegalStateException("Не удалось записать источник наказания", e);
    }

    // Применяем бан в Minecraft (в главном потоке)
    final String finalTargetUuid = targetUuid;
    final String finalTargetIp = targetIp;
    try {
      applyBanInBukkit(
          cleanTarget,
          finalTargetUuid,
          finalTargetIp,
          ruleReason,
          description,
          expiresAt,
          isIpBan,
          actorUsername);
    } catch (RuntimeException error) {
      plugin.getDatabaseManager().failBanApplication(savedBan.id());
      throw error;
    }
    // Если бан привязан к жалобе — автоматически принимаем жалобу и награждаем заявителя
    if (linkedReportId != null && linkedReportId > 0) {
      int effectiveBanId = (savedBan != null && savedBan.id() > 0) ? savedBan.id() : banId;
      plugin.getReportManager().acceptReport(linkedReportId, actorUsername, effectiveBanId);
    }

    if (plugin.getDiscordBridge() != null) plugin.getDiscordBridge().notifyBanned(savedBan, origin);

    String reportNote =
        (linkedReportId != null && linkedReportId > 0)
            ? " (по жалобе #" + linkedReportId + ")"
            : "";
    plugin
        .getLogManager()
        .logWebAction(
            actorUsername,
            "Забанил игрока "
                + cleanTarget
                + " по причине ["
                + ruleReason
                + "]"
                + reportNote
                + (isIpBan ? " (+бан по IP)" : ""));

    // Отправляем исходящий вебхук (Discord / HTTP)
    if (plugin.getWebhookManager() != null) {
      Map<String, Object> hookData = new java.util.LinkedHashMap<>();
      hookData.put("targetName", cleanTarget);
      hookData.put("creatorName", actorUsername);
      hookData.put("ruleReason", ruleReason);
      hookData.put("description", description != null ? description : "");
      hookData.put("isIpBan", isIpBan);
      hookData.put("linkedReportId", linkedReportId);
      plugin.getWebhookManager().dispatch("BAN", hookData);
    }

    return true;
  }

  public boolean finalizeBan(
      int banId,
      String actorUsername,
      String targetName,
      String ruleReason,
      String description,
      List<String> proofUrls,
      boolean isIpBan,
      long durationSeconds) {
    return finalizeBan(
        banId,
        actorUsername,
        targetName,
        ruleReason,
        description,
        proofUrls,
        isIpBan,
        durationSeconds,
        null);
  }

  public WebBan finalizeBan(
      int banId,
      String targetName,
      String ruleReason,
      String actorUsername,
      String description,
      List<String> proofUrls,
      boolean isIpBan,
      Integer linkedReportId) {
    finalizeBan(
        banId,
        actorUsername,
        targetName,
        ruleReason,
        description,
        proofUrls,
        isIpBan,
        -1L,
        linkedReportId);
    return plugin.getDatabaseManager().getActiveBan(targetName).orElse(null);
  }

  private void applyBanInBukkit(
      String targetName,
      String targetUuid,
      String targetIp,
      String ruleReason,
      String description,
      long expiresAt,
      boolean isIpBan,
      String banner) {

    Date expiryDate = (expiresAt > 0) ? Date.from(Instant.ofEpochSecond(expiresAt)) : null;
    String cleanDesc = (description != null) ? description.trim() : "";
    if (cleanDesc.endsWith(":")) {
      cleanDesc = cleanDesc.substring(0, cleanDesc.length() - 1).trim();
    }
    String banMessage =
        "§c§lВЫ ЗАБЛОКИРОВАНЫ НА СЕРВЕРЕ!\n"
            + "§7Причина: §f"
            + ruleReason
            + "\n"
            + (!cleanDesc.isBlank() ? "§7Детали: §f" + cleanDesc + "\n" : "")
            + "§7Заблокировал: §e"
            + banner
            + "\n"
            + "§7Срок: §f"
            + (expiryDate != null ? expiryDate.toString() : "Навсегда")
            + "\n"
            + "§8Доказательства зафиксированы в WebAdmin.";

    NativeBanSnapshot previousName =
        NativeBanSnapshot.capture(Bukkit.getBanList(BanList.Type.NAME), targetName);
    NativeBanSnapshot previousIp =
        isIpBan && targetIp != null && !targetIp.isBlank()
            ? NativeBanSnapshot.capture(Bukkit.getBanList(BanList.Type.IP), targetIp)
            : null;
    try {
      // Бан по нику
      var nameBanList = Bukkit.getBanList(BanList.Type.NAME);
      nameBanList.addBan(targetName, banMessage, expiryDate, banner);

      // Бан по IP если запрошено
      if (isIpBan && targetIp != null && !targetIp.isBlank()) {
        var ipBanList = Bukkit.getBanList(BanList.Type.IP);
        ipBanList.addBan(targetIp, banMessage, expiryDate, banner);
      }

      // Если игрок онлайн — немедленно кикаем с экраном бана
      Player online = Bukkit.getPlayerExact(targetName);
      if (online != null && online.isOnline()) {
        online.kickPlayer(banMessage);
      }
    } catch (Exception e) {
      try {
        previousName.restore(Bukkit.getBanList(BanList.Type.NAME), targetName);
      } catch (RuntimeException restore) {
        e.addSuppressed(restore);
      }
      if (previousIp != null)
        try {
          previousIp.restore(Bukkit.getBanList(BanList.Type.IP), targetIp);
        } catch (RuntimeException restore) {
          e.addSuppressed(restore);
        }
      throw new IllegalStateException("Блокировка не применена сервером", e);
    }
  }

  private record NativeBanSnapshot(
      boolean present, String reason, Date expiry, String source, Date created) {
    static NativeBanSnapshot capture(BanList<?> list, String target) {
      org.bukkit.BanEntry<?> entry = list.getBanEntry(target);
      return entry == null
          ? new NativeBanSnapshot(false, null, null, null, null)
          : new NativeBanSnapshot(
              true,
              entry.getReason(),
              entry.getExpiration(),
              entry.getSource(),
              entry.getCreated());
    }

    void restore(BanList<?> list, String target) {
      if (!present) {
        list.pardon(target);
        return;
      }
      org.bukkit.BanEntry<?> entry = list.addBan(target, reason, expiry, source);
      if (entry != null && created != null) {
        entry.setCreated(created);
        entry.save();
      }
    }
  }

  /** Journal first, native change on main, conditional SQLite update, rollback on failure. */
  public synchronized WebBan correct(
      int id,
      String actor,
      String reason,
      String description,
      long remainingSeconds,
      String explanation,
      long revision) {
    var before =
        plugin
            .getDatabaseManager()
            .getBanById(id)
            .orElseThrow(() -> new IllegalArgumentException("Блокировка не найдена"));
    if (!before.status().equals("ACTIVE"))
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
          "Изменять можно только действующую блокировку");
    if (reason == null
        || reason.isBlank()
        || reason.length() > 500
        || description == null
        || description.length() > 4000
        || remainingSeconds != -1 && (remainingSeconds < 60 || remainingSeconds > 315360000L))
      throw new IllegalArgumentException("Проверьте причину, описание и срок");
    long expiry = remainingSeconds < 0 ? -1 : System.currentTimeMillis() / 1000 + remainingSeconds;
    var after =
        new WebBan(
            id,
            before.targetName(),
            before.targetUuid(),
            before.targetIp(),
            before.creatorName(),
            reason.trim(),
            description,
            before.proofUrls(),
            before.isIpBan(),
            before.status(),
            remainingSeconds,
            before.createdAt(),
            expiry,
            before.linkedReportId());
    int history;
    try {
      if ("SECURITY".equals(plugin.moderation().source(id).get("kind")))
        throw new IllegalArgumentException("Ограничение безопасности изменяется через LoveAuth");
      if (!plugin.moderation().reserveRevision("ban", id, revision))
        throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
            "Блокировка изменена; обновите её. Черновик сохранён");
      history =
          plugin
              .moderation()
              .prepareBanCorrection(
                  id, actor, explanation, JsonUtils.toJson(before), JsonUtils.toJson(after));
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
    boolean nativeChanged = false, stored = false;
    try {
      plugin
          .main()
          .call(
              () -> {
                applyBanInBukkit(
                    after.targetName(),
                    after.targetUuid(),
                    after.targetIp(),
                    after.ruleReason(),
                    after.description(),
                    after.expiresAt(),
                    after.isIpBan(),
                    before.creatorName());
                return null;
              })
          .join();
      nativeChanged = true;
      if (!plugin.getDatabaseManager().correctActiveBan(before, after))
        throw new IllegalStateException("Блокировка изменена одновременно; обновите данные");
      stored = true;
      plugin.moderation().finishBanCorrection(history, "APPLIED");
    } catch (Exception error) {
      boolean restored = !nativeChanged;
      if (nativeChanged && !stored)
        try {
          plugin
              .main()
              .call(
                  () -> {
                    var current =
                        plugin
                            .getDatabaseManager()
                            .getBanById(id)
                            .orElseThrow(
                                () ->
                                    new IllegalStateException(
                                        "Нельзя установить итоговое состояние блокировки"));
                    if (current.status().equals("ACTIVE"))
                      applyBanInBukkit(
                          current.targetName(),
                          current.targetUuid(),
                          current.targetIp(),
                          current.ruleReason(),
                          current.description(),
                          current.expiresAt(),
                          current.isIpBan(),
                          current.creatorName());
                    else {
                      Bukkit.getBanList(BanList.Type.NAME).pardon(current.targetName());
                      if (current.isIpBan() && current.targetIp() != null)
                        Bukkit.getBanList(BanList.Type.IP).pardon(current.targetIp());
                    }
                    return null;
                  })
              .join();
          restored = true;
        } catch (Exception restore) {
          error.addSuppressed(restore);
        }
      try {
        plugin
            .moderation()
            .finishBanCorrection(history, restored && !stored ? "ROLLED_BACK" : "UNCERTAIN");
      } catch (Exception journal) {
        error.addSuppressed(journal);
      }
      throw new IllegalStateException("Не удалось изменить блокировку; проверьте историю", error);
    }
    plugin.getLogManager().logWebAction(actor, "Исправил блокировку №" + id + ": " + explanation);
    plugin
        .getDiscordBridge()
        .notifyBanned(
            after,
            new me.lovelace.loveWebAdmin.models.BanOrigin(
                me.lovelace.loveWebAdmin.models.BanOrigin.Kind.STAFF, actor, null));
    return after;
  }

  /** A reduction never extends a finite ban or changes its source. */
  public boolean reduceDuration(int banId, String actor, long remainingSeconds) {
    if (!Bukkit.isPrimaryThread())
      return plugin.main().call(() -> reduceDuration(banId, actor, remainingSeconds)).join();
    if (remainingSeconds < 60 || remainingSeconds > 315360000L)
      throw new IllegalArgumentException("Оставшийся срок: от минуты до 10 лет");
    WebBan ban =
        plugin
            .getDatabaseManager()
            .getBanById(banId)
            .orElseThrow(() -> new IllegalArgumentException("Блокировка не найдена"));
    long now = System.currentTimeMillis() / 1000, expiry = now + remainingSeconds;
    if (!"ACTIVE".equals(ban.status()) || (ban.expiresAt() > 0 && expiry >= ban.expiresAt()))
      throw new IllegalArgumentException("Новый срок должен быть меньше текущего");
    applyBanInBukkit(
        ban.targetName(),
        ban.targetUuid(),
        ban.targetIp(),
        ban.ruleReason(),
        ban.description(),
        expiry,
        ban.isIpBan(),
        ban.creatorName());
    try {
      if (!plugin.getDatabaseManager().shortenBan(ban.id(), ban.expiresAt(), expiry))
        throw new IllegalStateException("Блокировка была изменена. Обновите дело");
    } catch (RuntimeException error) {
      try {
        applyBanInBukkit(
            ban.targetName(),
            ban.targetUuid(),
            ban.targetIp(),
            ban.ruleReason(),
            ban.description(),
            ban.expiresAt(),
            ban.isIpBan(),
            ban.creatorName());
      } catch (RuntimeException restore) {
        error.addSuppressed(restore);
      }
      throw error;
    }
    plugin
        .getLogManager()
        .logWebAction(
            actor, "Сократил блокировку №" + banId + " до " + Instant.ofEpochSecond(expiry));
    return true;
  }

  /** Разбанивает игрока. */
  public boolean unban(int banId, String actorUsername) {
    if (!Bukkit.isPrimaryThread())
      return plugin.main().call(() -> unban(banId, actorUsername)).join();
    Optional<WebBan> banOpt = plugin.getDatabaseManager().getBanById(banId);
    if (banOpt.isEmpty()) return false;

    WebBan ban = banOpt.get();

    // Проверяем Bukkit Event (Cancellable)
    try {
      var unbanEvent =
          new me.lovelace.loveWebAdmin.api.events.WebAdminUnbanEvent(
              banId, ban.targetName(), actorUsername);
      Bukkit.getPluginManager().callEvent(unbanEvent);
      if (unbanEvent.isCancelled()) {
        return false;
      }
    } catch (Exception e) {
      plugin
          .getLogger()
          .warning("[BanManager] Ошибка вызова WebAdminUnbanEvent: " + e.getMessage());
    }

    boolean ok = plugin.getDatabaseManager().unban(banId);
    if (ok) {
      try {
        var others =
            plugin
                .getDatabaseManager()
                .getActiveBansForPlayer(ban.targetName(), ban.targetUuid(), null)
                .stream()
                .filter(b -> b.id() != banId && b.targetName().equalsIgnoreCase(ban.targetName()))
                .toList();
        if (others.isEmpty()) Bukkit.getBanList(BanList.Type.NAME).pardon(ban.targetName());
        else {
          var other =
              others.stream()
                  .max(
                      java.util.Comparator.comparingLong(
                          b -> b.expiresAt() < 0 ? Long.MAX_VALUE : b.expiresAt()))
                  .orElseThrow();
          applyBanInBukkit(
              other.targetName(),
              other.targetUuid(),
              other.targetIp(),
              other.ruleReason(),
              other.description(),
              other.expiresAt(),
              other.isIpBan(),
              other.creatorName());
        }
        if (ban.isIpBan() && ban.targetIp() != null) {
          boolean other =
              plugin.getDatabaseManager().getActiveBansForPlayer("", null, ban.targetIp()).stream()
                  .anyMatch(b -> b.id() != banId && b.isIpBan());
          if (!other) Bukkit.getBanList(BanList.Type.IP).pardon(ban.targetIp());
        }
      } catch (RuntimeException e) {
        plugin.getDatabaseManager().saveBan(ban);
        throw e;
      }
      plugin.getLogManager().logWebAction(actorUsername, "Разбанил игрока " + ban.targetName());

      // Отправляем исходящий вебхук (Discord / HTTP)
      if (plugin.getWebhookManager() != null) {
        Map<String, Object> hookData = new java.util.LinkedHashMap<>();
        hookData.put("banId", banId);
        hookData.put("targetName", ban.targetName());
        hookData.put("actorUsername", actorUsername);
        plugin.getWebhookManager().dispatch("UNBAN", hookData);
      }
    }
    return ok;
  }

  /**
   * Разбанивает игрока по нику. Сначала проверяет активный бан в базе данных LoveWebAdmin, затем
   * снимает ограничения в Bukkit BanList.
   */
  public boolean unban(String targetName, String actorUsername) {
    if (!Bukkit.isPrimaryThread())
      return plugin.main().call(() -> unban(targetName, actorUsername)).join();
    if (targetName == null || targetName.isBlank()) return false;
    String cleanTarget = targetName.trim();

    Optional<WebBan> banOpt = plugin.getDatabaseManager().getActiveBan(cleanTarget);
    if (banOpt.isPresent()) {
      return unban(banOpt.get().id(), actorUsername);
    }

    // Проверяем Bukkit BanList напрямую (если бан был выдан вне WebAdmin)
    boolean wasBannedInBukkit = false;
    try {
      var nameBanList = Bukkit.getBanList(BanList.Type.NAME);
      if (nameBanList.isBanned(cleanTarget)) {
        nameBanList.pardon(cleanTarget);
        wasBannedInBukkit = true;
      }
    } catch (Exception e) {
      plugin.getLogger().warning("Ошибка разбана в Bukkit API: " + e.getMessage());
    }

    if (wasBannedInBukkit) {
      plugin.getLogManager().logWebAction(actorUsername, "Разбанил игрока (Bukkit) " + cleanTarget);
      if (plugin.getWebhookManager() != null) {
        Map<String, Object> hookData = new java.util.LinkedHashMap<>();
        hookData.put("targetName", cleanTarget);
        hookData.put("actorUsername", actorUsername);
        plugin.getWebhookManager().dispatch("UNBAN", hookData);
      }
      return true;
    }

    return false;
  }
}
