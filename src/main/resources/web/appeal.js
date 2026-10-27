(() => {
  'use strict';
  const browserStorage=window.LovePreview?.storage || window.localStorage;
  const $=id=>document.getElementById(id),  status=$('status');
  let id, access;
  const fragment=location.hash.slice(1).split(':');
  if(fragment.length===2&&/^\d+$/.test(fragment[0])&&/^[a-f0-9]{64}$/.test(fragment[1])){
    id=fragment[0];
    access=fragment[1];
    sessionStorage.setItem('lwa_appeal_'+id, access);
    history.replaceState(null, '', location.pathname+'?appeal='+id);
  }
  else{
    id=new URLSearchParams(location.search).get('appeal');
    if(id)access=sessionStorage.getItem('lwa_appeal_'+id);
  }
  async function api(method, path, body){
    const r=await fetch(path, {
      method, headers:{
        'Content-Type':'application/json', ...(access?{
          'X-Appeal-Token':access
        }
        :{
        })
      }, body:body===undefined?undefined:JSON.stringify(body)
    });
    const j=await r.json();
    if(!r.ok||!j.success)throw Error(j.error||'Запрос не выполнен');
    return j.data;
  }
  function fail(e){
    status.textContent=e.message;
    status.className='error';
  }
  async function refresh(){
    const a=await api('GET', '/api/appeals/'+id);
    $('create').hidden=true;
    $('thread').hidden=false;
    $('heading').textContent='Апелляция №'+a.id+' — '+a.playerName;
    $('state').textContent=({
      PENDING:'На рассмотрении', DECIDING:'Принимается решение', APPROVED:'Одобрена', REJECTED:'Отклонена'
    })[a.status]||a.status;
    $('verification').textContent=a.accountVerified?'Аккаунт подтверждён':'Аккаунт не подтверждён';
    $('messages').replaceChildren();
    for(const m of a.messages){
      const el=document.createElement('p');
      el.className='message';
      el.textContent=(m.isStaff?'Сотрудник: ':'Игрок: ')+m.authorName+' · '+new Date(m.createdAt*1000).toLocaleString('ru-RU')+'\n'+m.message;
      $('messages').append(el);
    }
    $('reply-form').hidden=a.status!=='PENDING';
    status.textContent='Обновлено: '+new Date().toLocaleTimeString('ru-RU');
  }
  $('appeal-form').onsubmit=async e=>{
    e.preventDefault();
    const button=e.submitter;
    button.disabled=true;
    try{
      const a=await api('POST', '/api/appeals', {
        playerName:$('nick').value, reason:$('reason').value
      });
      id=a.id;
      access=a.accessToken;
      sessionStorage.setItem('lwa_appeal_'+id, access);
      history.replaceState(null, '', location.pathname+'?appeal='+id);
      await refresh();
    }
    catch(e){
      fail(e);
    }
    finally{
      button.disabled=false;
    }
  };
  $('reply-form').onsubmit=async e=>{
    e.preventDefault();
    e.submitter.disabled=true;
    try{
      await api('POST', '/api/appeals/'+id+'/reply', {
        message:$('reply').value
      });
      $('reply').value='';
      await refresh();
    }
    catch(e){
      fail(e);
    }
    finally{
      e.submitter.disabled=false;
    }
  };
  $('refresh').onclick=()=>refresh().catch(fail);
  if(id&&access)refresh().catch(fail);
})();
