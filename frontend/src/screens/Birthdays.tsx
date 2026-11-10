import { useEffect, useState } from 'react';
import { api, date } from '../api';
import { perform, useRemote } from './common';
import type { ScreenProps } from './common';

type Birthday={
  customerId:string; name:string;phone:string|null;
  whatsappOptIn:boolean; birthday:string;daysUntil:number;
};
type BirthdayStatus={showModal:boolean;today:string;birthdays:Birthday[]};
type Notify=(text:string,error?:boolean)=>void;

function whatsappUrl(entry:Birthday){
  const raw=String(entry.phone||'').replace(/[^0-9]/g,'');
  const phone=raw.startsWith('55')?raw:'55'+raw;
  return 'https://wa.me/'+phone+'?text='+encodeURIComponent(
    'Olá, '+entry.name+'! Feliz aniversário! 🎉 Que seu dia seja especial e cheio de alegria. São os votos de toda a nossa equipe da ótica!'
  );
}

async function contact(entry:Birthday,notify:Notify,after:()=>void){
  if(!entry.phone||!entry.whatsappOptIn){
    notify('Confirme a preferência de WhatsApp do cliente antes de contatá-lo.',true);
    return;
  }
  window.open(whatsappUrl(entry),'_blank','noopener,noreferrer');
  await perform(()=>api('/birthdays/'+entry.customerId+'/whatsapp-opened',{
    method:'POST',
  }),notify,after);
}

export function BirthdayWelcome({allowed,notify,onOpen}:{
  allowed:boolean;notify:Notify;onOpen:()=>void;
}){
  const [status,setStatus]=useState<BirthdayStatus|null>(null);
  const [dismissed,setDismissed]=useState(false);
  useEffect(()=>{
    if(!allowed){setStatus(null);return;}
    let alive=true;
    void api<BirthdayStatus>('/birthdays/status')
      .then(v=>{if(alive)setStatus(v);})
      .catch(()=>{if(alive)setStatus(null);});
    return ()=>{alive=false;};
  },[allowed]);
  if(!allowed||dismissed||!status?.showModal)return null;
  const dismiss=async(showList:boolean)=>{
    await perform(()=>api('/birthdays/dismiss',{method:'POST'}),notify,()=>{
      setDismissed(true);if(showList)onOpen();
    });
  };
  return <div className="calendar-modal-backdrop">
    <div className="calendar-modal" role="dialog" aria-modal="true"
      aria-labelledby="birthday-welcome-title">
      <span className="eyebrow">Datas especiais</span>
      <h2 id="birthday-welcome-title">Aniversariantes de hoje</h2>
      <p>Estes clientes estão fazendo aniversário. Você pode preparar uma saudação quando houver autorização para WhatsApp.</p>
      <div className="calendar-modal-list">{status.birthdays.map(b=>
        <div className="calendar-holiday-row" key={b.customerId}>
          <div><strong>{b.name}</strong><small>{b.phone||'Sem telefone cadastrado'}</small></div>
          <button type="button" className="outline-action"
            disabled={!b.whatsappOptIn||!b.phone}
            onClick={()=>void contact(b,notify,()=>{})}>Preparar saudação</button>
        </div>)}</div>
      <div className="calendar-actions">
        <button className="outline-action" onClick={()=>void dismiss(false)}>Fechar por hoje</button>
        <button className="primary-action" onClick={()=>void dismiss(true)}>Ver aniversariantes</button>
      </div>
    </div>
  </div>;
}

export function Birthdays({notify,can}:ScreenProps){
  const [days,setDays]=useState(30);
  const results=useRemote<Birthday[]>('/birthdays?days='+days);
  return <div className="screen-stack">
    <div className="section-title"><div>
      <h2>Aniversariantes</h2>
      <p>Datas especiais de clientes da sua ótica, com saudação personalizada opcional.</p>
    </div></div>
    <div className="surface">
      <div className="filter-bar">
        <label className="field">Período<select value={days}
            onChange={e=>setDays(Number(e.target.value))}>
          <option value={0}>Hoje</option><option value={7}>Próximos 7 dias</option>
          <option value={30}>Próximos 30 dias</option><option value={90}>Próximos 90 dias</option>
        </select></label>
      </div>
      <p className="helper">O botão abre uma conversa com mensagem pronta; não envia automaticamente. Uma mensagem de aniversário só pode ser iniciada quando o cliente autorizou contato pelo WhatsApp.</p>
      {results.loading?<p className="empty">Carregando aniversariantes...</p>:
        !results.data?.length?<p className="empty">Nenhum aniversariante nesse período.</p>:
        <div className="table-wrap"><table><thead><tr><th>Cliente</th><th>Aniversário</th>
          <th>Contato autorizado</th><th>Ação</th></tr></thead><tbody>
          {results.data.map(b=><tr key={b.customerId}>
            <td><strong>{b.name}</strong><small>{b.phone||'Sem WhatsApp'}</small></td>
            <td>{date(b.birthday)}<small>{b.daysUntil===0?'Hoje':'Em '+b.daysUntil+' dias'}</small></td>
            <td>{can?.('CUSTOMERS_WRITE')?<label className="checkline">
              <input type="checkbox" checked={b.whatsappOptIn}
                onChange={e=>void perform(()=>api('/birthdays/'+b.customerId+'/consent',{
                  method:'PUT',body:{allowWhatsapp:e.target.checked},
                }),notify,results.refresh)}/>
              {b.whatsappOptIn?'Sim':'Não'}
              </label>:b.whatsappOptIn?'Sim':'Não'}</td>
            <td><button className="outline-action" disabled={!b.whatsappOptIn||!b.phone}
              onClick={()=>void contact(b,notify,results.refresh)}>Abrir WhatsApp</button></td>
          </tr>)}</tbody></table></div>}
      <p className="helper">A autorização deve refletir a preferência real do cliente. Para aniversários em 29/02, o aviso ocorre em 28/02 nos anos não bissextos.</p>
    </div>
  </div>;
}
