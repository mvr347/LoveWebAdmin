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
    config.set("discord.tickets.support.enabled", true);
    config.set(
        "discord.tickets.support.topics",
        List.of(
            Map.of("id", "question", "label", "Вопрос по игре", "description", "Как что-то работает"),
            Map.of("id", "bug", "label", "Ошибка / баг", "description", "Что-то сломалось"),
            Map.of("id", "donate", "label", "Донат", "description", "Покупки"),
            Map.of("id", "other", "label", "Другое", "description", "Ничего не подходит")));
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
                channel.equals("dm")?"":"99999",
                name,
                name.contains(":draft:") || name.contains(":submit:") ? 5 : 3,
                fields)));
    return result.get(8, TimeUnit.SECONDS);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"lwa:desk:mine", "мои-обращения"})
  void myCasesAlwaysOpensTheOwnerPrivateMenu(String action) throws Exception {
    var result = click("10000", "70000", action, Map.of());
    assertEquals("10000", discord.privateMenuOwner);
    assertEquals("appeals", discord.privateMenuSection);
    assertEquals(1, discord.privateMenuCalls.get());
    assertTrue(result.get("content").toString().contains("открыты в личных"));
    assertTrue(((List<?>) result.get("components")).isEmpty());
    assertEquals(
        64,
        ((Number) ((Map<?, ?>) discord.lastAcknowledgement.get("data")).get("flags")).intValue());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void blockedDirectMessagesNeverClaimTheMenuWasDelivered(boolean exceptional) throws Exception {
    discord.privateMenuResult =
        exceptional
            ? CompletableFuture.failedFuture(new IllegalStateException("transport"))
            : CompletableFuture.completedFuture(false);
    var result = click("10000", "70000", "lwa:desk:mine", Map.of());
    assertTrue(result.get("content").toString().contains("Разрешите сообщения"));
    assertFalse(result.get("content").toString().contains("открыты в личных"));
    assertTrue(((List<?>) result.get("components")).isEmpty());
  }

  @Test
  void myCasesDoesNotSendAnythingAfterARejectedAcknowledgement() {
    discord.acknowledgementAccepted = false;
    assertTrue(
        desk.onInteraction(
            new DiscordInteraction(
                "ignored",
                "11111",
                "unused",
                "10000",
                "99999",
                "lwa:desk:mine",
                3,
                Map.of("_channelId", "70000"))));
    assertEquals(0, discord.privateMenuCalls.get());
  }

  @Test
  void myCasesWaitsForDiscordBeforeConfirmingDelivery() throws Exception {
    discord.privateMenuResult = new CompletableFuture<>();
    var reply = new CompletableFuture<Map<String, Object>>();
    discord.responses.put("pending", reply);
    assertTrue(
        desk.onInteraction(
            new DiscordInteraction(
                "pending",
                "11111",
                "unused",
                "10000",
                "99999",
                "lwa:desk:mine",
                3,
                Map.of("_channelId", "70000"))));
    await(() -> discord.privateMenuCalls.get() == 1);
    assertFalse(reply.isDone());
    discord.privateMenuResult.complete(true);
    assertTrue(
        reply.get(8, TimeUnit.SECONDS).get("content").toString().contains("открыты в личных"));
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
    int id=savedReport();desk.create("report",id,"10000").get(8,TimeUnit.SECONDS);return id;
  }

  int savedReport() throws Exception {
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
    return id;
  }

  @Test
  void rejectedChannelCreationRetainsTheCaseAndCanBeRetried() throws Exception {
    int id=savedReport();discord.creationFailure=new DiscordRequestRejectedException(403);
    assertThrows(Exception.class,()->desk.create("report",id,"10000").get(8,TimeUnit.SECONDS));
    assertFalse(desk.store().creationStarted("report",id));
    assertTrue(desk.store().creationError("report",id).contains("403"));
    assertTrue(plugin.getDatabaseManager().getReportById(id).isPresent());
    discord.creationFailure=null;
    assertEquals("200000",desk.create("report",id,"10000").get(8,TimeUnit.SECONDS));
    assertEquals(2,discord.creationCalls.get());assertEquals("",desk.store().creationError("report",id));
  }

  @Test
  void unknownCreationCannotPostAgainAndCanRecoverAnExistingChannel() throws Exception {
    int id=savedReport();discord.creationFailure=new java.io.IOException("lost response");discord.recoveredChannel=null;
    assertThrows(Exception.class,()->desk.create("report",id,"10000").get(8,TimeUnit.SECONDS));
    assertThrows(Exception.class,()->desk.create("report",id,"10000").get(8,TimeUnit.SECONDS));
    assertEquals(1,discord.creationCalls.get());assertTrue(desk.store().creationStarted("report",id));
    discord.recoveryFailure=new DiscordRequestRejectedException(403);
    assertThrows(Exception.class,()->desk.create("report",id,"10000").get(8,TimeUnit.SECONDS));
    assertTrue(desk.store().creationStarted("report",id));
    assertEquals(1,discord.creationCalls.get());
    discord.recoveryFailure=null;
    discord.creationFailure=null;discord.recoveredChannel="200000";
    assertEquals("200000",desk.create("report",id,"10000").get(8,TimeUnit.SECONDS));assertEquals(1,discord.creationCalls.get());
  }

  String ticketState(int id) {
    try {
      return desk.store().get("report", id).orElseThrow().state();
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  String ticketChannel(int id) {
    try {
      return desk.store().get("report", id).orElseThrow().channel();
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  @Test
  void channelDeletedOutsideTheBotStillClosesTheCase() throws Exception {
    int id = createReport();
    discord.readOnlyFailure = new DiscordRequestRejectedException(404);
    click("10000", "200000", "lwa:desk:submit:close:report:" + id, Map.of("reason", "Канал удалён вручную"));
    desk.tick();
    await(() -> ticketState(id).equals("CLOSED"));
  }

  /** A desk without the per-ticket retry back-off of an earlier failed tick. */
  DiscordTicketDesk freshDesk() {
    var fresh = new DiscordTicketDesk(plugin, plugin.getDiscordBridge().ticketDeskStore());
    fresh.attach(discord.service, new DiscordActions(plugin, discord.service));
    return fresh;
  }

  @Test
  void lostCreationIsReconciledWithoutStaffOnlyWhenCoreIsNotStillCreating() throws Exception {
    int id = savedReport();
    discord.creationFailure = new java.io.IOException("lost response");
    discord.recoveredChannel = null;
    assertThrows(Exception.class, () -> desk.create("report", id, "10000").get(8, TimeUnit.SECONDS));
    assertTrue(desk.store().creationStarted("report", id));
    // Too early: the late answer could still arrive, so the case stays blocked.
    desk.tick();
    Thread.sleep(300);
    assertEquals(1, discord.creationCalls.get());
    // Past the window but the core still has the request in flight: stay blocked.
    var waiting = freshDesk();
    waiting.autoReconcileMs = 0;
    discord.creationPending = true;
    waiting.tick();
    Thread.sleep(300);
    assertEquals(1, discord.creationCalls.get());
    // Past the window, nothing in flight, lookup finds nothing: the channel is created again.
    var reconciling = freshDesk();
    reconciling.autoReconcileMs = 0;
    discord.creationPending = false;
    discord.creationFailure = null;
    reconciling.tick();
    await(() -> discord.creationCalls.get() == 2);
    await(() -> !ticketChannel(id).isBlank());
  }

  @Test
  void supportPanelHasFourButtonsOnlyWhenSupportIsOffered() {
    int offered = buttonCount(DiscordPanelMessages.body("lwa:desk:", false, true));
    int withheld = buttonCount(DiscordPanelMessages.body("lwa:desk:", false, false));
    int preview = buttonCount(DiscordPanelMessages.body("test:", true, true));
    assertEquals(4, offered);
    assertEquals(3, withheld);
    assertEquals(3, preview, "the test panel never offers support");
  }

  @SuppressWarnings("unchecked")
  static int buttonCount(Map<String, Object> panel) {
    return ((List<?>)
            ((Map<String, Object>) ((List<?>) panel.get("components")).getFirst()).get("components"))
        .size();
  }

  @Test
  void supportAsksForATopicThenOnlyForTheDescriptionOfALinkedPlayer() throws Exception {
    var picker = click("10000", "70000", "lwa:desk:new:support", Map.of());
    String json = JsonUtils.toJson(picker);
    assertTrue(json.contains("lwa:desk:support:topic"));
    for (String topic : List.of("question", "bug", "donate", "other")) assertTrue(json.contains(topic));

    var linked = click("10000", "70000", "lwa:desk:support:topic", Map.of("selected", "bug"));
    assertEquals("lwa:desk:sdraft:bug", linked.get("custom_id"));
    String linkedFields = JsonUtils.toJson(linked);
    assertTrue(linkedFields.contains("\"reason\""));
    assertFalse(linkedFields.contains("\"nick\""), "a linked account already knows the nick");

    var unlinked = click("77777", "70000", "lwa:desk:support:topic", Map.of("selected", "bug"));
    assertTrue(JsonUtils.toJson(unlinked).contains("\"nick\""));

    var gone = click("10000", "70000", "lwa:desk:support:topic", Map.of("selected", "missing"));
    assertTrue(gone.get("content").toString().contains("недоступна"));
  }

  @Test
  void supportRequestGetsItsOwnChannelAndNeverOffersVerdicts() throws Exception {
    var modal = click("10000", "70000", "lwa:desk:support:topic", Map.of("selected", "bug"));
    var preview =
        click("10000", "70000", (String) modal.get("custom_id"), Map.of("reason", "Не работает магазин на спавне"));
    assertTrue(preview.get("content").toString().contains("Ошибка / баг"));
    var saved = click("10000", "70000", confirm(preview), Map.of());
    assertTrue(saved.get("content").toString().contains("сохранена"), saved.toString());
    await(() -> discord.creationCalls.get() == 1);
    assertEquals("mine-ticket-1", discord.created.channelName());
    assertEquals(TicketType.SUPPORT, discord.created.routing().type());
    assertEquals("support:1", discord.created.routing().ticketId());
    assertEquals(List.of("10000"), discord.lastMembers);
    await(() -> ticketChannel("support", 1).equals("200000"));
    assertEquals("OPEN", plugin.moderation().support(1).orElseThrow().get("status"));

    desk.tick();
    await(() -> discord.messages.containsKey("200000"));
    String card = JsonUtils.toJson(discord.messages.get("200000"));
    assertTrue(card.contains("Поддержка №1"));
    assertTrue(card.contains("Ошибка / баг"));
    assertTrue(card.contains("lwa:desk:panel:support:1"));
    assertFalse(card.contains("approve") || card.contains("reject"));

    var panel = JsonUtils.toJson(click("20000", "200000", "lwa:desk:panel:support:1", Map.of()));
    assertTrue(panel.contains("lwa:desk:claim:support:1"));
    assertTrue(panel.contains("lwa:desk:form:close:support:1"));
    for (String hidden : List.of("form:approve", "form:reject", "form:reduce", "lwa:flow:templates"))
      assertFalse(panel.contains(hidden), hidden);

    click("20000", "200000", "lwa:desk:claim:support:1", Map.of());
    assertEquals("Moderator", plugin.moderation().work("support", 1).get("assignee"));
    var verdict =
        click("20000", "200000", "lwa:desk:submit:approve:support:1", Map.of("reason", "Подтверждаю"));
    assertTrue(verdict.get("content").toString().contains("нет вердиктов"));
    assertEquals("OPEN", ticketState("support", 1));
  }

  @Test
  void supportRequestClosesWithItsHistoryArchivedAndTheRecordClosed() throws Exception {
    var modal = click("10000", "70000", "lwa:desk:support:topic", Map.of("selected", "question"));
    var preview =
        click("10000", "70000", (String) modal.get("custom_id"), Map.of("reason", "Как получить привилегию?"));
    var saved = click("10000", "70000", confirm(preview), Map.of());
    assertTrue(saved.get("content").toString().contains("сохранена"), saved.toString());
    await(() -> ticketChannel("support", 1).equals("200000"));
    click("10000", "200000", "lwa:desk:submit:close:support:1", Map.of("reason", "Вопрос решён"));
    desk.tick();
    await(() -> ticketState("support", 1).equals("CLOSED"));
    assertEquals(1, discord.deleted.get());
    assertEquals("CLOSED", plugin.moderation().support(1).orElseThrow().get("status"));
  }

  @Test
  void staffAnswerInASupportChannelReachesTheAuthorAndStrangersAreIgnored() throws Exception {
    var modal = click("10000", "70000", "lwa:desk:support:topic", Map.of("selected", "other"));
    var preview =
        click("10000", "70000", (String) modal.get("custom_id"), Map.of("reason", "Нужна помощь с кланом"));
    click("10000", "70000", confirm(preview), Map.of());
    await(() -> ticketChannel("support", 1).equals("200000"));
    click("20000", "200000", "lwa:desk:claim:support:1", Map.of());
    var actions = new DiscordActions(plugin, discord.service);
    actions.onTicketMessage(
        new TicketMessage("SUPPORT:support:1", "200000", "100000000000000010", "20000", "Moderator",
            "Откройте меню клана командой /clan", List.of()));
    actions.onTicketMessage(
        new TicketMessage("SUPPORT:support:1", "200000", "100000000000000011", "90000", "Stranger",
            "Посторонний текст", List.of()));
    actions.onTicketMessage(
        new TicketMessage("SUPPORT:support:1", "200000", "100000000000000012", "10000", "Reporter",
            "Это сообщение автора", List.of()));
    var events = plugin.moderation().deliveries().stream().map(row -> row.get("event").toString()).toList();
    assertTrue(events.stream().anyMatch(e -> e.startsWith("owner-notice:support:1:message:100000000000000010")), events.toString());
    assertTrue(events.stream().noneMatch(e -> e.contains("100000000000000011")), "strangers get no relay");
    assertTrue(events.stream().noneMatch(e -> e.contains("100000000000000012")), "the author is not told their own text");
    // A support channel is never mistaken for a report with the same number.
    assertTrue(plugin.getDatabaseManager().reportDiscussion(1).isEmpty());
  }

  @Test
  void supportDoesNotTouchTheReportTicketRegistry() throws Exception {
    var modal = click("10000", "70000", "lwa:desk:support:topic", Map.of("selected", "other"));
    var preview =
        click("10000", "70000", (String) modal.get("custom_id"), Map.of("reason", "Нужна помощь с кланом"));
    click("10000", "70000", confirm(preview), Map.of());
    await(() -> ticketChannel("support", 1).equals("200000"));
    assertTrue(
        plugin.moderation().tickets().stream().noneMatch(t -> "support".equals(t.get("kind"))),
        "case_tickets feeds the report restore loop and must never contain support");
  }

  @Test
  @SuppressWarnings("unchecked")
  void newCaseMenuNeverPutsMoreThanFiveButtonsInARow() throws Exception {
    for (int i = 0; i < 3; i++)
      desk.store()
          .draft(
              "10000",
              "report",
              new LinkedHashMap<>(Map.of("nick", "Reporter", "target", "Griefer", "reason", "Описание " + i, "_step", 2)));
    new PrivateDiscordMenus(plugin, discord.service, new DiscordActions(plugin, discord.service)).register();
    var menu = discord.renderers.get("newcase").apply("10000");
    var rows = (List<Map<String, Object>>) menu.get("components");
    int buttons = 0;
    for (var row : rows) {
      int inRow = ((List<?>) row.get("components")).size();
      assertTrue(inRow <= 5, "a row holds " + inRow + " buttons");
      buttons += inRow;
    }
    assertEquals(6, buttons, "report, appeal, support and three drafts must all stay reachable");
  }

  @Test
  void supportDraftWithAnUnknownTopicOrAShortTextIsRejected() throws Exception {
    var unknown = click("10000", "70000", "lwa:desk:sdraft:nope", Map.of("reason", "Достаточно длинный текст"));
    assertTrue(unknown.get("content").toString().contains("недоступна"));
    var shortText = click("10000", "70000", "lwa:desk:sdraft:bug", Map.of("reason", "коротко"));
    assertTrue(shortText.get("content").toString().contains("от 10 до 2000"));
    assertEquals(0, discord.creationCalls.get());
  }

  String ticketState(String kind, int id) {
    try {
      return desk.store().get(kind, id).orElseThrow().state();
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  String ticketChannel(String kind, int id) {
    try {
      return desk.store().get(kind, id).map(t -> t.channel()).orElse("");
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  @Test
  void threeStepWizardKeepsInvalidDetailsAndPublishesOnlyAfterReview() throws Exception {
    var step2=click("10000","70000","lwa:desk:identity:report",Map.of("target","Griefer"));
    String draft=confirm(step2).split(":")[3];
    assertEquals(2,((Number)desk.store().draft(draft,"10000").data().get("_step")).intValue());
    var early=click("10000","70000","lwa:desk:confirm:"+draft,Map.of());assertTrue(early.get("content").toString().contains("Сначала"));
    var invalid=click("10000","70000","lwa:desk:details-submit:"+draft,Map.of("reason","Подробное описание нарушения","evidence","javascript:alert(1)"));
    assertTrue(invalid.get("content").toString().contains("http"));assertEquals("javascript:alert(1)",desk.store().draft(draft,"10000").data().get("evidence"));
    assertTrue(plugin.getDatabaseManager().getAllReports(null,null,200,0).isEmpty());
    var preview=click("10000","70000","lwa:desk:details-submit:"+draft,Map.of("reason","Подробное описание нарушения","when","Вчера вечером","where","Арена","evidence","https://example.com/evidence"));
    assertTrue(JsonUtils.toJson(preview).contains("Шаг 3 из 3"));assertTrue(JsonUtils.toJson(preview).contains("Reporter"));
    click("10000","70000",confirm(preview),Map.of());
    var report=plugin.getDatabaseManager().getAllReports(null,null,200,0).getFirst();assertTrue(report.description().contains("https://example.com/evidence"));assertTrue(report.description().contains("Вчера вечером"));
  }

  @Test
  void onlyTheOwnerCanReplyFromThePrivateCardAndShortTextNeverSends() throws Exception {
    int id=createReport();
    assertThrows(IllegalArgumentException.class,()->desk.replyFromOwner("report",id,"30000","wrong","Чужое сообщение"));
    assertThrows(IllegalArgumentException.class,()->desk.replyFromOwner("report",id,"10000","short","ок"));
    assertEquals(0,discord.replySends.get());
    desk.replyFromOwner("report",id,"10000","own","Сообщение для сотрудника");
    assertEquals(1,discord.replySends.get());
    assertTrue(discord.lastReply.get("content").toString().contains("> Сообщение"));
    assertEquals(List.of(),((Map<?,?>)discord.lastReply.get("allowed_mentions")).get("parse"));
    assertFalse(plugin.getDatabaseManager().reportDiscussion(id).isEmpty());
  }

  @Test
  void privateWithdrawalIsOwnerBoundAndKeepsTheArchiveIntent() throws Exception {
    int id=createReport();String action="lwa:desk:submit:close:report:"+id;
    click("30000","dm",action,Map.of("reason","Не своё обращение"));
    assertEquals("OPEN",desk.store().get("report",id).orElseThrow().state());
    click("10000","dm",action,Map.of("reason","Проблема решена самостоятельно"));
    assertEquals("CLOSING",desk.store().get("report",id).orElseThrow().state());
    assertEquals(0,discord.deleted.get());
  }

  @Test
  void linkedReportDoesNotAskForKnownNickButUnlinkedReportDoes() throws Exception {
    var form=click("10000","70000","lwa:desk:new:report",Map.of());
    assertEquals(Set.of("target"),fieldIds(form));
    discord.links.remove("10000");
    form=click("10000","70000","lwa:desk:new:report",Map.of());
    assertEquals(Set.of("target","nick"),fieldIds(form));
  }

  @SuppressWarnings("unchecked")
  Set<String> fieldIds(Map<String,Object> form) {
    var ids=new HashSet<String>();
    for(Object row:(List<?>)form.get("components")) for(Object field:(List<?>)((Map<?,?>)row).get("components")) ids.add(((Map<?,?>)field).get("custom_id").toString());
    return ids;
  }

  WebBan ban(String name,String uuid) {
    return plugin.getDatabaseManager().saveBan(new WebBan(0,name,uuid,null,"Issuer","Причина наказания","","[]",false,"ACTIVE",-1,100,-1));
  }

  @Test
  void linkedAppealOffersOwnPenaltiesAndSkipsIdentityForm() throws Exception {
    var first=ban("Reporter",discord.links.get("10000").toString());
    var renamed=ban("OldNick",discord.links.get("10000").toString());
    var foreign=ban("Reporter",discord.links.get("90000").toString());
    var picker=click("10000","70000","lwa:desk:new:appeal",Map.of());
    String json=JsonUtils.toJson(picker);
    assertTrue(json.contains("lwa:appeal:new:"+first.id()));assertTrue(json.contains("lwa:appeal:new:"+renamed.id()));assertFalse(json.contains("lwa:appeal:new:"+foreign.id()));
    var form=click("10000","70000","lwa:appeal:new:"+first.id(),Map.of());
    assertEquals(Set.of("reason","when","where","evidence"),fieldIds(form));
    String draft=form.get("custom_id").toString().substring("lwa:desk:details-submit:".length());
    assertEquals(first.id(),((Number)desk.store().draft(draft,"10000").data().get("banId")).intValue());
    var denied=click("10000","70000","lwa:appeal:new:"+foreign.id(),Map.of());
    assertTrue(denied.get("content").toString().contains("недоступно"));
  }

  @Test
  void aLinkChangeCannotSilentlyChangeTheAccountOfAnExistingDraft() throws Exception {
    var step2=click("10000","70000","lwa:desk:identity:report",Map.of("target","Griefer","nick","ForgedNick"));
    String draft=confirm(step2).split(":")[3];
    assertEquals("Reporter",desk.store().draft(draft,"10000").data().get("nick"));
    discord.links.remove("10000");
    var result=click("10000","70000","lwa:desk:details-submit:"+draft,Map.of("reason","Проверить нарушение"));
    assertTrue(result.get("content").toString().contains("Привязка аккаунта изменилась"));
    assertTrue(plugin.getDatabaseManager().getAllReports(null,null,100,0).isEmpty());
  }

  @Test
  void aSecondReportBecomesOneConfirmedAdditionWithoutAnotherCaseOrCooldown() throws Exception {
    int id=createReport();
    var step2=click("10000","70000","lwa:desk:identity:report",Map.of("target","Griefer"));
    assertTrue(JsonUtils.toJson(step2).contains("Дополнить жалобу"));
    String draft=confirm(step2).split(":")[3];
    var preview=click("10000","70000","lwa:desk:details-submit:"+draft,Map.of("reason","Дополнительные подробности нарушения"));
    String submit=confirm(preview);
    var result=click("10000","70000",submit,Map.of());
    assertTrue(result.get("content").toString().contains("Дополнение"));
    click("10000","70000",submit,Map.of());
    assertEquals(1,plugin.getDatabaseManager().getAllReports(null,null,100,0).size());
    assertEquals(1,plugin.getDatabaseManager().reportDiscussion(id).size());
    assertTrue(JsonUtils.toJson(plugin.getDatabaseManager().reportDiscussion(id)).contains("Дополнительные подробности"));
    assertTrue(plugin.moderation().deliveries().stream().anyMatch(row->row.get("event").toString().startsWith("report-addition:")));
  }

  @Test
  void waitingEntryIsVisibleOnlyToTheOwnerWhileAResponseIsRequired() throws Exception {
    int id=createReport();
    var menus=new PrivateDiscordMenus(plugin,discord.service,new DiscordActions(plugin,discord.service));menus.register();
    assertFalse(discord.visible.get("waiting").test("10000"));
    desk.store().workDisplay("report",id,"WAITING","Moderator");
    assertTrue(discord.visible.get("waiting").test("10000"));assertFalse(discord.visible.get("waiting").test("90000"));
    assertTrue(JsonUtils.toJson(discord.renderers.get("waiting").apply("10000")).contains("Нужен ваш ответ"));
    desk.store().beginClose("report",id,"Chief","Завершить рассмотрение");
    assertFalse(discord.visible.get("waiting").test("10000"));menus.close();assertFalse(discord.renderers.containsKey("waiting"));
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
  void channelCardShowsTheAuthorNoStaffButtonsAndStaffGetAPrivatePanel() throws Exception {
    int id = createReport();
    desk.tick();
    await(() -> discord.messages.containsKey("200000"));
    String card = JsonUtils.toJson(discord.messages.get("200000"));
    assertTrue(card.contains("lwa:desk:panel:report:" + id));
    assertTrue(card.contains("lwa:desk:form:close:report:" + id));
    for (String hidden :
        List.of("claim", "form:approve", "form:reject", "form:transfer", "form:clarify", "form:escalate", "lwa:flow:templates"))
      assertFalse(card.contains(hidden), hidden);
    var panel = click("20000", "200000", "lwa:desk:panel:report:" + id, Map.of());
    String buttons = JsonUtils.toJson(panel);
    assertTrue(buttons.contains("lwa:desk:claim:report:" + id));
    assertTrue(buttons.contains("lwa:desk:form:approve:report:" + id));
    var author = click("10000", "200000", "lwa:desk:panel:report:" + id, Map.of());
    assertTrue(author.get("content").toString().contains("права сотрудника"));
    assertFalse(JsonUtils.toJson(author).contains("lwa:desk:claim"));
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
        4,
        ((List<?>) ((Map<?, ?>) ((List<?>) first.get("components")).getFirst()).get("components"))
            .size());
    assertTrue(JsonUtils.toJson(first).contains("lwa:desk:new:support"));
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

  public interface PrivateMenuApi {
    CompletableFuture<Boolean> openPrivateMenu(String owner, String section);
    void registerPrivateMenu(String id,String label,java.util.function.Function<String,Map<String,Object>> render);
    void registerPrivateMenu(String id,String label,java.util.function.Predicate<String> visible,java.util.function.Function<String,Map<String,Object>> render);
    void unregisterPrivateMenu(String id);
  }

  public interface PendingApi {
    boolean isTicketCreationPending(TicketType type, String ticketId);
  }

  static class FakeDiscord {
    volatile Throwable readOnlyFailure;
    volatile boolean creationPending;
    final Map<String,java.util.function.Function<String,Map<String,Object>>> renderers=new ConcurrentHashMap<>();
    final Map<String,java.util.function.Predicate<String>> visible=new ConcurrentHashMap<>();
    volatile boolean acknowledgementAccepted = true;
    volatile Map<String, Object> lastAcknowledgement;
    volatile String privateMenuOwner, privateMenuSection;
    final AtomicInteger privateMenuCalls = new AtomicInteger();
    volatile CompletableFuture<Boolean> privateMenuResult = CompletableFuture.completedFuture(true);
    volatile Throwable publicationFailure, creationFailure, recoveryFailure;
    volatile String recoveredChannel="200000";
    volatile Map<String,Object> lastReply;
    final AtomicInteger replySends=new AtomicInteger();
    final AtomicInteger creationCalls=new AtomicInteger();
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
                new Class[] {DiscordService.class, PrivateMenuApi.class, PendingApi.class},
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
                    case "registerPrivateMenu" -> {
                      renderers.put(args[0].toString(),(java.util.function.Function<String,Map<String,Object>>)args[args.length-1]);
                      visible.put(args[0].toString(),args.length==4?(java.util.function.Predicate<String>)args[2]:user->true);
                      yield null;
                    }
                    case "unregisterPrivateMenu" -> {renderers.remove(args[0]);visible.remove(args[0]);yield null;}
                    case "openPrivateMenu" -> {
                      privateMenuOwner = args[0].toString();
                      privateMenuSection = args[1].toString();
                      privateMenuCalls.incrementAndGet();
                      yield privateMenuResult;
                    }
                    case "respond" -> {
                      var data = JsonUtils.parseObject(args[1].toString());
                      lastAcknowledgement = data;
                      if (((Number) data.get("type")).intValue() != 5)
                        responses
                            .get(((DiscordInteraction) args[0]).id())
                            .complete((Map<String, Object>) data.get("data"));
                      yield CompletableFuture.completedFuture(acknowledgementAccepted);
                    }
                    case "editInteractionResponse" -> {
                      responses
                          .get(((DiscordInteraction) args[0]).id())
                          .complete(JsonUtils.parseObject(args[1].toString()));
                      yield CompletableFuture.completedFuture(true);
                    }
                    case "recoverManagedTicket" -> recoveryFailure==null?CompletableFuture.completedFuture(recoveredChannel):CompletableFuture.failedFuture(recoveryFailure);
                    case "sendMessage" -> {
                      lastReply=JsonUtils.parseObject(args[1].toString());replySends.incrementAndGet();
                      yield CompletableFuture.completedFuture("100000000000"+ids.incrementAndGet());
                    }
                    case "createManagedTicket" -> {
                      creationCalls.incrementAndGet();
                      if(creationFailure!=null) yield CompletableFuture.failedFuture(creationFailure);
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
                    case "isTicketCreationPending" -> creationPending;
                    case "setTicketReadOnly" ->
                        readOnlyFailure == null
                            ? CompletableFuture.completedFuture(true)
                            : CompletableFuture.failedFuture(readOnlyFailure);
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
