package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.web.handlers.*;
import org.junit.jupiter.api.*;

class PanelRefinementApiTest {
  SupportApiTest f;

  @BeforeEach
  void setup() throws Exception {
    f = new SupportApiTest();
    f.setup();
  }

  @AfterEach
  void close() {
    f.stop();
  }

  @Test
  void searchRespectsCasePermissionsAndFindsSupport() throws Exception {
    var read = f.session("SearchStaff", Set.of(Permission.VIEW_REPORTS));
    var result =
        f.call(new ApiSearchHandler(f.plugin), "GET", "", Map.of("q", "Первое"), null, read);
    assertEquals(200, result.status);
    assertTrue(result.body.toString().contains("support"));
    assertFalse(result.body.toString().contains("\"kind\":\"appeal\""));
    var forbidden =
        f.call(
            new ApiSearchHandler(f.plugin), "GET", "", Map.of("q", "Первое"), null, f.appealsOnly);
    assertFalse(forbidden.body.toString().contains("Первое обращение"));
  }

  @Test
  void bansArePagedAndFilteredInSqlWithoutFullHistoryOrPrivateAddresses() throws Exception {
    var db = f.plugin.getDatabaseManager();
    for (int i = 0; i < 31; i++)
      db.saveBan(
          new WebBan(
              0,
              "Ada" + i,
              UUID.randomUUID().toString(),
              "127.1.2.3",
              i % 2 == 0 ? "Alice" : "Bob",
              "Проверка",
              "",
              "[]",
              false,
              "ACTIVE",
              -1,
              100 + i,
              -1));
    var page = db.searchBans("ACTIVE", "Ada", "", 0, 1000, 25, 25);
    assertEquals(31, page.total());
    assertEquals(6, page.items().size());
    assertEquals(3, db.searchBans("ACTIVE", "Ada", "Alice", 100, 104, 0, 25).total());
    var headers = f.session("BanReader", Set.of(Permission.VIEW_BANS));
    var response =
        f.call(
            new ApiBansHandler(f.plugin),
            "GET",
            "/list",
            Map.of("status", "ACTIVE", "offset", "25"),
            null,
            headers);
    assertEquals(200, response.status);
    assertFalse(response.body.toString().contains("127.1.2.3"));
    assertFalse(response.body.toString().contains("proofUrls"));
  }

  @Test
  void checkModelsAreOwnerOnlyEvenWithAdvancedAnticheatPermission() throws Exception {
    var headers =
        f.session(
            "Telemetry",
            Set.of(Permission.VIEW_VESUVIO_ADVANCED, Permission.MANAGE_VESUVIO_MODELS));
    assertEquals(
        403,
        f.call(new ApiVesuvioHandler(f.plugin), "GET", "/engine", Map.of(), null, headers).status);
    assertEquals(
        403,
        f.call(new ApiVesuvioHandler(f.plugin), "POST", "/models/reload", Map.of(), "{}", headers)
            .status);
  }

  @Test
  void trustBreakdownExplainsTheActualFiftyThreeScore() throws Exception {
    var manager = f.plugin.getPlayerProfileManager();
    var method =
        manager
            .getClass()
            .getDeclaredMethod(
                "computeTrustScore",
                Map.class,
                double.class,
                double.class,
                boolean.class,
                int.class,
                long.class,
                long.class);
    method.setAccessible(true);
    var value =
        (me.lovelace.loveWebAdmin.managers.PlayerProfileManager.TrustScore)
            method.invoke(manager, Map.of(), 42.5, 0.0, false, 0, 0L, 0L);
    assertEquals(53, value.score());
    assertTrue(value.factors().stream().anyMatch(line -> line.contains("17.0")));
  }
}
