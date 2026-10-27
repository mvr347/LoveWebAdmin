package me.lovelace.loveWebAdmin.web;

import java.security.SecureRandom;
import java.util.*;

/** Short-lived, bounded proof that the password was checked before the public 2FA endpoint. */
public final class PasswordChallenges {
  private record Challenge(String username, String ip, long expires, int attempts) {}

  private final java.util.function.LongSupplier clock;

  public PasswordChallenges() {
    this(System::currentTimeMillis);
  }

  PasswordChallenges(java.util.function.LongSupplier clock) {
    this.clock = clock;
  }

  private final Map<String, Challenge> pending = new LinkedHashMap<>();

  public synchronized String issue(String username, String ip) {
    long now = clock.getAsLong();
    pending.values().removeIf(c -> c.expires < now);
    while (pending.size() >= 512) pending.remove(pending.keySet().iterator().next());
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    pending.put(token, new Challenge(username.toLowerCase(Locale.ROOT), ip, now + 300000, 0));
    return token;
  }

  public synchronized boolean attempt(String token, String username, String ip) {
    var c = pending.get(token);
    if (c == null) return false;
    if (c.expires < clock.getAsLong() || c.attempts >= 5) {
      pending.remove(token);
      return false;
    }
    if (!c.username.equals(username.toLowerCase(Locale.ROOT)) || !Objects.equals(c.ip, ip))
      return false;
    pending.put(token, new Challenge(c.username, c.ip, c.expires, c.attempts + 1));
    return true;
  }

  public synchronized void complete(String token) {
    pending.remove(token);
  }
}
