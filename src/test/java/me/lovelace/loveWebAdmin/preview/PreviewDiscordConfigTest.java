package me.lovelace.loveWebAdmin.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreviewDiscordConfigTest {

  @Test
  void acceptsNumbersAndStringsAndTrims() {
    var config = PreviewDiscordConfig.parse(" 12345 ", "67890", List.of(1480933101902499951L, " 22222 ", ""));
    assertTrue(config.problems().isEmpty());
    assertEquals("12345", config.category());
    assertEquals(2, config.recipients().size());
    assertTrue(config.recipients().contains("1480933101902499951"));
  }

  @Test
  void emptyConfigIsValid() {
    var config = PreviewDiscordConfig.parse("", "", List.of());
    assertTrue(config.problems().isEmpty());
    assertTrue(config.recipients().isEmpty());
  }

  @Test
  void tooManyRecipientsDisablesDeliveryWithoutThrowing() {
    var ids = new ArrayList<String>();
    for (int i = 0; i < 11; i++) ids.add("1000" + i);
    var config = PreviewDiscordConfig.parse("12345", "67890", ids);
    assertTrue(config.problems().get(0).contains("11"));
    assertTrue(config.recipients().isEmpty());
    assertEquals("", config.channel());
  }

  @Test
  void namesTheOffendingValue() {
    var config = PreviewDiscordConfig.parse("", "", List.of("YOUR_ID"));
    assertTrue(config.problems().get(0).contains("YOUR_ID"));
    assertTrue(config.recipients().isEmpty());
  }

  @Test
  void badChannelIdIsReported() {
    var config = PreviewDiscordConfig.parse("abc", "", List.of());
    assertTrue(config.problems().get(0).contains("category-id"));
    assertEquals("", config.category());
  }
}
