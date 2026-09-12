package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.models.WebSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Обработчик модуля экономики LoveCore (LoveEconomy).
 *
 * GET  /api/economy/overview              — агрегированная сводка оборота, топ богачей и аномалии [VIEW_ECONOMY]
 * GET  /api/economy/player/{name}         — баланс конкретного игрока онлайн                       [VIEW_ECONOMY]
 * POST /api/economy/player/{name}/adjust  — начисление/списание монет                              [MANAGE_ECONOMY]
 */
public class ApiEconomyHandler extends ApiHandlerSupport {

    public ApiEconomyHandler(LoveWebAdmin plugin) {
        super(plugin);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = authenticate(req);
        if (sessionOpt.isEmpty()) {
            sendError(resp, 401, "Необходима авторизация");
            return;
        }

        WebSession session = sessionOpt.get();
        if (!hasPermission(session, Permission.VIEW_ECONOMY) && !hasPermission(session, Permission.VIEW_STATS)) {
            sendError(resp, 403, "Недостаточно прав для просмотра экономики");
            return;
        }

        String pathInfo = req.getPathInfo();
        if (pathInfo == null) pathInfo = "";

        if (pathInfo.isEmpty() || "/".equals(pathInfo) || "/overview".equals(pathInfo)) {
            Map<String, Object> overview = plugin.getLoveEconomyTracker().getOverview();
            sendSuccess(resp, overview);
            return;
        }

        if (pathInfo.startsWith("/player/")) {
            String target = pathInfo.substring("/player/".length()).trim();
            if (target.contains("/")) {
                target = target.substring(0, target.indexOf('/'));
            }

            if (target.isBlank()) {
                sendError(resp, 400, "Укажите имя игрока");
                return;
            }

            Player p = Bukkit.getPlayerExact(target);
            var bridge = plugin.getLoveEconomyBridge();
            if (p != null && p.isOnline()) {
                long balance = bridge.balance(p);
                sendSuccess(resp, Map.of(
                    "available", bridge.isAvailable(),
                    "online", true,
                    "name", p.getName(),
                    "balance", balance,
                    "currencyName", bridge.currencyName()
                ));
            } else {
                sendSuccess(resp, Map.of(
                    "available", bridge.isAvailable(),
                    "online", false,
                    "name", target,
                    "balance", 0,
                    "currencyName", bridge.currencyName(),
                    "message", "Игрок не в сети (монеты инвентарные)"
                ));
            }
            return;
        }

        sendError(resp, 404, "Маршрут не найден");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<WebSession> sessionOpt = requirePermission(req, resp, Permission.MANAGE_ECONOMY);
        if (sessionOpt.isEmpty()) return;

        String pathInfo = req.getPathInfo();
        if (pathInfo == null) pathInfo = "";

        if (pathInfo.startsWith("/player/") && pathInfo.endsWith("/adjust")) {
            String target = pathInfo.substring("/player/".length());
            target = target.substring(0, target.indexOf("/adjust")).trim();

            if (target.isBlank()) {
                sendError(resp, 400, "Укажите имя игрока");
                return;
            }

            Player targetPlayer = Bukkit.getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                sendError(resp, 400, "Игрок " + target + " должен быть онлайн для передачи/списания монет LoveEconomy");
                return;
            }

            Map<String, Object> body = readJsonBody(req);
            String action = stringOrNull(body.get("action"));
            Object amountObj = body.get("amount");
            long amount = 0L;
            if (amountObj instanceof Number n) {
                amount = n.longValue();
            } else if (amountObj != null) {
                try {
                    amount = Long.parseLong(amountObj.toString().trim());
                } catch (NumberFormatException ignored) {}
            }

            if (amount <= 0) {
                sendError(resp, 400, "Сумма должна быть строго больше нуля");
                return;
            }

            var bridge = plugin.getLoveEconomyBridge();
            if (!bridge.isAvailable()) {
                sendError(resp, 503, "Модуль LoveEconomy недоступен на сервере");
                return;
            }

            String adminName = sessionOpt.get().adminUsername();
            String currency = bridge.currencyName();

            if ("give".equalsIgnoreCase(action)) {
                boolean success = bridge.give(targetPlayer, amount);
                if (success) {
                    long newBalance = bridge.balance(targetPlayer);
                    plugin.getLogManager().logWebAction(adminName,
                        String.format("ВЫДАЧА ЭКОНОМИКИ: администратор %s выдал %d %s игроку %s (Новый баланс: %d)",
                            adminName, amount, currency, targetPlayer.getName(), newBalance));
                    sendSuccess(resp, Map.of(
                        "action", "give",
                        "amount", amount,
                        "newBalance", newBalance,
                        "currencyName", currency,
                        "message", String.format("Выдано %d %s игроку %s", amount, currency, targetPlayer.getName())
                    ));
                } else {
                    sendError(resp, 500, "Ошибка при выполнении операции выдачи LoveEconomy");
                }
                return;
            }

            if ("charge".equalsIgnoreCase(action)) {
                boolean success = bridge.charge(targetPlayer, amount);
                if (success) {
                    long newBalance = bridge.balance(targetPlayer);
                    plugin.getLogManager().logWebAction(adminName,
                        String.format("СПИСАНИЕ ЭКОНОМИКИ: администратор %s списал %d %s у игрока %s (Новый баланс: %d)",
                            adminName, amount, currency, targetPlayer.getName(), newBalance));
                    sendSuccess(resp, Map.of(
                        "action", "charge",
                        "amount", amount,
                        "newBalance", newBalance,
                        "currencyName", currency,
                        "message", String.format("Списано %d %s у игрока %s", amount, currency, targetPlayer.getName())
                    ));
                } else {
                    sendError(resp, 400, "Не удалось списать монеты: у игрока недостаточно средств на руках");
                }
                return;
            }

            sendError(resp, 400, "Неизвестное действие (допустимо: give, charge)");
            return;
        }

        sendError(resp, 404, "Маршрут не найден");
    }
}
