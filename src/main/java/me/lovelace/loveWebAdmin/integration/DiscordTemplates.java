package me.lovelace.loveWebAdmin.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable render snapshots; edits replace a file atomically before becoming visible. */
public final class DiscordTemplates {
  private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z]+)}");
  private static final Map<String, String> DEFAULTS =
      Map.of(
          "ban",
              "⛔ **Вы заблокированы на сервере**\n"
                  + "Игрок: {player}\n"
                  + "Причина: {reason}\n"
                  + "Источник: {source}\n"
                  + "Срок: {duration}\n"
                  + "Окончание: {expiry}\n"
                  + "Наказание: №{id}",
          "report",
              "Жалоба №{id} · {status}\n"
                  + "Игрок: {player}\n"
                  + "Заявитель: {reporter}\n"
                  + "Причина: {reason}\n"
                  + "Ответственный: {assignee}",
          "appeal",
              "Игрок **{player}** подал апелляцию №{id} на блокировку №{ban}.\nПричина: {reason}",
          "clarification", "Уточнение по вашей жалобе №{id}. Сотрудник: {actor}\n{text}");
  private static final Map<String, Set<String>> TOKENS =
      Map.of(
          "ban", Set.of("player", "reason", "source", "duration", "expiry", "id"),
          "report", Set.of("id", "status", "player", "reporter", "reason", "assignee"),
          "appeal", Set.of("id", "ban", "player", "reason"),
          "clarification", Set.of("id", "actor", "text"));
  private final Path file;
  private volatile Map<String, String> templates;

  public DiscordTemplates(Path file) throws IOException {
    this.file = file;
    var values = new HashMap<>(DEFAULTS);
    if (Files.exists(file)) {
      var yaml = new YamlConfiguration();
      try {
        yaml.load(file.toFile());
      } catch (org.bukkit.configuration.InvalidConfigurationException e) {
        throw new IOException("Некорректный discord-templates.yml", e);
      }
      for (String kind : DEFAULTS.keySet())
        if (yaml.contains(kind)) {
          String text = yaml.getString(kind);
          validate(kind, text);
          values.put(kind, text);
        }
      templates = Map.copyOf(values);
    } else {
      templates = Map.copyOf(values);
      persist(values);
    }
  }

  public Map<String, String> templates() {
    return templates;
  }

  public Map<String, Set<String>> tokens() {
    return TOKENS;
  }

  public static void validate(String kind, String text) {
    var allowed = TOKENS.get(kind);
    if (allowed == null)
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException("Неизвестный шаблон");
    if (text == null || text.isBlank() || text.length() > 1800)
      throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
          "Шаблон: от 1 до 1800 символов");
    var m = TOKEN.matcher(text);
    while (m.find())
      if (!allowed.contains(m.group(1)))
        throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
            "Неизвестное поле: " + m.group());
  }

  public String render(String kind, Map<String, String> values) {
    return preview(kind, templates.get(kind), values);
  }

  public static String preview(String kind, String text, Map<String, String> values) {
    validate(kind, text);
    var m = TOKEN.matcher(text);
    var out = new StringBuilder();
    while (m.find())
      m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "—")));
    m.appendTail(out);
    if (out.length() <= 1900) return out.toString();
    int end = 1899;
    if (Character.isHighSurrogate(out.charAt(end - 1))) end--;
    return out.substring(0, end) + "…";
  }

  public synchronized void save(String kind, String text) throws IOException {
    validate(kind, text);
    var values = new HashMap<>(templates);
    values.put(kind, text);
    persist(values);
    templates = Map.copyOf(values);
  }

  private void persist(Map<String, String> values) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    var yaml = new YamlConfiguration();
    for (String kind : new TreeSet<>(values.keySet())) yaml.set(kind, values.get(kind));
    Path temporary =
        Files.createTempFile(file.toAbsolutePath().getParent(), "discord-templates-", ".tmp");
    try {
      Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
      if (Files.exists(file))
        Files.copy(
            file,
            file.resolveSibling(file.getFileName() + ".bak"),
            StandardCopyOption.REPLACE_EXISTING);
      Files.move(
          temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
