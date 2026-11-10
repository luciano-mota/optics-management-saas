import { useState, type FormEvent } from 'react';
import { api, currency } from '../api';
import {
 SETTLEMENT_OPTIONS,settlementLabel,defaultBands,normalizeBands,bandsFromRates,
 bandFee,parsePercent,parseOptionalPercent,formatPercent,type FeeBand,
} from '../machineFees';
import type { ScreenProps } from './common';
import { useRemote } from './common';

type Rate={installments:number;fee:number;saleFee:number;installmentFee:number};
type Term={days:number;fee:number};
type Machine={id:string;name:string;debitFee:number;creditFee:number;fixedFee:number;
 debitDays:number;creditDays:number;anticipated:boolean;active:boolean;
 installmentFees:Rate[];creditTerms:Term[]};
type Draft={id?:string;name:string;debitFee:string;debitDays:number;creditDays:number;
 creditTerms:Record<number,string>;fixedFee:string;installments:boolean;
 maxInstallments:number;bands:FeeBand[];parcelFees:Record<number,string>;anticipated:boolean};

const empty=():Draft=>({name:'',debitFee:'',debitDays:1,creditDays:30,
 creditTerms:{30:''},fixedFee:'',installments:false,maxInstallments:12,
 bands:defaultBands(12),parcelFees:{},anticipated:false});
function restored(machine:Machine):Draft {
 const maximum=machine.installmentFees.length?Math.max(...machine.installmentFees.map(r=>r.installments)):12;
 return {id:machine.id,name:machine.name,debitFee:formatPercent(machine.debitFee),
  debitDays:machine.debitDays,creditDays:machine.creditDays,
  creditTerms:Object.fromEntries(
   (machine.creditTerms.length?machine.creditTerms:[{days:machine.creditDays,fee:machine.creditFee}])
   .map(t=>[t.days,formatPercent(t.fee)])),
  fixedFee:machine.fixedFee?String(machine.fixedFee).replace('.',','):'',
  installments:machine.installmentFees.length>0,maxInstallments:maximum,
  bands:machine.installmentFees.length?normalizeBands(bandsFromRates(machine.installmentFees),maximum):defaultBands(12),
  parcelFees:Object.fromEntries(machine.installmentFees.map(r=>[r.installments,formatPercent(r.installmentFee)])),
  anticipated:machine.anticipated};
}

function Editor({initial,onCancel,onSaved}:{
 initial:Draft;onCancel:()=>void;onSaved:()=>void;
}){
 const [form,setForm]=useState<Draft>(initial);
 const [otherDays,setOtherDays]=useState('');
 const [error,setError]=useState('');
 const [saving,setSaving]=useState(false);
 const patch=(p:Partial<Draft>)=>{setForm(f=>({...f,...p}));setError('');};
 const days=Object.keys(form.creditTerms).map(Number).sort((a,b)=>a-b);
 const parts=Array.from({length:form.maxInstallments-1},(_,i)=>i+2);
 const updateBandEnd=(index:number,end:number)=>{
  const copy=form.bands.map((band,i)=>i===index?{...band,to:end}:band);
  patch({bands:normalizeBands(copy,form.maxInstallments)});
 };
 const splitLast=()=>{
  const last=form.bands.at(-1);
  if(!last||last.from===last.to)return;
  const middle=Math.floor((last.from+last.to)/2);
  patch({bands:[...form.bands.slice(0,-1),{...last,to:middle},{from:middle+1,to:last.to,fee:''}]});
 };
 const save=async(e:FormEvent<HTMLFormElement>)=>{
  e.preventDefault();setError('');
  try {
   const creditTerms=days.map(day=>({days:day,fee:parsePercent(form.creditTerms[day],
    'Crédito à vista em '+day+' dias')}));
   const selected=creditTerms.find(t=>t.days===form.creditDays);
   if(!selected)throw Error('Marque o prazo padrão do crédito à vista.');
   const installmentFees=form.installments?parts.map(n=>{
    const sale=bandFee(form.bands,n);
    if(!sale.trim())throw Error('Informe a taxa por venda para '+n+'x.');
    const saleFee=parsePercent(sale,'Taxa por venda '+n+'x');
    const installmentFee=parseOptionalPercent(form.parcelFees[n]||'','Taxa adicional '+n+'x');
    if(saleFee+installmentFee>30)throw Error('A taxa total de '+n+'x não pode superar 30%.');
    return {installments:n,saleFee,installmentFee};
   }):[];
   if(!form.name.trim())throw Error('Informe o nome da maquininha.');
   const fixedFee=Number((form.fixedFee.trim()||'0').replace(',','.'));
   if(!Number.isFinite(fixedFee)||fixedFee<0||fixedFee>100||
     !/^\d{1,3}(?:[.,]\d{1,2})?$/.test(form.fixedFee.trim()||'0'))
     throw Error('A tarifa fixa deve ser um valor entre R$ 0 e R$ 100.');
   const payload={name:form.name.trim(),debitFee:parsePercent(form.debitFee,'Débito'),
    debitDays:form.debitDays,creditFee:selected.fee,creditDays:form.creditDays,
    creditTerms,installmentFees,fixedFee,anticipated:form.installments&&form.anticipated};
   setSaving(true);
   await api('/machines'+(form.id?'/'+form.id:''),{method:form.id?'PUT':'POST',body:payload});
   onSaved();
  }catch(error){setError(error instanceof Error?error.message:'Não foi possível salvar.');}
  finally{setSaving(false);}
 };
 return <div className="machine-backdrop">
  <section className="machine-dialog" role="dialog" aria-modal="true" aria-label="Configurar taxas da maquininha">
   <div className="section-title"><div><h2>{form.id?'Editar maquininha':'Nova maquininha'}</h2>
    <p>Copie as taxas que aparecem no aplicativo ou simulador da sua maquininha.</p></div>
    <button className="outline-action" type="button" onClick={onCancel}>Fechar</button></div>
   <form className="machine-editor" onSubmit={e=>void save(e)}>
    <label className="field">Nome da maquininha<input required maxLength={60} placeholder="Ex.: Mercado Pago Point"
     value={form.name} onChange={e=>patch({name:e.target.value})}/></label>
    <fieldset className="machine-section"><legend>Débito</legend>
     <div className="machine-fields"><label className="field">Taxa por venda (%)
      <input required inputMode="decimal" placeholder="Ex.: 1,99" value={form.debitFee}
       onChange={e=>patch({debitFee:e.target.value})}/></label>
      <label className="field">Quando cai<select value={form.debitDays} onChange={e=>patch({debitDays:Number(e.target.value)})}>
       {[0,1,2,14,30,60].map(n=><option value={n} key={n}>{settlementLabel(n)}</option>)}
      </select></label></div>
    </fieldset>
    <fieldset className="machine-section"><legend>Crédito à vista</legend>
     <p className="helper">O percentual pode mudar conforme o prazo. Marque o prazo que você usa normalmente.</p>
     <div className="machine-terms">{days.map(day=><div className="machine-term" key={day}>
      <label className="checkline"><input type="radio" name="creditDays"
       checked={form.creditDays===day} onChange={()=>patch({creditDays:day})}/>{settlementLabel(day)}
       {form.creditDays===day&&<strong> · padrão</strong>}</label>
      <label className="field">Taxa (%)<input required inputMode="decimal" placeholder="3,49"
       value={form.creditTerms[day]||''}
       onChange={e=>patch({creditTerms:{...form.creditTerms,[day]:e.target.value}})}/></label>
      {days.length>1&&<button type="button" className="outline-action" onClick={()=>{
       const next={...form.creditTerms};delete next[day];
       patch({creditTerms:next,creditDays:form.creditDays===day?Number(Object.keys(next)[0]):form.creditDays});
      }}>Remover</button>}
     </div>)}</div>
     <div className="machine-fields">
      <label className="field">Adicionar prazo<select value={otherDays} onChange={e=>setOtherDays(e.target.value)}>
       <option value="">Escolha...</option>
       {SETTLEMENT_OPTIONS.filter(n=>!days.includes(n)).map(n=><option key={n} value={n}>{settlementLabel(n)}</option>)}
       <option value="other">Outro prazo...</option>
      </select></label>
      {otherDays==='other'&&<label className="field">Dias (0–60)<input type="number" min={0} max={60}
        id="machine-other-days" placeholder="Ex.: 7"/></label>}
      <button type="button" className="outline-action" onClick={()=>{
       const raw=otherDays==='other'?(document.getElementById('machine-other-days') as HTMLInputElement)?.value:otherDays;
       if(!raw||!Number.isInteger(Number(raw))||Number(raw)<0||Number(raw)>60||days.includes(Number(raw))){
        setError('Escolha um prazo novo entre 0 e 60 dias.');return;}
       patch({creditTerms:{...form.creditTerms,[Number(raw)]:''}});setOtherDays('');
      }}>Adicionar prazo</button>
     </div>
    </fieldset>
    <fieldset className="machine-section"><legend>Crédito parcelado</legend>
     <label className="checkline"><input type="checkbox" checked={form.installments}
       onChange={e=>patch({installments:e.target.checked})}/>Aceito parcelar no cartão</label>
     {form.installments&&<>
      <label className="field">Até quantas parcelas<select value={form.maxInstallments}
        onChange={e=>{const max=Number(e.target.value);patch({maxInstallments:max,bands:normalizeBands(form.bands,max)});}}>
        {Array.from({length:11},(_,i)=>i+2).map(n=><option key={n} value={n}>{n}x</option>)}
      </select></label>
      <h4>Taxa por venda (por faixa)</h4>
      <p className="helper">Exemplo: uma taxa para 2x até 6x e outra para 7x até 12x.</p>
      <div className="machine-terms">{form.bands.map((b,i)=><div className="machine-term" key={b.from}>
       <strong>De {b.from}x até</strong>
       {i<form.bands.length-1?
        <select value={b.to} aria-label={'Fim da faixa a partir de '+b.from+'x'}
          onChange={e=>updateBandEnd(i,Number(e.target.value))}>
          {Array.from({length:form.maxInstallments-b.from},(_,j)=>b.from+j)
           .map(n=><option key={n} value={n}>{n}x</option>)}
        </select>:<strong>{b.to}x</strong>}
       <label className="field">Taxa (%)<input inputMode="decimal" placeholder="4,99"
        value={b.fee} onChange={e=>patch({bands:form.bands.map((v,j)=>i===j?{...v,fee:e.target.value}:v)})}/></label>
       {form.bands.length>1&&<button type="button" className="outline-action"
         onClick={()=>patch({bands:normalizeBands(form.bands.filter((_,j)=>i!==j),form.maxInstallments)})}>
         Remover faixa</button>}
      </div>)}</div>
      {form.bands.at(-1)!.to>form.bands.at(-1)!.from&&
       <button type="button" className="outline-action" onClick={splitLast}>+ Dividir faixa</button>}
      <h4>Taxa adicional de parcelamento (opcional)</h4>
      <p className="helper">Use os valores do simulador da operadora. Se não houver taxa adicional, deixe em branco.</p>
      <div className="machine-rates">{parts.map(n=><label className="field" key={n}>{n}x
       <input inputMode="decimal" placeholder="0" value={form.parcelFees[n]||''}
       onChange={e=>patch({parcelFees:{...form.parcelFees,[n]:e.target.value}})}/></label>)}</div>
      <div className="table-wrap"><table><thead><tr><th>Parcelas</th><th>Taxa por venda</th>
       <th>Taxa adicional</th><th>Total estimado</th></tr></thead><tbody>
       {parts.map(n=>{
        const base=Number(bandFee(form.bands,n).replace(',','.'));
        const additional=Number((form.parcelFees[n]||'0').replace(',','.'));
        return <tr key={n}><td>{n}x</td><td>{bandFee(form.bands,n)||'—'}%</td>
         <td>{form.parcelFees[n]||'0'}%</td><td><strong>{
          Number.isFinite(base)&&Number.isFinite(additional)?
           (base+additional).toFixed(2).replace('.',',')+'%':'—'
         }</strong></td></tr>;
       })}</tbody></table></div>
      <h4>Quando cai o parcelado?</h4>
      <label className="checkline"><input type="radio" name="anticipated"
       checked={form.anticipated} onChange={()=>patch({anticipated:true})}/>
       Tudo de uma vez ({settlementLabel(form.creditDays).toLowerCase()})</label>
      <label className="checkline"><input type="radio" name="anticipated"
       checked={!form.anticipated} onChange={()=>patch({anticipated:false})}/>
       Uma parcela por mês</label>
     </>}
    </fieldset>
    <label className="field">Tarifa fixa por venda (R$, opcional)<input inputMode="decimal"
      placeholder="0,00" value={form.fixedFee} onChange={e=>patch({fixedFee:e.target.value})}/></label>
    {error&&<p className="calendar-warning" role="alert">{error}</p>}
    <div className="calendar-actions"><button type="button" className="outline-action" onClick={onCancel}>
      Cancelar</button><button className="primary-action" type="submit" disabled={saving}>
      {saving?'Salvando...':'Salvar maquininha'}</button></div>
   </form>
  </section></div>;
}

export function Machines({notify,can}:ScreenProps) {
 const items=useRemote<Machine[]>('/machines');
 const [editing,setEditing]=useState<Draft|null>(null);
 const writable=!!can?.('FINANCE_WRITE');
 const deactivate=async(machine:Machine)=>{
  if(!window.confirm('Desativar '+machine.name+'? Vendas anteriores continuam no histórico.'))return;
  try{await api('/machines/'+machine.id,{method:'DELETE'});notify('Maquininha desativada.');items.refresh();}
  catch(e){notify(e instanceof Error?e.message:'Erro ao desativar.',true);}
 };
 return <div className="screen-stack">
  <div className="section-title"><div><h2>Maquininhas e taxas</h2>
   <p>Cadastre as taxas como aparecem no simulador da operadora. O valor líquido é calculado nas vendas.</p>
   </div>{writable&&<button className="primary-action" onClick={()=>setEditing(empty())}>+ Nova maquininha</button>}
  </div>
  <div className="surface"><h3>Minhas maquininhas</h3>
   {!items.data?.length?<p className="empty">Nenhuma maquininha cadastrada. Exemplos: Ton, Stone, PagBank ou InfinitePay.</p>:
    <div className="list-cards">{items.data.map(m=><div className="mini-row" key={m.id}>
      <div><strong>{m.name} {!m.active&&' (desativada)'}</strong>
       <small>Débito: {formatPercent(m.debitFee)}% · {settlementLabel(m.debitDays).toLowerCase()}</small>
       <small>Crédito à vista: {m.creditTerms.map(t=>settlementLabel(t.days)+': '+
         formatPercent(t.fee)+'%').join(' · ')}</small>
       {m.installmentFees.length>0&&<small>Parcelado até {Math.max(...m.installmentFees.map(r=>r.installments))}x
        · {m.anticipated?'recebe tudo de uma vez':'recebe uma parcela por mês'}</small>}
       {m.fixedFee>0&&<small>Tarifa fixa: {currency(m.fixedFee)}</small>}
      </div>
      {writable&&m.active&&<div className="filter-bar">
       <button className="outline-action" onClick={()=>setEditing(restored(m))}>Editar taxas</button>
       <button className="outline-action" onClick={()=>void deactivate(m)}>Desativar</button>
      </div>}
    </div>)}</div>}
  </div>
  {editing&&<Editor initial={editing} onCancel={()=>setEditing(null)}
   onSaved={()=>{setEditing(null);notify('Maquininha salva com sucesso.');items.refresh();}}/>}
 </div>;
}
