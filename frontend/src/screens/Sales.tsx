import { useState } from 'react';
import { api, currency, date } from '../api';
import type { ScreenProps } from './common';
import { AddressFields } from './AddressFields';
import { FormField, getField, perform, useRemote, PhoneField } from './common';

const statusLabel: Record<string,string> = {
  AWAITING_PRODUCTION: 'Aguardando produção', READY_FOR_DELIVERY: 'Pronto para entrega',
  DELIVERED: 'Entregue', CANCELLED: 'Cancelado',
};
export function Sales({ notify, can }: ScreenProps) {
  const [status, setStatus] = useState('');
  const [lensBrand, setLensBrand] = useState('');
  const [frameBrand, setFrameBrand] = useState('');
  const [method, setMethod] = useState('PIX');
  const [machineId,setMachineId]=useState('');
  const [installments,setInstallments]=useState(1);
  const [settlementDays,setSettlementDays]=useState<number|null>(null);
  const [saleAmount,setSaleAmount]=useState('');
  const [passFee,setPassFee]=useState(false);
  const [simulation,setSimulation]=useState<{
    chargedAmount:number;feeAmount:number;netAmount:number;feePercent:number;
    receivables:{installment:number;dueOn:string;amount:number}[];
  }|null>(null);
  const [newCustomer, setNewCustomer] = useState(false);
  const [patientSearch,setPatientSearch] = useState('');
  const [patient,setPatient] = useState<any|null>(null);
  const [loadingPatient,setLoadingPatient] = useState(false);
  const suggestedPatients=useRemote<any[]>('/optometry/patients/suggestions?q='+encodeURIComponent(patientSearch));

  const customers = useRemote<any[]>('/customers');
  const catalog = useRemote<any[]>('/catalog');
  const categories = useRemote<Record<string,Record<string,string>>>('/catalog/categories');
  const productLabel=(c:any)=>[c.brand,c.model].filter(Boolean).join(' ')+
    (c.category?' · '+(categories.data?.[c.kind]?.[c.category]?.replace(/ \(.*\)$/,'')??c.category):'');
  const machines = useRemote<any[]>('/machines');
  const chosenMachine=machines.data?.find(m=>m.id===machineId);
  const allowedInstallments=chosenMachine?.installmentFees?.length?
    Math.max(...chosenMachine.installmentFees.map((r:any)=>r.installments)):1;
  const availableTerms=chosenMachine?.creditTerms?.length?chosenMachine.creditTerms:
    chosenMachine?[{days:chosenMachine.creditDays,fee:chosenMachine.creditFee}]:[];
  const simulate=async()=>{
    if(!Number.isFinite(Number(saleAmount))||Number(saleAmount)<=0){
      notify('Informe um valor de venda para simular.',true);return;
    }
    try {
      const result=await api<any>('/machines/preview',{method:'POST',body:{
        amount:Number(saleAmount),paymentMethod:method,
        installments:method==='CREDITO'?installments:1,
        cardMachineId:['CREDITO','DEBITO'].includes(method)?machineId:null,
        passFee,settlementDays:method==='CREDITO'&&installments===1?
          settlementDays??chosenMachine?.creditDays??null:null,
      }});
      setSimulation(result);
    }catch(error){notify(error instanceof Error?error.message:'Erro na simulação.',true);}
  };
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
      <p className="helper">Selecione um cliente existente, cadastre um novo ou aproveite o cadastro de um paciente que fez consulta pela ótica.</p>
      {can?.('PATIENTS_READ')&&<div className="inline-panel">
        <h4>Já é paciente de uma clínica parceira?</h4>
        <label className="field">Buscar paciente pelo nome
          <input type="search" value={patientSearch}
            onChange={event=>{setPatientSearch(event.target.value);setPatient(null);}}
            placeholder="Digite pelo menos 2 letras" autoComplete="off"/></label>
        {patientSearch.trim().length>=2&&!patient&&<div className="list-cards">
          {suggestedPatients.data?.map(p=><button key={p.id} type="button"
            className="outline-action" onClick={()=>{
              setLoadingPatient(true);
              void api<any>('/optometry/patients/'+p.id+'/sales-prefill').then(details=>{
                setPatient(details);setNewCustomer(true);setPatientSearch(p.name);
              }).catch(error=>notify(String(error),true)).finally(()=>setLoadingPatient(false));
            }}>{p.name} {p.birthDate?' · '+date(p.birthDate):''} {p.phoneSuffix||''}</button>)}
          {!suggestedPatients.data?.length&&<p className="helper">Nenhum paciente disponível para conversão.</p>}
        </div>}
        {loadingPatient&&<p className="helper">Carregando cadastro do paciente...</p>}
        {patient&&<p className="helper">Paciente selecionado: <strong>{patient.full_name}</strong>.
          Confira os dados abaixo e complete os campos faltantes.
          <button type="button" className="text-action" onClick={()=>{
            setPatient(null);setPatientSearch('');setNewCustomer(false);
          }}>Desvincular</button></p>}
      </div>}
      <label className="checkline"><input type="checkbox" checked={newCustomer}
        onChange={event=>{setNewCustomer(event.target.checked);if(!event.target.checked)setPatient(null);}}/>
        {patient?'Cadastro aproveitado de paciente':'Novo cliente nesta venda'}</label>
      <form className="form-grid" key={patient?.id||'regular'} onSubmit={event => {
        event.preventDefault();
        const form=event.currentTarget;
        const sale = {
          customerId: newCustomer ? null : getField(form,'customerId'),
          lensId:getField(form,'lensId'),
          frameId:getField(form,'frameId'),amount:getField(form,'amount'),
          paymentMethod:method,cardMachineId:machineId||null,
          installments:method==='CREDITO'?installments:1,
          passFee,settlementDays:method==='CREDITO'&&installments===1?
            settlementDays??chosenMachine?.creditDays??null:null,
          replacementMonths:Number(getField(form,'replacementMonths') || 12),
          expectedDelivery:getField(form,'expectedDelivery') || null,
          notes:getField(form,'notes'),idempotencyKey:crypto.randomUUID(),
        };
        const customer = {
          fullName:getField(form,'fullName'),cpf:getField(form,'cpf'),
          birthDate:getField(form,'birthDate'),phone:getField(form,'phone'),
          address:getField(form,'address'),
        };
        void perform(() => patient ?
          api('/sales/with-patient',{method:'POST',body:{patientId:patient.id,sale,customer}}) :
          newCustomer ? api('/sales/with-customer',{method:'POST',body:{sale,customer}}) :
          api('/sales',{method:'POST',body:sale}), notify, () => {
            form.reset();setNewCustomer(false);setPatient(null);setPatientSearch('');
            setMachineId('');setInstallments(1);setSettlementDays(null);
            setSaleAmount('');setSimulation(null);setPassFee(false);refresh();
          });
      }}>
        {newCustomer ? <>
          <FormField name="fullName" title="Nome completo" required defaultValue={patient?.full_name||''}/>
          <FormField name="cpf" title="CPF" required defaultValue={patient?.cpf||''}/>
          <FormField name="birthDate" title="Data de nascimento" type="date" required defaultValue={patient?.birth_date||''}/>
          <PhoneField name="phone" title="WhatsApp ou telefone" defaultValue={patient?.phone||''}/>
          <AddressFields required defaultValue={patient?.address||''}/>
        </> : <label className="field">Cliente<select name="customerId" required defaultValue="">
          <option value="" disabled>Selecione o cliente</option>{customers.data?.map(c =>
            <option key={c.id} value={c.id}>{c.full_name}</option>)}
        </select></label>}
        <label className="field">Marca/tipo de lente<select name="lensId" required defaultValue="">
          <option value="" disabled>Selecione a lente</option>{catalog.data?.filter(c => c.kind==='LENS'&&c.active).map(c =>
            <option key={c.id} value={c.id}>{productLabel(c)}</option>)}
        </select></label>
        <label className="field">Armação<select name="frameId" required defaultValue="">
          <option value="" disabled>Selecione a armação</option>{catalog.data?.filter(c => c.kind==='FRAME'&&c.active).map(c =>
            <option key={c.id} value={c.id}>{productLabel(c)}</option>)}
        </select></label>
        <label className="field">Valor (R$)<input name="amount" type="number" step="0.01"
          required placeholder="Ex.: 350.00" value={saleAmount}
          onChange={e=>{setSaleAmount(e.target.value);setSimulation(null);}}/></label>
        <label className="field">Forma de pagamento<select name="paymentMethod" value={method}
          onChange={event=>{setMethod(event.target.value);setMachineId('');
            setInstallments(1);setSettlementDays(null);setSimulation(null);}}>
          <option value="PIX">Pix</option><option value="DINHEIRO">Dinheiro</option>
          <option value="DEBITO">Cartão de débito</option><option value="CREDITO">Cartão de crédito</option>
        </select></label>
        {(method==='DEBITO'||method==='CREDITO')&&<>
          <label className="field">Maquininha<select name="cardMachineId" required
            value={machineId} onChange={e=>{
              setMachineId(e.target.value);setInstallments(1);setSettlementDays(null);setSimulation(null);
            }}>
            <option value="" disabled>Selecione a maquininha</option>
            {machines.data?.filter(m=>m.active).map(m=><option key={m.id} value={m.id}>{m.name}</option>)}
          </select></label>
          {method==='CREDITO'&&<>
            <label className="field">Parcelas<select value={installments}
              onChange={e=>{setInstallments(Number(e.target.value));setSimulation(null);}}>
              <option value={1}>1x (à vista)</option>
              {Array.from({length:Math.max(0,allowedInstallments-1)},(_,i)=>i+2)
                .map(n=><option key={n} value={n}>{n}x</option>)}
            </select></label>
            {installments===1&&<label className="field">Quando a operadora deposita?<select
              value={settlementDays??chosenMachine?.creditDays??30}
              onChange={e=>{setSettlementDays(Number(e.target.value));setSimulation(null);}}>
              {availableTerms.map((t:any)=><option value={t.days} key={t.days}>
                {t.days===0?'Na hora':t.days===1?'No dia seguinte':'Em '+t.days+' dias'} · {t.fee}%
              </option>)}
              </select></label>}
          </>}
          <label className="checkline"><input type="checkbox" checked={passFee}
            onChange={e=>{setPassFee(e.target.checked);setSimulation(null);}}/>
            Repassar a taxa da maquininha ao cliente</label>
          <button type="button" className="outline-action" disabled={!machineId}
            onClick={()=>void simulate()}>Simular taxa e valor líquido</button>
          {simulation&&<div className="inline-panel">
            <strong>Cobrar do cliente: {currency(simulation.chargedAmount)}</strong>
            <p>Taxas: {currency(simulation.feeAmount)} ({simulation.feePercent}%) ·
             Líquido previsto: {currency(simulation.netAmount)}</p>
            {simulation.receivables?.map(r=><small key={r.installment}>
              {r.installment}ª parcela · {date(r.dueOn)} · {currency(r.amount)}
            </small>)}
          </div>}
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
