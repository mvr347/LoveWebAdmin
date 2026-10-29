package me.lovelace.loveWebAdmin.models;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BanOriginTest {
  @Test
  void sourceLabels() {
    assertEquals(
        "Сотрудник: Ada123", new BanOrigin(BanOrigin.Kind.STAFF, "Ada123", null).display());
    assertEquals("Античит", new BanOrigin(BanOrigin.Kind.ANTICHEAT, "Vesuvio", null).display());
    assertEquals(
        "Безопасность", new BanOrigin(BanOrigin.Kind.SECURITY, "LoveAuth", null).display());
  }

  @Test
  void missingSourceRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new BanOrigin(BanOrigin.Kind.STAFF, "", null));
  }
}
