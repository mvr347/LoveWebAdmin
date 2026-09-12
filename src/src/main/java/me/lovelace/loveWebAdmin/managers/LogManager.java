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
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Перехватывает вывод консоли и пишет в БД, держит ring buffer последних 200 записей в памяти.
 */
public class LogManager {

    private static final int RING_BUFFER_SIZE = 200;

    private final LoveWebAdmin plugin;
    private final ConcurrentLinkedQueue<String> pendingMessages = new ConcurrentLinkedQueue<>();
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
                if (record.getMessage() == null) return;
                pendingMessages.add(getFormatter() != null ? getFormatter().formatMessage(record) : record.getMessage());
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
        flushToDatabase();
    }

    private void flushToDatabase() {
        String message;
        boolean wrote = false;
        while ((message = pendingMessages.poll()) != null) {
            plugin.getDatabaseManager().saveServerLog(message);
            addToRingBuffer(new LogEntry(0, "SERVER", "CONSOLE", message, System.currentTimeMillis() / 1000));
            wrote = true;
        }
        if (wrote) {
            int keepCount = plugin.getConfig().getInt("logs.keep-count", 5000);
            plugin.getDatabaseManager().trimServerLogs(keepCount);
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
