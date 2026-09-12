package me.lovelace.loveWebAdmin.integration;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Безопасный мост к модулю LoveEconomy из LoveCore.
 * Если LoveCore отсутствует, методы возвращают безопасные заглушки.
 */
public class LoveEconomyBridge {

    public boolean isAvailable() {
        try {
            return LoveCore.service(LoveEconomy.class).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    private Optional<LoveEconomy> economy() {
        try {
            return LoveCore.service(LoveEconomy.class);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public long balance(Player player) {
        if (player == null) return 0;
        return economy().map(eco -> eco.balance(player)).orElse(0L);
    }

    public String currencyName() {
        return economy().map(LoveEconomy::currencyName).orElse("монет");
    }

    public boolean give(Player player, long amount) {
        if (player == null || amount <= 0) return false;
        var eco = economy();
        if (eco.isEmpty()) return false;
        eco.get().give(player, amount);
        return true;
    }

    public boolean charge(Player player, long amount) {
        if (player == null || amount <= 0) return false;
        var eco = economy();
        if (eco.isEmpty()) return false;
        return eco.get().charge(player, amount);
    }
}
