import { api, currency } from '../api';
import type { ScreenProps } from './common';
import { FormField, getField, perform, useRemote } from './common';

export function Machines({notify}:ScreenProps) {
  const {data,refresh}=useRemote<any[]>('/machines');
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Maquininhas e taxas</h2>
      <p>Cadastre suas taxas para saber quanto entra de verdade.</p></div></div>
    <div className="surface"><h3>Cadastrar maquininha</h3>
      <form className="form-grid" onSubmit={event=>{
        event.preventDefault();const form=event.currentTarget;
        const rates:Record<string,number>={};
        for(const segment of getField(form,'rates').split(',')){
          const [installments,fee]=segment.trim().split(':');
          if(installments&&fee)rates[installments]=Number(fee);
        }
        void perform(()=>api('/machines',{method:'POST',body:{
          name:getField(form,'name'),debitFee:getField(form,'debitFee'),
          creditFee:getField(form,'creditFee'),fixedFee:getField(form,'fixedFee')||'0',
          debitDays:Number(getField(form,'debitDays')||1),
          creditDays:Number(getField(form,'creditDays')||30),
          anticipated:new FormData(form).get('anticipated')==='on',
          installmentFees:rates,
        }}),notify,()=>{form.reset();refresh();});
      }}>
        <FormField name="name" title="Nome da maquininha" required />
        <FormField name="debitFee" title="Taxa débito (%)" type="number" defaultValue="1.99" required />
        <FormField name="creditFee" title="Taxa crédito 1x (%)" type="number" defaultValue="3.49" required />
        <FormField name="fixedFee" title="Taxa fixa (R$)" type="number" defaultValue="0" />
        <FormField name="debitDays" title="Dias para débito" type="number" defaultValue="1" />
        <FormField name="creditDays" title="Dias para crédito" type="number" defaultValue="30" />
        <FormField name="rates" title="Taxas de parcelas: 2:4.5,3:5.2,6:8.1" />
        <label className="checkline"><input type="checkbox" name="anticipated" /> Antecipação total das parcelas</label>
        <button className="primary-action form-action" type="submit">Cadastrar maquininha</button>
      </form>
    </div>
    <div className="surface"><h3>Maquininhas cadastradas</h3>
      {!data?.length?<p className="empty">Nenhuma maquininha cadastrada.</p>:
        <div className="list-cards">{data.map(machine=><div className="mini-row" key={machine.id}>
          <div><strong>{machine.name}</strong><small>Débito: {machine.debit_fee}% · Crédito: {machine.credit_fee}%</small>
            <small>Taxa fixa: {currency(machine.fixed_fee)} · Crédito em {machine.credit_days} dias</small></div>
          <span>{machine.active?'Ativa':'Inativa'}</span></div>)}</div>}
    </div>
  </div>;
}
