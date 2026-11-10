import { useState,type FormEvent } from 'react';
import { api } from '../api';

type Person={email:string;full_name:string;phone?:string;cpf?:string;
 birth_date?:string|null;address?:string};
const digits=(s:string)=>s.replace(/\D/g,'');
function cpfMask(s:string){
 const d=digits(s).slice(0,11);
 return d.replace(/^(\d{3})(\d)/,'$1.$2')
  .replace(/^(\d{3})\.(\d{3})(\d)/,'$1.$2.$3')
  .replace(/\.(\d{3})(\d)/,'.$1-$2');
}
function phoneMask(s:string){
 const d=digits(s).slice(0,11);
 if(d.length<=2)return d;
 if(d.length<=6)return '('+d.slice(0,2)+') '+d.slice(2);
 if(d.length<=10)return '('+d.slice(0,2)+') '+d.slice(2,6)+'-'+d.slice(6);
 return '('+d.slice(0,2)+') '+d.slice(2,7)+'-'+d.slice(7);
}
export function MyProfile({person,required,onSaved,onLogout}:{
 person:Person;required:boolean;onSaved:()=>void;onLogout:()=>void;
}){
 const [name,setName]=useState(person.full_name||'');
 const [cpf,setCpf]=useState(cpfMask(person.cpf||''));
 const [phone,setPhone]=useState(phoneMask(person.phone||''));
 const [birth,setBirth]=useState(person.birth_date?.slice(0,10)||'');
 const [address,setAddress]=useState(person.address||'');
 const [saving,setSaving]=useState(false);
 const [error,setError]=useState('');
 const submit=async(event:FormEvent)=>{
  event.preventDefault();setSaving(true);setError('');
  try{
   await api('/auth/profile',{method:'PUT',body:{
    fullName:name.trim(),cpf:digits(cpf),phone:digits(phone),
    birthDate:birth,address:address.trim()||null,
   }});
   onSaved();
  }catch(e){setError(e instanceof Error?e.message:'Não foi possível salvar seus dados.');}
  finally{setSaving(false);}
 };
 return <div className={required?'profile-onboarding-backdrop':'screen-stack'}>
  <section className={required?'profile-onboarding surface':'surface'}
    role={required?'dialog':undefined} aria-modal={required?true:undefined}
    aria-labelledby="profile-title">
   <div className="section-title"><div>
    <span className="eyebrow">{required?'Primeiro acesso ao Optics Management':'Minha conta'}</span>
    <h2 id="profile-title">{required?'Conclua seu cadastro':'Meus dados pessoais'}</h2>
    <p>{required?'Antes de começar, confirme seus dados pessoais. Leva apenas alguns instantes.':
       'Atualize seus dados e mantenha seu cadastro correto.'}</p>
   </div></div>
   <div className="inline-panel">
    <strong>E-mail vinculado à sua conta</strong>
    <p>{person.email}</p>
    <small>O e-mail já foi confirmado no cadastro ou no convite e não é alterado aqui.</small>
   </div>
   <form className="form-grid" onSubmit={e=>void submit(e)}>
    <label className="field">Nome completo<input autoComplete="name" required
      maxLength={180} value={name} onChange={e=>setName(e.target.value)}/></label>
    <label className="field">CPF<input inputMode="numeric" autoComplete="off"
      placeholder="000.000.000-00" required value={cpf} maxLength={14}
      onChange={e=>setCpf(cpfMask(e.target.value))}/></label>
    <label className="field">Data de nascimento<input type="date" required value={birth}
      max={new Date().toISOString().slice(0,10)} onChange={e=>setBirth(e.target.value)}/></label>
    <label className="field">Telefone com DDD<input inputMode="tel" autoComplete="tel"
      required maxLength={16} value={phone} placeholder="(98) 99999-9999"
      onChange={e=>setPhone(phoneMask(e.target.value))}/></label>
    <label className="field">Endereço (opcional)<input maxLength={1000} value={address}
      autoComplete="street-address" onChange={e=>setAddress(e.target.value)}/></label>
    {error&&<p className="calendar-warning" role="alert">{error}</p>}
    <button className="primary-action form-action" disabled={saving}>
      {saving?'Salvando...':required?'Concluir e acessar o painel':'Salvar alterações'}</button>
   </form>
   {required&&<div className="profile-onboarding-footer"><p className="helper">
      Seus dados são utilizados para identificar o titular da conta. Não os compartilhamos com outras óticas.
      O endereço residencial é opcional.
    </p><button className="text-action" type="button" onClick={onLogout}>
      Sair e concluir depois</button></div>}
  </section>
 </div>;
}
