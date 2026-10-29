package me.lovelace.loveWebAdmin.models;

public record BanOrigin(Kind kind, String actor, String externalEventId) {
  public enum Kind {
    STAFF,
    ANTICHEAT,
    SECURITY,
    CONSOLE,
    UNKNOWN
  }

  public BanOrigin {
    java.util.Objects.requireNonNull(kind);
    if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Не указан источник");
  }

  public String display() {
    return switch (kind) {
      case STAFF -> "Сотрудник: " + actor;
      case ANTICHEAT -> "Античит";
      case SECURITY -> "Безопасность";
      case CONSOLE -> "Система";
      case UNKNOWN -> "Система";
    };
  }
}
