package me.lovelace.loveWebAdmin.integration;

import java.util.concurrent.atomic.AtomicBoolean;
import me.lovelace.loveWebAdmin.LoveWebAdmin;

/** Durable outbox: appeal/unban delivery resumes when the provider or server comes back. */
public final class TrainingReviewSync implements AutoCloseable {
  private final LoveWebAdmin plugin;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final io.papermc.paper.threadedregions.scheduler.ScheduledTask timer;
  private volatile boolean closed;

  public TrainingReviewSync(LoveWebAdmin plugin) {
    this.plugin = plugin;
    timer =
        plugin
            .getServer()
            .getAsyncScheduler()
            .runAtFixedRate(
                plugin, ignored -> tick(), 5, 30, java.util.concurrent.TimeUnit.SECONDS);
  }

  private void tick() {
    if (closed || !busy.compareAndSet(false, true)) return;
    try {
      var bridge = plugin.getVesuvioBridge();
      if (!bridge.isAvailable()) return;
      Object capabilities = bridge.call("getCapabilities");
      if (!(capabilities instanceof java.util.Collection<?> list)
          || !list.contains("training-governance")) return;
      for (int id : plugin.getDatabaseManager().banReviews().pendingTrainingRevocations()) {
        if (closed) return;
        bridge.call(
            "revokeTrainingReference",
            new Class<?>[] {String.class, String.class, String.class},
            "lovewebadmin:ban:" + id,
            "Система",
            "Бан №" + id + " снят. Требуется повторный разбор обучающих меток.");
        if (!closed) plugin.getDatabaseManager().banReviews().acknowledgeTrainingRevocation(id);
      }
    } catch (Exception error) {
      if (!closed)
        plugin
            .getLogger()
            .warning("Пересмотр обучающих меток отложен: " + error.getClass().getSimpleName());
    } finally {
      busy.set(false);
    }
  }

  @Override
  public void close() {
    closed = true;
    timer.cancel();
  }
}
