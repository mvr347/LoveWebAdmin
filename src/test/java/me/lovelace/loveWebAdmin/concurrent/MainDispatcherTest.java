package me.lovelace.loveWebAdmin.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class MainDispatcherTest {
  MainDispatcher main;

  @BeforeEach
  void setup() {
    MockBukkit.mock();
    main = new MainDispatcher(MockBukkit.createMockPlugin());
  }

  @AfterEach
  void close() {
    try {
      if (main != null) main.close();
    } finally {
      MockBukkit.unmock();
    }
  }

  @Test
  void callsOnMainCompleteImmediately() {
    assertEquals(42, main.call(() -> 42).join());
  }

  @Test
  void backgroundCallOnlyRunsOnServerTick() {
    AtomicInteger calls = new AtomicInteger();
    var result = CompletableFuture.supplyAsync(() -> main.call(calls::incrementAndGet)).join();
    assertFalse(result.isDone());
    assertEquals(0, calls.get());
    MockBukkit.getMock().getScheduler().performOneTick();
    assertEquals(1, result.join());
  }

  @Test
  void shutdownFinishesPendingAndCancelsBukkitAction() {
    AtomicInteger calls = new AtomicInteger();
    var result = CompletableFuture.supplyAsync(() -> main.call(calls::incrementAndGet)).join();
    main.close();
    assertTrue(result.isCompletedExceptionally());
    MockBukkit.getMock().getScheduler().performOneTick();
    assertEquals(0, calls.get());
    assertTrue(main.call(() -> 1).isCompletedExceptionally());
  }

  @Test
  void queuedDeadlineDoesNotReportFailureAfterMutationHasStarted() {
    main.close();
    main =
        new MainDispatcher(
            MockBukkit.createMockPlugin("SlowMutation"), java.time.Duration.ofMillis(20));
    assertEquals(
        42,
        main.call(
                () -> {
                  try {
                    Thread.sleep(80);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return 42;
                })
            .join());
  }

  @Test
  void overflowIsRejectedWithoutExecutingMoreTasks() {
    var queued = new java.util.ArrayList<CompletableFuture<Integer>>();
    CompletableFuture.runAsync(
            () -> {
              for (int i = 0; i < 256; i++) queued.add(main.call(() -> 1));
            })
        .join();
    assertTrue(
        CompletableFuture.supplyAsync(() -> main.call(() -> 2)).join().isCompletedExceptionally());
    main.close();
    assertTrue(queued.stream().allMatch(CompletableFuture::isDone));
  }
}
