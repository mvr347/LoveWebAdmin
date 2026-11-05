package me.lovelace.loveWebAdmin.database;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.workflow.CaseWaitPolicy;

/**
 * Short read-only queries over existing stores. No case bodies or delivery payloads are returned.
 */
public final class WorkSummaryStore implements AutoCloseable {
  private final Connection db;
  private String bucket = "";
  private int offset;

  public WorkSummaryStore(Path folder) throws SQLException {
    db =
        org.sqlite.JDBC.createConnection(
            "jdbc:sqlite:" + folder.resolve("database.db"), new Properties());
    try {
      for (var entry :
          Map.of("moderation", "moderation.db", "workflow", "case-workflows.db").entrySet()) {
        if (!Files.isRegularFile(folder.resolve(entry.getValue())))
          throw new SQLException("Не найдено хранилище сводки");
        try (var q = db.prepareStatement("ATTACH DATABASE ? AS " + entry.getKey())) {
          q.setString(1, folder.resolve(entry.getValue()).toString());
          q.execute();
        }
      }
      try (var q = db.createStatement()) {
        q.execute("PRAGMA busy_timeout=3000");
        q.execute("PRAGMA query_only=ON");
      }
    } catch (SQLException error) {
      db.close();
      throw error;
    }
  }

  public synchronized Map<String, Object> summary(
      String actor, boolean mine, Set<Permission> rights, long now, CaseWaitPolicy policy)
      throws SQLException {
    return summary(actor, mine, rights, now, policy, "", 0);
  }

  public synchronized Map<String, Object> summary(
      String actor,
      boolean mine,
      Set<Permission> rights,
      long now,
      CaseWaitPolicy policy,
      String bucket,
      int offset)
      throws SQLException {
    this.bucket = bucket;
    this.offset = Math.max(0, offset);
    var cards = new ArrayList<Map<String, Object>>();
    var unions = new ArrayList<String>();
    if (rights.contains(Permission.VIEW_REPORTS))
      unions.add(
          "SELECT 'report' kind,id,target_name name,created_at FROM player_reports WHERE"
              + " status='PENDING'");
    if (rights.contains(Permission.VIEW_APPEALS))
      unions.add(
          "SELECT 'appeal' kind,id,player_name name,created_at FROM ban_appeals WHERE"
              + " status='PENDING'");
    if (!unions.isEmpty()) {
      String cte =
          "WITH heads AS ("
              + String.join(" UNION ALL ", unions)
              + "), tasks AS (SELECT h.*,COALESCE(c.assignee,'') assignee,COALESCE(c.state,'OPEN')"
              + " state,COALESCE(NULLIF(w.mode,'NONE'),CASE WHEN c.state='WAITING' THEN 'PLAYER'"
              + " ELSE 'STAFF' END) mode,COALESCE(w.since,h.created_at) since FROM heads h LEFT"
              + " JOIN moderation.case_work c ON c.kind=h.kind AND c.id=h.id LEFT JOIN"
              + " workflow.waits w ON w.kind=h.kind AND w.id=h.id WHERE"
              + " COALESCE(c.state,'OPEN')<>'CLOSED') ";
      String scope = mine ? " AND assignee=?" : "";
      addCases(
          cards,
          cte,
          "unassigned",
          "Взять обращения",
          "Дела без ответственного",
          "assignee=''",
          List.of(),
          "case");
      addCases(
          cards,
          cte,
          "reply",
          "Ответить игрокам",
          "Ожидается ответ сотрудника",
          "assignee<>'' AND mode='STAFF'" + scope,
          mine ? List.of(actor) : List.of(),
          "case");
      long staff = policy.staffMinutes() * 60L, player = policy.playerMinutes() * 60L;
      addCases(
          cards,
          cte,
          "overdue",
          "Проверить сроки",
          "Превышен срок ожидания; дело не закрывается автоматически",
          "((mode='STAFF' AND "
              + staff
              + ">0 AND since+"
              + staff
              + "<=?) OR (mode='PLAYER' AND "
              + player
              + ">0 AND since+"
              + player
              + "<=?))"
              + scope,
          mine ? List.of(now, now, actor) : List.of(now, now),
          "case");
      addCases(
          cards,
          cte,
          "waiting",
          "Ожидаем игрока",
          "Уточнение отправлено; требуется ответ игрока",
          "mode='PLAYER'" + scope,
          mine ? List.of(actor) : List.of(),
          "case");
    }
    if (rights.contains(Permission.VIEW_REPORTS)) {
      String scope = mine ? " AND c.assignee=?" : "";
      String sql =
          "SELECT s.id,s.nick name,'support' kind FROM moderation.support_cases s LEFT JOIN"
              + " moderation.case_work c ON c.kind='support' AND c.id=s.id WHERE s.status='OPEN'"
              + scope;
      add(
          cards,
          "support",
          "Поддержка",
          "Открытые обращения в поддержку",
          sql,
          mine ? List.of(actor) : List.of(),
          "case");
    }
    if (rights.contains(Permission.MANAGE_API))
      add(
          cards,
          "delivery",
          "Проверить доставку",
          "Ошибка или неизвестный результат отправки; сначала сверьте результат",
          "SELECT id FROM moderation.delivery_outbox WHERE state IN ('FAILED','UNCERTAIN')",
          List.of(),
          "deliveries");
    return Map.of("cards", cards, "scope", mine ? "mine" : "all", "serverTime", now);
  }

  private void addCases(
      List<Map<String, Object>> cards,
      String cte,
      String id,
      String title,
      String explanation,
      String predicate,
      List<?> args,
      String destination)
      throws SQLException {
    add(
        cards,
        id,
        title,
        explanation,
        cte + "SELECT id,name,kind FROM tasks WHERE " + predicate,
        args,
        destination);
  }

  private void add(
      List<Map<String, Object>> cards,
      String id,
      String title,
      String explanation,
      String query,
      List<?> args,
      String destination)
      throws SQLException {
    long count;
    try (var q = db.prepareStatement("SELECT COUNT(*) FROM (" + query + ")")) {
      bind(q, args);
      try (var r = q.executeQuery()) {
        r.next();
        count = r.getLong(1);
      }
    }
    if (count == 0 && !id.equals(bucket)) return;
    int page = id.equals(bucket) ? 20 : 5, skip = id.equals(bucket) ? offset : 0;
    var items = new ArrayList<Map<String, Object>>();
    try (var q = db.prepareStatement(query + " ORDER BY id LIMIT " + page + " OFFSET " + skip)) {
      bind(q, args);
      try (var r = q.executeQuery()) {
        while (r.next()) {
          var item = new LinkedHashMap<String, Object>();
          item.put("id", r.getInt("id"));
          if (destination.equals("case")) {
            item.put("kind", r.getString("kind"));
            item.put("name", r.getString("name"));
          }
          items.add(item);
        }
      }
    }
    cards.add(
        Map.of(
            "id",
            id,
            "title",
            title,
            "description",
            explanation,
            "count",
            count,
            "items",
            items,
            "destination",
            destination,
            "nextOffset",
            skip + items.size() < count ? skip + items.size() : -1));
  }

  public static Optional<Map<String, Object>> pendingCard(
      List<me.lovelace.loveWebAdmin.moderation.RestrictionsStore.Pending> rows,
      String actor,
      boolean mine,
      long now,
      String bucket,
      int offset) {
    var pending =
        rows.stream()
            .filter(p -> p.deadline() > now && (!mine || p.actor().equals(actor)))
            .sorted(
                Comparator.comparingLong(
                    me.lovelace.loveWebAdmin.moderation.RestrictionsStore.Pending::deadline))
            .toList();
    if (pending.isEmpty()) return Optional.empty();
    int skip = bucket.equals("pending") ? Math.max(0, offset) : 0,
        size = bucket.equals("pending") ? 20 : 5;
    return Optional.of(
        Map.of(
            "id",
            "pending",
            "title",
            "Оформить баны",
            "description",
            "До снятия ближайшего предварительного бана",
            "count",
            pending.size(),
            "deadline",
            pending.getFirst().deadline(),
            "destination",
            "restrictions",
            "items",
            pending.stream()
                .skip(skip)
                .limit(size)
                .map(p -> Map.of("id", p.id(), "name", p.name()))
                .toList(),
            "nextOffset",
            skip + size < pending.size() ? skip + size : -1));
  }

  private static void bind(PreparedStatement q, List<?> args) throws SQLException {
    for (int i = 0; i < args.size(); i++) q.setObject(i + 1, args.get(i));
  }

  @Override
  public synchronized void close() throws SQLException {
    db.close();
  }
}
