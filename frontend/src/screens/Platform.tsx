import { useState } from 'react';
import { api, currency, date } from '../api';
import type { ScreenProps } from './common';
import { getField, perform, useRemote } from './common';

const modeLabel:Record<string,string>={TRIAL:'Teste grátis',PAID:'Assinante',COMPLIMENTARY:'Cortesia'};
export function Platform({notify}:ScreenProps){
  const shops=useRemote<any[]>('/platform/optics');
  const stats=useRemote<any>('/platform/dashboard');
  const notices=useRemote<any[]>('/platform/notices');
  const [selected,setSelected]=useState<any|null>(null);
  const reload=()=>{shops.refresh();stats.refresh();notices.refresh();};
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Administração da plataforma</h2>
      <p>Gestão de óticas, mensalidades, cortesias, páginas públicas e avisos.</p></div></div>
    <div className="metric-grid">
      {[['Óticas cadastradas',stats.data?.total],['Testando',stats.data?.trial],
        ['Cortesias',stats.data?.complimentary],['Sites habilitados',stats.data?.sites_enabled]].map(
        ([title,count])=><div className="metric" key={title}><span>{title}</span><strong>{count??'—'}</strong></div>)}
    </div>
    <div className="surface"><h3>Óticas da plataforma</h3>
      {!shops.data?.length?<p className="empty">Nenhuma ótica cadastrada.</p>:
        <div className="table-wrap"><table><thead><tr><th>Ótica</th><th>Contato</th>
          <th>Assinatura</th><th>Site</th><th>Ação</th></tr></thead><tbody>
          {shops.data.map(shop=><tr key={shop.id}>
            <td><strong>{shop.trade_name}</strong><small>{shop.slug}</small></td>
            <td>{shop.contact_email}<small>{shop.whatsapp||shop.contact_phone||'Sem telefone'}</small></td>
            <td>{modeLabel[shop.billing_mode]}<small>{shop.billing_mode==='TRIAL'?
              'Até '+date(shop.trial_ends_at):currency(shop.monthly_price)}</small></td>
            <td>{shop.site_enabled?<a href={'/?site='+shop.slug} target="_blank"
              rel="noreferrer">Ver site</a>:'Desabilitado'}</td>
            <td><button className="text-action" onClick={()=>setSelected(shop)}>Gerenciar</button></td>
          </tr>)}</tbody></table></div>}
    </div>
    {selected&&<div className="surface"><div className="section-title"><h3>{selected.trade_name}</h3>
      <button className="outline-action" onClick={()=>setSelected(null)}>Fechar</button></div>
      <p><strong>Contato:</strong> {selected.contact_email} / {selected.whatsapp}</p>
      <form className="form-grid" key={selected.id} onSubmit={event=>{
        event.preventDefault();const form=event.currentTarget;
        void perform(()=>api('/platform/optics/'+selected.id,{method:'PATCH',body:{
          billingMode:getField(form,'billingMode'),monthlyPrice:getField(form,'monthlyPrice')||null,
          siteEnabled:new FormData(form).get('siteEnabled')==='on',
          homeVisitsEnabled:new FormData(form).get('homeVisitsEnabled')==='on',
          active:new FormData(form).get('active')==='on',
        }}),notify,()=>{reload();setSelected(null);});
      }}>
        <label className="field">Situação da assinatura<select name="billingMode" defaultValue={selected.billing_mode}>
          <option value="TRIAL">Teste grátis</option><option value="COMPLIMENTARY">Cortesia ilimitada</option>
          <option value="PAID">Assinatura paga</option></select></label>
        <label className="field">Mensalidade (R$)<input name="monthlyPrice" type="number" step="0.01"
          defaultValue={selected.monthly_price||''} /></label>
        <label className="checkline"><input name="siteEnabled" type="checkbox"
          defaultChecked={selected.site_enabled} /> Liberar site e campanhas</label>
        <label className="checkline"><input name="homeVisitsEnabled" type="checkbox"
          defaultChecked={selected.home_visits_enabled} />
          Campanha exclusiva: permitir atendimento em domicílio neste site
        </label>
        <p className="helper">Desativado por padrão. Habilite somente para a ótica que oferece
          visitas em casa. As outras exibem uma página comercial comum, sem formulário de visita.</p>
        <label className="checkline"><input name="active" type="checkbox"
          defaultChecked={selected.active} /> Ótica ativa</label>
        <button className="primary-action form-action" type="submit">Salvar configurações</button>
      </form>
    </div>}
    <div className="surface"><h3>Enviar avisos para óticas</h3>
      <form className="form-grid" onSubmit={event=>{
        event.preventDefault();const form=event.currentTarget;
        void perform(()=>api('/platform/notices',{method:'POST',body:{
          opticId:getField(form,'opticId')||null,subject:getField(form,'subject'),
          message:getField(form,'message'),
        }}),notify,()=>{form.reset();reload();});
      }}>
        <label className="field">Destinatário<select name="opticId" defaultValue="">
          <option value="">Todas as óticas</option>{shops.data?.map(shop=>
            <option key={shop.id} value={shop.id}>{shop.trade_name}</option>)}
        </select></label>
        <label className="field">Assunto<input name="subject" required /></label>
        <label className="field">Mensagem<textarea name="message" required rows={4}/></label>
        <button className="primary-action form-action" type="submit">Enviar aviso interno</button>
      </form>
      <h4>Últimos avisos</h4>
      <div className="list-cards">{notices.data?.map(notice=><div className="mini-row" key={notice.id}>
        <div><strong>{notice.subject}</strong><small>{notice.message}</small></div>
        <span>{date(notice.created_at)}</span></div>)}</div>
    </div>
  </div>;
}
