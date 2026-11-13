import { useEffect, useState } from 'react';
import { api, date } from '../api';
import type { ScreenProps } from './common';
import { perform, useRemote } from './common';
import { MapPin } from 'lucide-react';
import { parseAddress, type CepAddress } from '../cep';
import { AddressFields } from './AddressFields';

type Holiday = {
  name:string; scope:string; source:string;sourceUrl:string|null; verified:boolean;
};
export type CalendarDay = {
  date:string;weekday:number;normallyOpen:boolean;willOpen:boolean;
  overridden:boolean;holidays:Holiday[];
};
export type MonthPlan = {
  year:number;month:number;timeZone:string;stateUf:string|null;municipalityIbge:string|null;
  reviewed:boolean;localHolidaysVerified:boolean;warnings:string[];
  totalOpenDays:number;openDaysThroughToday:number;remainingOpenDays:number;days:CalendarDay[];
};
const scopes:Record<string,string>={
  NATIONAL:'Nacional',STATE:'Estadual',MUNICIPAL:'Municipal',OPTIONAL:'Ponto facultativo',
};

function holidaySummary(day:CalendarDay):string {
  return day.holidays.map(h=>h.name+' ('+(scopes[h.scope]||h.scope)+')').join(', ');
}

export function MonthlyReview({shopId,onDone,onCalendar,notify}:{
  shopId:string;onDone:()=>void;onCalendar:()=>void;
  notify:(msg:string,error?:boolean)=>void;
}){
  const [plan,setPlan]=useState<MonthPlan|null>(null);
  const [selected,setSelected]=useState<Record<string,boolean>>({});
  const [loading,setLoading]=useState(true);
  useEffect(()=>{
    let alive=true;
    setLoading(true);setSelected({});setPlan(null);
    api<MonthPlan>('/business-calendar/month')
      .then(value=>{if(alive){setPlan(value);if(value.reviewed)onDone();}})
      .catch(error=>{if(alive){notify('Não foi possível verificar o calendário: '+String(error),true);onDone();}})
      .finally(()=>{if(alive)setLoading(false);});
    return ()=>{alive=false;};
  },[shopId]);
  if(loading||!plan||plan.reviewed)return null;
  const dates=plan.days.filter(day=>day.normallyOpen&&day.holidays.length);
  const effectiveOpen=plan.days.reduce((sum,day)=>sum+
    Number(selected[day.date]??day.willOpen),0);
  return <div className="calendar-modal-backdrop">
    <div className="calendar-modal" role="dialog" aria-modal="true"
      aria-labelledby="monthly-review-title">
      <div className="calendar-modal-heading"><div>
        <span className="eyebrow">Planejamento da sua ótica</span>
        <h2 id="monthly-review-title">Vamos organizar {plan.month.toString().padStart(2,'0')}/{plan.year}?</h2>
        <p>Confira os feriados que caem em dias de funcionamento e escolha quando sua loja vai abrir.</p>
      </div></div>
      {plan.warnings.map((warning,i)=><p className="calendar-warning" key={i}>{warning}</p>)}
      <div className="metric-grid">
        <div className="metric"><span>Dias de expediente previstos</span><strong>{effectiveOpen}</strong></div>
        <div className="metric"><span>Feriados a conferir</span><strong>{dates.length}</strong></div>
      </div>
      <div className="calendar-modal-list">
        {dates.length ? dates.map(day=><label key={day.date} className="calendar-holiday-row">
          <div><strong>{date(day.date)}</strong><small>{holidaySummary(day)}</small>
            {day.holidays.some(h=>!h.verified)&&<small>Fonte pendente de verificação</small>}</div>
          <span className="calendar-toggle">
            <input type="checkbox" checked={selected[day.date]??day.willOpen}
              onChange={event=>setSelected(old=>({...old,[day.date]:event.target.checked}))}/>
            Terá expediente?
          </span>
        </label>) : <p className="helper">Não há feriados identificados em seus dias habituais de atendimento neste mês.</p>}
      </div>
      <div className="calendar-actions">
        <button className="outline-action" onClick={()=>{onCalendar();onDone();}}>
          Abrir calendário completo</button>
        <button className="outline-action" onClick={onDone}>Revisar depois</button>
        <button className="primary-action" onClick={()=>void perform(
          ()=>api('/business-calendar/review',{method:'POST',body:{
            year:plan.year,month:plan.month,decisions:selected,
          }}),notify,onDone)}>Salvar meu planejamento</button>
      </div>
    </div>
  </div>;
}

export function BusinessCalendar({notify,isMaster}:ScreenProps&{isMaster:boolean}){
  const now=new Date();
  const [year,setYear]=useState(now.getFullYear());
  const [month,setMonth]=useState(now.getMonth()+1);
  const [editing,setEditing]=useState<string|null>(null);
  const plan=useRemote<MonthPlan>('/business-calendar/month?year='+year+'&month='+month);
  const settings=useRemote<any>('/business-calendar/settings');
  const canMaster=isMaster;
  const [weekday,setWeekday]=useState<number[]>([1,2,3,4,5,6]);
  const [editingAddress,setEditingAddress]=useState(false);
  const [address,setAddress]=useState('');
  const [place,setPlace]=useState<CepAddress|null>(null);
  useEffect(()=>{
    if(Array.isArray(settings.data?.openWeekdays))setWeekday(settings.data.openWeekdays);
  },[settings.data?.openWeekdays]);
  const location=settings.data?.state_uf?
    settings.data.municipality_name+'/'+settings.data.state_uf:null;
  const saveAddress=()=>{
    const parsed=parseAddress(address);
    void perform(()=>api('/optic-profile/address',{method:'PUT',body:{
      address,city:place?.city||parsed.city,uf:place?.uf||parsed.uf,ibge:place?.ibge||null,
    }}),notify,()=>{setEditingAddress(false);settings.refresh();plan.refresh();});
  };
  const changeDay=(day:CalendarDay,willOpen:boolean)=>{
    void perform(()=>api('/business-calendar/days/'+day.date,{method:'PUT',
      body:{willOpen,title:editing || 'Expediente informado pela ótica',sourceUrl:null}}),
      notify,plan.refresh);
  };
  const weekDays=['Segunda','Terça','Quarta','Quinta','Sexta','Sábado','Domingo'];
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Calendário e feriados</h2>
      <p>Os dias da meta são os dias em que sua ótica abre, mesmo que não realize nenhuma venda.</p></div></div>
    <div className="surface">
      <h3>Onde fica a ótica</h3>
      {!editingAddress?<div className="location-card">
        <div><span className="tile-icon accent-red"><MapPin size={18}/></span>
          <div><strong>{location||'Endereço da ótica não cadastrado'}</strong>
            <small>{settings.data?.address||'Cadastre o endereço para mostrarmos os feriados da sua cidade.'}</small></div></div>
        {canMaster&&<button className="outline-action" type="button" onClick={()=>{
          setAddress(settings.data?.address||'');setPlace(null);setEditingAddress(true);
        }}>{location?'Alterar endereço':'Cadastrar endereço'}</button>}
      </div>:<form className="form-grid" onSubmit={event=>{event.preventDefault();saveAddress();}}>
        <AddressFields name="opticAddress" legend="Endereço da ótica" required
          defaultValue={settings.data?.address||''} onChange={setAddress} onLocation={setPlace}/>
        <div className="calendar-actions span-all">
          <button className="outline-action" type="button" onClick={()=>setEditingAddress(false)}>Cancelar</button>
          <button className="primary-action" type="submit">Salvar endereço</button>
        </div>
      </form>}
      <p className="helper">Os feriados nacionais e os do seu estado já aparecem no calendário. Feriados da
        cidade podem ser marcados por você abaixo, em cada dia.</p>
    </div>
    <div className="surface">
      <h3>Dias em que a loja abre</h3>
      <div className="day-chips" role="group" aria-label="Dias de abertura">
        {weekDays.map((name,index)=>{
          const active=weekday.includes(index+1);
          return <button key={name} type="button" className={active?'day-chip active':'day-chip'}
            aria-pressed={active} disabled={!canMaster}
            onClick={()=>setWeekday(prev=>active?prev.filter(d=>d!==index+1):[...prev,index+1].sort())}>
            {name}</button>;})}
      </div>
      {canMaster&&<button className="primary-action day-chips-save" type="button" onClick={()=>void perform(
        ()=>api('/business-calendar/settings',{method:'PUT',body:{openWeekdays:weekday}}),
        notify,()=>{settings.refresh();plan.refresh();})}>Salvar dias de abertura</button>}
    </div>
    <div className="surface">
      <div className="section-title"><h3>Calendário mensal</h3><div className="filter-bar">
        <label className="field">Mês<select value={month} onChange={event=>setMonth(Number(event.target.value))}>
          {Array.from({length:12},(_,i)=><option key={i+1} value={i+1}>{String(i+1).padStart(2,'0')}</option>)}
        </select></label>
        <label className="field">Ano<input type="number" min={2000} max={2199}
          value={year} onChange={event=>setYear(Number(event.target.value))}/></label>
      </div></div>
      {plan.data?.warnings.map((warning,i)=><p className="calendar-warning" key={i}>{warning}</p>)}
      <div className="metric-grid">
        {[
          ['Dias previstos',plan.data?.totalOpenDays],
          ['Dias abertos até hoje',plan.data?.openDaysThroughToday],
          ['Dias restantes',plan.data?.remainingOpenDays],
        ].map(([name,count])=><div className="metric" key={name}><span>{name}</span>
          <strong>{count??'—'}</strong></div>)}
      </div>
      <div className="calendar-day-list">{plan.data?.days.map(day=>
        <div className="mini-row" key={day.date}>
          <div><strong>{date(day.date)} · {weekDays[day.weekday-1]}</strong>
            <small>{holidaySummary(day)||'Dia habitual'}</small>
            {day.overridden&&<small>Expediente definido manualmente</small>}</div>
          <div className="filter-bar"><span>{day.willOpen?'Aberto':'Fechado'}</span>
            {canMaster&&<button className="outline-action"
              onClick={()=>changeDay(day,!day.willOpen)}>
              {day.willOpen?'Marcar sem expediente':'Marcar com expediente'}</button>}
          </div>
        </div>)}</div>
      {canMaster&&<div className="inline-panel">
        <h4>Registrar feriado ou fechamento não identificado</h4>
        <p className="helper">Escolha a data na lista acima e marque sem expediente. Você também pode escrever o motivo para a alteração.</p>
        <label className="field">Motivo / feriado informado pela ótica<input
          value={editing??''} onChange={e=>setEditing(e.target.value)}
          placeholder="Ex.: aniversário da cidade ou recesso"/></label>
      </div>}
    </div>
  </div>;
}
