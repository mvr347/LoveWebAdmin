package me.lovelace.loveWebAdmin.utils;

import java.util.*;
import java.util.function.LongSupplier;

/** Bounded fixed-window limiter; unknown identities fail closed when capacity is exhausted. */
public final class SubmissionLimiter {
  private record Window(long end, int count) {}

  private final Map<String, Window> windows = new HashMap<>();
  private final LongSupplier clock;

  public SubmissionLimiter() {
    this(System::currentTimeMillis);
  }

  public SubmissionLimiter(LongSupplier clock) {
    this.clock = clock;
  }

  public synchronized boolean take(String identity) {
    long now = clock.getAsLong();
    Window w = windows.get(identity);
    if (w == null || w.end <= now) {
      windows.values().removeIf(v -> v.end <= now);
      if (w == null && windows.size() >= 4096) return false;
      windows.put(identity, new Window(now + 300000, 1));
      return true;
    }
    if (w.count >= 5) return false;
    windows.put(identity, new Window(w.end, w.count + 1));
    return true;
  }
}
