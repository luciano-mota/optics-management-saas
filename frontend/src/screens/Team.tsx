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
  ['RECEIPTS_READ','Consultar recibos'],['RECEIPTS_ISSUE','Emitir recibos'],
  ['USERS_MANAGE','Gerenciar equipe'], ['SITE_MANAGE','Personalizar site'],
] as const;
const roleDefaults: Record<string,string[]> = {
  "SELLER": [
    "CUSTOMERS_READ",
    "CUSTOMERS_WRITE",
    "SALES_READ",
    "SALES_CREATE",
    "SALES_DELIVER",
    "VISITS_READ",
    "VISITS_MANAGE",
    "CATALOG_READ",
    "REPORTS_READ",
    "RECEIPTS_READ",
    "RECEIPTS_ISSUE"
  ],
  "SECRETARY": [
    "CUSTOMERS_READ",
    "CUSTOMERS_WRITE",
    "SALES_READ",
    "SALES_DELIVER",
    "VISITS_READ",
    "VISITS_MANAGE",
    "CATALOG_READ",
    "REPORTS_READ",
    "RECEIPTS_READ",
    "RECEIPTS_ISSUE"
  ],
  "MANAGER": [
    "CUSTOMERS_READ",
    "CUSTOMERS_WRITE",
    "SALES_READ",
    "SALES_CREATE",
    "SALES_DELIVER",
    "VISITS_READ",
    "VISITS_MANAGE",
    "FINANCE_READ",
    "FINANCE_WRITE",
    "CATALOG_READ",
    "CATALOG_WRITE",
    "REPORTS_READ",
    "RECEIPTS_READ",
    "RECEIPTS_ISSUE"
  ],
  "MASTER": [
    "CUSTOMERS_READ",
    "CUSTOMERS_WRITE",
    "SALES_READ",
    "SALES_CREATE",
    "SALES_DELIVER",
    "VISITS_READ",
    "VISITS_MANAGE",
    "FINANCE_READ",
    "FINANCE_WRITE",
    "CATALOG_READ",
    "CATALOG_WRITE",
    "REPORTS_READ",
    "USERS_MANAGE",
    "SITE_MANAGE"
  ]
};

export function Team({notify}:ScreenProps){
  const [inviteLink,setInviteLink]=useState('');
  const [inviteRole,setInviteRole]=useState('SELLER');
  const [invitePermissions,setInvitePermissions]=useState<string[]>(roleDefaults.SELLER);
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
            email:getField(form,'email'),role:inviteRole,
            permissions:inviteRole==='MASTER'?null:invitePermissions,
          }});
          setInviteLink(result.inviteUrl);
        },notify,()=>{form.reset();setInviteRole('SELLER');setInvitePermissions(roleDefaults.SELLER);});
      }}>
        <FormField name="email" title="E-mail corporativo ou pessoal" type="email" required />
        <label className="field">Perfil<select name="role" value={inviteRole} onChange={event=>{
          const role=event.target.value;setInviteRole(role);setInvitePermissions(roleDefaults[role]||[]);
        }}>
          <option value="SELLER">Vendedor(a)</option><option value="SECRETARY">Secretário(a)</option>
          <option value="MANAGER">Gerente</option><option value="MASTER">Administrador master</option>
        </select></label>
        <fieldset className="invite-permissions"><legend>Acessos ao enviar convite</legend>
          <p className="helper">Selecione quais áreas o funcionário poderá usar. O perfil Master tem acesso completo.</p>
          <div className="permission-selection">{perms.map(([id,label])=>
            <label key={id} className="checkline">
              <input type="checkbox" disabled={inviteRole==='MASTER'}
                checked={inviteRole==='MASTER'||invitePermissions.includes(id)}
                onChange={event=>setInvitePermissions(current=>event.target.checked?
                  [...current,id]:current.filter(permission=>permission!==id))}/>
              {label}
            </label>)}</div>
        </fieldset>
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
