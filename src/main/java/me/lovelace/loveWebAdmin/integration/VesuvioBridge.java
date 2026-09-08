package me.lovelace.loveWebAdmin.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicesManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reflection-only bridge to Vesuvio's public API (net.lovelace.vesuvio.api.VesuvioAPI),
 * discovered at runtime through Bukkit's ServicesManager.
 *
 * LoveWebAdmin deliberately does NOT take a compile-time dependency on Vesuvio: the two
 * plugins are built from separate repositories with no shared Maven repository between them,
 * so a compile dependency would either break CI (unresolvable artifact) or require publishing
 * infrastructure this ecosystem doesn't have. Reflection keeps the integration truly optional -
 * if Vesuvio isn't installed (or is an incompatible version missing a method), every call here
 * fails soft with {@link VesuvioUnavailableException} instead of a NoClassDefFoundError at
 * plugin load time.
 *
 * Vesuvio's API returns Java records (see Vesuvio's WebPanelModels). {@link #call} converts
 * whatever comes back into plain Map/List/String/Number/Boolean structures via
 * {@link #toJsonSafe}, ready to hand straight to JsonUtils - callers never need to know the
 * concrete record types.
 *
 * Author: Lovelace
 */
public final class VesuvioBridge {

    private static final String API_CLASS_NAME = "net.lovelace.vesuvio.api.VesuvioAPI";

    public static final class VesuvioUnavailableException extends RuntimeException {
        public VesuvioUnavailableException(String message) {
            super(message);
        }

        public VesuvioUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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
     * Invokes a no-argument VesuvioAPI method and returns its result converted to
     * JSON-safe Map/List/String/Number/Boolean structures.
     */
    public Object call(String method) {
        return call(method, new Class<?>[0]);
    }

    public Object call(String method, Class<?>[] paramTypes, Object... args) {
        Object api = findApiInstance();
        if (api == null) {
            throw new VesuvioUnavailableException("Vesuvio не установлен или ещё не загружен на сервере");
        }
        try {
            Method m = api.getClass().getMethod(method, paramTypes);
            Object result = m.invoke(api, args);
            return toJsonSafe(result);
        } catch (NoSuchMethodException e) {
            throw new VesuvioUnavailableException(
                    "Установленная версия Vesuvio несовместима (нет метода " + method + ")", e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Ошибка вызова Vesuvio API (" + method + "): " + e.getCause(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Recursively converts records, lists, maps, UUIDs and enums into plain JSON-compatible
     * structures. Anything unrecognized falls back to toString() rather than failing the whole
     * response over one odd field.
     */
    @SuppressWarnings("unchecked")
    private static Object toJsonSafe(Object value) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof UUID) return value.toString();
        if (value instanceof Enum<?> e) return e.name();

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), toJsonSafe(entry.getValue()));
            }
            return out;
        }

        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : iterable) {
                out.add(toJsonSafe(item));
            }
            return out;
        }

        Class<?> cls = value.getClass();
        if (cls.isRecord()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent rc : cls.getRecordComponents()) {
                try {
                    out.put(rc.getName(), toJsonSafe(rc.getAccessor().invoke(value)));
                } catch (ReflectiveOperationException ignored) {
                    // Skip a field we can't read rather than failing the whole conversion.
                }
            }
            return out;
        }

        return value.toString();
    }
}
