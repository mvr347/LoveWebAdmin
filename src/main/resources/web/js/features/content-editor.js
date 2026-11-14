import {api,esc,hasPerm,showToast,confirmAction} from "./ui.js";
import {CONTENT_KINDS,ContentModel,contentName,contentLinks,contentErrors,assertContentDefinition} from "../core/content-model.js";
import {state} from "../state.js";

const stepTypes=["VISIT_NPC","DELIVER_ITEM","VISIT_LOCATION","MINE_MATERIAL","CRAFT_ITEM","KILL_ENTITY","CATCH_FISH"];
const stepNames={VISIT_NPC:"Разговор с персонажем",DELIVER_ITEM:"Передача предметов",VISIT_LOCATION:"Посещение места",MINE_MATERIAL:"Добыча",CRAFT_ITEM:"Крафт",KILL_ENTITY:"Победа над существами",CATCH_FISH:"Рыбалка"};
export async function renderContentEditor() {
  const area=document.getElementById("content-area");
  const generation=state.requestGeneration;
  area.innerHTML='<p class="muted">Загрузка игровых каталогов…</p>';
  let live,draft;
  try { [live,draft]=await Promise.all([api("GET","/api/content/catalogs"),api("GET","/api/content/draft")]); }
  catch(error){if(generation===state.requestGeneration)area.textContent=error.message;return;}
  if(generation!==state.requestGeneration)return;
  const actor=state.me?.username;
  const cached=state.contentEditorSession?.actor===actor&&state.contentEditorSession.model.dirty?state.contentEditorSession:null;
  let model=cached?.model||new ContentModel(draft.catalogs?draft:live),kind=cached?.kind||"contracts",selected=cached?.selected||Object.keys(model.catalogs[kind]||{})[0],errors=[],busy=false;
  if(!state.contentEditorUnloadGuard){
    state.contentEditorUnloadGuard=true;
    window.addEventListener('beforeunload',event=>{const current=state.contentEditorSession;if(current?.actor===state.me?.username&&current?.model.dirty){event.preventDefault();event.returnValue='';}});
  }
  const manage=hasPerm("MANAGE_CONTENT"), publish=hasPerm("PUBLISH_CONTENT");
  area.innerHTML=`<div class="view-header"><div><h2>ИГРОВОЕ СОДЕРЖИМОЕ</h2><p>Контракты, достижения и открываемое оформление${location.pathname.startsWith("/preview/")?" · Превью: синтетические каталоги, сервер не изменяется":""}</p></div></div>
    <div class="content-toolbar"><button class="secondary" id="content-refresh">Загрузить с сервера</button>${manage?'<button class="secondary" id="content-save">Сохранить черновик</button><button class="secondary" id="content-check">Проверить</button>':''}${publish?'<button class="primary" id="content-publish">Опубликовать</button>':''}<button class="secondary" id="content-history">История</button><span id="content-status" role="status"></span></div>
    <div class="sub-tabs-bar" id="content-kinds"></div><div id="content-errors" role="alert"></div>
    <div class="content-workspace"><aside class="content-catalog"><input id="content-search" type="search" placeholder="Поиск по названию или ID" aria-label="Поиск содержимого"><div id="content-list"></div>${manage?'<form id="content-create"><input id="content-id" required maxlength="64" pattern="[a-zA-Z0-9_-]+" placeholder="ID новой записи" aria-label="ID новой записи"><button class="primary">Создать</button></form>':''}</aside>
    <section id="content-form"></section></div><section class="content-chain"><h3>Схема связей</h3><p>Контракт → ачивка → пометка / оформление. Нажмите на карточку, чтобы открыть её.</p><div id="content-graph"></div>${manage?'<form id="content-connect"><select id="content-from" aria-label="Источник связи"></select><span>→</span><select id="content-to" aria-label="Цель связи"></select><button class="secondary">Связать</button></form>':''}</section>`;
  const $=selector=>area.querySelector(selector);
  async function request(...args) {
    const result=await api(...args);
    if(generation!==state.requestGeneration)throw Object.assign(new Error('Представление сменилось'),{stale:true});
    return result;
  }
  function status(message="") {if(!$("#content-status")||generation!==state.requestGeneration)return;$("#content-status").textContent=message||(model.dirty?"Есть несохранённые изменения":draft.catalogs?`Черновик: ${draft.actor||"редактор"}`:"Версия сервера");}
  function checkLocal(){errors=contentErrors(model.catalogs);$("#content-errors").innerHTML=errors.map(e=>`<p class="error">${esc(e)}</p>`).join("");}
  function options(values,current){return values.map(value=>`<option value="${esc(value)}" ${value===current?"selected":""}>${esc(stepNames[value]||value)}</option>`).join("");}
  function field(path,label,type="text",values) {
    let value=model.item(kind,selected);for(const key of path.split("."))value=value?.[key];
    const attrs=`data-field="${esc(path)}" ${!manage?'disabled':''}`;
    let control=values?`<select ${attrs}>${options(values,String(value??values[0]))}</select>`:type==="boolean"?`<input type="checkbox" ${attrs} ${value?'checked':''}>`:type==="lines"||type==="textarea"?`<textarea rows="3" ${attrs} data-value-type="${type}">${esc(type==="lines"?(Array.isArray(value)?value:[]).join("\n"):value||"")}</textarea>`:`<input type="${['rewards.money','cost'].includes(path)?'text':type}" ${['rewards.money','cost'].includes(path)?'data-money="true"':''} ${attrs} value="${esc(value??(type==="number"?(path.endsWith('.count')||path==='trigger.at-least'?1:path==='expiration-minutes'?1440:path.endsWith('.radius')?4:0):""))}" ${type==="number"?'step="any"':''}>`;
    return `<label class="content-field"><span>${esc(label)}</span>${control}</label>`;
  }
  function nodeForm(node,path,title) {
    return `<fieldset class="content-node"><legend>${esc(title)}</legend>${field(path+".text","Реплики (по одной на строку)","lines")}${(node.options||[]).map((option,i)=>`<div class="content-option">${field(`${path}.options.${i}.text`,"Ответ")}${field(`${path}.options.${i}.action`,"Действие","text",["CLOSE","NEXT_NODE","ACCEPT_QUEST","COMPLETE_STEP"])}${field(`${path}.options.${i}.next-node`,"Следующий узел")}${manage?`<button type="button" class="secondary" data-remove-option="${esc(path)}" data-index="${i}">Удалить ответ</button>`:''}</div>`).join("")}${manage?`<button type="button" class="secondary" data-add-option="${esc(path)}">Добавить ответ</button>`:''}</fieldset>`;
  }
  function drawForm() {
    const d=model.item(kind,selected);
    if(!d){$("#content-form").innerHTML='<p class="muted">Выберите запись или создайте новую.</p>';return;}
    const achievementIds=["",...Object.keys(model.catalogs.achievements||{})];
    let fields=field(kind==="marks"?"display":kind==="contracts"||kind==="stories"?"display-name":"name","Название / отображение");
    if(["contracts","stories","achievements"].includes(kind))fields+=field("description","Описание","textarea");
    if(["contracts","stories"].includes(kind))fields+=field("enabled","Включён","boolean")+field("rewards.money","Денежная награда (медные единицы)","number")+field("requires-achievement","Открывается после ачивки","text",achievementIds)+field("completion-achievement","Ачивка за завершение","text",achievementIds);
    if(kind==="contracts") {
      fields+=field("difficulty","Сложность","text",["STARTER","EASY","MEDIUM","HARD"])+field("type","Повторяемость","text",["REPEATING","ONE_TIME"])+field("condition.type","Цель","text",["MineMaterial","CraftItem","KillEntity","CatchFish"])+field("condition.count","Количество","number");
      if(["MineMaterial","CraftItem"].includes(d.condition?.type))fields+=field("condition.material","Материал");
      if(d.condition?.type==="KillEntity")fields+=field("condition.entity-type","Тип существа");
      fields+=field("expiration-minutes","Срок выполнения, минуты","number");
    }
    if(kind==="achievements") {
      fields+=field("category","Категория")+field("rarity","Редкость","text",["COMMON","RARE","EPIC","LEGENDARY"])+field("hidden","Скрыта до получения","boolean")+field("hint","Подсказка")+field("reward-text","Текст награды")+field("trigger.type","Условие","text",["manual","counter","set","all-of","unlocked-count"]);
      if(["counter","set"].includes(d.trigger?.type))fields+=field("trigger.key","Ключ метрики / набора");
      if(["counter","unlocked-count"].includes(d.trigger?.type))fields+=field("trigger.at-least","Порог","number");
      if(d.trigger?.type==="all-of")fields+=field("trigger.ids","Предыдущие ачивки (ID по строкам)","lines");
      if(d.trigger?.type==="set")fields+=field("trigger.values","Значения набора (по строкам)","lines");
    }
    if(kind==="marks")fields+=field("rarity","Редкость","text",["COMMON","RARE","EPIC","LEGENDARY"])+field("lore","Описание","lines")+field("cost","Цена (0 — бесплатно)","number")+field("achievement","Требуемая ачивка","text",achievementIds)+field("unlock-hint","Как открыть")+field("permission","Дополнительное право");
    if(kind==="joinquit")fields+=field("join","Сообщение входа","textarea")+field("quit","Сообщение выхода","textarea")+field("achievement","Требуемая ачивка","text",achievementIds)+field("permission","Дополнительное право")+field("icon","Материал иконки");
    if(kind==="stories") {
      fields+=field("npc-key","Заказчик: ключ существующего NPC или @actor:имя")+field("repeatable","Повторяемая цепочка","boolean")+field("cooldown-minutes","Пауза между повторениями, минуты","number")+field("initial-dialogue","Начальный узел диалога");
      fields+=`<h3>Этапы по порядку</h3>${(d.steps||[]).map((step,i)=>`<fieldset class="content-step"><legend>Этап ${i+1}</legend>${field(`steps.${i}.type`,"Действие","text",stepTypes)}${field(`steps.${i}.description`,"Задача игроку","textarea")}${field(`steps.${i}.count`,"Количество","number")}${["VISIT_NPC","DELIVER_ITEM"].includes(step.type)?field(`steps.${i}.target-npc-key`,"NPC-получатель")+field(`steps.${i}.target-npc-name`,"Имя для игрока"):''}${["DELIVER_ITEM","MINE_MATERIAL","CRAFT_ITEM"].includes(step.type)?field(`steps.${i}.material`,"Материал"):''}${step.type==="KILL_ENTITY"?field(`steps.${i}.entity-type`,"Тип существа"):''}${step.location||step.type==="VISIT_LOCATION"?field(`steps.${i}.location.world`,"Мир")+['x','y','z','radius'].map(k=>field(`steps.${i}.location.${k}`,k,"number")).join(''):''}${step.dialogue?nodeForm(step.dialogue,`steps.${i}.dialogue`,"Разговор этапа"):''}${manage?`<div class="content-toolbar"><button type="button" class="secondary" data-step-up="${i}" ${i===0?'disabled':''}>Выше</button><button type="button" class="secondary" data-step-down="${i}" ${i===d.steps.length-1?'disabled':''}>Ниже</button><button type="button" class="secondary" data-step-delete="${i}">Удалить этап</button>${!step.dialogue?`<button type="button" class="secondary" data-step-dialog="${i}">Добавить разговор</button>`:''}${!step.location?`<button type="button" class="secondary" data-step-location="${i}">Указать область</button>`:''}</div>`:''}</fieldset>`).join('')}${manage?'<button type="button" class="secondary" id="content-add-step">Добавить этап</button>':''}`;
      fields+=`<h3>Узлы диалога</h3>${Object.entries(d.dialogues||{}).map(([id,node])=>nodeForm(node,`dialogues.${id}`,id)).join('')}${manage?'<div class="content-toolbar"><input id="content-node-id" placeholder="ID узла" aria-label="ID узла диалога"><button type="button" class="secondary" id="content-add-node">Добавить узел</button></div>':''}`;
    }
    $("#content-form").innerHTML=`<div class="content-title"><h3>${esc(selected)}</h3>${manage?'<button type="button" class="secondary" id="content-duplicate">Дублировать</button><button type="button" class="danger" id="content-delete">Удалить</button>':''}</div><div class="content-fields">${fields}</div><section class="content-preview"><h4>Предпросмотр текста</h4><div id="content-preview-text"></div></section><details><summary>Все свойства записи (JSON)</summary><textarea id="content-json" rows="12" spellcheck="false" ${!manage?'readonly':''}>${esc(JSON.stringify(d,null,2))}</textarea>${manage?'<button class="secondary" type="button" id="content-apply-json">Применить свойства</button>':''}</details>`;
    $("#content-form").querySelectorAll('[data-field]').forEach(input=>input.addEventListener(input.tagName==='SELECT'||input.type==='checkbox'?'change':'input',()=>{
      try {const value=input.type==='checkbox'?input.checked:input.dataset.money?(Number.isFinite(Number(input.value))?Number(input.value):input.value):input.type==='number'?Number(input.value):input.dataset.valueType==='lines'?input.value.split('\n').map(x=>x.trim()).filter(Boolean):input.value;
        model.set(kind,selected,input.dataset.field,value);status();checkLocal();drawGraph();preview();
        if(input.tagName==='SELECT'&&['condition.type','trigger.type'].includes(input.dataset.field)||input.dataset.field.endsWith('.type')&&input.tagName==='SELECT')drawForm();
      }catch(error){showToast(error.message,'error');}
    }));
    $("#content-delete")?.addEventListener('click',()=>confirmAction('Удалить запись?',`Ссылки на ${selected} останутся видны как ошибки до исправления.`,()=>{model.remove(kind,selected);selected=Object.keys(model.catalogs[kind])[0];render();}));
    $("#content-duplicate")?.addEventListener('click',()=>{let id=selected+'_copy';while(model.item(kind,id))id+='_copy';try{model.add(kind,id,d);selected=id;render();}catch(error){showToast(error.message,'error');}});
    $("#content-apply-json")?.addEventListener('click',()=>{try{const value=JSON.parse($("#content-json").value);assertContentDefinition(kind,value);model.catalogs[kind][selected]=value;model.dirty=true;render();}catch(error){showToast(error.message,'error');}});
    $("#content-add-step")?.addEventListener('click',()=>{d.steps.push({type:'VISIT_NPC','target-npc-key':'@contractor',count:1,description:'Поговорите с заказчиком'});model.dirty=true;render();});
    $("#content-add-node")?.addEventListener('click',()=>{const id=$("#content-node-id").value;if(!/^[a-zA-Z0-9_-]{1,64}$/.test(id)||['__proto__','constructor','prototype'].includes(id)||Object.hasOwn(d.dialogues||{},id)){showToast('Нужен новый корректный ID узла','error');return;}model.set(kind,selected,`dialogues.${id}`,{text:['Новая реплика'],options:[{text:'До встречи',action:'CLOSE'}]});render();});
    for(const [attribute,action] of Object.entries({stepUp:i=>model.moveStep(kind,selected,i,-1),stepDown:i=>model.moveStep(kind,selected,i,1),stepDelete:i=>{d.steps.splice(i,1);},stepDialog:i=>{d.steps[i].dialogue={text:['Обсудим поручение.'],options:[{text:'Продолжить',action:'COMPLETE_STEP'}]};},stepLocation:i=>{d.steps[i].location={world:'world',x:0,y:64,z:0,radius:4};}})) {
      $("#content-form").querySelectorAll(`[data-${attribute.replace(/[A-Z]/g,m=>'-'+m.toLowerCase())}]`).forEach(button=>button.addEventListener('click',()=>{action(Number(button.dataset[attribute]));model.dirty=true;render();}));
    }
    $("#content-form").querySelectorAll('[data-add-option]').forEach(button=>button.addEventListener('click',()=>{let node=d;for(const key of button.dataset.addOption.split('.'))node=node[key];if((node.options||[]).length>=5){showToast('Максимум 5 ответов','error');return;}(node.options||=[]).push({text:'Новый ответ',action:'CLOSE'});model.dirty=true;render();}));
    $("#content-form").querySelectorAll('[data-remove-option]').forEach(button=>button.addEventListener('click',()=>{let node=d;for(const key of button.dataset.removeOption.split('.'))node=node[key];node.options.splice(Number(button.dataset.index),1);model.dirty=true;render();}));
    preview();
  }
  function preview() {
    const d=model.item(kind,selected);if(!d)return;
    const lines=kind==='joinquit'?[d.join,d.quit]:[d['display-name']||d.name||d.display,d.description,...(d.lore||[])];
    $("#content-preview-text").replaceChildren(...lines.filter(Boolean).map(text=>{const p=document.createElement('p');p.textContent=String(text).replaceAll('<player>','Игрок').replace(/<[^>]*>/g,'');return p;}));
  }
  function drawList() {
    const query=$("#content-search").value.toLowerCase();
    $("#content-list").innerHTML=Object.entries(model.catalogs[kind]||{}).filter(([id,d])=>(id+' '+contentName(id,d)).toLowerCase().includes(query)).map(([id,d])=>`<button type="button" class="content-record ${id===selected?'selected':''}" data-id="${esc(id)}"><strong>${esc(contentName(id,d))}</strong><small>${esc(id)}</small></button>`).join('')||'<p class="muted">Записей нет</p>';
    $("#content-list").querySelectorAll('[data-id]').forEach(button=>button.addEventListener('click',()=>{selected=button.dataset.id;render();}));
    if(!live.available[kind]&&!Object.keys(model.catalogs[kind]||{}).length)$("#content-list").innerHTML='<p class="muted">Нужна обновлённая версия плагина-владельца этого каталога. Проверьте установку и включение плагина.</p>';
    if($("#content-create"))$("#content-create").hidden=!live.available[kind];
  }
  function drawGraph() {
    const links=contentLinks(model.catalogs),all=Object.entries(model.catalogs).flatMap(([k,items])=>Object.entries(items).map(([id,d])=>({key:`${k}:${id}`,kind:k,id,label:contentName(id,d)})));
    const key=`${kind}:${selected}`;const nearby=new Set([key]);let changed=true;
    while(changed){changed=false;for(const link of links)if(nearby.has(link.from)||nearby.has(link.to)){if(!nearby.has(link.from)||!nearby.has(link.to))changed=true;nearby.add(link.from);nearby.add(link.to);}}
    const nodes=all.filter(n=>nearby.has(n.key)).slice(0,100), positions=new Map(),columns={contracts:0,stories:0,achievements:1,marks:2,joinquit:2},rows=[0,0,0];
    for(const node of nodes){const column=columns[node.kind];positions.set(node.key,{x:20+column*310,y:20+rows[column]++*85});}
    const height=Math.max(130,...rows.map(n=>n*85+25));
    $("#content-graph").innerHTML=`<svg role="img" aria-label="Связи выбранной цепочки" viewBox="0 0 940 ${height}" style="min-width:700px;height:${height}px"><defs><marker id="content-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor"/></marker></defs>${links.filter(l=>positions.has(l.from)&&positions.has(l.to)).map(l=>{const a=positions.get(l.from),b=positions.get(l.to);return `<path d="M${a.x+265},${a.y+28} C${a.x+290},${a.y+28} ${b.x-25},${b.y+28} ${b.x},${b.y+28}" class="content-edge" marker-end="url(#content-arrow)"/>`;}).join('')}${nodes.map(n=>{const p=positions.get(n.key);return `<g data-node="${esc(n.key)}" tabindex="0" role="button" aria-label="${esc(n.label)}"><rect x="${p.x}" y="${p.y}" width="265" height="60" rx="8" class="${n.key===key?'selected':''}"/><text x="${p.x+12}" y="${p.y+22}">${esc(n.label.slice(0,29))}</text><text x="${p.x+12}" y="${p.y+43}" class="content-graph-kind">${esc(CONTENT_KINDS[n.kind])}</text></g>`;}).join('')}</svg>`;
    $("#content-graph").querySelectorAll('[data-node]').forEach(node=>{const open=()=>{[kind,selected]=node.dataset.node.split(':');render();};node.addEventListener('click',open);node.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();open();}});});
    if(manage){const opts=all.map(n=>`<option value="${esc(n.key)}">${esc(CONTENT_KINDS[n.kind]+': '+n.label+' ['+n.id+']')}</option>`).join('');const previous=$("#content-to").value;$("#content-from").innerHTML=opts;$("#content-to").innerHTML=opts;$("#content-from").value=key;if(all.some(n=>n.key===previous))$("#content-to").value=previous;}
  }
  function render() {
    if(generation!==state.requestGeneration)return;
    state.contentEditorSession={actor,model,kind,selected};
    $("#content-kinds").innerHTML=Object.entries(CONTENT_KINDS).map(([k,label])=>`<button type="button" class="sub-tab-btn ${kind===k?'active':''}" data-kind="${k}">${esc(label)} ${live.available[k]?'':'(недоступно)'}</button>`).join('');
    $("#content-kinds").querySelectorAll('[data-kind]').forEach(button=>button.addEventListener('click',()=>{kind=button.dataset.kind;selected=Object.keys(model.catalogs[kind]||{})[0];render();}));
    drawList();drawForm();drawGraph();checkLocal();status();
  }
  async function action(work){if(busy)return;busy=true;try{await work();}catch(error){if(!error.stale&&generation===state.requestGeneration){status(error.message);showToast(error.message,'error');}}finally{busy=false;}}
  $("#content-search").addEventListener('input',drawList);
  $("#content-create")?.addEventListener('submit',event=>{event.preventDefault();try{const id=$("#content-id").value.trim();model.add(kind,id);selected=id;$("#content-id").value='';render();}catch(error){showToast(error.message,'error');}});
  $("#content-connect")?.addEventListener('submit',event=>{event.preventDefault();try{model.connect($("#content-from").value,$("#content-to").value);render();}catch(error){showToast(error.message,'error');}});
  $("#content-save")?.addEventListener('click',()=>action(async()=>{draft=await request('POST','/api/content/draft',model.body());model.draftVersion=draft.draftVersion;model.dirty=false;status('Черновик сохранён');}));
  $("#content-check")?.addEventListener('click',()=>action(async()=>{const result=await request('POST','/api/content/validate',model.body());$("#content-errors").innerHTML=result.errors.map(e=>`<p class="error">${esc(e)}</p>`).join('');status(result.errors.length?'Исправьте ошибки':'Связи и определения корректны');}));
  $("#content-publish")?.addEventListener('click',()=>{checkLocal();if(errors.length){status('Исправьте связи перед публикацией');return;}confirmAction('Опубликовать содержимое?','Определения будут применены на сервере. Прогресс игроков не сбрасывается.',()=>action(async()=>{live=await request('POST','/api/content/publish',model.body());draft={};model=new ContentModel(live);render();status('Опубликовано');}),'ОПУБЛИКОВАТЬ',false);});
  $("#content-refresh").addEventListener('click',()=>confirmAction('Загрузить актуальные каталоги?','Локальные правки будут заменены. Сохранённый черновик останется в панели.',()=>action(async()=>{live=await request('GET','/api/content/catalogs');const version=model.draftVersion;model=new ContentModel(live);model.draftVersion=version;render();}),'ЗАГРУЗИТЬ',false));
  $("#content-history").addEventListener('click',()=>action(async()=>{const history=await request('GET','/api/content/history');$("#content-form").innerHTML=`<h3>История публикаций</h3><p>Восстанавливается содержимое до выбранной публикации — в черновик, без немедленного применения.</p>${history.map(h=>`<div class="content-history-row"><span>${esc(h.createdAt)} · ${esc(h.actor)}</span>${manage?`<button type="button" class="secondary" data-history="${esc(h.id)}">Восстановить в черновик</button>`:''}</div>`).join('')||'<p>Публикаций пока нет</p>'}`;$("#content-form").querySelectorAll('[data-history]').forEach(button=>button.addEventListener('click',()=>confirmAction('Восстановить версию?','Текущий черновик будет заменён выбранной версией.',()=>action(async()=>{draft=await request('POST','/api/content/restore',{id:button.dataset.history,draftVersion:model.draftVersion});model=new ContentModel(draft);selected=Object.keys(model.catalogs[kind]||{})[0];render();}),'ВОССТАНОВИТЬ',false)));}));
  render();
}
