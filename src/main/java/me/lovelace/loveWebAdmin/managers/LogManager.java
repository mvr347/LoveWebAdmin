package me.lovelace.loveWebAdmin.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.LogEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Перехватывает вывод консоли и пишет в БД, держит ring buffer последних 200 записей в памяти.
 */
public class LogManager {

    private static final int RING_BUFFER_SIZE = 200;
    /** Hard cap on lines waiting for the database; beyond it new lines are counted and dropped. */
    private static final int MAX_PENDING = 20_000;
    private static final int MAX_PER_FLUSH = 2_000;

    private final LoveWebAdmin plugin;
    private final ConcurrentLinkedQueue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    // ConcurrentLinkedQueue#size() walks the whole queue, so the length is tracked separately.
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final ThreadLocal<Boolean> flushing = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Deque<LogEntry> ringBuffer = new ArrayDeque<>(RING_BUFFER_SIZE);
    private Handler logHandler;
    private ScheduledTask flushTask;

    public LogManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public void startCapture() {
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() == null || flushing.get()) return;
                if (pendingCount.get() >= MAX_PENDING) {
                    dropped.incrementAndGet();
                    return;
                }
                pendingMessages.add(getFormatter() != null ? getFormatter().formatMessage(record) : record.getMessage());
                pendingCount.incrementAndGet();
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        java.util.logging.Logger.getLogger("").addHandler(logHandler);

        flushTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> flushToDatabase(), 3, 3, TimeUnit.SECONDS);
    }

    public void stopCapture() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (logHandler != null) {
            java.util.logging.Logger.getLogger("").removeHandler(logHandler);
            logHandler = null;
        }
        // Each flush is capped, so drain in rounds (bounded, in case the database keeps failing).
        for (int round = 0; round < 10 && pendingCount.get() > 0; round++) {
            flushToDatabase();
        }
    }

    /**
     * Writes at most {@link #MAX_PER_FLUSH} queued lines in one transaction. The old loop drained the
     * queue until empty, one autocommit INSERT per line: when the insert failed, the warning it logged
     * went straight back through this handler into the queue, so the loop never ended (and a log storm
     * outran the per-line inserts and grew the queue without a bound).
     */
    private void flushToDatabase() {
        List<String> batch = new ArrayList<>(Math.min(Math.max(pendingCount.get(), 1), MAX_PER_FLUSH));
        String message;
        while (batch.size() < MAX_PER_FLUSH && (message = pendingMessages.poll()) != null) {
            pendingCount.decrementAndGet();
            batch.add(message);
        }
        int lost = dropped.getAndSet(0);
        if (lost > 0) {
            batch.add("[LoveWebAdmin] Log queue overflow, dropped lines: " + lost);
        }
        if (batch.isEmpty()) {
            return;
        }

        // Anything this thread logs while writing (e.g. a database error) must not re-enter the queue.
        flushing.set(Boolean.TRUE);
        try {
            plugin.getDatabaseManager().saveServerLogs(batch);
            long now = System.currentTimeMillis() / 1000;
            // The ring buffer keeps only the newest lines, so older ones from a big batch are skipped.
            for (int i = Math.max(0, batch.size() - RING_BUFFER_SIZE); i < batch.size(); i++) {
                addToRingBuffer(new LogEntry(0, "SERVER", "CONSOLE", batch.get(i), now));
            }
            int keepCount = plugin.getConfig().getInt("logs.keep-count", 5000);
            plugin.getDatabaseManager().trimServerLogs(keepCount);
        } finally {
            flushing.set(Boolean.FALSE);
        }
    }

    private synchronized void addToRingBuffer(LogEntry entry) {
        ringBuffer.addFirst(entry);
        while (ringBuffer.size() > RING_BUFFER_SIZE) {
            ringBuffer.removeLast();
        }
    }

    public synchronized List<LogEntry> getRecentServerLogs() {
        return Collections.unmodifiableList(new ArrayList<>(ringBuffer));
    }

    public void logWebAction(String actor, String action) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task ->
            plugin.getDatabaseManager().saveWebLog(actor, action));
    }
}
