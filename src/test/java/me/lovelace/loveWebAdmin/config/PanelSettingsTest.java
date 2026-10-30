package me.lovelace.loveWebAdmin.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.utils.PasswordUtils;

class PanelSettingsTest {
  @Test
  void invalidCombinedPatchDoesNotAlterTheOriginalSnapshot() {
    var before = PanelSettings.defaults();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PanelSettings.normalize(
                Map.of(
                    "webPort",
                    9090,
                    "previewCategoryId",
                    "123456789012345678",
                    "previewChannelId",
                    "123456789012345678"),
                before));
    assertEquals(8080, before.get("webPort"));
    assertEquals("", before.get("previewChannelId"));
  }

  @Test
  void discordIdsRemainStringsAndHaveExplicitBounds() {
    var id = "1548780207941615707";
    var patch =
        PanelSettings.normalize(
            Map.of("previewAllowedUserIds", List.of(id), "previewCategoryId", id),
            PanelSettings.defaults());
    assertEquals(List.of(id), patch.get("previewAllowedUserIds"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PanelSettings.normalize(
                Map.of("previewChannelId", 1548780207941615707L), PanelSettings.defaults()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PanelSettings.normalize(
                Map.of(
                    "previewAllowedUserIds",
                    java.util.stream.IntStream.range(0, 11).mapToObj(i -> "1000" + i).toList()),
                PanelSettings.defaults()));
  }

  @Test
  void remindersAndQuietHoursRejectInvalidCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PanelSettings.normalize(
                Map.of("discordStaffReminderMinutes", 60, "discordStaffEscalationMinutes", 20),
                PanelSettings.defaults()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PanelSettings.normalize(Map.of("discordQuietStartHour", 24), PanelSettings.defaults()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PanelSettings.normalize(
                Map.of("discordQuietTimezone", "Mars/Unknown"), PanelSettings.defaults()));
    assertEquals(
        0,
        PanelSettings.normalize(
                Map.of("discordStaffEscalationMinutes", 0), PanelSettings.defaults())
            .get("discordStaffEscalationMinutes"));
  }

  @Test
  void webhookIsMaskedRetainedWhenBlankAndOnlyExplicitlyCleared() {
    var current = PanelSettings.defaults();
    String secret = "https://discord.com/api/webhooks/123456789/private-secret";
    current.put("discordWebhookUrl", secret);
    var view = PanelSettings.view(current, p -> true);
    assertFalse(view.toString().contains("private-secret"));
    assertEquals(true, view.get("discordWebhookConfigured"));
    assertTrue(PanelSettings.normalize(Map.of("discordWebhookUrl", ""), current).isEmpty());
    var clear = PanelSettings.normalize(Map.of("clearDiscordWebhookUrl", true), current);
    assertEquals("", clear.get("discordWebhookUrl"));
    assertEquals(false, clear.get("webhooksEnabled"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PanelSettings.normalize(
                Map.of("discordWebhookUrl", secret, "clearDiscordWebhookUrl", true), current));
  }

  @Test
  void onlyDiscordWebhookAndRealPublicAddressesAreAccepted() {
    for (String url :
        List.of(
            "http://discord.com/api/webhooks/12345/token",
            "https://example.com/private-token",
            "https://discord.com@evil.invalid/api/webhooks/12345/token"))
      assertFalse(PanelSettings.validWebhook(url));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PanelSettings.normalize(
                Map.of("webPublicUrl", "javascript:alert(1)"), PanelSettings.defaults()));
    assertThrows(
        IllegalArgumentException.class,
        () -> PanelSettings.normalize(Map.of("webhooksEnabled", true), PanelSettings.defaults()));
  }

  @Test
  void integrationManagerSeesTheirSettingsWithoutUnrelatedServerFields() {
    var data =
        PanelSettings.view(PanelSettings.defaults(), p -> p == Permission.MANAGE_INTEGRATIONS);
    assertTrue(data.containsKey("discordPanelChannelId"));
    assertFalse(data.containsKey("webPort"));
    assertFalse(data.containsKey("previewCategoryId"));
    assertFalse(data.containsKey("discordWebhookUrl"));
  }

  @Test
  void removedAndUnknownSettingsCannotBeStored() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PanelSettings.normalize(
                Map.of("telegramBotToken", "obsolete"), PanelSettings.defaults()));
    assertThrows(
        IllegalArgumentException.class,
        () -> PanelSettings.normalize(Map.of("unknownField", true), PanelSettings.defaults()));
    assertThrows(
        IllegalArgumentException.class,
        () -> PanelSettings.normalize(Map.of("previewEnabled", "yes"), PanelSettings.defaults()));
  }

  @Test
  void configuredPasswordLengthDoesNotWeakenTheExistingPolicy() {
    String password = "TestPassword25!";
    assertNull(PasswordUtils.validateStrength(password));
    assertNotNull(PasswordUtils.validateStrength(password, 24));
    assertNull(PasswordUtils.validateStrength("MuchLongerTestPassword25!", 24));
    assertNotNull(PasswordUtils.validateStrength("Short25!", 8));
  }
}
