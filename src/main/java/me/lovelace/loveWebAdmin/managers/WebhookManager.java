package me.lovelace.loveWebAdmin.managers;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebhookRecord;

/** Binary-compatible facade for the removed webhook feature. No network resources are allocated. */
@Deprecated
public class WebhookManager {
  public WebhookManager(LoveWebAdmin plugin) {}

  public void dispatch(String event, Map<String, Object> data) {}

  public CompletableFuture<Integer> sendTestPing(WebhookRecord hook) {
    return CompletableFuture.completedFuture(410);
  }
}
