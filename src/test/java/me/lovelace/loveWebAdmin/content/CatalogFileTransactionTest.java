package me.lovelace.loveWebAdmin.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CatalogFileTransactionTest {
  @TempDir Path directory;
  @Test void publishesAllFilesBeforeReload()throws Exception{
    Path a=directory.resolve("a.yml"),b=directory.resolve("b.yml");Files.writeString(a,"old-a");Files.writeString(b,"old-b");
    CatalogFileTransaction.apply(Map.of(a,"new-a",b,"new-b"),()->{assertEquals("new-a",Files.readString(a));assertEquals("new-b",Files.readString(b));});
    assertEquals("new-a",Files.readString(a));
    try(var files=Files.list(directory)){assertEquals(2,files.count());}
  }
  @Test void failedOwnerReloadRestoresEveryFileAndReloadsOldDefinitions()throws Exception{
    Path a=directory.resolve("a.yml"),b=directory.resolve("b.yml");Files.writeString(a,"old-a");Files.writeString(b,"old-b");
    var calls=new AtomicInteger();
    assertThrows(IllegalArgumentException.class,()->CatalogFileTransaction.apply(Map.of(a,"new-a",b,"new-b"),()->{
      if(calls.incrementAndGet()==1)throw new IllegalArgumentException("Native catalogue refused");
      assertEquals("old-a",Files.readString(a));assertEquals("old-b",Files.readString(b));
    }));
    assertEquals(2,calls.get());assertEquals("old-b",Files.readString(b));
  }
}
