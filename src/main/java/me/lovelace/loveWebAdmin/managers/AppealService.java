package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;

public final class AppealService {
  private final LoveWebAdmin plugin;

  public AppealService(LoveWebAdmin plugin) {
    this.plugin = plugin;
  }

  public String decide(int id, String actor, boolean approve, String reason) {
    if (reason == null || reason.trim().length() < 5)
      throw new IllegalArgumentException("Укажите обоснование решения");
    var appeal =
        plugin
            .getDatabaseManager()
            .getAppealById(id)
            .orElseThrow(() -> new IllegalArgumentException("Апелляция не найдена"));
    try {
      if (approve && "SECURITY".equals(plugin.moderation().source(appeal.banId()).get("kind")))
        throw new IllegalArgumentException(
            "Снимите ограничение через раздел безопасности LoveAuth; обычный разбан к нему"
                + " неприменим");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    if (!plugin.getDatabaseManager().reserveAppealDecision(id))
      throw new IllegalArgumentException("Апелляция уже закрыта или рассматривается");
    boolean completed = false;
    try {
      if (approve && !plugin.getBanManager().unban(appeal.banId(), actor))
        throw new IllegalStateException("Не удалось снять блокировку");
      String status = approve ? "APPROVED" : "REJECTED";
      if (!plugin.getDatabaseManager().updateAppealStatus(id, status))
        throw new IllegalStateException("Не удалось сохранить решение");
      completed = true;
      String message =
          (approve ? "Апелляция одобрена" : "Апелляция отклонена")
              + ". Сотрудник: "
              + actor
              + ".\n"
              + reason.trim();
      plugin.getDatabaseManager().addAppealMessage(id, actor, true, message);
      plugin.getDiscordBridge().finishAppeal(appeal, message);
      plugin
          .getLogManager()
          .logWebAction(
              actor, "Решение по апелляции №" + id + ": " + status + "; " + reason.trim());
      return message;
    } finally {
      if (!completed) plugin.getDatabaseManager().updateAppealStatus(id, "PENDING");
    }
  }
}
