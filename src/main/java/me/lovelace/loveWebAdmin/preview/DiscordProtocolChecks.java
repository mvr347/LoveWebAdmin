package me.lovelace.loveWebAdmin.preview;

import dev.lovelace.lovecore.api.discord.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import me.lovelace.loveWebAdmin.database.TicketDeskStore;
import me.lovelace.loveWebAdmin.integration.*;

/**
 * Exercises the real publisher/store/archive against a controlled transport; never contacts
 * Discord.
 */
public final class DiscordProtocolChecks {
  @FunctionalInterface
  interface Check {
    void run() throws Exception;
  }

  public static List<Map<String, Object>> run(Path parent) throws Exception {
    var results = new ArrayList<Map<String, Object>>();
    Path folder = Files.createTempDirectory(parent, "discord-checks-");
    try {
      check(
          results,
          "Панель содержит жалобу, апелляцию и личные обращения",
          () -> {
            var body = DiscordPanelMessages.body("test:", true);
            var buttons =
                (List<?>)
                    ((Map<?, ?>) ((List<?>) body.get("components")).getFirst()).get("components");
            require(buttons.size() == 3, "Не три кнопки");
            require(
                body.toString().contains("test:report")
                    && body.toString().contains("test:appeal")
                    && body.toString().contains("test:mine"),
                "Маршруты кнопок потеряны");
            require(
                body.get("allowed_mentions").equals(Map.of("parse", List.of())),
                "Упоминания не отключены");
          });
      check(
          results,
          "Повторная публикация редактирует то же сообщение",
          () -> {
            var transport = new Transport();
            try (var store = new TicketDeskStore(folder.resolve("repeat.db"))) {
              publish(store, transport);
              publish(store, transport);
              require(transport.creates == 1 && transport.edits == 1, "Создан дубликат панели");
            }
          });
      check(
          results,
          "После перезапуска сохранённый ID используется повторно",
          () -> {
            var transport = new Transport();
            Path file = folder.resolve("restart.db");
            try (var store = new TicketDeskStore(file)) {
              publish(store, transport);
            }
            try (var store = new TicketDeskStore(file)) {
              publish(store, transport);
              require(transport.creates == 1, "Перезапуск создал дубликат");
            }
          });
      for (int status : List.of(400, 401, 403, 404, 429))
        check(
            results,
            "Явный отказ HTTP " + status + " не блокирует исправленную отправку",
            () -> {
              var transport = new Transport();
              transport.failure = new Rejection(status);
              try (var store = new TicketDeskStore(folder.resolve("http-" + status + ".db"))) {
                try {
                  publish(store, transport);
                  throw new AssertionError("Отказ проигнорирован");
                } catch (ExecutionException expected) {
                }
                require(
                    store.message("panel").orElseThrow().get("state").equals("FAILED"),
                    "Отказ принят за неизвестный результат");
                transport.failure = null;
                publish(store, transport);
                require(transport.creates == 1, "Не удалось восстановить отправку");
              }
            });
      check(
          results,
          "Потерянный ответ блокирует автоматическое создание дубля",
          () -> {
            var transport = new Transport();
            transport.failure = new java.io.IOException("lost response");
            try (var store = new TicketDeskStore(folder.resolve("unknown.db"))) {
              try {
                publish(store, transport);
              } catch (Exception expected) {
              }
              int attempts = transport.attempts;
              try {
                publish(store, transport);
              } catch (IllegalArgumentException expected) {
              }
              require(transport.attempts == attempts, "Неизвестный запрос повторён");
              require(
                  store.message("panel").orElseThrow().get("state").equals("UNCERTAIN"),
                  "Не сохранено состояние сверки");
            }
          });
      check(
          results,
          "После остановки незавершённая отправка требует сверки",
          () -> {
            Path file = folder.resolve("interrupted.db");
            try (var store = new TicketDeskStore(file)) {
              require(store.sending("panel", "70000"), "Запрос не принят");
            }
            try (var store = new TicketDeskStore(file)) {
              require(
                  store.message("panel").orElseThrow().get("state").equals("UNCERTAIN"),
                  "Опасный повтор после рестарта");
            }
          });
      check(
          results,
          "Ручная сверка связывает найденное сообщение без дубля",
          () -> {
            var transport = new Transport();
            try (var store = new TicketDeskStore(folder.resolve("reconcile.db"))) {
              store.sending("panel", "70000");
              store.uncertain("panel", "lost");
              require(
                  store.resolveMessage("panel", "90000", "Сообщение найдено в тестовом канале"),
                  "Сверка отклонена");
              publish(store, transport);
              require(transport.creates == 0 && transport.edits == 1, "Сверка создала дубликат");
            }
          });
      check(
          results,
          "Старое ядро не превращает отсутствие метода в неизвестную отправку",
          () -> {
            var transport = new Transport();
            transport.failure = new UnsupportedOperationException();
            try (var store = new TicketDeskStore(folder.resolve("old.db"))) {
              try {
                publish(store, transport);
              } catch (Exception expected) {
              }
              require(
                  store.message("panel").orElseThrow().get("state").equals("FAILED"),
                  "Несовместимость заблокировала сообщение");
            }
          });
      check(
          results,
          "Форму может подтвердить только её автор",
          () -> {
            try (var store = new TicketDeskStore(folder.resolve("author.db"))) {
              String draft = store.draft("10000", "report", Map.of("text", "Описание ситуации"));
              try {
                store.draft(draft, "20000");
                throw new AssertionError("Чужая форма доступна");
              } catch (IllegalArgumentException expected) {
              }
              require(store.consume(draft, "10000", false), "Подтверждение не принято");
              require(!store.consume(draft, "10000", false), "Повторное подтверждение принято");
            }
          });
      check(
          results,
          "Отменённая форма не создаёт обращение",
          () -> {
            try (var store = new TicketDeskStore(folder.resolve("cancel.db"))) {
              String draft = store.draft("10000", "appeal", Map.of());
              require(store.consume(draft, "10000", true), "Отмена не принята");
              require(!store.consume(draft, "10000", false), "Отменённая форма подтверждена");
            }
          });
      check(
          results,
          "Лимит черновиков сдерживает повторные нажатия",
          () -> {
            try (var store = new TicketDeskStore(folder.resolve("limit.db"))) {
              for (int i = 0; i < 3; i++) store.draft("10000", "report", Map.of());
              try {
                store.draft("10000", "report", Map.of());
                throw new AssertionError("Лимит не сработал");
              } catch (IllegalArgumentException expected) {
              }
            }
          });
      check(
          results,
          "Архив сохраняется до удаления канала",
          () -> archiveCheck(folder.resolve("archive.db"), false));
      check(
          results,
          "Ошибка истории оставляет канал и останавливает удаление",
          () -> archiveCheck(folder.resolve("archive-error.db"), true));
      return results;
    } finally {
      try (var paths = Files.walk(folder)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  private static void publish(TicketDeskStore store, Transport transport) throws Exception {
    DiscordMessagePublisher.publish(
        store, transport.service, "panel", "70000", DiscordPanelMessages.body("test:", true));
  }

  private static void require(boolean ok, String why) {
    if (!ok) throw new AssertionError(why);
  }

  private static void check(List<Map<String, Object>> rows, String name, Check check) {
    try {
      check.run();
      rows.add(
          Map.of(
              "name",
              name,
              "mode",
              "TRANSPORT_MODEL",
              "passed",
              true,
              "detail",
              "Настоящий механизм, подменённый транспорт. Discord не вызывался"));
    } catch (Throwable error) {
      rows.add(
          Map.of(
              "name",
              name,
              "mode",
              "TRANSPORT_MODEL",
              "passed",
              false,
              "detail",
              Objects.toString(error.getMessage(), error.getClass().getSimpleName())));
    }
  }

  private static void archiveCheck(Path file, boolean fail) throws Exception {
    try (var store = new TicketDeskStore(file)) {
      store.register("report", 1, "10000");
      store.channel("report", 1, "70000");
      store.beginClose("report", 1, "Тест", "Проверка архивации");
      int[] deletes = {0};
      var archive =
          new DiscordTicketArchive(
              store,
              new DiscordTicketArchive.Transport() {
                public void readOnly(String channel) {}

                public List<TicketMessage> history(String channel, String before) throws Exception {
                  if (fail) throw new java.io.IOException("history failed");
                  return List.of();
                }

                public byte[] attachment(String c, String m, String u) {
                  throw new AssertionError("No attachments");
                }

                public void delete(String channel) throws Exception {
                  require(
                      store.get("report", 1).orElseThrow().state().equals("ARCHIVED"),
                      "Удаление до архива");
                  deletes[0]++;
                }
              });
      try {
        archive.finish(store.get("report", 1).orElseThrow());
        if (fail) throw new AssertionError("Ошибка истории проигнорирована");
      } catch (java.io.IOException expected) {
        if (!fail) throw expected;
      }
      require(deletes[0] == (fail ? 0 : 1), "Неверное удаление канала");
    }
  }

  public static final class Rejection extends IllegalStateException {
    private final int status;

    public Rejection(int status) {
      this.status = status;
    }

    public int getStatusCode() {
      return status;
    }
  }

  static final class Transport {
    int creates, edits, attempts;
    Throwable failure;
    final DiscordService service =
        (DiscordService)
            Proxy.newProxyInstance(
                DiscordService.class.getClassLoader(),
                new Class<?>[] {DiscordService.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "isEnabled" -> true;
                      case "ensureMessage" -> {
                        attempts++;
                        if (failure != null) yield CompletableFuture.failedFuture(failure);
                        if (args[1] == null) creates++;
                        else edits++;
                        yield CompletableFuture.completedFuture("90000");
                      }
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
  }
}
