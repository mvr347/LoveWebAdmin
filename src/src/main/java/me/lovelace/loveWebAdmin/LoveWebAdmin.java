package me.lovelace.loveWebAdmin;

import me.lovelace.loveWebAdmin.commands.BanCommand;
import me.lovelace.loveWebAdmin.commands.LoveWebAdminCommand;
import me.lovelace.loveWebAdmin.commands.ReportCommand;
import me.lovelace.loveWebAdmin.commands.UnbanCommand;
import me.lovelace.loveWebAdmin.database.DatabaseManager;
import me.lovelace.loveWebAdmin.integration.VesuvioBridge;
import me.lovelace.loveWebAdmin.listeners.ChatLogListener;
import me.lovelace.loveWebAdmin.listeners.CommandLogListener;
import me.lovelace.loveWebAdmin.listeners.PlayerConnectionListener;
import me.lovelace.loveWebAdmin.listeners.PlayerFreezeListener;
import me.lovelace.loveWebAdmin.listeners.ReportMenuListener;
import me.lovelace.loveWebAdmin.managers.AdminManager;
import me.lovelace.loveWebAdmin.managers.BanManager;
import me.lovelace.loveWebAdmin.managers.FreezeManager;
import me.lovelace.loveWebAdmin.managers.LogManager;
import me.lovelace.loveWebAdmin.managers.LoginAttemptTracker;
import me.lovelace.loveWebAdmin.managers.LuckPermsManager;
import me.lovelace.loveWebAdmin.managers.PlayerInventoryManager;
import me.lovelace.loveWebAdmin.managers.PlayerProfileManager;
import me.lovelace.loveWebAdmin.managers.ReportManager;
import me.lovelace.loveWebAdmin.managers.RoleManager;
import me.lovelace.loveWebAdmin.services.SecurityWebhookService;
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
    private BanManager banManager;
    private PlayerProfileManager playerProfileManager;
    private ReportManager reportManager;
    private LoginAttemptTracker loginAttemptTracker;
    private LuckPermsManager luckPermsManager;
    private SessionManager sessionManager;
    private CommandLogListener commandLogListener;
    private ChatLogListener chatLogListener;
    private PlayerConnectionListener playerConnectionListener;
    private FreezeManager freezeManager;
    private PlayerInventoryManager playerInventoryManager;
    private SecurityWebhookService securityWebhookService;
    private WebServer webServer;
    private VesuvioBridge vesuvioBridge;
    private me.lovelace.loveWebAdmin.managers.LockdownManager lockdownManager;
    private me.lovelace.loveWebAdmin.managers.StaffAuditManager staffAuditManager;
    private me.lovelace.loveWebAdmin.integration.LoveEconomyBridge loveEconomyBridge;
    private me.lovelace.loveWebAdmin.integration.LoveCoreDiscordBridge discordBridge;
    private me.lovelace.loveWebAdmin.managers.LoveEconomyTracker loveEconomyTracker;
    private me.lovelace.loveWebAdmin.managers.ApiKeyManager apiKeyManager;
    private me.lovelace.loveWebAdmin.managers.WebhookManager webhookManager;
    private me.lovelace.loveWebAdmin.api.LoveWebAdminAPI loveWebAdminApi;
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
        this.banManager = new BanManager(this);
        this.banManager.startCleanupTask();

        // Reflection-only bridges to external plugins (Vesuvio & LoveEconomy & LoveCore)
        this.vesuvioBridge = new VesuvioBridge();
        this.loveEconomyBridge = new me.lovelace.loveWebAdmin.integration.LoveEconomyBridge();
        this.discordBridge = new me.lovelace.loveWebAdmin.integration.LoveCoreDiscordBridge(this);
        this.discordBridge.initialize();

        this.playerProfileManager = new PlayerProfileManager(this);

        this.loginAttemptTracker = new LoginAttemptTracker(this);
        this.loginAttemptTracker.startCleanupTask();
        this.luckPermsManager = new LuckPermsManager(this);

        this.sessionManager = new SessionManager(this);
        this.sessionManager.loadFromDatabase();
        this.sessionManager.startCleanupTask();

        this.lockdownManager = new me.lovelace.loveWebAdmin.managers.LockdownManager(this);
        getServer().getPluginManager().registerEvents(lockdownManager, this);

        this.staffAuditManager = new me.lovelace.loveWebAdmin.managers.StaffAuditManager(this);

        this.commandLogListener = new CommandLogListener(this);
        getServer().getPluginManager().registerEvents(commandLogListener, this);

        this.chatLogListener = new ChatLogListener(this);
        getServer().getPluginManager().registerEvents(chatLogListener, this);

        this.playerConnectionListener = new PlayerConnectionListener(this);
        getServer().getPluginManager().registerEvents(playerConnectionListener, this);

        this.freezeManager = new FreezeManager(this);
        getServer().getPluginManager().registerEvents(new PlayerFreezeListener(this), this);

        this.reportManager = new ReportManager(this);
        getServer().getPluginManager().registerEvents(new ReportMenuListener(this), this);

        this.playerInventoryManager = new PlayerInventoryManager(this);
        this.securityWebhookService = new SecurityWebhookService(this);

        this.apiKeyManager = new me.lovelace.loveWebAdmin.managers.ApiKeyManager(this);
        this.webhookManager = new me.lovelace.loveWebAdmin.managers.WebhookManager(this);
        this.loveWebAdminApi = new me.lovelace.loveWebAdmin.api.LoveWebAdminAPIImpl(this);
        me.lovelace.loveWebAdmin.api.LoveWebAdminAPIProvider.register(this.loveWebAdminApi);
        getServer().getServicesManager().register(
            me.lovelace.loveWebAdmin.api.LoveWebAdminAPI.class,
            this.loveWebAdminApi,
            this,
            org.bukkit.plugin.ServicePriority.Normal
        );

        // LoveCore LoveEconomy 5-minute Anomaly Tracker
        this.loveEconomyTracker = new me.lovelace.loveWebAdmin.managers.LoveEconomyTracker(this, loveEconomyBridge);
        this.loveEconomyTracker.start();

        // /lovewebadmin (алиас /lwa)
        LoveWebAdminCommand loveWebAdminCommand = new LoveWebAdminCommand(this);
        var loveWebAdminPluginCommand = getCommand("lovewebadmin");
        if (loveWebAdminPluginCommand != null) {
            loveWebAdminPluginCommand.setExecutor(loveWebAdminCommand);
            loveWebAdminPluginCommand.setTabCompleter(loveWebAdminCommand);
        }

        // /бан (алиасы /ban, /webban, /lban)
        BanCommand banCommand = new BanCommand(this);
        var banPluginCommand = getCommand("бан");
        if (banPluginCommand != null) {
            banPluginCommand.setExecutor(banCommand);
            banPluginCommand.setTabCompleter(banCommand);
        }

        // /репорт (алиасы /жалоба, /report)
        ReportCommand reportCommand = new ReportCommand(this);
        var reportPluginCommand = getCommand("report");
        if (reportPluginCommand != null) {
            reportPluginCommand.setExecutor(reportCommand);
            reportPluginCommand.setTabCompleter(reportCommand);
        }

        // /разбан (алиасы /unban, /webunban, /lunban, /pardon)
        UnbanCommand unbanCommand = new UnbanCommand(this);
        var unbanPluginCommand = getCommand("разбан");
        if (unbanPluginCommand != null) {
            unbanPluginCommand.setExecutor(unbanCommand);
            unbanPluginCommand.setTabCompleter(unbanCommand);
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
        me.lovelace.loveWebAdmin.api.LoveWebAdminAPIProvider.unregister();
        HandlerList.unregisterAll(this);
        if (loveEconomyTracker != null) loveEconomyTracker.stop();
        if (freezeManager != null) freezeManager.clearAll();
        if (webServer != null) webServer.stop();
        if (banManager != null) banManager.stopCleanupTask();
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

    public BanManager getBanManager() {
        return banManager;
    }

    public PlayerProfileManager getPlayerProfileManager() {
        return playerProfileManager;
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
        if (vesuvioBridge == null) {
            vesuvioBridge = new VesuvioBridge();
        }
        return vesuvioBridge;
    }

    public FreezeManager getFreezeManager() {
        return freezeManager;
    }

    public PlayerInventoryManager getPlayerInventoryManager() {
        return playerInventoryManager;
    }

    public SecurityWebhookService getSecurityWebhookService() {
        return securityWebhookService;
    }

    public me.lovelace.loveWebAdmin.managers.LockdownManager getLockdownManager() {
        return lockdownManager;
    }

    public me.lovelace.loveWebAdmin.managers.StaffAuditManager getStaffAuditManager() {
        return staffAuditManager;
    }

    public me.lovelace.loveWebAdmin.integration.LoveEconomyBridge getLoveEconomyBridge() {
        if (loveEconomyBridge == null) {
            loveEconomyBridge = new me.lovelace.loveWebAdmin.integration.LoveEconomyBridge();
        }
        return loveEconomyBridge;
    }

    public me.lovelace.loveWebAdmin.integration.LoveCoreDiscordBridge getDiscordBridge() {
        return discordBridge;
    }

    public me.lovelace.loveWebAdmin.managers.LoveEconomyTracker getLoveEconomyTracker() {
        return loveEconomyTracker;
    }

    public ReportManager getReportManager() {
        return reportManager;
    }

    public me.lovelace.loveWebAdmin.managers.ApiKeyManager getApiKeyManager() {
        return apiKeyManager;
    }

    public me.lovelace.loveWebAdmin.managers.WebhookManager getWebhookManager() {
        return webhookManager;
    }

    public me.lovelace.loveWebAdmin.api.LoveWebAdminAPI getApi() {
        return loveWebAdminApi;
    }

    public static me.lovelace.loveWebAdmin.api.LoveWebAdminAPI getPluginApi() {
        return me.lovelace.loveWebAdmin.api.LoveWebAdminAPIProvider.get();
    }
}
