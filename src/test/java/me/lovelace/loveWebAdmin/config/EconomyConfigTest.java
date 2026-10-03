package me.lovelace.loveWebAdmin.config;

import dev.lovelace.lovecore.api.economy.MoneyParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EconomyConfigTest {

    @Test
    void anomalyThresholdParsesAndIsTenDiamondCoins() throws Exception {
        YamlConfiguration cfg;
        try (Reader r = new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            cfg = YamlConfiguration.loadConfiguration(r);
        }
        Object raw = cfg.get("economy.anomaly-threshold");
        assertNotNull(raw);
        long value = raw instanceof Number n ? n.longValue() : MoneyParser.parse(String.valueOf(raw), MoneyParser.STANDARD);
        assertEquals(10 * 20_000L, value);
        assertEquals(value, MoneyParser.parse("10d", MoneyParser.STANDARD));
    }
}
