package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.lovelace.loveWebAdmin.managers.RolePermissionPolicy;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.web.handlers.ApiRolesHandler;
import org.junit.jupiter.api.*;

class RoleDelegationTest {
  SupportApiTest f;
  Map<String, String> headers;

  @BeforeEach
  void setup() throws Exception {
    f = new SupportApiTest();
    f.setup();
    headers = f.session("Delegator", Set.of(Permission.MANAGE_ROLES, Permission.VIEW_PLAYERS));
  }

  @AfterEach
  void stop() {
    f.stop();
  }

  @Test
  void cannotCreatePrivilegesAboveOwnRole() throws Exception {
    var handler = new ApiRolesHandler(f.plugin);
    assertEquals(
        403,
        f.call(
                handler,
                "POST",
                "",
                Map.of(),
                "{\"name\":\"Forbidden\",\"permissions\":[\"EXECUTE_COMMANDS\"]}",
                headers)
            .status);
    assertTrue(f.plugin.getDatabaseManager().getRoleByName("Forbidden").isEmpty());
    assertEquals(
        200,
        f.call(
                handler,
                "POST",
                "",
                Map.of(),
                "{\"name\":\"Reader2\",\"permissions\":[\"VIEW_PLAYERS\"]}",
                headers)
            .status);
  }

  @Test
  void ownRoleAndHigherRolesCannotBeEdited() throws Exception {
    var role = f.plugin.getDatabaseManager().getRoleByName("Delegator").orElseThrow();
    assertEquals(
        403,
        f.call(
                new ApiRolesHandler(f.plugin),
                "PUT",
                "/" + role.id(),
                Map.of(),
                "{\"name\":\"Self\",\"permissions\":[\"VIEW_PLAYERS\"]}",
                headers)
            .status);
    var higher =
        f.plugin.getRoleManager().createRole("Higher", "", Set.of(Permission.EXECUTE_COMMANDS));
    assertEquals(
        403,
        f.call(new ApiRolesHandler(f.plugin), "DELETE", "/" + higher.id(), Map.of(), null, headers)
            .status);
  }

  @Test
  void delegationNeverGrantsAnExternalGroupOrOwner() {
    var target =
        f.plugin
            .getRoleManager()
            .createRole("External", "administrators", Set.of(Permission.VIEW_PLAYERS));
    assertFalse(RolePermissionPolicy.canDelegate(f.plugin, "Delegator", target));
    assertFalse(RolePermissionPolicy.canDelegate(f.plugin, "Unknown", target));
  }

  @Test
  void removingPrivilegesInvalidatesExistingSessionAuthority() throws Exception {
    var role = f.plugin.getDatabaseManager().getRoleByName("Delegator").orElseThrow();
    f.plugin.getRoleManager().updateRole(role.id(), "", Set.of(Permission.VIEW_PLAYERS));
    assertEquals(
        403,
        f.call(
                new ApiRolesHandler(f.plugin),
                "POST",
                "",
                Map.of(),
                "{\"name\":\"AfterRevoke\",\"permissions\":[\"VIEW_PLAYERS\"]}",
                headers)
            .status);
  }

  @Test
  void metadataEditDoesNotErasePermissionsAndInvalidNamesAreRejected() throws Exception {
    var target =
        f.plugin.getRoleManager().createRole("Target", "", Set.of(Permission.VIEW_PLAYERS));
    assertEquals(
        200,
        f.call(
                new ApiRolesHandler(f.plugin),
                "PUT",
                "/" + target.id(),
                Map.of(),
                "{\"name\":\"Renamed\"}",
                headers)
            .status);
    assertEquals(
        Set.of(Permission.VIEW_PLAYERS),
        f.plugin.getDatabaseManager().getRoleById(target.id()).orElseThrow().permissions());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            f.call(
                new ApiRolesHandler(f.plugin),
                "PUT",
                "/" + target.id(),
                Map.of(),
                "{\"permissions\":[\"INVALID_PERMISSION\"]}",
                headers));
    assertEquals(
        Set.of(Permission.VIEW_PLAYERS),
        f.plugin.getDatabaseManager().getRoleById(target.id()).orElseThrow().permissions());
  }

  @Test
  void gameAccountSuggestionsExposeOnlyNamesWithoutPlayerViewPermission() throws Exception {
    var accountHeaders = f.session("AccountStaff", Set.of(Permission.MANAGE_LOVEAUTH));
    f.plugin
        .getDatabaseManager()
        .recordPlayerLogin(UUID.randomUUID().toString(), "Ada123", "127.0.0.1");
    var result =
        f.call(
            new me.lovelace.loveWebAdmin.web.handlers.ApiPlayersHandler(f.plugin),
            "GET",
            "/search",
            Map.of("q", "Ada", "limit", "8"),
            null,
            accountHeaders);
    assertEquals(200, result.status);
    assertTrue(result.body.toString().contains("Ada123"));
    assertFalse(result.body.toString().contains("lastSeenAt"));
    assertEquals(
        403,
        f.call(
                new me.lovelace.loveWebAdmin.web.handlers.ApiPlayersHandler(f.plugin),
                "GET",
                "/Ada123",
                Map.of(),
                null,
                accountHeaders)
            .status);
    assertEquals(
        400,
        f.call(
                new me.lovelace.loveWebAdmin.web.handlers.ApiPlayersHandler(f.plugin),
                "GET",
                "/search",
                Map.of("limit", "-1"),
                null,
                headers)
            .status);
  }

  @Test
  void newDependenciesAreTransitiveButLegacyRolesDoNotExpand() {
    assertTrue(
        RolePermissionPolicy.withNewDependencies(
                Set.of(Permission.VIEW_VESUVIO_ADVANCED, Permission.MANAGE_VESUVIO_MODELS),
                Set.of(Permission.VIEW_VESUVIO_ADVANCED))
            .contains(Permission.VIEW_VESUVIO));
    var legacy = Set.of(Permission.MANAGE_VESUVIO_MODELS);
    assertEquals(legacy, RolePermissionPolicy.withNewDependencies(legacy, legacy));
    assertTrue(
        RolePermissionPolicy.withNewDependencies(legacy, Set.of())
            .containsAll(Set.of(Permission.VIEW_VESUVIO, Permission.VIEW_VESUVIO_ADVANCED)));
    assertTrue(
        RolePermissionPolicy.withNewDependencies(
                Set.of(Permission.MANAGE_REPORTS),
                Set.of(Permission.MANAGE_REPORTS, Permission.VIEW_REPORTS))
            .contains(Permission.VIEW_REPORTS));
  }
}
