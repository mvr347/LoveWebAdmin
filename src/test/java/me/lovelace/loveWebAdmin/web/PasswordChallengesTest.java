package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordChallengesTest {
  @Test
  void challengeIsBoundToAccountAndIpAndConsumedAfterSuccess() {
    var c = new PasswordChallenges();
    String token = c.issue("Ada", "192.0.2.1");
    assertFalse(c.attempt(token, "Other", "192.0.2.1"));
    assertFalse(c.attempt(token, "Ada", "192.0.2.2"));
    assertTrue(c.attempt(token, "ada", "192.0.2.1"));
    c.complete(token);
    assertFalse(c.attempt(token, "Ada", "192.0.2.1"));
  }

  @Test
  void expiresAndLimitsRetries() {
    var clock = new java.util.concurrent.atomic.AtomicLong();
    var c = new PasswordChallenges(clock::get);
    String token = c.issue("Ada", "ip");
    for (int i = 0; i < 5; i++) assertTrue(c.attempt(token, "Ada", "ip"));
    assertFalse(c.attempt(token, "Ada", "ip"));
    token = c.issue("Ada", "ip");
    clock.set(300001);
    assertFalse(c.attempt(token, "Ada", "ip"));
  }

  @Test
  void capacityEvictsOldestPendingChallenge() {
    var c = new PasswordChallenges();
    String old = c.issue("Ada", "ip");
    for (int i = 0; i < 512; i++) c.issue("Ada", "ip");
    assertFalse(c.attempt(old, "Ada", "ip"));
  }
}
