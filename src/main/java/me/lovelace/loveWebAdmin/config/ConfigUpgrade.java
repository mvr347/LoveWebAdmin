package me.lovelace.loveWebAdmin.config;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Merge explicit user values into the commented schema, with backup and atomic replacement. */
public final class ConfigUpgrade {
  public record Result(boolean changed, Path backup) {}

  public static Result upgrade(Path file, String template) throws Exception {
    var defaults = new YamlConfiguration();
    defaults.loadFromString(template);
    if (!Files.exists(file)) {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, template, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      return new Result(true, null);
    }
    byte[] original = Files.readAllBytes(file);
    var source = new YamlConfiguration();
    try {
      source.loadFromString(new String(original, StandardCharsets.UTF_8));
    } catch (org.bukkit.configuration.InvalidConfigurationException error) {
      throw new IllegalArgumentException(
          "Некорректный YAML в config.yml: проверьте отступы. Исходный файл не изменён");
    }
    int version = source.getInt("config-version", 0), target = defaults.getInt("config-version", 0);
    if (version > target) return new Result(false, null);
    boolean missing = version < target;
    for (String key : defaults.getKeys(true)) {
      if (defaults.isConfigurationSection(key)) {
        if (source.isSet(key) && !source.isConfigurationSection(key))
          throw new IllegalArgumentException(
              "В config.yml раздел «" + key + "» должен быть блоком настроек");
      } else {
        if (source.isConfigurationSection(key))
          throw new IllegalArgumentException(
              "В config.yml параметр «" + key + "» не должен быть разделом");
        if (!source.isSet(key)) missing = true;
      }
    }
    if (!missing) return new Result(false, null);
    for (String key : source.getKeys(true)) {
      if (source.isConfigurationSection(key)) continue;
      defaults.set(key, source.get(key));
      var comments = source.getComments(key);
      if (!comments.isEmpty()) {
        var combined = new ArrayList<>(defaults.getComments(key));
        for (String comment : comments) if (!combined.contains(comment)) combined.add(comment);
        defaults.setComments(key, combined);
      }
      if (!source.getInlineComments(key).isEmpty())
        defaults.setInlineComments(key, source.getInlineComments(key));
    }
    defaults.set("config-version", target);
    if (defaults.isSet("discord.notifications.escalation"))
      defaults.setComments(
          "discord.notifications.escalation",
          List.of(
              "Сохранено для совместимости. Актуальные сроки: discord.tickets.waiting; руководство: discord.tickets.supervisor-role-ids."));
    String migrated = defaults.saveToString();
    var validated = new YamlConfiguration();
    validated.loadFromString(migrated);
    Path backup =
        file.resolveSibling(
            file.getFileName()
                + ".backup-"
                + java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                + "-"
                + UUID.randomUUID().toString().substring(0, 8));
    Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
    Files.copy(file, backup);
    try {
      Files.writeString(temporary, migrated, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return new Result(true, backup);
  }
}
