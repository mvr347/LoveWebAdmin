package me.lovelace.loveWebAdmin.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RunGuardTest {

    @Test
    void enterIsRefusedAfterStop() {
        RunGuard guard = new RunGuard();

        assertTrue(guard.stopAndAwait(1, TimeUnit.SECONDS));
        assertFalse(guard.enter());
    }

    @Test
    void stopWaitsUntilRunningPieceExits() throws Exception {
        RunGuard guard = new RunGuard();
        assertTrue(guard.enter());

        CompletableFuture<Boolean> stopped = new CompletableFuture<>();
        Thread stopper = new Thread(() -> stopped.complete(guard.stopAndAwait(5, TimeUnit.SECONDS)));
        stopper.start();

        // The piece is still running, so stop cannot have returned yet.
        Thread.sleep(100);
        assertFalse(stopped.isDone());

        guard.exit();
        assertTrue(stopped.get(5, TimeUnit.SECONDS));
        stopper.join();
    }

    @Test
    void stopTimesOutWhileAPieceIsStillRunning() {
        RunGuard guard = new RunGuard();
        assertTrue(guard.enter());

        assertFalse(guard.stopAndAwait(50, TimeUnit.MILLISECONDS));

        guard.exit();
    }

    @Test
    void reopenAllowsEnterAgain() {
        RunGuard guard = new RunGuard();
        assertTrue(guard.stopAndAwait(1, TimeUnit.SECONDS));

        guard.reopen();

        assertTrue(guard.enter());
        guard.exit();
    }

    @Test
    void concurrentEnterAndExitLeaveNothingRunning() throws Exception {
        RunGuard guard = new RunGuard();
        AtomicInteger refused = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 1_000; i++) {
                    if (!guard.enter()) refused.incrementAndGet();
                    else guard.exit();
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();

        assertEquals(0, refused.get());
        assertTrue(guard.stopAndAwait(1, TimeUnit.SECONDS));
    }
}
