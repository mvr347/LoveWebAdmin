package me.lovelace.loveWebAdmin.api;

/**
 * Статический провайдер для удобного получения инстанса LoveWebAdminAPI.
 *
 * <pre>{@code
 * LoveWebAdminAPI api = LoveWebAdminAPIProvider.get();
 * if (api.isPlayerBanned("BadPlayer")) { ... }
 * }</pre>
 */
public final class LoveWebAdminAPIProvider {

    private static LoveWebAdminAPI instance;

    private LoveWebAdminAPIProvider() {}

    public static LoveWebAdminAPI get() {
        if (instance == null) {
            throw new IllegalStateException("LoveWebAdminAPI ещё не инициализирован! Убедитесь, что LoveWebAdmin загружен.");
        }
        return instance;
    }

    public static void register(LoveWebAdminAPI api) {
        instance = api;
    }

    public static void unregister() {
        instance = null;
    }

    public static boolean isAvailable() {
        return instance != null;
    }
}
