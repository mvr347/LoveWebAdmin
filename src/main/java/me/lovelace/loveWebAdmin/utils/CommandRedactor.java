package me.lovelace.loveWebAdmin.utils;

import java.util.*;

public final class CommandRedactor {
  private static final Set<String> SECRET =
      Set.of(
          "login",
          "l",
          "log",
          "register",
          "reg",
          "changepassword",
          "changepass",
          "cp",
          "password",
          "2fa",
          "totp",
          "привязать",
          "войти",
          "регистрация",
          "вход");

  private CommandRedactor() {}

  public static String redact(String command) {
    String clean = Objects.toString(command, "").stripLeading();
    String[] words = clean.split("\\s+", 2);
    String label = words[0].replaceFirst("^/", "");
    int colon = label.lastIndexOf(':');
    if (colon >= 0) label = label.substring(colon + 1);
    return SECRET.contains(label.toLowerCase(Locale.ROOT))
        ? words[0] + " [параметры скрыты]"
        : clean;
  }
}
