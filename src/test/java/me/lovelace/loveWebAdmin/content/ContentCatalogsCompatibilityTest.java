package me.lovelace.loveWebAdmin.content;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

class ContentCatalogsCompatibilityTest {
  LoveWebAdmin plugin;
  org.bukkit.plugin.java.JavaPlugin essential;
  ContentCatalogs catalogs;
  Path file;
  @BeforeEach void setup() throws Exception {
    MockBukkit.mock();MockBukkit.createMockPlugin("LoveCore");
    essential=MockBukkit.createMockPlugin("LoveEssential");
    var config=new YamlConfiguration();config.set("web.enabled",false);
    plugin=MockBukkit.loadWithConfig(LoveWebAdmin.class,config);
    file=essential.getDataFolder().toPath().resolve("modules/achievements/achievements.yml");
    Files.createDirectories(file.getParent());
    Files.writeString(file,"catalog:\n  helper:\n    name: Помощник\n    trigger:\n      type: manual\n");
    catalogs=new ContentCatalogs(plugin);
  }
  @AfterEach void stop(){MockBukkit.unmock();}
  @Test void oldOwnersStillExposeTheirRecordsButPublicationCannotWriteThem() throws Exception {
    var snapshot=catalogs.snapshot();
    assertEquals(false,ContentSchema.map(snapshot.get("available")).get("achievements"));
    var diagnostic=ContentSchema.map(ContentSchema.map(snapshot.get("availability")).get("achievements"));
    assertEquals("read_only",diagnostic.get("state"));
    assertEquals("LoveEssential",diagnostic.get("owner"));
    var all=ContentSchema.map(snapshot.get("catalogs"));
    var achievements=ContentSchema.map(all.get("achievements"));
    assertTrue(achievements.containsKey("helper"));
    String original=Files.readString(file);
    achievements.put("new",Map.of("name","Новое","trigger",Map.of("type","manual")));
    all.put("achievements",achievements);
    assertTrue(assertThrows(IllegalArgumentException.class,()->catalogs.publish(Map.of("revision",snapshot.get("revision"),"catalogs",all),"Owner")).getMessage().contains("LoveEssential"));
    assertEquals(original,Files.readString(file));
    assertTrue(catalogs.history().isEmpty());
  }
  @Test void missingFileAndDisabledOwnerHaveDifferentDiagnostics() throws Exception {
    Files.delete(file);
    var availability=ContentSchema.map(catalogs.snapshot().get("availability"));
    assertEquals("missing_file",ContentSchema.map(availability.get("achievements")).get("state"));
    MockBukkit.getMock().getPluginManager().disablePlugin(essential);
    availability=ContentSchema.map(catalogs.snapshot().get("availability"));
    assertEquals("disabled",ContentSchema.map(availability.get("achievements")).get("state"));
    assertEquals("missing",ContentSchema.map(availability.get("marks")).get("state"));
  }
}
