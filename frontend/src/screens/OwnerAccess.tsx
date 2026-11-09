import { useState } from 'react';
import { api } from '../api';
import { getField, perform } from './common';

type Toast = {message:string;error:boolean}|null;
type Notice = (message:string,error?:boolean)=>void;

export function OwnerInviteRequest({notify,toast,onBack}:{
  notify:Notice;toast:Toast;onBack:()=>void;
}) {
  const [sent,setSent]=useState(false);
  return <div className="auth-layout"><section className="auth-box">
    <h1>Administração LCM Dev Tech</h1>
    <p>Este acesso é exclusivo do proprietário da plataforma. Informe o e-mail
      autorizado para receber um link de cadastro seguro, válido por 30 minutos.</p>
    {toast&&<div className={toast.error?'toast error':'toast'} role="status">{toast.message}</div>}
    {sent?<div className="inline-panel"><strong>Solicitação recebida.</strong>
      <p>Se o e-mail corresponde ao operador autorizado, um link de ativação foi enviado.
        Confira também a pasta de spam.</p></div>:
      <form className="auth-form" onSubmit={e=>{
        e.preventDefault();const form=e.currentTarget;
        void perform(()=>api('/public/platform-owner/request',{method:'POST',
          body:{email:getField(form,'email')}}),notify,()=>setSent(true));
      }}>
        <label className="field">E-mail do operador
          <input type="email" name="email" autoComplete="email" required/></label>
        <button className="primary-action wide" type="submit">Enviar meu link de acesso</button>
      </form>}
    <button className="text-action" onClick={onBack}>Voltar ao login</button>
  </section></div>;
}

export function OwnerInviteComplete({token,notify,toast}:{
  token:string;notify:Notice;toast:Toast;
}) {
  const [created,setCreated]=useState(false);
  return <div className="auth-layout"><section className="auth-box">
    <h1>Ativar administração da plataforma</h1>
    <p>Este convite foi enviado ao e-mail autorizado da LCM Dev Tech.
      Defina seus dados para gerenciar as óticas cadastradas.</p>
    {toast&&<div className={toast.error?'toast error':'toast'} role="status">{toast.message}</div>}
    {created?<div className="inline-panel"><strong>Acesso ativado!</strong>
      <p>Entre com o e-mail que recebeu o convite e a senha escolhida.</p>
      <a className="primary-action" href="/">Acessar login</a>
    </div>:<form className="auth-form" onSubmit={e=>{
      e.preventDefault();const form=e.currentTarget;
      void perform(()=>api('/public/platform-owner/complete',{method:'POST',body:{
        token,fullName:getField(form,'fullName'),password:getField(form,'password'),
        acceptedTerms:new FormData(form).get('acceptedTerms')==='on',
      }}),notify,()=>setCreated(true));
    }}>
      <label className="field">Nome completo
        <input name="fullName" autoComplete="name" required/></label>
      <label className="field">Senha (letras e números, pelo menos 10 caracteres)
        <input name="password" type="password" autoComplete="new-password"
          minLength={10} maxLength={128} required/></label>
      <label className="checkline"><input type="checkbox" name="acceptedTerms" required/>
        Li e concordo com os termos de uso e privacidade.</label>
      <button className="primary-action wide">Ativar meu painel LCM</button>
    </form>}
  </section></div>;
}
