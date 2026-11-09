import { useState } from 'react';
import { api, currency, date } from '../api';
import type { ScreenProps } from './common';
import { FormField, getField, perform, useRemote } from './common';

const statusLabel: Record<string,string> = {
  AWAITING_PRODUCTION: 'Aguardando produção', READY_FOR_DELIVERY: 'Pronto para entrega',
  DELIVERED: 'Entregue', CANCELLED: 'Cancelado',
};
export function Sales({ notify }: ScreenProps) {
  const [status, setStatus] = useState('');
  const [lensBrand, setLensBrand] = useState('');
  const [frameBrand, setFrameBrand] = useState('');
  const [method, setMethod] = useState('PIX');
  const customers = useRemote<any[]>('/customers');
  const catalog = useRemote<any[]>('/catalog');
  const machines = useRemote<any[]>('/machines');
  const sales = useRemote<any[]>('/sales?status=' + encodeURIComponent(status)
    + '&lensBrand=' + encodeURIComponent(lensBrand) + '&frameBrand=' + encodeURIComponent(frameBrand));
  const summary = useRemote<any>('/sales/dashboard');
  const refresh = () => { customers.refresh(); sales.refresh(); summary.refresh(); };
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Vendas e entregas</h2><p>Registre cada venda com o cliente e acompanhe até a entrega.</p></div></div>
    <div className="metric-grid">
      {[['Vendas hoje',summary.data?.today],['Esta semana',summary.data?.week],
        ['Este mês',summary.data?.month],['Entregas pendentes',summary.data?.pending_delivery]].map(
        ([title,count]) => <div className="metric" key={String(title)}><span>{title}</span><strong>{count ?? '—'}</strong></div>)}
    </div>
    <div className="surface"><h3>Registrar uma venda</h3>
      <p className="helper">Cadastre o cliente em “Meus clientes” antes de concluir a venda. O pedido ficará associado ao cadastro.</p>
      <form className="form-grid" onSubmit={event => {
        event.preventDefault();
        const form=event.currentTarget;
        void perform(() => api('/sales', { method: 'POST', body: {
          customerId: getField(form,'customerId'),lensId:getField(form,'lensId'),
          frameId:getField(form,'frameId'),amount:getField(form,'amount'),
          paymentMethod:method,cardMachineId:getField(form,'cardMachineId') || null,
          installments:Number(getField(form,'installments') || 1),
          passFee:new FormData(form).get('passFee') === 'on',
          replacementMonths:Number(getField(form,'replacementMonths') || 12),
          expectedDelivery:getField(form,'expectedDelivery') || null,
          notes:getField(form,'notes'),idempotencyKey:crypto.randomUUID(),
        } }), notify, () => { form.reset(); refresh(); });
      }}>
        <label className="field">Cliente<select name="customerId" required defaultValue="">
          <option value="" disabled>Selecione o cliente</option>{customers.data?.map(c =>
            <option key={c.id} value={c.id}>{c.full_name}</option>)}
        </select></label>
        <label className="field">Marca/tipo de lente<select name="lensId" required defaultValue="">
          <option value="" disabled>Selecione a lente</option>{catalog.data?.filter(c => c.kind==='LENS'&&c.active).map(c =>
            <option key={c.id} value={c.id}>{c.brand} {c.model || ''}</option>)}
        </select></label>
        <label className="field">Armação<select name="frameId" required defaultValue="">
          <option value="" disabled>Selecione a armação</option>{catalog.data?.filter(c => c.kind==='FRAME'&&c.active).map(c =>
            <option key={c.id} value={c.id}>{c.brand} {c.model || ''}</option>)}
        </select></label>
        <FormField name="amount" type="number" title="Valor (R$)" required placeholder="Ex.: 350.00" />
        <label className="field">Forma de pagamento<select name="paymentMethod" value={method}
          onChange={event => setMethod(event.target.value)}>
          <option value="PIX">Pix</option><option value="DINHEIRO">Dinheiro</option>
          <option value="DEBITO">Cartão de débito</option><option value="CREDITO">Cartão de crédito</option>
        </select></label>
        {(method==='DEBITO'||method==='CREDITO') && <>
          <label className="field">Maquininha<select name="cardMachineId" required defaultValue="">
            <option value="" disabled>Selecione</option>{machines.data?.filter(m=>m.active).map(m =>
              <option key={m.id} value={m.id}>{m.name}</option>)}
          </select></label>
          {method==='CREDITO' && <FormField name="installments" title="Parcelas (1 a 12)" type="number" defaultValue="1" />}
          <label className="checkline"><input type="checkbox" name="passFee"/> Repassar as taxas ao cliente</label>
        </>}
        <label className="field">Periodicidade de troca<select name="replacementMonths" defaultValue="12">
          <option value="6">A cada 6 meses</option><option value="12">A cada 1 ano</option>
        </select></label>
        <FormField name="expectedDelivery" title="Previsão de entrega" type="date" />
        <FormField name="notes" title="Observações" />
        <button className="primary-action form-action" type="submit">Confirmar venda</button>
      </form>
    </div>
    <div className="surface"><h3>Consultar vendas e entregas</h3>
      <div className="filter-bar">
        <label className="field">Situação<select value={status} onChange={e=>setStatus(e.target.value)}>
          <option value="">Todas</option>{Object.entries(statusLabel).map(([key,val]) =>
            <option key={key} value={key}>{val}</option>)}</select></label>
        <label className="field">Marca da lente<input value={lensBrand} onChange={e=>setLensBrand(e.target.value)}/></label>
        <label className="field">Marca da armação<input value={frameBrand} onChange={e=>setFrameBrand(e.target.value)}/></label>
      </div>
      {!sales.data?.length ? <p className="empty">Nenhuma venda encontrada.</p> :
        <div className="table-wrap"><table><thead><tr><th>Cliente</th><th>Produtos</th><th>Valor</th><th>Data</th>
          <th>Situação</th><th>Próxima etapa</th></tr></thead><tbody>{sales.data.map(s => <tr key={s.id}>
          <td><strong>{s.customer_name}</strong><small>{s.seller_name}</small></td>
          <td>{s.lens_brand} / {s.frame_brand}</td>
          <td>{currency(s.charged_amount)}<small>Líquido: {currency(s.net_amount)}</small></td>
          <td>{date(s.sold_on)}</td><td>{statusLabel[s.status] || s.status}</td>
          <td>{s.status==='AWAITING_PRODUCTION' ? <button className="text-action" onClick={() =>
            void perform(()=>api('/sales/'+s.id+'/status',{method:'PATCH',body:{status:'READY_FOR_DELIVERY'}}),
              notify,refresh)}>Marcar como pronto</button> :
            s.status==='READY_FOR_DELIVERY' ? <button className="text-action" onClick={() =>
              void perform(()=>api('/sales/'+s.id+'/status',{method:'PATCH',body:{status:'DELIVERED'}}),
                notify,refresh)}>Confirmar entrega</button> : '—'}</td>
          </tr>)}</tbody></table></div>}
    </div>
  </div>;
}
