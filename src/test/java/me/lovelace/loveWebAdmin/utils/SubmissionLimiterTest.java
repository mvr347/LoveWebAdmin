package me.lovelace.loveWebAdmin.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SubmissionLimiterTest {
  @Test
  void windowExpiresAndDoesNotAffectOtherUsers() {
    AtomicLong time = new AtomicLong();
    var limiter = new SubmissionLimiter(time::get);
    for (int i = 0; i < 5; i++) assertTrue(limiter.take("a"));
    assertFalse(limiter.take("a"));
    assertTrue(limiter.take("b"));
    time.set(300001);
    assertTrue(limiter.take("a"));
  }

  @Test
  void boundedIdentityStorageFailsClosed() {
    var limiter = new SubmissionLimiter(() -> 0);
    for (int i = 0; i < 4096; i++) assertTrue(limiter.take("user" + i));
    assertFalse(limiter.take("new-user"));
    assertTrue(limiter.take("user0"));
  }
}
