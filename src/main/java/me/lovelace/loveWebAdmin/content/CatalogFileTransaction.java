package me.lovelace.loveWebAdmin.content;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Each replacement is atomic; a failed apply restores all original files and reloads the owners. */
public final class CatalogFileTransaction {
  @FunctionalInterface public interface Reload { void run() throws Exception; }
  private CatalogFileTransaction() {}
  public static void write(Path file,String text)throws Exception {
    Files.createDirectories(file.getParent());var temp=Files.createTempFile(file.getParent(),".content-",".tmp");
    try{Files.writeString(temp,text,StandardCharsets.UTF_8);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    finally{Files.deleteIfExists(temp);}
  }
  public static void apply(Map<Path,String> writes,Reload reload)throws Exception {
    var originals=new LinkedHashMap<Path,String>();
    for(var file:writes.keySet())originals.put(file,Files.readString(file));
    try {
      for(var entry:writes.entrySet())write(entry.getKey(),entry.getValue());
      reload.run();
    }catch(Exception error){
      for(var entry:originals.entrySet())try{write(entry.getKey(),entry.getValue());}catch(Exception rollback){error.addSuppressed(rollback);}
      try{reload.run();}catch(Exception rollback){error.addSuppressed(rollback);}
      throw error;
    }
  }
}
