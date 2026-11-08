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
  void removalOfOldMessagingSecretsKeepsAnExactBackupAndOtherSettings() throws Exception {
    var file = dir.resolve("config.yml");
    String original =
        "config-version: 2\n"
            + "web:\n"
            + "  port: 9090\n"
            + "security:\n"
            + "  webhooks:\n"
            + "    enabled: true\n"
            + "    telegram:\n"
            + "      bot-token: old-secret\n"
            + "      chat-id: '12345'\n"
            + "    discord-url: https://discord.com/api/webhooks/123456789/legacy-secret\n";
    Files.writeString(file, original);
    var migration = ConfigUpgrade.upgrade(file, template());
    assertEquals(original, Files.readString(migration.backup()));
    var after = YamlConfiguration.loadConfiguration(file.toFile());
    assertFalse(after.isSet("security.webhooks.telegram"));
    assertFalse(Files.readString(file).contains("old-secret"));
    assertEquals(9090, after.getInt("web.port"));
    assertFalse(after.isSet("security.webhooks"));
  }

  @Test
  void anOldConfigGainsSupportTopicsAndAnOwnerEditedListSurvives() throws Exception {
    var file = dir.resolve("config.yml");
    Files.writeString(file, "config-version: 3\nweb:\n  port: 9090\n");
    ConfigUpgrade.upgrade(file, template());
    var after = YamlConfiguration.loadConfiguration(file.toFile());
    assertTrue(after.getBoolean("discord.tickets.support.enabled"));
    assertEquals(3, after.getMapList("discord.tickets.support.topics").size());

    Files.writeString(
        file,
        "config-version: 3\n"
            + "discord:\n"
            + "  tickets:\n"
            + "    support:\n"
            + "      enabled: true\n"
            + "      topics:\n"
            + "        - id: only\n"
            + "          label: Единственная\n"
            + "          description: ''\n");
    ConfigUpgrade.upgrade(file, template());
    var kept =
        YamlConfiguration.loadConfiguration(file.toFile())
            .getMapList("discord.tickets.support.topics");
    assertEquals(1, kept.size());
    assertEquals("only", kept.getFirst().get("id"));
  }

  @Test
  void removedDonationTopicIsMigratedOnceWithoutChangingOtherTopicsOrSettings() throws Exception {
    var path = dir.resolve("config.yml");
    Files.writeString(path, template());
    var old = YamlConfiguration.loadConfiguration(path.toFile());
    old.set("web.port", 9090);
    old.set(
        "discord.tickets.support.topics",
        java.util.List.of(
            java.util.Map.of("id", "question", "label", "Мой вопрос", "description", "Описание"),
            java.util.Map.of("id", "donate", "label", "Донат", "description", "Покупки"),
            java.util.Map.of(
                "id", "custom", "label", "Своя тема", "description", "Пользовательская")));
    old.save(path.toFile());
    String before = Files.readString(path);
    var result = ConfigUpgrade.upgrade(path, template());
    assertTrue(result.changed());
    assertEquals(before, Files.readString(result.backup()));
    var after = YamlConfiguration.loadConfiguration(path.toFile());
    var topics = after.getMapList("discord.tickets.support.topics");
    assertEquals(
        java.util.List.of("question", "custom"),
        topics.stream().map(row -> row.get("id")).toList());
    assertEquals("Мой вопрос", topics.getFirst().get("label"));
    assertEquals("Пользовательская", topics.getLast().get("description"));
    assertEquals(9090, after.getInt("web.port"));
    assertFalse(ConfigUpgrade.upgrade(path, template()).changed());
  }

  @Test
  void donationOnlyListStaysEmptyAfterRemovalAndDefaultsDoNotReappear() throws Exception {
    var path = dir.resolve("config.yml");
    Files.writeString(path, template());
    var old = YamlConfiguration.loadConfiguration(path.toFile());
    old.set(
        "discord.tickets.support.topics",
        java.util.List.of(java.util.Map.of("id", "donate", "label", "Донат")));
    old.save(path.toFile());
    ConfigUpgrade.upgrade(path, template());
    var after = YamlConfiguration.loadConfiguration(path.toFile());
    assertTrue(after.getMapList("discord.tickets.support.topics").isEmpty());
    assertFalse(ConfigUpgrade.upgrade(path, template()).changed());
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
    assertEquals(3, after.getInt("config-version"));
    assertEquals(9090, after.getInt("web.port"));
    assertTrue(after.getBoolean("preview.enabled"));
    assertEquals("1548780207941615707", after.getString("preview.discord.channel-id"));
    assertFalse(after.isSet("security.webhooks"));
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
    assertEquals(3, config.getInt("config-version"));
  }
}
