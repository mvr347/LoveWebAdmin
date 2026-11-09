package me.lovelace.loveWebAdmin.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatFilterBridgeTest {

    /** A public class with the API's method names; the bridge reflects into it exactly as into the plugin. */
    public static class FakeApi {
        public String lastAdded;

        public List<Map<String, Object>> listWords(String kind) {
            if (kind == null || !List.of("bad", "safe", "ad").contains(kind)) throw new IllegalArgumentException("Неизвестный вид слов");
            return List.of(Map.of("word", "дурак", "source", "game"));
        }

        public String addWord(String kind, String word, String addedBy) {
            lastAdded = kind + ":" + word + ":" + addedBy;
            return word;
        }

        public void resetSetting(String key) {
            throw new UnsupportedOperationException();
        }
    }

    private static ChatFilterBridge bridge(Object api) {
        return new ChatFilterBridge(task -> task.get(), () -> api == null ? null : new ChatFilterBridge.Binding(api, api.getClass()));
    }

    @Test
    void aMissingPluginIsReportedAsUnavailable() {
        var bridge = bridge(null);
        assertFalse(bridge.available());
        var error = assertThrows(ChatFilterBridge.ChatFilterUnavailableException.class, () -> bridge.listWords("bad"));
        assertTrue(error.getMessage().contains("не установлен"));
    }

    @Test
    void callsReachThePluginAndAnswerComesBack() {
        var api = new FakeApi();
        var bridge = bridge(api);
        assertTrue(bridge.available());
        assertEquals("дурак", bridge.listWords("bad").get(0).get("word"));
        assertEquals("дурак", bridge.addWord("bad", "дурак", "Админ"));
        assertEquals("bad:дурак:Админ", api.lastAdded);
    }

    @Test
    void aRefusalOfThePluginKeepsItsTextForTheAdministrator() {
        var error = assertThrows(IllegalArgumentException.class, () -> bridge(new FakeApi()).listWords("что-то"));
        assertEquals("Неизвестный вид слов", error.getMessage());
    }

    @Test
    void anOlderPluginWithoutTheMethodAsksForAnUpdate() {
        var error = assertThrows(ChatFilterBridge.ChatFilterUnavailableException.class, () -> bridge(new FakeApi()).statistics(7));
        assertTrue(error.getMessage().contains("Обновите LoveChatFilter"));
        assertThrows(ChatFilterBridge.ChatFilterUnavailableException.class, () -> bridge(new FakeApi()).resetSetting("x"));
    }

    @Test
    void theCallRunsThroughTheMainThreadHook() {
        var ran = new boolean[1];
        var bridge = new ChatFilterBridge(task -> { ran[0] = true; return task.get(); }, () -> new ChatFilterBridge.Binding(new FakeApi(), FakeApi.class));
        bridge.listWords("ad");
        assertTrue(ran[0]);
    }
}
