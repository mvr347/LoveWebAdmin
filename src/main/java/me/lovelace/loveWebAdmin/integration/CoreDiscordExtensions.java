package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.*;
import java.util.concurrent.*;

/** Additive Core v3 adapter. Older Core instances never fall back to production ticket routing. */
public final class CoreDiscordExtensions {
  private CoreDiscordExtensions() {}

  /** Tells LoveCore that ids with this prefix are ours; older Core builds simply do not need it. */
  public static void claimPrefix(DiscordService service, String prefix) {
    try {
      service.getClass().getMethod("claimInteractionPrefix", String.class).invoke(service, prefix);
    } catch (ReflectiveOperationException unavailable) {
      // Pre-ownership Core answers every unclaimed interaction itself; nothing to declare.
    }
  }

  @SuppressWarnings("unchecked")
  public static CompletableFuture<Boolean> unregister(DiscordService service, String name) {
    try {
      return (CompletableFuture<Boolean>)
          service.getClass().getMethod("unregisterCommand", String.class).invoke(service, name);
    } catch (ReflectiveOperationException unavailable) {
      return CompletableFuture.completedFuture(false);
    }
  }

  @SuppressWarnings("unchecked")
  public static CompletableFuture<String> createPreviewTicket(
      DiscordService service,
      TicketType kind,
      String key,
      String player,
      String reason,
      String owner,
      String category) {
    try {
      var loader = DiscordService.class.getClassLoader();
      var type = Class.forName("dev.lovelace.lovecore.api.discord.TicketRequest", true, loader);
      Object request =
          type.getConstructor(
                  TicketType.class,
                  String.class,
                  String.class,
                  String.class,
                  String.class,
                  String.class,
                  boolean.class)
              .newInstance(kind, key, player, reason, owner, category, true);
      return (CompletableFuture<String>)
          service.getClass().getMethod("createTicketChannel", type).invoke(service, request);
    } catch (ReflectiveOperationException unavailable) {
      return CompletableFuture.failedFuture(
          new IllegalStateException(
              "Обновите LoveCore: требуется явная тестовая маршрутизация Discord", unavailable));
    }
  }

  @SuppressWarnings("unchecked")
  public static CompletableFuture<String> sendImage(
      DiscordService service,
      String channel,
      String content,
      String filename,
      String mime,
      byte[] image) {
    try {
      return (CompletableFuture<String>)
          service
              .getClass()
              .getMethod(
                  "sendImage", String.class, String.class, String.class, String.class, byte[].class)
              .invoke(service, channel, content, filename, mime, image);
    } catch (ReflectiveOperationException unavailable) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Обновите LoveCore для передачи изображений", unavailable));
    }
  }
}
