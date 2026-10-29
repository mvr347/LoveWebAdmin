package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;

public final class AppealService {
  public interface Context {
    me.lovelace.loveWebAdmin.database.DatabaseManager database();

    me.lovelace.loveWebAdmin.database.ModerationStore moderation();

    boolean unban(int ban, String actor);

    void reduce(int ban, String actor, long seconds);

    void finish(me.lovelace.loveWebAdmin.models.WebBanAppeal appeal, String message);

    void audit(String actor, String message);

    void failure(String message);
  }

  private final Context context;

  public AppealService(Context context) {
    this.context = java.util.Objects.requireNonNull(context);
  }

  public AppealService(LoveWebAdmin plugin) {
    this(
        new Context() {
          public me.lovelace.loveWebAdmin.database.DatabaseManager database() {
            return plugin.getDatabaseManager();
          }

          public me.lovelace.loveWebAdmin.database.ModerationStore moderation() {
            return plugin.moderation();
          }

          public boolean unban(int id, String actor) {
            return plugin.getBanManager().unban(id, actor);
          }

          public void reduce(int id, String actor, long seconds) {
            plugin.getBanManager().reduceDuration(id, actor, seconds);
          }

          public void finish(me.lovelace.loveWebAdmin.models.WebBanAppeal appeal, String message) {
            plugin.getDiscordBridge().finishAppeal(appeal, message);
          }

          public void audit(String actor, String message) {
            plugin.getLogManager().logWebAction(actor, message);
          }

          public void failure(String message) {
            plugin.getLogger().severe(message);
          }
        });
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
        context
            .database()
            .getAppealById(id)
            .orElseThrow(
                () ->
                    new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
                        "Апелляция не найдена"));
    try {
      if (!status.equals("REJECTED")
          && "SECURITY".equals(context.moderation().source(appeal.banId()).get("kind")))
        throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
            "Снимите ограничение через раздел безопасности; обычный разбан к нему" + " неприменим");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    try {
      var source = context.moderation().source(appeal.banId());
      if ("STAFF".equals(source.get("kind"))
          && actor.equalsIgnoreCase(source.get("actor").toString()))
        throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
            "Своё наказание рассматривает другой сотрудник или руководство");
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
    if (!context.database().reserveAppealDecision(id))
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
          "Апелляция уже закрыта или рассматривается");
    boolean completed = false;
    boolean nativeChanged = false;
    try {
      if (approve
          && context
              .database()
              .getBanById(appeal.banId())
              .filter(b -> b.status().equals("ACTIVE"))
              .isPresent()
          && !context.unban(appeal.banId(), actor))
        throw new IllegalStateException("Не удалось снять блокировку");
      if (status.equals("REDUCED")) context.reduce(appeal.banId(), actor, remainingSeconds);
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
      if (!context.database().finishAppealDecision(id, status, actor, message))
        throw new IllegalStateException("Не удалось сохранить решение");
      completed = true;
      context.finish(appeal, message);
      context.audit(actor, "Решение по апелляции №" + id + ": " + status + "; " + reason.trim());
      return message;
    } finally {
      if (!completed && !nativeChanged) context.database().updateAppealStatus(id, "PENDING");
      else if (!completed)
        context.failure(
            "Апелляция №"
                + id
                + ": наказание изменено, но решение не сохранено. Состояние DECIDING оставлено"
                + " для ручной проверки.");
    }
  }
}
