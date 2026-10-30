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

  public static CompletableFuture<String> managedPreviewTicket(
      DiscordService service,
      TicketType kind,
      String key,
      String player,
      String owner,
      String category,
      java.util.List<String> members,
      String name) {
    try {
      var loader = DiscordService.class.getClassLoader();
      var routeType =
          Class.forName("dev.lovelace.lovecore.api.discord.TicketRequest", true, loader);
      var route =
          routeType
              .getConstructor(
                  TicketType.class,
                  String.class,
                  String.class,
                  String.class,
                  String.class,
                  String.class,
                  boolean.class)
              .newInstance(kind, key, player, "[ТЕСТ] Проверка обращений", owner, category, true);
      var type =
          Class.forName("dev.lovelace.lovecore.api.discord.ManagedTicketRequest", true, loader);
      Object request =
          type.getConstructor(routeType, java.util.List.class, java.util.List.class, String.class)
              .newInstance(route, java.util.List.of(), members, name);
      return managed(service, "createManagedTicket", new Class<?>[] {type}, request);
    } catch (ReflectiveOperationException error) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Обновите ядро для приватных тестовых обращений", error));
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

  @SuppressWarnings("unchecked")
  public static <T> java.util.concurrent.CompletableFuture<T> managed(
      DiscordService service, String method, Class<?>[] types, Object... args) {
    try {
      return (java.util.concurrent.CompletableFuture<T>)
          service.getClass().getMethod(method, types).invoke(service, args);
    } catch (ReflectiveOperationException error) {
      return java.util.concurrent.CompletableFuture.failedFuture(
          new IllegalStateException("Обновите ядро: требуются управляемые обращения", error));
    }
  }

  public static String guild(DiscordService service) {
    try {
      return (String) service.getClass().getMethod("getDiscordGuildId").invoke(service);
    } catch (ReflectiveOperationException error) {
      return "";
    }
  }

  public static java.util.concurrent.CompletableFuture<String> managedTicket(
      DiscordService service,
      TicketType kind,
      String key,
      String player,
      String reason,
      String owner,
      java.util.List<String> roles,
      java.util.List<String> members,
      String name) {
    return managedTicket(service, kind, key, player, reason, owner, roles, members, name, false);
  }

  public static java.util.concurrent.CompletableFuture<String> managedTicket(
      DiscordService service,
      TicketType kind,
      String key,
      String player,
      String reason,
      String owner,
      java.util.List<String> roles,
      java.util.List<String> members,
      String name,
      boolean lookupOnly) {
    try {
      var loader = DiscordService.class.getClassLoader();
      var routing = Class.forName("dev.lovelace.lovecore.api.discord.TicketRequest", true, loader);
      Object request =
          routing
              .getConstructor(
                  TicketType.class,
                  String.class,
                  String.class,
                  String.class,
                  String.class,
                  String.class,
                  boolean.class)
              .newInstance(kind, key, player, reason, owner, null, false);
      var type =
          Class.forName("dev.lovelace.lovecore.api.discord.ManagedTicketRequest", true, loader);
      Object managed =
          type.getConstructor(routing, java.util.List.class, java.util.List.class, String.class)
              .newInstance(request, roles, members, name);
      return managed(
          service,
          lookupOnly ? "recoverManagedTicket" : "createManagedTicket",
          new Class<?>[] {type},
          managed);
    } catch (ReflectiveOperationException error) {
      return java.util.concurrent.CompletableFuture.failedFuture(
          new IllegalStateException("Обновите ядро для приватных обращений", error));
    }
  }
}
