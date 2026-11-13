import { useState } from 'react';
import { api, currency, date } from '../api';
import type { ScreenProps } from './common';
import { FormField, getField, perform, useRemote } from './common';

export function Finance({ notify }: ScreenProps) {
  const [direction,setDirection]=useState('EXPENSE');
  const [filter,setFilter]=useState('');
  const {data,refresh}=useRemote<any[]>('/finance?direction='+filter);
  const summary=useRemote<any>('/finance/summary');
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
      {!data?.length ? <p className="empty">Nenhum lançamento encontrado.</p> :
        <div className="table-wrap"><table><thead><tr><th>Data</th><th>Descrição</th><th>Valor líquido</th>
          <th>Situação</th><th>Ações</th></tr></thead><tbody>{data.map(entry=><tr key={entry.id}>
          <td>{date(entry.occurred_on)}</td><td><strong>{entry.description}</strong>
            <small>{entry.category} · {entry.origin==='SALE'?'Venda':'Manual'}</small></td>
          <td>{currency(entry.net_amount)}</td><td>{entry.reversed_at?'Estornado':
            entry.settled_on?'Pago/recebido':'Pendente'}</td>
          <td>{!entry.settled_on&&!entry.reversed_at &&
            <button className="text-action" onClick={()=>void perform(
              ()=>api('/finance/'+entry.id+'/settle',{method:'PATCH',body:{
                settledOn:null}}),notify,reload)}>Confirmar pagamento</button>}
            {entry.origin==='MANUAL'&&!entry.reversed_at && <button className="text-action danger"
              onClick={()=>void perform(()=>api('/finance/'+entry.id+'/reverse',{method:'PATCH'}),
              notify,reload)}>Estornar</button>}</td></tr>)}</tbody></table></div>}
    </div>
  </div>;
}
