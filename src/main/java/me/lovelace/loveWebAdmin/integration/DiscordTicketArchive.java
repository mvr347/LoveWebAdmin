package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.TicketMessage;
import java.util.*;
import me.lovelace.loveWebAdmin.database.TicketDeskStore;

/** Fails closed: deletion is reachable only after every page and attachment was persisted. */
public final class DiscordTicketArchive {
  public interface Transport {
    void readOnly(String channel) throws Exception;

    List<TicketMessage> history(String channel, String before) throws Exception;

    byte[] attachment(String channel, String message, String url) throws Exception;

    void delete(String channel) throws Exception;
  }

  private final TicketDeskStore store;
  private final Transport transport;
  private final java.util.function.BooleanSupplier enabled;

  public DiscordTicketArchive(TicketDeskStore store, Transport transport) {
    this(store, transport, () -> true);
  }

  public DiscordTicketArchive(
      TicketDeskStore store, Transport transport, java.util.function.BooleanSupplier enabled) {
    this.store = store;
    this.transport = transport;
    this.enabled = enabled;
  }

  private void admitted() throws InterruptedException {
    if (!enabled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new InterruptedException("Архивация остановлена");
  }

  public boolean finish(TicketDeskStore.Ticket ticket) throws Exception {
    admitted();
    if (ticket.state().equals("CLOSING")) {
      if (ticket.channel().isBlank() && store.creationStarted(ticket.kind(), ticket.id()))
        throw new IllegalStateException(
            "Создание канала не подтверждено; сначала требуется сверка");
      if (!ticket.channel().isBlank()) archive(ticket);
      if (!store.transition(ticket.kind(), ticket.id(), "CLOSING", "ARCHIVED")) return false;
      ticket = store.get(ticket.kind(), ticket.id()).orElseThrow();
    }
    if (!ticket.state().equals("ARCHIVED")) return false;
    admitted();
    if (!ticket.channel().isBlank()) transport.delete(ticket.channel());
    return store.transition(ticket.kind(), ticket.id(), "ARCHIVED", "CLOSED");
  }

  private void archive(TicketDeskStore.Ticket ticket) throws Exception {
    transport.readOnly(ticket.channel());
    String before = null, latest = null;
    int count = 0;
    while (true) {
      admitted();
      var messages = transport.history(ticket.channel(), before);
      if (messages.size() > 100)
        throw new IllegalStateException("Превышен размер страницы истории");
      if (messages.isEmpty()) break;
      if (latest == null) latest = messages.getFirst().messageId();
      for (var message : messages) {
        if (!ticket.channel().equals(message.channelId()))
          throw new IllegalArgumentException("Сообщение из другого канала");
        if (++count > 10000)
          throw new IllegalArgumentException("В обращении больше 10000 сообщений; канал сохранён");
        store.archiveMessage(
            ticket.kind(),
            ticket.id(),
            message.messageId(),
            message.authorId(),
            message.authorName(),
            message.content());
        for (var file : message.attachments())
          if (!store.hasFile(ticket.kind(), ticket.id(), message.messageId(), file.url())) {
            byte[] bytes = transport.attachment(ticket.channel(), message.messageId(), file.url());
            store.archiveFile(
                ticket.kind(), ticket.id(), message.messageId(), file.url(), file.name(), bytes);
          }
      }
      String cursor = messages.getLast().messageId();
      if (cursor.equals(before)) throw new IllegalStateException("Курсор истории не изменился");
      before = cursor;
      if (messages.size() < 100) break;
    }
    var last = transport.history(ticket.channel(), null);
    if (!Objects.equals(latest, last.isEmpty() ? null : last.getFirst().messageId()))
      throw new IllegalStateException(
          "Во время архивации появились новые сообщения; канал сохранён");
  }
}
