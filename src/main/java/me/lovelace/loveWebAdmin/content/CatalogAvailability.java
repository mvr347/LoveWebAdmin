package me.lovelace.loveWebAdmin.content;

import java.util.Map;

/** Read-only compatibility is distinct from an absent plugin or a failed startup. */
public final class CatalogAvailability {
  private CatalogAvailability() {}
  public static Map<String,Object> describe(String owner,String version,boolean installed,boolean enabled,boolean writable) {
    String state,message;
    if(!installed){state="missing";message="Плагин "+owner+" не установлен. Установите его для этого раздела.";}
    else if(!enabled){state="disabled";message=owner+" "+version+" выключен или не загрузился. Проверьте запуск плагина в журнале сервера.";}
    else if(!writable){state="read_only";message="Просмотр конфигурации "+owner+" "+version+". Эта сборка не поддерживает применение из веба. Обновите именно "+owner+" и перезапустите сервер.";}
    else {state="ready";message=owner+" "+version+": редактирование доступно.";}
    return Map.of("owner",owner,"version",version,"state",state,"message",message,"writable",writable);
  }
}
