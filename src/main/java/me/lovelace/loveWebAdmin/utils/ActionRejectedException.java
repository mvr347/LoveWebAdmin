package me.lovelace.loveWebAdmin.utils;

/** An expected, deliberately public explanation of why a user action is unavailable. */
public final class ActionRejectedException extends IllegalArgumentException {
  public ActionRejectedException(String message) {
    super(message);
  }
}
