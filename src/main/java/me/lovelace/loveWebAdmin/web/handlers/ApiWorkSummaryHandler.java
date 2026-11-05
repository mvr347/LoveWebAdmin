package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.database.WorkSummaryStore;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.workflow.CaseWaitPolicy;

/** Permission-filtered aggregate; never applies or retries a moderation action. */
public final class ApiWorkSummaryHandler extends ApiHandlerSupport {
  public ApiWorkSummaryHandler(LoveWebAdmin plugin) {
    super(plugin);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    var session = requirePermission(req, resp, Permission.VIEW_STATS);
    if (session.isEmpty()) return;
    var rights = currentPermissions(session.get());
    boolean mine = !"all".equals(req.getParameter("scope"));
    try (var store = new WorkSummaryStore(plugin.getDataFolder().toPath())) {
      var result =
          new LinkedHashMap<>(
              store.summary(
                  session.get().adminUsername(),
                  mine,
                  rights,
                  System.currentTimeMillis() / 1000,
                  CaseWaitPolicy.from(plugin.getConfig()),
                  Objects.toString(req.getParameter("bucket"), ""),
                  Math.clamp(parseIntOrDefault(req.getParameter("offset"), 0), 0, 1000000)));
      var cards =
          new ArrayList<Map<String, Object>>((List<Map<String, Object>>) result.get("cards"));
      if (rights.contains(Permission.MANAGE_BANS) && rights.contains(Permission.VIEW_BANS)) {
        WorkSummaryStore.pendingCard(
                plugin.getModerationService().pending(),
                session.get().adminUsername(),
                mine,
                System.currentTimeMillis() / 1000,
                Objects.toString(req.getParameter("bucket"), ""),
                Math.clamp(parseIntOrDefault(req.getParameter("offset"), 0), 0, 1000000))
            .ifPresent(cards::addFirst);
      }
      if (rights.contains(Permission.VIEW_VESUVIO)) {
        try {
          Object wave =
              plugin.main().call(() -> plugin.getVesuvioBridge().call("getModerationWave")).join();
          if (wave instanceof List<?> entries) {
            long count =
                entries.stream()
                    .filter(e -> e instanceof Map<?, ?> m && "QUEUED".equals(m.get("state")))
                    .count();
            if (count > 0)
              cards.add(
                  Map.of(
                      "id",
                      "wave",
                      "title",
                      "Банволна",
                      "description",
                      "Наказания в очереди античита",
                      "count",
                      count,
                      "destination",
                      "wave",
                      "items",
                      List.of()));
          }
        } catch (RuntimeException ignored) {
        }
      }
      result.put("cards", cards);
      sendSuccess(resp, result);
    } catch (Exception error) {
      throw new IllegalStateException("Рабочая сводка недоступна", error);
    }
  }
}
