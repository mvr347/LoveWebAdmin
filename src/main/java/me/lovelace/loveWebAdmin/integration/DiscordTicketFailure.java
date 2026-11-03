package me.lovelace.loveWebAdmin.integration;

import java.util.*;

/** Channel-specific diagnostics. Never logs arbitrary response bodies or exception messages. */
final class DiscordTicketFailure {
  static String describe(Throwable error, boolean previousUnknown) {
    Integer status = null;
    boolean preflight = false, notFound = false, noAnswer = false;
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      String message = Objects.toString(cause.getMessage(), "");
      if (message.startsWith("Канал ещё не создан:")) notFound = true;
      if (message.equals("Discord не подтвердил операцию (нет ответа)")) noAnswer = true;
      if (cause instanceof IllegalArgumentException && message.startsWith("Категория тикетов"))
        return message;
      if (message.startsWith("Не удалось проверить каналы обращения")
          || message.startsWith("Категория тикетов не проверена")
          || message.startsWith("Проверка бота не завершена")) preflight = true;
      var oldStatus =
          java.util.regex.Pattern.compile("^Discord не подтвердил операцию \\(([1-5][0-9]{2})\\)$")
              .matcher(message);
      if (oldStatus.matches()) status = Integer.parseInt(oldStatus.group(1));
      try {
        status = ((Number) cause.getClass().getMethod("getStatusCode").invoke(cause)).intValue();
      } catch (ReflectiveOperationException ignored) {
      }
    }
    if (notFound)
      return previousUnknown
          ? "Сверка выполнена: соответствующий канал не найден. Прежний результат создания"
                + " неизвестен; сотруднику нужно сверить доставку и разрешить повтор."
          : "Сверка выполнена: канал пока не найден. Проверьте задание создания в разделе «Доставка"
                + " Discord»; обращение сохранено.";
    String prefix =
        previousUnknown
            ? "Результат прежнего создания неизвестен. Проверка каналов не подтвердила"
                  + " восстановление. "
            : preflight
                ? "Не удалось проверить существующие каналы; новый канал не создавался. "
                : "Создание канала не подтверждено. ";
    if (status != null)
      return prefix
          + switch (status) {
            case 400, 422 -> "Discord отклонил параметры канала или доступа (HTTP " + status + ").";
            case 401 -> "Discord отклонил авторизацию бота (HTTP 401).";
            case 403 -> "Боту не хватает доступа к категории или прав на каналы (HTTP 403).";
            case 404 -> "Категория или канал не найдены (HTTP 404).";
            case 429 -> "Discord ограничил частоту запросов (HTTP 429).";
            default -> "Ответ Discord: HTTP " + status + ".";
          };
    if (noAnswer)
      return prefix
          + "Discord не ответил на проверку каналов. Обращение сохранено; повторное создание без"
          + " сверки не выполняется.";
    return prefix
        + (preflight && !previousUnknown
            ? "Повтор будет выполнен позже."
            : "Сотруднику нужна сверка в разделе «Доставка Discord»; обращение сохранено.");
  }
}
