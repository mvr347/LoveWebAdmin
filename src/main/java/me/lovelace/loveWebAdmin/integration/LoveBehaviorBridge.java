package me.lovelace.loveWebAdmin.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicesManager;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reflection bridge к LoveBehavior (рейтинг Вежливости и Стиля игры).
 */
public class LoveBehaviorBridge {

    private static final String API_CLASS_NAME = "me.lovelace.lovebehavior.api.LoveBehaviorAPI";

    public boolean isAvailable() {
        return findApiInstance() != null;
    }

    private Object findApiInstance() {
        ServicesManager sm = Bukkit.getServicesManager();
        for (Class<?> svc : sm.getKnownServices()) {
            if (svc.getName().equals(API_CLASS_NAME)) {
                var reg = sm.getRegistration(svc);
                return (reg != null) ? reg.getProvider() : null;
            }
        }
        return null;
    }

    /**
     * Получает рейтинг поведения игрока:
     * Очки вежливости, уровень вежливости, очки стиля, уровень стиля.
     */
    public Map<String, Object> getPlayerBehavior(UUID uuid) {
        Map<String, Object> map = new LinkedHashMap<>();
        Object api = findApiInstance();
        if (api == null) {
            map.put("available", false);
            return map;
        }

        try {
            Class<?> cls = api.getClass();
            Method getPolitenessPoints = cls.getMethod("getPolitenessPoints", UUID.class);
            Method getPolitenessLevel = cls.getMethod("getPolitenessLevel", UUID.class);
            Method getPlaystylePoints = cls.getMethod("getPlaystylePoints", UUID.class);
            Method getPlaystyleLevel = cls.getMethod("getPlaystyleLevel", UUID.class);

            int pPoints = (int) getPolitenessPoints.invoke(api, uuid);
            int pLevel = (int) getPolitenessLevel.invoke(api, uuid);
            int sPoints = (int) getPlaystylePoints.invoke(api, uuid);
            int sLevel = (int) getPlaystyleLevel.invoke(api, uuid);

            map.put("available", true);
            map.put("politenessPoints", pPoints);
            map.put("politenessLevel", pLevel);
            map.put("playstylePoints", sPoints);
            map.put("playstyleLevel", sLevel);
            map.put("maxPoints", 7000);
        } catch (Exception e) {
            map.put("available", false);
            map.put("error", e.getMessage());
        }

        return map;
    }

    /**
     * Изменяет очки вежливости игрока в LoveBehavior на delta (может быть положительным или отрицательным).
     * @return true если успешно изменено, false если LoveBehavior недоступен.
     */
    public boolean modifyPoliteness(UUID uuid, int delta) {
        Object api = findApiInstance();
        if (api == null || uuid == null) {
            return false;
        }

        try {
            Class<?> cls = api.getClass();

            // 1. Проверяем наличие прямых методов addPolitenessPoints / modifyPolitenessPoints
            for (Method m : cls.getMethods()) {
                if ((m.getName().equalsIgnoreCase("addPolitenessPoints") ||
                     m.getName().equalsIgnoreCase("modifyPolitenessPoints")) &&
                    m.getParameterCount() == 2 &&
                    m.getParameterTypes()[0].equals(UUID.class)) {
                    m.invoke(api, uuid, delta);
                    return true;
                }
            }

            // 2. Если отдельного add-метода нет, пробуем get + set
            Method getMethod = null;
            Method setMethod = null;
            for (Method m : cls.getMethods()) {
                if (m.getName().equalsIgnoreCase("getPolitenessPoints") && m.getParameterCount() == 1) {
                    getMethod = m;
                } else if (m.getName().equalsIgnoreCase("setPolitenessPoints") && m.getParameterCount() == 2) {
                    setMethod = m;
                }
            }

            if (getMethod != null && setMethod != null) {
                int current = (int) getMethod.invoke(api, uuid);
                int updated = Math.max(0, current + delta);
                setMethod.invoke(api, uuid, updated);
                return true;
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[LoveWebAdmin] Не удалось изменить рейтинг LoveBehavior для " + uuid + ": " + e.getMessage());
        }

        return false;
    }
}
