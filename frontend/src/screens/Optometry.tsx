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
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Consultas de vista</h2>
      <p>Organize consultas nas clínicas parceiras. Pacientes não viram clientes até uma venda ser confirmada.</p></div></div>
    <div className="surface"><h3>Clínicas parceiras</h3>
      <div className="list-cards">{clinics.data?.map(clinic=><div className="mini-row" key={clinic.id}>
        <strong>{clinic.name}</strong><span>{clinic.phone||'Sem telefone'}</span></div>)}</div>
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
          email:getField(form,'email'),address:getField(form,'address'),
        }}),notify,()=>{form.reset();refresh();});
      }}>
        <FormField name="fullName" title="Nome do paciente" required/>
        <FormField name="cpf" title="CPF (opcional)"/>
        <FormField name="birthDate" title="Nascimento" type="date"/>
        <PhoneField name="phone" title="Telefone"/>
        <FormField name="email" title="E-mail" type="email"/>
        <AddressFields/>
        <button className="primary-action form-action">Cadastrar paciente</button>
      </form>}
      <div className="list-cards">{patients.data?.map(patient=><div className="mini-row" key={patient.id}>
        <div><strong>{patient.full_name}</strong>
          <small>{patient.phone||'Sem contato'} · {patient.converted?'Também é cliente':'Somente paciente'}</small></div>
        <span>{patient.converted?'Cliente':'Paciente'}</span>
      </div>)}</div>
    </div>
    <div className="surface"><h3>Agendar consulta de vista</h3>
      <p className="helper">Confirme os horários diretamente com a clínica parceira. Este calendário não se conecta automaticamente à agenda da clínica.</p>
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
            <small>{date(a.starts_at)} · {stage[a.status]||a.status}</small></div>
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
