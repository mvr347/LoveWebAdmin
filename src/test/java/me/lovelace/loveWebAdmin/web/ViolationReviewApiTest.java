package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import me.lovelace.loveWebAdmin.integration.VesuvioBridge;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.web.handlers.ApiVesuvioHandler;
import org.junit.jupiter.api.*;

class ViolationReviewApiTest {
  SupportApiTest fixture;
  Provider provider;
  ApiVesuvioHandler handler;
  Map<String, String> reviewer;

  @BeforeEach
  void setup() throws Exception {
    fixture = new SupportApiTest();
    fixture.setup();
    provider = new Provider();
    handler = new ApiVesuvioHandler(fixture.plugin);
    var bridge = new VesuvioBridge();
    var type = Class.forName(VesuvioBridge.class.getName() + "$Binding");
    var constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    var binding = VesuvioBridge.class.getDeclaredField("binding");
    binding.setAccessible(true);
    binding.set(bridge, constructor.newInstance(provider, Provider.class));
    var field = ApiVesuvioHandler.class.getDeclaredField("bridge");
    field.setAccessible(true);
    field.set(handler, bridge);
    reviewer =
        fixture.session(
            "ReviewStaff", Set.of(Permission.MANAGE_VESUVIO, Permission.VIEW_VESUVIO_ADVANCED));
  }

  @AfterEach
  void close() {
    fixture.stop();
  }

  @Test
  void readsDurableIdsAndDecisionsFromCapableProvider() throws Exception {
    var result = fixture.call(handler, "GET", "/violations", Map.of(), null, reviewer);
    assertEquals(200, result.status);
    assertTrue(result.body.toString().contains("\"id\":15"));
    assertTrue(result.body.toString().contains("legit"));
    assertEquals(0, provider.legacyReads);
    provider.legacy = true;
    result = fixture.call(handler, "GET", "/violations", Map.of(), null, reviewer);
    assertEquals(200, result.status);
    assertEquals(1, provider.legacyReads);
  }

  @Test
  void managementCanReviewWithoutOwnerAndReviewerCannotBeSpoofed() throws Exception {
    var result =
        fixture.call(
            handler,
            "POST",
            "/violation/15/review",
            Map.of(),
            "{\"verdict\":\"legit\",\"revision\":0,\"reason\":\"Обычная"
                + " ходьба\",\"reviewer\":\"Spoofed\"}",
            reviewer);
    assertEquals(200, result.status);
    assertEquals("ReviewStaff", provider.reviewer);
    assertEquals("legit", provider.verdict);
    assertEquals(
        409,
        fixture.call(
                handler,
                "POST",
                "/violation/15/review",
                Map.of(),
                "{\"verdict\":\"cheat\",\"revision\":1,\"reason\":\"Устаревший экран\"}",
                reviewer)
            .status);
  }

  @Test
  void viewPermissionDoesNotAllowReviewAndInvalidInputsNeverReachProvider() throws Exception {
    var viewer = fixture.session("ViewOnly", Set.of(Permission.VIEW_VESUVIO_ADVANCED));
    assertEquals(
        403, fixture.call(handler, "POST", "/violation/15/review", Map.of(), "{}", viewer).status);
    assertEquals(
        400,
        fixture.call(
                handler,
                "POST",
                "/violation/15/review",
                Map.of(),
                "{\"verdict\":\"legit\",\"revision\":0.5,\"reason\":\"Не целая версия\"}",
                reviewer)
            .status);
    assertNull(provider.reviewer);
  }

  @Test
  void mlSamplesUseTheExistingTrainingApiWithoutExposingModels() throws Exception {
    assertEquals(
        200,
        fixture.call(
                handler,
                "POST",
                "/sample/S123/review",
                Map.of(),
                "{\"verdict\":\"legit\"}",
                reviewer)
            .status);
    assertEquals("S123", provider.sample);
    assertEquals("ReviewStaff", provider.reviewer);
    assertEquals(403, fixture.call(handler, "GET", "/engine", Map.of(), null, reviewer).status);
  }

  public static class Provider {
    boolean legacy;
    int legacyReads;
    String reviewer, verdict, sample;

    public Set<String> getCapabilities() {
      return legacy ? Set.of("violations") : Set.of("violation-reviews");
    }

    public List<Map<String, Object>> getReviewableViolations(int limit) {
      return List.of(
          Map.of("id", 15, "name", "Walker", "timestamp", 1794220000000L, "verdict", "legit"));
    }

    public List<Map<String, Object>> getRecentViolations(int limit) {
      legacyReads++;
      return List.of(Map.of("name", "Walker", "timestamp", 1794220000000L));
    }

    public CompletableFuture<Boolean> submitViolationReview(
        long id, String verdict, String reviewer, String reason, long revision) {
      this.reviewer = reviewer;
      this.verdict = verdict;
      return CompletableFuture.completedFuture(revision == 0);
    }

    public CompletableFuture<Boolean> submitReview(String sample, String verdict, String reviewer) {
      this.sample = sample;
      this.reviewer = reviewer;
      return CompletableFuture.completedFuture(true);
    }
  }
}
