import { useState } from 'react';
import { api } from '../api';
import type { ScreenProps } from './common';
import { getField, perform, useRemote } from './common';

export function Site({notify}:ScreenProps){
  const {data,refresh}=useRemote<any>('/site-settings');
  const [primary,setPrimary]=useState('#116b6d');
  const [secondary,setSecondary]=useState('#f3faf8');
  const link=window.location.origin+'/?site='+encodeURIComponent(data?.slug||'');
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Site e campanhas</h2>
      <p>Personalize sua página pública para receber pedidos de visita.</p></div></div>
    <div className="surface">
      {data==null?<p className="empty">Este recurso ainda não foi habilitado pela LCM Dev Tech.</p>:<>
        <div className="inline-panel"><strong>Seu site está habilitado</strong><p>
          <a href={link} target="_blank" rel="noreferrer">{link}</a></p></div>
        <form className="form-grid" key={data.slug} onSubmit={event=>{
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
