package me.lovelace.loveWebAdmin.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ConfigUpgradeTest {
  @TempDir Path dir;

  String template() throws Exception {
    try (var in = getClass().getResourceAsStream("/config.yml")) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  void missingBlocksAreAddedButExplicitValuesSecretsListsAndUnknownKeysSurvive() throws Exception {
    var path = dir.resolve("config.yml");
    String original =
        """
        # Мои настройки
        web:
          port: 9090
        preview:
          enabled: true
          discord:
            category-id: '1548780207941615707'
            channel-id: '1548780207941615707'
        security:
          command-blacklist: [stop, custom-command]
          webhooks:
            discord:
              webhook-url: 'https://example.invalid/my-secret'
        own-setting: unchanged
        """;
    Files.writeString(path, original);
    var result = ConfigUpgrade.upgrade(path, template());
    assertTrue(result.changed());
    assertEquals(original, Files.readString(result.backup()));
    var after = YamlConfiguration.loadConfiguration(path.toFile());
    assertEquals(2, after.getInt("config-version"));
    assertEquals(9090, after.getInt("web.port"));
    assertTrue(after.getBoolean("preview.enabled"));
    assertEquals("1548780207941615707", after.getString("preview.discord.channel-id"));
    assertEquals(
        "https://example.invalid/my-secret",
        after.getString("security.webhooks.discord.webhook-url"));
    assertEquals(
        java.util.List.of("stop", "custom-command"),
        after.getStringList("security.command-blacklist"));
    assertEquals("unchanged", after.getString("own-setting"));
    assertTrue(after.isSet("discord.tickets.panel-channel-id"));
    assertEquals(20, after.getInt("discord.tickets.waiting.staff-reminder-minutes"));
    assertTrue(Files.readString(path).contains("ID ТЕКСТОВОГО канала"));
  }

  @Test
  void completedMigrationDoesNotRewriteOrGenerateAnotherBackup() throws Exception {
    var path = dir.resolve("config.yml");
    Files.writeString(path, "preview:\n  enabled: true\n");
    ConfigUpgrade.upgrade(path, template());
    String migrated = Files.readString(path);
    assertFalse(ConfigUpgrade.upgrade(path, template()).changed());
    assertEquals(migrated, Files.readString(path));
    try (var files = Files.list(dir)) {
      assertEquals(1, files.filter(p -> p.getFileName().toString().contains(".backup-")).count());
    }
  }

  @Test
  void malformedYamlIsNeverReplacedWithDefaults() throws Exception {
    var path = dir.resolve("config.yml");
    String original = "web: [ broken";
    Files.writeString(path, original);
    assertThrows(Exception.class, () -> ConfigUpgrade.upgrade(path, template()));
    assertEquals(original, Files.readString(path));
  }

  @Test
  void conflictingSectionTypeLeavesSourceUntouched() throws Exception {
    var path = dir.resolve("config.yml");
    Files.writeString(path, "discord: false\n");
    assertThrows(IllegalArgumentException.class, () -> ConfigUpgrade.upgrade(path, template()));
    assertEquals("discord: false\n", Files.readString(path));
  }

  @Test
  void futureSchemaIsNotDowngraded() throws Exception {
    var path = dir.resolve("config.yml");
    String original = "config-version: 99\ncustom: value\n";
    Files.writeString(path, original);
    assertFalse(ConfigUpgrade.upgrade(path, template()).changed());
    assertEquals(original, Files.readString(path));
  }

  @Test
  void cleanInstallHasTheDocumentedSchemaAndNoInventedDiscordDestinations() throws Exception {
    var path = dir.resolve("config.yml");
    var result = ConfigUpgrade.upgrade(path, template());
    assertTrue(result.changed());
    assertNull(result.backup());
    var config = YamlConfiguration.loadConfiguration(path.toFile());
    assertEquals("", config.getString("discord.tickets.panel-channel-id"));
    assertEquals("", config.getString("preview.discord.channel-id"));
    assertTrue(config.getStringList("preview.discord.allowed-user-ids").isEmpty());
    assertEquals(2, config.getInt("config-version"));
  }
}
