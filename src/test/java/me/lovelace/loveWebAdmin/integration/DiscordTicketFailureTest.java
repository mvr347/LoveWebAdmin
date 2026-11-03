package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.discord.DiscordRequestRejectedException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class DiscordTicketFailureTest {
  @Test
  void legacyCoreReadStatusAndConfirmedAbsenceAreExplainedAsChannelChecks() {
    assertTrue(
        DiscordTicketFailure.describe(
                new IllegalStateException("Discord не подтвердил операцию (403)"), true)
            .contains("403"));
    assertTrue(
        DiscordTicketFailure.describe(new IllegalStateException("Канал ещё не создан:"), true)
            .contains("Сверка выполнена"));
    assertTrue(
        DiscordTicketFailure.describe(
                new IllegalStateException("Discord не подтвердил операцию (нет ответа)"), true)
            .contains("не ответил"));
  }

  @Test
  void previousUnknownIsNotReportedAsASafeNewCreation() {
    String text =
        DiscordTicketFailure.describe(
            new CompletionException(new DiscordRequestRejectedException(403)), true);
    assertTrue(text.contains("прежнего создания неизвестен"));
    assertTrue(text.contains("403"));
    assertFalse(text.contains("новый канал не создавался"));
  }

  @Test
  void preflightFailureExplainsThePhaseAndDoesNotExposeArbitraryTransportDetails() {
    String text =
        DiscordTicketFailure.describe(
            new RejectedExecutionException(
                "Не удалось проверить каналы обращения; новый канал не создавался",
                new RuntimeException("private-secret")),
            false);
    assertTrue(text.contains("существующие каналы"));
    assertTrue(text.contains("новый канал не создавался"));
    assertFalse(text.contains("private-secret"));
    assertFalse(
        DiscordTicketFailure.describe(new RuntimeException("private-secret"), false)
            .contains("private-secret"));
  }
}
