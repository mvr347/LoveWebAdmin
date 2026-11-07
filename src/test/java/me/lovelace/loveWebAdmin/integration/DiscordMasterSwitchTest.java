package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DiscordMasterSwitchTest {
  @Test
  void discordFeaturesAreOnByDefaultAndOffWhenSwitchedOff() {
    assertTrue(LoveCoreDiscordBridge.discordEnabled(new YamlConfiguration()), "missing key = on, as before");
    var off = new YamlConfiguration();
    off.set("discord.enabled", false);
    assertFalse(LoveCoreDiscordBridge.discordEnabled(off));
  }

  @Test
  void theShippedConfigHasTheSwitchOn() throws Exception {
    var shipped = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
    assertTrue(shipped.contains("discord.enabled"));
    assertTrue(LoveCoreDiscordBridge.discordEnabled(shipped));
    assertTrue(Files.readString(new File("src/main/resources/config.yml").toPath()).contains("2026-11-07"));
  }
}
