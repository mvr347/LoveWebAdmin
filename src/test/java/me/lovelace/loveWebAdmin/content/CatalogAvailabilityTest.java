package me.lovelace.loveWebAdmin.content;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CatalogAvailabilityTest {
  @Test void missingDisabledAndReadOnlyHaveDifferentActionableReasons(){
    assertEquals("missing",CatalogAvailability.describe("LoveContracts","",false,false,false).get("state"));
    assertEquals("disabled",CatalogAvailability.describe("LoveEssential","2.0",true,false,false).get("state"));
    var old=CatalogAvailability.describe("LoveSubnames","1.0",true,true,false);
    assertEquals("read_only",old.get("state"));assertEquals(false,old.get("writable"));
    assertTrue(old.get("message").toString().contains("LoveSubnames 1.0"));
    assertEquals("ready",CatalogAvailability.describe("LoveEssential","2.0",true,true,true).get("state"));
  }
}
