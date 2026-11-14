package me.lovelace.loveWebAdmin.content;

import java.util.*;

/** Native catalogue references, not an alternative reward engine. No player state is edited. */
public final class ContentSchema {
  public static final List<String> KINDS = List.of("contracts", "stories", "achievements", "marks", "joinquit");
  private static final Set<String> RARITIES = Set.of("COMMON", "RARE", "EPIC", "LEGENDARY");
  private ContentSchema() {}
  public static Map<String,Object> map(Object value) {
    if (!(value instanceof Map<?,?> m)) throw new IllegalArgumentException("Ожидается объект");
    Map<String,Object> result = new LinkedHashMap<>();
    m.forEach((k,v) -> result.put(String.valueOf(k),v)); return result;
  }
  public static void requireShape(Map<String,Object> catalogs) {
    for(String kind:catalogs.keySet())if(!KINDS.contains(kind))throw new IllegalArgumentException("Неизвестный каталог: "+kind);
    for(String kind:KINDS)for(Object value:map(catalogs.getOrDefault(kind,Map.of())).values()) {
      var d=map(value);
      if(kind.equals("marks")&&d.containsKey("lore")&&!(d.get("lore") instanceof List<?>))throw new IllegalArgumentException("lore должен быть списком");
      if(kind.equals("achievements")&&d.containsKey("trigger")) {
        var trigger=map(d.get("trigger"));
        for(String key:List.of("ids","values"))if(trigger.containsKey(key)&&!(trigger.get(key) instanceof List<?>))throw new IllegalArgumentException(key+" должен быть списком");
      }
      if(kind.equals("stories")) {
        if(d.containsKey("steps")&&!(d.get("steps") instanceof List<?>))throw new IllegalArgumentException("steps должен быть списком");
        for(Object step:(List<?>)d.getOrDefault("steps",List.of())){
          var definition=map(step);if(definition.containsKey("dialogue"))dialogueShape(map(definition.get("dialogue")));
        }
        for(var entry:map(d.getOrDefault("dialogues",Map.of())).entrySet()){
          if(!entry.getKey().matches("[a-zA-Z0-9_-]{1,64}")||Set.of("__proto__","constructor","prototype").contains(entry.getKey()))throw new IllegalArgumentException("Недопустимый ID узла диалога");
          dialogueShape(map(entry.getValue()));
        }
      }
    }
  }
  private static void dialogueShape(Map<String,Object> node) {
    for(String key:List.of("text","options"))if(node.containsKey(key)&&!(node.get(key) instanceof List<?>))throw new IllegalArgumentException(key+" должен быть списком");
    for(Object option:(List<?>)node.getOrDefault("options",List.of()))map(option);
  }
  public static List<Map<String,String>> links(Map<String,Object> catalogs) {
    List<Map<String,String>> links = new ArrayList<>();
    for (String kind : KINDS) for (var item : map(catalogs.getOrDefault(kind,Map.of())).entrySet()) {
      var d = map(item.getValue()); String node = kind+":"+item.getKey();
      if (Set.of("marks","joinquit").contains(kind)) edge(links, "achievements:", d.get("achievement"), node);
      if (Set.of("contracts","stories").contains(kind)) edge(links, "achievements:", d.get("requires-achievement"), node);
      if (Set.of("contracts","stories").contains(kind) && d.get("completion-achievement") instanceof String id && !id.isBlank()) links.add(Map.of("from",node,"to","achievements:"+id));
      if (kind.equals("achievements") && d.get("trigger") instanceof Map<?,?> t
          && "all-of".equals(t.get("type")) && t.get("ids") instanceof List<?> ids)
        for (Object id : ids) edge(links,"achievements:",id,node);
    }
    return links;
  }
  private static void edge(List<Map<String,String>> links,String prefix,Object id,String to) {
    if (id instanceof String s && !s.isBlank()) links.add(Map.of("from",prefix+s,"to",to));
  }
  public static List<String> validate(Map<String,Object> catalogs) {
    List<String> errors = new ArrayList<>(); Set<String> nodes = new HashSet<>();
    try {
      requireShape(catalogs);
      for(String kind:KINDS)if(!catalogs.containsKey(kind))errors.add("Отсутствует каталог: "+kind);
      for(String kind:catalogs.keySet()) if(!KINDS.contains(kind)) errors.add("Неизвестный каталог: "+kind);
      for (String kind : KINDS) {
        var items = map(catalogs.getOrDefault(kind,Map.of()));
        if(items.size()>1000) errors.add(kind+": максимум 1000 записей");
        for(var item:items.entrySet()) {
          String at=kind+":"+item.getKey(); nodes.add(at);
          if(!item.getKey().matches("[a-zA-Z0-9_-]{1,64}")||Set.of("__proto__","constructor","prototype").contains(item.getKey())) errors.add(at+": недопустимый ID");
          var d=map(item.getValue());
          if (d.containsKey("achievement") && !Set.of("marks","joinquit").contains(kind)) errors.add(at+": поле achievement здесь не исполняется");
          if ((d.containsKey("requires-achievement") || d.containsKey("completion-achievement")) && !Set.of("contracts","stories").contains(kind)) errors.add(at+": ссылки контрактов здесь не исполняются");
          if(kind.equals("marks")) { required(d,"display",at,errors); rarity(d,at,errors); }
          if(kind.equals("joinquit")) { required(d,"name",at,errors);required(d,"join",at,errors);required(d,"quit",at,errors); }
          if(kind.equals("achievements")) {
            required(d,"name",at,errors); rarity(d,at,errors);
            if(d.get("trigger") instanceof Map<?,?> raw) {
              var t=map(raw); String type=String.valueOf(t.getOrDefault("type","manual"));
              if(!Set.of("manual","counter","set","all-of","unlocked-count").contains(type)) errors.add(at+": неизвестный триггер");
              if(Set.of("counter","set").contains(type)) required(t,"key",at,errors);
              if(Set.of("counter","unlocked-count").contains(type)) positive(t.getOrDefault("at-least",1),at,errors);
              if(type.equals("set") && (!(t.get("values") instanceof List<?> list)||list.isEmpty())) errors.add(at+": нужен непустой набор values");
              if(type.equals("all-of") && (!(t.get("ids") instanceof List<?> list)||list.isEmpty())) errors.add(at+": нужны зависимости ачивки");
            }
          }
          if(kind.equals("contracts")) {
            required(d,"display-name",at,errors);
            var c=map(d.getOrDefault("condition",Map.of()));
            if(!Set.of("KillEntity","MineMaterial","CraftItem","CatchFish").contains(String.valueOf(c.get("type")))) errors.add(at+": неизвестное условие");
            positive(c.getOrDefault("count",1),at,errors);
            if(Set.of("MineMaterial","CraftItem").contains(String.valueOf(c.get("type")))) required(c,"material",at,errors);
            if("KillEntity".equals(c.get("type"))) required(c,"entity-type",at,errors);
          }
          if(kind.equals("stories")) story(d,at,errors);
        }
      }
      var links=links(catalogs); Map<String,List<String>> graph=new HashMap<>();
      for(var link:links) {
        if(!nodes.contains(link.get("from"))) errors.add("Отсутствует источник: "+link.get("from"));
        if(!nodes.contains(link.get("to"))) errors.add("Отсутствует цель: "+link.get("to"));
        graph.computeIfAbsent(link.get("from"),k->new ArrayList<>()).add(link.get("to"));
      }
      Set<String> done=new HashSet<>(), visiting=new HashSet<>();
      for(String node:nodes) if(cycle(node,graph,done,visiting)) { errors.add("Цепочка содержит цикл зависимостей"); break; }
    } catch(IllegalArgumentException error) { errors.add(error.getMessage()); }
    return errors;
  }
  private static void story(Map<String,Object> d,String at,List<String> errors) {
    required(d,"display-name",at,errors);required(d,"npc-key",at,errors);
    var dialogues=map(d.getOrDefault("dialogues",Map.of()));
    if(!dialogues.containsKey(String.valueOf(d.getOrDefault("initial-dialogue","greeting")))) errors.add(at+": нет начального диалога");
    for(Object value:dialogues.values()) dialogue(map(value),dialogues,at,errors);
    if(!(d.get("steps") instanceof List<?> steps)||steps.isEmpty()||steps.size()>64) { errors.add(at+": нужно от 1 до 64 этапов");return; }
    for(int i=0;i<steps.size();i++) {
      var step=map(steps.get(i));String path=at+" этап "+(i+1);String type=String.valueOf(step.get("type"));
      if(!Set.of("VISIT_NPC","DELIVER_ITEM","VISIT_LOCATION","MINE_MATERIAL","CRAFT_ITEM","KILL_ENTITY","CATCH_FISH").contains(type)) errors.add(path+": неизвестный тип");
      if(Set.of("VISIT_NPC","DELIVER_ITEM").contains(type)) required(step,"target-npc-key",path,errors);
      if(Set.of("DELIVER_ITEM","MINE_MATERIAL","CRAFT_ITEM").contains(type)) required(step,"material",path,errors);
      if(type.equals("KILL_ENTITY")) required(step,"entity-type",path,errors);
      positive(step.getOrDefault("count",1),path,errors);
      if(type.equals("VISIT_LOCATION")&&!step.containsKey("location")) errors.add(path+": нужна область location");
      if(step.get("dialogue") instanceof Map<?,?> node) dialogue(map(node),dialogues,path,errors);
    }
  }
  private static void dialogue(Map<String,Object> node,Map<String,Object> dialogues,String at,List<String> errors) {
    if(node.get("options") instanceof List<?> options) {
      if(options.size()>5) errors.add(at+": максимум 5 ответов в диалоге");
      for(Object value:options) {
        var option=map(value);String action=String.valueOf(option.getOrDefault("action",option.containsKey("next-node")?"NEXT_NODE":"CLOSE"));
        if(!Set.of("NEXT_NODE","ACCEPT_QUEST","COMPLETE_STEP","CLOSE").contains(action)) errors.add(at+": неизвестное действие ответа");
        if(action.equals("NEXT_NODE")&&!dialogues.containsKey(String.valueOf(option.get("next-node")))) errors.add(at+": отсутствует узел диалога "+option.get("next-node"));
      }
    }
  }
  private static boolean cycle(String node,Map<String,List<String>> graph,Set<String> done,Set<String> visiting) {
    if(done.contains(node))return false;if(visiting.size()>128||!visiting.add(node))return true;
    for(String next:graph.getOrDefault(node,List.of()))if(cycle(next,graph,done,visiting))return true;
    visiting.remove(node);done.add(node);return false;
  }
  private static void required(Map<String,Object> d,String key,String at,List<String> errors) { if(!(d.get(key) instanceof String s)||s.isBlank())errors.add(at+": заполните "+key); }
  private static void rarity(Map<String,Object> d,String at,List<String> errors) { if(!RARITIES.contains(String.valueOf(d.getOrDefault("rarity","COMMON"))))errors.add(at+": неизвестная редкость"); }
  private static void positive(Object value,String at,List<String> errors) { if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<=0)errors.add(at+": количество должно быть больше нуля"); }
}
