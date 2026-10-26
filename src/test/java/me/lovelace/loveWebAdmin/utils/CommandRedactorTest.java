package me.lovelace.loveWebAdmin.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CommandRedactorTest {
  @Test
  void hidesNamespacedLoginAndUnicodeAliases() {
    assertEquals(
        "loveauth:login [параметры скрыты]", CommandRedactor.redact("loveauth:login password123"));
    assertEquals("/вход [параметры скрыты]", CommandRedactor.redact("/вход password123"));
  }

  @Test
  void keepsModerationArguments() {
    assertEquals("ban Ada123 reason", CommandRedactor.redact("ban Ada123 reason"));
  }

  @Test
  void nestedAuthCommandsAndServerLogLinesDoNotLeakPasswords() {
    assertEquals(
        "loveauthadmin [параметры скрыты]",
        CommandRedactor.redact("loveauthadmin setpassword Ada123 secretValue"));
    assertEquals(
        "Ada123 issued server command: /login [параметры скрыты]",
        CommandRedactor.redactLog("Ada123 issued server command: /login secretValue"));
  }
}
