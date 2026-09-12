package me.lovelace.loveWebAdmin.web;

import jakarta.servlet.DispatcherType;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.web.handlers.ApiAdminsHandler;
import me.lovelace.loveWebAdmin.web.handlers.ApiAuthHandler;
import me.lovelace.loveWebAdmin.web.handlers.ApiCommandHandler;
import me.lovelace.loveWebAdmin.web.handlers.ApiLogsHandler;
import me.lovelace.loveWebAdmin.web.handlers.ApiRolesHandler;
import me.lovelace.loveWebAdmin.web.handlers.ApiStatsHandler;
import me.lovelace.loveWebAdmin.web.handlers.ApiVesuvioHandler;
import me.lovelace.loveWebAdmin.web.handlers.StaticHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.util.EnumSet;

/**
 * Встроенный Jetty HTTP сервер. Запускается асинхронно в onEnable(), останавливается в onDisable().
 */
public class WebServer {

    private final LoveWebAdmin plugin;
    private final int port;
    private final String host;
    private Server server;

    public WebServer(LoveWebAdmin plugin, int port, String host) {
        this.plugin = plugin;
        this.port = port;
        this.host = host;
    }

    public void start() {
        try {
            server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(port);
            connector.setHost(host);
            server.addConnector(connector);

            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
            context.setContextPath("/");
            context.addFilter(new FilterHolder(new CorsFilter(plugin)), "/*", EnumSet.of(DispatcherType.REQUEST));

            ServletHolder authHolder = new ServletHolder(new ApiAuthHandler(plugin));
            context.addServlet(authHolder, "/api/auth");
            context.addServlet(authHolder, "/api/auth/*");
            context.addServlet(authHolder, "/api/me");
            context.addServlet(authHolder, "/api/me/*");

            ServletHolder statsHolder = new ServletHolder(new ApiStatsHandler(plugin));
            context.addServlet(statsHolder, "/api/stats");
            context.addServlet(statsHolder, "/api/stats/*");

            ServletHolder analyticsHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiAnalyticsHandler(plugin));
            context.addServlet(analyticsHolder, "/api/analytics");
            context.addServlet(analyticsHolder, "/api/analytics/*");

            ServletHolder bansHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiBansHandler(plugin));
            context.addServlet(bansHolder, "/api/bans");
            context.addServlet(bansHolder, "/api/bans/*");

            ServletHolder appealsHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiAppealsHandler(plugin));
            context.addServlet(appealsHolder, "/api/appeals");
            context.addServlet(appealsHolder, "/api/appeals/*");

            ServletHolder playersHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiPlayersHandler(plugin));
            context.addServlet(playersHolder, "/api/players");
            context.addServlet(playersHolder, "/api/players/*");

            ServletHolder loveAuthHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiLoveAuthHandler(plugin));
            context.addServlet(loveAuthHolder, "/api/loveauth");
            context.addServlet(loveAuthHolder, "/api/loveauth/*");

            ServletHolder logsHolder = new ServletHolder(new ApiLogsHandler(plugin));
            context.addServlet(logsHolder, "/api/logs");
            context.addServlet(logsHolder, "/api/logs/*");

            ServletHolder commandHolder = new ServletHolder(new ApiCommandHandler(plugin));
            context.addServlet(commandHolder, "/api/command");
            context.addServlet(commandHolder, "/api/command/*");

            ServletHolder adminsHolder = new ServletHolder(new ApiAdminsHandler(plugin));
            context.addServlet(adminsHolder, "/api/admins");
            context.addServlet(adminsHolder, "/api/admins/*");

            ServletHolder rolesHolder = new ServletHolder(new ApiRolesHandler(plugin));
            context.addServlet(rolesHolder, "/api/roles");
            context.addServlet(rolesHolder, "/api/roles/*");

            ServletHolder vesuvioHolder = new ServletHolder(new ApiVesuvioHandler(plugin));
            context.addServlet(vesuvioHolder, "/api/vesuvio");
            context.addServlet(vesuvioHolder, "/api/vesuvio/*");

            ServletHolder lockdownHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiLockdownHandler(plugin));
            context.addServlet(lockdownHolder, "/api/lockdown");
            context.addServlet(lockdownHolder, "/api/lockdown/*");

            ServletHolder staffAuditHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiStaffAuditHandler(plugin));
            context.addServlet(staffAuditHolder, "/api/staff-audit");
            context.addServlet(staffAuditHolder, "/api/staff-audit/*");

            ServletHolder economyHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiEconomyHandler(plugin));
            context.addServlet(economyHolder, "/api/economy");
            context.addServlet(economyHolder, "/api/economy/*");

            ServletHolder reportsHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiReportsHandler(plugin));
            context.addServlet(reportsHolder, "/api/reports");
            context.addServlet(reportsHolder, "/api/reports/*");

            ServletHolder serverOpsHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiServerOpsHandler(plugin));
            context.addServlet(serverOpsHolder, "/api/server");
            context.addServlet(serverOpsHolder, "/api/server/*");

            ServletHolder devHolder = new ServletHolder(new me.lovelace.loveWebAdmin.web.handlers.ApiDeveloperHandler(plugin));
            context.addServlet(devHolder, "/api/developer");
            context.addServlet(devHolder, "/api/developer/*");

            context.addServlet(new ServletHolder(new StaticHandler()), "/*");

            server.setHandler(context);
            server.start();
            plugin.getLogger().info("Веб-сервер запущен на " + host + ":" + port);
        } catch (Exception e) {
            plugin.getLogger().severe("Не удалось запустить веб-сервер: " + e.getMessage());
        }
    }

    public void stop() {
        try {
            if (server != null) {
                server.stop();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при остановке веб-сервера: " + e.getMessage());
        }
    }
}
