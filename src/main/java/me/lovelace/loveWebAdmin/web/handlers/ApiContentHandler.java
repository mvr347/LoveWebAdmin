package me.lovelace.loveWebAdmin.web.handlers;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import me.lovelace.loveWebAdmin.LoveWebAdmin;
import me.lovelace.loveWebAdmin.content.*;
import me.lovelace.loveWebAdmin.models.Permission;

public final class ApiContentHandler extends ApiHandlerSupport {
  private final ContentCatalogs catalogs;
  public ApiContentHandler(LoveWebAdmin plugin){super(plugin);catalogs=new ContentCatalogs(plugin);}
  @FunctionalInterface private interface Call { Object run() throws Exception; }
  private void answer(HttpServletResponse response,Call call)throws IOException {
    try{sendSuccess(response,call.run());}
    catch(ConcurrentModificationException error){sendError(response,409,error.getMessage());}
    catch(IllegalArgumentException error){sendError(response,400,error.getMessage());}
    catch(Exception error){
      Throwable cause=error;
      while(cause.getCause()!=null && (cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException || cause instanceof java.lang.reflect.InvocationTargetException))cause=cause.getCause();
      if(cause instanceof IllegalArgumentException){sendError(response,400,cause.getMessage());return;}
      plugin.getLogger().warning("Content editor: "+error);sendError(response,503,"Не удалось применить содержимое. Проверьте доступность и версии плагинов.");}
  }
  @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException {
    if(requirePermission(request,response,Permission.VIEW_CONTENT).isEmpty())return;
    String path=Objects.toString(request.getPathInfo(),"/");
    switch(path){
      case "/","/catalogs" -> answer(response,catalogs::snapshot);
      case "/draft" -> answer(response,catalogs::draft);
      case "/history" -> answer(response,catalogs::history);
      default -> sendError(response,404,"Не найдено");
    }
  }
  @Override protected void doPost(HttpServletRequest request,HttpServletResponse response)throws IOException {
    String path=Objects.toString(request.getPathInfo(),"");
    Permission permission=path.equals("/publish")?Permission.PUBLISH_CONTENT:Permission.MANAGE_CONTENT;
    var session=requirePermission(request,response,permission);if(session.isEmpty())return;
    var body=readJsonBody(request);String actor=session.get().adminUsername();
    switch(path){
      case "/validate" -> answer(response,()->catalogs.validate(body));
      case "/draft" -> answer(response,()->catalogs.saveDraft(body,actor));
      case "/restore" -> answer(response,()->catalogs.restoreDraft(String.valueOf(body.get("id")),actor,body.get("draftVersion")));
      case "/publish" -> answer(response,()->{
        var result=catalogs.publish(body,actor);
        plugin.getLogManager().logWebAction(actor,"Опубликованы игровые каталоги: "+result.get("revision"));
        return result;
      });
      default -> sendError(response,404,"Не найдено");
    }
  }
}
