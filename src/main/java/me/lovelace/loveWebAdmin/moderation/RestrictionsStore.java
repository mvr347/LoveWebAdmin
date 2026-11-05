package me.lovelace.loveWebAdmin.moderation;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Durable transitions; callers publish a new immutable hot-path snapshot only after commit. */
public final class RestrictionsStore implements AutoCloseable {
  public record Pending(
      int id,
      String name,
      String uuid,
      String ip,
      String actor,
      long created,
      long deadline,
      String state,
      String day) {}

  public record Restriction(
      String kind,
      String uuid,
      String name,
      String actor,
      String reason,
      long created,
      long expires) {
    public boolean active(long now) {
      return expires < 0 || expires > now;
    }
  }

  private final Connection db;

  public RestrictionsStore(Path file) throws SQLException {
    this(org.sqlite.JDBC.createConnection("jdbc:sqlite:" + file, new java.util.Properties()));
  }

  public RestrictionsStore(Connection db) throws SQLException {
    this.db = db;
    try (var s = db.createStatement()) {
      s.execute("PRAGMA busy_timeout=5000");
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA synchronous=FULL");
      s.execute(
          "CREATE TABLE IF NOT EXISTS pending_bans(id INTEGER PRIMARY KEY,name TEXT NOT NULL,uuid"
              + " TEXT NOT NULL,ip TEXT NOT NULL,actor TEXT NOT NULL,created INTEGER NOT"
              + " NULL,deadline INTEGER NOT NULL,state TEXT NOT NULL,day TEXT NOT NULL)");
      s.execute(
          "CREATE UNIQUE INDEX IF NOT EXISTS unique_pending_player ON pending_bans(lower(name))"
              + " WHERE state='PENDING'");
      s.execute("CREATE INDEX IF NOT EXISTS pending_expiry ON pending_bans(state,deadline)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS restrictions(kind TEXT NOT NULL,uuid TEXT NOT NULL,name TEXT"
              + " NOT NULL,actor TEXT NOT NULL,reason TEXT NOT NULL,created INTEGER NOT"
              + " NULL,expires INTEGER NOT NULL,PRIMARY KEY(kind,uuid))");
      s.execute(
          "CREATE TABLE IF NOT EXISTS restriction_history(id INTEGER PRIMARY KEY,kind TEXT NOT"
              + " NULL,uuid TEXT NOT NULL,actor TEXT NOT NULL,reason TEXT NOT NULL,action TEXT NOT"
              + " NULL,at INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS overdue_alerts(actor TEXT NOT NULL,day TEXT NOT NULL,PRIMARY"
              + " KEY(actor,day))");
    }
  }

  public synchronized void pending(Pending p) throws SQLException {
    try (var q = db.prepareStatement("INSERT INTO pending_bans VALUES(?,?,?,?,?,?,?,?,?)")) {
      q.setInt(1, p.id);
      q.setString(2, p.name);
      q.setString(3, p.uuid);
      q.setString(4, p.ip);
      q.setString(5, p.actor);
      q.setLong(6, p.created);
      q.setLong(7, p.deadline);
      q.setString(8, p.state);
      q.setString(9, p.day);
      q.executeUpdate();
    }
  }

  public synchronized List<Pending> pending() throws SQLException {
    var out = new ArrayList<Pending>();
    try (var q = db.prepareStatement("SELECT * FROM pending_bans WHERE state='PENDING'");
        var r = q.executeQuery()) {
      while (r.next())
        out.add(
            new Pending(
                r.getInt("id"),
                r.getString("name"),
                r.getString("uuid"),
                r.getString("ip"),
                r.getString("actor"),
                r.getLong("created"),
                r.getLong("deadline"),
                r.getString("state"),
                r.getString("day")));
    }
    return List.copyOf(out);
  }

  public synchronized Optional<Pending> findPending(int id) throws SQLException {
    try (var q = db.prepareStatement("SELECT * FROM pending_bans WHERE id=?")) {
      q.setInt(1, id);
      try (var r = q.executeQuery()) {
        return r.next()
            ? Optional.of(
                new Pending(
                    r.getInt("id"),
                    r.getString("name"),
                    r.getString("uuid"),
                    r.getString("ip"),
                    r.getString("actor"),
                    r.getLong("created"),
                    r.getLong("deadline"),
                    r.getString("state"),
                    r.getString("day")))
            : Optional.empty();
      }
    }
  }

  public synchronized boolean transition(int id, String state, long now) throws SQLException {
    if (!Set.of("FINALIZED", "EXPIRED", "CANCELLED").contains(state))
      throw new IllegalArgumentException("Состояние");
    String condition =
        state.equals("EXPIRED")
            ? " AND deadline<=?"
            : state.equals("FINALIZED") ? " AND deadline>?" : "";
    try (var q =
        db.prepareStatement(
            "UPDATE pending_bans SET state=? WHERE id=? AND state='PENDING'" + condition)) {
      q.setString(1, state);
      q.setInt(2, id);
      if (!condition.isEmpty()) q.setLong(3, now);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized int overdue(String actor, String day) throws SQLException {
    try (var q =
        db.prepareStatement(
            "SELECT count(*) FROM pending_bans WHERE actor=? AND day=? AND state='EXPIRED'")) {
      q.setString(1, actor);
      q.setString(2, day);
      try (var r = q.executeQuery()) {
        return r.next() ? r.getInt(1) : 0;
      }
    }
  }

  public synchronized List<Map<String, Object>> alertsNeeded() throws SQLException {
    var out = new ArrayList<Map<String, Object>>();
    try (var q = db.createStatement();
        var r =
            q.executeQuery(
                "SELECT actor,day,count(*) AS total FROM pending_bans p WHERE state='EXPIRED' AND"
                    + " NOT EXISTS(SELECT 1 FROM overdue_alerts a WHERE a.actor=p.actor AND"
                    + " a.day=p.day) GROUP BY actor,day HAVING count(*)>=3")) {
      while (r.next())
        out.add(Map.of("actor", r.getString(1), "day", r.getString(2), "count", r.getInt(3)));
    }
    return out;
  }

  public synchronized boolean claimAlert(String actor, String day) throws SQLException {
    try (var q = db.prepareStatement("INSERT OR IGNORE INTO overdue_alerts VALUES(?,?)")) {
      q.setString(1, actor);
      q.setString(2, day);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized List<Restriction> restrictions() throws SQLException {
    var out = new ArrayList<Restriction>();
    try (var q = db.prepareStatement("SELECT * FROM restrictions");
        var r = q.executeQuery()) {
      while (r.next())
        out.add(
            new Restriction(
                r.getString("kind"),
                r.getString("uuid"),
                r.getString("name"),
                r.getString("actor"),
                r.getString("reason"),
                r.getLong("created"),
                r.getLong("expires")));
    }
    return List.copyOf(out);
  }

  public synchronized void put(Restriction v) throws SQLException {
    if (!Set.of("MUTE", "FREEZE").contains(v.kind))
      throw new IllegalArgumentException("Тип ограничения");
    db.setAutoCommit(false);
    try {
      try (var q =
          db.prepareStatement(
              "INSERT INTO restrictions VALUES(?,?,?,?,?,?,?) ON CONFLICT(kind,uuid) DO UPDATE SET"
                  + " name=excluded.name,actor=excluded.actor,reason=excluded.reason,created=excluded.created,expires=excluded.expires")) {
        q.setString(1, v.kind);
        q.setString(2, v.uuid);
        q.setString(3, v.name);
        q.setString(4, v.actor);
        q.setString(5, v.reason);
        q.setLong(6, v.created);
        q.setLong(7, v.expires);
        q.executeUpdate();
      }
      history(v.kind, v.uuid, v.actor, v.reason, "APPLIED", v.created);
      db.commit();
    } catch (SQLException e) {
      db.rollback();
      throw e;
    } finally {
      db.setAutoCommit(true);
    }
  }

  public synchronized boolean remove(
      String kind, String uuid, String actor, String reason, long now) throws SQLException {
    db.setAutoCommit(false);
    try {
      int changed;
      try (var q = db.prepareStatement("DELETE FROM restrictions WHERE kind=? AND uuid=?")) {
        q.setString(1, kind);
        q.setString(2, uuid);
        changed = q.executeUpdate();
      }
      if (changed > 0) history(kind, uuid, actor, reason, "REMOVED", now);
      db.commit();
      return changed > 0;
    } catch (SQLException e) {
      db.rollback();
      throw e;
    } finally {
      db.setAutoCommit(true);
    }
  }

  private void history(
      String kind, String uuid, String actor, String reason, String action, long now)
      throws SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO restriction_history(kind,uuid,actor,reason,action,at)"
                + " VALUES(?,?,?,?,?,?)")) {
      q.setString(1, kind);
      q.setString(2, uuid);
      q.setString(3, actor);
      q.setString(4, reason);
      q.setString(5, action);
      q.setLong(6, now);
      q.executeUpdate();
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    db.close();
  }
}
