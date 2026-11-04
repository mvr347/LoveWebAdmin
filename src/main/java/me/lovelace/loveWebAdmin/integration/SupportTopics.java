package me.lovelace.loveWebAdmin.integration;

import java.util.*;

/**
 * Validated support topics from config. A broken list disables support with a reason instead of
 * breaking the plugin: Discord rejects a select menu with duplicate or oversized options as a
 * whole, so it is better to refuse early than to publish a panel that cannot be used.
 */
record SupportTopics(boolean enabled, List<Topic> topics, String problem) {
  record Topic(String id, String label, String description) {}

  static final int MAX_TOPICS = 25;

  static SupportTopics disabled() {
    return new SupportTopics(false, List.of(), "");
  }

  static SupportTopics parse(boolean enabled, List<? extends Map<?, ?>> rows) {
    if (!enabled) return disabled();
    if (rows == null || rows.isEmpty())
      return new SupportTopics(false, List.of(), "Не заданы темы поддержки (support.topics)");
    if (rows.size() > MAX_TOPICS)
      return new SupportTopics(
          false, List.of(), "Тем поддержки больше " + MAX_TOPICS + " (ограничение Discord)");
    var topics = new ArrayList<Topic>();
    var seen = new HashSet<String>();
    for (var row : rows) {
      String id = text(row.get("id")), label = text(row.get("label"));
      String description = text(row.get("description"));
      if (!id.matches("[a-z0-9_-]{1,40}"))
        return new SupportTopics(
            false, List.of(), "Тема «" + id + "»: id латиницей [a-z0-9_-], до 40 символов");
      if (!seen.add(id))
        return new SupportTopics(false, List.of(), "Повторяется id темы поддержки: " + id);
      if (label.isEmpty() || label.length() > 100)
        return new SupportTopics(
            false, List.of(), "Тема «" + id + "»: название от 1 до 100 символов");
      if (description.length() > 100)
        return new SupportTopics(
            false, List.of(), "Тема «" + id + "»: описание не длиннее 100 символов");
      topics.add(new Topic(id, label, description));
    }
    return new SupportTopics(true, List.copyOf(topics), "");
  }

  Optional<Topic> find(String id) {
    return topics.stream().filter(topic -> topic.id().equals(id)).findFirst();
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString().trim();
  }
}
