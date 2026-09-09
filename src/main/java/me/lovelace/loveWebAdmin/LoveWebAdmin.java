package me.lovelace.loveWebAdmin;

import me.lovelace.loveWebAdmin.commands.LoveWebAdminCommand;
import me.lovelace.loveWebAdmin.database.DatabaseManager;
import me.lovelace.loveWebAdmin.integration.VesuvioBridge;
import me.lovelace.loveWebAdmin.listeners.CommandLogListener;
import me.lovelace.loveWebAdmin.managers.AdminManager;
import me.lovelace.loveWebAdmin.managers.LogManager;
import me.lovelace.loveWebAdmin.managers.LoginAttemptTracker;
import me.lovelace.loveWebAdmin.managers.LuckPermsManager;
import me.lovelace.loveWebAdmin.managers.RoleManager;
import me.lovelace.loveWebAdmin.web.SessionManager;
import me.lovelace.loveWebAdmin.web.WebServer;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class LoveWebAdmin extends JavaPlugin {

    private DatabaseManager databaseManager;
    private LogManager logManager;
    private RoleManager roleManager;
    private AdminManager adminManager;
    private LoginAttemptTracker loginAttemptTracker;
    private LuckPermsManager luckPermsManager;
    private SessionManager sessionManager;
    private CommandLogListener commandLogListener;
    private WebServer webServer;
    private VesuvioBridge vesuvioBridge;
    private long startTimeMillis;

    @Override
    public void onEnable() {
        startTimeMillis = System.currentTimeMillis();
        saveDefaultConfig();

        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        this.logManager = new LogManager(this);
        this.logManager.startCapture();

        this.roleManager = new RoleManager(this);
        this.adminManager = new AdminManager(this);
        this.loginAttemptTracker = new LoginAttemptTracker(this);
        this.loginAttemptTracker.startCleanupTask();
        this.luckPermsManager = new LuckPermsManager(this);

        this.sessionManager = new SessionManager(this);
        this.sessionManager.loadFromDatabase();
        this.sessionManager.startCleanupTask();

        this.commandLogListener = new CommandLogListener(this);
        getServer().getPluginManager().registerEvents(commandLogListener, this);

        // Reflection-only bridge - see VesuvioBridge for why this isn't a compile dependency.
        this.vesuvioBridge = new VesuvioBridge();

        // /lwa остаётся рабочим алиасом (см. plugin.yml) для тех, кто набирает его по привычке.
        LoveWebAdminCommand loveWebAdminCommand = new LoveWebAdminCommand(this);
        var loveWebAdminPluginCommand = getCommand("lovewebadmin");
        if (loveWebAdminPluginCommand != null) {
            loveWebAdminPluginCommand.setExecutor(loveWebAdminCommand);
            loveWebAdminPluginCommand.setTabCompleter(loveWebAdminCommand);
        }

        int port = getConfig().getInt("web.port", 8080);
        String host = getConfig().getString("web.host", "0.0.0.0");
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            getLogger().warning("=============================================================================");
            getLogger().warning("[LoveWebAdmin] web.host = \"" + host + "\" - веб-панель доступна с ЛЮБОГО IP,");
            getLogger().warning("[LoveWebAdmin] который может достучаться до этого порта (" + port + "), включая");
            getLogger().warning("[LoveWebAdmin] игроков на сервере, зная только его адрес. Если панель не должна");
            getLogger().warning("[LoveWebAdmin] быть доступна публично, поставьте web.host: \"127.0.0.1\" в config.yml");
            getLogger().warning("[LoveWebAdmin] и используйте reverse proxy/VPN для доступа снаружи, либо закройте");
            getLogger().warning("[LoveWebAdmin] порт " + port + " файрволом хостинга для всех кроме нужных IP.");
            getLogger().warning("=============================================================================");
        }
        this.webServer = new WebServer(this, port, host);
        Bukkit.getAsyncScheduler().runNow(this, task -> webServer.start());

        getLogger().info("LoveWebAdmin запущен на " + host + ":" + port);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (webServer != null) webServer.stop();
        if (sessionManager != null) sessionManager.stopCleanupTask();
        if (loginAttemptTracker != null) loginAttemptTracker.stopCleanupTask();
        if (logManager != null) logManager.stopCapture();
        if (databaseManager != null) databaseManager.close();
        getLogger().info("LoveWebAdmin остановлен.");
    }

    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMillis) / 1000;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public LogManager getLogManager() {
        return logManager;
    }

    public RoleManager getRoleManager() {
        return roleManager;
    }

    public AdminManager getAdminManager() {
        return adminManager;
    }

    public LoginAttemptTracker getLoginAttemptTracker() {
        return loginAttemptTracker;
    }

    public LuckPermsManager getLuckPermsManager() {
        return luckPermsManager;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public CommandLogListener getCommandLogListener() {
        return commandLogListener;
    }

    public VesuvioBridge getVesuvioBridge() {
        return vesuvioBridge;
    }
}
