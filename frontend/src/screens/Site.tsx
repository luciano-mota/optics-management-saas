import { useEffect, useState } from 'react';
import { api, fileAsBase64 } from '../api';
import type { ScreenProps } from './common';
import { getField, perform, useRemote } from './common';

export function Site({notify}:ScreenProps){
  const {data,refresh}=useRemote<any>('/site-settings');
  const [primary,setPrimary]=useState('#5b4cf0');
  const [secondary,setSecondary]=useState('#f6f6fa');
  useEffect(()=>{
    if(data?.primary_color)setPrimary(data.primary_color);
    if(data?.secondary_color)setSecondary(data.secondary_color);
  },[data?.primary_color,data?.secondary_color]);
  const link=window.location.origin+'/?site='+encodeURIComponent(data?.slug||'');
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Site e campanhas</h2>
      <p>Personalize seu site institucional, sua vitrine e suas campanhas.</p></div></div>
    <div className="surface">
      {data==null?<div className="inline-panel">
        <span className="eyebrow">Recurso exclusivo liberado pela LCM Dev Tech</span>
        <h3>Seu site e suas campanhas ainda não estão habilitados</h3>
        <p>Você poderá criar uma página da sua ótica, personalizar a identidade visual,
          divulgar seus produtos e campanhas, além de facilitar o contato com seus clientes.
          A ativação é feita somente pela administração do Optics Management.</p>
        <p><strong>Para solicitar acesso:</strong> entre em contato com a equipe LCM Dev Tech.</p>
        <div className="filter-bar">
          <a className="outline-action" href="mailto:contato@lcmtech.com.br?subject=Ativacao%20do%20site%20Optics%20Management">
            Solicitar liberação por e-mail</a>
          <a className="outline-action" target="_blank" rel="noopener noreferrer"
            href="https://wa.me/5598920026493?text=Ol%C3%A1%2C%20quero%20solicitar%20a%20ativa%C3%A7%C3%A3o%20do%20site%20da%20minha%20%C3%B3tica%20no%20Optics%20Management.">
            Solicitar pelo WhatsApp</a>
        </div>
      </div>:<>
        <div className="inline-panel"><strong>Seu site está habilitado</strong><p>
          <a href={link} target="_blank" rel="noreferrer">{link}</a></p></div>
        {data.home_visits_enabled&&<div className="inline-panel">
          <strong>Campanha especial: atendimento em domicílio</strong>
          <p>Essa modalidade foi ativada especificamente para sua ótica pela LCM Dev Tech.
            O site exibirá o formulário de solicitação de visita com receita anexada.</p>
        </div>}
        <div className="inline-panel">
          <h4>Logo da ótica</h4>
          <p>Envie PNG ou JPG com até 2 MB. A paleta de cores será sugerida com base na logo e ajustada para leitura.</p>
          {data.has_logo&&<img src={'/api/v1/public/site/'+encodeURIComponent(data.slug)+'/logo'}
            alt="Logo cadastrada da ótica" style={{maxWidth:180,maxHeight:120,objectFit:'contain'}}/>}
          <input type="file" accept="image/png,image/jpeg" aria-label="Enviar logo"
            onChange={event=>{
              const file=event.target.files?.[0];if(!file)return;
              void perform(async()=>{
                if(file.size>2*1024*1024)throw new Error('O arquivo deve ter até 2 MB.');
                const dataBase64=await fileAsBase64(file);
                await api('/site-settings/logo',{method:'POST',body:{mime:file.type,dataBase64}});
              },notify,refresh);
            }}/>
        </div>
        <form className="form-grid" key={data.slug+'-'+data.primary_color} onSubmit={event=>{
          event.preventDefault();const form=event.currentTarget;
          void perform(()=>api('/site-settings',{method:'PUT',body:{
            primaryColor:getField(form,'primaryColor'),
            secondaryColor:getField(form,'secondaryColor'),
            font:getField(form,'font'),template:getField(form,'template'),
            campaignTitle:getField(form,'campaignTitle'),
            campaignDescription:getField(form,'campaignDescription'),
          }}),notify,refresh);
        }}>
          <label className="field">Cor principal<input name="primaryColor" type="color"
            defaultValue={data.primary_color||primary} onChange={e=>setPrimary(e.target.value)} /></label>
          <label className="field">Cor secundária<input name="secondaryColor" type="color"
            defaultValue={data.secondary_color||secondary} onChange={e=>setSecondary(e.target.value)} /></label>
          <label className="field">Fonte<select name="font" defaultValue={data.font}>
            <option>Manrope</option><option>DM Sans</option><option>Arial</option><option>Georgia</option>
          </select></label>
          <label className="field">Modelo<select name="template" defaultValue={data.template}>
            <option value="classic">Clássico</option><option value="modern">Moderno</option>
            <option value="compact">Compacto</option></select></label>
          <label className="field">Título da campanha<input name="campaignTitle"
            defaultValue={data.campaign_title} required /></label>
          <label className="field">Descrição da campanha<textarea name="campaignDescription" rows={3}
            defaultValue={data.campaign_description} /></label>
          <button className="primary-action form-action" type="submit">Salvar personalização</button>
        </form>
        <div className="site-preview" style={{background:primary,color:secondary}}>
          <strong>Prévia de cores</strong><p>Seu site se adapta à identidade da sua ótica.</p>
        </div>
      </>}
    </div>
  </div>;
}
