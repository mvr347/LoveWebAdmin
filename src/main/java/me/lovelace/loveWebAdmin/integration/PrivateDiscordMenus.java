package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.discord.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/** Player-facing cases live entirely in Discord; each selected case checks its real owner. */
final class PrivateDiscordMenus implements AutoCloseable {
  private final LoveWebAdmin plugin;
  private final DiscordService service;
  private final DiscordActions authority;

  private record View(int page, String kind, int id, int history) {
    View(int page, String kind, int id) {
      this(page, kind, id, 0);
    }
  }

  private final Map<String, View> views =
      Collections.synchronizedMap(
          new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, View> e) {
              return size() > 512;
            }
          });

  PrivateDiscordMenus(LoveWebAdmin plugin, DiscordService service, DiscordActions authority) {
    this.plugin = plugin;
    this.service = service;
    this.authority = authority;
  }

  void register() {
    register("appeals", "Мои обращения", this::mine);
    register(
        "newcase",
        "Создать обращение",
        u ->
            body(
                "**Новое обращение**\nФорму и результат увидите только вы и допущенная модерация.",
                List.of(
                    button("lwa:desk:new:report", "Жалоба"),
                    button("lwa:desk:new:appeal", "Апелляция"))));
    register("penalties", "Мои наказания", this::penalties);
    try {
      service
          .getClass()
          .getMethod(
              "registerPrivateMenu",
              String.class,
              String.class,
              java.util.function.Predicate.class,
              Function.class)
          .invoke(
              service,
              "staffcases",
              "Мои дела",
              (java.util.function.Predicate<String>) this::isStaff,
              (Function<String, Map<String, Object>>) this::staffCases);
    } catch (ReflectiveOperationException error) {
      plugin.getLogger().warning("Обновите LoveCore для личного меню сотрудника");
    }
  }

  private void register(String id, String label, Function<String, Map<String, Object>> render) {
    try {
      service
          .getClass()
          .getMethod("registerPrivateMenu", String.class, String.class, Function.class)
          .invoke(service, id, label, render);
    } catch (ReflectiveOperationException e) {
      plugin.getLogger().warning("Для общего личного меню обновите LoveCore");
    }
  }

  private Map<String, Object> mine(String owner) {
    try {
      View view = views.getOrDefault(owner, new View(0, "", 0));
      var store = plugin.getDiscordBridge().ticketDeskStore();
      if (view.id > 0) {
        var t =
            store.get(view.kind, view.id).filter(row -> row.owner().equals(owner)).orElseThrow();
        var lines =
            new StringBuilder(
                "**"
                    + (t.kind().equals("report") ? "Жалоба" : "Апелляция")
                    + " №"
                    + t.id()
                    + "**\nСтатус: "
                    + state(t.state())
                    + "\n");
        var controls = new ArrayList<Map<String, Object>>();
        if (t.channel() != null && !t.channel().isBlank() && !t.state().equals("CLOSED"))
          controls.add(
              Map.of(
                  "type",
                  2,
                  "style",
                  5,
                  "label",
                  "Открыть переписку",
                  "url",
                  "https://discord.com/channels/"
                      + CoreDiscordExtensions.guild(service)
                      + "/"
                      + t.channel()));
        var transcript = store.transcript(t.kind(), t.id(), view.history * 5);
        int shown = 0;
        for (var message : transcript.stream().limit(5).toList()) {
          if (lines.length() > 1500) break;
          shown++;
          lines
              .append("\n")
              .append(Objects.toString(message.get("author"), "Сообщение"))
              .append(": ")
              .append(capMessage(Objects.toString(message.get("text"), "")));
        }
        var result = body(cap(lines.toString()), controls);
        var rows = new ArrayList<Object>((List<?>) result.get("components"));
        var history = new ArrayList<Map<String, Object>>();
        if (view.history > 0)
          history.add(button("lwa:mine:history:" + (view.history - 1), "Раньше"));
        if (transcript.size() > shown
            || store.files(t.kind(), t.id()).size() > (view.history + 1) * 5)
          history.add(button("lwa:mine:history:" + (view.history + 1), "Дальше"));
        history.add(button("lwa:mine:page:" + view.page, "К списку"));
        rows.add(Map.of("type", 1, "components", history));
        var files = store.files(t.kind(), t.id());
        var downloads = new ArrayList<Map<String, Object>>();
        for (var file : files.stream().skip((long) view.history * 5).limit(5).toList())
          downloads.add(
              button(
                  "lwa:mine:file:" + t.kind() + ":" + t.id() + ":" + file.get("id"),
                  "Файл " + file.get("id")));
        if (!downloads.isEmpty()) rows.add(Map.of("type", 1, "components", downloads));
        result.put("components", rows);
        return result;
      }
      var tickets = store.owned(owner, view.page * 5);
      var buttons = new ArrayList<Map<String, Object>>();
      var lines = new StringBuilder("**Мои обращения**\nСтраница " + (view.page + 1) + "\n");
      for (var t : tickets.stream().limit(5).toList()) {
        String label = (t.kind().equals("report") ? "Жалоба" : "Апелляция") + " №" + t.id();
        lines.append("\n").append(label).append(" · ").append(state(t.state()));
        buttons.add(button("lwa:mine:open:" + t.kind() + ":" + t.id(), label));
      }
      if (tickets.isEmpty()) lines.append("\nОбращений пока нет.");
      var result = body(lines.toString(), buttons);
      var rows = new ArrayList<Object>((List<?>) result.get("components"));
      var navigation = new ArrayList<Map<String, Object>>();
      if (view.page > 0) navigation.add(button("lwa:mine:page:" + (view.page - 1), "Предыдущая"));
      if (tickets.size() > 5)
        navigation.add(button("lwa:mine:page:" + (view.page + 1), "Следующая"));
      navigation.add(button("lwa:mine:page:" + view.page, "Обновить"));
      rows.add(Map.of("type", 1, "components", navigation));
      result.put("components", rows);
      return result;
    } catch (Exception e) {
      throw new IllegalStateException("Не удалось загрузить свои обращения", e);
    }
  }

  private boolean isStaff(String owner) {
    return authority
            .staffMember(owner, me.lovelace.loveWebAdmin.models.Permission.MANAGE_REPORTS)
            .isPresent()
        || authority
            .staffMember(owner, me.lovelace.loveWebAdmin.models.Permission.MANAGE_APPEALS)
            .isPresent();
  }

  private Map<String, Object> staffCases(String owner) {
    if (!isStaff(owner)) return body("Доступ сотрудника не подтверждён.", List.of());
    try {
      var admin =
          authority
              .staffMember(owner, me.lovelace.loveWebAdmin.models.Permission.MANAGE_REPORTS)
              .or(
                  () ->
                      authority.staffMember(
                          owner, me.lovelace.loveWebAdmin.models.Permission.MANAGE_APPEALS))
              .orElseThrow();
      var lines =
          new StringBuilder(
              "**Мои дела**\n"
                  + "Откройте назначенное вам обращение. Новые дела доступны в закрытой очереди"
                  + " сотрудников.\n");
      var buttons = new ArrayList<Map<String, Object>>();
      for (var ticket : plugin.getDiscordBridge().ticketDeskStore().active()) {
        String assignee =
            Objects.toString(
                plugin.moderation().work(ticket.kind(), ticket.id()).get("assignee"), "");
        if (authority
            .staffMember(
                owner,
                ticket.kind().equals("report")
                    ? me.lovelace.loveWebAdmin.models.Permission.MANAGE_REPORTS
                    : me.lovelace.loveWebAdmin.models.Permission.MANAGE_APPEALS)
            .isEmpty()) continue;
        if (!assignee.equals(admin.username())
            || ticket.channel().isBlank()
            || ticket.state().equals("CLOSED")) continue;
        String label =
            (ticket.kind().equals("report") ? "Жалоба" : "Апелляция") + " №" + ticket.id();
        lines.append("\n").append(label);
        if (buttons.size() < 5)
          buttons.add(
              Map.of(
                  "type",
                  2,
                  "style",
                  5,
                  "label",
                  label,
                  "url",
                  "https://discord.com/channels/"
                      + CoreDiscordExtensions.guild(service)
                      + "/"
                      + ticket.channel()));
      }
      if (buttons.isEmpty()) lines.append("\nНазначенных дел пока нет.");
      return body(cap(lines.toString()), buttons);
    } catch (Exception error) {
      throw new IllegalStateException("Не удалось загрузить дела", error);
    }
  }

  private Map<String, Object> penalties(String owner) {
    var linked = service.getLinkedPlayer(owner);
    if (linked.isEmpty())
      return body(
          "Привяжите аккаунт в разделе «Мой аккаунт», чтобы просмотреть наказания.", List.of());
    var stats = plugin.getDatabaseManager().getPlayerStats(linked.get().toString());
    String name = stats.map(m -> Objects.toString(m.get("name"), "")).orElse("");
    var bans =
        plugin.getDatabaseManager().getActiveBansForPlayer(name, linked.get().toString(), null);
    var lines = new StringBuilder("**Мои наказания**\n");
    var buttons = new ArrayList<Map<String, Object>>();
    for (var b : bans.stream().limit(5).toList()) {
      lines
          .append("\nБан №")
          .append(b.id())
          .append(": ")
          .append(b.ruleReason())
          .append("\nСрок: ")
          .append(b.expiresAt() < 0 ? "бессрочно" : "<t:" + b.expiresAt() + ":R>")
          .append("\n");
      buttons.add(button("lwa:appeal:new:" + b.id(), "Обжаловать №" + b.id()));
    }
    if (bans.isEmpty()) lines.append("Действующих банов нет.");
    return body(cap(lines.toString()), buttons);
  }

  boolean interaction(DiscordInteraction i) {
    if (i.name().equals("мои-обращения") || i.name().equals("lwa:desk:mine")) {
      openFromInteraction(i);
      return true;
    }
    if (!i.name().startsWith("lwa:mine:")) return false;
    service
        .respond(
            i,
            i.name().startsWith("lwa:mine:")
                ? "{\"type\":6}"
                : JsonUtils.toJson(
                    Map.of(
                        "type",
                        4,
                        "data",
                        Map.of("content", "Обращения открыты в личном меню бота.", "flags", 64))))
        .thenRun(
            () ->
                plugin
                    .io()
                    .run(
                        () -> {
                          try {
                            if (i.name().startsWith("lwa:mine:file:")) {
                              String[] parts = i.name().split(":");
                              String kind = parts[3];
                              int id = Integer.parseInt(parts[4]);
                              int fileId = Integer.parseInt(parts[5]);
                              var store = plugin.getDiscordBridge().ticketDeskStore();
                              store
                                  .get(kind, id)
                                  .filter(t -> t.owner().equals(i.userId()))
                                  .orElseThrow();
                              var meta =
                                  store.files(kind, id).stream()
                                      .filter(
                                          file -> ((Number) file.get("id")).intValue() == fileId)
                                      .findFirst()
                                      .orElseThrow();
                              byte[] bytes = store.file(kind, id, fileId).orElseThrow();
                              @SuppressWarnings("unchecked")
                              var sent =
                                  (CompletableFuture<String>)
                                      service
                                          .getClass()
                                          .getMethod(
                                              "sendDirectAttachment",
                                              String.class,
                                              String.class,
                                              byte[].class)
                                          .invoke(
                                              service,
                                              i.userId(),
                                              meta.get("name").toString(),
                                              bytes);
                              sent.thenAccept(
                                  message ->
                                      service.editResponse(
                                          i,
                                          message == null
                                              ? "Не удалось отправить вложение."
                                              : "Вложение отправлено в личные сообщения."));
                              return;
                            } else if (i.name().startsWith("lwa:mine:history:")) {
                              var old = views.get(i.userId());
                              if (old == null || old.id <= 0) throw new IllegalArgumentException();
                              int page =
                                  Math.clamp(
                                      Integer.parseInt(
                                          i.name().substring("lwa:mine:history:".length())),
                                      0,
                                      10000);
                              views.put(i.userId(), new View(old.page, old.kind, old.id, page));
                            } else if (i.name().startsWith("lwa:mine:open:")) {
                              String[] p = i.name().split(":");
                              int id = Integer.parseInt(p[4]);
                              String kind = p[3];
                              var t =
                                  plugin
                                      .getDiscordBridge()
                                      .ticketDeskStore()
                                      .get(kind, id)
                                      .filter(row -> row.owner().equals(i.userId()))
                                      .orElseThrow();
                              views.put(
                                  i.userId(),
                                  new View(
                                      views.getOrDefault(i.userId(), new View(0, "", 0)).page,
                                      t.kind(),
                                      t.id()));
                            } else if (i.name().startsWith("lwa:mine:page:")) {
                              int page =
                                  Integer.parseInt(i.name().substring("lwa:mine:page:".length()));
                              views.put(i.userId(), new View(Math.clamp(page, 0, 10000), "", 0));
                            }
                            open(i.userId());
                          } catch (Exception error) {
                            service.editResponse(i, "Обращение недоступно.");
                          }
                        }));
    return true;
  }

  private void openFromInteraction(DiscordInteraction i) {
    service
        .respond(i, JsonUtils.toJson(Map.of("type", 5, "data", Map.of("flags", 64))))
        .thenAccept(
            acknowledged -> {
              if (!Boolean.TRUE.equals(acknowledged)) return;
              plugin
                  .io()
                  .run(
                      () -> {
                        if (!plugin.operations().allow("discord:" + i.userId())) {
                          service.editInteractionResponse(
                              i,
                              JsonUtils.toJson(
                                  body("Слишком много нажатий. Подождите минуту.", List.of())));
                          return;
                        }
                        open(i.userId())
                            .handle(
                                (opened, error) ->
                                    Boolean.TRUE.equals(opened) && error == null
                                        ? "Мои обращения открыты в личных сообщениях бота."
                                        : "Не удалось открыть личные сообщения. Разрешите сообщения"
                                              + " от участников этого сервера в настройках"
                                              + " конфиденциальности Discord и нажмите «Мои"
                                              + " обращения» ещё раз.")
                            .thenCompose(
                                message ->
                                    service.editInteractionResponse(
                                        i, JsonUtils.toJson(body(message, List.of()))));
                      });
            });
  }

  CompletableFuture<Boolean> open(String owner) {
    return CoreDiscordExtensions.managed(
        service, "openPrivateMenu", new Class<?>[] {String.class, String.class}, owner, "appeals");
  }

  static Map<String, Object> button(String id, String label) {
    return Map.of("type", 2, "style", 1, "custom_id", id, "label", label);
  }

  static Map<String, Object> body(String content, List<Map<String, Object>> controls) {
    var out = new LinkedHashMap<String, Object>();
    out.put("content", content);
    out.put("allowed_mentions", Map.of("parse", List.of()));
    out.put(
        "components",
        controls.isEmpty() ? List.of() : List.of(Map.of("type", 1, "components", controls)));
    return out;
  }

  private static String capMessage(String value) {
    return value.length() > 240 ? value.substring(0, 240) + "…" : value;
  }

  private static String cap(String value) {
    return value.length() > 1900 ? value.substring(0, 1900) : value;
  }

  private static String state(String state) {
    return switch (state) {
      case "OPEN", "ACTIVE", "CREATING" -> "Открыто";
      case "CLOSED" -> "Закрыто";
      case "CLOSING" -> "Закрывается";
      default -> "В обработке";
    };
  }

  @Override
  public void close() {
    for (String id : List.of("appeals", "newcase", "penalties", "staffcases"))
      try {
        service.getClass().getMethod("unregisterPrivateMenu", String.class).invoke(service, id);
      } catch (ReflectiveOperationException ignored) {
      }
    views.clear();
  }
}
