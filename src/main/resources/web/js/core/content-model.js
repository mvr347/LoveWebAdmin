export const CONTENT_KINDS = {contracts:"Контракты", stories:"Цепочки", achievements:"Ачивки", marks:"Пометки", joinquit:"Вход и выход"};
const forbidden = new Set(["__proto__", "constructor", "prototype"]);
export const cloneContent = value => structuredClone(value);
export const contentName = (id, d) => String(d?.["display-name"] || d?.name || d?.display || id).replace(/<[^>]*>/g, "");
export function newContent(kind) {
  const greeting={text:["Нужна твоя помощь."],options:[{text:"Я помогу",action:"ACCEPT_QUEST"},{text:"Позже",action:"CLOSE"}]};
  return cloneContent({
    contracts:{"display-name":"Новый контракт",description:"Выполните поручение",difficulty:"EASY",type:"REPEATING",enabled:true,condition:{type:"MineMaterial",material:"STONE",count:16},rewards:{money:100}},
    stories:{"display-name":"Новая история",enabled:true,"npc-key":"@contractor",repeatable:false,"initial-dialogue":"greeting",dialogues:{greeting},steps:[{type:"VISIT_NPC",count:1,"target-npc-key":"@contractor","target-npc-name":"Заказчик",description:"Обсудите поручение"}],rewards:{money:100}},
    achievements:{name:"Новое достижение",description:"Выполните условие",category:"play",rarity:"COMMON",hidden:false,trigger:{type:"manual"}},
    marks:{display:"<gold>Следопыт",rarity:"COMMON",lore:["Награда за достижение"],cost:0},
    joinquit:{name:"Следопыт",icon:"OAK_SIGN",join:"<green><player> возвращается из путешествия",quit:"<gray><player> отправляется в путь",achievement:""}
  }[kind]);
}
export function contentLinks(catalogs) {
  const links=[];
  for(const [kind, entries] of Object.entries(catalogs)) for(const [id,d] of Object.entries(entries)) {
    const node=`${kind}:${id}`;
    const fields=["marks","joinquit"].includes(kind)?["achievement"]:["contracts","stories"].includes(kind)?["requires-achievement"]:[];
    for(const field of fields) if(d[field]) links.push({from:`achievements:${d[field]}`,to:node});
    if(["contracts","stories"].includes(kind)&&d["completion-achievement"]) links.push({from:node,to:`achievements:${d["completion-achievement"]}`});
    if(kind==="achievements"&&d.trigger?.type==="all-of") for(const parent of d.trigger.ids||[]) links.push({from:`achievements:${parent}`,to:node});
  }
  return links;
}
export function contentErrors(catalogs) {
  const errors=[], nodes=new Set();
  for(const [kind,entries] of Object.entries(catalogs)) {
    if(!Object.hasOwn(CONTENT_KINDS,kind)){errors.push(`Неизвестный каталог: ${kind}`);continue;}
    for(const id of Object.keys(entries)) {
      nodes.add(`${kind}:${id}`);
      if(!/^[a-zA-Z0-9_-]{1,64}$/.test(id)||forbidden.has(id)) errors.push(`Недопустимый ID: ${id}`);
    }
  }
  const graph=new Map();
  for(const link of contentLinks(catalogs)) {
    if(!nodes.has(link.from))errors.push(`Отсутствует источник: ${link.from}`);
    if(!nodes.has(link.to))errors.push(`Отсутствует цель: ${link.to}`);
    if(!graph.has(link.from))graph.set(link.from,[]);graph.get(link.from).push(link.to);
  }
  const done=new Set(), visiting=new Set();
  function cycle(node) {
    if(done.has(node))return false;if(visiting.has(node))return true;visiting.add(node);
    if((graph.get(node)||[]).some(cycle))return true;
    visiting.delete(node);done.add(node);return false;
  }
  if([...nodes].some(cycle))errors.push("Цепочка содержит цикл зависимостей");
  return [...new Set(errors)];
}
export function assertContentDefinition(kind, value) {
  if(!value||typeof value!=='object'||Array.isArray(value))throw Error('Запись должна быть объектом');
  if(kind==='stories') {
    if(value.steps!==undefined&&!Array.isArray(value.steps))throw Error('Этапы steps должны быть списком');
    const node=n=>{
      if(!n||typeof n!=='object'||Array.isArray(n))throw Error('Узел диалога должен быть объектом');
      if(n.options!==undefined&&!Array.isArray(n.options))throw Error('Ответы options должны быть списком');
      if(n.text!==undefined&&!Array.isArray(n.text))throw Error('Реплики text должны быть списком');
    };
    if(value.dialogues!==undefined&&(!value.dialogues||typeof value.dialogues!=='object'||Array.isArray(value.dialogues)))throw Error('dialogues должен быть объектом узлов');
    for(const [id,n] of Object.entries(value.dialogues||{})){
      if(forbidden.has(id)||!/^[a-zA-Z0-9_-]{1,64}$/.test(id))throw Error('Недопустимый ID узла диалога');node(n);
    }
    for(const step of value.steps||[]){if(!step||typeof step!=='object'||Array.isArray(step))throw Error('Этап должен быть объектом');if(step.dialogue)node(step.dialogue);}
  }
}
export class ContentModel {
  constructor(snapshot) { this.catalogs=cloneContent(snapshot.catalogs);this.revision=snapshot.revision;this.draftVersion=snapshot.draftVersion;this.dirty=false; }
  item(kind,id) { return Object.hasOwn(this.catalogs[kind]||{},id)?this.catalogs[kind][id]:null; }
  add(kind,id,definition=newContent(kind)) {
    if(!Object.hasOwn(CONTENT_KINDS,kind)||!/^[a-zA-Z0-9_-]{1,64}$/.test(id)||forbidden.has(id))throw Error("ID: буквы, цифры, _ или -, до 64 символов");
    if(this.item(kind,id))throw Error("Такой ID уже существует");
    assertContentDefinition(kind,definition);
    this.catalogs[kind][id]=cloneContent(definition);this.dirty=true;
  }
  set(kind,id,path,value) {
    const keys=path.split(".");if(keys.some(k=>forbidden.has(k)))throw Error("Недопустимое поле");
    let object=this.item(kind,id);if(!object)throw Error("Запись удалена");
    for(let i=0;i<keys.length-1;i++) { if(object[keys[i]]===undefined)object[keys[i]]={};object=object[keys[i]]; }
    object[keys.at(-1)]=cloneContent(value);this.dirty=true;
  }
  remove(kind,id) { delete this.catalogs[kind][id];this.dirty=true; }
  connect(from,to) {
    const [a,id]=from.split(":"),[b,target]=to.split(":");
    if(!this.item(a,id)||!this.item(b,target))throw Error("Выберите существующие записи");
    if(["contracts","stories"].includes(a)&&b==="achievements")this.set(a,id,"completion-achievement",target);
    else if(a==="achievements"&&["marks","joinquit"].includes(b))this.set(b,target,"achievement",id);
    else if(a==="achievements"&&["contracts","stories"].includes(b))this.set(b,target,"requires-achievement",id);
    else if(a==="achievements"&&b==="achievements") {
      const trigger=this.item(b,target).trigger;
      if(trigger&&!['manual','all-of'].includes(trigger.type))throw Error("У целевой ачивки уже другое условие: создайте составную ачивку all-of");
      this.set(b,target,"trigger",{type:"all-of",ids:[...new Set([...(trigger?.ids||[]),id])]});
    } else throw Error("Поддерживаются связи: контракт → ачивка → пометка/вход/выход и ачивка → следующий контракт");
  }
  moveStep(kind,id,index,delta) {
    const steps=this.item(kind,id)?.steps;
    if(!steps||index+delta<0||index+delta>=steps.length)return;
    [steps[index],steps[index+delta]]=[steps[index+delta],steps[index]];this.dirty=true;
  }
  body() { return {revision:this.revision,draftVersion:this.draftVersion,catalogs:cloneContent(this.catalogs)}; }
}
