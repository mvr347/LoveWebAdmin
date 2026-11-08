package me.lovelace.loveWebAdmin.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.util.regex.Pattern;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class LoveWebAdminCommandTest {
  ServerMock server;
  LoveWebAdmin plugin;

  @BeforeEach
  void setup() {
    server = MockBukkit.mock();
    MockBukkit.createMockPlugin("LoveCore");
    MockBukkit.createMockPlugin("LoveEssential");
    var config = new YamlConfiguration();
    config.set("web.enabled", false);
    plugin = MockBukkit.loadWithConfig(LoveWebAdmin.class, config);
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  String run(org.bukkit.command.CommandSender sender, String... args) {
    var command = plugin.getCommand("lovewebadmin");
    command.execute(sender, "lovewebadmin", args);
    var out = new StringBuilder();
    if (sender instanceof org.mockbukkit.mockbukkit.entity.PlayerMock player) {
      String line;
      while ((line = player.nextMessage()) != null) out.append(line).append('\n');
    } else if (sender instanceof org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock console) {
      String line;
      while ((line = console.nextMessage()) != null) out.append(line).append('\n');
    }
    return out.toString();
  }

  @Test
  void destructiveCommandsAreConsoleOnlyAndNeedTheOneTimeCode() {
    var console = server.getConsoleSender();
    var op = server.addPlayer();
    op.addAttachment(plugin, "lovewebadmin.admin", true);

    assertTrue(run(op, "discord", "purge").contains("только из консоли"));
    assertTrue(run(op, "resetowner").contains("только из консоли"));

    String asked = run(console, "discord", "purge");
    assertTrue(asked.contains("confirm"));
    var code = Pattern.compile("confirm (\\d{4})").matcher(asked);
    assertTrue(code.find());
    // A wrong code never starts the operation and issues a fresh warning instead.
    String wrong =
        run(console, "discord", "purge", "confirm", "0000".equals(code.group(1)) ? "1111" : "0000");
    assertFalse(wrong.contains("Очистка запущена"));
    assertTrue(wrong.contains("confirm"));
  }

  @Test
  void infoAndGenerateTokenReportTheOwnerState() {
    var console = server.getConsoleSender();
    assertTrue(run(console, "info").contains("Управляющий"));
    String token = run(console, "generatetoken");
    if (plugin.getAdminManager().hasOwner()) assertTrue(token.contains("уже создан"));
    else assertTrue(token.contains("LWA-"));
  }

  @Test
  void previewCommandsNeedAdminAndEnabledSandboxAndUsePublicUrl() {
    var console = server.getConsoleSender();
    plugin.getConfig().set("preview.enabled", false);
    assertTrue(run(console, "test", "support").contains("preview.enabled"));
    plugin.getConfig().set("preview.enabled", true);
    plugin.getConfig().set("web.public-url", "https://panel.example.test/");
    assertTrue(
        run(console, "test", "support")
            .contains("https://panel.example.test/preview/?test=support#section=support"));
    assertTrue(run(console, "test", "unknown").contains("Тесты:"));
    var player = server.addPlayer();
    assertFalse(run(player, "test", "cases").contains("/preview/"));
  }
}
