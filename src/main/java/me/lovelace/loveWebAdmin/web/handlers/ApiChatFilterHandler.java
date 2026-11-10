package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.integration.ChatFilterBridge;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;

/**
 * Фильтр чата (LoveChatFilter):
 *
 * GET  /api/chatfilter/status                  — установлен ли плагин и какой он версии API   [VIEW_CHATFILTER]
 * GET  /api/chatfilter/words?kind=bad|safe|ad  — слова вида (из игры и из config.yml)         [VIEW_CHATFILTER]
 * POST /api/chatfilter/words {kind, word}      — добавить слово                               [MANAGE_CHATFILTER]
 * POST /api/chatfilter/words/remove {kind, word} — убрать слово, добавленное из игры или панели [MANAGE_CHATFILTER]
 * POST /api/chatfilter/check {text}            — что сделает фильтр с текстом (ничего не отправляется) [VIEW_CHATFILTER]
 * GET  /api/chatfilter/settings                — настройки, доступные панели                  [VIEW_CHATFILTER]
 * POST /api/chatfilter/settings {key, value}   — изменить настройку                           [MANAGE_CHATFILTER]
 * POST /api/chatfilter/settings/reset {key}    — вернуть значение из config.yml               [MANAGE_CHATFILTER]
 * GET  /api/chatfilter/stats?days=30           — счётчики нарушений по дням и типам           [VIEW_CHATFILTER]
 */
public class ApiChatFilterHandler extends ApiHandlerSupport {

  private final ChatFilterBridge bridge;

  public ApiChatFilterHandler(LoveWebAdmin plugin) {
    super(plugin);
    this.bridge = plugin.getChatFilterBridge();
  }

  @FunctionalInterface
  private interface Action {
    Object run() throws IOException;
  }

  /** Runs one bridge call and maps refusals to readable HTTP answers; the administrator sees the reason. */
  private void answer(HttpServletResponse resp, Action action) throws IOException {
    try {
      sendSuccess(resp, action.run());
    } catch (IllegalArgumentException refused) {
      sendError(resp, 400, refused.getMessage());
    } catch (ChatFilterBridge.ChatFilterUnavailableException unavailable) {
      sendError(resp, 503, unavailable.getMessage());
    } catch (IllegalStateException failed) {
      plugin.getLogger().warning("Фильтр чата: " + failed.getMessage());
      sendError(resp, 500, "Не удалось выполнить запрос к LoveChatFilter");
    }
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    Optional<WebSession> session = requirePermission(req, resp, Permission.VIEW_CHATFILTER);
    if (session.isEmpty()) return;
    String path = req.getPathInfo() == null ? "" : req.getPathInfo();
    switch (path) {
      case "", "/", "/status" -> {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("available", bridge.available());
        sendSuccess(resp, status);
      }
      case "/words" -> answer(resp, () -> bridge.listWords(req.getParameter("kind")));
      case "/settings" -> answer(resp, bridge::settings);
      case "/stats" -> answer(resp, () -> bridge.statistics(Math.clamp(parseIntOrDefault(req.getParameter("days"), 30), 1, 90)));
      default -> sendError(resp, 404, "Не найдено");
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String path = req.getPathInfo() == null ? "" : req.getPathInfo();
    boolean read = path.equals("/check");
    Optional<WebSession> session =
        requirePermission(req, resp, read ? Permission.VIEW_CHATFILTER : Permission.MANAGE_CHATFILTER);
    if (session.isEmpty()) return;
    String admin = session.get().adminUsername();
    Map<String, Object> body = readJsonBody(req);
    switch (path) {
      case "/check" -> answer(resp, () -> bridge.checkText(stringOrNull(body.get("text"))));
      case "/words" ->
          answer(
              resp,
              () -> {
                String kind = stringOrNull(body.get("kind"));
                String stored = bridge.addWord(kind, stringOrNull(body.get("word")), admin);
                plugin.getLogManager().logWebAction(admin, "Фильтр чата: добавил слово «" + stored + "» (" + kind + ")");
                return Map.of("word", stored);
              });
      case "/words/remove" ->
          answer(
              resp,
              () -> {
                String kind = stringOrNull(body.get("kind"));
                String word = stringOrNull(body.get("word"));
                boolean removed = bridge.removeWord(kind, word);
                if (removed)
                  plugin.getLogManager().logWebAction(admin, "Фильтр чата: убрал слово «" + word + "» (" + kind + ")");
                return Map.of("removed", removed);
              });
      case "/settings" ->
          answer(
              resp,
              () -> {
                String key = stringOrNull(body.get("key"));
                String value = stringOrNull(body.get("value"));
                bridge.changeSetting(key, value);
                plugin.getLogManager().logWebAction(admin, "Фильтр чата: настройка " + key + " = " + value);
                return Map.of("key", key);
              });
      case "/settings/reset" ->
          answer(
              resp,
              () -> {
                String key = stringOrNull(body.get("key"));
                bridge.resetSetting(key);
                plugin.getLogManager().logWebAction(admin, "Фильтр чата: вернул настройку " + key + " к config.yml");
                return Map.of("key", key);
              });
      default -> sendError(resp, 404, "Не найдено");
    }
  }
}
