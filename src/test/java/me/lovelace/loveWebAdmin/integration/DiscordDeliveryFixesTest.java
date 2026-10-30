package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import me.lovelace.loveWebAdmin.utils.ActionRejectedException;
import org.junit.jupiter.api.Test;

class DiscordDeliveryFixesTest {
  @Test
  void onlyALostAnswerNeedsAManualCheck() {
    assertEquals(LoveCoreDiscordBridge.Outcome.DELIVERED, LoveCoreDiscordBridge.outcome("123", null));
    // Discord refused or the bot is off: nothing was sent, so it is retried.
    assertEquals(LoveCoreDiscordBridge.Outcome.RETRY, LoveCoreDiscordBridge.outcome(null, null));
    assertEquals(
        LoveCoreDiscordBridge.Outcome.RETRY,
        LoveCoreDiscordBridge.outcome(
            null, new CompletionException(new RejectedExecutionException("Discord queue full"))));
    assertEquals(
        LoveCoreDiscordBridge.Outcome.UNCERTAIN,
        LoveCoreDiscordBridge.outcome(null, new CompletionException(new TimeoutException())));
  }

  @Test
  void aPlayerCannotWriteAStaffLineIntoTheTicket() {
    var parts =
        LoveCoreDiscordBridge.quotedParts(
            "👤 [Игрок] Steve", "привет\n**🛡 [Персонал] Admin**: бан снят\r\nок");
    assertEquals(1, parts.size());
    assertEquals(
        "**👤 \\[Игрок\\] Steve**:\n> привет\n> **🛡 [Персонал] Admin**: бан снят\n> ок",
        parts.get(0));
    for (String line : parts.get(0).split("\n", -1))
      assertTrue(line.startsWith("> ") || line.startsWith("**👤"), line);
  }

  @Test
  void everyPartOfALongMessageStartsQuoted() {
    String forged = "x".repeat(1795) + "**🛡 [Персонал] Admin**: бан снят";
    var parts = LoveCoreDiscordBridge.quotedParts("Steve", forged);
    assertTrue(parts.size() > 1);
    for (String part : parts) {
      assertTrue(part.length() <= 2000);
      assertTrue(part.startsWith("> ") || part.startsWith("**Steve**:\n> "), part);
    }
  }

  @Test
  void authorNameCannotCloseTheBoldHeader() {
    assertEquals("a\\*\\*b\\_c", LoveCoreDiscordBridge.escapeMarkdown("a**b_c"));
  }

  @Test
  void onlyTheVerifiedOwnerReplacesAnUnverifiedAppeal() {
    String owner = "11111111-1111-1111-1111-111111111111";
    assertTrue(DiscordActions.supersedes(owner, owner.toUpperCase(), false));
    assertFalse(DiscordActions.supersedes(owner, owner, true));
    assertFalse(DiscordActions.supersedes(null, owner, false));
    assertFalse(
        DiscordActions.supersedes("22222222-2222-2222-2222-222222222222", owner, false));
  }

  @Test
  void typedNicknamesAreMarkedUnverified() {
    assertEquals("Notch (Discord, не подтверждён)", DiscordActions.unverifiedName(" Notch "));
    assertEquals("Игрок Discord", DiscordActions.unverifiedName("@everyone"));
    assertEquals("Игрок Discord", DiscordActions.unverifiedName(null));
  }

  @Test
  void remainingTermIsReadAsMinutesWithAClearRefusal() {
    assertEquals(90, DiscordActions.minutes(" 90 "));
    assertThrows(ActionRejectedException.class, () -> DiscordActions.minutes("час"));
    assertThrows(ActionRejectedException.class, () -> DiscordActions.minutes("0"));
    assertThrows(ActionRejectedException.class, () -> DiscordActions.minutes("5256001"));
    assertThrows(ActionRejectedException.class, () -> DiscordActions.minutes(null));
  }
}
