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
import me.lovelace.loveWebAdmin.managers.BanArchiveManager;
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
import me.lovelace.loveWebAdmin.utils.ProofStorage;
import me.lovelace.loveWebAdmin.web.SessionManager;
import me.lovelace.loveWebAdmin.web.WebServer;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public class LoveWebAdmin extends JavaPlugin {

  private me.lovelace.loveWebAdmin.concurrent.MainDispatcher main;
  private me.lovelace.loveWebAdmin.concurrent.VirtualIoExecutor io;

  public me.lovelace.loveWebAdmin.concurrent.MainDispatcher main() {
    return main;
  }

  public me.lovelace.loveWebAdmin.concurrent.VirtualIoExecutor io() {
    return io;
  }

  private me.lovelace.loveWebAdmin.database.ModerationStore moderation;

  public me.lovelace.loveWebAdmin.database.ModerationStore moderation() {
    return moderation;
  }

  private final me.lovelace.loveWebAdmin.managers.AppealService appeals =
      new me.lovelace.loveWebAdmin.managers.AppealService(this);

  public me.lovelace.loveWebAdmin.managers.AppealService appeals() {
    return appeals;
  }

  private me.lovelace.loveWebAdmin.integration.DiscordTemplates discordTemplates;

  public me.lovelace.loveWebAdmin.integration.DiscordTemplates discordTemplates() {
    return discordTemplates;
  }

  private final java.util.Set<java.util.UUID> onlineIds =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  public void connected(java.util.UUID id, boolean connected) {
    if (connected) onlineIds.add(id);
    else onlineIds.remove(id);
  }

  public boolean isPlayerOnline(java.util.UUID id) {
    return onlineIds.contains(id);
  }

  private DatabaseManager databaseManager;
  private LogManager logManager;
  private RoleManager roleManager;
  private AdminManager adminManager;
  private BanManager banManager;
  private BanArchiveManager banArchiveManager;
  private ProofStorage proofStorage;
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
  private me.lovelace.loveWebAdmin.managers.NotificationManager notificationManager;
  private me.lovelace.loveWebAdmin.api.LoveWebAdminAPIImpl loveWebAdminApi;
  private volatile boolean maintenanceMode = false;
  private volatile String maintenanceMessage =
      "Ведутся плановые технические работы. Панель временно доступна только руководству.";
  private long startTimeMillis;

  @Override
  public void onEnable() {
    main = new me.lovelace.loveWebAdmin.concurrent.MainDispatcher(this);
    io = new me.lovelace.loveWebAdmin.concurrent.VirtualIoExecutor("lwa-io-", 8, 256);
    try {
      Class<?> listener =
          Class.forName(
              "dev.lovelace.lovecore.api.discord.DiscordInteractionListener",
              false,
              getClass().getClassLoader());
      Class<?> service =
          Class.forName(
              "dev.lovelace.lovecore.api.discord.DiscordService",
              false,
              getClass().getClassLoader());
      service.getMethod("registerInteractionListener", listener);
      service.getMethod(
          "restoreTicket",
          Class.forName(
              "dev.lovelace.lovecore.api.discord.TicketType", false, getClass().getClassLoader()),
          String.class,
          String.class);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Обновите LoveCore вместе с LoveWebAdmin: требуется Discord API v2 из PR #37", e);
    }

    startTimeMillis = System.currentTimeMillis();
    saveDefaultConfig();
    if(getConfig().contains("debug-mode")||getConfig().contains("security.debug-mode")) {
      getConfig().set("debug-mode",null);getConfig().set("security.debug-mode",null);saveConfig();
    }


    this.databaseManager = new DatabaseManager(this);
    this.databaseManager.initialize();
    try {
      this.moderation =
          new me.lovelace.loveWebAdmin.database.ModerationStore(
              getDataFolder().toPath().resolve("moderation.db"));
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("Не удалось открыть хранилище дел", e);
    }

    try {
      this.discordTemplates =
          new me.lovelace.loveWebAdmin.integration.DiscordTemplates(
              getDataFolder().toPath().resolve("discord-templates.yml"));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Не удалось открыть шаблоны Discord", e);
    }

    onlineIds.clear();
    getServer().getOnlinePlayers().forEach(p -> onlineIds.add(p.getUniqueId()));
    getServer()
        .getScheduler()
        .runTaskTimer(
            this,
            () -> {
              int count = getServer().getOnlinePlayers().size();
              long epoch = System.currentTimeMillis() / 1000;
              io.run(() -> databaseManager.recordOnlineSample(epoch, count));
            },
            1L,
            1200L);

    this.logManager = new LogManager(this);
    this.logManager.startCapture();

    this.roleManager = new RoleManager(this);
    this.adminManager = new AdminManager(this);
    this.notificationManager = new me.lovelace.loveWebAdmin.managers.NotificationManager(this);

    if (!this.adminManager.hasOwner()) {
      String token = this.adminManager.generateSetupToken();
      getLogger().info("==================================================================");
      getLogger().info("[SECURITY] Первичная настройка Управляющего требует одноразовый токен:");
      getLogger().info(">> ТОКЕН ДЛЯ ВЕБ-ПАНЕЛИ: " + token);
      getLogger()
          .info(">> Введите этот токен на экране создания учетной записи главного администратора.");
      getLogger().info("==================================================================");
    }

    this.banManager = new BanManager(this);
    this.banManager.startCleanupTask();
    this.proofStorage = new ProofStorage(this);
    this.banArchiveManager = new BanArchiveManager(this);
    this.banArchiveManager.start();

    // Reflection-only bridges to external plugins (Vesuvio & LoveEconomy & LoveCore)
    this.vesuvioBridge = new VesuvioBridge();
    this.loveEconomyBridge = new me.lovelace.loveWebAdmin.integration.LoveEconomyBridge(this);
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
    getServer()
        .getServicesManager()
        .register(
            me.lovelace.loveWebAdmin.api.LoveWebAdminAPI.class,
            this.loveWebAdminApi,
            this,
            org.bukkit.plugin.ServicePriority.Normal);

    // LoveCore LoveEconomy 5-minute Anomaly Tracker
    this.loveEconomyTracker =
        new me.lovelace.loveWebAdmin.managers.LoveEconomyTracker(this, loveEconomyBridge);
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
      getLogger()
          .warning("=============================================================================");
      getLogger()
          .warning("[LoveWebAdmin] web.host = \"" + host + "\" - веб-панель доступна с ЛЮБОГО IP,");
      getLogger()
          .warning(
              "[LoveWebAdmin] который может достучаться до этого порта (" + port + "), включая");
      getLogger()
          .warning(
              "[LoveWebAdmin] игроков на сервере, зная только его адрес. Если панель не должна");
      getLogger()
          .warning(
              "[LoveWebAdmin] быть доступна публично, поставьте web.host: \"127.0.0.1\" в"
                  + " config.yml");
      getLogger()
          .warning(
              "[LoveWebAdmin] и используйте reverse proxy/VPN для доступа снаружи, либо закройте");
      getLogger()
          .warning("[LoveWebAdmin] порт " + port + " файрволом хостинга для всех кроме нужных IP.");
      getLogger()
          .warning("=============================================================================");
    }

    this.webServer = new WebServer(this, port, host);
    if (getConfig().getBoolean("web.enabled", true)) io.run(webServer::start);

    getLogger().info("LoveWebAdmin запущен на " + host + ":" + port);
  }

  private void safely(String component, Runnable close) {
    try {
      close.run();
    } catch (Throwable e) {
      getLogger().log(java.util.logging.Level.SEVERE, "Ошибка остановки " + component, e);
    }
  }

  @Override
  public void onDisable() {
    if (main != null) main.close();
    onlineIds.clear();
    if (discordBridge != null) safely("Discord", discordBridge::close);
    if (webServer != null) safely("HTTP", webServer::stop);
    if (banArchiveManager != null) safely("архив", banArchiveManager::stop);
    if (banManager != null) safely("наказания", banManager::stopCleanupTask);
    if (loveEconomyTracker != null) safely("экономика", loveEconomyTracker::stop);
    if (sessionManager != null) safely("сессии", sessionManager::stopCleanupTask);
    if (loginAttemptTracker != null) safely("вход", loginAttemptTracker::stopCleanupTask);
    if (logManager != null) safely("журнал", logManager::stopCapture);
    if (freezeManager != null) safely("заморозка", freezeManager::clearAll);
    me.lovelace.loveWebAdmin.api.LoveWebAdminAPIProvider.unregister();
    HandlerList.unregisterAll(this);
    safely("Bukkit scheduler", () -> getServer().getScheduler().cancelTasks(this));
    safely("Async scheduler", () -> getServer().getAsyncScheduler().cancelTasks(this));
    if (io != null) io.stop(java.time.Duration.ofSeconds(3));
    if (moderation != null)
      safely(
          "дела",
          () -> {
            try {
              moderation.close();
            } catch (java.sql.SQLException e) {
              throw new IllegalStateException(e);
            }
          });
    if (databaseManager != null) safely("SQLite", databaseManager::close);
  }

  public long getUptimeSeconds() {
    return (System.currentTimeMillis() - startTimeMillis) / 1000;
  }

  /** Retained for binary compatibility; authentication bypass has been removed. */
  @Deprecated public boolean isDebugMode() {return false;}

  public boolean isPreviewEnabled() {return getConfig().getBoolean("preview.enabled",false);}

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

  public BanArchiveManager getBanArchiveManager() {
    return banArchiveManager;
  }

  public ProofStorage getProofStorage() {
    return proofStorage;
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
      loveEconomyBridge = new me.lovelace.loveWebAdmin.integration.LoveEconomyBridge(this);
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

  public me.lovelace.loveWebAdmin.managers.NotificationManager getNotificationManager() {
    return notificationManager;
  }

  public boolean isMaintenanceMode() {
    return maintenanceMode;
  }

  public void setMaintenanceMode(boolean maintenanceMode) {
    this.maintenanceMode = maintenanceMode;
  }

  public void setMaintenanceMode(boolean maintenanceMode, String message) {
    this.maintenanceMode = maintenanceMode;
    if (message != null && !message.isBlank()) {
      this.maintenanceMessage = message;
    }
  }

  public void setMaintenanceMessage(String message) {
    if (message != null && !message.isBlank()) {
      this.maintenanceMessage = message;
    }
  }

  public String getMaintenanceMessage() {
    return maintenanceMessage;
  }

  public static me.lovelace.loveWebAdmin.api.LoveWebAdminAPI getPluginApi() {
    return me.lovelace.loveWebAdmin.api.LoveWebAdminAPIProvider.get();
  }
}
