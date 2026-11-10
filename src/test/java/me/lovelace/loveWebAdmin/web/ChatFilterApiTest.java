package me.lovelace.loveWebAdmin.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import me.lovelace.lovechatfilter.api.LoveChatFilterAPI;
import me.lovelace.loveWebAdmin.models.Permission;
import me.lovelace.loveWebAdmin.web.handlers.ApiChatFilterHandler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.*;

class ChatFilterApiTest {
    SupportApiTest f;
    Map<String, String> viewer, manager, nobody;
    final List<String> words = new ArrayList<>();

    final LoveChatFilterAPI fake = new LoveChatFilterAPI() {
        public List<Map<String, Object>> listWords(String kind) {
            if (!kind.equals("bad")) throw new IllegalArgumentException("Неизвестный вид слов");
            return words.stream().map(w -> Map.<String, Object>of("word", w, "source", "game")).toList();
        }

        public String addWord(String kind, String word, String addedBy) {
            if (word.length() < 3) throw new IllegalArgumentException("Слово слишком короткое.");
            words.add(word);
            return word;
        }

        public boolean removeWord(String kind, String word) {
            return words.remove(word);
        }

        public Map<String, Object> checkText(String text) {
            return Map.of("text", text, "clean", !text.contains("дурак"), "profane", text.contains("дурак"));
        }

        public List<Map<String, Object>> settings() {
            return List.of(Map.of("key", "modules.anti-caps", "value", true));
        }

        public void changeSetting(String key, String value) {
            if (!key.equals("modules.anti-caps")) throw new IllegalArgumentException("Эту настройку менять нельзя.");
        }

        public void resetSetting(String key) {}

        public Map<String, Object> statistics(int days) {
            return Map.of("days", days);
        }
    };

    @BeforeEach
    void setup() throws Exception {
        f = new SupportApiTest();
        f.setup();
        viewer = f.session("CfViewer", Set.of(Permission.VIEW_CHATFILTER));
        manager = f.session("CfManager", Set.of(Permission.VIEW_CHATFILTER, Permission.MANAGE_CHATFILTER));
        nobody = f.session("CfNobody", Set.of(Permission.VIEW_REPORTS));
        Bukkit.getServicesManager().register(LoveChatFilterAPI.class, fake, f.plugin, ServicePriority.Normal);
    }

    @AfterEach
    void stop() {
        f.stop();
    }

    private SupportApiTest.Result call(String method, String path, Map<String, String> params, String body, Map<String, String> headers)
            throws Exception {
        return f.call(new ApiChatFilterHandler(f.plugin), method, path, params, body, headers);
    }

    @Test
    void everythingNeedsASessionAndTheViewPermission() throws Exception {
        assertEquals(401, call("GET", "/words", Map.of("kind", "bad"), null, Map.of()).status);
        assertEquals(403, call("GET", "/words", Map.of("kind", "bad"), null, nobody).status);
        assertEquals(403, call("GET", "/settings", Map.of(), null, nobody).status);
        assertEquals(403, call("GET", "/stats", Map.of(), null, nobody).status);
        assertEquals(200, call("GET", "/words", Map.of("kind", "bad"), null, viewer).status);
    }

    @Test
    void changesNeedTheManagePermission() throws Exception {
        for (String path : List.of("/words", "/words/remove", "/settings", "/settings/reset"))
            assertEquals(403, call("POST", path, Map.of(), "{\"kind\":\"bad\",\"word\":\"дурак\",\"key\":\"modules.anti-caps\",\"value\":\"true\"}", viewer).status, path);
        assertTrue(words.isEmpty());
        assertEquals(200, call("POST", "/words", Map.of(), "{\"kind\":\"bad\",\"word\":\"дурак\"}", manager).status);
        assertEquals(List.of("дурак"), words);
    }

    @Test
    void textCheckOnlyNeedsViewing() throws Exception {
        var result = call("POST", "/check", Map.of(), "{\"text\":\"ты дурак\"}", viewer);
        assertEquals(200, result.status);
        assertTrue(result.body.toString().contains("profane"));
    }

    @Test
    void aRefusalOfThePluginIsAnswered400WithItsText() throws Exception {
        var result = call("POST", "/words", Map.of(), "{\"kind\":\"bad\",\"word\":\"аб\"}", manager);
        assertEquals(400, result.status);
        assertTrue(result.body.toString().contains("слишком короткое"));
        var setting = call("POST", "/settings", Map.of(), "{\"key\":\"ignore-commands\",\"value\":\"x\"}", manager);
        assertEquals(400, setting.status);
        assertEquals(400, call("GET", "/words", Map.of("kind", "что-то"), null, viewer).status);
    }

    @Test
    void removingReportsWhetherTheWordWasThere() throws Exception {
        words.add("дурак");
        assertTrue(call("POST", "/words/remove", Map.of(), "{\"kind\":\"bad\",\"word\":\"дурак\"}", manager).body.toString().contains("true"));
        assertTrue(call("POST", "/words/remove", Map.of(), "{\"kind\":\"bad\",\"word\":\"дурак\"}", manager).body.toString().contains("false"));
    }

    @Test
    void withoutThePluginTheAnswerIs503() throws Exception {
        Bukkit.getServicesManager().unregisterAll(f.plugin);
        var result = call("GET", "/words", Map.of("kind", "bad"), null, viewer);
        assertEquals(503, result.status);
        assertTrue(result.body.toString().contains("LoveChatFilter"));
        assertTrue(call("GET", "/status", Map.of(), null, viewer).body.toString().contains("false"));
    }

    @Test
    void statisticsDaysAreClamped() throws Exception {
        assertTrue(call("GET", "/stats", Map.of("days", "5000"), null, viewer).body.toString().contains("90"));
        assertTrue(call("GET", "/stats", Map.of("days", "0"), null, viewer).body.toString().contains("1"));
    }
}
