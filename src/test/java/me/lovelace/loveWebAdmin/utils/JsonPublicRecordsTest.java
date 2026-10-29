package me.lovelace.loveWebAdmin.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonPublicRecordsTest {
  public record Sensitive(
      String name,
      String passwordHash,
      String totpSecret,
      String token,
      String keyHash,
      String backupCodes) {}

  @Test
  void recordSerializationDoesNotAccidentallyExposeCredentials() {
    String json =
        JsonUtils.toJson(
            new Sensitive(
                "Visible",
                "password-value",
                "totp-value",
                "token-value",
                "key-value",
                "backup-value"));
    assertEquals("{\"name\":\"Visible\"}", json);
  }

  @Test
  void nonFiniteNumbersProduceValidJsonNull() {
    assertEquals("null", JsonUtils.toJson(Double.NaN));
    assertEquals("null", JsonUtils.toJson(Float.POSITIVE_INFINITY));
  }

  @Test
  void excessivelyNestedInputIsRejectedBeforeExhaustingStack() {
    assertThrows(
        IllegalArgumentException.class,
        () -> JsonUtils.parseObject("{\"x\":".repeat(10000) + "0" + "}".repeat(10000)));
  }
}
