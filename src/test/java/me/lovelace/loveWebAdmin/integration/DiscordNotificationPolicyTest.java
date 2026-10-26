package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DiscordNotificationPolicyTest {
  DiscordNotificationPolicy policy(boolean enabled, int start, int end) {
    return new DiscordNotificationPolicy(
        true, enabled, start, end, ZoneId.of("Europe/Moscow"), 15, Set.of());
  }

  @Test
  void quietHoursAcrossMidnightDelayUntilMorning() {
    assertEquals(
        Instant.parse("2026-10-27T05:00:00Z"),
        policy(true, 23, 8).nextDelivery(Instant.parse("2026-10-26T21:15:00Z")));
  }

  @Test
  void exactEndIsNoLongerQuiet() {
    var at = Instant.parse("2026-10-27T05:00:00Z");
    assertEquals(at, policy(true, 23, 8).nextDelivery(at));
  }

  @Test
  void daytimeIntervalStaysOnSameDate() {
    assertEquals(
        Instant.parse("2026-10-26T14:00:00Z"),
        policy(true, 9, 17).nextDelivery(Instant.parse("2026-10-26T09:00:00Z")));
  }

  @Test
  void disabledAndEqualBoundsNeverDelay() {
    var at = Instant.parse("2026-10-26T22:00:00Z");
    assertEquals(at, policy(false, 23, 8).nextDelivery(at));
    assertEquals(at, policy(true, 8, 8).nextDelivery(at));
  }
}
