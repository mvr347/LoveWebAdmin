package me.lovelace.loveWebAdmin.content;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentSchemaTest {
  private Map<String,Object> catalogs(){
    var result=new LinkedHashMap<String,Object>();for(String kind:ContentSchema.KINDS)result.put(kind,new LinkedHashMap<String,Object>());return result;
  }
  @SuppressWarnings("unchecked") private void add(Map<String,Object> all,String kind,String id,Map<String,Object> value){((Map<String,Object>)all.get(kind)).put(id,value);}
  @Test void contractAchievementMarkAndJoinQuitUseRealFields(){
    var all=catalogs();
    add(all,"contracts","order",Map.of("display-name","Помощь", "condition",Map.of("type","MineMaterial","material","STONE","count",16),"completion-achievement","helper"));
    add(all,"achievements","helper",Map.of("name","Помощник","trigger",Map.of("type","manual")));
    add(all,"marks","helper_mark",Map.of("display","Помощник","achievement","helper"));
    add(all,"joinquit","helper_style",Map.of("name","Помощник","join","Пришёл <player>","quit","Ушёл <player>","achievement","helper"));
    assertEquals(List.of(),ContentSchema.validate(all));
    assertEquals(3,ContentSchema.links(all).size());
  }
  @Test void missingAchievementAndCircularUnlocksBlockPublication(){
    var all=catalogs();add(all,"marks","mark",Map.of("display","Знак","achievement","missing"));
    assertTrue(ContentSchema.validate(all).stream().anyMatch(e->e.contains("missing")));
    all=catalogs();add(all,"achievements","a",Map.of("name","A","trigger",Map.of("type","all-of","ids",List.of("b"))));
    add(all,"achievements","b",Map.of("name","B","trigger",Map.of("type","all-of","ids",List.of("a"))));
    assertTrue(ContentSchema.validate(all).stream().anyMatch(e->e.contains("цикл")));
  }
  @Test void npcEditorAndUnsafeIdsAreNotAccepted(){
    var all=catalogs();all.put("npcs",Map.of());assertFalse(ContentSchema.validate(all).isEmpty());
    all=catalogs();add(all,"marks","../escape",Map.of("display","x"));assertFalse(ContentSchema.validate(all).isEmpty());
    all=catalogs();add(all,"marks","__proto__",Map.of("display","x"));assertFalse(ContentSchema.validate(all).isEmpty());
  }
  @Test void invalidDialogueReferenceAndUnknownConditionAreReported(){
    var all=catalogs();add(all,"stories","story",Map.of("display-name","История","npc-key","existing",
        "dialogues",Map.of("greeting",Map.of("options",List.of(Map.of("action","NEXT_NODE","next-node","missing")))),
        "steps",List.of(Map.of("type","VISIT_NPC","target-npc-key","existing"))));
    assertTrue(ContentSchema.validate(all).stream().anyMatch(e->e.contains("узел диалога")));
    add(all,"contracts","broken",Map.of("display-name","x","condition",Map.of()));
    assertDoesNotThrow(()->ContentSchema.validate(all));
    assertTrue(ContentSchema.validate(all).stream().anyMatch(e->e.contains("условие")));
  }
}
