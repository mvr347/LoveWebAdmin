package me.lovelace.lovechatfilter.api;

import java.util.List;
import java.util.Map;

/**
 * Test double of LoveChatFilter's public API under its real class name: the panel finds the service by that name, so
 * a fake registered under it behaves like the installed plugin. Only the methods the panel calls are listed.
 */
public interface LoveChatFilterAPI {
    List<Map<String, Object>> listWords(String kind);

    String addWord(String kind, String word, String addedBy);

    boolean removeWord(String kind, String word);

    Map<String, Object> checkText(String text);

    List<Map<String, Object>> settings();

    void changeSetting(String key, String value);

    void resetSetting(String key);

    Map<String, Object> statistics(int days);
}
