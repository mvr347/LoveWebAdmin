package me.lovelace.loveWebAdmin.content;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/** Fixed owner files and definition sections only. HTTP clients cannot select filesystem paths. */
public final class ContentCatalogs {
  private record Target(String kind,String owner,String file,String section) {}
  private static final List<Target> TARGETS=List.of(
      new Target("contracts","LoveContracts","contracts.yml","contracts"),
      new Target("stories","LoveContracts","npc_stories.yml","npc_quests"),
      new Target("achievements","LoveEssential","modules/achievements/achievements.yml","catalog"),
      new Target("marks","LoveSubnames","config.yml","tags"),
      new Target("joinquit","LoveEssential","modules/joinquit/joinquit.yml","styles"));
  private final LoveWebAdmin plugin;
  private final Path storage;
  public ContentCatalogs(LoveWebAdmin plugin) { this.plugin=plugin;storage=plugin.getDataFolder().toPath().resolve("content-editor"); }
  private Plugin owner(Target t) {
    return plugin.main().call(() -> {
      var p=Bukkit.getPluginManager().getPlugin(t.owner());
      if(p==null||!p.isEnabled())return null;
      try{p.getClass().getMethod("reloadContentDefinitions");p.getClass().getMethod("validateContentDefinitions",String.class,Map.class);return p;}catch(NoSuchMethodException old){return null;}
    }).join();
  }
  private Path path(Target t,Plugin p) throws Exception {
    Path root=p.getDataFolder().toPath().toRealPath(), file=root.resolve(t.file()).normalize();
    if(!file.startsWith(root)||!file.toRealPath().startsWith(root))throw new IllegalArgumentException("Неверный путь каталога");
    return file;
  }
  private static Object plain(Object value) {
    if(value instanceof ConfigurationSection section)return plain(section.getValues(false));
    if(value instanceof Map<?,?> map){var result=new LinkedHashMap<String,Object>();map.forEach((k,v)->result.put(String.valueOf(k),plain(v)));return result;}
    if(value instanceof List<?> list)return list.stream().map(ContentCatalogs::plain).toList();
    if(value instanceof Number n) return n.doubleValue()==Math.rint(n.doubleValue()) ? (Object)n.longValue() : n.doubleValue();
    return value;
  }
  private YamlConfiguration read(Path path) throws Exception { var yaml=new YamlConfiguration();yaml.load(path.toFile());return yaml; }
  public synchronized Map<String,Object> snapshot() throws Exception {
    Map<String,Object> catalogs=new LinkedHashMap<>(),status=new LinkedHashMap<>();
    var digest=MessageDigest.getInstance("SHA-256");
    for(Target t:TARGETS){
      var p=owner(t);status.put(t.kind(),p!=null);
      if(p==null){catalogs.put(t.kind(),Map.of());digest.update((t.kind()+":unavailable").getBytes(StandardCharsets.UTF_8));continue;}
      Path file=path(t,p);digest.update(t.kind().getBytes(StandardCharsets.UTF_8));digest.update(Files.readAllBytes(file));
      var yaml=read(file);Map<String,Object> items=new LinkedHashMap<>();
      if(t.kind().equals("marks")&&yaml.getBoolean("tags-defaults",true))try(var in=p.getResource("tags-default.yml")){
        if(in!=null){var defaults=new YamlConfiguration();defaults.load(new java.io.InputStreamReader(in,StandardCharsets.UTF_8));items.putAll(ContentSchema.map(plain(defaults.getConfigurationSection("tags"))));}
      }
      var section=yaml.getConfigurationSection(t.section());if(section!=null)items.putAll(ContentSchema.map(plain(section)));
      catalogs.put(t.kind(),items);
    }
    return Map.of("revision",HexFormat.of().formatHex(digest.digest()),"catalogs",catalogs,"available",status,"links",ContentSchema.links(catalogs));
  }
  private static void atomic(Path file,String content) throws Exception { CatalogFileTransaction.write(file,content); }
  public synchronized Map<String,Object> draft() throws Exception {
    Path file=storage.resolve("draft.json");return Files.exists(file)?ContentSchema.map(JsonUtils.parse(Files.readString(file))):Map.of();
  }
  public synchronized Map<String,Object> saveDraft(Map<String,Object> body,String actor) throws Exception {
    var existing=draft();
    if(!existing.isEmpty()&&!Objects.equals(body.get("draftVersion"),existing.get("draftVersion")))
      throw new ConcurrentModificationException("Черновик уже изменён другим редактором");
    var catalogs=ContentSchema.map(body.get("catalogs"));
    ContentSchema.requireShape(catalogs);
    var saved=new LinkedHashMap<String,Object>();saved.put("revision",body.get("revision"));saved.put("catalogs",catalogs);
    saved.put("draftVersion",UUID.randomUUID().toString());saved.put("actor",actor);saved.put("savedAt",Instant.now().toString());saved.put("errors",ContentSchema.validate(catalogs));
    atomic(storage.resolve("draft.json"),JsonUtils.toJson(saved));return saved;
  }
  public synchronized List<Map<String,Object>> history() throws Exception {
    if(!Files.isDirectory(storage.resolve("history")))return List.of();
    try(var paths=Files.list(storage.resolve("history"))){
      List<Map<String,Object>> result=new ArrayList<>();
      for(Path p:paths.filter(f->f.getFileName().toString().endsWith(".json")).sorted(Comparator.reverseOrder()).limit(50).toList()){
        var item=ContentSchema.map(JsonUtils.parse(Files.readString(p)));item.remove("catalogs");result.add(item);
      }return result;
    }
  }
  public synchronized Map<String,Object> restoreDraft(String id,String actor,Object version) throws Exception {
    if(!id.matches("[0-9]+-[a-f0-9-]{36}"))throw new IllegalArgumentException("Неверная версия");
    var old=ContentSchema.map(JsonUtils.parse(Files.readString(storage.resolve("history/"+id+".json"))));
    var body=new LinkedHashMap<String,Object>();body.put("revision",snapshot().get("revision"));body.put("catalogs",old.get("catalogs"));body.put("draftVersion",version);
    return saveDraft(body,actor);
  }
  public synchronized Map<String,Object> validate(Map<String,Object> body) throws Exception {
    var entries=ContentSchema.map(body.get("catalogs"));var errors=new ArrayList<>(ContentSchema.validate(entries));
    if(errors.isEmpty())for(Target target:TARGETS){
      var p=owner(target);if(p==null)continue;
      try{plugin.main().call(()->{
        try{p.getClass().getMethod("validateContentDefinitions",String.class,Map.class).invoke(p,target.kind(),ContentSchema.map(entries.getOrDefault(target.kind(),Map.of())));return null;}
        catch(Exception error){throw new CompletionException(error);}
      }).join();}catch(Exception error){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();errors.add(target.kind()+": "+cause.getMessage());}
    }
    return Map.of("errors",errors,"links",errors.isEmpty()?ContentSchema.links(entries):List.of());
  }

  public synchronized Map<String,Object> publish(Map<String,Object> body,String actor) throws Exception {
    var shared=draft();
    if(!shared.isEmpty()&&!Objects.equals(body.get("draftVersion"),shared.get("draftVersion")))throw new ConcurrentModificationException("Черновик изменён другим редактором");
    var before=snapshot();if(!Objects.equals(body.get("revision"),before.get("revision")))throw new ConcurrentModificationException("Каталоги изменились. Загрузите актуальную версию и перенесите правки.");
    var catalogs=ContentSchema.map(body.get("catalogs"));var errors=ContentSchema.validate(catalogs);
    if(!errors.isEmpty())throw new IllegalArgumentException(String.join("; ",errors));
    Map<Path,String> writes=new LinkedHashMap<>();Set<Plugin> owners=new LinkedHashSet<>();
    var old=ContentSchema.map(before.get("catalogs"));
    for(Target t:TARGETS){
      if(Objects.equals(old.get(t.kind()),catalogs.getOrDefault(t.kind(),Map.of())))continue;
      var p=owner(t);if(p==null)throw new IllegalArgumentException(t.owner()+" недоступен или требует обновления");
      plugin.main().call(()->{
        try {p.getClass().getMethod("validateContentDefinitions",String.class,Map.class).invoke(p,t.kind(),ContentSchema.map(catalogs.get(t.kind())));return null;}
        catch(Exception error){throw new CompletionException(error);}
      }).join();
      Path file=path(t,p);var yaml=read(file);
      yaml.set(t.section(),null);yaml.createSection(t.section(),ContentSchema.map(catalogs.get(t.kind())));
      if(t.kind().equals("marks"))yaml.set("tags-defaults",false);
      if(t.kind().equals("joinquit"))yaml.set("content-editor-owned",true);
      writes.put(file,yaml.saveToString());owners.add(p);
    }
    if(writes.isEmpty())return before;
    String id=System.currentTimeMillis()+"-"+UUID.randomUUID();
    atomic(storage.resolve("history/"+id+".json"),JsonUtils.toJson(Map.of("id",id,"actor",actor,"createdAt",Instant.now().toString(),"catalogs",old)));
    // Conflict is checked outside the rollback block so an external edit is never restored over.
    if(!Objects.equals(before.get("revision"),snapshot().get("revision")))throw new ConcurrentModificationException("Каталоги изменились во время публикации");
    CatalogFileTransaction.apply(writes,()->reload(owners));
    Files.deleteIfExists(storage.resolve("draft.json"));
    return snapshot();
  }
  private void reload(Set<Plugin> owners) throws Exception {
    for(var owner:owners){Object value=plugin.main().call(()->{
      try{return owner.getClass().getMethod("reloadContentDefinitions").invoke(owner);}
      catch(Exception error){throw new CompletionException(error);}
    }).join();if(value instanceof CompletionStage<?> stage)stage.toCompletableFuture().get(30,TimeUnit.SECONDS);}
  }
}
