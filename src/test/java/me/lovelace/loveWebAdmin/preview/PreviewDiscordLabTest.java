package me.lovelace.loveWebAdmin.preview;

import static org.junit.jupiter.api.Assertions.*;
import dev.lovelace.lovecore.api.discord.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PreviewDiscordLabTest {
  @TempDir Path folder;
  PreviewRuntime runtime;
  PreviewDiscordLab lab;
  final AtomicBoolean alive = new AtomicBoolean(true);
  Fake discord;
  String prefix;

  @BeforeEach
  void setup() throws Exception {
    String namespace = "a".repeat(32);
    prefix = "lwa:preview:" + namespace + ":";
    runtime = new PreviewRuntime(folder, namespace);
    lab =
        new PreviewDiscordLab(
            new PreviewService.Session(namespace, "hash", 1, runtime, System.currentTimeMillis()),
            "60000",
            "70000",
            Set.of("10000", "20000"),
            alive::get);
    discord = new Fake();
  }

  @AfterEach
  void stop() throws Exception {
    lab.close();
    runtime.close();
  }

  DiscordInteraction interaction(
      String user, String channel, String action, Map<String, String> values) {
    var data = new HashMap<>(values);
    data.put("_channelId", channel);
    return new DiscordInteraction(
        "123456", "99999", "unused", user, "90000", prefix + action, 3, data);
  }

  @SuppressWarnings("unchecked")
  String draft(String kind) throws Exception {
    lab.interact(
        interaction(
            "10000",
            "70000",
            "draft:" + kind,
            Map.of("nick", "Ada123", "reason", "Описание тестовой ситуации")),
        discord.service);
    var data = (Map<String, Object>) discord.response.get("data");
    var row = (Map<?, ?>) ((List<?>) data.get("components")).getFirst();
    var button = (Map<?, ?>) ((List<?>) row.get("components")).getFirst();
    return button.get("custom_id").toString().substring(prefix.length());
  }

  @Test
  void previewVerdictQueuesOnlyTestDeliveryAndClosesThroughTheArchive() throws Exception {
    String action = draft("appeal");
    lab.interact(interaction("10000", "70000", action, Map.of()), discord.service);
    var appeal = runtime.database().getAllAppeals(null, 100, 0).getFirst();
    runtime.control("role", Map.of("id", 2));
    String route = "/api/cases/appeal/" + appeal.id();
    var prepared =
        (Map<?, ?>)
            runtime.execute(
                "POST",
                route + "/decision-preview",
                Map.of("action", "approve", "reason", "Тестовое решение после проверки материалов"),
                Map.of());
    runtime.execute(
        "POST", route + "/decision-confirm", Map.of("key", prepared.get("key")), Map.of());
    assertEquals("UNBANNED", runtime.database().getBanById(appeal.banId()).orElseThrow().status());
    assertTrue(
        runtime.dueDeliveries().stream()
            .anyMatch(d -> d.get("payload").toString().contains("APPEAL_DECISION")));
    assertTrue(lab.finishKnown("appeal", appeal.id(), discord.service));
    int count = discord.deleted;
    assertTrue(lab.finishKnown("appeal", appeal.id(), discord.service));
    assertEquals(count, discord.deleted);
  }

  @Test
  void protocolChecksExercisePublicationFailureAndArchiveInvariants() throws Exception {
    var checks = DiscordProtocolChecks.run(folder);
    assertTrue(checks.size() >= 17);
    for (var check : checks) assertEquals(true, check.get("passed"), check.toString());
    assertEquals(0, discord.writes);
  }

  @Test
  void statusNeverSendsAndPanelReusesItsIdAfterRestart() throws Exception {
    assertEquals(0, discord.writes);
    lab.status();
    assertEquals(0, discord.writes);
    lab.run("panel", discord.service);
    lab.run("panel", discord.service);
    assertEquals(1, discord.panelCreates);
    lab.close();
    lab =
        new PreviewDiscordLab(
            new PreviewService.Session(
                "a".repeat(32), "hash", 1, runtime, System.currentTimeMillis()),
            "60000",
            "70000",
            Set.of("10000", "20000"),
            alive::get);
    lab.run("panel", discord.service);
    assertEquals(1, discord.panelCreates);
    String content = JsonUtils.toJson(discord.panel);
    assertTrue(content.contains("[ТЕСТ]"));
    assertTrue(content.contains(prefix + "report"));
    assertFalse(content.contains("lwa:desk:"));
  }

  @Test
  void liveProbeUsesPrivateTestRoutingAndArchivesImageBeforeDeleting() throws Exception {
    var result = lab.run("roundtrip", discord.service);
    assertEquals(1, discord.deleted);
    assertTrue(discord.locked);
    assertTrue(discord.request.routing().preview());
    assertEquals("60000", discord.request.routing().categoryId());
    assertTrue(discord.request.supervisorRoleIds().isEmpty());
    assertEquals(Set.of("10000", "20000"), new HashSet<>(discord.request.memberIds()));
    var ticket = lab.store.active().getFirst();
    assertEquals("CLOSED", ticket.state());
    assertFalse(lab.store.transcript(ticket.kind(), ticket.id(), 0).isEmpty());
    assertEquals(1, lab.store.files(ticket.kind(), ticket.id()).size());
    assertTrue(JsonUtils.toJson(result).contains("Архив текста и вложения"));
  }

  @Test
  void unavailableImageKeepsTheChannelForReconciliation() throws Exception {
    discord.failDownload = true;
    assertThrows(Exception.class, () -> lab.run("roundtrip", discord.service));
    assertEquals(0, discord.deleted);
    assertEquals("CLOSING", lab.store.active().getFirst().state());
  }

  @Test
  void badTestParentAndRevokedOwnerNeverSend() throws Exception {
    discord.wrongParent = true;
    assertThrows(IllegalArgumentException.class, () -> lab.run("panel", discord.service));
    assertEquals(0, discord.writes);
    discord.wrongParent = false;
    alive.set(false);
    assertThrows(PreviewRuntime.Rejected.class, () -> lab.run("panel", discord.service));
    assertEquals(0, discord.writes);
  }

  @Test
  void humanFormIsAuthorBoundAndConfirmCannotCreateTwoCases() throws Exception {
    String action = draft("report");
    assertThrows(
        IllegalArgumentException.class,
        () -> lab.interact(interaction("20000", "70000", action, Map.of()), discord.service));
    lab.interact(interaction("10000", "70000", action, Map.of()), discord.service);
    lab.interact(interaction("10000", "70000", action, Map.of()), discord.service);
    assertEquals(1, runtime.database().getAllReports(null, null, 100, 0).size());
    assertEquals(1, discord.creates);
    assertEquals("10000", lab.store.active().getFirst().owner());
    lab.interact(interaction("20000", "70000", "mine", Map.of()), discord.service);
    assertTrue(discord.edited.get("content").toString().contains("пока нет"));
    assertThrows(
        IllegalArgumentException.class,
        () -> lab.interact(interaction("30000", "70000", "mine", Map.of()), discord.service));
  }

  @Test
  void cancelAndForeignChannelDoNotCreateCases() throws Exception {
    String action = draft("appeal");
    lab.interact(
        interaction("10000", "70000", action.replace("confirm:", "cancel:"), Map.of()),
        discord.service);
    lab.interact(interaction("10000", "70000", action, Map.of()), discord.service);
    assertTrue(runtime.database().getAllAppeals(null, 100, 0).isEmpty());
    assertEquals(0, discord.creates);
    assertThrows(
        IllegalArgumentException.class,
        () -> lab.interact(interaction("10000", "80000", "report", Map.of()), discord.service));
  }

  @Test
  void appealFromPanelExistsOnlyInSandboxAndReplyIsDeduplicated() throws Exception {
    String action = draft("appeal");
    lab.interact(interaction("10000", "70000", action, Map.of()), discord.service);
    var appeal = runtime.database().getAllAppeals(null, 100, 0).getFirst();
    assertFalse(runtime.moderation().verified(appeal.id()));
    assertEquals("10000", runtime.moderation().owner(appeal.id()).orElseThrow());
    runtime.incoming(
        "appeal", appeal.id(), "real-discord-message", "Тестировщик", "Подробности ситуации");
    runtime.incoming(
        "appeal", appeal.id(), "real-discord-message", "Тестировщик", "Подробности ситуации");
    assertEquals(
        1,
        runtime.database().getAppealMessages(appeal.id()).stream()
            .filter(m -> m.message().equals("Подробности ситуации"))
            .count());
  }

  final class Fake {
    int writes, panelCreates, creates, deleted;
    boolean locked, wrongParent, failDownload;
    Map<String, Object> response, edited, panel;
    ManagedTicketRequest request;
    final AtomicInteger seq = new AtomicInteger(100000);
    final Map<String, List<TicketMessage>> history = new HashMap<>();
    final DiscordService service =
        (DiscordService)
            Proxy.newProxyInstance(
                DiscordService.class.getClassLoader(),
                new Class<?>[] {DiscordService.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "isEnabled" -> true;
                      case "getDiscordGuildId" -> "90000";
                      case "inspectChannel" -> {
                        String channel = args[0].toString();
                        var access = new ArrayList<Map<String, Object>>();
                        access.add(Map.of("id", "90000", "type", 0, "allow", "0", "deny", "1024"));
                        access.add(
                            Map.of("id", "33333", "type", 1, "allow", "117776", "deny", "0"));
                        for (String member : List.of("10000", "20000"))
                          access.add(
                              Map.of("id", member, "type", 1, "allow", "101376", "deny", "0"));
                        yield CompletableFuture.completedFuture(
                            Map.of(
                                "id",
                                channel,
                                "guildId",
                                "90000",
                                "name",
                                "test",
                                "type",
                                channel.equals("60000") ? 4 : 0,
                                "parentId",
                                wrongParent ? "50000" : "60000",
                                "overwrites",
                                access));
                      }
                      case "ensureMessage" -> {
                        writes++;
                        String channel = args[0].toString();
                        String id =
                            args[1] == null
                                ? String.valueOf(seq.incrementAndGet())
                                : args[1].toString();
                        var body = JsonUtils.parseObject(args[2].toString());
                        if (channel.equals("70000")) {
                          panel = body;
                          if (args[1] == null) panelCreates++;
                        } else add(channel, id, body.get("content").toString(), List.of());
                        yield CompletableFuture.completedFuture(id);
                      }
                      case "createManagedTicket" -> {
                        writes++;
                        creates++;
                        request = (ManagedTicketRequest) args[0];
                        yield CompletableFuture.completedFuture("80000");
                      }
                      case "sendImage" -> {
                        writes++;
                        String id = String.valueOf(seq.incrementAndGet());
                        add(
                            args[0].toString(),
                            id,
                            "Изображение",
                            List.of(
                                new TicketMessage.Attachment(
                                    "preview.png",
                                    "https://cdn.discordapp.com/attachments/1/2/proof.png")));
                        yield CompletableFuture.completedFuture(id);
                      }
                      case "getTicketHistory" ->
                          CompletableFuture.completedFuture(
                              args[1] != null
                                  ? List.of()
                                  : List.copyOf(
                                      history.getOrDefault(args[0].toString(), List.of())));
                      case "getTicketAttachment" -> {
                        if (failDownload)
                          yield CompletableFuture.failedFuture(
                              new java.io.IOException("download failed"));
                        yield CompletableFuture.completedFuture(PreviewDiscordLab.PNG);
                      }
                      case "setTicketReadOnly" -> {
                        locked = true;
                        yield CompletableFuture.completedFuture(true);
                      }
                      case "deleteTicketChannel" -> {
                        assertTrue(locked);
                        var ticket =
                            lab.store.active().stream()
                                .filter(t -> t.channel().equals(args[0]))
                                .findFirst()
                                .orElseThrow();
                        assertEquals("ARCHIVED", ticket.state());
                        assertEquals(
                            history.get(args[0].toString()).stream()
                                .mapToLong(m -> m.attachments().size())
                                .sum(),
                            lab.store.files(ticket.kind(), ticket.id()).size());
                        deleted++;
                        yield CompletableFuture.completedFuture(true);
                      }
                      case "respond" -> {
                        response = JsonUtils.parseObject(args[1].toString());
                        yield CompletableFuture.completedFuture(true);
                      }
                      case "editInteractionResponse" -> {
                        edited = JsonUtils.parseObject(args[1].toString());
                        yield CompletableFuture.completedFuture(true);
                      }
                      default -> throw new UnsupportedOperationException(method.getName());
                    });

    void add(String channel, String id, String text, List<TicketMessage.Attachment> files) {
      history
          .computeIfAbsent(channel, k -> new ArrayList<>())
          .addFirst(
              new TicketMessage(
                  "preview:test:report:1", channel, id, "33333", "Тестовый бот", text, files));
    }
  }
}
