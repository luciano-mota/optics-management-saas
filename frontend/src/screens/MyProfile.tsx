import { useState,type FormEvent } from 'react';
import { api } from '../api';
import { phoneDigits, phoneMask } from '../phone';
import { BrandLogo } from '../brand';
import type { CepAddress } from '../cep';
import { AddressFields } from './AddressFields';

type Person={email:string;full_name:string;phone?:string;cpf?:string;
 birth_date?:string|null;address?:string};
const digits=(s:string)=>s.replace(/\D/g,'');
function cnpjMask(s:string){
 const raw=s.toUpperCase().replace(/[^A-Z0-9]/g,'').slice(0,14);
 return raw.replace(/^([A-Z0-9]{2})([A-Z0-9])/,'$1.$2')
  .replace(/^([A-Z0-9]{2})\.([A-Z0-9]{3})([A-Z0-9])/,'$1.$2.$3')
  .replace(/\.([A-Z0-9]{3})([A-Z0-9])/g,'.$1/$2')
  .replace(/\/([A-Z0-9]{4})([0-9])/,'/$1-$2');
}
function cpfMask(s:string){
 const d=digits(s).slice(0,11);
 return d.replace(/^(\d{3})(\d)/,'$1.$2')
  .replace(/^(\d{3})\.(\d{3})(\d)/,'$1.$2.$3')
  .replace(/\.(\d{3})(\d)/,'.$1-$2');
}
export function MyProfile({person,required,requiredCnpj=false,opticCnpj='',opticAddress='',showPersonalAddress=true,onSaved,onLogout}:{
 person:Person;required:boolean;requiredCnpj?:boolean;opticCnpj?:string;opticAddress?:string;
 /** Master e operador LCM não informam endereço pessoal: só o endereço da ótica importa. */
 showPersonalAddress?:boolean;
 onSaved:()=>void;onLogout:()=>void;
}){
 const [name,setName]=useState(person.full_name||'');
 const [cpf,setCpf]=useState(cpfMask(person.cpf||''));
 const [phone,setPhone]=useState(phoneMask(person.phone||''));
 const [birth,setBirth]=useState(person.birth_date?.slice(0,10)||'');
 const [address,setAddress]=useState(person.address||'');
 const [cnpj,setCnpj]=useState(cnpjMask(opticCnpj));
 const [shopAddress,setShopAddress]=useState(opticAddress);
 const [shopPlace,setShopPlace]=useState<CepAddress|null>(null);
 const [saving,setSaving]=useState(false);
 const [error,setError]=useState('');
 const submit=async(event:FormEvent)=>{
  event.preventDefault();setSaving(true);setError('');
  try{
   await api('/auth/profile',{method:'PUT',body:{
    fullName:name.trim(),cpf:digits(cpf),phone:phoneDigits(phone),
    birthDate:birth,address:address.trim()||null,
    opticCnpj:requiredCnpj?cnpj.replace(/[^A-Z0-9]/g,''):null,
    // Cidade, UF e código IBGE vêm do CEP: o calendário de feriados se ajusta sozinho.
    opticAddress:requiredCnpj&&shopAddress?{address:shopAddress,
      city:shopPlace?.city||cityFrom(shopAddress),uf:shopPlace?.uf||ufFrom(shopAddress),
      ibge:shopPlace?.ibge||null}:null,
   }});
   onSaved();
  }catch(e){setError(e instanceof Error?e.message:'Não foi possível salvar seus dados.');}
  finally{setSaving(false);}
 };
 return <div className={required?'profile-onboarding-backdrop':'screen-stack'}>
  <section className={required?'profile-onboarding surface':'surface'}
    role={required?'dialog':undefined} aria-modal={required?true:undefined}
    aria-labelledby="profile-title">
   {required&&<div className="onboarding-brand"><BrandLogo size={36}/></div>}
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
    {requiredCnpj&&<>
      <p className="onboarding-section">Dados da ótica</p>
      <label className="field">CNPJ da ótica
        <input required autoComplete="off" value={cnpj}
          placeholder="00.000.000/0000-00" maxLength={18}
          onChange={e=>setCnpj(cnpjMask(e.target.value))}/>
      </label>
      <AddressFields name="opticAddress" legend="Endereço da ótica" required
        defaultValue={opticAddress} onChange={setShopAddress} onLocation={setShopPlace}/>
      <p className="helper span-all">Usamos a cidade da ótica para
        mostrar os feriados no calendário e calcular as metas. Você não precisa configurar mais nada.</p>
      <p className="onboarding-section">Seus dados</p>
    </>}
    <label className="field">Nome completo<input autoComplete="name" required
      maxLength={180} value={name} onChange={e=>setName(e.target.value)}/></label>
    <label className="field">CPF<input inputMode="numeric" autoComplete="off"
      placeholder="000.000.000-00" required value={cpf} maxLength={14}
      onChange={e=>setCpf(cpfMask(e.target.value))}/></label>
    <label className="field">Data de nascimento<input type="date" required value={birth}
      max={new Date().toISOString().slice(0,10)} onChange={e=>setBirth(e.target.value)}/></label>
    <label className="field">Telefone com DDD<input inputMode="tel" autoComplete="tel"
      required value={phone} placeholder="(98) 99999-9999"
      onChange={e=>setPhone(phoneMask(e.target.value))}/></label>
    {showPersonalAddress&&<AddressFields name="personalAddress" legend="Seu endereço" defaultValue={person.address||''}
      onChange={setAddress}/>}
    {error&&<p className="calendar-warning" role="alert">{error}</p>}
    <button className="primary-action form-action" disabled={saving}>
      {saving?'Salvando...':required?'Concluir e acessar o painel':'Salvar alterações'}</button>
   </form>
   {required&&<div className="profile-onboarding-footer"><p className="helper">
      Seus dados são utilizados para identificar o titular da conta. Não os compartilhamos com outras óticas.
    </p><button className="text-action" type="button" onClick={onLogout}>
      Sair e concluir depois</button></div>}
  </section>
 </div>;
}

/** Para endereços já salvos (sem nova busca de CEP): "... - Cidade/UF - CEP ...". */
function cityFrom(address:string){
 const match=address.match(/([^,-]+)\/([A-Z]{2})(?: - CEP|$)/);
 return match?match[1].trim():'';
}
function ufFrom(address:string){
 const match=address.match(/\/([A-Z]{2})(?: - CEP|$)/);
 return match?match[1]:'';
}
