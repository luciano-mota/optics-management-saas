import { useState } from 'react';
import { api, date, currency } from '../api';
import type { ScreenProps } from './common';
import { FormField, getField, perform, useRemote } from './common';

const labels: Record<string,string> = {
  REQUESTED:'Nova solicitação', CLAIMED:'Assumida', CONFIRMED:'Confirmada com cliente',
  VISITED:'Visita realizada', CONVERTED:'Venda concluída',
  NOT_CONVERTED:'Venda não concretizada', CANCELLED:'Cancelada',
};
export function Visits({notify}:ScreenProps) {
  const [filter,setFilter]=useState('');
  const [selected,setSelected]=useState<any|null>(null);
  const [history,setHistory]=useState<any[]>([]);
  const [payment,setPayment]=useState('PIX');
  const {data,refresh}=useRemote<any[]>('/visits?status='+filter);
  const catalog=useRemote<any[]>('/catalog');
  const machines=useRemote<any[]>('/machines');
  async function open(visit:any) {
    setSelected(visit);
    try {setHistory(await api<any[]>('/visits/'+visit.id+'/events'));}
    catch(error){notify(String(error),true);}
  }
  const refreshAll=()=>{refresh();setSelected(null);};
  function whatsapp(visit:any) {
    const digits=String(visit.lead_phone).replace(/\D/g,'');
    const number=digits.startsWith('55')?digits:'55'+digits;
    const message='Olá, '+visit.lead_name+'! Sua solicitação de visita à ótica foi recebida. Podemos confirmar o horário de '+date(visit.appointment_at)+'?';
    window.open('https://wa.me/'+number+'?text='+encodeURIComponent(message),'_blank','noopener,noreferrer');
  }
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Visitas em domicílio</h2>
      <p>Atenda desde o primeiro pedido até a venda concretizada.</p></div></div>
    <div className="surface">
      <div className="filter-bar"><label className="field">Situação<select value={filter}
        onChange={e=>setFilter(e.target.value)}><option value="">Todas</option>
        {Object.entries(labels).map(([id,title])=><option key={id} value={id}>{title}</option>)}
      </select></label></div>
      {!data?.length?<p className="empty">Nenhuma visita encontrada.</p>:
        <div className="list-cards">{data.map(v=><div className="mini-row" key={v.id}>
          <div><strong>{v.lead_name}</strong>
            <small>{date(v.appointment_at)} · {v.lead_address}</small>
            <small>{labels[v.status]} · {v.assigned_name||'Sem responsável'}</small></div>
          <button className="outline-action" onClick={()=>void open(v)}>Abrir atendimento</button>
        </div>)}</div>}
    </div>
    {selected&&<div className="surface">
      <div className="section-title"><h3>Atendimento — {selected.lead_name}</h3>
        <button className="outline-action" onClick={()=>setSelected(null)}>Fechar</button></div>
      <p><strong>Endereço:</strong> {selected.lead_address}</p>
      <p><strong>Telefone:</strong> {selected.lead_phone}</p>
      <p><strong>Data solicitada:</strong> {date(selected.appointment_at)}</p>
      <p><strong>Situação:</strong> {labels[selected.status]}</p>
      <div className="filter-bar">
        {selected.status==='REQUESTED' && <button className="primary-action"
          onClick={()=>void perform(()=>api('/visits/'+selected.id+'/claim',{method:'POST'}),notify,refreshAll)}>
          Assumir visita</button>}
        {['CLAIMED','CONFIRMED'].includes(selected.status) &&
          <button className="outline-action" onClick={()=>whatsapp(selected)}>
            Abrir WhatsApp com mensagem pronta</button>}
        {selected.status==='CLAIMED' && <button className="primary-action"
          onClick={()=>void perform(()=>api('/visits/'+selected.id+'/status',
            {method:'PATCH',body:{status:'CONFIRMED'}}),notify,refreshAll)}>Confirmei com o cliente</button>}
        {selected.status==='CONFIRMED'&&<button className="primary-action"
          onClick={()=>void perform(()=>api('/visits/'+selected.id+'/status',
            {method:'PATCH',body:{status:'VISITED'}}),notify,refreshAll)}>Visita realizada</button>}
        {selected.status==='VISITED'&&<button className="outline-action"
          onClick={()=>void perform(()=>api('/visits/'+selected.id+'/status',
            {method:'PATCH',body:{status:'NOT_CONVERTED'}}),notify,refreshAll)}>Não concretizou venda</button>}
        {selected.status==='NOT_CONVERTED'&&<button className="outline-action danger"
          onClick={()=>void perform(()=>api('/visits/'+selected.id,{method:'DELETE'}),notify,refreshAll)}>
            Excluir lead</button>}
      </div>
      <h4>Histórico do atendimento</h4>
      <div className="list-cards">{history.map((event,i)=><div className="mini-row" key={i}>
        <span>{labels[event.event]||event.event}</span><span>{event.responsible||'Site público'} · {date(event.occurred_at)}</span>
      </div>)}</div>
      {selected.status==='VISITED'&&<div className="inline-panel">
        <h3>Concretizar a venda desta visita</h3>
        <p className="helper">Os dados do cliente serão transferidos para o cadastro principal somente após confirmar a venda.</p>
        <form className="form-grid" onSubmit={event=>{
          event.preventDefault();const form=event.currentTarget;
          void perform(()=>api('/visits/'+selected.id+'/convert',{method:'POST',body:{
            fullName:getField(form,'fullName'),cpf:getField(form,'cpf'),
            birthDate:getField(form,'birthDate')||null,
            lensId:getField(form,'lensId'),frameId:getField(form,'frameId'),
            amount:getField(form,'amount'),paymentMethod:payment,
            cardMachineId:getField(form,'cardMachineId')||null,
            installments:Number(getField(form,'installments')||1),
            passFee:new FormData(form).get('passFee')==='on',
            replacementMonths:Number(getField(form,'replacementMonths')||12),
            expectedDelivery:getField(form,'expectedDelivery')||null,
            notes:getField(form,'notes'),
          }}),notify,refreshAll);
        }}>
          <FormField name="fullName" title="Nome completo" required defaultValue={selected.lead_name}/>
          <FormField name="cpf" title="CPF" />
          <FormField name="birthDate" title="Nascimento" type="date" />
          <label className="field">Lente<select name="lensId" required defaultValue="">
            <option value="" disabled>Selecione</option>{catalog.data?.filter(x=>x.kind==='LENS'&&x.active).map(x=>
              <option key={x.id} value={x.id}>{x.brand}</option>)}</select></label>
          <label className="field">Armação<select name="frameId" required defaultValue="">
            <option value="" disabled>Selecione</option>{catalog.data?.filter(x=>x.kind==='FRAME'&&x.active).map(x=>
              <option key={x.id} value={x.id}>{x.brand}</option>)}</select></label>
          <FormField name="amount" title="Valor (R$)" type="number" required />
          <label className="field">Pagamento<select value={payment} onChange={e=>setPayment(e.target.value)}>
            <option value="PIX">Pix</option><option value="DINHEIRO">Dinheiro</option>
            <option value="DEBITO">Débito</option><option value="CREDITO">Crédito</option>
          </select></label>
          {['DEBITO','CREDITO'].includes(payment)&&<>
            <label className="field">Maquininha<select name="cardMachineId" required defaultValue="">
              <option value="" disabled>Selecione</option>{machines.data?.filter(x=>x.active).map(x=>
                <option key={x.id} value={x.id}>{x.name}</option>)}</select></label>
            {payment==='CREDITO'&&<FormField name="installments" title="Parcelas" type="number" defaultValue="1"/>}
            <label className="checkline"><input type="checkbox" name="passFee"/> Repassar a taxa</label>
          </>}
          <label className="field">Frequência de troca<select name="replacementMonths" defaultValue="12">
            <option value="6">6 meses</option><option value="12">1 ano</option></select></label>
          <FormField name="expectedDelivery" title="Data prevista de entrega" type="date" />
          <FormField name="notes" title="Observações" />
          <button className="primary-action form-action" type="submit">Confirmar visita e venda</button>
        </form>
      </div>}
    </div>}
  </div>;
}
