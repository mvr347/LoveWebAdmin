package me.lovelace.loveWebAdmin.preview;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validated {@code preview.discord} section. Any invalid part switches Discord delivery off as a whole
 * (empty category/channel/recipients) and is reported in {@link #problems()}; it never throws, so a typo
 * in a test-only setting cannot stop the real panel from starting.
 */
record PreviewDiscordConfig(String category, String channel, Set<String> recipients, List<String> problems) {

  static final int MAX_RECIPIENTS = 10;
  private static final String ID = "[0-9]{5,25}";

  static PreviewDiscordConfig parse(String category, String channel, List<?> allowedUserIds) {
    var problems = new ArrayList<String>();
    String categoryId = category == null ? "" : category.trim();
    String channelId = channel == null ? "" : channel.trim();
    if (!categoryId.isEmpty() && !categoryId.matches(ID))
      problems.add("category-id должен быть числовым ID (5–25 цифр), получено «" + categoryId + "»");
    if (!channelId.isEmpty() && !channelId.matches(ID))
      problems.add("channel-id должен быть числовым ID (5–25 цифр), получено «" + channelId + "»");

    if (!categoryId.isEmpty() && categoryId.equals(channelId))
      problems.add(
          "category-id и channel-id совпадают: у категории и текстового канала должны быть разные ID");
    var ids = new LinkedHashSet<String>();
    if (allowedUserIds != null)
      for (Object raw : allowedUserIds) {
        String id = raw == null ? "" : String.valueOf(raw).trim();
        if (id.isEmpty()) continue;
        if (!id.matches(ID)) problems.add("allowed-user-ids: «" + id + "» не числовой Discord ID (5–25 цифр)");
        else ids.add(id);
      }
    if ((!categoryId.isEmpty() || !channelId.isEmpty()) && ids.isEmpty())
      problems.add("Добавьте Discord ID хотя бы одного тестировщика в allowed-user-ids");
    if (ids.size() > MAX_RECIPIENTS)
      problems.add("allowed-user-ids: " + ids.size() + " ID, допускается не больше " + MAX_RECIPIENTS);

    if (!problems.isEmpty()) {
      problems.add("тестовая отправка в Discord отключена до исправления config.yml");
      return new PreviewDiscordConfig("", "", Set.of(), List.copyOf(problems));
    }
    return new PreviewDiscordConfig(categoryId, channelId, Set.copyOf(ids), List.of());
  }
}
