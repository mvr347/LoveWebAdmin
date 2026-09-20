package me.lovelace.loveWebAdmin.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BanArchiveManager {
    private static final long THREE_MONTHS_MS = 90L * 24 * 3600 * 1000;
    private static final long ARCHIVE_AFTER_SEC = 30L * 24 * 3600;

    private final LoveWebAdmin plugin;
    private final Path archiveDir;
    private ScheduledTask task;

    public BanArchiveManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
        this.archiveDir = plugin.getDataFolder().toPath().resolve("archive");
        try { Files.createDirectories(archiveDir); } catch (IOException e) {
            plugin.getLogger().warning("archive/: " + e.getMessage());
        }
    }

    public void start() {
        task = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, t -> {
            try { archiveOldUnbanned(); purgeOldArchives(); }
            catch (Exception e) { plugin.getLogger().warning("archive task: " + e.getMessage()); }
        }, 5, 12 * 60, TimeUnit.MINUTES);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    public List<String> listArchiveFiles() {
        try {
            if (!Files.isDirectory(archiveDir)) return List.of();
            try (Stream<Path> s = Files.list(archiveDir)) {
                return s.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .map(p -> p.getFileName().toString()).sorted().toList();
            }
        } catch (IOException e) { return List.of(); }
    }

    private void archiveOldUnbanned() {
        long now = System.currentTimeMillis() / 1000L;
        long threshold = now - ARCHIVE_AFTER_SEC;
        List<WebBan> bans = plugin.getDatabaseManager().getAllBans("UNBANNED");
        for (WebBan ban : bans) {
            if (ban.createdAt() > threshold) continue;
            try {
                Path single = archiveDir.resolve("ban-" + ban.id() + "-" +
                    (ban.targetName() != null ? ban.targetName().replaceAll("[^a-zA-Z0-9_-]", "_") : "x") + ".zip");
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(single))) {
                    String json = JsonUtils.toJson(Map.of(
                        "id", ban.id(),
                        "targetName", ban.targetName() != null ? ban.targetName() : "",
                        "ruleReason", ban.ruleReason() != null ? ban.ruleReason() : "",
                        "description", ban.description() != null ? ban.description() : "",
                        "creatorName", ban.creatorName() != null ? ban.creatorName() : "",
                        "status", ban.status() != null ? ban.status() : "",
                        "createdAt", ban.createdAt(),
                        "proofUrls", ban.proofUrls() != null ? ban.proofUrls() : "[]"
                    ));
                    zos.putNextEntry(new ZipEntry("ban.json"));
                    zos.write(json.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
                plugin.getDatabaseManager().deleteBan(ban.id());
            } catch (Exception e) {
                plugin.getLogger().warning("archive ban #" + ban.id() + ": " + e.getMessage());
            }
        }
    }

    private void purgeOldArchives() {
        long cutoff = System.currentTimeMillis() - THREE_MONTHS_MS;
        try {
            if (!Files.isDirectory(archiveDir)) return;
            try (Stream<Path> s = Files.list(archiveDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".zip")).forEach(p -> {
                    try {
                        if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                            Files.deleteIfExists(p);
                        }
                    } catch (IOException ignored) {}
                });
            }
        } catch (IOException e) {
            plugin.getLogger().warning("purge archives: " + e.getMessage());
        }
    }
}
