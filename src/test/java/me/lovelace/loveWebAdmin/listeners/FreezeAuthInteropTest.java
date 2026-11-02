package me.lovelace.loveWebAdmin.listeners;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.UUID;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.managers.FreezeManager;
import org.bukkit.event.player.*;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

class FreezeAuthInteropTest {
  public static class Web extends LoveWebAdmin {
    @Override
    public void onEnable() {}

    @Override
    public void onDisable() {}

    @Override
    public FreezeManager getFreezeManager() {
      return new FreezeManager(this) {
        @Override
        public boolean isFrozen(UUID id) {
          return true;
        }
      };
    }
  }

  public static class Auth extends JavaPlugin {
    boolean authenticated, bypass;

    public Auth getAuthManager() {
      return this;
    }

    public Auth getLimboManager() {
      return this;
    }

    public boolean isAuthenticated(UUID id) {
      return authenticated;
    }

    public boolean isTeleportBypassed(UUID id) {
      return bypass;
    }
  }

  ServerMock server;
  Web web;
  Auth auth;
  PlayerFreezeListener listener;

  @BeforeEach
  void setup() throws Exception {
    server = MockBukkit.mock();
    web =
        MockBukkit.loadWith(
            Web.class,
            new PluginDescriptionFile(
                new StringReader(
                    "name: LoveWebAdmin\nversion: 1\nmain: " + Web.class.getName() + "\n")));
    auth =
        MockBukkit.loadWith(
            Auth.class,
            new PluginDescriptionFile(
                new StringReader(
                    "name: LoveAuth\nversion: 1\nmain: " + Auth.class.getName() + "\n")));
    listener = new PlayerFreezeListener(web);
  }

  @AfterEach
  void cleanup() {
    MockBukkit.unmock();
  }

  @Test
  void passwordLoginIsNotBlockedButAuthenticatedCommandsRemainRestricted() {
    var player = server.addPlayer();
    var login = new PlayerCommandPreprocessEvent(player, "/login password");
    listener.onCommand(login);
    assertFalse(login.isCancelled());
    auth.authenticated = true;
    var home = new PlayerCommandPreprocessEvent(player, "/le home");
    listener.onCommand(home);
    assertTrue(home.isCancelled());
  }

  @Test
  void onlyTeleportIssuedInsideAuthLimboGuardBypassesFreeze() {
    var player = server.addPlayer();
    auth.authenticated = true;
    var event =
        new PlayerTeleportEvent(
            player,
            player.getLocation(),
            player.getLocation().add(10, 0, 0),
            PlayerTeleportEvent.TeleportCause.PLUGIN);
    listener.onTeleport(event);
    assertTrue(event.isCancelled());
    event.setCancelled(false);
    auth.bypass = true;
    listener.onTeleport(event);
    assertFalse(event.isCancelled());
  }
}
