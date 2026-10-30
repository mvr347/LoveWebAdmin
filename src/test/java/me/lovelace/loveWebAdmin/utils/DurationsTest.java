package me.lovelace.loveWebAdmin.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DurationsTest {
  @Test
  void showsTheTwoLargestUnits() {
    assertEquals("30 дн.", Durations.human(2_592_000));
    assertEquals("30 дн. 2 ч.", Durations.human(2_599_200));
    assertEquals("1 ч. 30 мин.", Durations.human(5400));
    assertEquals("45 сек.", Durations.human(45));
    assertEquals("0 мин.", Durations.human(0));
  }
}
