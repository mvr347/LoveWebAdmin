package me.lovelace.loveWebAdmin.database;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Durable admission and result journal. An interrupted external action is never blindly replayed.
 */
public final class OperationStore implements AutoCloseable {
  public record Operation(
      String actor,
      String key,
      String route,
      String fingerprint,
      String state,
      int status,
      String response,
      String permission) {}

  private final Connection db;
  private final Map<String, long[]> limits = new HashMap<>();

  public OperationStore(Path file) throws SQLException {
    db = DriverManager.getConnection("jdbc:sqlite:" + file);
    try (var q = db.createStatement()) {
      q.execute("PRAGMA busy_timeout=5000");
      q.execute("PRAGMA journal_mode=WAL");
      q.execute(
          "CREATE TABLE IF NOT EXISTS operations(actor TEXT NOT NULL,op_key TEXT NOT NULL,route TEXT NOT NULL,fingerprint TEXT NOT NULL,state TEXT NOT NULL,status INTEGER NOT NULL DEFAULT 202,response TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL,PRIMARY KEY(actor,op_key))");
      boolean permission = false;
      try (var rs = q.executeQuery("PRAGMA table_info(operations)")) {
        while (rs.next()) if (rs.getString("name").equals("permission")) permission = true;
      }
      if (!permission) q.execute("ALTER TABLE operations ADD COLUMN permission TEXT");
      q.execute("CREATE INDEX IF NOT EXISTS idx_operation_expiry ON operations(state,created_at)");
      q.execute("UPDATE operations SET state='UNCERTAIN' WHERE state='RUNNING'");
    }
  }

  public synchronized boolean allow(String actor) {
    long now = System.currentTimeMillis();
    limits.values().removeIf(w -> w[0] <= now);
    if (!limits.containsKey(actor) && limits.size() >= 4096) return false;
    var window = limits.computeIfAbsent(actor, k -> new long[] {now + 60000, 0});
    return ++window[1] <= 60;
  }

  /**
   * null means this caller acquired the operation; existing rows are immutable until completion.
   */
  public synchronized Operation reserve(String actor, String key, String route, String hash)
      throws SQLException {
    if (key == null || !key.matches("[a-zA-Z0-9_-]{8,100}"))
      throw new IllegalArgumentException("Некорректный ключ операции");
    try (var q =
        db.prepareStatement("DELETE FROM operations WHERE created_at<? AND state='COMPLETE'")) {
      q.setLong(1, System.currentTimeMillis() - 86400000);
      q.executeUpdate();
    }
    try (var q =
        db.prepareStatement(
            "INSERT OR IGNORE INTO operations(actor,op_key,route,fingerprint,state,created_at) VALUES(?,?,?,?,'RUNNING',?)")) {
      q.setString(1, actor);
      q.setString(2, key);
      q.setString(3, route);
      q.setString(4, hash);
      q.setLong(5, System.currentTimeMillis());
      if (q.executeUpdate() == 1) return null;
    }
    var existing = get(actor, key).orElseThrow();
    if (!existing.fingerprint().equals(hash) || !existing.route().equals(route))
      throw new IllegalArgumentException("Ключ уже использован для другого запроса");
    return existing;
  }

  public synchronized Optional<Operation> get(String actor, String key) throws SQLException {
    try (var q = db.prepareStatement("SELECT * FROM operations WHERE actor=? AND op_key=?")) {
      q.setString(1, actor);
      q.setString(2, key);
      try (var rs = q.executeQuery()) {
        return rs.next()
            ? Optional.of(
                new Operation(
                    actor,
                    key,
                    rs.getString("route"),
                    rs.getString("fingerprint"),
                    rs.getString("state"),
                    rs.getInt("status"),
                    rs.getString("response"),
                    rs.getString("permission")))
            : Optional.empty();
      }
    }
  }

  public synchronized void complete(
      String actor, String key, int status, String response, String permission)
      throws SQLException {
    if (response.length() > 1048576)
      throw new IllegalArgumentException("Результат операции слишком велик");
    try (var q =
        db.prepareStatement(
            "UPDATE operations SET state='COMPLETE',status=?,response=?,permission=? WHERE actor=? AND op_key=? AND state='RUNNING'")) {
      q.setInt(1, status);
      q.setString(2, response);
      q.setString(3, permission);
      q.setString(4, actor);
      q.setString(5, key);
      q.executeUpdate();
    }
  }

  public synchronized void uncertain(String actor, String key) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE operations SET state='UNCERTAIN' WHERE actor=? AND op_key=? AND state='RUNNING'")) {
      q.setString(1, actor);
      q.setString(2, key);
      q.executeUpdate();
    }
  }

  public synchronized boolean resolve(String actor, String key, boolean applied, String explanation)
      throws SQLException {
    if (explanation == null || explanation.trim().length() < 5 || explanation.length() > 2000)
      throw new IllegalArgumentException("Укажите результат проверки");
    String response =
        me.lovelace.loveWebAdmin.utils.JsonUtils.error(
            (applied ? "Выполнение подтверждено" : "Подтверждено отсутствие выполнения")
                + ". "
                + explanation.trim()
                + ". Обновите данные перед новым действием.");
    try (var q =
        db.prepareStatement(
            "UPDATE operations SET state='COMPLETE',status=409,response=? WHERE actor=? AND op_key=? AND state='UNCERTAIN'")) {
      q.setString(1, response);
      q.setString(2, actor);
      q.setString(3, key);
      return q.executeUpdate() == 1;
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    db.close();
    limits.clear();
  }
}
