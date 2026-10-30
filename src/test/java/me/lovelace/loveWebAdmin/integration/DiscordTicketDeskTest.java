package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;
import dev.lovelace.lovecore.api.discord.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class DiscordTicketDeskTest {
  LoveWebAdmin plugin;
  DiscordTicketDesk desk;
  FakeDiscord discord;

  @BeforeEach
  void setup() {
    MockBukkit.mock();
    MockBukkit.createMockPlugin("LoveCore");
    MockBukkit.createMockPlugin("LoveEssential");
    var config = new YamlConfiguration();
    config.set("web.enabled", false);
    config.set("web.host", "127.0.0.1");
    config.set("discord.tickets.panel-channel-id", "70000");
    config.set("discord.tickets.queue-channel-id", "80000");
    config.set("discord.tickets.supervisor-role-ids", List.of("60000"));
    plugin = MockBukkit.loadWithConfig(LoveWebAdmin.class, config);
    discord = new FakeDiscord();
    desk = plugin.getDiscordBridge().ticketDesk();
    desk.attach(discord.service, new DiscordActions(plugin, discord.service));
    player("10000", "Reporter", false);
    player("20000", "Moderator", true);
    player("30000", "Other", true);
    player("40000", "Issuer", true);
    player("50000", "Chief", true);
    player("90000", "Griefer", false);
  }

  @AfterEach
  void close() {
    MockBukkit.unmock();
  }

  void player(String discordId, String name, boolean staff) {
    UUID uuid = UUID.randomUUID();
    discord.links.put(discordId, uuid);
    plugin.getDatabaseManager().recordPlayerLogin(uuid.toString(), name, "127.0.0.1");
    if (staff) {
      var role =
          plugin
              .getRoleManager()
              .createRole(
                  name,
                  "",
                  Set.of(
                      Permission.MANAGE_REPORTS,
                      Permission.VIEW_REPORTS,
                      Permission.MANAGE_APPEALS,
                      Permission.VIEW_APPEALS,
                      Permission.MANAGE_BANS));
      plugin.getDatabaseManager().saveAdmin(new WebAdmin(0, name, "hash", role.id(), 100, 0));
    }
  }

  static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertTrue(condition.getAsBoolean());
  }

  Map<String, Object> click(String user, String channel, String name, Map<String, String> values)
      throws Exception {
    String id = "1000000000000" + discord.ids.incrementAndGet();
    var result = new CompletableFuture<Map<String, Object>>();
    discord.responses.put(id, result);
    var fields = new HashMap<>(values);
    fields.put("_channelId", channel);
    assertTrue(
        desk.onInteraction(
            new DiscordInteraction(
                id,
                "11111",
                "unused",
                user,
                "99999",
                name,
                name.contains(":draft:") || name.contains(":submit:") ? 5 : 3,
                fields)));
    return result.get(8, TimeUnit.SECONDS);
  }

  @SuppressWarnings("unchecked")
  String confirm(Map<String, Object> preview) {
    return ((Map<String, Object>)
            ((List<Map<String, Object>>)
                    ((List<Map<String, Object>>) preview.get("components"))
                        .getFirst()
                        .get("components"))
                .getFirst())
        .get("custom_id")
        .toString();
  }

  int createReport() throws Exception {
    var preview =
        click(
            "10000",
            "70000",
            "lwa:desk:draft:report",
            Map.of("target", "Griefer", "reason", "Подробное описание нарушения"));
    String submit = confirm(preview);
    var wrong = click("90000", "70000", submit, Map.of());
    assertTrue(wrong.get("content").toString().contains("другому"));
    assertTrue(plugin.getDatabaseManager().getAllReports(null, null, 200, 0).isEmpty());
    click("10000", "70000", submit, Map.of());
    click("10000", "70000", submit, Map.of());
    var reports = plugin.getDatabaseManager().getAllReports(null, null, 200, 0);
    assertEquals(1, reports.size());
    int id = reports.getFirst().id();
    desk.create("report", id, "10000").get(8, TimeUnit.SECONDS);
    return id;
  }

  @Test
  void explicitPermissionFailureCanBeRetriedAfterFixingRights() throws Exception {
    discord.publicationFailure =
        new dev.lovelace.lovecore.api.discord.DiscordRequestRejectedException(403);
    assertThrows(Exception.class, () -> desk.publishPanel());
    assertEquals("FAILED", desk.panelStatus().get("state"));
    assertTrue(desk.panelStatus().get("explanation").toString().contains("403"));
    discord.publicationFailure = null;
    desk.publishPanel();
    assertEquals("READY", desk.panelStatus().get("state"));
    assertEquals(1, discord.panelCreates.get());
  }

  @Test
  void lostPublicationResponseRequiresReconciliationAndNeverCreatesAgain() throws Exception {
    discord.publicationFailure = new java.io.IOException("lost response");
    assertThrows(Exception.class, () -> desk.publishPanel());
    int calls = discord.publicationCalls.get();
    discord.publicationFailure = null;
    desk.publishPanel();
    assertEquals(calls, discord.publicationCalls.get());
    assertEquals("UNCERTAIN", desk.panelStatus().get("state"));
  }

  @Test
  void missingPanelChannelHasAnExplicitStatusAndDoesNotSend() throws Exception {
    plugin.getConfig().set("discord.tickets.panel-channel-id", "");
    var other = new DiscordTicketDesk(plugin, plugin.getDiscordBridge().ticketDeskStore());
    other.attach(discord.service, new DiscordActions(plugin, discord.service));
    assertEquals("NOT_CONFIGURED", other.panelStatus().get("state"));
    assertTrue(
        other
            .panelStatus()
            .get("explanation")
            .toString()
            .contains("discord.tickets.panel-channel-id"));
    assertThrows(IllegalArgumentException.class, other::publishPanel);
    assertEquals(0, discord.publicationCalls.get());
    other.close();
  }

  @Test
  void confirmationAssignmentTransferAndWithdrawalUseOnePrivateCase() throws Exception {
    int id = createReport();
    assertEquals(List.of("10000"), discord.lastMembers);
    assertEquals(List.of("60000"), discord.lastRoles);
    assertEquals("mine-ticket-" + id + "-r", discord.created.channelName());
    var denied =
        click(
            "30000",
            "200000",
            "lwa:desk:submit:close:report:" + id,
            Map.of("reason", "Чужое обращение"));
    assertTrue(denied.get("content").toString().contains("Сначала"));
    assertEquals(
        "OPEN",
        plugin.getDiscordBridge().ticketDeskStore().get("report", id).orElseThrow().state());
    click("20000", "80000", "lwa:desk:claim:report:" + id, Map.of());
    assertTrue(discord.lastMembers.contains("20000"));
    assertFalse(discord.lastMembers.contains("30000"));
    var taken = click("30000", "80000", "lwa:desk:claim:report:" + id, Map.of());
    assertTrue(taken.get("content").toString().contains("другому"));
    click(
        "20000",
        "200000",
        "lwa:desk:submit:transfer:report:" + id,
        Map.of("staff", "Other", "reason", "Передаю другому сотруднику"));
    assertTrue(discord.lastMembers.contains("30000"));
    assertFalse(discord.lastMembers.contains("20000"));
    assertFalse(desk.permitsMessage("report", id, "20000"));
    assertTrue(desk.permitsMessage("report", id, "30000"));
    click(
        "10000",
        "200000",
        "lwa:desk:submit:close:report:" + id,
        Map.of("reason", "Я отзываю обращение"));
    assertEquals("CLOSED", plugin.getDatabaseManager().getReportById(id).orElseThrow().status());
    desk.tick();
    await(() -> discord.deleted.get() == 1);
    assertEquals(
        "CLOSED",
        plugin.getDiscordBridge().ticketDeskStore().get("report", id).orElseThrow().state());
    assertEquals(1, plugin.getDiscordBridge().ticketDeskStore().transcript("report", id, 0).size());
  }

  @Test
  void issuerCanExplainButCannotTakeOrDecideOwnAppeal() throws Exception {
    var ban =
        plugin
            .getDatabaseManager()
            .saveBan(
                new WebBan(
                    0,
                    "Reporter",
                    discord.links.get("10000").toString(),
                    null,
                    "Issuer",
                    "Причина наказания",
                    "",
                    "[]",
                    false,
                    "ACTIVE",
                    -1,
                    100,
                    -1));
    plugin.moderation().source(ban.id(), "STAFF", "Issuer", null);
    int id =
        plugin
            .getDatabaseManager()
            .createAppeal(ban.id(), ban.targetUuid(), ban.targetName(), "Прошу проверить решение");
    plugin.moderation().createAccess(id, "10000", true);
    desk.create("appeal", id, "10000").get(8, TimeUnit.SECONDS);
    assertTrue(discord.lastMembers.contains("40000"));
    assertTrue(desk.permitsMessage("appeal", id, "40000"));
    var own = click("40000", "80000", "lwa:desk:claim:appeal:" + id, Map.of());
    assertTrue(own.get("content").toString().contains("Своё наказание"));
    assertEquals("", plugin.moderation().work("appeal", id).get("assignee"));
    click("50000", "80000", "lwa:desk:claim:appeal:" + id, Map.of());
    assertEquals("Chief", plugin.moderation().work("appeal", id).get("assignee"));
    assertTrue(discord.lastMembers.contains("50000"));
  }

  @Test
  void panelIsUpdatedAndPayloadsHaveNoProviderNames() throws Exception {
    desk.tick();
    await(() -> discord.messages.containsKey("70000"));
    var first = discord.messages.get("70000");
    assertEquals(
        3,
        ((List<?>) ((Map<?, ?>) ((List<?>) first.get("components")).getFirst()).get("components"))
            .size());
    var second = new DiscordTicketDesk(plugin, plugin.getDiscordBridge().ticketDeskStore());
    second.attach(discord.service, new DiscordActions(plugin, discord.service));
    second.tick();
    await(() -> discord.panelUpdates.get() >= 2);
    assertEquals(1, discord.panelCreates.get());
    assertTrue(JsonUtils.toJson(first).contains("lwa:desk:new:report"));
    assertTrue(JsonUtils.toJson(first).contains("lwa:desk:new:appeal"));
    assertTrue(JsonUtils.toJson(first).contains("lwa:desk:mine"));
    assertFalse(JsonUtils.toJson(first).contains("lwa:preview:"));
    assertFalse(JsonUtils.toJson(discord.messages).contains("LoveWebAdmin"));
    assertFalse(JsonUtils.toJson(discord.messages).contains("LoveCore"));
    second.close();
    var body = DiscordTicketDesk.body("😀".repeat(1500), List.of());
    String text = body.get("content").toString();
    assertTrue(text.length() <= 2000);
    assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 1)));
  }

  @Test
  void existingOpenTicketKeepsItsAuthorAndBecomesManaged() throws Exception {
    int id =
        plugin
            .getDatabaseManager()
            .createPlayerReport(
                discord.links.get("10000").toString(),
                "Reporter",
                "",
                discord.links.get("90000").toString(),
                "Griefer",
                List.of(),
                "Историческое обращение",
                false,
                0);
    plugin.moderation().ticketOwner("report", id, "10000");
    plugin.moderation().ticketChannel("report", id, "200000");
    var restored = new DiscordTicketDesk(plugin, plugin.getDiscordBridge().ticketDeskStore());
    restored.attach(discord.service, new DiscordActions(plugin, discord.service));
    assertTrue(restored.permitsMessage("report", id, "10000"));
    restored.tick();
    await(() -> discord.lastMembers.contains("10000"));
    assertEquals(List.of("10000"), discord.lastMembers);
    assertEquals(List.of("60000"), discord.lastRoles);
    restored.close();
  }

  @Test
  void lostCreationResponseRecoversExistingChannelWithoutCreatingAnother() throws Exception {
    int id =
        plugin
            .getDatabaseManager()
            .createPlayerReport(
                discord.links.get("10000").toString(),
                "Reporter",
                "",
                discord.links.get("90000").toString(),
                "Griefer",
                List.of(),
                "Потерянный ответ",
                false,
                0);
    plugin.moderation().ticketOwner("report", id, "10000");
    plugin.getDiscordBridge().ticketDeskStore().register("report", id, "10000");
    desk.tick();
    await(
        () -> {
          try {
            return plugin
                .getDiscordBridge()
                .ticketDeskStore()
                .get("report", id)
                .orElseThrow()
                .channel()
                .equals("200000");
          } catch (Exception error) {
            return false;
          }
        });
    assertNull(discord.created);
    assertEquals("200000", plugin.moderation().ticket("report", id).orElseThrow().get("channel"));
    assertEquals(List.of("10000"), discord.lastMembers);
  }

  @Test
  void anAuthorWhoIsAlsoStaffDoesNotImpersonateTheAssignedEmployee() throws Exception {
    int id = createReport();
    var role =
        plugin.getRoleManager().createRole("ReporterStaff", "", Set.of(Permission.VIEW_REPORTS));
    plugin.getDatabaseManager().saveAdmin(new WebAdmin(0, "Reporter", "hash", role.id(), 100, 0));
    new DiscordActions(plugin, discord.service)
        .onTicketMessage(
            new TicketMessage(
                "report:" + id,
                "200000",
                "100000000000000001",
                "10000",
                "Reporter",
                "Отвечаю как автор обращения",
                List.of()));
    var message = plugin.getDatabaseManager().reportDiscussion(id).getLast();
    assertEquals("Reporter", message.get("author"));
    assertEquals(false, message.get("isStaff"));
  }

  @Test
  void knownCooldownRejectionsDoNotPermanentlyConsumeAllDraftSlots() throws Exception {
    int old =
        plugin
            .getDatabaseManager()
            .createPlayerReport(
                discord.links.get("10000").toString(),
                "Reporter",
                "",
                discord.links.get("90000").toString(),
                "Griefer",
                List.of(),
                "Ранее рассмотренная жалоба",
                false,
                0);
    plugin.getDatabaseManager().resolveReport(old, "CLOSED", "Moderator");
    for (int attempt = 0; attempt < 3; attempt++) {
      var preview =
          click(
              "10000",
              "70000",
              "lwa:desk:draft:report",
              Map.of("target", "Griefer", "reason", "Повторная жалоба на игрока"));
      var rejected = click("10000", "70000", confirm(preview), Map.of());
      assertTrue(rejected.get("content").toString().contains("неделе"));
    }
    var allowed =
        click(
            "10000",
            "70000",
            "lwa:desk:draft:report",
            Map.of("target", "Other", "reason", "Другое новое обращение"));
    assertTrue(allowed.get("content").toString().contains("Проверьте обращение"));
  }

  @Test
  void botDecisionRequiresASeparateConfirmationAndShowsTheRealEffect() throws Exception {
    int id = createReport();
    click("20000", "80000", "lwa:desk:claim:report:" + id, Map.of());
    var preview =
        click(
            "20000",
            "200000",
            "lwa:desk:submit:reject:report:" + id,
            Map.of("reason", "Материалов для решения недостаточно"));
    assertTrue(preview.get("content").toString().contains("Предпросмотр решения"));
    assertEquals("PENDING", plugin.getDatabaseManager().getReportById(id).orElseThrow().status());
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) preview.get("components");
    @SuppressWarnings("unchecked")
    var buttons = (List<Map<String, Object>>) rows.getFirst().get("components");
    String confirm = buttons.getFirst().get("custom_id").toString();
    var rejected = click("30000", "200000", confirm, Map.of());
    assertTrue(rejected.get("content").toString().contains("другому"));
    assertEquals("PENDING", plugin.getDatabaseManager().getReportById(id).orElseThrow().status());
    var templates =
        click("20000", "200000", "lwa:flow:templates:CLARIFY:report:" + id + ":0", Map.of());
    assertTrue(templates.get("content").toString().contains("Выберите текст"));
  }

  static class FakeDiscord {
    volatile Throwable publicationFailure;
    final AtomicInteger publicationCalls = new AtomicInteger();
    final AtomicInteger ids = new AtomicInteger(10000),
        deleted = new AtomicInteger(),
        panelCreates = new AtomicInteger(),
        panelUpdates = new AtomicInteger();
    final Map<String, UUID> links = new ConcurrentHashMap<>();
    final Map<String, CompletableFuture<Map<String, Object>>> responses = new ConcurrentHashMap<>();
    final Map<String, Map<String, Object>> messages = new ConcurrentHashMap<>();
    volatile ManagedTicketRequest created;
    volatile List<String> lastMembers = List.of(), lastRoles = List.of();

    @SuppressWarnings("unchecked")
    final DiscordService service =
        (DiscordService)
            Proxy.newProxyInstance(
                DiscordService.class.getClassLoader(),
                new Class[] {DiscordService.class},
                (proxy, method, args) -> {
                  return switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "getDiscordGuildId" -> "99999";
                    case "getLinkedPlayer" -> Optional.ofNullable(links.get(args[0]));
                    case "getLinkedDiscordId" ->
                        links.entrySet().stream()
                            .filter(e -> e.getValue().equals(args[0]))
                            .map(Map.Entry::getKey)
                            .findFirst();
                    case "getMemberRoleIds" ->
                        CompletableFuture.completedFuture(
                            args[0].equals("50000") ? Set.of("60000") : Set.of());
                    case "respond" -> {
                      var data = JsonUtils.parseObject(args[1].toString());
                      if (((Number) data.get("type")).intValue() != 5)
                        responses
                            .get(((DiscordInteraction) args[0]).id())
                            .complete((Map<String, Object>) data.get("data"));
                      yield CompletableFuture.completedFuture(true);
                    }
                    case "editInteractionResponse" -> {
                      responses
                          .get(((DiscordInteraction) args[0]).id())
                          .complete(JsonUtils.parseObject(args[1].toString()));
                      yield CompletableFuture.completedFuture(true);
                    }
                    case "recoverManagedTicket" -> CompletableFuture.completedFuture("200000");
                    case "createManagedTicket" -> {
                      created = (ManagedTicketRequest) args[0];
                      lastMembers = created.memberIds();
                      lastRoles = created.supervisorRoleIds();
                      yield CompletableFuture.completedFuture("200000");
                    }
                    case "updateTicketAccess" -> {
                      lastRoles = List.copyOf((List<String>) args[1]);
                      lastMembers = List.copyOf((List<String>) args[2]);
                      yield CompletableFuture.completedFuture(true);
                    }
                    case "ensureMessage" -> {
                      publicationCalls.incrementAndGet();
                      if (publicationFailure != null)
                        yield CompletableFuture.failedFuture(publicationFailure);
                      messages.put(args[0].toString(), JsonUtils.parseObject(args[2].toString()));
                      if (args[0].equals("70000")) {
                        panelUpdates.incrementAndGet();
                        if (args[1] == null) panelCreates.incrementAndGet();
                      }
                      yield CompletableFuture.completedFuture(
                          args[1] == null
                              ? String.valueOf(ids.incrementAndGet())
                              : args[1].toString());
                    }
                    case "setTicketReadOnly" -> CompletableFuture.completedFuture(true);
                    case "getTicketHistory" ->
                        CompletableFuture.completedFuture(
                            List.of(
                                new TicketMessage(
                                    "report:1",
                                    "200000",
                                    "100000000000000000",
                                    "10000",
                                    "Игрок",
                                    "Переписка сохранена",
                                    List.of())));
                    case "deleteTicketChannel" -> {
                      deleted.incrementAndGet();
                      yield CompletableFuture.completedFuture(true);
                    }
                    default -> {
                      if (CompletableFuture.class.isAssignableFrom(method.getReturnType()))
                        yield CompletableFuture.completedFuture(null);
                      yield null;
                    }
                  };
                });
  }
}
