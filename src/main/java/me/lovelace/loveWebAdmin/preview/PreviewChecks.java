package me.lovelace.loveWebAdmin.preview;

import java.nio.file.*;
import java.util.*;
import static me.lovelace.loveWebAdmin.preview.PreviewRuntime.*;

/**
 * Reproducible test centre. Child data is never pumped to Discord or used as live configuration.
 */
final class PreviewChecks {
  @FunctionalInterface
  interface Check {
    void run() throws Exception;
  }

  static List<Map<String, Object>> run(Path parent) throws Exception {
    Path folder = Files.createTempDirectory(parent, "checks-");
    var results = new ArrayList<Map<String, Object>>();
    try (var r = new PreviewRuntime(folder, "checks")) {
      for (String endpoint :
          List.of(
              "me",
              "stats",
              "players",
              "bans/all",
              "reports",
              "appeals",
              "server/config",
              "server/whitelist",
              "server/ops",
              "admins",
              "roles",
              "logs/web",
              "staff-audit/logs",
              "loveauth/status",
              "vesuvio/status",
              "vesuvio/engine",
              "vesuvio/reviews",
              "analytics/summary",
              "economy",
              "developer/keys",
              "developer/webhooks",
              "cases/templates",
              "cases/deliveries"))
        check(
            results,
            "Чтение /api/" + endpoint,
            "SERVER",
            () -> r.execute("GET", "/api/" + endpoint, Map.of(), Map.of()));
      check(
          results,
          "Блокировка, исправление и неизменный автор",
          "SERVER",
          () -> {
            r.execute(
                "POST",
                "/api/bans",
                Map.of("targetName", "Ada123", "ruleReason", "Тестовая блокировка"),
                Map.of("idempotency-key", "test-centre-ban"));
            r.execute(
                "POST",
                "/api/bans",
                Map.of("targetName", "Ada123", "ruleReason", "Тестовая блокировка"),
                Map.of("idempotency-key", "test-centre-ban"));
            if (r.database().getAllBans(null).size() != 1)
              throw new AssertionError("Повторный запрос создал второй бан");
            var ban = r.database().getAllBans(null).getFirst();
            r.execute(
                "POST",
                "/api/bans/" + ban.id() + "/correct",
                Map.of(
                    "revision",
                    0,
                    "ruleReason",
                    "Исправленная причина",
                    "explanation",
                    "Объяснение исправления",
                    "remainingSeconds",
                    3600),
                Map.of());
            if (!ban.creatorName()
                .equals(r.database().getBanById(ban.id()).orElseThrow().creatorName()))
              throw new AssertionError("Изменился автор");
          });
      check(
          results,
          "Апелляция: доступ автора, решение, защита от повтора",
          "SERVER",
          () -> {
            var a = map(r.control("appeal", Map.of()));
            int id = integer(a.get("id"));
            r.execute(
                "GET",
                "/api/appeals/" + id,
                Map.of(),
                Map.of("x-appeal-token", text(a, "accessToken", "")));
            r.control("role", Map.of("id", 2));
            r.execute(
                "POST",
                "/api/appeals/" + id + "/approve",
                Map.of("verdictMessage", "Тестовое обоснование решения"),
                Map.of());
            try {
              r.execute(
                  "POST",
                  "/api/appeals/" + id + "/reject",
                  Map.of("verdictMessage", "Повторное решение невозможно"),
                  Map.of());
              throw new AssertionError("Повторное решение разрешено");
            } catch (me.lovelace.loveWebAdmin.utils.ActionRejectedException expected) {
            }
            r.control("role", Map.of("id", 1));
          });
      check(
          results,
          "Жалоба: ответственный, доказательства и конфликт версий",
          "SERVER",
          () -> {
            int id = integer(map(r.control("report", Map.of())).get("id"));
            r.execute(
                "POST", "/api/cases/report/" + id + "/claim", Map.of("revision", 0), Map.of());
            try {
              r.execute(
                  "POST",
                  "/api/cases/report/" + id + "/note",
                  Map.of("revision", 0, "text", "Устаревшая заметка"),
                  Map.of());
              throw new AssertionError("Устаревшая версия принята");
            } catch (Rejected expected) {
              if (expected.status != 409) throw expected;
            }
            r.execute(
                "POST",
                "/api/cases/report/" + id + "/evidence",
                Map.of(
                    "revision",
                    1,
                    "type",
                    "TEXT",
                    "value",
                    "Приватное доказательство",
                    "visibility",
                    "STAFF"),
                Map.of());
            if (!r.moderation().evidence("report", id, true).isEmpty())
              throw new AssertionError("Внутреннее доказательство видно автору");
          });
      check(
          results,
          "Инвентарь: изъятие и однократный возврат",
          "SIMULATED",
          () -> {
            var inv = map(r.execute("GET", "/api/players/Ada123/inventory", Map.of(), Map.of()));
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) inv.get("storage");
            var item = items.getFirst();
            r.execute(
                "POST",
                "/api/players/Ada123/action",
                Map.of(
                    "action",
                    "remove_item",
                    "slot",
                    item.get("slot"),
                    "fingerprint",
                    item.get("fingerprint"),
                    "reason",
                    "Проверка предмета"),
                Map.of());
            @SuppressWarnings("unchecked")
            var held =
                (List<Map<String, Object>>)
                    r.execute("GET", "/api/players/Ada123/confiscated", Map.of(), Map.of());
            r.execute(
                "POST",
                "/api/players/Ada123/action",
                Map.of("action", "restore_item", "id", held.getFirst().get("id")),
                Map.of());
          });
      check(
          results,
          "Игровые действия, экономика и LoveAuth",
          "SIMULATED",
          () -> {
            for (String action :
                List.of("freeze", "unfreeze", "heal", "feed", "teleport_spawn", "vanish"))
              r.execute("POST", "/api/players/Ada123/action", Map.of("action", action), Map.of());
            r.execute(
                "POST", "/api/economy/set", Map.of("player", "Ada123", "amount", 123), Map.of());
            r.execute("POST", "/api/loveauth/player/Ada123/unlock", Map.of(), Map.of());
          });
      check(
          results,
          "Личный кабинет: одноразовый обмен ссылки",
          "SERVER",
          () -> {
            var code = map(r.control("portal", Map.of()));
            r.execute("POST", "/api/portal/exchange", code, Map.of());
            try {
              r.execute("POST", "/api/portal/exchange", code, Map.of());
              throw new AssertionError("Одноразовая ссылка использована повторно");
            } catch (Rejected expected) {
              if (expected.status != 401) throw expected;
            }
          });
      check(
          results,
          "Роль наблюдателя не меняет данные",
          "SERVER",
          () -> {
            r.control("role", Map.of("id", 3));
            r.execute(
                "POST", "/api/me/preferences", Map.of("roleId", 1, "isOwner", true), Map.of());
            try {
              r.execute("POST", "/api/bans", Map.of("targetName", "Ada123"), Map.of());
              throw new AssertionError("Повышены права");
            } catch (Rejected expected) {
              if (expected.status != 403) throw expected;
            }
          });
      check(
          results,
          "Вход не принимает неверный код 2FA",
          "SERVER",
          () -> {
            r.control("login", Map.of());
            r.execute(
                "POST",
                "/api/auth/login",
                Map.of("username", "PreviewOwner", "password", "PreviewOnly!25"),
                Map.of());
            try {
              r.execute(
                  "POST",
                  "/api/auth/verify-2fa",
                  Map.of("username", "PreviewOwner", "challenge", "wrong", "code", "000000"),
                  Map.of());
              throw new AssertionError("Обход 2FA");
            } catch (Rejected expected) {
              if (expected.status != 401) throw expected;
            }
          });
      results.add(
          Map.of(
              "name",
              "Отправка и ответ Discord",
              "mode",
              "LIVE_REQUIRED",
              "passed",
              false,
              "detail",
              "Проверяйте созданное тестовое обращение и статус доставки; локальный прогон не отправляет сообщения"));
      return results;
    } finally {
      try (var paths = Files.walk(folder)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  private static void check(
      List<Map<String, Object>> results, String name, String mode, Check check) {
    try {
      check.run();
      results.add(
          Map.of(
              "name",
              name,
              "mode",
              mode,
              "passed",
              true,
              "detail",
              mode.equals("SIMULATED")
                  ? "Игровой сервер не изменялся"
                  : "Проверено в отдельном хранилище"));
    } catch (Throwable error) {
      results.add(
          Map.of(
              "name",
              name,
              "mode",
              mode,
              "passed",
              false,
              "detail",
              Objects.toString(error.getMessage(), error.getClass().getSimpleName())));
    }
  }
}
