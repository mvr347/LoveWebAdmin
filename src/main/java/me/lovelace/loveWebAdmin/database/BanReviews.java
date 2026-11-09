package me.lovelace.loveWebAdmin.database;

import java.sql.*;
import java.util.*;

/** A live review queue over final bans. The ban remains the authoritative evidence record. */
public final class BanReviews {
  private final Connection connection;
  private final Object lock;

  public BanReviews(Connection connection, Object lock) throws SQLException {
    this.connection = connection;
    this.lock = lock;
    try (var s = connection.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS training_revocations(ban_id INTEGER PRIMARY KEY,created_at INTEGER NOT NULL,delivered INTEGER NOT NULL DEFAULT 0)");
      s.execute("CREATE TRIGGER IF NOT EXISTS training_unban_review AFTER UPDATE OF status ON web_bans WHEN NEW.status='UNBANNED' AND OLD.status!='UNBANNED' BEGIN INSERT OR REPLACE INTO training_revocations(ban_id,created_at,delivered) VALUES(NEW.id,strftime('%s','now'),0); END");
      // Replay historical successful appeals as well; delivered markers prevent replay storms.
      s.execute("INSERT OR IGNORE INTO training_revocations(ban_id,created_at) SELECT id,strftime('%s','now') FROM web_bans WHERE status='UNBANNED'");
      s.execute("CREATE INDEX IF NOT EXISTS idx_web_bans_review_reason ON web_bans(TRIM(rule_reason),status,created_at,id)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS anticheat_ban_reviews(ban_id INTEGER PRIMARY KEY,verdict TEXT"
              + " NOT NULL,reviewer TEXT NOT NULL,reason TEXT NOT NULL,reviewed_at INTEGER NOT"
              + " NULL,revision INTEGER NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS anticheat_ban_review_history(ban_id INTEGER NOT NULL,revision"
              + " INTEGER NOT NULL,verdict TEXT NOT NULL,reviewer TEXT NOT NULL,reason TEXT NOT"
              + " NULL,reviewed_at INTEGER NOT NULL,PRIMARY KEY(ban_id,revision))");
    }
  }

  public List<Integer> pendingTrainingRevocations() throws SQLException {
    synchronized(lock) {
      var ids=new ArrayList<Integer>();
      try(var q=connection.createStatement();var rows=q.executeQuery("SELECT ban_id FROM training_revocations WHERE delivered=0 ORDER BY created_at,ban_id LIMIT 5")) {
        while(rows.next())ids.add(rows.getInt(1));
      }return List.copyOf(ids);
    }
  }
  public void acknowledgeTrainingRevocation(int id) throws SQLException {
    synchronized(lock) {try(var q=connection.prepareStatement("UPDATE training_revocations SET delivered=1 WHERE ban_id=?")) {q.setInt(1,id);q.executeUpdate();}}
  }

  public Map<String, Object> list(Set<String> reasons, int offset) throws SQLException {
    synchronized (lock) {
      if (reasons.isEmpty()) return Map.of("items", List.of(), "total", 0);
      String where =
          " WHERE b.status IN ('ACTIVE','UNBANNED','EXPIRED') AND TRIM(b.rule_reason) IN ("
              + String.join(",", Collections.nCopies(reasons.size(), "?"))
              + ")";
      try (var count = connection.prepareStatement("SELECT COUNT(*) FROM web_bans b" + where);
          var q =
              connection.prepareStatement(
                  "SELECT"
                      + " b.id,b.target_name,b.rule_reason,b.creator_name,b.created_at,b.status,r.verdict,r.reviewer,r.reason"
                      + " AS review_reason,r.reviewed_at,r.revision FROM web_bans b LEFT JOIN"
                      + " anticheat_ban_reviews r ON r.ban_id=b.id"
                      + where
                      + " ORDER BY (r.ban_id IS NULL) DESC,b.created_at DESC,b.id DESC LIMIT 25"
                      + " OFFSET ?")) {
        int i = 1;
        for (var reason : reasons) {
          count.setString(i, reason);
          q.setString(i++, reason);
        }
        q.setInt(i, Math.clamp(offset, 0, 1000000));
        long total;
        try (var r = count.executeQuery()) {
          r.next();
          total = r.getLong(1);
        }
        var items = new ArrayList<Map<String, Object>>();
        try (var r = q.executeQuery()) {
          while (r.next()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", r.getInt("id"));
            row.put("name", r.getString("target_name"));
            row.put("reason", r.getString("rule_reason"));
            row.put("actor", r.getString("creator_name"));
            row.put("createdAt", r.getLong("created_at"));
            row.put("status", r.getString("status"));
            row.put("verdict", Objects.toString(r.getString("verdict"), ""));
            row.put("reviewer", Objects.toString(r.getString("reviewer"), ""));
            row.put("reviewReason", Objects.toString(r.getString("review_reason"), ""));
            row.put("reviewedAt", r.getLong("reviewed_at"));
            row.put("revision", r.getLong("revision"));
            items.add(row);
          }
        }
        return Map.of("items", items, "total", total, "pageSize", 25);
      }
    }
  }

  public boolean review(
      int id, long expected, String verdict, String reviewer, String reason, Set<String> reasons)
      throws SQLException {
    if (id <= 0
        || expected < 0
        || expected == Long.MAX_VALUE
        || !Set.of("legit", "cheat").contains(verdict)
        || reviewer == null
        || reviewer.isBlank()
        || reason == null
        || reason.trim().length() < 5
        || reason.length() > 2000)
      throw new IllegalArgumentException("Укажите вердикт и обоснование");
    synchronized (lock) {
      if (!connection.getAutoCommit())
        throw new IllegalStateException("Вложенная операция разбора недоступна");
      connection.setAutoCommit(false);
      try {
        try (var q =
            connection.prepareStatement("SELECT rule_reason,status FROM web_bans WHERE id=?")) {
          q.setInt(1, id);
          try (var r = q.executeQuery()) {
            if (!r.next()
                || !reasons.contains(r.getString("rule_reason").trim())
                || !Set.of("ACTIVE", "UNBANNED", "EXPIRED").contains(r.getString("status"))) {
              connection.rollback();
              return false;
            }
          }
        }
        long at = System.currentTimeMillis() / 1000, next = expected + 1;
        int changed;
        if (expected == 0) {
          try (var q =
              connection.prepareStatement(
                  "INSERT INTO anticheat_ban_reviews VALUES(?,?,?,?,?,1) ON CONFLICT(ban_id) DO"
                      + " NOTHING")) {
            q.setInt(1, id);
            q.setString(2, verdict);
            q.setString(3, reviewer);
            q.setString(4, reason.trim());
            q.setLong(5, at);
            changed = q.executeUpdate();
          }
        } else {
          try (var q =
              connection.prepareStatement(
                  "UPDATE anticheat_ban_reviews SET"
                      + " verdict=?,reviewer=?,reason=?,reviewed_at=?,revision=? WHERE ban_id=? AND"
                      + " revision=?")) {
            q.setString(1, verdict);
            q.setString(2, reviewer);
            q.setString(3, reason.trim());
            q.setLong(4, at);
            q.setLong(5, next);
            q.setInt(6, id);
            q.setLong(7, expected);
            changed = q.executeUpdate();
          }
        }
        if (changed != 1) {
          connection.rollback();
          return false;
        }
        try (var q =
            connection.prepareStatement(
                "INSERT INTO anticheat_ban_review_history VALUES(?,?,?,?,?,?)")) {
          q.setInt(1, id);
          q.setLong(2, next);
          q.setString(3, verdict);
          q.setString(4, reviewer);
          q.setString(5, reason.trim());
          q.setLong(6, at);
          q.executeUpdate();
        }
        if("legit".equals(verdict))try(var q=connection.prepareStatement("INSERT OR REPLACE INTO training_revocations(ban_id,created_at,delivered) VALUES(?,?,0)")){
          q.setInt(1,id);q.setLong(2,at);q.executeUpdate();
        }
        connection.commit();
        return true;
      } catch (SQLException | RuntimeException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }
}
