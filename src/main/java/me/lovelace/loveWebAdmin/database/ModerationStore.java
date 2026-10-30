package me.lovelace.loveWebAdmin.database;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.HexFormat;

/** Durable companion tables. No Player or mutable inventory is retained here. */
public final class ModerationStore implements AutoCloseable {
  private final Connection db;

  public ModerationStore(Path file) throws SQLException {
    this(openWithBackup(file));
  }

  private static Connection openWithBackup(Path file) throws SQLException {
    var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
    Path backup = file.resolveSibling(file.getFileName() + ".before-panel-v3.db");
    try {
      if (java.nio.file.Files.size(file) > 0 && !java.nio.file.Files.exists(backup))
        try (var snapshot = connection.createStatement()) {
          snapshot.execute(
              "VACUUM INTO '" + backup.toAbsolutePath().toString().replace("'", "''") + "'");
        }
      return connection;
    } catch (Exception error) {
      connection.close();
      if (error instanceof SQLException sql) throw sql;
      throw new SQLException("Не удалось сохранить резервную копию дел", error);
    }
  }

  public ModerationStore(Connection db) throws SQLException {
    this.db = db;
    try (var s = db.createStatement()) {
      s.execute("PRAGMA busy_timeout=3000");
      s.execute("PRAGMA journal_mode=WAL");
      s.execute(
          "CREATE TABLE IF NOT EXISTS discord_cards(kind TEXT NOT NULL,id INTEGER NOT NULL,channel"
              + " TEXT NOT NULL,message TEXT NOT NULL,PRIMARY KEY(kind,id))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS case_work (kind TEXT NOT NULL,id INTEGER NOT NULL, assignee"
              + " TEXT, priority TEXT NOT NULL DEFAULT 'NORMAL', state TEXT NOT NULL DEFAULT"
              + " 'OPEN', PRIMARY KEY(kind,id))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS case_notes (id INTEGER PRIMARY KEY,kind TEXT NOT NULL,case_id"
              + " INTEGER NOT NULL,author TEXT NOT NULL,body TEXT NOT NULL,created_at INTEGER NOT"
              + " NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS appeal_access (appeal_id INTEGER PRIMARY KEY, token_hash TEXT"
              + " UNIQUE NOT NULL, discord_id TEXT, verified INTEGER NOT NULL DEFAULT 0)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS discord_incoming (message_id TEXT PRIMARY KEY, appeal_id"
              + " INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS ban_sources (ban_id INTEGER PRIMARY KEY, kind TEXT NOT NULL,"
              + " actor TEXT NOT NULL, external_event TEXT UNIQUE)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS delivery_outbox (id INTEGER PRIMARY KEY,event_key TEXT UNIQUE"
              + " NOT NULL,recipient TEXT NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL"
              + " DEFAULT 'PENDING',attempts INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL"
              + " DEFAULT 0,error TEXT)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS confiscated_items(id TEXT PRIMARY KEY,owner TEXT NOT"
              + " NULL,actor TEXT NOT NULL,item BLOB NOT NULL,state TEXT NOT NULL DEFAULT"
              + " 'PENDING',created_at INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS confiscation_reasons(id TEXT PRIMARY KEY,reason TEXT NOT"
              + " NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS confiscation_returns(id TEXT PRIMARY KEY,actor TEXT NOT"
              + " NULL,at INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS portal_links(token_hash TEXT PRIMARY KEY,discord_id TEXT NOT NULL,player_uuid TEXT,expires_at INTEGER NOT NULL,used INTEGER NOT NULL DEFAULT 0)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS portal_sessions(token_hash TEXT PRIMARY KEY,discord_id TEXT NOT NULL,player_uuid TEXT,expires_at INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS case_tickets(kind TEXT NOT NULL,case_id INTEGER NOT NULL,discord_id TEXT NOT NULL,channel TEXT,PRIMARY KEY(kind,case_id))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS entity_versions(kind TEXT NOT NULL,id INTEGER NOT NULL,version INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS ban_history(id INTEGER PRIMARY KEY,ban_id INTEGER NOT NULL,actor TEXT NOT NULL,explanation TEXT NOT NULL,before_json TEXT NOT NULL,after_json TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'PREPARED',created_at INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS case_evidence(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,case_id INTEGER NOT NULL,author TEXT NOT NULL,type TEXT NOT NULL,value TEXT NOT NULL,visibility TEXT NOT NULL,mime TEXT,data BLOB,created_at INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS appeal_access_parent(appeal_id INTEGER PRIMARY KEY,parent_id INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS staff_identity(admin_id INTEGER PRIMARY KEY,uuid TEXT NOT NULL)");
      s.execute("CREATE INDEX IF NOT EXISTS idx_delivery_due ON delivery_outbox(state,next_at,id)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS idx_delivery_ticket ON delivery_outbox(recipient,id,state)");
      s.execute(
          "UPDATE delivery_outbox SET state='UNCERTAIN',error='Остановка до подтверждения доставки: проверьте Discord перед повтором' WHERE state='SENDING'");
    } catch (SQLException | RuntimeException error) {
      try {
        db.close();
      } catch (SQLException close) {
        error.addSuppressed(close);
      }
      throw error;
    }
  }

  private static String kind(String kind) {
    if (!Set.of("report", "appeal").contains(kind))
      throw new IllegalArgumentException("Неизвестный тип дела");
    return kind;
  }

  public synchronized Map<String, Object> work(String kind, int id) throws SQLException {
    try (var q = db.prepareStatement("SELECT * FROM case_work WHERE kind=? AND id=?")) {
      q.setString(1, kind(kind));
      q.setInt(2, id);
      try (var r = q.executeQuery()) {
        if (r.next())
          return Map.of(
              "assignee",
              Objects.toString(r.getString("assignee"), ""),
              "priority",
              r.getString("priority"),
              "state",
              r.getString("state"));
      }
    }
    return Map.of("assignee", "", "priority", "NORMAL", "state", "OPEN");
  }

  public synchronized boolean claim(String kind, int id, String actor) throws SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO case_work(kind,id,assignee,state) VALUES(?,?,?,'IN_PROGRESS') ON"
                + " CONFLICT(kind,id) DO UPDATE SET assignee=excluded.assignee,state='IN_PROGRESS'"
                + " WHERE case_work.assignee IS NULL AND case_work.state<>'CLOSED'")) {
      q.setString(1, kind(kind));
      q.setInt(2, id);
      q.setString(3, actor);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized boolean updateWork(
      String kind, int id, String actor, String assignee, String priority, String state)
      throws SQLException {
    if (!Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(priority)
        || !Set.of("OPEN", "IN_PROGRESS", "WAITING", "CLOSED").contains(state))
      throw new IllegalArgumentException("Некорректное состояние");
    try (var q =
        db.prepareStatement(
            "UPDATE case_work SET assignee=?,priority=?,state=? WHERE kind=? AND id=? AND"
                + " assignee=?")) {
      q.setString(1, assignee);
      q.setString(2, priority);
      q.setString(3, state);
      q.setString(4, kind(kind));
      q.setInt(5, id);
      q.setString(6, actor);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized void note(String kind, int id, String author, String body)
      throws SQLException {
    if (body == null || body.isBlank() || body.length() > 4000)
      throw new IllegalArgumentException("Заметка: от 1 до 4000 символов");
    try (var q =
        db.prepareStatement(
            "INSERT INTO case_notes(kind,case_id,author,body,created_at) VALUES(?,?,?,?,?)")) {
      q.setString(1, kind(kind));
      q.setInt(2, id);
      q.setString(3, author);
      q.setString(4, body);
      q.setLong(5, System.currentTimeMillis() / 1000);
      q.executeUpdate();
    }
  }

  public synchronized List<Map<String, Object>> notes(String kind, int id) throws SQLException {
    var result = new ArrayList<Map<String, Object>>();
    try (var q =
        db.prepareStatement(
            "SELECT * FROM case_notes WHERE kind=? AND case_id=? ORDER BY id LIMIT 500")) {
      q.setString(1, kind(kind));
      q.setInt(2, id);
      try (var r = q.executeQuery()) {
        while (r.next())
          result.add(
              Map.of(
                  "author",
                  r.getString("author"),
                  "body",
                  r.getString("body"),
                  "createdAt",
                  r.getLong("created_at")));
      }
    }
    return result;
  }

  public static String hash(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public synchronized String createAccess(int appeal, String discord, boolean verified)
      throws SQLException {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String raw = HexFormat.of().formatHex(bytes);
    try (var q =
        db.prepareStatement(
            "INSERT INTO appeal_access(appeal_id,token_hash,discord_id,verified)"
                + " VALUES(?,?,?,?)")) {
      q.setInt(1, appeal);
      q.setString(2, hash(raw));
      q.setString(3, discord);
      q.setBoolean(4, verified);
      q.executeUpdate();
    }
    return raw;
  }

  /** Same author capability extends only to an explicitly approved child review. */
  public synchronized void copyAppealAccess(int from, int to) throws SQLException {
    db.setAutoCommit(false);
    try {
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      try (var q =
          db.prepareStatement(
              "INSERT INTO appeal_access(appeal_id,token_hash,discord_id,verified) SELECT ?,?,discord_id,verified FROM appeal_access WHERE appeal_id=?")) {
        q.setInt(1, to);
        q.setString(2, hash(HexFormat.of().formatHex(random)));
        q.setInt(3, from);
        if (q.executeUpdate() != 1)
          throw new SQLException("Не подтверждён автор исходного обращения");
      }
      try (var q =
          db.prepareStatement(
              "INSERT INTO appeal_access_parent(appeal_id,parent_id) VALUES(?,?)")) {
        q.setInt(1, to);
        q.setInt(2, from);
        q.executeUpdate();
      }
      db.commit();
    } catch (SQLException error) {
      db.rollback();
      throw error;
    } finally {
      db.setAutoCommit(true);
    }
  }

  public synchronized boolean author(int appeal, String raw) throws SQLException {
    if (raw == null || raw.length() != 64) return false;
    try (var q =
        db.prepareStatement(
            "WITH RECURSIVE lineage(id) AS (SELECT ? UNION SELECT p.parent_id FROM appeal_access_parent p JOIN lineage l ON p.appeal_id=l.id) SELECT 1 FROM appeal_access a JOIN lineage l ON a.appeal_id=l.id WHERE a.token_hash=?")) {
      q.setInt(1, appeal);
      q.setString(2, hash(raw));
      try (var r = q.executeQuery()) {
        return r.next();
      }
    }
  }

  public synchronized boolean verified(int appeal) throws SQLException {
    try (var q = db.prepareStatement("SELECT verified FROM appeal_access WHERE appeal_id=?")) {
      q.setInt(1, appeal);
      try (var r = q.executeQuery()) {
        return r.next() && r.getBoolean(1);
      }
    }
  }

  /**
   * Pins a panel account to the Minecraft UUID it was first used from through Discord. A nickname
   * is not an identity: once a staff member renames, anyone who later takes the old name would
   * otherwise inherit their panel rights. Returns false when the account is pinned to another UUID.
   */
  public synchronized boolean pinStaff(int adminId, String uuid) throws SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO staff_identity(admin_id,uuid) VALUES(?,?) ON CONFLICT(admin_id) DO"
                + " NOTHING")) {
      q.setInt(1, adminId);
      q.setString(2, uuid);
      q.executeUpdate();
    }
    return staffUuid(adminId).filter(uuid::equalsIgnoreCase).isPresent();
  }

  /** The Minecraft UUID this panel account is pinned to, if it has used Discord yet. */
  public synchronized Optional<String> staffUuid(int adminId) throws SQLException {
    try (var q = db.prepareStatement("SELECT uuid FROM staff_identity WHERE admin_id=?")) {
      q.setInt(1, adminId);
      try (var r = q.executeQuery()) {
        return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
      }
    }
  }

  public synchronized Optional<String> owner(int appeal) throws SQLException {
    try (var q = db.prepareStatement("SELECT discord_id FROM appeal_access WHERE appeal_id=?")) {
      q.setInt(1, appeal);
      try (var r = q.executeQuery()) {
        return r.next() ? Optional.ofNullable(r.getString(1)) : Optional.empty();
      }
    }
  }

  public synchronized boolean acceptMessage(int appeal, String messageId) throws SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT OR IGNORE INTO discord_incoming(message_id,appeal_id) VALUES(?,?)")) {
      q.setString(1, messageId);
      q.setInt(2, appeal);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized void forgetMessage(String messageId) throws SQLException {
    try (var q = db.prepareStatement("DELETE FROM discord_incoming WHERE message_id=?")) {
      q.setString(1, messageId);
      q.executeUpdate();
    }
  }

  public synchronized Optional<Integer> externalBan(String event) throws SQLException {
    if (event == null) return Optional.empty();
    try (var q = db.prepareStatement("SELECT ban_id FROM ban_sources WHERE external_event=?")) {
      q.setString(1, event);
      try (var r = q.executeQuery()) {
        return r.next() ? Optional.of(r.getInt(1)) : Optional.empty();
      }
    }
  }

  public synchronized void source(int id, String type, String actor, String event)
      throws SQLException {
    if (!Set.of("STAFF", "ANTICHEAT", "SECURITY", "CONSOLE", "UNKNOWN").contains(type))
      throw new IllegalArgumentException("Неизвестный источник");
    try (var q =
        db.prepareStatement(
            "INSERT INTO ban_sources(ban_id,kind,actor,external_event) VALUES(?,?,?,?)")) {
      q.setInt(1, id);
      q.setString(2, type);
      q.setString(3, actor);
      q.setString(4, event);
      q.executeUpdate();
    }
  }

  public synchronized Map<String, Object> source(int id) throws SQLException {
    try (var q = db.prepareStatement("SELECT kind,actor FROM ban_sources WHERE ban_id=?")) {
      q.setInt(1, id);
      try (var r = q.executeQuery()) {
        if (r.next()) return Map.of("kind", r.getString(1), "actor", r.getString(2));
      }
    }
    return Map.of("kind", "UNKNOWN", "actor", "");
  }

  public synchronized void enqueue(String key, String recipient, String payload)
      throws SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO delivery_outbox(event_key,recipient,payload) VALUES(?,?,?) ON"
                + " CONFLICT(event_key) DO NOTHING")) {
      q.setString(1, key);
      q.setString(2, recipient);
      q.setString(3, payload);
      q.executeUpdate();
    }
  }

  public record Delivery(String key, String recipient, String payload) {}

  public synchronized void enqueueBatch(List<Delivery> deliveries) throws SQLException {
    if (deliveries.size() > 8) throw new IllegalArgumentException("Слишком много частей сообщения");
    db.setAutoCommit(false);
    try {
      for (var delivery : deliveries)
        enqueue(delivery.key(), delivery.recipient(), delivery.payload());
      db.commit();
    } catch (SQLException | RuntimeException error) {
      try {
        db.rollback();
      } catch (SQLException rollback) {
        error.addSuppressed(rollback);
      }
      throw error;
    } finally {
      db.setAutoCommit(true);
    }
  }

  public synchronized List<Map<String, Object>> due() throws SQLException {
    var result = new ArrayList<Map<String, Object>>();
    try (var q =
        db.prepareStatement(
            "SELECT o.* FROM delivery_outbox o WHERE o.state='PENDING' AND o.next_at<=? AND"
                + " ((o.recipient NOT LIKE 'ticket:%' AND o.recipient NOT LIKE 'report-ticket:%') OR NOT EXISTS(SELECT 1 FROM delivery_outbox"
                + " earlier WHERE earlier.recipient=o.recipient AND earlier.id<o.id AND"
                + " earlier.state IN ('PENDING','SENDING','UNCERTAIN'))) ORDER BY o.id LIMIT 8")) {
      q.setLong(1, System.currentTimeMillis() / 1000);
      try (var r = q.executeQuery()) {
        while (r.next())
          result.add(
              Map.of(
                  "id",
                  r.getInt("id"),
                  "recipient",
                  r.getString("recipient"),
                  "payload",
                  r.getString("payload"),
                  "attempts",
                  r.getInt("attempts")));
      }
    }
    return result;
  }

  public synchronized boolean takeDelivery(int id) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE delivery_outbox SET state='SENDING' WHERE id=? AND state='PENDING'")) {
      q.setInt(1, id);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized void uncertainDelivery(int id, String reason) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE delivery_outbox SET state='UNCERTAIN',error=? WHERE id=? AND state='SENDING'")) {
      q.setString(1, reason);
      q.setInt(2, id);
      q.executeUpdate();
    }
  }

  public synchronized void delivered(int id, boolean ok, int attempts, String error)
      throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE delivery_outbox SET state=?,attempts=?,next_at=?,error=? WHERE id=? AND"
                + " state='SENDING'")) {
      q.setString(1, ok ? "DELIVERED" : attempts >= 6 ? "FAILED" : "PENDING");
      q.setInt(2, attempts);
      q.setLong(
          3, System.currentTimeMillis() / 1000 + Math.min(3600, 30L << Math.min(attempts, 6)));
      q.setString(4, error);
      q.setInt(5, id);
      q.executeUpdate();
    }
  }

  public synchronized void deferDelivery(int id, long epochSeconds) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE delivery_outbox SET state='PENDING',next_at=? WHERE id=? AND"
                + " state='SENDING'")) {
      q.setLong(1, epochSeconds);
      q.setInt(2, id);
      q.executeUpdate();
    }
  }

  public synchronized void skipDelivery(int id, String reason) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE delivery_outbox SET state='SKIPPED',error=? WHERE id=? AND state='SENDING'")) {
      q.setString(1, reason);
      q.setInt(2, id);
      q.executeUpdate();
    }
  }

  public synchronized boolean resolveDelivery(
      int id, boolean delivered, String actor, String explanation) throws SQLException {
    if (explanation == null || explanation.trim().length() < 5 || explanation.length() > 2000)
      throw new IllegalArgumentException("Укажите обоснование сверки с Discord");
    try (var q =
        db.prepareStatement(
            "UPDATE delivery_outbox SET state=?,error=? WHERE id=? AND state='UNCERTAIN'")) {
      q.setString(1, delivered ? "DELIVERED" : "FAILED");
      q.setString(2, "Проверил " + actor + ": " + explanation.trim());
      q.setInt(3, id);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized boolean retryDelivery(int id) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE delivery_outbox SET state='PENDING',attempts=0,next_at=0,error=NULL WHERE id=?"
                + " AND state='FAILED'")) {
      q.setInt(1, id);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized List<Map<String, Object>> deliveries() throws SQLException {
    var result = new ArrayList<Map<String, Object>>();
    try (var s = db.createStatement();
        var r =
            s.executeQuery(
                "SELECT id,event_key,state,attempts,error FROM delivery_outbox ORDER BY id DESC"
                    + " LIMIT 100")) {
      while (r.next())
        result.add(
            Map.of(
                "id",
                r.getInt(1),
                "event",
                r.getString(2),
                "state",
                r.getString(3),
                "attempts",
                r.getInt(4),
                "error",
                Objects.toString(r.getString(5), "")));
    }
    return result;
  }

  public record Evidence(String id, String owner, String actor, byte[] item, String reason) {
    public Evidence(String id, String owner, String actor, byte[] item) {
      this(id, owner, actor, item, "Очистка инвентаря через WebAdmin");
    }
  }

  public synchronized void evidenceBatch(java.util.List<Evidence> items) throws SQLException {
    if (items.isEmpty() || items.size() > 41)
      throw new IllegalArgumentException("Недопустимый размер снимка");
    db.setAutoCommit(false);
    try (var q =
        db.prepareStatement(
            "INSERT INTO confiscated_items(id,owner,actor,item,created_at) VALUES(?,?,?,?,?)")) {
      for (var item : items) {
        q.setString(1, item.id());
        q.setString(2, item.owner());
        q.setString(3, item.actor());
        q.setBytes(4, item.item());
        q.setLong(5, System.currentTimeMillis() / 1000);
        q.addBatch();
      }
      q.executeBatch();
      try (var reasons =
          db.prepareStatement("INSERT INTO confiscation_reasons(id,reason) VALUES(?,?)")) {
        for (var item : items) {
          if (item.reason() == null || item.reason().isBlank() || item.reason().length() > 2000)
            throw new IllegalArgumentException("Укажите причину изъятия");
          reasons.setString(1, item.id());
          reasons.setString(2, item.reason());
          reasons.addBatch();
        }
        reasons.executeBatch();
      }
      db.commit();
    } catch (SQLException | RuntimeException e) {
      try {
        db.rollback();
      } catch (SQLException rollback) {
        e.addSuppressed(rollback);
      }
      throw e;
    } finally {
      db.setAutoCommit(true);
    }
  }

  public synchronized void holdBatch(java.util.List<Evidence> items) throws SQLException {
    String placeholders = String.join(",", java.util.Collections.nCopies(items.size(), "?"));
    try (var q =
        db.prepareStatement(
            "UPDATE confiscated_items SET state='HELD' WHERE state='PENDING' AND id IN ("
                + placeholders
                + ")")) {
      for (int i = 0; i < items.size(); i++) q.setString(i + 1, items.get(i).id());
      if (q.executeUpdate() != items.size()) throw new SQLException("Не весь снимок подтверждён");
    }
  }

  public synchronized String evidence(String owner, String actor, byte[] bytes)
      throws SQLException {
    return evidence(owner, actor, bytes, "Изъятие через API WebAdmin");
  }

  public synchronized String evidence(String owner, String actor, byte[] bytes, String reason)
      throws SQLException {
    if (reason == null || reason.isBlank() || reason.length() > 2000)
      throw new IllegalArgumentException("Укажите причину изъятия");
    String id = UUID.randomUUID().toString();
    evidenceBatch(java.util.List.of(new Evidence(id, owner, actor, bytes, reason)));
    return id;
  }

  public synchronized boolean confirmReturn(String id, String actor) throws SQLException {
    db.setAutoCommit(false);
    try {
      if (!evidenceState(id, "RETURNING", "RETURNED")) {
        db.rollback();
        return false;
      }
      try (var q =
          db.prepareStatement("INSERT INTO confiscation_returns(id,actor,at) VALUES(?,?,?)")) {
        q.setString(1, id);
        q.setString(2, actor);
        q.setLong(3, System.currentTimeMillis() / 1000);
        q.executeUpdate();
      }
      db.commit();
      return true;
    } catch (SQLException e) {
      try {
        db.rollback();
      } catch (SQLException error) {
        e.addSuppressed(error);
      }
      throw e;
    } finally {
      db.setAutoCommit(true);
    }
  }

  public synchronized boolean evidenceState(String id, String before, String after)
      throws SQLException {
    try (var q =
        db.prepareStatement("UPDATE confiscated_items SET state=? WHERE id=? AND state=?")) {
      q.setString(1, after);
      q.setString(2, id);
      q.setString(3, before);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized Optional<byte[]> evidenceItem(String id, String owner) throws SQLException {
    try (var q =
        db.prepareStatement(
            "SELECT item FROM confiscated_items WHERE id=? AND owner=? AND state='HELD'")) {
      q.setString(1, id);
      q.setString(2, owner);
      try (var r = q.executeQuery()) {
        return r.next() ? Optional.of(r.getBytes(1)) : Optional.empty();
      }
    }
  }

  public synchronized List<Map<String, Object>> evidenceList(String owner) throws SQLException {
    var result = new ArrayList<Map<String, Object>>();
    try (var q =
        db.prepareStatement(
            "SELECT c.id,c.actor,c.state,c.created_at,r.reason,t.actor FROM confiscated_items c"
                + " LEFT JOIN confiscation_reasons r ON r.id=c.id LEFT JOIN confiscation_returns t"
                + " ON t.id=c.id WHERE c.owner=? ORDER BY c.created_at DESC LIMIT 200")) {
      q.setString(1, owner);
      try (var r = q.executeQuery()) {
        while (r.next())
          result.add(
              Map.of(
                  "id",
                  r.getString(1),
                  "actor",
                  r.getString(2),
                  "state",
                  r.getString(3),
                  "createdAt",
                  r.getLong(4),
                  "reason",
                  Objects.toString(r.getString(5), "Причина не указана"),
                  "returnedBy",
                  Objects.toString(r.getString(6), "")));
      }
    }
    return result;
  }

  public synchronized Optional<Map<String, String>> card(String kind, int id) throws SQLException {
    try (var q =
        db.prepareStatement("SELECT channel,message FROM discord_cards WHERE kind=? AND id=?")) {
      q.setString(1, kind(kind));
      q.setInt(2, id);
      try (var r = q.executeQuery()) {
        return r.next()
            ? Optional.of(Map.of("channel", r.getString(1), "message", r.getString(2)))
            : Optional.empty();
      }
    }
  }

  public synchronized void card(String kind, int id, String channel, String message)
      throws SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO discord_cards(kind,id,channel,message) VALUES(?,?,?,?) ON"
                + " CONFLICT(kind,id) DO UPDATE SET"
                + " channel=excluded.channel,message=excluded.message")) {
      q.setString(1, kind(kind));
      q.setInt(2, id);
      q.setString(3, channel);
      q.setString(4, message);
      q.executeUpdate();
    }
  }

  public synchronized String portalLink(String discord, String uuid) throws SQLException {
    if (discord == null || !discord.matches("[0-9]{5,24}"))
      throw new IllegalArgumentException("Неверный Discord ID");
    String token =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    try (var q = db.prepareStatement("DELETE FROM portal_links WHERE expires_at<?")) {
      q.setLong(1, System.currentTimeMillis() / 1000);
      q.executeUpdate();
    }
    try (var q =
        db.prepareStatement(
            "INSERT INTO portal_links(token_hash,discord_id,player_uuid,expires_at) VALUES(?,?,?,?)")) {
      q.setString(1, hash(token));
      q.setString(2, discord);
      q.setString(3, uuid);
      q.setLong(4, System.currentTimeMillis() / 1000 + 300);
      q.executeUpdate();
    }
    return token;
  }

  public synchronized Optional<Map<String, String>> exchangePortal(String token)
      throws SQLException {
    if (token == null || token.length() > 100) return Optional.empty();
    String hashed = hash(token);
    long now = System.currentTimeMillis() / 1000;
    String discord, uuid;
    try (var q =
        db.prepareStatement(
            "SELECT discord_id,player_uuid FROM portal_links WHERE token_hash=? AND used=0 AND expires_at>?")) {
      q.setString(1, hashed);
      q.setLong(2, now);
      try (var rs = q.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        discord = rs.getString(1);
        uuid = rs.getString(2);
      }
    }
    String session =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    db.setAutoCommit(false);
    try {
      try (var q =
          db.prepareStatement("UPDATE portal_links SET used=1 WHERE token_hash=? AND used=0")) {
        q.setString(1, hashed);
        if (q.executeUpdate() != 1) {
          db.rollback();
          return Optional.empty();
        }
      }
      try (var q =
          db.prepareStatement(
              "INSERT INTO portal_sessions(token_hash,discord_id,player_uuid,expires_at) VALUES(?,?,?,?)")) {
        q.setString(1, hash(session));
        q.setString(2, discord);
        q.setString(3, uuid);
        q.setLong(4, now + 3600);
        q.executeUpdate();
      }
      db.commit();
    } catch (SQLException error) {
      db.rollback();
      throw error;
    } finally {
      db.setAutoCommit(true);
    }
    return Optional.of(
        Map.of("token", session, "discordId", discord, "playerUuid", Objects.toString(uuid, "")));
  }

  public synchronized Optional<Map<String, String>> portalSession(String token)
      throws SQLException {
    if (token == null || token.length() > 100) return Optional.empty();
    try (var q =
        db.prepareStatement(
            "SELECT discord_id,player_uuid FROM portal_sessions WHERE token_hash=? AND expires_at>?")) {
      q.setString(1, hash(token));
      q.setLong(2, System.currentTimeMillis() / 1000);
      try (var rs = q.executeQuery()) {
        return rs.next()
            ? Optional.of(
                Map.of(
                    "discordId",
                    rs.getString(1),
                    "playerUuid",
                    Objects.toString(rs.getString(2), "")))
            : Optional.empty();
      }
    }
  }

  public synchronized void portalLogout(String token) throws SQLException {
    if (token == null) return;
    try (var q = db.prepareStatement("DELETE FROM portal_sessions WHERE token_hash=?")) {
      q.setString(1, hash(token));
      q.executeUpdate();
    }
  }

  public synchronized void ticketOwner(String kind, int id, String discord) throws SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO case_tickets(kind,case_id,discord_id) VALUES(?,?,?) ON CONFLICT(kind,case_id) DO NOTHING")) {
      q.setString(1, kind(kind));
      q.setInt(2, id);
      q.setString(3, discord);
      q.executeUpdate();
    }
  }

  public synchronized void ticketChannel(String kind, int id, String channel) throws SQLException {
    try (var q =
        db.prepareStatement("UPDATE case_tickets SET channel=? WHERE kind=? AND case_id=?")) {
      q.setString(1, channel);
      q.setString(2, kind(kind));
      q.setInt(3, id);
      q.executeUpdate();
    }
  }

  public synchronized Optional<Map<String, Object>> ticket(String kind, int id)
      throws SQLException {
    try (var q =
        db.prepareStatement(
            "SELECT discord_id,channel FROM case_tickets WHERE kind=? AND case_id=?")) {
      q.setString(1, kind(kind));
      q.setInt(2, id);
      try (var rs = q.executeQuery()) {
        return rs.next()
            ? Optional.of(
                Map.of(
                    "discordId", rs.getString(1), "channel", Objects.toString(rs.getString(2), "")))
            : Optional.empty();
      }
    }
  }

  public synchronized List<Map<String, Object>> tickets() throws SQLException {
    var result = new ArrayList<Map<String, Object>>();
    try (var q = db.createStatement();
        var rs = q.executeQuery("SELECT * FROM case_tickets WHERE channel IS NOT NULL")) {
      while (rs.next())
        result.add(
            Map.of(
                "kind",
                rs.getString("kind"),
                "id",
                rs.getInt("case_id"),
                "discordId",
                rs.getString("discord_id"),
                "channel",
                rs.getString("channel")));
    }
    return result;
  }

  public synchronized long revision(String kind, int id) throws SQLException {
    try (var q = db.prepareStatement("SELECT version FROM entity_versions WHERE kind=? AND id=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      try (var rs = q.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0;
      }
    }
  }

  /** Compare-and-increment before a write. A conflict cannot silently overwrite another draft. */
  public synchronized boolean reserveRevision(String kind, int id, long expected)
      throws SQLException {
    try (var q =
        db.prepareStatement("INSERT OR IGNORE INTO entity_versions(kind,id) VALUES(?,?)")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.executeUpdate();
    }
    try (var q =
        db.prepareStatement(
            "UPDATE entity_versions SET version=version+1 WHERE kind=? AND id=? AND version=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setLong(3, expected);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized int prepareBanCorrection(
      int id, String actor, String why, String before, String after) throws SQLException {
    if (why == null || why.trim().length() < 5 || why.length() > 2000)
      throw new IllegalArgumentException("Укажите объяснение изменения");
    try (var q =
        db.prepareStatement(
            "INSERT INTO ban_history(ban_id,actor,explanation,before_json,after_json,created_at) VALUES(?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
      q.setInt(1, id);
      q.setString(2, actor);
      q.setString(3, why.trim());
      q.setString(4, before);
      q.setString(5, after);
      q.setLong(6, System.currentTimeMillis() / 1000);
      q.executeUpdate();
      try (var keys = q.getGeneratedKeys()) {
        keys.next();
        return keys.getInt(1);
      }
    }
  }

  public synchronized void finishBanCorrection(int id, String state) throws SQLException {
    if (!Set.of("APPLIED", "ROLLED_BACK", "UNCERTAIN").contains(state))
      throw new IllegalArgumentException("Неизвестное состояние");
    try (var q =
        db.prepareStatement("UPDATE ban_history SET state=? WHERE id=? AND state='PREPARED'")) {
      q.setString(1, state);
      q.setInt(2, id);
      q.executeUpdate();
    }
  }

  private static String publicBanChange(String json) {
    var value = me.lovelace.loveWebAdmin.utils.JsonUtils.parseObject(json);
    value.keySet().retainAll(Set.of("ruleReason", "description", "durationSeconds", "expiresAt"));
    return me.lovelace.loveWebAdmin.utils.JsonUtils.toJson(value);
  }

  public synchronized List<Map<String, Object>> banHistory(int id) throws SQLException {
    var result = new ArrayList<Map<String, Object>>();
    try (var q =
        db.prepareStatement(
            "SELECT * FROM ban_history WHERE ban_id=? ORDER BY id DESC LIMIT 100")) {
      q.setInt(1, id);
      try (var rs = q.executeQuery()) {
        while (rs.next())
          result.add(
              Map.of(
                  "id",
                  rs.getInt("id"),
                  "actor",
                  rs.getString("actor"),
                  "explanation",
                  rs.getString("explanation"),
                  "before",
                  publicBanChange(rs.getString("before_json")),
                  "after",
                  publicBanChange(rs.getString("after_json")),
                  "state",
                  rs.getString("state"),
                  "createdAt",
                  rs.getLong("created_at")));
      }
    }
    return result;
  }

  public synchronized int addEvidence(
      String kind,
      int id,
      String author,
      String type,
      String value,
      String visibility,
      String mime,
      byte[] data)
      throws SQLException {
    kind(kind);
    if (!Set.of("TEXT", "CHAT", "LINK", "IMAGE").contains(type)
        || !Set.of("STAFF", "AUTHOR").contains(visibility))
      throw new IllegalArgumentException("Неизвестный тип или видимость доказательства");
    if (value == null
        || value.isBlank()
        || value.length() > 8000
        || data != null && data.length > 700000)
      throw new IllegalArgumentException("Доказательство слишком велико или пусто");
    try (var q =
        db.prepareStatement("SELECT COUNT(*) FROM case_evidence WHERE kind=? AND case_id=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      try (var rs = q.executeQuery()) {
        if (rs.next() && rs.getInt(1) >= 100)
          throw new IllegalArgumentException("Лимит 100 доказательств в деле");
      }
    }
    try (var q =
        db.prepareStatement(
            "INSERT INTO case_evidence(kind,case_id,author,type,value,visibility,mime,data,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setString(3, author);
      q.setString(4, type);
      q.setString(5, value);
      q.setString(6, visibility);
      q.setString(7, mime);
      q.setBytes(8, data);
      q.setLong(9, System.currentTimeMillis() / 1000);
      q.executeUpdate();
      try (var keys = q.getGeneratedKeys()) {
        keys.next();
        return keys.getInt(1);
      }
    }
  }

  public synchronized List<Map<String, Object>> evidence(String kind, int id, boolean author)
      throws SQLException {
    var result = new ArrayList<Map<String, Object>>();
    try (var q =
        db.prepareStatement(
            "SELECT id,author,type,value,visibility,mime,created_at FROM case_evidence WHERE kind=? AND case_id=?"
                + (author ? " AND visibility='AUTHOR'" : "")
                + " ORDER BY id")) {
      q.setString(1, kind(kind));
      q.setInt(2, id);
      try (var rs = q.executeQuery()) {
        while (rs.next())
          result.add(
              Map.of(
                  "id",
                  rs.getInt(1),
                  "author",
                  rs.getString(2),
                  "type",
                  rs.getString(3),
                  "value",
                  rs.getString(4),
                  "visibility",
                  rs.getString(5),
                  "mime",
                  Objects.toString(rs.getString(6), ""),
                  "createdAt",
                  rs.getLong(7)));
      }
    }
    return result;
  }

  public synchronized Optional<byte[]> evidenceBytes(
      String kind, int caseId, int id, boolean author) throws SQLException {
    try (var q =
        db.prepareStatement(
            "SELECT data FROM case_evidence WHERE kind=? AND case_id=? AND id=?"
                + (author ? " AND visibility='AUTHOR'" : ""))) {
      q.setString(1, kind(kind));
      q.setInt(2, caseId);
      q.setInt(3, id);
      try (var rs = q.executeQuery()) {
        return rs.next() ? Optional.ofNullable(rs.getBytes(1)) : Optional.empty();
      }
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    db.close();
  }
}
