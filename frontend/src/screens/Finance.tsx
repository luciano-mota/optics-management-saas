import { Fragment, useState } from 'react';
import { MessageCircle } from 'lucide-react';
import { firstName, openWhatsapp } from '../whatsapp';
import { api, currency, date } from '../api';
import type { ScreenProps } from './common';
import { FormField, getField, perform, useRemote } from './common';

export function Finance({ notify }: ScreenProps) {
  const [direction,setDirection]=useState('EXPENSE');
  const [filter,setFilter]=useState('');
  const {data,refresh}=useRemote<any[]>('/finance?direction='+filter);
  const summary=useRemote<any>('/finance/summary');
  const [opened,setOpened]=useState<{id:string;mode:'settle'|'history'}|null>(null);
  const reload=()=>{refresh();summary.refresh();};
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Meu financeiro</h2>
      <p>Veja o dinheiro que entrou, as despesas e os recebimentos futuros.</p></div></div>
    <div className="metric-grid">
      {[['Recebido no mês',currency(summary.data?.received_month)],
        ['Despesas pagas',currency(summary.data?.spent_month)],
        ['A receber',currency(summary.data?.receivable)],
        ['Taxas de cartão',currency(summary.data?.card_fees_month)]].map(
        ([title,value])=><div className="metric" key={title}><span>{title}</span>
          <strong className="money">{value}</strong></div>)}
    </div>
    <div className="surface">
      <h3>Adicionar lançamento</h3>
      <form className="form-grid" onSubmit={event=>{
        event.preventDefault();const form=event.currentTarget;
        void perform(()=>api('/finance',{method:'POST',body:{
          direction,category:getField(form,'category'),description:getField(form,'description'),
          amount:getField(form,'amount'),occurredOn:getField(form,'occurredOn')||null,
          dueOn:getField(form,'dueOn')||null,
          settled:new FormData(form).get('settled')==='on',
        }}),notify,()=>{form.reset();reload();});
      }}>
        <label className="field">Tipo<select value={direction} onChange={e=>setDirection(e.target.value)}>
          <option value="EXPENSE">Despesa / saída</option><option value="INCOME">Receita / entrada</option>
        </select></label>
        <FormField name="category" title="Categoria" required />
        <FormField name="description" title="Descrição" required />
        <FormField name="amount" type="number" title="Valor (R$)" required />
        <FormField name="occurredOn" type="date" title="Data" />
        <FormField name="dueOn" type="date" title="Vencimento (opcional)" />
        <label className="checkline"><input type="checkbox" name="settled" /> Já foi pago/recebido</label>
        <button className="primary-action form-action" type="submit">Salvar lançamento</button>
      </form>
    </div>
    <div className="surface">
      <div className="section-title"><h3>Histórico financeiro</h3>
        <label className="field">Filtrar<select value={filter} onChange={e=>setFilter(e.target.value)}>
          <option value="">Todos</option><option value="INCOME">Receitas</option>
          <option value="EXPENSE">Despesas</option></select></label></div>
      <p className="helper">Recebeu só uma parte? Use <strong>Registrar recebimento</strong> com o valor pago: o saldo continua em aberto.
        Cada recebimento fica no histórico e pode ser estornado.</p>
      {!data?.length ? <p className="empty">Nenhum lançamento encontrado.</p> :
        <div className="table-wrap"><table><thead><tr><th>Data</th><th>Descrição</th><th>Valor líquido</th>
          <th>Pago/recebido</th><th>Situação</th><th>Ações</th></tr></thead><tbody>{data.map(entry=>{
          const income=entry.direction==='INCOME';
          const open=Number(entry.open_amount??0),paid=Number(entry.paid_amount??0);
          const state=entry.reversed_at?'Estornado':open<=0?(income?'Recebido':'Pago'):paid>0?'Parcial':'Pendente';
          const expanded=opened?.id===entry.id;
          return <Fragment key={entry.id}><tr className={expanded?'selected':undefined}>
          <td>{date(entry.occurred_on)}</td><td><strong>{entry.description}</strong>
            <small>{entry.category} · {entry.origin==='SALE'?'Venda':'Manual'}{entry.due_on?' · vence '+date(entry.due_on):''}</small></td>
          <td>{currency(entry.net_amount)}</td>
          <td>{currency(paid)}{open>0&&paid>0&&<small>falta {currency(open)}</small>}</td>
          <td><span className={'status-pill '+(state==='Parcial'?'warn':state==='Pendente'?'muted':state==='Estornado'?'danger':'ok')}>{state}</span></td>
          <td className="row-actions">{!entry.reversed_at&&open>0&&
            <button className="text-action" onClick={()=>setOpened(expanded&&opened?.mode==='settle'?null:{id:entry.id,mode:'settle'})}>
              Registrar {income?'recebimento':'pagamento'}</button>}
            {paid>0||entry.reversed_at?<button className="text-action" onClick={()=>
              setOpened(expanded&&opened?.mode==='history'?null:{id:entry.id,mode:'history'})}>Histórico</button>:null}
            {income&&entry.origin==='SALE'&&!entry.reversed_at&&open>0&&Number(entry.fee_amount)===0&&entry.customer_phone&&
              <button className="text-action whatsapp-action" onClick={()=>openWhatsapp(entry.customer_phone,
                'Olá, '+firstName(entry.customer_name)+'! Tudo bem? Passando para lembrar da parcela de '+currency(open)
                +(entry.due_on?' com vencimento em '+date(entry.due_on):'')+' referente à sua compra na ótica. '
                +'Se já pagou, desconsidere. Qualquer dúvida, estamos à disposição!',notify)}>
                <MessageCircle size={14}/> Lembrar pelo WhatsApp</button>}
            {entry.origin==='MANUAL'&&!entry.reversed_at && <button className="text-action danger"
              onClick={()=>void perform(()=>api('/finance/'+entry.id+'/reverse',{method:'PATCH'}),
              notify,reload)}>Estornar lançamento</button>}</td></tr>
          {expanded&&<tr className="detail-row"><td colSpan={6}>{opened?.mode==='settle'
            ?<SettleForm entry={entry} open={open} notify={notify} onDone={()=>{setOpened(null);reload();}}/>
            :<SettlementHistory entryId={entry.id} income={income} notify={notify} onChanged={reload}/>}</td></tr>}
          </Fragment>;})}</tbody></table></div>}
    </div>
  </div>;
}

const today=()=>new Date().toLocaleDateString('sv-SE');

/** Baixa parcial ou total. Cartão (com taxa) é recebido de uma vez, pelo valor da parcela. */
function SettleForm({entry,open,notify,onDone}:{entry:any;open:number;notify:ScreenProps['notify'];onDone:()=>void}){
  const card=Number(entry.fee_amount)>0;
  const [amount,setAmount]=useState(open.toFixed(2));
  const [paidOn,setPaidOn]=useState(today());
  const [note,setNote]=useState('');
  const income=entry.direction==='INCOME';
  return <form className="settle-form" onSubmit={e=>{
    e.preventDefault();
    void perform(()=>api('/finance/'+entry.id+'/settlements',{method:'POST',body:{amount,paidOn,note:note||null}}),notify,onDone);
  }}>
    <label className="field">Valor {income?'recebido':'pago'} (R$)<input type="number" step="0.01" min="0.01" max={open}
      required value={amount} readOnly={card} onChange={e=>setAmount(e.target.value)}/></label>
    <label className="field">Data<input type="date" required value={paidOn} max={today()} onChange={e=>setPaidOn(e.target.value)}/></label>
    <label className="field">Observação (opcional)<input maxLength={200} value={note} onChange={e=>setNote(e.target.value)}
      placeholder="Ex.: entrada em dinheiro"/></label>
    <div className="filter-bar"><button className="primary-action">Confirmar</button>
      <span className="helper">Saldo em aberto: <strong>{currency(open)}</strong>{card?' · cartão é recebido de uma vez':''}</span></div>
  </form>;
}

function SettlementHistory({entryId,income,notify,onChanged}:{entryId:string;income:boolean;
  notify:ScreenProps['notify'];onChanged:()=>void}){
  const {data,refresh}=useRemote<any[]>('/finance/'+entryId+'/settlements');
  if(!data)return <p className="helper">Carregando...</p>;
  if(!data.length)return <p className="helper">Nenhum {income?'recebimento':'pagamento'} registrado.</p>;
  return <div className="list-cards">{data.map(st=><div className="mini-row" key={st.id}>
    <div><strong className={st.reversed_at?'strike':undefined}>{currency(st.amount)} em {date(st.paid_on)}</strong>
      <small>{st.note?st.note+' · ':''}lançado por {st.created_by_name}
        {st.reversed_at?' · estornado em '+date(st.reversed_on)+(st.reversal_reason?' ('+st.reversal_reason+')':'')+' por '+st.reversed_by_name:''}</small></div>
    {!st.reversed_at&&<button className="text-action danger" onClick={()=>{
      const reason=window.prompt('Motivo do estorno (opcional):','')??null;
      if(reason===null)return;
      void perform(()=>api('/finance/settlements/'+st.id+'/reverse',{method:'POST',body:{reason}}),notify,()=>{refresh();onChanged();});
    }}>Estornar</button>}
  </div>)}</div>;
}
