package me.lovelace.loveWebAdmin.concurrent;

import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns every pending main-thread request, including timeout and shutdown. */
public final class MainDispatcher implements AutoCloseable {
  private final JavaPlugin plugin;
  private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
  private final Semaphore admission = new Semaphore(256);
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("lwa-main-timeouts").factory());
  private volatile boolean closed;
  private final java.time.Duration timeout;

  public MainDispatcher(JavaPlugin plugin) {
    this(plugin, java.time.Duration.ofSeconds(5));
  }

  MainDispatcher(JavaPlugin plugin, java.time.Duration timeout) {
    this.plugin = plugin;
    this.timeout = timeout;
  }

  public synchronized <T> CompletableFuture<T> call(Supplier<T> action) {
    if (closed || !admission.tryAcquire())
      return CompletableFuture.failedFuture(
          new RejectedExecutionException("Сервер занят или останавливается"));
    CompletableFuture<T> result = new CompletableFuture<>();
    pending.add(result);
    boolean direct=Bukkit.isPrimaryThread();
    var phase = new java.util.concurrent.atomic.AtomicInteger(direct?1:0); // queued / running / finished
    var expiry = direct ? null :
        timer.schedule(
            () -> {
              if (phase.compareAndSet(0, 2))
                result.completeExceptionally(
                    new TimeoutException("Главный поток не принял запрос вовремя"));
            },
            timeout.toNanos(),
            TimeUnit.NANOSECONDS);
    result.whenComplete(
        (v, e) -> {
          phase.set(2);
          if(expiry!=null)expiry.cancel(false);
          pending.remove(result);
          admission.release();
        });
    Runnable invoke =
        () -> {
          if ((!direct&&!phase.compareAndSet(0, 1)) || result.isDone()) return;
          if(expiry!=null)expiry.cancel(false);
          try {
            result.complete(action.get());
          } catch (Throwable e) {
            result.completeExceptionally(e);
          }
        };
    try {
      if (direct) invoke.run();
      else {
        var task = Bukkit.getScheduler().runTask(plugin, invoke);
        result.whenComplete(
            (v, e) -> {
              if (e != null) task.cancel();
            });
      }
    } catch (Throwable e) {
      result.completeExceptionally(e);
    }
    return result;
  }

  public boolean isClosed() {
    return closed;
  }

  @Override
  public synchronized void close() {
    closed = true;
    pending.forEach(f -> f.completeExceptionally(new CancellationException("Плагин отключён")));
    timer.shutdownNow();
  }
}
