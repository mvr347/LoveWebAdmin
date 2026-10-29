package me.lovelace.loveWebAdmin.workflow;

import org.bukkit.configuration.ConfigurationSection;

/** Waiting time is informational; no timeout can close a case or change a punishment. */
public record CaseWaitPolicy(int staffMinutes, int escalationMinutes, int playerMinutes) {
  public CaseWaitPolicy {
    if (staffMinutes < 0
        || escalationMinutes < 0
        || playerMinutes < 0
        || staffMinutes > 43200
        || escalationMinutes > 43200
        || playerMinutes > 43200
        || staffMinutes > 0 && escalationMinutes > 0 && escalationMinutes < staffMinutes)
      throw new IllegalArgumentException("Проверьте сроки ожидания обращений");
  }

  public static CaseWaitPolicy defaults() {
    return new CaseWaitPolicy(20, 60, 2880);
  }

  public static CaseWaitPolicy from(ConfigurationSection config) {
    String p = "discord.tickets.waiting.";
    return new CaseWaitPolicy(
        config.getInt(p + "staff-reminder-minutes", 20),
        config.getInt(p + "staff-escalation-minutes", 60),
        config.getInt(p + "player-reminder-minutes", 2880));
  }

  public long deadline(String mode, long since, int stage) {
    int minutes =
        mode.equals("PLAYER") ? playerMinutes : stage == 2 ? escalationMinutes : staffMinutes;
    return minutes == 0 ? 0 : since + minutes * 60L;
  }
}
