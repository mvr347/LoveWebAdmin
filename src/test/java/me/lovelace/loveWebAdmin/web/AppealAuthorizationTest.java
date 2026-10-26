package me.lovelace.loveWebAdmin.web;
import me.lovelace.loveWebAdmin.*;
import me.lovelace.loveWebAdmin.models.*;
import me.lovelace.loveWebAdmin.web.handlers.*;
import me.lovelace.loveWebAdmin.utils.JsonUtils;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AppealAuthorizationTest {
    LoveWebAdmin plugin;int appeal;String key;
    @BeforeEach void setup()throws Exception {
        MockBukkit.mock();MockBukkit.createMockPlugin("LoveCore");MockBukkit.createMockPlugin("LoveEssential");
        var config=new YamlConfiguration();config.set("web.enabled",false);config.set("web.host","127.0.0.1");
        plugin=MockBukkit.loadWithConfig(LoveWebAdmin.class,config);
        var ban=plugin.getDatabaseManager().saveBan(new WebBan(0,"Ada123",UUID.randomUUID().toString(),null,"Staff","Причина","","[]",false,"ACTIVE",-1,100,-1));
        appeal=plugin.getDatabaseManager().createAppeal(ban.id(),ban.targetUuid(),ban.targetName(),"Проверьте решение");
        key=plugin.moderation().createAccess(appeal,null,false);
        plugin.getDatabaseManager().addAppealMessage(appeal,"Staff",true,"Конфиденциальная переписка");
    }
    @AfterEach void stop(){
        MockBukkit.unmock();
        if(plugin!=null){assertTrue(plugin.io().isShutdown());assertThrows(java.sql.SQLException.class,()->plugin.moderation().work("appeal",appeal));}
    }
    @Test void knownNicknameCannotReadConversation()throws Exception {
        var result=get("/"+appeal,Map.of("player","Ada123"),Map.of());assertEquals(403,result.status);assertFalse(result.body.toString().contains("Конфиденциальная"));
    }
    @Test void bearerAuthorTokenCanReadOwnConversation()throws Exception {
        var result=get("/"+appeal,Map.of(),Map.of("X-Appeal-Token",key));assertEquals(200,result.status);assertTrue(result.body.toString().contains("Конфиденциальная"));
    }
    @Test void tokenForAnotherAppealDoesNotAuthorize()throws Exception {
        String other=plugin.moderation().createAccess(appeal+100,null,false);
        assertEquals(403,get("/"+appeal,Map.of(),Map.of("X-Appeal-Token",other)).status);
    }
    @Test void nicknameLookupRequiresStaffSession()throws Exception {
        assertEquals(401,get("/",Map.of("player","Ada123"),Map.of()).status);
    }
    private Result get(String path,Map<String,String> params,Map<String,String> headers)throws Exception {
        var result=new Result();
        HttpServletRequest req=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},(p,m,a)->switch(m.getName()){
            case "getPathInfo"->path;case "getParameter"->params.get(a[0]);case "getHeader"->headers.get(a[0]);case "getRemoteAddr"->"127.0.0.1";default->null;
        });
        HttpServletResponse resp=(HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletResponse.class},(p,m,a)->switch(m.getName()){
            case "getWriter"->new PrintWriter(result.body);case "setStatus"->{result.status=(int)a[0];yield null;}case "isCommitted"->false;default->null;
        });
        new Exposed(plugin).get(req,resp);return result;
    }
    static class Result{int status=200;StringWriter body=new StringWriter();}
    static class Exposed extends ApiAppealsHandler {
        Exposed(LoveWebAdmin plugin){super(plugin);}void get(HttpServletRequest req,HttpServletResponse resp)throws IOException{super.doGet(req,resp);}
    }
}
