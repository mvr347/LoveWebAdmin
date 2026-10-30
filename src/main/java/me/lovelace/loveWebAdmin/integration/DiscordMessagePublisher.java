package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.DiscordService;
import java.util.*;
import java.util.concurrent.TimeUnit;
import me.lovelace.loveWebAdmin.database.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/**
 * Durable shared publisher. Confirmed rejection is retryable; uncertain creation requires
 * reconciliation.
 */
public final class DiscordMessagePublisher {
  public static String publish(
      TicketDeskStore store,
      DiscordService service,
      String slot,
      String channel,
      Map<String, Object> payload)
      throws Exception {
    if (service == null || !service.isEnabled())
      throw new IllegalArgumentException("Бот Discord выключен или недоступен");
    if (!channel.matches("[0-9]{5,25}"))
      throw new IllegalArgumentException("Укажите числовой ID канала панели");
    var previous = store.message(slot);
    if (previous.filter(m -> m.get("state").equals("UNCERTAIN")).isPresent())
      throw new IllegalArgumentException(
          "Результат предыдущей отправки неизвестен. Сверьте сообщение в разделе «Доставка Discord»");
    if (!store.sending(slot, channel)) return previous.map(m -> m.get("message")).orElse("");
    try {
      var data = new LinkedHashMap<String, Object>(payload);
      data.put(
          "nonce",
          new java.math.BigInteger(
                  1, java.util.HexFormat.of().parseHex(ModerationStore.hash(slot).substring(0, 20)))
              .toString());
      data.put("enforce_nonce", true);
      String message =
          previous
              .filter(m -> m.get("channel").equals(channel))
              .map(m -> m.get("message"))
              .filter(s -> !s.isBlank())
              .orElse(null);
      String result =
          CoreDiscordExtensions.<String>managed(
                  service,
                  "ensureMessage",
                  new Class<?>[] {String.class, String.class, String.class},
                  channel,
                  message,
                  JsonUtils.toJson(data))
              .get(45, TimeUnit.SECONDS);
      if (result == null || !result.matches("[0-9]{5,25}"))
        throw new IllegalStateException("Discord не подтвердил ID сообщения");
      store.sent(slot, result);
      return result;
    } catch (Exception error) {
      if (definitive(error)) store.failed(slot, explanation(error));
      else
        store.uncertain(
            slot, "Результат отправки неизвестен. Сверьте сообщение в Discord перед повтором");
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      throw error;
    }
  }

  public static boolean definitive(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof UnsupportedOperationException
          || cause instanceof ReflectiveOperationException
          || cause instanceof java.util.concurrent.RejectedExecutionException) return true;
      try {
        int status =
            ((Number) cause.getClass().getMethod("getStatusCode").invoke(cause)).intValue();
        if (Set.of(400, 401, 403, 404, 413, 422, 429).contains(status)) return true;
      } catch (ReflectiveOperationException ignored) {
      }
    }
    return false;
  }

  public static String explanation(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof IllegalArgumentException
          && Set.of(
                  "Тестовая категория совпадает с рабочей",
                  "Канал принадлежит другому Discord-серверу",
                  "Некорректный ID канала")
              .contains(Objects.toString(cause.getMessage(), ""))) return cause.getMessage();
      if (cause instanceof UnsupportedOperationException
          || cause instanceof ReflectiveOperationException)
        return "Обновите ядро: публикация сообщений этой версией не поддерживается";
      if (cause instanceof java.util.concurrent.RejectedExecutionException)
        return "Очередь Discord занята; повторите позже";
      try {
        int status =
            ((Number) cause.getClass().getMethod("getStatusCode").invoke(cause)).intValue();
        return switch (status) {
          case 400, 413, 422 -> "Discord отклонил формат сообщения (HTTP " + status + ")";
          case 401 -> "Discord отклонил токен бота (HTTP 401)";
          case 403 -> "Боту недоступен канал или не хватает прав отправки (HTTP 403)";
          case 404 -> "Канал не найден (HTTP 404)";
          case 429 -> "Ограничение частоты запросов Discord (HTTP 429)";
          default -> "Результат запроса Discord не подтверждён";
        };
      } catch (ReflectiveOperationException ignored) {
      }
    }
    return "Результат Discord не подтверждён; требуется сверка сообщения";
  }
}
