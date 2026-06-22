package me.lovelace.loveWebAdmin.database;

import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.LogEntry;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebAdmin;
import me.lovelace.loveWebAdmin.models.WebRole;
import me.lovelace.loveWebAdmin.models.WebSession;
import me.lovelace.loveWebAdmin.utils.JsonUtils;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Все методы вызываются только из async контекста.
 * SQLite допускает одного писателя одновременно — синхронизируемся на единственном Connection.
 */
public class DatabaseManager {

    private final LoveWebAdmin plugin;
    private Connection connection;

    public DatabaseManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public synchronized void initialize() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "database.db");
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
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
                        FOREIGN KEY (role_id) REFERENCES web_roles(id)
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS web_sessions (
                        token TEXT PRIMARY KEY,
                        admin_id INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
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
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Не удалось инициализировать базу данных: " + e.getMessage());
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

    public synchronized void updateRolePermissions(int id, Set<Permission> permissions) {
        String permissionsJson = JsonUtils.toJson(permissions.stream().map(Enum::name).toList());
        String sql = "UPDATE web_roles SET permissions = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, permissionsJson);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления прав роли: " + e.getMessage());
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
                } catch (IllegalArgumentException ignored) {
                    // неизвестное право - игнорируем
                }
            }
        }
        return new WebRole(id, name, lpGroup, permissions, isOwner);
    }

    // ---------- Управляющий ----------

    public synchronized boolean hasOwner() {
        String sql = "SELECT COUNT(*) FROM web_admins a JOIN web_roles r ON a.role_id = r.id WHERE r.is_owner = 1";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки владельца: " + e.getMessage());
        }
        return false;
    }

    public synchronized void bootstrapOwner(String username) {
        String ownerLpGroup = plugin.getConfig().getString("luckperms.owner-lp-group", "owner");
        WebRole ownerRole = new WebRole(0, "Управляющий", ownerLpGroup, EnumSet.allOf(Permission.class), true);
        saveRole(ownerRole);
        Optional<WebRole> saved = getRoleByName("Управляющий");
        if (saved.isEmpty()) return;

        WebAdmin admin = new WebAdmin(0, username, null, saved.get().id(), System.currentTimeMillis() / 1000, 0);
        saveAdmin(admin);
    }

    // ---------- Админы ----------

    public synchronized void saveAdmin(WebAdmin admin) {
        try {
            if (admin.id() == 0) {
                String sql = "INSERT INTO web_admins (username, password_hash, role_id, created_at, last_login_at) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, admin.username());
                    ps.setString(2, admin.passwordHash());
                    ps.setInt(3, admin.roleId());
                    ps.setLong(4, admin.createdAt());
                    ps.setLong(5, admin.lastLoginAt());
                    ps.executeUpdate();
                }
            } else {
                String sql = "UPDATE web_admins SET username = ?, password_hash = ?, role_id = ?, last_login_at = ? WHERE id = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, admin.username());
                    ps.setString(2, admin.passwordHash());
                    ps.setInt(3, admin.roleId());
                    ps.setLong(4, admin.lastLoginAt());
                    ps.setInt(5, admin.id());
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
        String sql = "SELECT * FROM web_admins WHERE username = ?";
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
        String sql = "SELECT * FROM web_admins ORDER BY id";
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

    public synchronized void deleteAdminPassword(int id) {
        setAdminPassword(id, null);
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

    private WebAdmin mapAdmin(ResultSet rs) throws SQLException {
        return new WebAdmin(
            rs.getInt("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getInt("role_id"),
            rs.getLong("created_at"),
            rs.getLong("last_login_at")
        );
    }

    // ---------- Сессии ----------

    public synchronized void saveSession(WebSession session) {
        String sql = "INSERT OR REPLACE INTO web_sessions (token, admin_id, expires_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, session.token());
            ps.setInt(2, session.adminId());
            ps.setLong(3, session.expiresAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения сессии: " + e.getMessage());
        }
    }

    public synchronized Optional<WebSession> getSession(String token) {
        String sql = """
            SELECT s.token, s.admin_id, s.expires_at, a.username, a.role_id
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
                        rs.getLong("expires_at")
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
            SELECT s.token, s.admin_id, s.expires_at, a.username, a.role_id
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
                        rs.getLong("expires_at")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка загрузки активных сессий: " + e.getMessage());
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
}
