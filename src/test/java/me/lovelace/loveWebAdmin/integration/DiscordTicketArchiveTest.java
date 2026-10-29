package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;
import dev.lovelace.lovecore.api.discord.TicketMessage;
import java.nio.file.Path;
import java.util.*;
import me.lovelace.loveWebAdmin.database.TicketDeskStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DiscordTicketArchiveTest {
  @TempDir Path dir;

  static class Discord implements DiscordTicketArchive.Transport {
    boolean locked, deleted, failFile, failDelete, late;
    int pages;

    public void readOnly(String channel) {
      locked = true;
    }

    TicketMessage message(String id) {
      return new TicketMessage(
          "report:1",
          "20000",
          id,
          "10000",
          "Игрок",
          "Сообщение",
          List.of(
              new TicketMessage.Attachment(
                  "proof.png", "https://cdn.discordapp.com/attachments/1/2/proof.png")));
    }

    public List<TicketMessage> history(String channel, String before) {
      pages++;
      return List.of(message(late && pages > 1 ? "100000000000000001" : "100000000000000000"));
    }

    public byte[] attachment(String channel, String message, String url) {
      if (failFile) throw new IllegalStateException("Сеть недоступна");
      return new byte[] {1, 2, 3};
    }

    public void delete(String channel) {
      assertTrue(locked);
      if (failDelete) throw new IllegalStateException("Удаление не подтверждено");
      deleted = true;
    }
  }

  private TicketDeskStore.Ticket closing(TicketDeskStore store) throws Exception {
    store.register("report", 1, "10000");
    store.channel("report", 1, "20000");
    store.beginClose("report", 1, "Сотрудник", "Рассмотрение завершено");
    return store.get("report", 1).orElseThrow();
  }

  @Test
  void fileFailureKeepsChannelAndResumePreservesAllData() throws Exception {
    try (var store = new TicketDeskStore(dir.resolve("desk.db"))) {
      var ticket = closing(store);
      var discord = new Discord();
      discord.failFile = true;
      var archive = new DiscordTicketArchive(store, discord);
      assertThrows(IllegalStateException.class, () -> archive.finish(ticket));
      assertFalse(discord.deleted);
      assertEquals("CLOSING", store.get("report", 1).orElseThrow().state());
      discord.failFile = false;
      assertTrue(archive.finish(store.get("report", 1).orElseThrow()));
      assertTrue(discord.deleted);
      assertEquals(1, store.transcript("report", 1, 0).size());
      assertEquals(1, store.files("report", 1).size());
      assertFalse(archive.finish(store.get("report", 1).orElseThrow()));
    }
  }

  @Test
  void newMessageDuringArchivePreventsDeletion() throws Exception {
    try (var store = new TicketDeskStore(dir.resolve("desk.db"))) {
      var ticket = closing(store);
      var discord = new Discord();
      discord.late = true;
      assertThrows(
          IllegalStateException.class,
          () -> new DiscordTicketArchive(store, discord).finish(ticket));
      assertFalse(discord.deleted);
      assertEquals("CLOSING", store.get("report", 1).orElseThrow().state());
    }
  }

  @Test
  void restartAfterUnknownDeletionDoesNotReadOrLoseArchivedHistory() throws Exception {
    var file = dir.resolve("desk.db");
    var discord = new Discord();
    try (var store = new TicketDeskStore(file)) {
      var ticket = closing(store);
      discord.failDelete = true;
      assertThrows(
          IllegalStateException.class,
          () -> new DiscordTicketArchive(store, discord).finish(ticket));
      assertEquals("ARCHIVED", store.get("report", 1).orElseThrow().state());
    }
    try (var store = new TicketDeskStore(file)) {
      discord.failDelete = false;
      int pages = discord.pages;
      assertTrue(
          new DiscordTicketArchive(store, discord).finish(store.get("report", 1).orElseThrow()));
      assertEquals(pages, discord.pages);
      assertEquals(1, store.transcript("report", 1, 0).size());
      assertEquals("CLOSED", store.get("report", 1).orElseThrow().state());
    }
  }

  @Test
  void pagingDoesNotSilentlyDiscardOlderMessages() throws Exception {
    try (var store = new TicketDeskStore(dir.resolve("pages.db"))) {
      var ticket = closing(store);
      var discord =
          new Discord() {
            @Override
            public List<TicketMessage> history(String channel, String before) {
              var page = new ArrayList<TicketMessage>();
              if (before == null) {
                for (int i = 150; i > 50; i--)
                  page.add(
                      new TicketMessage(
                          "report:1",
                          channel,
                          String.valueOf(100000000000000000L + i),
                          "10000",
                          "Игрок",
                          "Текст " + i,
                          List.of()));
              } else {
                for (int i = 50; i > 0; i--)
                  page.add(
                      new TicketMessage(
                          "report:1",
                          channel,
                          String.valueOf(100000000000000000L + i),
                          "10000",
                          "Игрок",
                          "Текст " + i,
                          List.of()));
              }
              return page;
            }
          };
      assertTrue(new DiscordTicketArchive(store, discord).finish(ticket));
      assertEquals(100, store.transcript("report", 1, 0).size());
      assertEquals(50, store.transcript("report", 1, 100).size());
      assertTrue(discord.deleted);
    }
  }

  @Test
  void stoppingAfterHistoryWasSavedStillPreventsDeletion() throws Exception {
    try (var store = new TicketDeskStore(dir.resolve("stop.db"))) {
      var ticket = closing(store);
      var running = new java.util.concurrent.atomic.AtomicBoolean(true);
      var discord =
          new Discord() {
            @Override
            public List<TicketMessage> history(String channel, String before) {
              var result = super.history(channel, before);
              if (pages > 1) running.set(false);
              return result;
            }
          };
      assertThrows(
          InterruptedException.class,
          () -> new DiscordTicketArchive(store, discord, running::get).finish(ticket));
      assertFalse(discord.deleted);
      assertEquals("ARCHIVED", store.get("report", 1).orElseThrow().state());
    }
  }

  @Test
  void closingDuringAnUnknownCreationKeepsTheRecoverableIntent() throws Exception {
    try (var store = new TicketDeskStore(dir.resolve("creation.db"))) {
      store.register("report", 1, "10000");
      store.startCreation("report", 1);
      store.beginClose("report", 1, "Сотрудник", "Закрываю обращение");
      var discord = new Discord();
      var archive = new DiscordTicketArchive(store, discord);
      assertThrows(
          IllegalStateException.class, () -> archive.finish(store.get("report", 1).orElseThrow()));
      assertFalse(discord.deleted);
      assertEquals("CLOSING", store.get("report", 1).orElseThrow().state());
      store.channel("report", 1, "20000");
      assertTrue(archive.finish(store.get("report", 1).orElseThrow()));
      assertTrue(discord.deleted);
      store.register("report", 2, "10000");
      store.beginClose("report", 2, "Сотрудник", "Закрыто до отправки");
      assertTrue(archive.finish(store.get("report", 2).orElseThrow()));
    }
  }
}
