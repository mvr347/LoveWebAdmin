package me.lovelace.loveWebAdmin.workflow;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/** Durable waiting periods, editable drafts and immutable review requests. */
public final class CaseWorkflowStore implements AutoCloseable {
  private final Connection db;

  public record Wait(
      String mode, long since, long version, int stage, String work, String message) {}

  public record Prepared(
      String key,
      String actor,
      Map<String, Object> payload,
      String hash,
      String state,
      String result) {}

  public CaseWorkflowStore(Path file) throws Exception {
    Files.createDirectories(file.toAbsolutePath().getParent());
    db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    try (var q = db.createStatement()) {
      q.execute("PRAGMA journal_mode=WAL");
      q.execute("PRAGMA busy_timeout=5000");
      q.execute("PRAGMA synchronous=FULL");
      q.execute(
          "CREATE TABLE IF NOT EXISTS waits(kind TEXT,id INTEGER,mode TEXT NOT NULL,since INTEGER NOT NULL,version INTEGER NOT NULL DEFAULT 0,stage INTEGER NOT NULL DEFAULT 0,work TEXT NOT NULL,message TEXT NOT NULL,PRIMARY KEY(kind,id))");
      q.execute(
          "CREATE TABLE IF NOT EXISTS workflow_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
      q.execute(
          "CREATE TABLE IF NOT EXISTS case_templates(id INTEGER PRIMARY KEY,owner TEXT NOT NULL,purpose TEXT NOT NULL,name TEXT NOT NULL,body TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0)");
      q.execute(
          "CREATE TABLE IF NOT EXISTS decision_previews(key TEXT PRIMARY KEY,actor TEXT NOT NULL,payload TEXT NOT NULL,hash TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'DRAFT',result TEXT NOT NULL DEFAULT '',expires INTEGER NOT NULL)");
      q.execute(
          "CREATE TABLE IF NOT EXISTS case_reviews(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,case_id INTEGER NOT NULL,actor TEXT NOT NULL,body TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'PENDING',staff TEXT NOT NULL DEFAULT '',explanation TEXT NOT NULL DEFAULT '',new_kind TEXT NOT NULL DEFAULT '',new_id INTEGER NOT NULL DEFAULT 0,image_name TEXT NOT NULL DEFAULT '',image BLOB,created INTEGER NOT NULL,notified_state TEXT NOT NULL DEFAULT '')");
      q.execute(
          "CREATE UNIQUE INDEX IF NOT EXISTS one_pending_review ON case_reviews(kind,case_id) WHERE state IN ('PENDING','PROCESSING')");
    } catch (Exception error) {
      db.close();
      throw error;
    }
    try {
      seed();
    } catch (Exception error) {
      db.close();
      throw error;
    }
  }

  private void seed() throws SQLException {
    try (var q = db.createStatement();
        var r = q.executeQuery("SELECT value FROM workflow_meta WHERE key='templates-v1'")) {
      if (r.next()) return;
    }
    db.setAutoCommit(false);
    try {
      template(
          "*",
          0,
          0,
          "CLARIFY",
          "Уточнить время",
          "Укажите дату и примерное время ситуации, а также место, где она произошла.");
      template(
          "*",
          0,
          0,
          "CLARIFY",
          "Запросить доказательства",
          "Приложите скриншот или ссылку на видео и поясните, какой момент нужно проверить.");
      template(
          "*",
          0,
          0,
          "VERDICT",
          "Недостаточно доказательств",
          "По представленным материалам недостаточно оснований для изменения решения. Вы можете направить новые доказательства.");
      template(
          "*",
          0,
          0,
          "VERDICT",
          "Наказание применено ошибочно",
          "Контекст ситуации проверен: наказание применено ошибочно. Решение подлежит пересмотру.");
      try (var q = db.createStatement()) {
        q.execute("INSERT INTO workflow_meta(key,value) VALUES('templates-v1','1')");
      }
      db.commit();
    } catch (SQLException | RuntimeException error) {
      db.rollback();
      throw error;
    } finally {
      db.setAutoCommit(true);
    }
  }

  public synchronized Wait observe(
      String kind, int id, boolean open, String work, String message, boolean lastStaff, long now)
      throws SQLException {
    Wait old = waitFor(kind, id).orElse(null);
    String mode = !open ? "NONE" : work.equals("WAITING") ? "PLAYER" : "STAFF";
    long since = old == null ? now : old.since(), version = old == null ? 0 : old.version();
    int stage = old == null ? 0 : old.stage();
    if (old != null && open) {
      mode = old.mode().equals("NONE") ? (work.equals("WAITING") ? "PLAYER" : "STAFF") : old.mode();
      if (!old.message().equals(message) && !message.isBlank())
        mode = lastStaff ? "PLAYER" : "STAFF";
      if (!old.work().equals(work)) mode = work.equals("WAITING") ? "PLAYER" : "STAFF";
    }
    if (old != null && !old.mode().equals(mode)) {
      since = now;
      version++;
      stage = 0;
    }
    try (var q =
        db.prepareStatement(
            "INSERT INTO waits(kind,id,mode,since,version,stage,work,message) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET mode=excluded.mode,since=excluded.since,version=excluded.version,stage=excluded.stage,work=excluded.work,message=excluded.message")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setString(3, mode);
      q.setLong(4, since);
      q.setLong(5, version);
      q.setInt(6, stage);
      q.setString(7, work);
      q.setString(8, message);
      q.executeUpdate();
    }
    return new Wait(mode, since, version, stage, work, message);
  }

  public synchronized Optional<Wait> waitFor(String kind, int id) throws SQLException {
    try (var q = db.prepareStatement("SELECT * FROM waits WHERE kind=? AND id=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      try (var r = q.executeQuery()) {
        return r.next()
            ? Optional.of(
                new Wait(
                    r.getString("mode"),
                    r.getLong("since"),
                    r.getLong("version"),
                    r.getInt("stage"),
                    r.getString("work"),
                    r.getString("message")))
            : Optional.empty();
      }
    }
  }

  public synchronized boolean advance(String kind, int id, long version, int stage)
      throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE waits SET stage=? WHERE kind=? AND id=? AND version=? AND stage<? AND mode<>'NONE'")) {
      q.setInt(1, stage);
      q.setString(2, kind);
      q.setInt(3, id);
      q.setLong(4, version);
      q.setInt(5, stage);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized List<Map<String, Object>> templates(String actor) throws SQLException {
    var rows = new ArrayList<Map<String, Object>>();
    try (var q =
        db.prepareStatement(
            "SELECT * FROM case_templates WHERE owner='*' OR owner=? ORDER BY (owner='*'),id LIMIT 200")) {
      q.setString(1, actor);
      try (var r = q.executeQuery()) {
        while (r.next())
          rows.add(
              Map.of(
                  "id",
                  r.getInt("id"),
                  "owner",
                  r.getString("owner"),
                  "purpose",
                  r.getString("purpose"),
                  "name",
                  r.getString("name"),
                  "text",
                  r.getString("body"),
                  "revision",
                  r.getLong("revision")));
      }
    }
    return rows;
  }

  public synchronized void template(
      String owner, int id, long revision, String purpose, String name, String text)
      throws SQLException {
    if (!Set.of("CLARIFY", "VERDICT").contains(purpose)
        || name == null
        || name.isBlank()
        || name.length() > 60
        || text == null
        || text.trim().length() < 5
        || text.length() > 2000)
      throw new IllegalArgumentException("Шаблон: название до 60 символов, текст 5–2000 символов");
    if (id == 0) {
      try (var q =
          db.prepareStatement(
              "INSERT INTO case_templates(owner,purpose,name,body) VALUES(?,?,?,?)")) {
        q.setString(1, owner);
        q.setString(2, purpose);
        q.setString(3, name.trim());
        q.setString(4, text.trim());
        q.executeUpdate();
      }
    } else
      try (var q =
          db.prepareStatement(
              "UPDATE case_templates SET purpose=?,name=?,body=?,revision=revision+1 WHERE id=? AND owner=? AND revision=?")) {
        q.setString(1, purpose);
        q.setString(2, name.trim());
        q.setString(3, text.trim());
        q.setInt(4, id);
        q.setString(5, owner);
        q.setLong(6, revision);
        if (q.executeUpdate() != 1)
          throw new IllegalArgumentException("Шаблон изменён или недоступен; обновите список");
      }
  }

  public synchronized boolean removeTemplate(String owner, int id, long revision)
      throws SQLException {
    try (var q =
        db.prepareStatement("DELETE FROM case_templates WHERE owner=? AND id=? AND revision=?")) {
      q.setString(1, owner);
      q.setInt(2, id);
      q.setLong(3, revision);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized String prepare(
      String actor, Map<String, Object> payload, String hash, long now) throws SQLException {
    try (var q =
        db.prepareStatement(
            "DELETE FROM decision_previews WHERE expires<? AND state IN ('DRAFT','DONE','CANCELLED')")) {
      q.setLong(1, now);
      q.executeUpdate();
    }
    try (var q =
        db.prepareStatement(
            "SELECT COUNT(*) FROM decision_previews WHERE actor=? AND state='DRAFT'")) {
      q.setString(1, actor);
      try (var r = q.executeQuery()) {
        if (r.next() && r.getInt(1) >= 10)
          throw new IllegalArgumentException("Завершите предыдущие предпросмотры решения");
      }
    }
    String key = UUID.randomUUID().toString().replace("-", "");
    try (var q =
        db.prepareStatement(
            "INSERT INTO decision_previews(key,actor,payload,hash,expires) VALUES(?,?,?,?,?)")) {
      q.setString(1, key);
      q.setString(2, actor);
      q.setString(3, JsonUtils.toJson(payload));
      q.setString(4, hash);
      q.setLong(5, now + 600);
      q.executeUpdate();
    }
    return key;
  }

  public synchronized Prepared prepared(String key, String actor, long now) throws SQLException {
    try (var q =
        db.prepareStatement(
            "SELECT * FROM decision_previews WHERE key=? AND actor=? AND expires>=?")) {
      q.setString(1, key);
      q.setString(2, actor);
      q.setLong(3, now);
      try (var r = q.executeQuery()) {
        if (!r.next())
          throw new IllegalArgumentException(
              "Предпросмотр истёк или принадлежит другому сотруднику");
        return new Prepared(
            key,
            actor,
            JsonUtils.parseObject(r.getString("payload")),
            r.getString("hash"),
            r.getString("state"),
            r.getString("result"));
      }
    }
  }

  public synchronized void cancelDecision(String key, String actor) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE decision_previews SET state='CANCELLED' WHERE key=? AND actor=? AND state='DRAFT'")) {
      q.setString(1, key);
      q.setString(2, actor);
      q.executeUpdate();
    }
  }

  public synchronized boolean reserveDecision(String key, String actor) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE decision_previews SET state='APPLYING' WHERE key=? AND actor=? AND state='DRAFT'")) {
      q.setString(1, key);
      q.setString(2, actor);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized void decisionDone(String key, String result) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE decision_previews SET state='DONE',result=? WHERE key=? AND state='APPLYING'")) {
      q.setString(1, result);
      q.setString(2, key);
      q.executeUpdate();
    }
  }

  public synchronized int requestReview(
      String kind, int id, String actor, String text, String imageName, byte[] image, long now)
      throws SQLException {
    if (text == null
        || text.trim().length() < 10
        || text.length() > 2000
        || image != null && image.length > 700000)
      throw new IllegalArgumentException(
          "Опишите новые доказательства (10–2000 символов), скриншот до 700 КБ");
    try (var q =
        db.prepareStatement(
            "INSERT INTO case_reviews(kind,case_id,actor,body,image_name,image,created) VALUES(?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setString(3, actor);
      q.setString(4, text.trim());
      q.setString(5, imageName);
      q.setBytes(6, image);
      q.setLong(7, now);
      q.executeUpdate();
      try (var r = q.getGeneratedKeys()) {
        if (r.next()) return r.getInt(1);
      }
    } catch (SQLException error) {
      if (error.getErrorCode() == 19)
        throw new me.lovelace.loveWebAdmin.utils.ActionRejectedException(
            "Новые доказательства по этому делу уже ожидают рассмотрения");
      throw error;
    }
    return -1;
  }

  public synchronized List<Map<String, Object>> reviews(String kind, int id) throws SQLException {
    var rows = new ArrayList<Map<String, Object>>();
    try (var q =
        db.prepareStatement(
            "SELECT id,kind,case_id,actor,body,state,staff,explanation,new_kind,new_id,created,image_name,(image IS NOT NULL) AS has_image FROM case_reviews WHERE kind=? AND case_id=? ORDER BY id DESC LIMIT 50")) {
      q.setString(1, kind);
      q.setInt(2, id);
      try (var r = q.executeQuery()) {
        while (r.next()) {
          var row = new LinkedHashMap<String, Object>();
          for (String field :
              List.of(
                  "id",
                  "kind",
                  "case_id",
                  "actor",
                  "body",
                  "state",
                  "staff",
                  "explanation",
                  "new_kind",
                  "new_id",
                  "created",
                  "image_name")) row.put(field, r.getObject(field));
          row.put("hasImage", r.getBoolean("has_image"));
          rows.add(row);
        }
      }
    }
    return rows;
  }

  public synchronized Optional<byte[]> reviewImage(String kind, int id, int review)
      throws SQLException {
    try (var q =
        db.prepareStatement("SELECT image FROM case_reviews WHERE kind=? AND case_id=? AND id=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setInt(3, review);
      try (var r = q.executeQuery()) {
        return r.next() ? Optional.ofNullable(r.getBytes(1)) : Optional.empty();
      }
    }
  }

  public synchronized boolean reserveReview(
      int request, String kind, int id, String staff, String reason, boolean accept)
      throws SQLException {
    if (reason == null || reason.trim().length() < 5 || reason.length() > 2000)
      throw new IllegalArgumentException("Объясните решение по дополнению");
    try (var q =
        db.prepareStatement(
            "UPDATE case_reviews SET state=?,staff=?,explanation=? WHERE id=? AND kind=? AND case_id=? AND state='PENDING'")) {
      q.setString(1, accept ? "PROCESSING" : "REJECTED");
      q.setString(2, staff);
      q.setString(3, reason);
      q.setInt(4, request);
      q.setString(5, kind);
      q.setInt(6, id);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized void child(int review, String kind, int id) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE case_reviews SET new_kind=?,new_id=? WHERE id=? AND state='PROCESSING'")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setInt(3, review);
      q.executeUpdate();
    }
  }

  public synchronized void accepted(int review) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE case_reviews SET state='ACCEPTED' WHERE id=? AND state='PROCESSING'")) {
      q.setInt(1, review);
      q.executeUpdate();
    }
  }

  public synchronized Optional<Map<String, Object>> parent(String kind, int id)
      throws SQLException {
    try (var q =
        db.prepareStatement(
            "SELECT kind,case_id,id FROM case_reviews WHERE new_kind=? AND new_id=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      try (var r = q.executeQuery()) {
        return r.next()
            ? Optional.of(Map.of("kind", r.getString(1), "id", r.getInt(2), "review", r.getInt(3)))
            : Optional.empty();
      }
    }
  }

  public synchronized List<Map<String, Object>> notices() throws SQLException {
    var rows = new ArrayList<Map<String, Object>>();
    try (var q = db.createStatement();
        var r =
            q.executeQuery(
                "SELECT id,kind,case_id,state FROM case_reviews WHERE state='PENDING' OR notified_state<>state ORDER BY CASE WHEN notified_state<>state THEN 0 ELSE 1 END,id DESC LIMIT 100")) {
      while (r.next())
        rows.add(
            Map.of(
                "id",
                r.getInt(1),
                "kind",
                r.getString(2),
                "case_id",
                r.getInt(3),
                "state",
                r.getString(4)));
    }
    return rows;
  }

  public synchronized void noticeDone(int id, String state) throws SQLException {
    try (var q = db.prepareStatement("UPDATE case_reviews SET notified_state=? WHERE id=?")) {
      q.setString(1, state);
      q.setInt(2, id);
      q.executeUpdate();
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    db.close();
  }
}
