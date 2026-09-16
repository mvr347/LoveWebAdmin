package me.lovelace.loveWebAdmin.managers;

import me.lovelace.loveWebAdmin.LoveWebAdmin;

import java.util.List;
import java.util.Map;

/**
 * Менеджер системных и пользовательских уведомлений персонала внутри панели.
 * Поддерживает уровни: INFO, WARNING, IMPORTANT, CRITICAL.
 */
public class NotificationManager {

    private final LoveWebAdmin plugin;

    public NotificationManager(LoveWebAdmin plugin) {
        this.plugin = plugin;
    }

    public void createNotification(String title, String message, String type, String createdBy) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            plugin.getDatabaseManager().saveStaffNotification(title, message, type, createdBy);
        });
    }

    public void broadcast(String title, String message, String type, String createdBy) {
        createNotification(title, message, type, createdBy);
    }

    public List<Map<String, Object>> getNotifications(int limit, int offset, int adminId) {
        return plugin.getDatabaseManager().getStaffNotifications(limit, offset, adminId);
    }

    public int getUnreadCount(int adminId) {
        return plugin.getDatabaseManager().getStaffUnreadCount(adminId);
    }

    public void markAsRead(int notificationId, int adminId) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            plugin.getDatabaseManager().markStaffNotificationRead(notificationId, adminId);
        });
    }

    public void markAllAsRead(int adminId) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            plugin.getDatabaseManager().markAllStaffNotificationsRead(adminId);
        });
    }
}
