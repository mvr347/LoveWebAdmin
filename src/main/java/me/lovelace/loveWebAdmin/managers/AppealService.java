package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;

public final class AppealService {
  private final LoveWebAdmin plugin;

  public AppealService(LoveWebAdmin plugin) {
    this.plugin = plugin;
  }

  public String decide(int id, String actor, boolean approve, String reason) {
    return resolve(id, actor, approve ? "APPROVED" : "REJECTED", reason, 0);
  }

  public String reduce(int id, String actor, long remainingSeconds, String reason) {
    return resolve(id, actor, "REDUCED", reason, remainingSeconds);
  }

  private String resolve(
      int id, String actor, String status, String reason, long remainingSeconds) {
    boolean approve = status.equals("APPROVED");
    if (reason == null || reason.trim().length() < 5 || reason.length() > 2000)
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
          "Укажите обоснование решения");
    var appeal =
        plugin
            .getDatabaseManager()
            .getAppealById(id)
            .orElseThrow(
                () ->
                    new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
                        "Апелляция не найдена"));
    try {
      if (!status.equals("REJECTED")
          && "SECURITY".equals(plugin.moderation().source(appeal.banId()).get("kind")))
        throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
            "Снимите ограничение через раздел безопасности LoveAuth; обычный разбан к нему"
                + " неприменим");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    if (!plugin.getDatabaseManager().reserveAppealDecision(id))
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
          "Апелляция уже закрыта или рассматривается");
    boolean completed = false;
    boolean nativeChanged = false;
    try {
      if (approve && !plugin.getBanManager().unban(appeal.banId(), actor))
        throw new IllegalStateException("Не удалось снять блокировку");
      if (status.equals("REDUCED"))
        plugin.getBanManager().reduceDuration(appeal.banId(), actor, remainingSeconds);
      nativeChanged = approve || status.equals("REDUCED");
      String message =
          (approve
                  ? "Апелляция одобрена"
                  : status.equals("REDUCED")
                      ? "Срок сокращён. Осталось: " + remainingSeconds + " сек."
                      : "Апелляция отклонена")
              + ". Сотрудник: "
              + actor
              + ".\n"
              + reason.trim();
      if (!plugin.getDatabaseManager().finishAppealDecision(id, status, actor, message))
        throw new IllegalStateException("Не удалось сохранить решение");
      completed = true;
      plugin.getDiscordBridge().finishAppeal(appeal, message);
      plugin
          .getLogManager()
          .logWebAction(
              actor, "Решение по апелляции №" + id + ": " + status + "; " + reason.trim());
      return message;
    } finally {
      if (!completed && !nativeChanged)
        plugin.getDatabaseManager().updateAppealStatus(id, "PENDING");
      else if (!completed)
        plugin
            .getLogger()
            .severe(
                "Апелляция №"
                    + id
                    + ": наказание изменено, но решение не сохранено. Состояние DECIDING оставлено"
                    + " для ручной проверки.");
    }
  }
}
