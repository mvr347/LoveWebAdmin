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
}
