import { useState } from 'react';
import { firstName, openWhatsapp } from '../whatsapp';
import { CalendarPlus, MessageCircle } from 'lucide-react';
import { api, date } from '../api';
import type { ScreenProps } from './common';
import { AddressFields } from './AddressFields';
import { FormField, getField, perform, useRemote, PhoneField } from './common';

const stage:Record<string,string> = {
  SCHEDULED:'Agendado',CONFIRMED:'Confirmado',COMPLETED:'Consulta realizada',
  NO_SHOW:'Não compareceu',CANCELLED:'Cancelado',
};
export function Optometry({notify,can}:ScreenProps) {
  const patients=useRemote<any[]>('/optometry/patients');
  const clinics=useRemote<any[]>('/optometry/clinics');
  const appointments=useRemote<any[]>('/optometry/appointments');
  const refresh=()=>{patients.refresh();clinics.refresh();appointments.refresh();};
  const [calendarOf,setCalendarOf]=useState<string|null>(null);
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Consultas de vista</h2>
      <p>Organize consultas nas clínicas parceiras. Pacientes não viram clientes até uma venda ser confirmada.</p></div></div>
    <div className="surface"><h3>Clínicas parceiras</h3>
      <div className="list-cards">{clinics.data?.map(clinic=><div key={clinic.id}>
        <div className="mini-row"><div><strong>{clinic.name}</strong><small>{clinic.phone||'Sem telefone'}</small></div>
          {can?.('PARTNER_CLINICS_MANAGE')&&<button className="text-action" onClick={()=>setCalendarOf(calendarOf===clinic.id?null:clinic.id)}>
            <CalendarPlus size={15}/> Agenda para a clínica</button>}</div>
        {calendarOf===clinic.id&&<ClinicCalendar clinicId={clinic.id} name={clinic.name} notify={notify}/>}
      </div>)}</div>
      {can?.('PARTNER_CLINICS_MANAGE')&&<form className="form-grid" onSubmit={e=>{
        e.preventDefault(); const form=e.currentTarget;
        void perform(()=>api('/optometry/clinics',{method:'POST',body:{
          name:getField(form,'name'),phone:getField(form,'phone'),address:getField(form,'address'),
        }}),notify,()=>{form.reset();refresh();});
      }}>
        <FormField name="name" title="Nome da clínica" required/>
        <PhoneField name="phone" title="Telefone da clínica"/>
        <AddressFields legend="Endereço da clínica"/>
        <button className="primary-action form-action">Adicionar clínica</button>
      </form>}
    </div>
    <div className="surface"><h3>Cadastro de pacientes</h3>
      <p className="helper">Esses registros são independentes dos clientes da ótica.</p>
      {can?.('PATIENTS_WRITE')&&<form className="form-grid" onSubmit={e=>{
        e.preventDefault();const form=e.currentTarget;
        void perform(()=>api('/optometry/patients',{method:'POST',body:{
          fullName:getField(form,'fullName'),cpf:getField(form,'cpf')||null,
          birthDate:getField(form,'birthDate')||null,phone:getField(form,'phone'),
          email:getField(form,'email')||null,address:getField(form,'address'),
          reminderOptIn:new FormData(form).get('reminderOptIn')==='on',
        }}),notify,()=>{form.reset();refresh();});
      }}>
        <FormField name="fullName" title="Nome do paciente" required/>
        <FormField name="cpf" title="CPF (opcional)"/>
        <FormField name="birthDate" title="Nascimento" type="date"/>
        <PhoneField name="phone" title="Telefone"/>
        <FormField name="email" title="E-mail" type="email"/>
        <label className="checkline span-all"><input type="checkbox" name="reminderOptIn"/>
          <span>O paciente autoriza receber lembrete da consulta por e-mail no dia anterior.</span></label>
        <AddressFields/>
        <button className="primary-action form-action">Cadastrar paciente</button>
      </form>}
      <div className="list-cards">{patients.data?.map(patient=><div className="mini-row" key={patient.id}>
        <div><strong>{patient.full_name}</strong>
          <small>{patient.phone||'Sem contato'} · {patient.converted?'Também é cliente':'Somente paciente'}
            {' · '}{patient.reminder_opt_in?'recebe lembrete por e-mail':'sem lembrete por e-mail'}</small></div>
        <div className="row-actions">
          {can?.('PATIENTS_WRITE')&&patient.email&&<button className="text-action" onClick={()=>void perform(()=>api(
            '/optometry/patients/'+patient.id+'/reminder',{method:'PUT',body:{email:patient.email,reminderOptIn:!patient.reminder_opt_in}}),
            notify,refresh)}>{patient.reminder_opt_in?'Parar lembretes':'Ativar lembretes'}</button>}
          <span>{patient.converted?'Cliente':'Paciente'}</span></div>
      </div>)}</div>
    </div>
    <div className="surface"><h3>Agendar consulta de vista</h3>
      <p className="helper">Confirme os horários diretamente com a clínica parceira. Ela pode acompanhar pelo link de agenda (em Clínicas parceiras → Agenda para a clínica).</p>
      {can?.('APPOINTMENTS_MANAGE')&&<form className="form-grid" onSubmit={e=>{
        e.preventDefault();const form=e.currentTarget;
        void perform(()=>api('/optometry/appointments',{method:'POST',body:{
          patientId:getField(form,'patientId'),clinicId:getField(form,'clinicId'),
          startsAt:new Date(getField(form,'startsAt')).toISOString(),
          endsAt:new Date(getField(form,'endsAt')).toISOString(),
          notes:getField(form,'notes'),
        }}),notify,()=>{form.reset();refresh();});
      }}>
        <label className="field">Paciente<select name="patientId" required defaultValue="">
          <option value="" disabled>Selecione o paciente</option>
          {patients.data?.map(p=><option key={p.id} value={p.id}>{p.full_name}</option>)}
        </select></label>
        <label className="field">Clínica parceira<select name="clinicId" required defaultValue="">
          <option value="" disabled>Selecione a clínica</option>
          {clinics.data?.filter(c=>c.active).map(c=><option key={c.id} value={c.id}>{c.name}</option>)}
        </select></label>
        <FormField name="startsAt" title="Início da consulta" type="datetime-local" required/>
        <FormField name="endsAt" title="Fim da consulta" type="datetime-local" required/>
        <FormField name="notes" title="Observações administrativas"/>
        <button className="primary-action form-action">Agendar</button>
      </form>}
      <h3>Próximas consultas e histórico</h3>
      {!appointments.data?.length?<p className="empty">Ainda não há consultas agendadas.</p>:
        <div className="list-cards">{appointments.data.map(a=><div className="mini-row" key={a.id}>
          <div><strong>{a.patient_name} · {a.clinic_name}</strong>
            <small>{date(a.starts_at)} {new Date(a.starts_at).toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit'})}
              {' · '}{stage[a.status]||a.status}</small>
            {['SCHEDULED','CONFIRMED'].includes(a.status)&&a.patient_phone&&new Date(a.starts_at)>new Date()&&
              <button className="text-action whatsapp-action" onClick={()=>openWhatsapp(a.patient_phone,
                'Olá, '+firstName(a.patient_name)+'! Passando para lembrar da sua consulta de vista em '+date(a.starts_at)
                +' às '+new Date(a.starts_at).toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit'})+', na '+a.clinic_name
                +'. Podemos confirmar sua presença?',notify)}><MessageCircle size={14}/> Lembrar pelo WhatsApp</button>}</div>
          {can?.('APPOINTMENTS_MANAGE')&&['SCHEDULED','CONFIRMED'].includes(a.status)&&
          <div className="filter-bar">{[
            ...(a.status==='SCHEDULED'?['CONFIRMED']:[]),
            'COMPLETED','NO_SHOW','CANCELLED'
          ].map(value=><button className="outline-action" key={value}
            onClick={()=>void perform(()=>api('/optometry/appointments/'+a.id+'/status',{
              method:'PATCH',body:{status:value},
            }),notify,refresh)}>{stage[value]}</button>)}</div>}
        </div>)}</div>}
    </div>
  </div>;
}

/** Link de calendário que a clínica assina no Google Agenda ou Outlook (só as consultas dela, nome curto do paciente). */
function ClinicCalendar({clinicId,name,notify}:{clinicId:string;name:string;notify:ScreenProps['notify']}){
  const status=useRemote<{active:boolean;createdAt?:string;lastAccessAt?:string}>('/optometry/clinics/'+clinicId+'/calendar-feed');
  const [link,setLink]=useState<{url:string;webcal:string}|null>(null);
  const generate=()=>void perform(async()=>{setLink(await api('/optometry/clinics/'+clinicId+'/calendar-feed',{method:'POST'}));},
    notify,status.refresh);
  return <div className="inline-panel">
    <h4>Agenda de consultas para a {name}</h4>
    <p className="helper">A clínica assina este link no Google Agenda (Outras agendas → Do URL) ou no Outlook e vê as consultas
      marcadas por vocês, atualizadas sozinhas. Aparece só o primeiro nome e a inicial do paciente: sem telefone, CPF ou e-mail.</p>
    {link?<>
      <div className="copy-row"><input readOnly value={link.url} onFocus={e=>e.currentTarget.select()}/>
        <button className="outline-action" onClick={()=>void navigator.clipboard?.writeText(link.url).then(()=>notify('Link copiado.'))}>Copiar</button></div>
      <p className="helper">Guarde e envie para a clínica agora: por segurança, o link não aparece de novo. Gerar outro desativa este.</p>
    </>:<p className="helper">{status.data?.active?'Há um link ativo'+(status.data.lastAccessAt&&status.data.lastAccessAt!=='null'
      ?' (último acesso da clínica: '+new Date(status.data.lastAccessAt).toLocaleString('pt-BR')+')':' (a clínica ainda não acessou)')+'.'
      :'Nenhum link ativo.'}</p>}
    <div className="filter-bar">
      <button className="primary-action" onClick={generate}>{status.data?.active?'Gerar novo link':'Gerar link da agenda'}</button>
      {status.data?.active&&<button className="text-action danger" onClick={()=>void perform(()=>api(
        '/optometry/clinics/'+clinicId+'/calendar-feed/revoke',{method:'POST'}),notify,()=>{setLink(null);status.refresh();})}>Desativar link</button>}
    </div>
  </div>;
}
