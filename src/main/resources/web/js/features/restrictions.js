import { api, esc, hasPerm, openModal, closeModal, showToast } from './ui.js';
import { state } from '../state.js';

// Shared controls for live moderation. Requests use the central API's idempotency protection.
export async function renderRestrictions(container) {
  container.innerHTML = '<h3>Ограничения и оформление банов</h3><p>Предварительный бан действует пять минут. Если дело не оформлено, блокировка снимается автоматически.</p><div id="restrictions-actions"></div><div id="pending-bans"></div><div id="active-restrictions"></div>';
  const actions = container.querySelector('#restrictions-actions');
  for (const [action,label,permission] of [['ban','Предварительный бан','MANAGE_BANS'],['mute','Мут','MANAGE_MUTES'],['freeze','Проверка','FREEZE_PLAYERS'],['kick','Кик','KICK_PLAYERS']]) {
    if (!hasPerm(permission)) continue;
    const button = document.createElement('button'); button.type='button'; button.className='secondary'; button.textContent=label;
    button.addEventListener('click',()=>form(action,label,load)); actions.append(button);
  }
  let snapshot=null, offset=0;
  function drawCountdowns() {
    if(!snapshot || !container.isConnected)return;
    const now=Math.floor(Date.now()/1000)+offset;
    for(const element of container.querySelectorAll('[data-deadline]')) {
      const seconds=Math.max(0,Number(element.dataset.deadline)-now);
      element.textContent=seconds ? `${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,'0')}` : 'Срок оформления истёк';
    }
  }
  async function load() {
    try {
      const data=await api('GET','/api/moderation');if(!container.isConnected)return;
      snapshot=data;offset=data.serverTime-Math.floor(Date.now()/1000);
      const pending=container.querySelector('#pending-bans');
      pending.innerHTML='<h4>Ожидают оформления</h4>'+(data.pending.length ? data.pending.map(p=>`<div class="card"><strong>${esc(p.name)}</strong> · ${esc(p.actor)} · <span data-deadline="${Number(p.deadline)}"></span> <button type="button" class="secondary" data-finalize="${Number(p.id)}">Оформить №${Number(p.id)}</button></div>`).join('') : '<p>Нет предварительных банов.</p>');
      for(const button of pending.querySelectorAll('[data-finalize]'))button.addEventListener('click',()=>finalize(Number(button.dataset.finalize),load));
      const restrictions=container.querySelector('#active-restrictions');
      restrictions.innerHTML='<h4>Муты и проверки</h4>'+(data.restrictions.length ? data.restrictions.map((r,i)=>`<div class="card"><strong>${esc(r.name)}</strong> · ${r.kind==='MUTE'?'Мут':'Проверка'}<p>${esc(r.reason)}</p><span>${r.expires<0?'Бессрочно':new Date(r.expires*1000).toLocaleString('ru-RU')}</span> <button type="button" class="secondary" data-remove="${i}">Снять</button></div>`).join('') : '<p>Ограничений нет.</p>');
      for(const button of restrictions.querySelectorAll('[data-remove]'))button.addEventListener('click',()=>{
        const r=data.restrictions[Number(button.dataset.remove)];form(r.kind==='MUTE'?'unmute':'unfreeze','Снять ограничение',load,r.name);
      });drawCountdowns();
    } catch(error) {if(container.isConnected)showToast('Модерация',error.message,'error');}
  }
  await load();state.pollers.restrictions=setInterval(load,10000);state.pollers.restrictions_clock=setInterval(drawCountdowns,1000);
}
function form(action,title,refresh,name='') {
  const overlay = openModal(`<div class="modal-header"><h3>${esc(title)}</h3><button type="button" class="close-btn" data-modal-close="true">×</button></div><form id="restriction-form"><div class="modal-body"><label>Игрок<input name="player" value="${esc(name)}" pattern="[A-Za-z0-9_]{1,16}" maxlength="16" required></label>${action==='mute'?'<label>Срок<input name="duration" placeholder="30m, 2h, 7d, permanent" required></label>':''}${action!=='ban'?'<label>Причина<textarea name="reason" maxlength="1000" required></textarea></label>':'<p>Игрок будет отключён. Завершите оформление на сайте в течение пяти минут.</p>'}<p id="restriction-error" role="alert"></p></div><div class="modal-footer"><button type="button" class="secondary" data-modal-close="true">Отмена</button><button type="submit" class="primary">Применить</button></div></form>`);
  const form=document.getElementById('restriction-form');let busy=false;
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy)return;busy=true;const button=form.querySelector('[type=submit]');button.disabled=true;
    try {await api('POST','/api/moderation',{action,...Object.fromEntries(new FormData(form))});if(closeModal(overlay))await refresh();}
    catch(error){form.querySelector('#restriction-error').textContent=error.message;}
    finally {busy=false;button.disabled=false;}
  });
}

async function finalize(id,refresh) {
  const ban=await api('GET',`/api/bans/${id}`);
  const overlay = openModal(`<div class="modal-header"><h3>Оформить бан №${id}: ${esc(ban.targetName)}</h3><button type="button" class="close-btn" data-modal-close="true">×</button></div><form id="pending-ban-form"><div class="modal-body"><label>Причина<input name="ruleReason" maxlength="256" required></label><label>Описание<textarea name="description" maxlength="4000" required></textarea></label><label>Срок<input name="duration" placeholder="7d или permanent" required></label><label>Доказательства (ссылки по одной на строке)<textarea name="proofs" maxlength="8000"></textarea></label><p>Срок наказания считается с первого отключения.</p><p role="alert" id="pending-error"></p></div><div class="modal-footer"><button type="button" class="secondary" data-modal-close="true">Отмена</button><button type="submit" class="primary">Подтвердить бан</button></div></form>`);
  const form=document.getElementById('pending-ban-form');let busy=false;
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy)return;busy=true;const button=form.querySelector('[type=submit]');button.disabled=true;
    try {const data=Object.fromEntries(new FormData(form));await api('POST','/api/bans',{id,targetName:ban.targetName,ruleReason:data.ruleReason,description:data.description,durationSeconds:parseDuration(data.duration),proofUrls:data.proofs.split('\n').map(v=>v.trim()).filter(Boolean),isIpBan:true});if(closeModal(overlay))await refresh();}
    catch(error){form.querySelector('#pending-error').textContent=error.message;}
    finally {busy=false;button.disabled=false;}
  });
}
export function parseDuration(value) {
  if(['permanent','бессрочно','навсегда'].includes(value.trim().toLowerCase()))return -1;
  const match=/^([1-9][0-9]{0,8})([smhdw])$/.exec(value.trim().toLowerCase());
  if(!match)throw new Error('Срок: 30m, 2h, 7d или permanent');
  const seconds=Number(match[1])*({s:1,m:60,h:3600,d:86400,w:604800}[match[2]]);
  if(seconds<60||seconds>315360000)throw new Error('Срок: от минуты до десяти лет');return seconds;
}
