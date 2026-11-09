import { useState } from 'react';
import { api } from '../api';
import type { ScreenProps } from './common';
import { FormField, getField, perform, useRemote } from './common';

const perms = [
  ['CUSTOMERS_READ','Consultar clientes'], ['CUSTOMERS_WRITE','Cadastrar clientes'],
  ['SALES_READ','Consultar vendas'], ['SALES_CREATE','Realizar vendas'],
  ['SALES_DELIVER','Confirmar entregas'], ['VISITS_READ','Ver visitas'],
  ['VISITS_MANAGE','Atender visitas'], ['FINANCE_READ','Consultar financeiro'],
  ['FINANCE_WRITE','Lançar receitas e despesas'], ['CATALOG_READ','Consultar catálogo'],
  ['CATALOG_WRITE','Editar catálogo'], ['REPORTS_READ','Emitir relatórios'],
  ['USERS_MANAGE','Gerenciar equipe'], ['SITE_MANAGE','Personalizar site'],
] as const;
export function Team({notify}:ScreenProps){
  const [inviteLink,setInviteLink]=useState('');
  const [selected,setSelected]=useState<any|null>(null);
  const {data,refresh}=useRemote<any[]>('/team');
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Minha equipe</h2>
      <p>Convide colaboradores por e-mail e ajuste o que cada pessoa pode acessar.</p></div></div>
    <div className="surface"><h3>Convidar funcionário</h3>
      <form className="form-grid" onSubmit={event=>{
        event.preventDefault();const form=event.currentTarget;
        void perform(async()=>{
          const result=await api<{inviteUrl:string}>('/team/invites',{method:'POST',body:{
            email:getField(form,'email'),role:getField(form,'role'),
          }});
          setInviteLink(result.inviteUrl);
        },notify,()=>form.reset());
      }}>
        <FormField name="email" title="E-mail corporativo ou pessoal" type="email" required />
        <label className="field">Perfil<select name="role" defaultValue="SELLER">
          <option value="SELLER">Vendedor(a)</option><option value="SECRETARY">Secretário(a)</option>
          <option value="MANAGER">Gerente</option><option value="MASTER">Administrador master</option>
        </select></label>
        <button className="primary-action form-action" type="submit">Gerar convite</button>
      </form>
      {inviteLink&&<div className="inline-panel">
        <strong>Convite de uso único — validade de 72 horas</strong>
        <p>Compartilhe este link com o funcionário pelo canal de sua preferência.</p>
        <div className="filter-bar"><input readOnly value={inviteLink} aria-label="Link do convite" />
          <button className="outline-action" onClick={()=>void navigator.clipboard.writeText(inviteLink)}>
            Copiar link</button></div>
      </div>}
    </div>
    <div className="surface"><h3>Funcionários cadastrados</h3>
      {!data?.length?<p className="empty">Nenhum funcionário cadastrado.</p>:
        <div className="list-cards">{data.map(person=><div className="mini-row" key={person.account_id}>
          <div><strong>{person.full_name}</strong><small>{person.email} · {person.role}</small></div>
          <button className="outline-action" onClick={()=>setSelected(person)}>Gerenciar acessos</button></div>)}</div>}
    </div>
    {selected&&<div className="surface">
      <div className="section-title"><h3>Acessos de {selected.full_name}</h3>
        <button className="outline-action" onClick={()=>setSelected(null)}>Fechar</button></div>
      <p className="helper">O acesso de administradores é completo. Nos demais perfis, conceder ou bloquear um acesso substitui o padrão.</p>
      <div className="permission-grid">{perms.map(([id,label])=><div className="mini-row" key={id}>
        <span>{label}</span><select aria-label={label} disabled={selected.role==='MASTER'}
          defaultValue="DEFAULT" onChange={e=>void perform(
            ()=>api('/team/'+selected.account_id+'/permission',
              {method:'PUT',body:{permission:id,effect:e.target.value}}),notify)}>
          <option value="DEFAULT">Padrão do perfil</option><option value="ALLOW">Permitir</option>
          <option value="DENY">Bloquear</option></select></div>)}</div>
      <div className="filter-bar"><label className="field">Perfil<select defaultValue={selected.role}
        onChange={e=>void perform(()=>api('/team/'+selected.account_id,
          {method:'PATCH',body:{role:e.target.value,active:selected.active}}),
          notify,refresh)}>
        <option value="MASTER">Administrador master</option><option value="MANAGER">Gerente</option>
        <option value="SECRETARY">Secretário(a)</option><option value="SELLER">Vendedor(a)</option>
      </select></label></div>
    </div>}
  </div>;
}
