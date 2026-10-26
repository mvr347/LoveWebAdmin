package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.lovecore.api.auth.AuthOracle;
import dev.lovelace.lovecore.api.combat.CombatState;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class GameplayPolicyTest {
  org.bukkit.entity.Player player;
  org.bukkit.plugin.java.JavaPlugin owner;

  @BeforeEach
  void setup() {
    MockBukkit.mock();
    owner = MockBukkit.createMockPlugin();
    player = MockBukkit.getMock().addPlayer();
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  @Test
  void noOptionalProvidersAllowsOrdinaryOnlinePlayer() {
    assertDoesNotThrow(() -> GameplayPolicy.check(player));
  }

  @Test
  void unauthenticatedPlayerCannotBeChanged() {
    MockBukkit.getMock()
        .getServicesManager()
        .register(AuthOracle.class, id -> false, owner, ServicePriority.Normal);
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> GameplayPolicy.check(player))
            .getMessage()
            .contains("авторизацию"));
  }

  @Test
  void combatTagBlocksInventoryAndGameplayChanges() {
    MockBukkit.getMock()
        .getServicesManager()
        .register(
            CombatState.class,
            new CombatState() {
              public boolean inCombat(UUID id) {
                return true;
              }

              public Optional<Instant> combatEndsAt(UUID id) {
                return Optional.empty();
              }

              public Optional<String> combatSource(UUID id) {
                return Optional.of("DeluxeCombat");
              }

              public void mark(UUID id, Duration duration, String source) {}

              public void clear(UUID id) {}
            },
            owner,
            ServicePriority.Normal);
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> GameplayPolicy.check(player))
            .getMessage()
            .contains("бою"));
  }

  @Test
  void stateChecksCannotReadPlayersOffThread() {
    assertThrows(
        CompletionException.class,
        () -> CompletableFuture.runAsync(() -> GameplayPolicy.check(player)).join());
  }

  @Test
  void incompatibleDuelProviderExplainsWhyActionIsUnavailable() {
    MockBukkit.createMockPlugin("LoveDuels");
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> GameplayPolicy.check(player))
            .getMessage()
            .contains("LoveDuels"));
  }
}
