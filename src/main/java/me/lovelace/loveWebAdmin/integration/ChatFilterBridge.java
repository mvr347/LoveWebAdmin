package me.lovelace.loveWebAdmin.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.Bukkit;

/**
 * Reflection-only bridge to LoveChatFilter's public API ({@code me.lovelace.lovechatfilter.api.LoveChatFilterAPI}),
 * found through Bukkit's ServicesManager on every call (LoveChatFilter may enable after the panel or be reloaded).
 *
 * <p>There is no compile-time dependency, so the panel keeps working without LoveChatFilter or with an older one:
 * a missing plugin or a method it does not have becomes {@link ChatFilterUnavailableException}, which the handler
 * turns into a readable answer. The API uses plain Java types only, so results go straight to JSON.</p>
 *
 * <p>The API must be called on the server main thread (it rebuilds filters and reads the live config), so
 * {@code mainThread} is the panel's main-thread dispatcher.</p>
 */
public final class ChatFilterBridge {
  static final String API_CLASS_NAME = "me.lovelace.lovechatfilter.api.LoveChatFilterAPI";

  /** LoveChatFilter is missing, not started yet, or too old for the requested operation. */
  public static final class ChatFilterUnavailableException extends RuntimeException {
    public ChatFilterUnavailableException(String message) {
      super(message);
    }

    public ChatFilterUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Runs a task on the main thread and returns its result; the panel passes {@code plugin.main()::call}. */
  public interface MainThread {
    Object run(Supplier<Object> task);
  }

  /** The registered API object and the interface it was registered under (methods are looked up on the interface). */
  public record Binding(Object provider, Class<?> type) {}

  /** Finds the registered API; replaceable in tests. */
  public interface Locator {
    Binding find();
  }

  private final MainThread mainThread;
  private final Locator locator;

  public ChatFilterBridge(MainThread mainThread) {
    this(mainThread, ChatFilterBridge::locateInServices);
  }

  ChatFilterBridge(MainThread mainThread, Locator locator) {
    this.mainThread = mainThread;
    this.locator = locator;
  }

  private static Binding locateInServices() {
    for (Class<?> type : Bukkit.getServicesManager().getKnownServices()) {
      if (!type.getName().equals(API_CLASS_NAME)) continue;
      var registration = Bukkit.getServicesManager().getRegistration(type);
      if (registration != null && registration.getPlugin().isEnabled())
        return new Binding(registration.getProvider(), type);
    }
    return null;
  }

  public boolean available() {
    return locator.find() != null;
  }

  // ---- operations ----

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> listWords(String kind) {
    return (List<Map<String, Object>>) call("listWords", new Class<?>[] {String.class}, kind);
  }

  public String addWord(String kind, String word, String addedBy) {
    return (String) call("addWord", new Class<?>[] {String.class, String.class, String.class}, kind, word, addedBy);
  }

  public boolean removeWord(String kind, String word) {
    return Boolean.TRUE.equals(call("removeWord", new Class<?>[] {String.class, String.class}, kind, word));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> checkText(String text) {
    return (Map<String, Object>) call("checkText", new Class<?>[] {String.class}, text);
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> settings() {
    return (List<Map<String, Object>>) call("settings", new Class<?>[0]);
  }

  public void changeSetting(String key, String value) {
    call("changeSetting", new Class<?>[] {String.class, String.class}, key, value);
  }

  public void resetSetting(String key) {
    call("resetSetting", new Class<?>[] {String.class}, key);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> statistics(int days) {
    return (Map<String, Object>) call("statistics", new Class<?>[] {int.class}, days);
  }

  /**
   * Calls one API method on the main thread. A refusal from LoveChatFilter ({@link IllegalArgumentException}) is
   * rethrown as it is, because its text is meant for the administrator.
   */
  Object call(String method, Class<?>[] types, Object... args) {
    Binding binding = locator.find();
    if (binding == null) throw new ChatFilterUnavailableException("LoveChatFilter не установлен или ещё не загружен на сервере");
    Object api = binding.provider();
    Method target;
    try {
      // The method comes from the registered interface: it is public even when the provider's own class is not.
      target = binding.type().getMethod(method, types);
    } catch (NoSuchMethodException e) {
      throw new ChatFilterUnavailableException(
          "Обновите LoveChatFilter: установленная версия не поддерживает управление из панели (нет метода " + method + ")", e);
    }
    return mainThread.run(
        () -> {
          try {
            return target.invoke(api, args);
          } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException invalid) throw invalid;
            if (cause instanceof UnsupportedOperationException)
              throw new ChatFilterUnavailableException(
                  "Обновите LoveChatFilter: установленная версия не поддерживает это действие", cause);
            throw new IllegalStateException("Ошибка вызова LoveChatFilter (" + method + "): " + cause, cause);
          } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
