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

  private record View(int page, String kind, int id, int history, String filter) {
    View(int page, String kind, int id) {
      this(page, kind, id, 0, "active");
    }

    View(int page, String kind, int id, int history) {
      this(page, kind, id, history, "active");
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
    register("newcase", "Создать обращение", this::newCase);
    register("penalties", "Мои наказания", this::penalties);
    register("loginhelp", "Не могу войти", new DiscordLoginHelp(plugin, service)::render);
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
              "waiting",
              "Нужен ваш ответ",
              (java.util.function.Predicate<String>) this::hasWaiting,
              (Function<String, Map<String, Object>>)
                  owner -> {
                    views.put(owner, new View(0, "", 0, 0, "waiting"));
                    return mine(owner);
                  });
    } catch (ReflectiveOperationException unavailable) {
      plugin.getLogger().warning("Не удалось подключить раздел ожидающих ответа обращений");
    }
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

  private boolean hasWaiting(String owner) {
    try {
      return !plugin.getDiscordBridge().ticketDeskStore().owned(owner, "waiting", 0).isEmpty();
    } catch (java.sql.SQLException unavailable) {
      return false;
    }
  }

  private Map<String, Object> newCase(String owner) {
    var context = new DiscordPlayerContext(plugin, service);
    Optional<DiscordPlayerContext.Identity> identity;
    try {
      identity = context.linked(owner);
    } catch (IllegalArgumentException missing) {
      return body(missing.getMessage(), List.of(button("lc:menu:account", "Мой аккаунт")));
    }
    boolean canAppeal = identity.isEmpty() || !context.bans(identity.get()).isEmpty();
    var buttons =
        new ArrayList<Map<String, Object>>(
            List.of(
                button("lwa:desk:new:report", "Жалоба"),
                canAppeal
                    ? button("lwa:desk:new:appeal", "Апелляция")
                    : button("lc:menu:loginhelp", "Не могу войти")));
    try {
      var topics = plugin.getDiscordBridge().ticketDesk().supportTopics();
      if (topics.enabled()) buttons.add(button("lwa:desk:new:support", "Поддержка"));
      for (var draft : plugin.getDiscordBridge().ticketDeskStore().drafts(owner)) {
        // A support draft is already at the review step; it is confirmed from its own message.
        if (draft.kind().equals("support")) continue;
        int step = ((Number) draft.data().getOrDefault("_step", 3)).intValue();
        buttons.add(
            button(
                (step == 1 ? "lwa:desk:identity-edit:" : "lwa:desk:details:") + draft.id(),
                "Продолжить "
                    + (draft.kind().equals("report") ? "жалобу" : "апелляцию")
                    + " · шаг "
                    + Math.min(3, step)));
      }
    } catch (java.sql.SQLException error) {
      throw new IllegalStateException("Не удалось загрузить черновики", error);
    }
    return body(
        "**Новое обращение**\n"
            + "Три шага: участник → описание и доказательства → проверка.\n"
            + "Форму и результат видите только вы и допущенные сотрудники.\n"
            + "Незавершённый черновик можно продолжить здесь в течение 10 минут.",
        buttons);
  }

  private Map<String, Object> mine(String owner) {
    try {
      View view = views.getOrDefault(owner, new View(0, "", 0));
      var store = plugin.getDiscordBridge().ticketDeskStore();
      if (view.id > 0) {
        var t =
            store.get(view.kind, view.id).filter(row -> row.owner().equals(owner)).orElseThrow();
        var work = plugin.moderation().work(t.kind(), t.id());
        var lines =
            new StringBuilder(
                "**"
                    + DiscordTicketDesk.caseLabel(t.kind())
                    + " №"
                    + t.id()
                    + "**\nСтатус: "
                    + caseState(t, work)
                    + "\n");
        String workState = Objects.toString(work.get("state"), "");
        String assigned = Objects.toString(work.get("assignee"), "");
        lines
            .append("Ответственный: ")
            .append(assigned.isBlank() ? "Ожидается назначение" : assigned)
            .append("\n");
        if (!t.state().equals("CLOSED"))
          lines
              .append("Далее: ")
              .append(
                  workState.equals("WAITING")
                      ? "ответьте сотруднику — ожидается ваш ответ."
                      : "можно дополнить обращение; сотрудник увидит сообщение.")
              .append("\n");
        var controls = new ArrayList<Map<String, Object>>();
        if (t.state().equals("OPEN") && !t.channel().isBlank()) {
          controls.add(button("lwa:mine:reply:" + t.kind() + ":" + t.id(), "Ответить"));
          controls.add(
              Map.of(
                  "type",
                  2,
                  "style",
                  4,
                  "label",
                  "Отозвать обращение",
                  "custom_id",
                  "lwa:desk:form:close:" + t.kind() + ":" + t.id()));
        } else if (t.state().equals("OPEN") && t.channel().isBlank()) {
          String issue = store.creationError(t.kind(), t.id());
          lines
              .append("Канал: ")
              .append(
                  issue.isBlank()
                      ? (store.creationStarted(t.kind(), t.id())
                          ? "Результат создания не подтверждён; проверьте канал или обратитесь к"
                                + " сотруднику"
                          : "Ожидает создания")
                      : issue)
              .append("\n");
          controls.add(
              button(
                  "lwa:mine:retry:" + t.kind() + ":" + t.id(),
                  store.creationStarted(t.kind(), t.id())
                      ? "Проверить создание канала"
                      : "Повторить создание канала"));
        }
        if (t.channel() != null && !t.channel().isBlank() && !t.state().equals("CLOSED"))
          controls.add(
              Map.of(
                  "type",
                  2,
                  "style",
                  5,
                  "label",
                  "Переписка и файлы",
                  "url",
                  "https://discord.com/channels/"
                      + CoreDiscordExtensions.guild(service)
                      + "/"
                      + t.channel()));
        store
            .latestMessage(t.kind(), t.id())
            .ifPresent(
                message ->
                    lines
                        .append("\nПоследнее сообщение: ")
                        .append(capMessage(message))
                        .append("\n"));
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
      var tickets = store.owned(owner, view.filter, view.page * 5);
      var buttons = new ArrayList<Map<String, Object>>();
      var lines =
          new StringBuilder(
              "**Мои обращения**\n"
                  + (view.filter.equals("closed")
                      ? "Завершённые"
                      : view.filter.equals("waiting") ? "Нужен ваш ответ" : "Активные")
                  + " · Страница "
                  + (view.page + 1)
                  + "\n");
      for (var t : tickets.stream().limit(5).toList()) {
        String label = DiscordTicketDesk.caseLabel(t.kind()) + " №" + t.id();
        var work = plugin.moderation().work(t.kind(), t.id());
        String status = caseState(t, work);
        lines.append("\n").append(label).append(" · ").append(status);
        buttons.add(button("lwa:mine:open:" + t.kind() + ":" + t.id(), label));
      }
      if (tickets.isEmpty()) lines.append("\nОбращений пока нет.");
      var result = body(lines.toString(), buttons);
      var rows = new ArrayList<Object>((List<?>) result.get("components"));
      var navigation = new ArrayList<Map<String, Object>>();
      if (view.page > 0) navigation.add(button("lwa:mine:page:" + (view.page - 1), "Предыдущая"));
      if (tickets.size() > 5)
        navigation.add(button("lwa:mine:page:" + (view.page + 1), "Следующая"));
      navigation.add(
          button(
              "lwa:mine:filter:" + (view.filter.equals("closed") ? "active" : "closed"),
              view.filter.equals("closed") ? "Активные" : "Завершённые"));
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
                DiscordTicketDesk.permission(ticket.kind()))
            .isEmpty()) continue;
        if (!assignee.equals(admin.username())
            || ticket.channel().isBlank()
            || ticket.state().equals("CLOSED")) continue;
        String label =
            DiscordTicketDesk.caseLabel(ticket.kind()) + " №" + ticket.id();
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
    var context = new DiscordPlayerContext(plugin, service);
    Optional<DiscordPlayerContext.Identity> identity;
    try {
      identity = context.linked(owner);
    } catch (IllegalArgumentException missing) {
      return body(missing.getMessage(), List.of(button("lc:menu:account", "Мой аккаунт")));
    }
    if (identity.isEmpty())
      return body(
          "**Мои наказания**\n"
              + "Привяжите аккаунт, чтобы бот нашёл ваши наказания. Номер вводить не нужно.",
          List.of(button("lc:menu:account", "Привязать аккаунт")));
    var bans = context.bans(identity.get());
    var lines = new StringBuilder("**Мои наказания**\n");
    var buttons = new ArrayList<Map<String, Object>>();
    for (var b : bans.stream().limit(5).toList()) {
      lines
          .append("\nБан №")
          .append(b.id())
          .append(": ")
          .append(b.ruleReason())
          .append("\nСрок: ")
          .append(b.expiresAt() <= 0 ? "бессрочно" : "<t:" + b.expiresAt() + ":R>")
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
    if (i.name().startsWith("lwa:mine:reply:")) {
      var field =
          Map.<String, Object>of(
              "type",
              4,
              "custom_id",
              "text",
              "label",
              "Сообщение сотруднику",
              "style",
              2,
              "required",
              true,
              "min_length",
              5,
              "max_length",
              1800);
      var modal =
          Map.of(
              "custom_id",
              i.name().replace(":reply:", ":send:"),
              "title",
              "Ответить по обращению",
              "components",
              List.of(Map.of("type", 1, "components", List.of(field))));
      service.respond(i, JsonUtils.toJson(Map.of("type", 9, "data", modal)));
      return true;
    }
    service
        .respond(
            i,
            i.name().startsWith("lwa:mine:send:")
                ? "{\"type\":5,\"data\":{\"flags\":64}}"
                : "{\"type\":6}")
        .thenAccept(
            acknowledged -> {
              if (!Boolean.TRUE.equals(acknowledged)) return;
              plugin
                  .io()
                  .run(
                      () -> {
                        try {
                          if (!plugin.operations().allow("discord:" + i.userId()))
                            throw new IllegalArgumentException(
                                "Слишком много нажатий. Подождите минуту.");
                          if (i.name().startsWith("lwa:mine:filter:")) {
                            String filter = i.name().substring("lwa:mine:filter:".length());
                            if (!Set.of("active", "closed", "waiting").contains(filter))
                              throw new IllegalArgumentException();
                            views.put(i.userId(), new View(0, "", 0, 0, filter));
                          } else if (i.name().startsWith("lwa:mine:retry:")) {
                            String[] parts = i.name().split(":");
                            var ticket =
                                plugin
                                    .getDiscordBridge()
                                    .ticketDeskStore()
                                    .get(parts[3], Integer.parseInt(parts[4]))
                                    .filter(t -> t.owner().equals(i.userId()))
                                    .orElseThrow();
                            plugin
                                .getDiscordBridge()
                                .ticketDesk()
                                .create(ticket.kind(), ticket.id(), i.userId())
                                .handle((channel, error) -> null)
                                .get(50, TimeUnit.SECONDS);
                          } else if (i.name().startsWith("lwa:mine:send:")) {
                            String[] parts = i.name().split(":");
                            plugin
                                .getDiscordBridge()
                                .ticketDesk()
                                .replyFromOwner(
                                    parts[3],
                                    Integer.parseInt(parts[4]),
                                    i.userId(),
                                    i.id(),
                                    i.values().getOrDefault("text", ""));
                          } else if (i.name().startsWith("lwa:mine:file:")) {
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
                                    .filter(file -> ((Number) file.get("id")).intValue() == fileId)
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
                            views.put(
                                i.userId(), new View(old.page, old.kind, old.id, page, old.filter));
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
                                    t.id(),
                                    0,
                                    views.getOrDefault(i.userId(), new View(0, "", 0)).filter));
                          } else if (i.name().startsWith("lwa:mine:page:")) {
                            int page =
                                Integer.parseInt(i.name().substring("lwa:mine:page:".length()));
                            var old = views.getOrDefault(i.userId(), new View(0, "", 0));
                            views.put(
                                i.userId(),
                                new View(Math.clamp(page, 0, 10000), "", 0, 0, old.filter));
                          }
                          open(i.userId())
                              .thenAccept(
                                  ok -> {
                                    if (i.name().startsWith("lwa:mine:send:"))
                                      service.editResponse(
                                          i,
                                          Boolean.TRUE.equals(ok)
                                              ? "Сообщение отправлено сотруднику."
                                              : "Сообщение отправлено. Откройте личное меню ещё"
                                                  + " раз.");
                                  });
                        } catch (Exception error) {
                          service.editResponse(
                              i,
                              error instanceof IllegalArgumentException
                                  ? error.getMessage()
                                  : "Не удалось выполнить действие. Проверьте переписку перед"
                                      + " повтором.");
                        }
                      });
            });
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
                          DiscordTicketDesk.finishDeferred(
                              plugin,
                              service,
                              i,
                              body("Слишком много нажатий. Подождите минуту.", List.of()));
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
                                    DiscordTicketDesk.finishDeferred(
                                        plugin,
                                        service,
                                        i,
                                        Map.of(
                                            "content",
                                            message,
                                            "components",
                                            List.of(),
                                            "allowed_mentions",
                                            Map.of("parse", List.of()))));
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
    // Discord allows at most five buttons in a row and five rows in a message.
    var rows = new ArrayList<Map<String, Object>>();
    for (int from = 0; from < controls.size() && rows.size() < 5; from += 5)
      rows.add(
          Map.of(
              "type",
              1,
              "components",
              List.copyOf(controls.subList(from, Math.min(controls.size(), from + 5)))));
    out.put("components", rows);
    return DiscordCards.decorate(
        out,
        content.contains("Закрыто")
            ? DiscordCards.GRAY
            : content.contains("ожидается ваш ответ") ? DiscordCards.AMBER : DiscordCards.BLUE);
  }

  private static String capMessage(String value) {
    return value.length() > 240 ? DiscordCards.cap(value, 240) + "…" : value;
  }

  private static String cap(String value) {
    return DiscordCards.cap(value, 1900);
  }

  private static String caseState(
      me.lovelace.loveWebAdmin.database.TicketDeskStore.Ticket ticket, Map<String, Object> work) {
    if (ticket.state().equals("CLOSED")) return "Завершено";
    if (Set.of("CLOSING", "ARCHIVED").contains(ticket.state())) return "Сохраняется история";
    return switch (Objects.toString(work.get("state"), "")) {
      case "WAITING" -> "Нужен ваш ответ";
      case "IN_PROGRESS" -> "В работе";
      case "CLOSED" -> "Рассмотрение завершено";
      default -> "Ожидает сотрудника";
    };
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
    for (String id :
        List.of("appeals", "newcase", "penalties", "staffcases", "waiting", "loginhelp"))
      try {
        service.getClass().getMethod("unregisterPrivateMenu", String.class).invoke(service, id);
      } catch (ReflectiveOperationException ignored) {
      }
    views.clear();
  }
}
