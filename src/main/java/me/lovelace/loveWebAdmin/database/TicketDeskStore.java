package me.lovelace.loveWebAdmin.database;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

/**
 * Durable Discord UI identities, creation intents and transcripts; no Discord tokens are stored.
 */
public final class TicketDeskStore implements AutoCloseable {
  private final Connection db;

  public record Ticket(
      String kind,
      int id,
      String owner,
      String channel,
      String state,
      String actor,
      String reason,
      String card,
      String queueCard,
      String acl) {}

  public record Draft(
      String id, String owner, String kind, Map<String, Object> data, String state, int caseId) {}

  public TicketDeskStore(Path file) throws SQLException, java.io.IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    try (var q = db.createStatement()) {
      q.execute("PRAGMA busy_timeout=5000");
      q.execute("PRAGMA journal_mode=WAL");
      q.execute("PRAGMA synchronous=FULL");
      q.execute(
          "CREATE TABLE IF NOT EXISTS desk_messages(slot TEXT PRIMARY KEY,channel TEXT NOT NULL,message TEXT,state TEXT NOT NULL DEFAULT 'READY',error TEXT)");
      q.execute(
          "CREATE TABLE IF NOT EXISTS desk_drafts(id TEXT PRIMARY KEY,owner TEXT NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'DRAFT',case_id INTEGER NOT NULL DEFAULT 0,expires_at INTEGER NOT NULL)");
      q.execute("CREATE INDEX IF NOT EXISTS desk_drafts_expiry ON desk_drafts(expires_at)");
      q.execute(
          "CREATE TABLE IF NOT EXISTS desk_tickets(kind TEXT NOT NULL,id INTEGER NOT NULL,owner TEXT NOT NULL,channel TEXT NOT NULL DEFAULT '',state TEXT NOT NULL DEFAULT 'OPEN',actor TEXT NOT NULL DEFAULT '',reason TEXT NOT NULL DEFAULT '',card TEXT NOT NULL DEFAULT '',queue_card TEXT NOT NULL DEFAULT '',acl TEXT NOT NULL DEFAULT '',notified INTEGER NOT NULL DEFAULT 0,creation_started INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))");
      q.execute(
          "CREATE TABLE IF NOT EXISTS desk_transcript(kind TEXT NOT NULL,id INTEGER NOT NULL,message_id TEXT NOT NULL,author_id TEXT NOT NULL,author_name TEXT NOT NULL,body TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(kind,id,message_id))");
      q.execute(
          "CREATE TABLE IF NOT EXISTS desk_files(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,case_id INTEGER NOT NULL,message_id TEXT NOT NULL,url TEXT NOT NULL,name TEXT NOT NULL,data BLOB NOT NULL,UNIQUE(kind,case_id,message_id,url))");
      q.execute("CREATE INDEX IF NOT EXISTS desk_files_case ON desk_files(kind,case_id)");
      q.execute(
          "UPDATE desk_messages SET state='UNCERTAIN',error='Отправка прервана. Сверьте сообщение в Discord.' WHERE state='SENDING'");
    } catch (SQLException error) {
      db.close();
      throw error;
    }
  }

  public synchronized String draft(String owner, String kind, Map<String, Object> payload)
      throws SQLException {
    try (var q =
        db.prepareStatement(
            "DELETE FROM desk_drafts WHERE expires_at<? AND state IN ('DRAFT','CANCELLED','CONFIRMED')")) {
      q.setLong(1, System.currentTimeMillis() / 1000);
      q.executeUpdate();
    }
    try (var q =
        db.prepareStatement(
            "SELECT COUNT(*) FROM desk_drafts WHERE owner=? AND state IN ('DRAFT','CREATING')")) {
      q.setString(1, owner);
      try (var r = q.executeQuery()) {
        if (r.next() && r.getInt(1) >= 3)
          throw new IllegalArgumentException(
              "Завершите предыдущие обращения перед созданием новых");
      }
    }
    String id = UUID.randomUUID().toString().replace("-", "");
    try (var q =
        db.prepareStatement(
            "INSERT INTO desk_drafts(id,owner,kind,payload,expires_at) VALUES(?,?,?,?,?)")) {
      q.setString(1, id);
      q.setString(2, owner);
      q.setString(3, kind);
      q.setString(4, JsonUtils.toJson(payload));
      q.setLong(5, System.currentTimeMillis() / 1000 + 600);
      q.executeUpdate();
    }
    return id;
  }

  public synchronized Draft draft(String id, String owner) throws SQLException {
    try (var q =
        db.prepareStatement("SELECT * FROM desk_drafts WHERE id=? AND owner=? AND expires_at>=?")) {
      q.setString(1, id);
      q.setString(2, owner);
      q.setLong(3, System.currentTimeMillis() / 1000);
      try (var r = q.executeQuery()) {
        if (!r.next())
          throw new IllegalArgumentException("Форма истекла или принадлежит другому игроку");
        return new Draft(
            id,
            owner,
            r.getString("kind"),
            JsonUtils.parseObject(r.getString("payload")),
            r.getString("state"),
            r.getInt("case_id"));
      }
    }
  }

  public synchronized boolean consume(String id, String owner, boolean cancel) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE desk_drafts SET state=? WHERE id=? AND owner=? AND state='DRAFT' AND expires_at>=?")) {
      q.setString(1, cancel ? "CANCELLED" : "CREATING");
      q.setString(2, id);
      q.setString(3, owner);
      q.setLong(4, System.currentTimeMillis() / 1000);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized void rejectDraft(String id) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE desk_drafts SET state='CANCELLED' WHERE id=? AND state='CREATING'")) {
      q.setString(1, id);
      q.executeUpdate();
    }
  }

  public synchronized void completeDraft(String id, int caseId) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE desk_drafts SET state='CONFIRMED',case_id=? WHERE id=? AND state='CREATING'")) {
      q.setInt(1, caseId);
      q.setString(2, id);
      if (q.executeUpdate() != 1) throw new SQLException("Creation intent changed");
    }
  }

  public synchronized void register(String kind, int id, String owner) throws SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO desk_tickets(kind,id,owner) VALUES(?,?,?) ON CONFLICT(kind,id) DO NOTHING")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setString(3, Objects.toString(owner, ""));
      q.executeUpdate();
    }
  }

  private Ticket ticket(ResultSet r) throws SQLException {
    return new Ticket(
        r.getString("kind"),
        r.getInt("id"),
        r.getString("owner"),
        r.getString("channel"),
        r.getString("state"),
        r.getString("actor"),
        r.getString("reason"),
        r.getString("card"),
        r.getString("queue_card"),
        r.getString("acl"));
  }

  public synchronized Optional<Ticket> get(String kind, int id) throws SQLException {
    try (var q = db.prepareStatement("SELECT * FROM desk_tickets WHERE kind=? AND id=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      try (var r = q.executeQuery()) {
        return r.next() ? Optional.of(ticket(r)) : Optional.empty();
      }
    }
  }

  public synchronized List<Ticket> active() throws SQLException {
    var rows = new ArrayList<Ticket>();
    try (var q = db.createStatement();
        var r =
            q.executeQuery(
                "SELECT * FROM desk_tickets WHERE state<>'CLOSED' OR notified=0 ORDER BY id LIMIT 1000")) {
      while (r.next()) rows.add(ticket(r));
    }
    return rows;
  }

  public synchronized void startCreation(String kind, int id) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE desk_tickets SET creation_started=1 WHERE kind=? AND id=? AND state='OPEN'")) {
      q.setString(1, kind);
      q.setInt(2, id);
      if (q.executeUpdate() != 1) throw new IllegalStateException("Обращение уже закрывается");
    }
  }

  public synchronized boolean creationStarted(String kind, int id) throws SQLException {
    try (var q =
        db.prepareStatement("SELECT creation_started FROM desk_tickets WHERE kind=? AND id=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      try (var r = q.executeQuery()) {
        return r.next() && r.getBoolean(1);
      }
    }
  }

  public synchronized void channel(String kind, int id, String channel) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE desk_tickets SET channel=? WHERE kind=? AND id=? AND state IN ('OPEN','CLOSING')")) {
      q.setString(1, channel);
      q.setString(2, kind);
      q.setInt(3, id);
      if (q.executeUpdate() != 1) throw new SQLException("Ticket not open");
    }
  }

  public synchronized void display(String kind, int id, String card, String queue, String acl)
      throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE desk_tickets SET card=?,queue_card=?,acl=? WHERE kind=? AND id=?")) {
      q.setString(1, card);
      q.setString(2, queue);
      q.setString(3, acl);
      q.setString(4, kind);
      q.setInt(5, id);
      q.executeUpdate();
    }
  }

  public synchronized boolean beginClose(String kind, int id, String actor, String reason)
      throws SQLException {
    if (reason == null || reason.trim().length() < 5 || reason.length() > 4000)
      throw new IllegalArgumentException("Объясните причину закрытия (5–4000 символов)");
    try (var q =
        db.prepareStatement(
            "UPDATE desk_tickets SET state='CLOSING',actor=?,reason=? WHERE kind=? AND id=? AND state='OPEN'")) {
      q.setString(1, actor);
      q.setString(2, reason.trim());
      q.setString(3, kind);
      q.setInt(4, id);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized boolean transition(String kind, int id, String from, String to)
      throws SQLException {
    if (!Set.of("CLOSING:ARCHIVED", "ARCHIVED:CLOSED").contains(from + ":" + to))
      throw new IllegalArgumentException("Invalid transition");
    try (var q =
        db.prepareStatement("UPDATE desk_tickets SET state=? WHERE kind=? AND id=? AND state=?")) {
      q.setString(1, to);
      q.setString(2, kind);
      q.setInt(3, id);
      q.setString(4, from);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized void archiveMessage(
      String kind, int id, String message, String author, String authorName, String text)
      throws SQLException {
    long time = (Long.parseUnsignedLong(message) >>> 22) + 1420070400000L;
    try (var q =
        db.prepareStatement(
            "INSERT INTO desk_transcript(kind,id,message_id,author_id,author_name,body,created_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(kind,id,message_id) DO UPDATE SET body=excluded.body,author_name=excluded.author_name")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setString(3, message);
      q.setString(4, author);
      q.setString(5, authorName);
      q.setString(6, text);
      q.setLong(7, time / 1000);
      q.executeUpdate();
    }
  }

  public synchronized boolean hasFile(String kind, int id, String message, String url)
      throws SQLException {
    try (var q =
        db.prepareStatement(
            "SELECT 1 FROM desk_files WHERE kind=? AND case_id=? AND message_id=? AND url=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setString(3, message);
      q.setString(4, url.split("\\?", 2)[0]);
      try (var r = q.executeQuery()) {
        return r.next();
      }
    }
  }

  public synchronized void archiveFile(
      String kind, int id, String message, String url, String name, byte[] bytes)
      throws SQLException {
    if (bytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("Вложение больше 8 МиБ");
    try (var q =
        db.prepareStatement(
            "SELECT COALESCE(SUM(length(data)),0) FROM desk_files WHERE kind=? AND case_id=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      try (var r = q.executeQuery()) {
        if (r.next() && r.getLong(1) + bytes.length > 256L * 1024 * 1024)
          throw new IllegalArgumentException("Архив вложений превысил 256 МиБ; канал сохранён");
      }
    }
    try (var q =
        db.prepareStatement(
            "INSERT INTO desk_files(kind,case_id,message_id,url,name,data) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setString(3, message);
      q.setString(4, url.split("\\?", 2)[0]);
      q.setString(5, name);
      q.setBytes(6, bytes);
      q.executeUpdate();
    }
  }

  public synchronized List<Map<String, Object>> transcript(String kind, int id, int offset)
      throws SQLException {
    var rows = new ArrayList<Map<String, Object>>();
    try (var q =
        db.prepareStatement(
            "SELECT * FROM desk_transcript WHERE kind=? AND id=? ORDER BY created_at,message_id LIMIT 100 OFFSET ?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setInt(3, Math.max(0, offset));
      try (var r = q.executeQuery()) {
        while (r.next())
          rows.add(
              Map.of(
                  "messageId",
                  r.getString("message_id"),
                  "author",
                  r.getString("author_name"),
                  "authorId",
                  r.getString("author_id"),
                  "text",
                  r.getString("body"),
                  "createdAt",
                  r.getLong("created_at")));
      }
    }
    return rows;
  }

  public synchronized List<Map<String, Object>> files(String kind, int id) throws SQLException {
    var rows = new ArrayList<Map<String, Object>>();
    try (var q =
        db.prepareStatement(
            "SELECT id,message_id,name,length(data) AS size FROM desk_files WHERE kind=? AND case_id=? ORDER BY id LIMIT 1000")) {
      q.setString(1, kind);
      q.setInt(2, id);
      try (var r = q.executeQuery()) {
        while (r.next())
          rows.add(
              Map.of(
                  "id",
                  r.getInt("id"),
                  "messageId",
                  r.getString("message_id"),
                  "name",
                  r.getString("name"),
                  "size",
                  r.getInt("size")));
      }
    }
    return rows;
  }

  public synchronized Optional<byte[]> file(String kind, int id, int file) throws SQLException {
    try (var q =
        db.prepareStatement("SELECT data FROM desk_files WHERE kind=? AND case_id=? AND id=?")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.setInt(3, file);
      try (var r = q.executeQuery()) {
        return r.next() ? Optional.of(r.getBytes(1)) : Optional.empty();
      }
    }
  }

  public synchronized Optional<Map<String, String>> message(String slot) throws SQLException {
    try (var q = db.prepareStatement("SELECT * FROM desk_messages WHERE slot=?")) {
      q.setString(1, slot);
      try (var r = q.executeQuery()) {
        return r.next()
            ? Optional.of(
                Map.of(
                    "channel",
                    r.getString("channel"),
                    "message",
                    Objects.toString(r.getString("message"), ""),
                    "state",
                    r.getString("state"),
                    "error",
                    Objects.toString(r.getString("error"), "")))
            : Optional.empty();
      }
    }
  }

  public synchronized boolean sending(String slot, String channel) throws SQLException {
    try (var q =
        db.prepareStatement(
            "INSERT INTO desk_messages(slot,channel,state) VALUES(?,?,'SENDING') ON CONFLICT(slot) DO UPDATE SET state='SENDING',message=CASE WHEN desk_messages.channel=excluded.channel THEN desk_messages.message ELSE NULL END,channel=excluded.channel WHERE desk_messages.state IN ('READY','FAILED')")) {
      q.setString(1, slot);
      q.setString(2, channel);
      return q.executeUpdate() == 1;
    }
  }

  public synchronized void sent(String slot, String message) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE desk_messages SET state='READY',message=?,error=NULL WHERE slot=?")) {
      q.setString(1, message);
      q.setString(2, slot);
      q.executeUpdate();
    }
  }

  public synchronized void failed(String slot, String error) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE desk_messages SET state='FAILED',error=? WHERE slot=? AND state='SENDING'")) {
      q.setString(1, error);
      q.setString(2, slot);
      q.executeUpdate();
    }
  }

  public synchronized void uncertain(String slot, String error) throws SQLException {
    try (var q =
        db.prepareStatement("UPDATE desk_messages SET state='UNCERTAIN',error=? WHERE slot=?")) {
      q.setString(1, error);
      q.setString(2, slot);
      q.executeUpdate();
    }
  }

  public synchronized void notified(String kind, int id) throws SQLException {
    try (var q =
        db.prepareStatement(
            "UPDATE desk_tickets SET notified=1 WHERE kind=? AND id=? AND state='CLOSED'")) {
      q.setString(1, kind);
      q.setInt(2, id);
      q.executeUpdate();
    }
    try (var q = db.prepareStatement("DELETE FROM desk_messages WHERE slot=?")) {
      q.setString(1, "case:" + kind + ":" + id);
      q.executeUpdate();
    }
  }

  public synchronized void refreshClosedQueue(String slot) throws SQLException {
    String[] parts = slot.split(":");
    if (parts.length != 3
        || !parts[0].equals("queue")
        || !Set.of("report", "appeal").contains(parts[1])
        || !parts[2].matches("[0-9]{1,10}")) return;
    try (var q =
        db.prepareStatement(
            "UPDATE desk_tickets SET notified=0 WHERE kind=? AND id=? AND state='CLOSED'")) {
      q.setString(1, parts[1]);
      q.setInt(2, Integer.parseInt(parts[2]));
      q.executeUpdate();
    }
  }

  public synchronized List<Map<String, String>> messages() throws SQLException {
    var result = new ArrayList<Map<String, String>>();
    try (var q = db.createStatement();
        var r =
            q.executeQuery(
                "SELECT slot,channel,message,state,error FROM desk_messages ORDER BY slot LIMIT 2000")) {
      while (r.next())
        result.add(
            Map.of(
                "slot",
                r.getString("slot"),
                "channel",
                r.getString("channel"),
                "message",
                Objects.toString(r.getString("message"), ""),
                "state",
                r.getString("state"),
                "error",
                Objects.toString(r.getString("error"), "")));
    }
    return result;
  }

  public synchronized boolean resolveMessage(String slot, String message, String reason)
      throws SQLException {
    if (reason == null || reason.trim().length() < 5 || reason.length() > 2000)
      throw new IllegalArgumentException("Укажите результат ручной проверки");
    if (message != null && !message.isBlank() && !message.matches("[0-9]{5,25}"))
      throw new IllegalArgumentException("Некорректный ID сообщения");
    try (var q =
        db.prepareStatement(
            "UPDATE desk_messages SET state='READY',message=?,error=NULL WHERE slot=? AND state='UNCERTAIN'")) {
      q.setString(1, message == null || message.isBlank() ? null : message);
      q.setString(2, slot);
      return q.executeUpdate() == 1;
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    db.close();
  }
}
