package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.junit.jupiter.api.Test;

class DiscordLoginHelpTest {
  @Test
  void unavailableOrMismatchedSecurityNeverClaimsTheAccountIsHealthy() {
    var identity = new DiscordPlayerContext.Identity(UUID.randomUUID(), "Player");
    String text =
        JsonUtils.toJson(
            DiscordLoginHelp.snapshot(
                Optional.of(identity), List.of(), Map.of("available", false)));
    assertTrue(text.contains("проверка недоступна"));
    assertFalse(text.contains("вы уже авторизованы"));
    text =
        JsonUtils.toJson(
            DiscordLoginHelp.snapshot(
                Optional.of(identity),
                List.of(),
                Map.of(
                    "available",
                    true,
                    "linked",
                    true,
                    "uuid",
                    UUID.randomUUID().toString(),
                    "authenticated",
                    true)));
    assertTrue(text.contains("ожидают синхронизации"));
    assertFalse(text.contains("вы уже авторизованы"));
  }

  @Test
  void pendingConfirmationPointsToTheExistingPromptInsteadOfBypassingIt() {
    var identity = new DiscordPlayerContext.Identity(UUID.randomUUID(), "Player");
    String text =
        JsonUtils.toJson(
            DiscordLoginHelp.snapshot(
                Optional.of(identity),
                List.of(),
                Map.of(
                    "available",
                    true,
                    "linked",
                    true,
                    "uuid",
                    identity.uuid().toString(),
                    "confirmationPending",
                    true)));
    assertTrue(text.contains("ожидается ваше подтверждение"));
    assertTrue(text.contains("Новый запрос не создаётся"));
    assertFalse(text.contains("la:core:yes"));
  }
}
