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
    private Server server;

    public WebServer(LoveWebAdmin plugin, int port) {
        this.plugin = plugin;
        this.port = port;
    }

    public void start() {
        try {
            server = new Server(port);

            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
            context.setContextPath("/");
            context.addFilter(new FilterHolder(new CorsFilter(plugin)), "/*", EnumSet.of(DispatcherType.REQUEST));

            ServletHolder authHolder = new ServletHolder(new ApiAuthHandler(plugin));
            context.addServlet(authHolder, "/api/auth/*");
            context.addServlet(authHolder, "/api/me");

            context.addServlet(new ServletHolder(new ApiStatsHandler(plugin)), "/api/stats");
            context.addServlet(new ServletHolder(new ApiLogsHandler(plugin)), "/api/logs/*");
            context.addServlet(new ServletHolder(new ApiCommandHandler(plugin)), "/api/command");
            context.addServlet(new ServletHolder(new ApiAdminsHandler(plugin)), "/api/admins/*");
            context.addServlet(new ServletHolder(new ApiRolesHandler(plugin)), "/api/roles/*");
            context.addServlet(new ServletHolder(new ApiVesuvioHandler(plugin)), "/api/vesuvio/*");
            context.addServlet(new ServletHolder(new StaticHandler()), "/*");

            server.setHandler(context);
            server.start();
            plugin.getLogger().info("Веб-сервер запущен на порту " + port);
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
