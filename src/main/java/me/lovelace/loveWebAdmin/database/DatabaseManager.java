package me.lovelace.loveWebAdmin.database;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.ApiKeyRecord;
import me.lovelace.loveWebAdmin.models.EconomyAnomalyRecord;
import me.lovelace.loveWebAdmin.models.EconomySnapshotRecord;
import me.lovelace.loveWebAdmin.models.LockdownStateRecord;
import me.lovelace.loveWebAdmin.models.LogEntry;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.PlayerReport;
import me.lovelace.loveWebAdmin.models.StaffCommandLog;
import me.lovelace.loveWebAdmin.models.StaffKpiRecord;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebBan;
import me.lovelace.loveWebAdmin.models.WebhookRecord;
import me.lovelace.loveWebAdmin.models.AppealMessage;
import me.lovelace.loveWebAdmin.models.WebBanAppeal;
import me.lovelace.loveWebAdmin.models.WebRole;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import me.lovelace.loveWebAdmin.utils.PasswordUtils;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;

/**
 * Менеджер базы данных SQLite.
 * Синхронизируется на объекте Connection для безопасной конкурентной работы.
 */
public class DatabaseManager {

    private final LoveWebAdmin plugin;
    private Connection connection;

    public DatabaseManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public synchronized void initialize() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            File dbFile = new File(plugin.getDataFolder(), "database.db");

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                plugin.getLogger().severe("Драйвер SQLite не найден: " + e.getMessage());
                return;
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS web_roles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE,
                        lp_group TEXT,
                        permissions TEXT NOT NULL,
                        is_owner INTEGER DEFAULT 0
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS web_admins (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL UNIQUE,
                        password_hash TEXT,
                        role_id INTEGER NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now')),
                        last_login_at INTEGER DEFAULT 0,
                        totp_secret TEXT,
                        totp_enabled INTEGER DEFAULT 0,
                        last_2fa_at INTEGER DEFAULT 0,
                        last_2fa_ip TEXT,
                        status TEXT DEFAULT 'ACTIVE',
                        ui_preferences TEXT DEFAULT '{}',
                        backup_codes TEXT DEFAULT '[]',
                        FOREIGN KEY (role_id) REFERENCES web_roles(id)
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS web_sessions (
                        token TEXT PRIMARY KEY,
                        admin_id INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        ip TEXT,
                        user_agent TEXT,
                        created_at INTEGER DEFAULT 0,
                        last_used_at INTEGER DEFAULT 0,
                        FOREIGN KEY (admin_id) REFERENCES web_admins(id)
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS web_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        actor TEXT NOT NULL,
                        action TEXT NOT NULL,
                        timestamp INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS server_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        message TEXT NOT NULL,
                        timestamp INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS web_bans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        target_name TEXT NOT NULL,
                        target_uuid TEXT,
                        target_ip TEXT,
                        creator_name TEXT NOT NULL,
                        rule_reason TEXT NOT NULL,
                        description TEXT,
                        proof_urls TEXT,
                        is_ip_ban INTEGER DEFAULT 0,
                        status TEXT NOT NULL,
                        duration_seconds INTEGER DEFAULT -1,
                        created_at INTEGER DEFAULT (strftime('%s', 'now')),
                        expires_at INTEGER DEFAULT -1,
                        linked_report_id INTEGER DEFAULT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS server_chat_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_name TEXT NOT NULL,
                        player_uuid TEXT,
                        message TEXT NOT NULL,
                        timestamp INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_server_chat_logs_player ON server_chat_logs(player_name)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_server_chat_logs_time ON server_chat_logs(timestamp)");

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS server_player_stats (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        first_joined_at INTEGER,
                        last_seen_at INTEGER,
                        total_playtime_seconds INTEGER DEFAULT 0,
                        last_ip TEXT
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_server_player_stats_name ON server_player_stats(name)");

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS server_player_ip_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        ip TEXT NOT NULL,
                        last_used INTEGER NOT NULL,
                        UNIQUE(uuid, ip)
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_player_ip_history_ip ON server_player_ip_history(ip)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_player_ip_history_uuid ON server_player_ip_history(uuid)");
                statement.execute("""
                    INSERT OR IGNORE INTO server_player_ip_history (uuid, player_name, ip, last_used)
                    SELECT uuid, name, last_ip, last_seen_at FROM server_player_stats WHERE last_ip IS NOT NULL AND last_ip != ''
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS server_analytics_daily (
                        date_key TEXT PRIMARY KEY,
                        unique_players INTEGER DEFAULT 0,
                        new_players INTEGER DEFAULT 0,
                        peak_online INTEGER DEFAULT 0,
                        total_playtime_minutes INTEGER DEFAULT 0,
                        sessions_count INTEGER DEFAULT 0
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS staff_command_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        admin_username TEXT NOT NULL,
                        command TEXT NOT NULL,
                        is_suspicious INTEGER DEFAULT 0,
                        risk_level TEXT DEFAULT 'INFO',
                        timestamp INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_staff_cmd_user ON staff_command_logs(admin_username)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_staff_cmd_suspicious ON staff_command_logs(is_suspicious)");

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS economy_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp INTEGER DEFAULT (strftime('%s', 'now')),
                        total_coins INTEGER DEFAULT 0,
                        tracked_players INTEGER DEFAULT 0,
                        top_balances_json TEXT DEFAULT '[]'
                    )
                    """);

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS economy_anomalies (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_name TEXT NOT NULL,
                        old_balance INTEGER DEFAULT 0,
                        new_balance INTEGER DEFAULT 0,
                        delta INTEGER DEFAULT 0,
                        timestamp INTEGER DEFAULT (strftime('%s', 'now')),
                        reviewed INTEGER DEFAULT 0
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_eco_anomaly_time ON economy_anomalies(timestamp)");

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS server_lockdown (
                        id INTEGER PRIMARY KEY,
                        is_active INTEGER DEFAULT 0,
                        activated_at INTEGER DEFAULT 0,
                        activated_by TEXT,
                        reason TEXT,
                        settings_json TEXT DEFAULT '{}'
                    )
                    """);

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_staff_notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_name TEXT NOT NULL,
                        author TEXT NOT NULL,
                        note TEXT NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_player_staff_notes_player ON player_staff_notes(player_name)");

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_reports (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        reporter_uuid TEXT NOT NULL,
                        reporter_name TEXT NOT NULL,
                        reporter_ip TEXT,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT NOT NULL,
                        reasons_json TEXT NOT NULL,
                        description TEXT,
                        is_recent INTEGER DEFAULT 1,
                        created_at INTEGER DEFAULT (strftime('%s', 'now')),
                        status TEXT DEFAULT 'PENDING',
                        resolved_by TEXT,
                        resolved_at INTEGER DEFAULT 0,
                        reputation_deducted INTEGER DEFAULT 0,
                        linked_ban_id INTEGER DEFAULT NULL
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_player_reports_target ON player_reports(target_uuid)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_player_reports_reporter ON player_reports(reporter_uuid)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_player_reports_reporter_ip ON player_reports(reporter_ip)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_player_reports_status ON player_reports(status)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_player_reports_created ON player_reports(created_at)");

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_report_notifications (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        message TEXT NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now')),
                        delivered INTEGER DEFAULT 0,
                        delivered_at INTEGER DEFAULT 0
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_report_notif_uuid ON player_report_notifications(player_uuid, delivered)");

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS api_keys (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        key_hash TEXT NOT NULL UNIQUE,
                        prefix TEXT NOT NULL,
                        permissions TEXT NOT NULL,
                        creator TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_used_at INTEGER DEFAULT NULL,
                        is_active INTEGER DEFAULT 1
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_api_keys_hash ON api_keys(key_hash)");

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS webhooks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        url TEXT NOT NULL,
                        events TEXT NOT NULL,
                        is_active INTEGER DEFAULT 1,
                        secret TEXT DEFAULT NULL,
                        created_at INTEGER NOT NULL,
                        last_trigger_at INTEGER DEFAULT NULL
                    )
                    """);

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS ban_appeals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        ban_id INTEGER NOT NULL,
                        player_uuid TEXT,
                        player_name TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        status TEXT DEFAULT 'PENDING',
                        discord_channel_id TEXT DEFAULT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now')),
                        updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_ban_appeals_ban ON ban_appeals(ban_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_ban_appeals_player ON ban_appeals(player_name)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_ban_appeals_status ON ban_appeals(status)");

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS appeal_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        appeal_id INTEGER NOT NULL,
                        author_name TEXT NOT NULL,
                        is_staff INTEGER DEFAULT 0,
                        message TEXT NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now')),
                        FOREIGN KEY (appeal_id) REFERENCES ban_appeals(id)
                    )
                    """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_appeal_messages_appeal ON appeal_messages(appeal_id)");
            }

            migrateWebAdmins();
            migrateWebSessions();
            migrateWebBans();
            migratePlayerReports();
            initializePresetRoles();
            bootstrapDefaultAdminIfEmpty();

        } catch (SQLException e) {
            plugin.getLogger().severe("Не удалось инициализировать базу данных: " + e.getMessage());
        }
    }

    private void migrateWebAdmins() {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(web_admins)")) {
            Set<String> columns = new HashSet<>();
            while (rs.next()) {
                columns.add(rs.getString("name").toLowerCase());
            }

            if (!columns.contains("totp_secret")) {
                statement.execute("ALTER TABLE web_admins ADD COLUMN totp_secret TEXT");
            }
            if (!columns.contains("totp_enabled")) {
                statement.execute("ALTER TABLE web_admins ADD COLUMN totp_enabled INTEGER DEFAULT 0");
            }
            if (!columns.contains("last_2fa_at")) {
                statement.execute("ALTER TABLE web_admins ADD COLUMN last_2fa_at INTEGER DEFAULT 0");
            }
            if (!columns.contains("last_2fa_ip")) {
                statement.execute("ALTER TABLE web_admins ADD COLUMN last_2fa_ip TEXT");
            }
            if (!columns.contains("status")) {
                statement.execute("ALTER TABLE web_admins ADD COLUMN status TEXT DEFAULT 'ACTIVE'");
            }
            if (!columns.contains("ui_preferences")) {
                statement.execute("ALTER TABLE web_admins ADD COLUMN ui_preferences TEXT DEFAULT '{}'");
            }
            if (!columns.contains("backup_codes")) {
                statement.execute("ALTER TABLE web_admins ADD COLUMN backup_codes TEXT DEFAULT '[]'");
            }
            if (!columns.contains("role_expires_at")) {
                statement.execute("ALTER TABLE web_admins ADD COLUMN role_expires_at INTEGER DEFAULT 0");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки/миграции колонок web_admins: " + e.getMessage());
        }
    }

    private void migrateWebSessions() {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(web_sessions)")) {
            Set<String> columns = new HashSet<>();
            while (rs.next()) {
                columns.add(rs.getString("name").toLowerCase());
            }

            if (!columns.contains("ip")) {
                statement.execute("ALTER TABLE web_sessions ADD COLUMN ip TEXT");
            }
            if (!columns.contains("user_agent")) {
                statement.execute("ALTER TABLE web_sessions ADD COLUMN user_agent TEXT");
            }
            if (!columns.contains("created_at")) {
                statement.execute("ALTER TABLE web_sessions ADD COLUMN created_at INTEGER DEFAULT 0");
            }
            if (!columns.contains("last_used_at")) {
                statement.execute("ALTER TABLE web_sessions ADD COLUMN last_used_at INTEGER DEFAULT 0");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки/миграции колонок web_sessions: " + e.getMessage());
        }
    }

    private void migrateWebBans() {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(web_bans)")) {
            Set<String> columns = new HashSet<>();
            while (rs.next()) {
                columns.add(rs.getString("name").toLowerCase());
            }

            if (!columns.contains("linked_report_id")) {
                statement.execute("ALTER TABLE web_bans ADD COLUMN linked_report_id INTEGER DEFAULT NULL");
                plugin.getLogger().info("[WebAdmin] Добавлена колонка linked_report_id в таблицу web_bans");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки/миграции колонок web_bans: " + e.getMessage());
        }
    }

    private void migratePlayerReports() {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(player_reports)")) {
            Set<String> columns = new HashSet<>();
            while (rs.next()) {
                columns.add(rs.getString("name").toLowerCase());
            }

            if (!columns.contains("linked_ban_id")) {
                statement.execute("ALTER TABLE player_reports ADD COLUMN linked_ban_id INTEGER DEFAULT NULL");
                plugin.getLogger().info("[WebAdmin] Добавлена колонка linked_ban_id в таблицу player_reports");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки/миграции колонок player_reports: " + e.getMessage());
        }
    }

    private void initializePresetRoles() {
        // Управляющий
        if (getRoleByName("Управляющий").isEmpty()) {
            String ownerLpGroup = plugin.getConfig().getString("luckperms.owner-lp-group", "owner");
            saveRole(new WebRole(0, "Управляющий", ownerLpGroup, EnumSet.allOf(Permission.class), true));
        } else {
            getRoleByName("Управляющий").ifPresent(ownerRole -> {
                Set<Permission> all = EnumSet.allOf(Permission.class);
                if (!ownerRole.permissions().containsAll(all)) {
                    saveRole(new WebRole(ownerRole.id(), ownerRole.name(), ownerRole.lpGroup(), all, true));
                }
            });
        }

        // Администратор
        if (getRoleByName("Администратор").isEmpty()) {
            Set<Permission> adminPerms = Set.of(
                Permission.VIEW_STATS,
                Permission.VIEW_ANALYTICS,
                Permission.VIEW_SERVER_LOGS,
                Permission.VIEW_WEB_LOGS,
                Permission.EXECUTE_COMMANDS,
                Permission.VIEW_BANS,
                Permission.MANAGE_BANS,
                Permission.VIEW_PLAYERS,
                Permission.MANAGE_PLAYERS,
                Permission.MANAGE_LOVEAUTH,
                Permission.VIEW_VESUVIO,
                Permission.VIEW_VESUVIO_ADVANCED,
                Permission.MANAGE_VESUVIO,
                Permission.MANAGE_PASSWORDS,
                Permission.MANAGE_LOCKDOWN,
                Permission.VIEW_STAFF_AUDIT,
                Permission.VIEW_ECONOMY,
                Permission.MANAGE_ECONOMY,
                Permission.VIEW_REPORTS,
                Permission.MANAGE_REPORTS,
                Permission.VIEW_APPEALS,
                Permission.MANAGE_APPEALS,
                Permission.MANAGE_ADMINS,
                Permission.MANAGE_ROLES
            );
            saveRole(new WebRole(0, "Администратор", "admin", adminPerms, false));
        } else {
            getRoleByName("Администратор").ifPresent(adminRole -> {
                Set<Permission> needed = Set.of(
                    Permission.MANAGE_PLAYERS,
                    Permission.MANAGE_LOCKDOWN,
                    Permission.VIEW_STAFF_AUDIT,
                    Permission.VIEW_ECONOMY,
                    Permission.MANAGE_ECONOMY,
                    Permission.VIEW_REPORTS,
                    Permission.MANAGE_REPORTS,
                    Permission.VIEW_APPEALS,
                    Permission.MANAGE_APPEALS,
                    Permission.MANAGE_ADMINS,
                    Permission.MANAGE_ROLES
                );
                if (!adminRole.permissions().containsAll(needed)) {
                    Set<Permission> updated = new HashSet<>(adminRole.permissions());
                    updated.addAll(needed);
                    saveRole(new WebRole(adminRole.id(), adminRole.name(), adminRole.lpGroup(), updated, false));
                }
            });
        }

        // Модератор
        if (getRoleByName("Модератор").isEmpty()) {
            Set<Permission> modPerms = Set.of(
                Permission.VIEW_STATS,
                Permission.VIEW_SERVER_LOGS,
                Permission.VIEW_BANS,
                Permission.MANAGE_BANS,
                Permission.VIEW_APPEALS,
                Permission.MANAGE_APPEALS,
                Permission.VIEW_PLAYERS,
                Permission.VIEW_VESUVIO,
                Permission.VIEW_ECONOMY,
                Permission.VIEW_REPORTS,
                Permission.MANAGE_REPORTS
            );
            saveRole(new WebRole(0, "Модератор", "mod", modPerms, false));
        } else {
            getRoleByName("Модератор").ifPresent(modRole -> {
                Set<Permission> needed = Set.of(
                    Permission.VIEW_ECONOMY,
                    Permission.VIEW_REPORTS,
                    Permission.MANAGE_REPORTS,
                    Permission.VIEW_APPEALS,
                    Permission.MANAGE_APPEALS
                );
                if (!modRole.permissions().containsAll(needed)) {
                    Set<Permission> updated = new HashSet<>(modRole.permissions());
                    updated.addAll(needed);
                    saveRole(new WebRole(modRole.id(), modRole.name(), modRole.lpGroup(), updated, false));
                }
            });
        }
    }

    private void bootstrapDefaultAdminIfEmpty() {
        String countSql = "SELECT COUNT(*) FROM web_admins";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(countSql)) {
            if (rs.next() && rs.getInt(1) == 0) {
                Optional<WebRole> ownerRole = getRoleByName("Управляющий");
                if (ownerRole.isPresent()) {
                    long now = System.currentTimeMillis() / 1000L;
                    WebAdmin defaultAdmin = new WebAdmin(
                        0,
                        "admin",
                        PasswordUtils.hash("admin"),
                        ownerRole.get().id(),
                        now,
                        0,
                        null,
                        false,
                        0,
                        null,
                        "NEED_ONBOARDING",
                        "{}"
                    );
                    saveAdmin(defaultAdmin);
                    plugin.getLogger().info("[WebAdmin] Создан первоначальный мастер-аккаунт admin / admin со статусом NEED_ONBOARDING.");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки администраторов: " + e.getMessage());
        }
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Ошибка при закрытии базы данных: " + e.getMessage());
            }
        }
    }

    // ---------- Роли ----------

    public synchronized void saveRole(WebRole role) {
        String permissionsJson = JsonUtils.toJson(role.permissions().stream().map(Enum::name).toList());
        try {
            if (role.id() == 0) {
                String sql = "INSERT INTO web_roles (name, lp_group, permissions, is_owner) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, role.name());
                    ps.setString(2, role.lpGroup());
                    ps.setString(3, permissionsJson);
                    ps.setInt(4, role.isOwner() ? 1 : 0);
                    ps.executeUpdate();
                }
            } else {
                String sql = "UPDATE web_roles SET name = ?, lp_group = ?, permissions = ?, is_owner = ? WHERE id = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, role.name());
                    ps.setString(2, role.lpGroup());
                    ps.setString(3, permissionsJson);
                    ps.setInt(4, role.isOwner() ? 1 : 0);
                    ps.setInt(5, role.id());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения роли: " + e.getMessage());
        }
    }

    public synchronized Optional<WebRole> getRoleById(int id) {
        String sql = "SELECT * FROM web_roles WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRole(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения роли: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized Optional<WebRole> getRoleByName(String name) {
        String sql = "SELECT * FROM web_roles WHERE name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRole(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения роли: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized List<WebRole> getAllRoles() {
        List<WebRole> roles = new ArrayList<>();
        String sql = "SELECT * FROM web_roles ORDER BY id";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                roles.add(mapRole(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения списка ролей: " + e.getMessage());
        }
        return roles;
    }

    public synchronized void deleteRole(int id) {
        String sql = "DELETE FROM web_roles WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка удаления роли: " + e.getMessage());
        }
    }

    private WebRole mapRole(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String name = rs.getString("name");
        String lpGroup = rs.getString("lp_group");
        String permissionsJson = rs.getString("permissions");
        boolean isOwner = rs.getInt("is_owner") != 0;

        Set<Permission> permissions = new LinkedHashSet<>();
        Object parsed = JsonUtils.parse(permissionsJson);
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                try {
                    permissions.add(Permission.valueOf(String.valueOf(item)));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return new WebRole(id, name, lpGroup, permissions, isOwner);
    }

    // ---------- Управляющий и статус ----------

    public synchronized boolean hasActiveOwner() {
        String sql = """
            SELECT COUNT(*) FROM web_admins a
            JOIN web_roles r ON a.role_id = r.id
            WHERE r.is_owner = 1 AND a.status = 'ACTIVE'
            """;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки владельца: " + e.getMessage());
        }
        return false;
    }

    public synchronized boolean isInitialSetupNeeded() {
        String sql = "SELECT COUNT(*) FROM web_admins WHERE status = 'NEED_ONBOARDING'";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next() && rs.getInt(1) > 0) return true;
        } catch (SQLException ignored) {}
        return !hasActiveOwner();
    }

    // ---------- Админы ----------

    public synchronized void saveAdmin(WebAdmin admin) {
        try {
            if (admin.id() == 0) {
                String sql = """
                    INSERT INTO web_admins (
                        username, password_hash, role_id, created_at, last_login_at,
                        totp_secret, totp_enabled, last_2fa_at, last_2fa_ip, status, ui_preferences, backup_codes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, admin.username());
                    ps.setString(2, admin.passwordHash());
                    ps.setInt(3, admin.roleId());
                    ps.setLong(4, admin.createdAt());
                    ps.setLong(5, admin.lastLoginAt());
                    ps.setString(6, admin.totpSecret());
                    ps.setInt(7, admin.totpEnabled() ? 1 : 0);
                    ps.setLong(8, admin.last2faAt());
                    ps.setString(9, admin.last2faIp());
                    ps.setString(10, admin.status());
                    ps.setString(11, admin.uiPreferences());
                    ps.setString(12, admin.backupCodes() != null ? admin.backupCodes() : "[]");
                    ps.executeUpdate();
                }
            } else {
                String sql = """
                    UPDATE web_admins SET
                        username = ?, password_hash = ?, role_id = ?, last_login_at = ?,
                        totp_secret = ?, totp_enabled = ?, last_2fa_at = ?, last_2fa_ip = ?,
                        status = ?, ui_preferences = ?, backup_codes = ?
                    WHERE id = ?
                    """;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, admin.username());
                    ps.setString(2, admin.passwordHash());
                    ps.setInt(3, admin.roleId());
                    ps.setLong(4, admin.lastLoginAt());
                    ps.setString(5, admin.totpSecret());
                    ps.setInt(6, admin.totpEnabled() ? 1 : 0);
                    ps.setLong(7, admin.last2faAt());
                    ps.setString(8, admin.last2faIp());
                    ps.setString(9, admin.status());
                    ps.setString(10, admin.uiPreferences());
                    ps.setString(11, admin.backupCodes() != null ? admin.backupCodes() : "[]");
                    ps.setInt(12, admin.id());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения администратора: " + e.getMessage());
        }
    }

    public synchronized Optional<WebAdmin> getAdminById(int id) {
        String sql = "SELECT * FROM web_admins WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapAdmin(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения администратора: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized Optional<WebAdmin> getAdminByUsername(String username) {
        String sql = "SELECT * FROM web_admins WHERE LOWER(username) = LOWER(?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapAdmin(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения администратора: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized List<WebAdmin> getAllAdmins() {
        List<WebAdmin> admins = new ArrayList<>();
        String sql = "SELECT * FROM web_admins WHERE status = 'ACTIVE' ORDER BY id";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                admins.add(mapAdmin(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения списка администраторов: " + e.getMessage());
        }
        return admins;
    }

    public synchronized List<WebAdmin> getPendingAdmins() {
        List<WebAdmin> admins = new ArrayList<>();
        String sql = "SELECT * FROM web_admins WHERE status = 'PENDING_APPROVAL' ORDER BY id DESC";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                admins.add(mapAdmin(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения заявок администраторов: " + e.getMessage());
        }
        return admins;
    }

    public synchronized boolean approveAdmin(int id, int roleId) {
        String sql = "UPDATE web_admins SET status = 'ACTIVE', role_id = ? WHERE id = ? AND status = 'PENDING_APPROVAL'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roleId);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка утверждения администратора: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean rejectAdmin(int id) {
        String sql = "DELETE FROM web_admins WHERE id = ? AND status = 'PENDING_APPROVAL'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка отклонения администратора: " + e.getMessage());
            return false;
        }
    }

    public synchronized void deleteAdmin(int id) {
        String sql = "DELETE FROM web_admins WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка удаления администратора: " + e.getMessage());
        }
    }

    public synchronized void setAdminPassword(int id, String passwordHash) {
        String sql = "UPDATE web_admins SET password_hash = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка установки пароля: " + e.getMessage());
        }
    }

    public synchronized void updateAdminLastLogin(int id, long timestamp) {
        String sql = "UPDATE web_admins SET last_login_at = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, timestamp);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления времени входа: " + e.getMessage());
        }
    }

    public synchronized void updateAdmin2faSuccess(int id, long timestamp, String ip) {
        String sql = "UPDATE web_admins SET last_2fa_at = ?, last_2fa_ip = ?, last_login_at = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, timestamp);
            ps.setString(2, ip);
            ps.setLong(3, timestamp);
            ps.setInt(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления 2FA данных: " + e.getMessage());
        }
    }

    public synchronized void updateAdminPreferences(int id, String uiPreferencesJson) {
        String sql = "UPDATE web_admins SET ui_preferences = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uiPreferencesJson);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления настроек интерфейса: " + e.getMessage());
        }
    }

    public synchronized void updateAdminRole(int id, int roleId) {
        String sql = "UPDATE web_admins SET role_id = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, roleId);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления роли администратора: " + e.getMessage());
        }
    }

    public synchronized void updateAdminBackupCodes(int id, String backupCodesJson) {
        String sql = "UPDATE web_admins SET backup_codes = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, backupCodesJson != null ? backupCodesJson : "[]");
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления резервных кодов: " + e.getMessage());
        }
    }

    private WebAdmin mapAdmin(ResultSet rs) throws SQLException {
        String backupCodes = "[]";
        try {
            backupCodes = rs.getString("backup_codes");
            if (backupCodes == null) backupCodes = "[]";
        } catch (SQLException ignored) {}

        return new WebAdmin(
            rs.getInt("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getInt("role_id"),
            rs.getLong("created_at"),
            rs.getLong("last_login_at"),
            rs.getString("totp_secret"),
            rs.getInt("totp_enabled") != 0,
            rs.getLong("last_2fa_at"),
            rs.getString("last_2fa_ip"),
            rs.getString("status"),
            rs.getString("ui_preferences"),
            backupCodes
        );
    }

    // ---------- Сессии ----------

    public synchronized void saveSession(WebSession session) {
        String sql = "INSERT OR REPLACE INTO web_sessions (token, admin_id, expires_at, ip, user_agent, created_at, last_used_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, session.token());
            ps.setInt(2, session.adminId());
            ps.setLong(3, session.expiresAt());
            ps.setString(4, session.ip());
            ps.setString(5, session.userAgent());
            ps.setLong(6, session.createdAt());
            ps.setLong(7, session.lastUsedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения сессии: " + e.getMessage());
        }
    }

    public synchronized Optional<WebSession> getSession(String token) {
        String sql = """
            SELECT s.token, s.admin_id, s.expires_at, s.ip, s.user_agent, s.created_at, s.last_used_at, a.username, a.role_id
            FROM web_sessions s
            JOIN web_admins a ON s.admin_id = a.id
            WHERE s.token = ?
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new WebSession(
                        rs.getString("token"),
                        rs.getInt("admin_id"),
                        rs.getString("username"),
                        rs.getInt("role_id"),
                        rs.getLong("expires_at"),
                        rs.getString("ip"),
                        rs.getString("user_agent"),
                        rs.getLong("created_at"),
                        rs.getLong("last_used_at")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения сессии: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized void deleteSession(String token) {
        String sql = "DELETE FROM web_sessions WHERE token = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка удаления сессии: " + e.getMessage());
        }
    }

    public synchronized void deleteExpiredSessions() {
        String sql = "DELETE FROM web_sessions WHERE expires_at < ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis() / 1000);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка очистки истёкших сессий: " + e.getMessage());
        }
    }

    public synchronized List<WebSession> getAllActiveSessions() {
        List<WebSession> sessions = new ArrayList<>();
        String sql = """
            SELECT s.token, s.admin_id, s.expires_at, s.ip, s.user_agent, s.created_at, s.last_used_at, a.username, a.role_id
            FROM web_sessions s
            JOIN web_admins a ON s.admin_id = a.id
            WHERE s.expires_at >= ?
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis() / 1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sessions.add(new WebSession(
                        rs.getString("token"),
                        rs.getInt("admin_id"),
                        rs.getString("username"),
                        rs.getInt("role_id"),
                        rs.getLong("expires_at"),
                        rs.getString("ip"),
                        rs.getString("user_agent"),
                        rs.getLong("created_at"),
                        rs.getLong("last_used_at")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка загрузки активных сессий: " + e.getMessage());
        }
        return sessions;
    }

    public synchronized List<WebSession> getSessionsForAdmin(int adminId) {
        List<WebSession> sessions = new ArrayList<>();
        String sql = """
            SELECT s.token, s.admin_id, s.expires_at, s.ip, s.user_agent, s.created_at, s.last_used_at, a.username, a.role_id
            FROM web_sessions s
            JOIN web_admins a ON s.admin_id = a.id
            WHERE s.admin_id = ? AND s.expires_at >= ?
            ORDER BY s.last_used_at DESC
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, adminId);
            ps.setLong(2, System.currentTimeMillis() / 1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sessions.add(new WebSession(
                        rs.getString("token"),
                        rs.getInt("admin_id"),
                        rs.getString("username"),
                        rs.getInt("role_id"),
                        rs.getLong("expires_at"),
                        rs.getString("ip"),
                        rs.getString("user_agent"),
                        rs.getLong("created_at"),
                        rs.getLong("last_used_at")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения сессий администратора: " + e.getMessage());
        }
        return sessions;
    }

    public synchronized void deleteSessionsForAdmin(int adminId) {
        String sql = "DELETE FROM web_sessions WHERE admin_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, adminId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка удаления сессий администратора: " + e.getMessage());
        }
    }

    public synchronized void deleteOtherSessions(int adminId, String currentToken) {
        String sql = "DELETE FROM web_sessions WHERE admin_id = ? AND token != ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, adminId);
            ps.setString(2, currentToken);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка удаления других сессий: " + e.getMessage());
        }
    }

    public synchronized void updateSessionLastUsed(String token, long now) {
        String sql = "UPDATE web_sessions SET last_used_at = ? WHERE token = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setString(2, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления активности сессии: " + e.getMessage());
        }
    }

    // ---------- Баны ----------

    public synchronized WebBan saveBan(WebBan ban) {
        try {
            if (ban.id() == 0) {
                String sql = """
                    INSERT INTO web_bans (
                        target_name, target_uuid, target_ip, creator_name, rule_reason,
                        description, proof_urls, is_ip_ban, status, duration_seconds, created_at, expires_at, linked_report_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, ban.targetName());
                    ps.setString(2, ban.targetUuid());
                    ps.setString(3, ban.targetIp());
                    ps.setString(4, ban.creatorName());
                    ps.setString(5, ban.ruleReason());
                    ps.setString(6, ban.description());
                    ps.setString(7, ban.proofUrls());
                    ps.setInt(8, ban.isIpBan() ? 1 : 0);
                    ps.setString(9, ban.status());
                    ps.setLong(10, ban.durationSeconds());
                    ps.setLong(11, ban.createdAt());
                    ps.setLong(12, ban.expiresAt());
                    if (ban.linkedReportId() != null && ban.linkedReportId() > 0) {
                        ps.setInt(13, ban.linkedReportId());
                    } else {
                        ps.setNull(13, Types.INTEGER);
                    }
                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            int newId = keys.getInt(1);
                            return getBanById(newId).orElse(ban);
                        }
                    }
                }
            } else {
                String sql = """
                    UPDATE web_bans SET
                        target_name = ?, target_uuid = ?, target_ip = ?, creator_name = ?,
                        rule_reason = ?, description = ?, proof_urls = ?, is_ip_ban = ?,
                        status = ?, duration_seconds = ?, expires_at = ?, linked_report_id = ?
                    WHERE id = ?
                    """;
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, ban.targetName());
                    ps.setString(2, ban.targetUuid());
                    ps.setString(3, ban.targetIp());
                    ps.setString(4, ban.creatorName());
                    ps.setString(5, ban.ruleReason());
                    ps.setString(6, ban.description());
                    ps.setString(7, ban.proofUrls());
                    ps.setInt(8, ban.isIpBan() ? 1 : 0);
                    ps.setString(9, ban.status());
                    ps.setLong(10, ban.durationSeconds());
                    ps.setLong(11, ban.expiresAt());
                    if (ban.linkedReportId() != null && ban.linkedReportId() > 0) {
                        ps.setInt(12, ban.linkedReportId());
                    } else {
                        ps.setNull(12, Types.INTEGER);
                    }
                    ps.setInt(13, ban.id());
                    ps.executeUpdate();
                }
                return ban;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения бана: " + e.getMessage());
        }
        return ban;
    }

    public synchronized Optional<WebBan> getBanById(int id) {
        String sql = "SELECT * FROM web_bans WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapBan(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения бана: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized List<WebBan> getAllBans(String statusFilter) {
        List<WebBan> list = new ArrayList<>();
        String sql = (statusFilter != null && !statusFilter.isBlank())
            ? "SELECT * FROM web_bans WHERE status = ? ORDER BY id DESC"
            : "SELECT * FROM web_bans ORDER BY id DESC";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (statusFilter != null && !statusFilter.isBlank()) {
                ps.setString(1, statusFilter);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapBan(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения списка банов: " + e.getMessage());
        }
        return list;
    }

    public synchronized List<WebBan> getBansByCreator(String creatorName) {
        List<WebBan> list = new ArrayList<>();
        String sql = "SELECT * FROM web_bans WHERE LOWER(creator_name) = LOWER(?) ORDER BY id DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, creatorName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapBan(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения банов создателя: " + e.getMessage());
        }
        return list;
    }

    public synchronized List<WebBan> getActiveBansForPlayer(String name, String uuid, String ip) {
        List<WebBan> list = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        String sql = """
            SELECT * FROM web_bans
            WHERE status = 'ACTIVE'
              AND (expires_at = -1 OR expires_at > ?)
              AND (LOWER(target_name) = LOWER(?) OR (target_uuid IS NOT NULL AND target_uuid = ?) OR (is_ip_ban = 1 AND target_ip IS NOT NULL AND target_ip = ?))
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setString(2, name != null ? name : "");
            ps.setString(3, uuid != null ? uuid : "");
            ps.setString(4, ip != null ? ip : "");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapBan(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка поиска активных банов игрока: " + e.getMessage());
        }
        return list;
    }

    public synchronized Optional<WebBan> getActiveBan(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        List<WebBan> list = getActiveBansForPlayer(playerName.trim(), null, null);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public synchronized boolean isPlayerBanned(String playerName) {
        return getActiveBan(playerName).isPresent();
    }

    public synchronized boolean unban(int banId) {
        String sql = "UPDATE web_bans SET status = 'UNBANNED' WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, banId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка разбана: " + e.getMessage());
            return false;
        }
    }

    public synchronized void deleteBan(int id) {
        String sql = "DELETE FROM web_bans WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка удаления бана: " + e.getMessage());
        }
    }

    public synchronized void purgeOldBans(long olderThanSeconds) {
        String sql = "DELETE FROM web_bans WHERE created_at < ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, olderThanSeconds);
            int count = ps.executeUpdate();
            if (count > 0) {
                plugin.getLogger().info("[WebAdmin] Очищено " + count + " записей банов старше 2 месяцев.");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка очистки старых банов: " + e.getMessage());
        }
    }

    private WebBan mapBan(ResultSet rs) throws SQLException {
        int rId = rs.getInt("linked_report_id");
        Integer linkedReportId = rs.wasNull() ? null : rId;
        return new WebBan(
            rs.getInt("id"),
            rs.getString("target_name"),
            rs.getString("target_uuid"),
            rs.getString("target_ip"),
            rs.getString("creator_name"),
            rs.getString("rule_reason"),
            rs.getString("description"),
            rs.getString("proof_urls"),
            rs.getInt("is_ip_ban") != 0,
            rs.getString("status"),
            rs.getLong("duration_seconds"),
            rs.getLong("created_at"),
            rs.getLong("expires_at"),
            linkedReportId
        );
    }

    // ---------- Чат-логи ----------

    public synchronized void saveChatLog(String playerName, String playerUuid, String message) {
        String sql = "INSERT INTO server_chat_logs (player_name, player_uuid, message) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setString(2, playerUuid);
            ps.setString(3, message);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения чат-лога: " + e.getMessage());
        }
    }

    public synchronized List<Map<String, Object>> getPlayerChatLogs(String playerName, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT * FROM server_chat_logs WHERE LOWER(player_name) = LOWER(?) ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", rs.getInt("id"));
                    map.put("name", rs.getString("player_name"));
                    map.put("uuid", rs.getString("player_uuid"));
                    map.put("message", rs.getString("message"));
                    map.put("timestamp", rs.getLong("timestamp"));
                    list.add(map);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка загрузки сообщений игрока: " + e.getMessage());
        }
        return list;
    }

    public synchronized List<Map<String, Object>> searchChatLogs(String query, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT * FROM server_chat_logs WHERE message LIKE ? OR player_name LIKE ? ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "%" + query + "%");
            ps.setString(2, "%" + query + "%");
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", rs.getInt("id"));
                    map.put("name", rs.getString("player_name"));
                    map.put("uuid", rs.getString("player_uuid"));
                    map.put("message", rs.getString("message"));
                    map.put("timestamp", rs.getLong("timestamp"));
                    list.add(map);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка поиска сообщений: " + e.getMessage());
        }
        return list;
    }

    public synchronized void purgeOldChatLogs(long olderThanSeconds) {
        String sql = "DELETE FROM server_chat_logs WHERE timestamp < ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, olderThanSeconds);
            int count = ps.executeUpdate();
            if (count > 0) {
                plugin.getLogger().info("[WebAdmin] Очищено " + count + " сообщений чата старше 2 месяцев.");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка очистки старых сообщений: " + e.getMessage());
        }
    }

    // ---------- Статистика игроков ----------

    public synchronized void recordPlayerLogin(String uuid, String name, String ip) {
        long now = System.currentTimeMillis() / 1000L;
        String sql = """
            INSERT INTO server_player_stats (uuid, name, first_joined_at, last_seen_at, total_playtime_seconds, last_ip)
            VALUES (?, ?, ?, ?, 0, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                name = excluded.name,
                last_seen_at = excluded.last_seen_at,
                last_ip = excluded.last_ip
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setString(5, ip);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления статистики игрока при входе: " + e.getMessage());
        }

        if (ip != null && !ip.isBlank()) {
            String histSql = """
                INSERT INTO server_player_ip_history (uuid, player_name, ip, last_used)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid, ip) DO UPDATE SET
                    player_name = excluded.player_name,
                    last_used = excluded.last_used
                """;
            try (PreparedStatement ps = connection.prepareStatement(histSql)) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, ip);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    public synchronized void recordPlayerQuit(String uuid, long sessionDurationSeconds) {
        long now = System.currentTimeMillis() / 1000L;
        String sql = """
            UPDATE server_player_stats
            SET last_seen_at = ?, total_playtime_seconds = total_playtime_seconds + ?
            WHERE uuid = ?
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, Math.max(0, sessionDurationSeconds));
            ps.setString(3, uuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления времени игры: " + e.getMessage());
        }
    }

    public synchronized Optional<Map<String, Object>> getPlayerStats(String nameOrUuid) {
        String sql = "SELECT * FROM server_player_stats WHERE uuid = ? OR LOWER(name) = LOWER(?) LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, nameOrUuid);
            ps.setString(2, nameOrUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("uuid", rs.getString("uuid"));
                    map.put("name", rs.getString("name"));
                    map.put("firstJoinedAt", rs.getLong("first_joined_at"));
                    map.put("lastSeenAt", rs.getLong("last_seen_at"));
                    map.put("playtimeSeconds", rs.getLong("total_playtime_seconds"));
                    map.put("lastIp", rs.getString("last_ip"));
                    return Optional.of(map);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения статистики игрока: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized List<Map<String, Object>> searchPlayers(String query, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT * FROM server_player_stats WHERE name LIKE ? OR uuid LIKE ? ORDER BY last_seen_at DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "%" + query + "%");
            ps.setString(2, "%" + query + "%");
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("uuid", rs.getString("uuid"));
                    map.put("name", rs.getString("name"));
                    map.put("lastSeenAt", rs.getLong("last_seen_at"));
                    map.put("playtimeSeconds", rs.getLong("total_playtime_seconds"));
                    list.add(map);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка поиска игроков: " + e.getMessage());
        }
        return list;
    }

    public synchronized Map<String, Object> getAssociatedAccounts(String query) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<Map<String, Object>> targetStatsOpt = getPlayerStats(query);
        if (targetStatsOpt.isEmpty()) {
            return result;
        }

        Map<String, Object> targetStats = targetStatsOpt.get();
        String targetUuid = (String) targetStats.get("uuid");
        result.put("target", targetStats);

        Set<String> knownIps = new LinkedHashSet<>();
        if (targetStats.get("lastIp") != null && !((String) targetStats.get("lastIp")).isBlank()) {
            knownIps.add((String) targetStats.get("lastIp"));
        }

        String ipSql = "SELECT DISTINCT ip FROM server_player_ip_history WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(ipSql)) {
            ps.setString(1, targetUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ip = rs.getString("ip");
                    if (ip != null && !ip.isBlank()) {
                        knownIps.add(ip);
                    }
                }
            }
        } catch (SQLException ignored) {}

        if (knownIps.isEmpty()) {
            result.put("sharedIps", List.of());
            result.put("allAlts", List.of());
            return result;
        }

        List<Map<String, Object>> sharedIpsList = new ArrayList<>();
        Map<String, Map<String, Object>> altMap = new LinkedHashMap<>();

        for (String ip : knownIps) {
            Map<String, Object> ipGroup = new LinkedHashMap<>();
            ipGroup.put("ip", ip);
            List<Map<String, Object>> accounts = new ArrayList<>();

            String altsSql = """
                SELECT DISTINCT s.uuid, s.name, s.last_seen_at, s.total_playtime_seconds, s.last_ip
                FROM server_player_stats s
                WHERE s.uuid != ? AND (
                    s.last_ip = ?
                    OR s.uuid IN (SELECT uuid FROM server_player_ip_history WHERE ip = ?)
                )
                ORDER BY s.last_seen_at DESC
                """;
            try (PreparedStatement ps = connection.prepareStatement(altsSql)) {
                ps.setString(1, targetUuid);
                ps.setString(2, ip);
                ps.setString(3, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String altUuid = rs.getString("uuid");
                        String altName = rs.getString("name");
                        long lastSeen = rs.getLong("last_seen_at");
                        long playtime = rs.getLong("total_playtime_seconds");
                        boolean isOnline = false;
                        try {
                            isOnline = Bukkit.getPlayer(UUID.fromString(altUuid)) != null;
                        } catch (Exception ignored) {}

                        Map<String, Object> altAcc = new LinkedHashMap<>();
                        altAcc.put("uuid", altUuid);
                        altAcc.put("name", altName);
                        altAcc.put("lastSeenAt", lastSeen);
                        altAcc.put("playtimeSeconds", playtime);
                        altAcc.put("isOnline", isOnline);
                        accounts.add(altAcc);

                        Map<String, Object> globalAlt = altMap.computeIfAbsent(altUuid, k -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("uuid", altUuid);
                            m.put("name", altName);
                            m.put("lastSeenAt", lastSeen);
                            m.put("playtimeSeconds", playtime);
                            m.put("isOnline", Bukkit.getPlayer(UUID.fromString(altUuid)) != null);
                            m.put("sharedIps", new ArrayList<String>());
                            return m;
                        });
                        @SuppressWarnings("unchecked")
                        List<String> sIps = (List<String>) globalAlt.get("sharedIps");
                        if (!sIps.contains(ip)) {
                            sIps.add(ip);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Ошибка поиска связанных аккаунтов по IP " + ip + ": " + e.getMessage());
            }

            ipGroup.put("accounts", accounts);
            sharedIpsList.add(ipGroup);
        }

        result.put("sharedIps", sharedIpsList);
        result.put("allAlts", new ArrayList<>(altMap.values()));
        return result;
    }

    // ---------- Команды и аналитика ----------

    public synchronized void recordCommandExecution(String commandName) {
        if (commandName == null || commandName.isBlank()) return;
        String clean = commandName.trim().toLowerCase();
        if (clean.startsWith("/")) clean = clean.substring(1);
        int space = clean.indexOf(' ');
        if (space > 0) clean = clean.substring(0, space);

        String sql = """
            INSERT INTO server_command_stats (command_name, execution_count)
            VALUES (?, 1)
            ON CONFLICT(command_name) DO UPDATE SET execution_count = execution_count + 1
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, clean);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public synchronized List<Map<String, Object>> getTopCommands(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT * FROM server_command_stats ORDER BY execution_count DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("command", "/" + rs.getString("command_name"));
                    map.put("count", rs.getInt("execution_count"));
                    list.add(map);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения популярных команд: " + e.getMessage());
        }
        return list;
    }

    public synchronized void recordDailyStats(String dateKey, int uniqueIncrement, int newIncrement, int currentOnline, int playtimeMinutes) {
        String sql = """
            INSERT INTO server_analytics_daily (date_key, unique_players, new_players, peak_online, total_playtime_minutes, sessions_count)
            VALUES (?, ?, ?, ?, ?, 1)
            ON CONFLICT(date_key) DO UPDATE SET
                unique_players = unique_players + excluded.unique_players,
                new_players = new_players + excluded.new_players,
                peak_online = MAX(peak_online, excluded.peak_online),
                total_playtime_minutes = total_playtime_minutes + excluded.total_playtime_minutes,
                sessions_count = sessions_count + 1
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, dateKey);
            ps.setInt(2, uniqueIncrement);
            ps.setInt(3, newIncrement);
            ps.setInt(4, currentOnline);
            ps.setInt(5, playtimeMinutes);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления дневной аналитики: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> getMonthlyAnalytics() {
        Map<String, Object> res = new LinkedHashMap<>();
        long thirtyDaysAgo = System.currentTimeMillis() / 1000L - 30L * 86400L;

        // Месячные визиты и уникальные
        String countSql = "SELECT COUNT(*), AVG(total_playtime_seconds) FROM server_player_stats WHERE last_seen_at >= ?";
        try (PreparedStatement ps = connection.prepareStatement(countSql)) {
            ps.setLong(1, thirtyDaysAgo);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    res.put("monthlyUniquePlayers", rs.getInt(1));
                    double avgSeconds = rs.getDouble(2);
                    res.put("avgPlaytimeMinutes", Math.round(avgSeconds / 60.0));
                }
            }
        } catch (SQLException ignored) {}

        // Новые игроки за 30 дней
        String newSql = "SELECT COUNT(*) FROM server_player_stats WHERE first_joined_at >= ?";
        try (PreparedStatement ps = connection.prepareStatement(newSql)) {
            ps.setLong(1, thirtyDaysAgo);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    res.put("newPlayersCount", rs.getInt(1));
                }
            }
        } catch (SQLException ignored) {}

        // Всего аккаунтов в базе
        String totalSql = "SELECT COUNT(*) FROM server_player_stats";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(totalSql)) {
            if (rs.next()) {
                res.put("totalPlayersDatabase", rs.getInt(1));
            }
        } catch (SQLException ignored) {}

        // Пиковый онлайн из дневной статистики
        String peakSql = "SELECT MAX(peak_online), SUM(sessions_count) FROM server_analytics_daily";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(peakSql)) {
            if (rs.next()) {
                res.put("peakOnline", rs.getInt(1));
                res.put("totalVisits", rs.getInt(2));
            }
        } catch (SQLException ignored) {}

        // Дневная история (последние 14-30 дней) для графиков
        List<Map<String, Object>> dailyHistory = new ArrayList<>();
        String historySql = "SELECT * FROM server_analytics_daily ORDER BY date_key DESC LIMIT 30";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(historySql)) {
            while (rs.next()) {
                Map<String, Object> day = new LinkedHashMap<>();
                day.put("date", rs.getString("date_key"));
                day.put("unique", rs.getInt("unique_players"));
                day.put("new", rs.getInt("new_players"));
                day.put("peak", rs.getInt("peak_online"));
                day.put("playtimeMinutes", rs.getInt("total_playtime_minutes"));
                day.put("sessions", rs.getInt("sessions_count"));
                dailyHistory.add(day);
            }
        } catch (SQLException ignored) {}
        res.put("history", dailyHistory);

        return res;
    }

    // ---------- Логи (веб) ----------

    public synchronized void saveWebLog(String actor, String action) {
        String sql = "INSERT INTO web_logs (actor, action) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, actor);
            ps.setString(2, action);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка записи лога панели: " + e.getMessage());
        }
    }

    public synchronized List<LogEntry> getWebLogs(int limit, int offset) {
        List<LogEntry> logs = new ArrayList<>();
        String sql = "SELECT * FROM web_logs ORDER BY id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    logs.add(new LogEntry(
                        rs.getInt("id"),
                        "WEB",
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getLong("timestamp")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения логов панели: " + e.getMessage());
        }
        return logs;
    }

    // ---------- Логи (сервер) ----------

    public synchronized void saveServerLog(String message) {
        String sql = "INSERT INTO server_logs (message) VALUES (?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, message);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка записи серверного лога: " + e.getMessage());
        }
    }

    public synchronized List<LogEntry> getServerLogs(int limit, int offset) {
        List<LogEntry> logs = new ArrayList<>();
        String sql = "SELECT * FROM server_logs ORDER BY id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    logs.add(new LogEntry(
                        rs.getInt("id"),
                        "SERVER",
                        "CONSOLE",
                        rs.getString("message"),
                        rs.getLong("timestamp")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения серверных логов: " + e.getMessage());
        }
        return logs;
    }

    public synchronized void trimServerLogs(int keepCount) {
        String sql = """
            DELETE FROM server_logs
            WHERE id NOT IN (SELECT id FROM server_logs ORDER BY id DESC LIMIT ?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, keepCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обрезки серверных логов: " + e.getMessage());
        }
    }

    // ---------- Staff Command Audit & KPI ----------

    public synchronized void recordStaffCommand(String adminUsername, String command, boolean isSuspicious, String riskLevel) {
        String sql = "INSERT INTO staff_command_logs (admin_username, command, is_suspicious, risk_level, timestamp) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, adminUsername);
            ps.setString(2, command);
            ps.setInt(3, isSuspicious ? 1 : 0);
            ps.setString(4, riskLevel != null ? riskLevel : "INFO");
            ps.setLong(5, System.currentTimeMillis() / 1000L);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка записи команды персонала: " + e.getMessage());
        }
    }

    public synchronized List<StaffCommandLog> getStaffCommandLogs(int limit, int offset, boolean suspiciousOnly, String staffFilter) {
        List<StaffCommandLog> logs = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT id, admin_username, command, is_suspicious, risk_level, timestamp FROM staff_command_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (suspiciousOnly) {
            sql.append(" AND is_suspicious = 1");
        }
        if (staffFilter != null && !staffFilter.isBlank()) {
            sql.append(" AND LOWER(admin_username) = LOWER(?)");
            params.add(staffFilter.trim());
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof String s) ps.setString(i + 1, s);
                else if (p instanceof Integer n) ps.setInt(i + 1, n);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    logs.add(new StaffCommandLog(
                        rs.getInt("id"),
                        rs.getString("admin_username"),
                        rs.getString("command"),
                        rs.getInt("is_suspicious") == 1,
                        rs.getString("risk_level"),
                        rs.getLong("timestamp")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения логов команд персонала: " + e.getMessage());
        }
        return logs;
    }

    public synchronized List<StaffKpiRecord> getStaffKpiStats() {
        List<StaffKpiRecord> result = new ArrayList<>();
        List<WebAdmin> admins = getAllAdmins();
        for (WebAdmin admin : admins) {
            String roleName = getRoleById(admin.roleId()).map(WebRole::name).orElse("Сотрудник");
            long playtimeMinutes = 0;
            long lastSeen = 0;

            String statsSql = "SELECT total_playtime_seconds, last_seen_at FROM server_player_stats WHERE LOWER(name) = LOWER(?)";
            try (PreparedStatement ps = connection.prepareStatement(statsSql)) {
                ps.setString(1, admin.username());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        playtimeMinutes = rs.getLong("total_playtime_seconds") / 60L;
                        lastSeen = rs.getLong("last_seen_at");
                    }
                }
            } catch (SQLException ignored) {}

            int bansCount = 0;
            String bansSql = "SELECT COUNT(*) FROM web_bans WHERE LOWER(creator_name) = LOWER(?)";
            try (PreparedStatement ps = connection.prepareStatement(bansSql)) {
                ps.setString(1, admin.username());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) bansCount = rs.getInt(1);
                }
            } catch (SQLException ignored) {}

            int totalCmds = 0;
            int suspCmds = 0;
            String cmdsSql = "SELECT COUNT(*), COALESCE(SUM(is_suspicious), 0) FROM staff_command_logs WHERE LOWER(admin_username) = LOWER(?)";
            try (PreparedStatement ps = connection.prepareStatement(cmdsSql)) {
                ps.setString(1, admin.username());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalCmds = rs.getInt(1);
                        suspCmds = rs.getInt(2);
                    }
                }
            } catch (SQLException ignored) {}

            result.add(new StaffKpiRecord(
                admin.username(),
                roleName,
                playtimeMinutes,
                bansCount,
                totalCmds,
                suspCmds,
                lastSeen
            ));
        }
        return result;
    }

    // ---------- Economy Snapshots & Anomalies ----------

    public synchronized void saveEconomySnapshot(long totalCoins, int trackedPlayers, String topBalancesJson) {
        String sql = "INSERT INTO economy_snapshots (timestamp, total_coins, tracked_players, top_balances_json) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis() / 1000L);
            ps.setLong(2, totalCoins);
            ps.setInt(3, trackedPlayers);
            ps.setString(4, topBalancesJson != null ? topBalancesJson : "[]");
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения среза экономики: " + e.getMessage());
        }
    }

    public synchronized List<EconomySnapshotRecord> getRecentEconomySnapshots(int limit) {
        List<EconomySnapshotRecord> list = new ArrayList<>();
        String sql = "SELECT id, timestamp, total_coins, tracked_players, top_balances_json FROM economy_snapshots ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new EconomySnapshotRecord(
                        rs.getInt("id"),
                        rs.getLong("timestamp"),
                        rs.getLong("total_coins"),
                        rs.getInt("tracked_players"),
                        rs.getString("top_balances_json")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения срезов экономики: " + e.getMessage());
        }
        return list;
    }

    public synchronized void recordEconomyAnomaly(String playerName, long oldBal, long newBal, long delta) {
        String sql = "INSERT INTO economy_anomalies (player_name, old_balance, new_balance, delta, timestamp, reviewed) VALUES (?, ?, ?, ?, ?, 0)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setLong(2, oldBal);
            ps.setLong(3, newBal);
            ps.setLong(4, delta);
            ps.setLong(5, System.currentTimeMillis() / 1000L);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка записи аномалии экономики: " + e.getMessage());
        }
    }

    public synchronized List<EconomyAnomalyRecord> getRecentEconomyAnomalies(int limit) {
        List<EconomyAnomalyRecord> list = new ArrayList<>();
        String sql = "SELECT id, player_name, old_balance, new_balance, delta, timestamp, reviewed FROM economy_anomalies ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new EconomyAnomalyRecord(
                        rs.getInt("id"),
                        rs.getString("player_name"),
                        rs.getLong("old_balance"),
                        rs.getLong("new_balance"),
                        rs.getLong("delta"),
                        rs.getLong("timestamp"),
                        rs.getInt("reviewed") == 1
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения аномалий экономики: " + e.getMessage());
        }
        return list;
    }

    // ---------- Lockdown State Persistence ----------

    public synchronized LockdownStateRecord loadLockdownState() {
        String sql = "SELECT is_active, activated_at, activated_by, reason, settings_json FROM server_lockdown WHERE id = 1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new LockdownStateRecord(
                    rs.getInt("is_active") == 1,
                    rs.getLong("activated_at"),
                    rs.getString("activated_by"),
                    rs.getString("reason"),
                    rs.getString("settings_json")
                );
            }
        } catch (SQLException ignored) {}
        return new LockdownStateRecord(false, 0, "", "", "{}");
    }

    public synchronized void saveLockdownState(boolean active, long activatedAt, String activatedBy, String reason, String settingsJson) {
        String sql = "INSERT OR REPLACE INTO server_lockdown (id, is_active, activated_at, activated_by, reason, settings_json) VALUES (1, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, active ? 1 : 0);
            ps.setLong(2, activatedAt);
            ps.setString(3, activatedBy != null ? activatedBy : "");
            ps.setString(4, reason != null ? reason : "");
            ps.setString(5, settingsJson != null ? settingsJson : "{}");
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения состояния режима ЧС: " + e.getMessage());
        }
    }

    // ---------- Заметки персонала (Staff Notes) ----------

    public synchronized List<Map<String, Object>> getPlayerStaffNotes(String playerName) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT id, player_name, author, note, created_at FROM player_staff_notes WHERE LOWER(player_name) = LOWER(?) ORDER BY created_at DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", rs.getInt("id"));
                    map.put("playerName", rs.getString("player_name"));
                    map.put("author", rs.getString("author"));
                    map.put("note", rs.getString("note"));
                    map.put("createdAt", rs.getLong("created_at"));
                    list.add(map);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка загрузки заметок игрока: " + e.getMessage());
        }
        return list;
    }

    public synchronized void addPlayerStaffNote(String playerName, String author, String note) {
        String sql = "INSERT INTO player_staff_notes (player_name, author, note, created_at) VALUES (?, ?, ?, strftime('%s', 'now'))";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerName);
            ps.setString(2, author);
            ps.setString(3, note);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения заметки игрока: " + e.getMessage());
        }
    }

    public synchronized void deletePlayerStaffNote(int id) {
        String sql = "DELETE FROM player_staff_notes WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка удаления заметки игрока: " + e.getMessage());
        }
    }

    // ---------- Сессии администраторов (для Раздела 6: Аутентификация) ----------

    public synchronized List<Map<String, Object>> getAllActiveSessionsDetailed() {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = """
            SELECT s.token, s.admin_id, s.expires_at, s.ip, s.user_agent, s.created_at, s.last_used_at,
                   a.username, r.name as role_name
            FROM web_sessions s
            JOIN web_admins a ON s.admin_id = a.id
            JOIN web_roles r ON a.role_id = r.id
            ORDER BY s.last_used_at DESC
        """;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("token", rs.getString("token"));
                map.put("adminId", rs.getInt("admin_id"));
                map.put("username", rs.getString("username"));
                map.put("role", rs.getString("role_name"));
                map.put("ip", rs.getString("ip") != null ? rs.getString("ip") : "—");
                map.put("userAgent", rs.getString("user_agent") != null ? rs.getString("user_agent") : "Неизвестно");
                map.put("createdAt", rs.getLong("created_at"));
                map.put("lastUsedAt", rs.getLong("last_used_at"));
                map.put("expiresAt", rs.getLong("expires_at"));
                list.add(map);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения списка всех сессий: " + e.getMessage());
        }
        return list;
    }

    public synchronized void terminateSession(String token) {
        String sql = "DELETE FROM web_sessions WHERE token = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка завершения сессии: " + e.getMessage());
        }
    }

    // ---------- Аналитика базы данных (для Раздела 8: Статистика) ----------

    public synchronized List<Map<String, Object>> getTopPlaytimePlayers(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT name, uuid, total_playtime_seconds, first_joined_at, last_seen_at FROM server_player_stats ORDER BY total_playtime_seconds DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", rs.getString("name"));
                    map.put("uuid", rs.getString("uuid"));
                    map.put("playtimeSeconds", rs.getLong("total_playtime_seconds"));
                    map.put("firstJoinedAt", rs.getLong("first_joined_at"));
                    map.put("lastSeenAt", rs.getLong("last_seen_at"));
                    list.add(map);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения топа игроков: " + e.getMessage());
        }
        return list;
    }

    public synchronized long getAdminRoleExpiry(int adminId) {
        String sql = "SELECT role_expires_at FROM web_admins WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, adminId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException ignored) {}
        return 0L;
    }

    public synchronized void setAdminRoleExpiry(int adminId, long expiresAt) {
        String sql = "UPDATE web_admins SET role_expires_at = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, expiresAt);
            ps.setInt(2, adminId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления срока роли: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> getExtendedDatabaseStats() {
        return getPeriodDatabaseStats("7d", null, null);
    }

    public synchronized Map<String, Object> getPeriodDatabaseStats(String periodKey, Long customFrom, Long customTo) {
        Map<String, Object> data = new LinkedHashMap<>();
        long now = System.currentTimeMillis() / 1000L;
        long startOfToday = now - (now % 86400L);

        long curFrom, curTo, prevFrom, prevTo;
        String periodLabel, prevPeriodLabel;

        String p = periodKey != null ? periodKey.toLowerCase() : "today";
        switch (p) {
            case "yesterday" -> {
                curFrom = startOfToday - 86400L;
                curTo = startOfToday;
                prevFrom = startOfToday - 2 * 86400L;
                prevTo = startOfToday - 86400L;
                periodLabel = "Вчера";
                prevPeriodLabel = "Позавчера";
            }
            case "7d" -> {
                curFrom = now - 7L * 86400L;
                curTo = now;
                prevFrom = now - 14L * 86400L;
                prevTo = now - 7L * 86400L;
                periodLabel = "Последние 7 дней";
                prevPeriodLabel = "Предыдущие 7 дней";
            }
            case "30d" -> {
                curFrom = now - 30L * 86400L;
                curTo = now;
                prevFrom = now - 60L * 86400L;
                prevTo = now - 30L * 86400L;
                periodLabel = "Последние 30 дней";
                prevPeriodLabel = "Предыдущие 30 дней";
            }
            case "custom" -> {
                curFrom = customFrom != null ? customFrom : (now - 7L * 86400L);
                curTo = customTo != null ? customTo : now;
                long dur = Math.max(86400L, curTo - curFrom);
                prevFrom = curFrom - dur;
                prevTo = curFrom;
                periodLabel = "Выбранный период";
                prevPeriodLabel = "Предыдущий период";
            }
            default -> { // today
                curFrom = startOfToday;
                curTo = now;
                prevFrom = startOfToday - 86400L;
                prevTo = startOfToday;
                periodLabel = "Сегодня";
                prevPeriodLabel = "Вчера";
            }
        }

        data.put("period", p);
        data.put("periodLabel", periodLabel);
        data.put("prevPeriodLabel", prevPeriodLabel);
        data.put("fromTimestamp", curFrom);
        data.put("toTimestamp", curTo);

        // 1. Уникальные игроки
        int curUnique = countPlayersSeen(curFrom, curTo);
        int prevUnique = countPlayersSeen(prevFrom, prevTo);
        data.put("uniquePlayers", curUnique);
        data.put("prevUniquePlayers", prevUnique);
        data.put("uniquePlayersDelta", calcDeltaPct(curUnique, prevUnique));

        // 2. Новые игроки
        int curNew = countNewPlayers(curFrom, curTo);
        int prevNew = countNewPlayers(prevFrom, prevTo);
        data.put("newPlayers", curNew);
        data.put("prevNewPlayers", prevNew);
        data.put("newPlayersDelta", calcDeltaPct(curNew, prevNew));

        // 3. Выданные наказания
        int curPunishments = countBans(curFrom, curTo);
        int prevPunishments = countBans(prevFrom, prevTo);
        data.put("punishments", curPunishments);
        data.put("prevPunishments", prevPunishments);
        data.put("punishmentsDelta", calcDeltaPct(curPunishments, prevPunishments));

        // 4. Сообщения в чате
        int curChat = countChatMessages(curFrom, curTo);
        int prevChat = countChatMessages(prevFrom, prevTo);
        data.put("chatMessages", curChat);
        data.put("prevChatMessages", prevChat);
        data.put("chatMessagesDelta", calcDeltaPct(curChat, prevChat));

        // 5. Всего игроков в базе
        int totalPlayers = 0;
        long totalPlaytime = 0;
        double avgPlaytime = 0;
        String totalSql = "SELECT COUNT(*), SUM(total_playtime_seconds), AVG(total_playtime_seconds) FROM server_player_stats";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(totalSql)) {
            if (rs.next()) {
                totalPlayers = rs.getInt(1);
                totalPlaytime = rs.getLong(2);
                avgPlaytime = rs.getDouble(3);
            }
        } catch (SQLException ignored) {}
        data.put("totalPlayers", totalPlayers);
        data.put("totalPlaytimeHours", Math.round(totalPlaytime / 3600.0));
        data.put("avgPlaytimeMinutes", Math.round(avgPlaytime / 60.0));

        // 6. Оценка среднего и пикового онлайна
        int onlineNow = Bukkit.getOnlinePlayers().size();
        int curPeak = Math.max(onlineNow, Math.max(1, (int) Math.ceil(curUnique * 0.45)));
        int prevPeak = Math.max(1, (int) Math.ceil(prevUnique * 0.45));
        double curAvg = Math.max(onlineNow > 0 ? (double) onlineNow : 1.0, Math.round(curUnique * 0.22 * 10.0) / 10.0);
        double prevAvg = Math.max(1.0, Math.round(prevUnique * 0.22 * 10.0) / 10.0);
        data.put("peakOnline", curPeak);
        data.put("peakOnlineDelta", calcDeltaPct(curPeak, prevPeak));
        data.put("avgOnline", curAvg);
        data.put("avgOnlineDelta", calcDeltaPct(curAvg, prevAvg));

        // 7. Retention Rate (удержание)
        int retained = 0;
        String retentionSql = "SELECT COUNT(*) FROM server_player_stats WHERE total_playtime_seconds >= 900";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(retentionSql)) {
            if (rs.next()) retained = rs.getInt(1);
        } catch (SQLException ignored) {}
        double retentionRate = totalPlayers > 0 ? (retained * 100.0 / totalPlayers) : 0.0;
        data.put("retentionRate", Math.round(retentionRate * 10.0) / 10.0);
        double prevRetention = Math.max(0.0, retentionRate * 0.95);
        data.put("retentionDelta", calcDeltaPct(retentionRate, prevRetention));
        // 8. Почасовая активность (текущий vs предыдущий период)
        int[] curHourly = getHourlyDistribution(curFrom, curTo);
        int[] prevHourly = getHourlyDistribution(prevFrom, prevTo);
        List<Integer> curList = new ArrayList<>();
        List<Integer> prevList = new ArrayList<>();
        for (int h : curHourly) curList.add(h);
        for (int h : prevHourly) prevList.add(h);
        data.put("hourlyActivity", curList);
        data.put("prevHourlyActivity", prevList);

        return data;
    }

    private int countPlayersSeen(long from, long to) {
        String sql = "SELECT COUNT(*) FROM server_player_stats WHERE last_seen_at >= ? AND last_seen_at <= ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    private int countNewPlayers(long from, long to) {
        String sql = "SELECT COUNT(*) FROM server_player_stats WHERE first_joined_at >= ? AND first_joined_at <= ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    private int countBans(long from, long to) {
        String sql = "SELECT COUNT(*) FROM web_bans WHERE created_at >= ? AND created_at <= ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    private int countChatMessages(long from, long to) {
        String sql = "SELECT COUNT(*) FROM server_chat_logs WHERE timestamp >= ? AND timestamp <= ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    private int[] getHourlyDistribution(long from, long to) {
        int[] hourly = new int[24];
        String sql = "SELECT CAST(strftime('%H', timestamp, 'unixepoch', 'localtime') AS INTEGER) as hr, COUNT(*) as cnt FROM server_chat_logs WHERE timestamp >= ? AND timestamp <= ? GROUP BY hr";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int hr = rs.getInt("hr");
                    if (hr >= 0 && hr < 24) hourly[hr] = rs.getInt("cnt");
                }
            }
        } catch (SQLException ignored) {}
        return hourly;
    }

    private double calcDeltaPct(double current, double previous) {
        if (previous <= 0) {
            return current > 0 ? 100.0 : 0.0;
        }
        double diff = current - previous;
        return Math.round((diff / previous) * 1000.0) / 10.0;
    }

    // ==========================================
    // Репорты и жалобы игроков (/report, /жалоба)
    // ==========================================

    public synchronized int createPlayerReport(
        String reporterUuid,
        String reporterName,
        String reporterIp,
        String targetUuid,
        String targetName,
        List<String> reasons,
        String description,
        boolean isRecent,
        int repDeducted
    ) {
        String sql = """
            INSERT INTO player_reports (
                reporter_uuid, reporter_name, reporter_ip,
                target_uuid, target_name, reasons_json,
                description, is_recent, status, reputation_deducted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, reporterUuid);
            ps.setString(2, reporterName);
            ps.setString(3, reporterIp != null ? reporterIp : "");
            ps.setString(4, targetUuid);
            ps.setString(5, targetName);
            ps.setString(6, JsonUtils.toJson(reasons != null ? reasons : List.of()));
            ps.setString(7, description != null ? description : "");
            ps.setInt(8, isRecent ? 1 : 0);
            ps.setInt(9, repDeducted);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка создания репорта: " + e.getMessage());
        }
        return -1;
    }

    public synchronized boolean canPlayerReportTarget(String reporterUuid, String reporterIp, String targetUuid) {
        long weekAgo = (System.currentTimeMillis() / 1000L) - (7L * 86400L);
        String sql = """
            SELECT COUNT(*) FROM player_reports
            WHERE (reporter_uuid = ? OR (reporter_ip IS NOT NULL AND reporter_ip != '' AND reporter_ip = ?))
              AND target_uuid = ?
              AND created_at >= ?
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, reporterUuid != null ? reporterUuid : "");
            ps.setString(2, reporterIp != null ? reporterIp : "");
            ps.setString(3, targetUuid != null ? targetUuid : "");
            ps.setLong(4, weekAgo);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) == 0;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки кулдауна жалобы: " + e.getMessage());
        }
        return false;
    }

    public synchronized int getActiveReportsCountForTarget(String targetUuid) {
        String sql = "SELECT COUNT(*) FROM player_reports WHERE target_uuid = ? AND status = 'PENDING'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, targetUuid != null ? targetUuid : "");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка подсчета активных репортов на цель: " + e.getMessage());
        }
        return 0;
    }

    public synchronized boolean isPlayerSuspicious(String targetUuid) {
        return getActiveReportsCountForTarget(targetUuid) > 4;
    }

    public synchronized List<PlayerReport> getAllReports(String statusFilter, String search, int limit, int offset) {
        List<PlayerReport> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM player_reports WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (statusFilter != null && !statusFilter.isBlank() && !statusFilter.equalsIgnoreCase("ALL")) {
            sql.append("AND status = ? ");
            params.add(statusFilter.toUpperCase());
        }

        if (search != null && !search.isBlank()) {
            sql.append("AND (LOWER(target_name) LIKE ? OR LOWER(reporter_name) LIKE ? OR LOWER(description) LIKE ? OR LOWER(reasons_json) LIKE ?) ");
            String term = "%" + search.toLowerCase() + "%";
            params.add(term);
            params.add(term);
            params.add(term);
            params.add(term);
        }

        sql.append("ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit > 0 ? limit : 50);
        params.add(Math.max(0, offset));

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPlayerReport(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка выборки списка репортов: " + e.getMessage());
        }
        return list;
    }

    public synchronized Optional<PlayerReport> getReportById(int id) {
        String sql = "SELECT * FROM player_reports WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapPlayerReport(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка выборки репорта #" + id + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized boolean resolveReport(int id, String status, String resolvedBy, Integer linkedBanId) {
        long now = System.currentTimeMillis() / 1000L;
        String sql = "UPDATE player_reports SET status = ?, resolved_by = ?, resolved_at = ?, linked_ban_id = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, resolvedBy != null ? resolvedBy : "ADMIN");
            ps.setLong(3, now);
            if (linkedBanId != null && linkedBanId > 0) {
                ps.setInt(4, linkedBanId);
            } else {
                ps.setNull(4, Types.INTEGER);
            }
            ps.setInt(5, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления статуса репорта #" + id + ": " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean resolveReport(int id, String status, String resolvedBy) {
        return resolveReport(id, status, resolvedBy, null);
    }

    public synchronized boolean linkBanAndReport(int banId, int reportId, String adminUsername) {
        String updateBanSql = "UPDATE web_bans SET linked_report_id = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(updateBanSql)) {
            ps.setInt(1, reportId);
            ps.setInt(2, banId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка привязки репорта #" + reportId + " к бану #" + banId + ": " + e.getMessage());
            return false;
        }
        return resolveReport(reportId, "ACCEPTED", adminUsername, banId);
    }

    public synchronized List<PlayerReport> getPendingReportsForPlayer(String targetName) {
        List<PlayerReport> list = new ArrayList<>();
        if (targetName == null || targetName.isBlank()) return list;
        String sql = "SELECT * FROM player_reports WHERE status = 'PENDING' AND LOWER(target_name) = LOWER(?) ORDER BY created_at DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, targetName.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPlayerReport(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения активных жалоб на игрока " + targetName + ": " + e.getMessage());
        }
        return list;
    }

    public synchronized boolean deleteReport(int id) {
        String sql = "DELETE FROM player_reports WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка удаления репорта #" + id + ": " + e.getMessage());
            return false;
        }
    }

    public synchronized List<PlayerReport> getExpiredActiveReports(long cutoffTimestamp) {
        List<PlayerReport> list = new ArrayList<>();
        String sql = "SELECT * FROM player_reports WHERE status = 'PENDING' AND created_at <= ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, cutoffTimestamp);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapPlayerReport(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка поиска просроченных репортов: " + e.getMessage());
        }
        return list;
    }

    public synchronized void queueReportNotification(String playerUuid, String message) {
        String sql = "INSERT INTO player_report_notifications (player_uuid, message) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid);
            ps.setString(2, message);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка постановки уведомления в очередь: " + e.getMessage());
        }
    }

    public synchronized List<String> getAndClearPendingReportNotifications(String playerUuid) {
        List<String> messages = new ArrayList<>();
        String selectSql = "SELECT id, message FROM player_report_notifications WHERE player_uuid = ? AND delivered = 0";
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
            ps.setString(1, playerUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt("id"));
                    messages.add(rs.getString("message"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения недоставленных уведомлений: " + e.getMessage());
        }

        if (!ids.isEmpty()) {
            long now = System.currentTimeMillis() / 1000L;
            String updateSql = "UPDATE player_report_notifications SET delivered = 1, delivered_at = ? WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
                for (int id : ids) {
                    ps.setLong(1, now);
                    ps.setInt(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                plugin.getLogger().warning("Ошибка отметки доставки уведомлений: " + e.getMessage());
            }
        }
        return messages;
    }

    public synchronized List<Map<String, Object>> getSurroundingChatLogs(String player1, String player2, long timestamp, int windowSeconds) {
        List<Map<String, Object>> list = new ArrayList<>();
        long fromTime = timestamp - windowSeconds;
        long toTime = timestamp + 10;
        String sql = """
            SELECT id, player_name, player_uuid, message, timestamp
            FROM server_chat_logs
            WHERE timestamp >= ? AND timestamp <= ?
              AND (LOWER(player_name) = LOWER(?) OR LOWER(player_name) = LOWER(?))
            ORDER BY timestamp ASC, id ASC
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromTime);
            ps.setLong(2, toTime);
            ps.setString(3, player1 != null ? player1 : "");
            ps.setString(4, player2 != null ? player2 : "");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", rs.getInt("id"));
                    map.put("playerName", rs.getString("player_name"));
                    map.put("playerUuid", rs.getString("player_uuid"));
                    map.put("message", rs.getString("message"));
                    map.put("timestamp", rs.getLong("timestamp"));
                    list.add(map);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка загрузки контекста чата игроков: " + e.getMessage());
        }
        return list;
    }

    public synchronized Map<String, Object> getReportsStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        int total = 0;
        int pending = 0;
        int accepted = 0;
        int rejected = 0;
        int expired = 0;

        String sql = "SELECT status, COUNT(*) as cnt FROM player_reports GROUP BY status";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String st = rs.getString("status");
                int cnt = rs.getInt("cnt");
                total += cnt;
                if ("PENDING".equalsIgnoreCase(st)) pending = cnt;
                else if ("ACCEPTED".equalsIgnoreCase(st)) accepted = cnt;
                else if ("REJECTED".equalsIgnoreCase(st)) rejected = cnt;
                else if ("EXPIRED".equalsIgnoreCase(st)) expired = cnt;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка подсчета статистики репортов: " + e.getMessage());
        }

        int suspiciousCount = 0;
        String suspSql = "SELECT COUNT(*) FROM (SELECT target_uuid FROM player_reports WHERE status = 'PENDING' GROUP BY target_uuid HAVING COUNT(*) > 4)";
        try (PreparedStatement ps = connection.prepareStatement(suspSql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) suspiciousCount = rs.getInt(1);
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка подсчета подозрительных игроков: " + e.getMessage());
        }

        stats.put("total", total);
        stats.put("pending", pending);
        stats.put("accepted", accepted);
        stats.put("rejected", rejected);
        stats.put("expired", expired);
        stats.put("suspiciousCount", suspiciousCount);
        return stats;
    }

    private PlayerReport mapPlayerReport(ResultSet rs) throws SQLException {
        String reasonsRaw = rs.getString("reasons_json");
        List<String> reasons = JsonUtils.fromJsonList(reasonsRaw, String.class);
        int banIdVal = rs.getInt("linked_ban_id");
        Integer linkedBanId = rs.wasNull() ? null : banIdVal;
        return new PlayerReport(
            rs.getInt("id"),
            rs.getString("reporter_uuid"),
            rs.getString("reporter_name"),
            rs.getString("reporter_ip"),
            rs.getString("target_uuid"),
            rs.getString("target_name"),
            reasons,
            rs.getString("description"),
            rs.getInt("is_recent") == 1,
            rs.getLong("created_at"),
            rs.getString("status"),
            rs.getString("resolved_by"),
            rs.getLong("resolved_at"),
            rs.getInt("reputation_deducted"),
            linkedBanId
        );
    }

    // =========================================================================
    // API KEYS MANAGEMENT
    // =========================================================================

    public synchronized ApiKeyRecord saveApiKey(String name, String keyHash, String prefix, List<String> permissions, String creator) {
        String sql = "INSERT INTO api_keys (name, key_hash, prefix, permissions, creator, created_at, is_active) VALUES (?, ?, ?, ?, ?, ?, 1)";
        long now = System.currentTimeMillis() / 1000L;
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, name);
            stmt.setString(2, keyHash);
            stmt.setString(3, prefix);
            stmt.setString(4, JsonUtils.toJson(permissions));
            stmt.setString(5, creator);
            stmt.setLong(6, now);
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    return new ApiKeyRecord(id, name, keyHash, prefix, permissions, creator, now, null, true);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка сохранения API ключа: " + e.getMessage());
        }
        return null;
    }

    public synchronized Optional<ApiKeyRecord> getApiKeyByHash(String keyHash) {
        String sql = "SELECT * FROM api_keys WHERE key_hash = ? AND is_active = 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, keyHash);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapApiKey(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка поиска API ключа: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized List<ApiKeyRecord> getAllApiKeys() {
        List<ApiKeyRecord> list = new ArrayList<>();
        String sql = "SELECT * FROM api_keys ORDER BY created_at DESC";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapApiKey(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка получения списка API ключей: " + e.getMessage());
        }
        return list;
    }

    public synchronized boolean deleteApiKey(int id) {
        String sql = "DELETE FROM api_keys WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка удаления API ключа: " + e.getMessage());
            return false;
        }
    }

    public synchronized void updateApiKeyLastUsed(int id, long timestamp) {
        String sql = "UPDATE api_keys SET last_used_at = ? WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, timestamp);
            stmt.setInt(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка обновления last_used API ключа: " + e.getMessage());
        }
    }

    private ApiKeyRecord mapApiKey(ResultSet rs) throws SQLException {
        String permsJson = rs.getString("permissions");
        List<String> perms = JsonUtils.fromJsonList(permsJson, String.class);
        long lastUsed = rs.getLong("last_used_at");
        Long lastUsedAt = rs.wasNull() ? null : lastUsed;
        return new ApiKeyRecord(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getString("key_hash"),
            rs.getString("prefix"),
            perms,
            rs.getString("creator"),
            rs.getLong("created_at"),
            lastUsedAt,
            rs.getInt("is_active") == 1
        );
    }

    // =========================================================================
    // OUTGOING WEBHOOKS MANAGEMENT
    // =========================================================================

    public synchronized WebhookRecord saveWebhook(String name, String url, List<String> events, String secret) {
        String sql = "INSERT INTO webhooks (name, url, events, is_active, secret, created_at) VALUES (?, ?, ?, 1, ?, ?)";
        long now = System.currentTimeMillis() / 1000L;
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, name);
            stmt.setString(2, url);
            stmt.setString(3, JsonUtils.toJson(events));
            stmt.setString(4, secret);
            stmt.setLong(5, now);
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    return new WebhookRecord(id, name, url, events, true, secret, now, null);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка сохранения вебхука: " + e.getMessage());
        }
        return null;
    }

    public synchronized List<WebhookRecord> getAllWebhooks() {
        List<WebhookRecord> list = new ArrayList<>();
        String sql = "SELECT * FROM webhooks ORDER BY created_at DESC";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapWebhook(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка получения списка вебхуков: " + e.getMessage());
        }
        return list;
    }

    public synchronized List<WebhookRecord> getActiveWebhooks() {
        List<WebhookRecord> list = new ArrayList<>();
        String sql = "SELECT * FROM webhooks WHERE is_active = 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(mapWebhook(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка получения активных вебхуков: " + e.getMessage());
        }
        return list;
    }

    public synchronized boolean deleteWebhook(int id) {
        String sql = "DELETE FROM webhooks WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка удаления вебхука: " + e.getMessage());
            return false;
        }
    }

    public synchronized void updateWebhookLastTrigger(int id, long timestamp) {
        String sql = "UPDATE webhooks SET last_trigger_at = ? WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, timestamp);
            stmt.setInt(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка обновления last_trigger вебхука: " + e.getMessage());
        }
    }

    private WebhookRecord mapWebhook(ResultSet rs) throws SQLException {
        String eventsJson = rs.getString("events");
        List<String> events = JsonUtils.fromJsonList(eventsJson, String.class);
        long lastTrigger = rs.getLong("last_trigger_at");
        Long lastTriggerAt = rs.wasNull() ? null : lastTrigger;
        return new WebhookRecord(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getString("url"),
            events,
            rs.getInt("is_active") == 1,
            rs.getString("secret"),
            rs.getLong("created_at"),
            lastTriggerAt
        );
    }

    // ==========================================
    // Ban Appeals & Discord Tickets
    // ==========================================

    public synchronized int createAppeal(int banId, String playerUuid, String playerName, String reason) {
        String sql = """
            INSERT INTO ban_appeals (ban_id, player_uuid, player_name, reason, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'PENDING', strftime('%s', 'now'), strftime('%s', 'now'))
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, banId);
            stmt.setString(2, playerUuid);
            stmt.setString(3, playerName);
            stmt.setString(4, reason);
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка создания апелляции: " + e.getMessage());
        }
        return -1;
    }

    public synchronized Optional<WebBanAppeal> getAppealById(int id) {
        String sql = "SELECT * FROM ban_appeals WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapBanAppeal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка получения апелляции по ID: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized Optional<WebBanAppeal> getAppealByBanId(int banId) {
        String sql = "SELECT * FROM ban_appeals WHERE ban_id = ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, banId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapBanAppeal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка получения апелляции по banId: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized Optional<WebBanAppeal> getLatestAppealForPlayer(String playerName) {
        String sql = "SELECT * FROM ban_appeals WHERE LOWER(player_name) = LOWER(?) ORDER BY id DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, playerName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapBanAppeal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка получения апелляции по игроку: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized Optional<WebBanAppeal> getAppealByDiscordChannel(String discordChannelId) {
        String sql = "SELECT * FROM ban_appeals WHERE discord_channel_id = ? LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, discordChannelId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapBanAppeal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка получения апелляции по discordChannelId: " + e.getMessage());
        }
        return Optional.empty();
    }

    public synchronized List<WebBanAppeal> getAllAppeals(String status, int limit, int offset) {
        List<WebBanAppeal> list = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM ban_appeals ");
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sql.append("WHERE status = ? ");
        }
        sql.append("ORDER BY id DESC LIMIT ? OFFSET ?");

        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            int p = 1;
            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
                stmt.setString(p++, status.toUpperCase());
            }
            stmt.setInt(p++, limit);
            stmt.setInt(p, offset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapBanAppeal(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка списка апелляций: " + e.getMessage());
        }
        return list;
    }

    public synchronized boolean updateAppealStatus(int id, String status) {
        String sql = "UPDATE ban_appeals SET status = ?, updated_at = strftime('%s', 'now') WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setInt(2, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка обновления статуса апелляции: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean setAppealDiscordChannelId(int id, String discordChannelId) {
        String sql = "UPDATE ban_appeals SET discord_channel_id = ?, updated_at = strftime('%s', 'now') WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, discordChannelId);
            stmt.setInt(2, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка установки Discord канала апелляции: " + e.getMessage());
            return false;
        }
    }

    public synchronized int addAppealMessage(int appealId, String authorName, boolean isStaff, String message) {
        String sql = """
            INSERT INTO appeal_messages (appeal_id, author_name, is_staff, message, created_at)
            VALUES (?, ?, ?, ?, strftime('%s', 'now'))
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, appealId);
            stmt.setString(2, authorName);
            stmt.setInt(3, isStaff ? 1 : 0);
            stmt.setString(4, message);
            stmt.executeUpdate();

            try (PreparedStatement upd = connection.prepareStatement("UPDATE ban_appeals SET updated_at = strftime('%s', 'now') WHERE id = ?")) {
                upd.setInt(1, appealId);
                upd.executeUpdate();
            }

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка добавления сообщения в апелляцию: " + e.getMessage());
        }
        return -1;
    }

    public synchronized List<AppealMessage> getAppealMessages(int appealId) {
        List<AppealMessage> list = new ArrayList<>();
        String sql = "SELECT * FROM appeal_messages WHERE appeal_id = ? ORDER BY id ASC";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, appealId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapAppealMessage(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[DatabaseManager] Ошибка загрузки сообщений апелляции: " + e.getMessage());
        }
        return list;
    }

    private WebBanAppeal mapBanAppeal(ResultSet rs) throws SQLException {
        return new WebBanAppeal(
            rs.getInt("id"),
            rs.getInt("ban_id"),
            rs.getString("player_uuid"),
            rs.getString("player_name"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("discord_channel_id"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
        );
    }

    private AppealMessage mapAppealMessage(ResultSet rs) throws SQLException {
        return new AppealMessage(
            rs.getInt("id"),
            rs.getInt("appeal_id"),
            rs.getString("author_name"),
            rs.getInt("is_staff") == 1,
            rs.getString("message"),
            rs.getLong("created_at")
        );
    }
}
