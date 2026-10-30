package me.lovelace.loveWebAdmin.preview;

import dev.lovelace.lovecore.api.discord.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.integration.CoreDiscordExtensions;
import me.lovelace.loveWebAdmin.integration.DiscordMessagePublisher;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/**
 * Bounded isolated sessions and their real Discord delivery workers. Never dispatches game actions.
 */
public final class PreviewService
    implements AutoCloseable, TicketMessageListenerV2, DiscordInteractionListener {
  public record Session(
      String id, String secretHash, int owner, PreviewRuntime runtime, long created) {}

  private final LoveWebAdmin plugin;
  private final Path root;
  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
  private final AtomicBoolean pumping = new AtomicBoolean();
  private final ConcurrentHashMap<String, PreviewDiscordLab> labs = new ConcurrentHashMap<>();
  private final List<String> discordProblems;
  private final boolean routingSafe;
  private final java.util.concurrent.atomic.AtomicInteger interactionPending =
      new java.util.concurrent.atomic.AtomicInteger();
  private final String category, channel;
  private final Set<String> recipients;
  private final org.bukkit.scheduler.BukkitTask task;
  private volatile DiscordService bound;
  private volatile boolean closed;

  public PreviewService(LoveWebAdmin plugin) throws Exception {
    this.plugin = plugin;
    root = plugin.getDataFolder().toPath().resolve("preview");
    Files.createDirectories(root);
    // A bad Discord section must not take the whole panel down: Preview keeps working without delivery.
    var discord =
        PreviewDiscordConfig.parse(
            plugin.getConfig().getString("preview.discord.category-id", ""),
            plugin.getConfig().getString("preview.discord.channel-id", ""),
            plugin.getConfig().getList("preview.discord.allowed-user-ids", List.of()));
    discord.problems().forEach(problem -> plugin.getLogger().warning("Preview Discord: " + problem));
    routingSafe =
        List.of(
                "discord.tickets.panel-channel-id",
                "discord.tickets.queue-channel-id",
                "discord.reports-channel-id",
                "discord.appeals-channel-id")
            .stream()
            .noneMatch(
                key ->
                    !discord.channel().isBlank()
                        && discord.channel().equals(plugin.getConfig().getString(key, "")));
    var problems = new ArrayList<String>(discord.problems());
    if (!routingSafe) problems.add("Тестовый канал совпадает с рабочим каналом обращений");
    discordProblems = List.copyOf(problems);
    category = discord.category();
    channel = discord.channel();
    recipients = discord.recipients();
    // State and hashed credentials survive restart; capabilities are never logged.
    try (var folders = Files.list(root)) {
      for (Path folder :
          folders
              .filter(
                  path ->
                      Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                          && path.getFileName().toString().matches("[a-f0-9]{32}"))
              .toList()) {
        Path meta = folder.resolve("session.json");
        if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)) continue;
        try {
          if (Files.size(meta) > 8192) throw new IllegalArgumentException("metadata too large");
          var data = JsonUtils.parseObject(Files.readString(meta));
          String id = String.valueOf(data.get("id")), hash = String.valueOf(data.get("secretHash"));
          long created = ((Number) data.get("created")).longValue();
          if (!id.equals(folder.getFileName().toString()) || !hash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid metadata");
          if (System.currentTimeMillis() - created > 86400000) {
            Files.delete(meta);
            continue;
          }
          if (sessions.size() >= 8) break;
          sessions.put(
              id,
              new Session(
                  id,
                  hash,
                  ((Number) data.get("owner")).intValue(),
                  new PreviewRuntime(folder, id),
                  created));
        } catch (Exception error) {
          plugin
              .getLogger()
              .warning(
                  "Пропущено повреждённое хранилище Preview: "
                      + folder.getFileName()
                      + " ("
                      + error.getClass().getSimpleName()
                      + ")");
        }
      }
    }
    task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::pump, 20, 20);
  }

  private static String random() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public synchronized Map<String, Object> create(int owner) throws Exception {
    if (closed) throw new PreviewRuntime.Rejected(503, "Preview останавливается");
    for (var old : List.copyOf(sessions.values()))
      if (System.currentTimeMillis() - old.created() > 86400000) remove(old);
    if (sessions.size() >= 8)
      throw new PreviewRuntime.Rejected(429, "Достигнут лимит 8 песочниц; закройте ненужную");
    String id = random(), secret = random() + random();
    Path folder = root.resolve(id);
    Files.createDirectories(folder);
    var session =
        new Session(
            id,
            me.lovelace.loveWebAdmin.database.ModerationStore.hash(secret),
            owner,
            new PreviewRuntime(folder, id),
            System.currentTimeMillis());
    try {
      Path metadata = folder.resolve("session.tmp");
      Files.writeString(
          metadata,
          JsonUtils.toJson(
              Map.of(
                  "id",
                  id,
                  "secretHash",
                  session.secretHash(),
                  "owner",
                  owner,
                  "created",
                  session.created())),
          StandardOpenOption.CREATE_NEW);
      Files.move(metadata, folder.resolve("session.json"), StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception error) {
      session.runtime().close();
      throw error;
    }
    sessions.put(id, session);
    return Map.of(
        "id",
        id,
        "token",
        secret,
        "state",
        session.runtime().snapshot(),
        "discordConfigured",
        !category.isBlank() && !channel.isBlank());
  }

  public Session authorize(String id, String secret) {
    var session = sessions.get(id);
    if (session == null
        || secret == null
        || !java.security.MessageDigest.isEqual(
            session.secretHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            me.lovelace
                .loveWebAdmin
                .database
                .ModerationStore
                .hash(secret)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
      throw new PreviewRuntime.Rejected(401, "Сессия Preview недействительна");
    if (closed || !ownerAllowed(session))
      throw new PreviewRuntime.Rejected(403, "Доступ к Preview отозван");
    return session;
  }

  private boolean ownerAllowed(Session session) {
    return System.currentTimeMillis() - session.created() <= 86400000
        && plugin
            .getDatabaseManager()
            .getAdminById(session.owner())
            .filter(admin -> "ACTIVE".equals(admin.status()))
            .flatMap(admin -> plugin.getDatabaseManager().getRoleById(admin.roleId()))
            .map(role -> role.isOwner() || role.permissions().contains(Permission.MANAGE_PREVIEW))
            .orElse(false);
  }

  public List<Map<String, Object>> list(int owner) {
    return sessions.values().stream()
        .filter(session -> session.owner() == owner)
        .sorted(java.util.Comparator.comparingLong(Session::created).reversed())
        .map(
            session ->
                Map.<String, Object>of(
                    "id",
                    session.id(),
                    "createdAt",
                    session.created() / 1000,
                    "expiresAt",
                    session.created() / 1000 + 86400))
        .toList();
  }

  public void removeOwned(String id, int owner) throws Exception {
    var session = sessions.get(id);
    if (session == null || session.owner() != owner)
      throw new PreviewRuntime.Rejected(404, "Песочница не найдена");
    remove(session);
  }

  private static void deleteFolder(Path folder) throws java.io.IOException {
    try (var paths = Files.walk(folder)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  public synchronized void remove(Session session) throws Exception {
    var lab = labs.get(session.id());
    if (lab != null && !lab.busy.compareAndSet(false, true))
      throw new PreviewRuntime.Rejected(
          409, "Дождитесь окончания проверки Discord перед удалением песочницы");
    sessions.remove(session.id(), session);
    if (lab != null) {
      labs.remove(session.id(), lab);
      lab.close();
    }
    Files.deleteIfExists(root.resolve(session.id()).resolve("session.json"));
    var discord = bound;
    if (discord != null) {
      Object tickets = session.runtime().snapshot().get("discordTickets");
      if (tickets instanceof Map<?, ?> map)
        for (Object value : map.values())
          discord.closeTicketChannel(String.valueOf(value), "[ТЕСТ] Песочница закрыта");
    }
    session.runtime().close();
    deleteFolder(root.resolve(session.id()));
  }

  /** Runs on main solely to resolve the Core service; all database/network work is delegated. */
  private void pump() {
    if (closed || !plugin.isPreviewEnabled()) return;
    var service =
        plugin.getDiscordBridge() == null
            ? Optional.<DiscordService>empty()
            : plugin.getDiscordBridge().discord();
    var current = service.orElse(null);
    if (current != bound) {
      if (bound != null) {
        bound.unregisterTicketMessageListenerV2(this);
        bound.unregisterInteractionListener(this);
      }
      bound = current;
      if (current != null) {
        current.registerTicketMessageListenerV2(this);
        CoreDiscordExtensions.claimPrefix(current, "lwa:preview:");
        current.registerInteractionListener(this);
      }
    }
    if (!pumping.compareAndSet(false, true)) return;
    plugin
        .io()
        .run(
            () -> {
              try {
                for (var session : sessions.values())
                  for (var delivery : session.runtime().dueDeliveries()) {
                    if (closed) return;
                    if (!ownerAllowed(session)) continue;
                    int id = ((Number) delivery.get("id")).intValue(),
                        attempts = ((Number) delivery.get("attempts")).intValue() + 1;
                    if (!session.runtime().takeDelivery(id)) continue;
                    try {
                      boolean ok =
                          send(
                                  session,
                                  id,
                                  JsonUtils.parseObject(String.valueOf(delivery.get("payload"))))
                              .get(30, TimeUnit.SECONDS);
                      session
                          .runtime()
                          .delivered(
                              id, ok, attempts, ok ? null : "Discord не принял тестовое сообщение");
                    } catch (Exception error) {
                      session
                          .runtime()
                          .uncertainDelivery(
                              id,
                              "Результат отправки неизвестен: "
                                  + error.getClass().getSimpleName()
                                  + ". Проверьте тестовый Discord перед повтором");
                      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    }
                  }
              } catch (Exception error) {
                plugin
                    .getLogger()
                    .warning(
                        "Ошибка тестовой доставки Discord: " + error.getClass().getSimpleName());
              } finally {
                pumping.set(false);
              }
            })
        .exceptionally(
            error -> {
              pumping.set(false);
              return null;
            });
  }

  private CompletableFuture<Boolean> send(
      Session session, int deliveryId, Map<String, Object> payload) throws Exception {
    var service = bound;
    if (closed
        || !plugin.isPreviewEnabled()
        || !ownerAllowed(session)
        || service == null
        || !service.isEnabled()
        || category.isBlank()
        || channel.isBlank()
        || recipients.isEmpty()
        || !routingSafe
        || session.runtime().simulatedDiscordFailure())
      return CompletableFuture.completedFuture(false);
    String event = String.valueOf(payload.get("event"));
    @SuppressWarnings("unchecked")
    var data = (Map<String, Object>) payload.get("data");
    String message = "[ТЕСТ] " + event + "\n" + JsonUtils.toJson(data);
    if (message.length() > 1900) message = message.substring(0, 1900);
    String body =
        JsonUtils.toJson(
            Map.of(
                "content",
                message,
                "allowed_mentions",
                Map.of("parse", List.of()),
                "nonce",
                me.lovelace
                    .loveWebAdmin
                    .database
                    .ModerationStore
                    .hash(session.id() + ":" + deliveryId)
                    .substring(0, 24),
                "enforce_nonce",
                true));
    String kind =
        data.containsKey("appealId") ? "appeal" : data.containsKey("reportId") ? "report" : null;
    int id =
        kind == null
            ? 0
            : ((Number) data.get(kind.equals("appeal") ? "appealId" : "reportId")).intValue();
    CompletableFuture<String> destination;
    if (kind != null) {
      String existing = session.runtime().channel(kind, id);
      if (!existing.isBlank()) destination = CompletableFuture.completedFuture(existing);
      else
        destination =
            CoreDiscordExtensions.managedPreviewTicket(
                    service,
                    kind.equals("appeal") ? TicketType.BAN_APPEAL : TicketType.REPORT,
                    "preview:" + session.id() + ":" + kind + ":" + id,
                    String.valueOf(data.getOrDefault("playerName", "Preview")),
                    recipients.stream().sorted().findFirst().orElse(null),
                    category,
                    new ArrayList<>(recipients),
                    "test-mine-ticket-" + id + (kind.equals("appeal") ? "-a" : "-r"))
                .thenApply(
                    created -> {
                      if (created != null)
                        try {
                          if (session.runtime().isClosed()) {
                            service.closeTicketChannel(
                                created, "[ТЕСТ] Песочница закрыта во время создания");
                            throw new IllegalStateException("Песочница закрыта");
                          }
                          session.runtime().channel(kind, id, created);
                          lab(session)
                              .registerExisting(
                                  kind,
                                  id,
                                  recipients.stream().sorted().findFirst().orElse(null),
                                  created);
                        } catch (Exception error) {
                          throw new CompletionException(error);
                        }
                      return created;
                    });
    } else destination = CompletableFuture.completedFuture(channel);
    final String messageBody = body;
    java.util.function.Function<String, CompletableFuture<String>> deliver =
        target -> {
          if (!event.equals("CASE_IMAGE")) return service.sendMessage(target, messageBody, null);
          try {
            int evidence = ((Number) data.get("evidenceId")).intValue();
            var metadata =
                session.runtime().moderation().evidence(kind, id, true).stream()
                    .filter(e -> ((Number) e.get("id")).intValue() == evidence)
                    .findFirst()
                    .orElseThrow();
            var bytes =
                session
                    .runtime()
                    .moderation()
                    .evidenceBytes(kind, id, evidence, true)
                    .orElseThrow();
            return CoreDiscordExtensions.sendImage(
                service,
                target,
                "[ТЕСТ] Доказательство №" + evidence,
                metadata.get("value").toString(),
                metadata.get("mime").toString(),
                bytes);
          } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
          }
        };
    return destination.thenCompose(
        target ->
            target == null
                ? CompletableFuture.completedFuture(false)
                : deliver
                    .apply(target)
                    .thenCompose(
                        messageId -> {
                          if (messageId == null)
                            return CompletableFuture.failedFuture(
                                new IllegalStateException("Discord не подтвердил отправку"));
                          if (event.equals("BAN") && !recipients.isEmpty())
                            return CompletableFuture.allOf(
                                    recipients.stream()
                                        .map(
                                            recipient ->
                                                service
                                                    .directMessage(recipient, messageBody)
                                                    .thenApply(
                                                        messageResult -> {
                                                          if (messageResult == null)
                                                            throw new CompletionException(
                                                                new IllegalStateException(
                                                                    "Тестовое личное сообщение не принято Discord"));
                                                          return true;
                                                        }))
                                        .toArray(CompletableFuture[]::new))
                                .thenApply(ignored -> true);
                          if (event.equals("APPEAL_DECISION") || event.equals("REPORT_DECISION"))
                            return plugin
                                .io()
                                .supply(
                                    () -> {
                                      try {
                                        var lab = lab(session);
                                        if (lab.hasChannel(kind, id, target))
                                          return lab.finishKnown(kind, id, service);
                                        return service
                                            .closeTicketChannel(
                                                session.runtime().channel(kind, id),
                                                "[ТЕСТ] Рассмотрение завершено")
                                            .get(30, TimeUnit.SECONDS);
                                      } catch (Exception error) {
                                        throw new CompletionException(error);
                                      }
                                    });
                          return CompletableFuture.completedFuture(true);
                        }));
  }

  @Override
  public void onTicketMessage(TicketMessage message) {
    if (closed
        || !plugin.isPreviewEnabled()
        || !message.ticketId().startsWith("preview:")
        || !recipients.contains(message.authorId())) return;
    String[] key = message.ticketId().split(":");
    if (key.length != 4
        || !Set.of("appeal", "report").contains(key[2])
        || !key[3].matches("[0-9]{1,10}")) return;
    var session = sessions.get(key[1]);
    if (session == null || !ownerAllowed(session)) return;
    if (!message.channelId().equals(session.runtime().channel(key[2], Integer.parseInt(key[3]))))
      return;
    plugin
        .io()
        .run(
            () -> {
              try {
                session
                    .runtime()
                    .incoming(
                        key[2],
                        Integer.parseInt(key[3]),
                        message.messageId(),
                        "Тестовый пользователь Discord",
                        message.content()
                            + message.attachments().stream()
                                .limit(5)
                                .map(
                                    attachment ->
                                        "\n" + attachment.name() + ": " + attachment.url())
                                .collect(java.util.stream.Collectors.joining()));
              } catch (Exception error) {
                throw new IllegalStateException(error);
              }
            });
  }

  private synchronized PreviewDiscordLab lab(Session session) throws Exception {
    var found = labs.get(session.id());
    if (found != null) return found;
    if (sessions.get(session.id()) != session || !ownerAllowed(session))
      throw new PreviewRuntime.Rejected(403, "Доступ к Preview отозван");
    found =
        new PreviewDiscordLab(
            session,
            category,
            channel,
            recipients,
            () ->
                !closed
                    && plugin.isPreviewEnabled()
                    && sessions.get(session.id()) == session
                    && ownerAllowed(session));
    labs.put(session.id(), found);
    return found;
  }

  public Map<String, Object> discordStatus(Session session) throws Exception {
    var result = new LinkedHashMap<String, Object>(lab(session).status());
    result.put("categoryId", category);
    result.put("channelId", channel);
    result.put("recipientCount", recipients.size());
    result.put("problems", discordProblems);
    result.put("botAvailable", bound != null && bound.isEnabled());
    result.put(
        "configured",
        !category.isBlank()
            && !channel.isBlank()
            && !recipients.isEmpty()
            && discordProblems.isEmpty());
    return result;
  }

  public Map<String, Object> discordCheck(Session session, String action) throws Exception {
    if (!Set.of("model", "diagnose", "panel", "dm", "roundtrip").contains(action))
      throw new IllegalArgumentException("Неизвестная проверка Discord");
    if (!action.equals("model")) {
      if (!discordProblems.isEmpty())
        throw new IllegalArgumentException(String.join("; ", discordProblems));
    }
    return lab(session).run(action, bound);
  }

  @Override
  public boolean onInteraction(DiscordInteraction interaction) {
    if (!interaction.name().startsWith("lwa:preview:")) return false;
    var service = bound;
    if (service == null || closed) return true;
    if (!plugin.isPreviewEnabled()) {
      service.respond(
          interaction,
          JsonUtils.toJson(
              Map.of(
                  "type",
                  4,
                  "data",
                  Map.of("flags", 64, "content", "Серверный Preview отключён"))));
      return true;
    }
    String[] parts = interaction.name().split(":");
    if (parts.length < 4) return true;
    var session = sessions.get(parts[2]);
    if (session == null
        || !ownerAllowed(session)
        || !recipients.contains(interaction.userId())
        || !interaction.guildId().equals(CoreDiscordExtensions.guild(service))) {
      service.respond(
          interaction,
          JsonUtils.toJson(
              Map.of(
                  "type",
                  4,
                  "data",
                  Map.of(
                      "flags",
                      64,
                      "content",
                      "Тестовая панель недоступна этому аккаунту или сессия завершена"))));
      return true;
    }
    if (interactionPending.incrementAndGet() > 16) {
      interactionPending.decrementAndGet();
      service.respond(
          interaction,
          JsonUtils.toJson(
              Map.of(
                  "type",
                  4,
                  "data",
                  Map.of("flags", 64, "content", "Дождитесь завершения предыдущих проверок"))));
      return true;
    }
    plugin
        .io()
        .run(
            () -> {
              try {
                lab(session).interact(interaction, service);
              } catch (Exception error) {
                String message =
                    error instanceof IllegalArgumentException
                        ? error.getMessage()
                        : DiscordMessagePublisher.explanation(error);
                service
                    .respond(
                        interaction,
                        JsonUtils.toJson(
                            Map.of("type", 4, "data", Map.of("flags", 64, "content", message))))
                    .thenAccept(
                        ok -> {
                          if (!ok)
                            CoreDiscordExtensions.managed(
                                service,
                                "editInteractionResponse",
                                new Class<?>[] {DiscordInteraction.class, String.class},
                                interaction,
                                JsonUtils.toJson(
                                    Map.of(
                                        "content",
                                        message,
                                        "allowed_mentions",
                                        Map.of("parse", List.of()))));
                        });
              }
            })
        .whenComplete((value, error) -> interactionPending.decrementAndGet());
    return true;
  }

  public synchronized void stop() {
    closed = true;
    task.cancel();
    if (bound != null) {
      bound.unregisterTicketMessageListenerV2(this);
      bound.unregisterInteractionListener(this);
    }
  }

  @Override
  public synchronized void close() {
    stop();
    for (var session : sessions.values())
      try {
        session.runtime().close();
      } catch (Exception error) {
        plugin
            .getLogger()
            .warning("Не удалось закрыть хранилище Preview: " + error.getClass().getSimpleName());
      }
    for (var lab : labs.values())
      try {
        lab.close();
      } catch (Exception error) {
        plugin
            .getLogger()
            .warning("Не закрыты проверки Discord: " + error.getClass().getSimpleName());
      }
    labs.clear();
    sessions.clear();
  }
}
