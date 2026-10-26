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
    return (SECRET.contains(label.toLowerCase(Locale.ROOT)) || label.toLowerCase(Locale.ROOT).contains("auth"))
        ? words[0] + " [параметры скрыты]"
        : clean;
  }
  public static String redactLog(String line) {
    String marker="issued server command: ";int start=line.indexOf(marker);
    return start<0?line:line.substring(0,start+marker.length())+redact(line.substring(start+marker.length()));
  }

}
