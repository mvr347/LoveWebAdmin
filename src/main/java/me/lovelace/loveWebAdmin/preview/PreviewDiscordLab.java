package me.lovelace.loveWebAdmin.preview;

import dev.lovelace.lovecore.api.discord.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.lovelace.loveWebAdmin.database.TicketDeskStore;
import me.lovelace.loveWebAdmin.integration.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/** Explicit live checks, confined to configured test destinations and allowlisted people. */
final class PreviewDiscordLab implements AutoCloseable {
  final PreviewService.Session session;
  final TicketDeskStore store;
  final AtomicBoolean busy = new AtomicBoolean();
  private final java.util.function.BooleanSupplier alive;
  private final Path resultFile;
  private final LinkedHashMap<String, Map<String, Object>> results = new LinkedHashMap<>();
  private final String category, channel;
  private final Set<String> recipients;
  private volatile DiscordService service;
  private volatile boolean closed;
  static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aO1sAAAAASUVORK5CYII=");

  PreviewDiscordLab(
      PreviewService.Session session,
      String category,
      String channel,
      Set<String> recipients,
      java.util.function.BooleanSupplier alive)
      throws Exception {
    this.session = session;
    this.category = category;
    this.channel = channel;
    this.recipients = recipients;
    this.alive = alive;
    store = new TicketDeskStore(session.runtime().folder().resolve("discord-lab.db"));
    resultFile = session.runtime().folder().resolve("discord-checks.json");
    if (Files.isRegularFile(resultFile) && Files.size(resultFile) < 100000) {
      var data = JsonUtils.parseObject(Files.readString(resultFile));
      for (var e : data.entrySet())
        if (e.getValue() instanceof Map<?, ?> value)
          results.put(e.getKey(), new LinkedHashMap<>((Map<String, Object>) value));
    }
  }

  private void admitted() {
    if (closed || !alive.getAsBoolean() || session.runtime().isClosed())
      throw new PreviewRuntime.Rejected(410, "Песочница закрыта или доступ отозван");
  }

  synchronized void record(String id, String name, String state, String detail) throws Exception {
    results.put(
        id,
        Map.of(
            "name",
            name,
            "state",
            state,
            "mode",
            "LIVE",
            "detail",
            detail,
            "at",
            System.currentTimeMillis() / 1000));
    Path temporary = resultFile.resolveSibling("discord-checks.tmp");
    Files.writeString(temporary, JsonUtils.toJson(results));
    Files.move(
        temporary, resultFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  private synchronized boolean hasResult(String key) {
    return results.containsKey(key);
  }

  synchronized Map<String, Object> status() {
    return Map.of(
        "busy", busy.get(), "results", List.copyOf(results.values()), "messages", safeMessages());
  }

  private List<?> safeMessages() {
    try {
      return store.messages();
    } catch (Exception error) {
      return List.of();
    }
  }

  private <T> T call(String method, Class<?>[] types, Object... args) throws Exception {
    admitted();
    return CoreDiscordExtensions.<T>managed(service, method, types, args).get(35, TimeUnit.SECONDS);
  }

  void configure(DiscordService service) {
    this.service = service;
  }

  private void inspect() throws Exception {
    admitted();
    if (service == null || !service.isEnabled())
      throw new IllegalArgumentException("Бот выключен или недоступен");
    if (category.isBlank() || channel.isBlank() || recipients.isEmpty())
      throw new IllegalArgumentException(
          "Задайте preview.discord.category-id, channel-id и allowed-user-ids");
    Map<String, Object> parent = call("inspectChannel", new Class<?>[] {String.class}, category);
    Map<String, Object> target = call("inspectChannel", new Class<?>[] {String.class}, channel);
    if (((Number) parent.get("type")).intValue() != 4
        || ((Number) target.get("type")).intValue() != 0
        || !category.equals(target.get("parentId")))
      throw new IllegalArgumentException(
          "Тестовый канал должен быть текстовым и находиться внутри тестовой категории");
    record(
        "channel",
        "Тестовые категория и канал",
        "PASS",
        "Discord подтвердил доступ к каналу. Право отправки проверяется отдельно");
  }

  Map<String, Object> run(String action, DiscordService service) throws Exception {
    if (!busy.compareAndSet(false, true))
      throw new PreviewRuntime.Rejected(409, "Предыдущая проверка Discord ещё выполняется");
    this.service = service;
    try {
      admitted();
      if (action.equals("model"))
        return Map.of("models", DiscordProtocolChecks.run(session.runtime().folder()));
      inspect();
      switch (action) {
        case "diagnose" -> {}
        case "panel" -> {
          String id =
              DiscordMessagePublisher.publish(
                  store, service, "panel", channel, DiscordPanelMessages.body(prefix(), true));
          record("panel", "Публикация трёх кнопок", "PASS", "Discord подтвердил сообщение " + id);
          for (String kind : List.of("report", "appeal", "mine"))
            if (!hasResult("click:" + kind))
              record(
                  "click:" + kind,
                  "Кнопка: " + label(kind),
                  "WAITING",
                  "Нажмите кнопку в тестовой панели разрешённым Discord-аккаунтом");
        }
        case "dm" -> {
          for (String user : recipients) {
            admitted();
            String id =
                service
                    .directMessage(
                        user,
                        JsonUtils.toJson(
                            Map.of(
                                "content",
                                "[ТЕСТ] Проверка личного уведомления. Рабочие наказания и обращения не изменялись.",
                                "allowed_mentions",
                                Map.of("parse", List.of()))))
                    .get(30, TimeUnit.SECONDS);
            if (id == null)
              throw new IllegalStateException("Discord не подтвердил ЛС пользователю " + user);
            record(
                "dm:" + user,
                "Личное уведомление тестировщику " + user,
                "PASS",
                "Discord подтвердил сообщение " + id + "; прочтение человеком не проверялось");
          }
        }
        case "roundtrip" -> probe();
        default -> throw new IllegalArgumentException("Неизвестная проверка");
      }
      return status();
    } catch (Exception error) {
      record(
          "last-error",
          "Последняя проверка",
          "FAIL",
          error instanceof IllegalArgumentException
              ? error.getMessage()
              : DiscordMessagePublisher.explanation(error));
      throw error;
    } finally {
      busy.set(false);
    }
  }

  private String prefix() {
    return "lwa:preview:" + session.id() + ":";
  }

  private static String label(String kind) {
    return switch (kind) {
      case "report" -> "Жалоба";
      case "appeal" -> "Апелляция";
      default -> "Мои обращения";
    };
  }

  private String create(String kind, int id, String owner, String nick) throws Exception {
    admitted();
    store.register(kind, id, owner);
    String existing = store.get(kind, id).orElseThrow().channel();
    if (!existing.isBlank()) return existing;
    // The intent remains persisted when the creation result is ambiguous; it is never blindly
    // retried.
    if (store.creationStarted(kind, id))
      throw new IllegalArgumentException("Создание тестового канала требует сверки");
    store.startCreation(kind, id);
    String result =
        CoreDiscordExtensions.managedPreviewTicket(
                service,
                kind.equals("appeal") ? TicketType.BAN_APPEAL : TicketType.REPORT,
                "preview:" + session.id() + ":" + kind + ":" + id,
                nick,
                owner,
                category,
                new ArrayList<>(recipients),
                "test-mine-ticket-" + id + (kind.equals("appeal") ? "-a" : "-r"))
            .get(40, TimeUnit.SECONDS);
    if (result == null) throw new IllegalStateException("Discord не подтвердил тестовый канал");
    store.channel(kind, id, result);
    session.runtime().channel(kind, id, result);
    return result;
  }

  private void probe() throws Exception {
    int id = (int) (System.currentTimeMillis() / 1000 - 1700000000L);
    while (store.get("report", id).isPresent()) id++;
    String owner = recipients.stream().sorted().findFirst().orElseThrow(),
        target = create("report", id, owner, "PreviewProbe");
    record(
        "create",
        "Создание приватного тестового канала",
        "PASS",
        "Discord подтвердил канал " + target);
    Map<String, Object> info = call("inspectChannel", new Class<?>[] {String.class}, target);
    if (!category.equals(info.get("parentId")))
      throw new IllegalStateException("Канал создан за пределами тестовой категории");
    verifyAccess(info);
    record(
        "access",
        "Приватный состав участников",
        "PASS",
        "@everyone запрещён просмотр, доступ выдан только боту и тестировщикам; Discord-администраторы могут обходить запрет");
    String message =
        call(
            "ensureMessage",
            new Class<?>[] {String.class, String.class, String.class},
            target,
            null,
            JsonUtils.toJson(
                Map.of(
                    "content",
                    "[ТЕСТ] Проверка истории и архива. Этот канал будет удалён после сохранения.",
                    "allowed_mentions",
                    Map.of("parse", List.of()))));
    record("send", "Отправка сообщения в тикет", "PASS", "Discord подтвердил сообщение " + message);
    String image =
        CoreDiscordExtensions.sendImage(
                service, target, "[ТЕСТ] Проверка изображения", "preview.png", "image/png", PNG)
            .get(30, TimeUnit.SECONDS);
    if (image == null) throw new IllegalStateException("Изображение не подтверждено");
    record("image", "Отправка изображения", "PASS", "Discord подтвердил вложение " + image);
    List<TicketMessage> history =
        call("getTicketHistory", new Class<?>[] {String.class, String.class}, target, null);
    if (history.stream().noneMatch(m -> m.messageId().equals(message))
        || history.stream().noneMatch(m -> m.messageId().equals(image)))
      throw new IllegalStateException("В истории нет тестовых сообщений");
    record(
        "history", "Чтение реальной истории", "PASS", "Найдены текст и сообщение с изображением");
    archive("report", id);
    record(
        "archive",
        "Архив текста и вложения перед удалением",
        "PASS",
        "История сохранена, тестовый канал удалён после архивации");
  }

  private void verifyAccess(Map<String, Object> info) {
    String guild = info.get("guildId").toString();
    boolean denied = false;
    var members = new HashSet<String>();
    int botEntries = 0;
    for (Object value : (List<?>) info.get("overwrites")) {
      var row = (Map<?, ?>) value;
      String id = row.get("id").toString();
      int type = ((Number) row.get("type")).intValue();
      long deny = Long.parseUnsignedLong(row.get("deny").toString()),
          allow = Long.parseUnsignedLong(row.get("allow").toString());
      if (type == 0) {
        if (!id.equals(guild) || (allow & 1024) != 0)
          throw new IllegalStateException("Найден лишний доступ роли");
        denied = (deny & 1024) != 0;
      } else if ((allow & 1024) != 0) {
        if (recipients.contains(id)) members.add(id);
        else {
          if ((allow & 16) == 0)
            throw new IllegalStateException("Найден лишний участник тестового канала");
          botEntries++;
        }
      }
    }
    if (!denied || !members.containsAll(recipients) || botEntries != 1)
      throw new IllegalStateException("Discord не подтвердил ожидаемый приватный состав");
  }

  void registerExisting(String kind, int id, String owner, String channel) throws Exception {
    admitted();
    store.register(kind, id, owner);
    store.channel(kind, id, channel);
  }

  boolean hasChannel(String kind, int id, String channel) throws Exception {
    return store.get(kind, id).filter(t -> t.channel().equals(channel)).isPresent();
  }

  boolean finishKnown(String kind, int id, DiscordService service) throws Exception {
    if (!busy.compareAndSet(false, true)) return false;
    this.service = service;
    try {
      var ticket = store.get(kind, id).orElseThrow();
      if (ticket.state().equals("CLOSED")) return true;
      archive(kind, id);
      record(
          "decision-close",
          "Закрытие после решения в Preview",
          "PASS",
          "Изменилось только тестовое дело; история сохранена перед удалением канала");
      return true;
    } finally {
      busy.set(false);
    }
  }

  private void archive(String kind, int id) throws Exception {
    admitted();
    store.beginClose(kind, id, "Тестировщик", "[ТЕСТ] Канал закрыт после сохранения истории");
    var archive =
        new DiscordTicketArchive(
            store,
            new DiscordTicketArchive.Transport() {
              public void readOnly(String c) throws Exception {
                Boolean result =
                    call(
                        "setTicketReadOnly", new Class<?>[] {String.class, boolean.class}, c, true);
                if (!Boolean.TRUE.equals(result))
                  throw new IllegalStateException("Запрет записи не подтверждён");
              }

              public List<TicketMessage> history(String c, String before) throws Exception {
                return call(
                    "getTicketHistory", new Class<?>[] {String.class, String.class}, c, before);
              }

              public byte[] attachment(String c, String m, String u) throws Exception {
                return call(
                    "getTicketAttachment",
                    new Class<?>[] {String.class, String.class, String.class},
                    c,
                    m,
                    u);
              }

              public void delete(String c) throws Exception {
                Boolean result = call("deleteTicketChannel", new Class<?>[] {String.class}, c);
                if (!Boolean.TRUE.equals(result))
                  throw new IllegalStateException("Удаление не подтверждено");
              }
            },
            () -> !closed && alive.getAsBoolean());
    if (!archive.finish(store.get(kind, id).orElseThrow()))
      throw new IllegalStateException("Архив/удаление требуют сверки");
  }

  boolean accepts(DiscordInteraction i) {
    return i.name().startsWith(prefix());
  }

  void interact(DiscordInteraction i, DiscordService service) throws Exception {
    admitted();
    this.service = service;
    if (!recipients.contains(i.userId()))
      throw new IllegalArgumentException(
          "Эта тестовая панель доступна только разрешённым тестировщикам");
    String suffix = i.name().substring(prefix().length());
    String[] p = suffix.split(":");
    String action = p[0];
    if (Set.of("report", "appeal").contains(action)) {
      if (!channel.equals(i.values().getOrDefault("_channelId", "")))
        throw new IllegalArgumentException("Откройте тестовую панель в настроенном канале");
      record(
          "click:" + action,
          "Кнопка: " + label(action),
          "PASS",
          "Discord прислал нажатие разрешённого пользователя");
      response(
          i,
          9,
          Map.of(
              "title",
              "[ТЕСТ] " + label(action),
              "custom_id",
              prefix() + "draft:" + action,
              "components",
              List.of(
                  field("nick", "Тестовый ник, например Ada123", 16),
                  field("reason", "Описание тестовой ситуации", 2000))));
      return;
    }
    if (action.equals("draft")) {
      String kind = p[1],
          nick = i.values().getOrDefault("nick", "").trim(),
          reason = i.values().getOrDefault("reason", "").trim();
      if (!Set.of("report", "appeal").contains(kind)
          || !nick.matches("[A-Za-z0-9_]{3,16}")
          || reason.length() < 5
          || reason.length() > 2000)
        throw new IllegalArgumentException("Укажите корректный ник и описание от 5 символов");
      String draft = store.draft(i.userId(), kind, Map.of("nick", nick, "reason", reason));
      response(
          i,
          4,
          Map.of(
              "flags",
              64,
              "content",
              "[ТЕСТ] "
                  + label(kind)
                  + " · "
                  + nick
                  + "\n"
                  + reason.substring(0, Math.min(reason.length(), 1200))
                  + "\nБудет создано только тестовое обращение.",
              "allowed_mentions",
              Map.of("parse", List.of()),
              "components",
              List.of(
                  Map.of(
                      "type",
                      1,
                      "components",
                      List.of(
                          button("Подтвердить", prefix() + "confirm:" + draft),
                          button("Отмена", prefix() + "cancel:" + draft))))));
      record(
          "form:" + kind,
          "Форма: " + label(kind),
          "PASS",
          "Данные получены; ожидается отдельное подтверждение");
      return;
    }
    if (!Boolean.TRUE.equals(
        service
            .respond(i, JsonUtils.toJson(Map.of("type", 5, "data", Map.of("flags", 64))))
            .get(10, TimeUnit.SECONDS))) return;
    if (action.equals("mine")) {
      var owned =
          store.active().stream()
              .filter(t -> t.owner().equals(i.userId()) && !t.state().equals("CLOSED"))
              .map(
                  t ->
                      label(t.kind())
                          + " №"
                          + t.id()
                          + " · "
                          + (t.state().equals("OPEN")
                              ? "Открыто"
                              : t.state().equals("CLOSED") ? "Закрыто" : "Сохраняется история"))
              .limit(20)
              .toList();
      edit(
          i,
          "[ТЕСТ] Ваши открытые обращения:\n"
              + (owned.isEmpty() ? "Обращений пока нет" : String.join("\n", owned)));
      record(
          "click:mine", "Кнопка: Мои обращения", "PASS", "Показан список только текущего автора");
      return;
    }
    if (action.equals("confirm") || action.equals("cancel")) {
      var draft = store.draft(p[1], i.userId());
      if (action.equals("confirm")
          && store.active().stream().filter(t -> !t.state().equals("CLOSED")).count() >= 10)
        throw new IllegalArgumentException(
            "В песочнице уже 10 открытых тестовых каналов; завершите предыдущие проверки");
      if (!store.consume(draft.id(), i.userId(), action.equals("cancel"))) {
        edit(i, "Форма уже обработана. Повторное обращение не создано.");
        return;
      }
      if (action.equals("cancel")) {
        edit(i, "Тестовая форма отменена.");
        record("cancel", "Отмена формы", "PASS", "Обращение не создано");
        return;
      }
      if (!busy.compareAndSet(false, true)) {
        store.rejectDraft(draft.id());
        throw new IllegalArgumentException("Дождитесь предыдущей проверки и заполните форму снова");
      }
      try {
        inspect();
        int id =
            session
                .runtime()
                .discordCase(
                    draft.kind(),
                    draft.data().get("nick").toString(),
                    draft.data().get("reason").toString(),
                    i.userId());
        store.completeDraft(draft.id(), id);
        String target = create(draft.kind(), id, i.userId(), draft.data().get("nick").toString());
        call(
            "ensureMessage",
            new Class<?>[] {String.class, String.class, String.class},
            target,
            null,
            JsonUtils.toJson(
                Map.of(
                    "content",
                    "[ТЕСТ] Обращение №"
                        + id
                        + ". Напишите сообщение в этот канал, затем проверьте переписку в серверном Preview. Закрытие сохранит историю перед удалением.",
                    "components",
                    List.of(
                        Map.of(
                            "type",
                            1,
                            "components",
                            List.of(
                                button(
                                    "Закрыть и сохранить",
                                    prefix() + "close:" + draft.kind() + ":" + id)))),
                    "allowed_mentions",
                    Map.of("parse", List.of()))));
        edit(i, "Тестовое обращение №" + id + " создано: <#" + target + ">");
        record(
            "confirm:" + draft.kind(),
            "Подтверждение: " + label(draft.kind()),
            "PASS",
            "Создано изолированное дело №" + id);
        record(
            "reply",
            "Ответ из Discord в дело",
            "WAITING",
            "Напишите в тестовый тикет разрешённым аккаунтом");
      } finally {
        busy.set(false);
      }
      return;
    }
    if (action.equals("close")) {
      String kind = p[1];
      int id = Integer.parseInt(p[2]);
      var ticket = store.get(kind, id).orElseThrow();
      if (!ticket.channel().equals(i.values().getOrDefault("_channelId", "")))
        throw new IllegalArgumentException("Кнопка принадлежит другому каналу");
      if (!busy.compareAndSet(false, true))
        throw new IllegalArgumentException("Дождитесь предыдущей проверки");
      try {
        archive(kind, id);
        record(
            "interactive-close",
            "Закрытие из тестового тикета",
            "PASS",
            "История сохранена, канал удалён");
      } finally {
        busy.set(false);
      }
      return;
    }
    throw new IllegalArgumentException("Неизвестное тестовое действие");
  }

  private void response(DiscordInteraction i, int type, Map<String, Object> data) throws Exception {
    service
        .respond(i, JsonUtils.toJson(Map.of("type", type, "data", data)))
        .get(10, TimeUnit.SECONDS);
  }

  private void edit(DiscordInteraction i, String message) throws Exception {
    call(
        "editInteractionResponse",
        new Class<?>[] {DiscordInteraction.class, String.class},
        i,
        JsonUtils.toJson(
            Map.of("content", message, "allowed_mentions", Map.of("parse", List.of()))));
  }

  private static Map<String, Object> button(String label, String id) {
    return Map.of("type", 2, "style", 1, "label", label, "custom_id", id);
  }

  private static Map<String, Object> field(String id, String label, int max) {
    return Map.of(
        "type",
        1,
        "components",
        List.of(
            Map.of(
                "type",
                4,
                "custom_id",
                id,
                "label",
                label,
                "style",
                max > 100 ? 2 : 1,
                "required",
                true,
                "max_length",
                max)));
  }

  @Override
  public void close() throws Exception {
    closed = true;
    store.close();
  }
}
