import { useEffect, useState, type CSSProperties } from 'react';
import {
  ArrowLeft, ArrowRight, Bell, CalendarDays, ChartColumnIncreasing,
  CircleHelp, ClipboardCheck, CreditCard, Glasses, House, LogOut,
  MapPinned, Menu, Palette, ShieldCheck,
  ShoppingBag, UsersRound, Wallet, X,
} from 'lucide-react';
import { api, csrf, date, fileAsBase64 } from './api';
import { Catalog } from './screens/Catalog';
import { Customers } from './screens/Customers';
import { Sales } from './screens/Sales';
import { Finance } from './screens/Finance';
import { Machines } from './screens/Machines';
import { Visits } from './screens/Visits';
import { Reports } from './screens/Reports';
import { Team } from './screens/Team';
import { Site } from './screens/Site';
import { Platform } from './screens/Platform';
import { getField, perform, useRemote } from './screens/common';

type Tile = {key:string;title:string;description:string;permission:string;icon:typeof Glasses;accent:string};
const tiles:Tile[]=[
  {key:'sales',title:'Vendas e entregas',description:'Registre novas vendas e confirme as entregas.',permission:'SALES_READ',icon:ShoppingBag,accent:'mint'},
  {key:'customers',title:'Meus clientes',description:'Cadastros, receitas e retorno para novos óculos.',permission:'CUSTOMERS_READ',icon:UsersRound,accent:'blue'},
  {key:'visits',title:'Visitas em domicílio',description:'Assuma pedidos, confirme visitas e feche vendas.',permission:'VISITS_READ',icon:MapPinned,accent:'orange'},
  {key:'finance',title:'Meu financeiro',description:'Entradas, despesas, caixa e contas a receber.',permission:'FINANCE_READ',icon:Wallet,accent:'green'},
  {key:'catalog',title:'Lentes e armações',description:'Cadastre marcas e modelos com facilidade.',permission:'CATALOG_READ',icon:Glasses,accent:'pink'},
  {key:'machines',title:'Maquininhas',description:'Configure taxas de débito e crédito.',permission:'FINANCE_READ',icon:CreditCard,accent:'orange'},
  {key:'reports',title:'Relatórios',description:'Resultados por funcionário, vendas e visitas.',permission:'REPORTS_READ',icon:ChartColumnIncreasing,accent:'purple'},
  {key:'team',title:'Minha equipe',description:'Envie convites e gerencie permissões.',permission:'USERS_MANAGE',icon:ShieldCheck,accent:'blue'},
  {key:'site',title:'Meu site e campanhas',description:'Personalize sua página de visitas.',permission:'SITE_MANAGE',icon:Palette,accent:'mint'},
];
type Toast = {message:string;error:boolean}|null;

export function App(){
  const params=new URLSearchParams(window.location.search);
  const site=params.get('site');
  const invite=params.get('invite');
  const [me,setMe]=useState<any|null>(null);
  const [ready,setReady]=useState(false);
  const [view,setView]=useState('dashboard');
  const [toast,setToast]=useState<Toast>(null);
  const [menuOpen,setMenuOpen]=useState(false);
  const notify=(message:string,error=false)=>{setToast({message,error});};
  const load=async()=>{
    try {setMe(await api('/auth/me'));} catch {setMe(null);}
    finally {setReady(true);}
  };
  useEffect(()=>{if(!site&&!invite)void load();},[site,invite]);
  if(site)return <PublicSite slug={site} notify={notify} toast={toast}/>;
  if(invite)return <InviteAccept token={invite} notify={notify} toast={toast}/>;
  if(!ready)return <div className="center-message">Abrindo o Optics Management...</div>;
  if(!me)return <AccessScreen onSignedIn={()=>void load()} notify={notify} toast={toast}/>;
  const activeShop=me.shops?.find((shop:any)=>shop.id===me.activeOpticId);
  const can=(permission:string)=>me.permissions?.includes(permission);
  const modules=tiles.filter(tile=>can(tile.permission)&&
    (tile.key!=='site'||activeShop?.site_enabled));
  const selected=modules.find(tile=>tile.key===view);
  const isPlatform=view==='platform'&&me.platformOperator;
  const countLabel=(count:any)=>count==null?'—':String(count);
  return <div className="app-shell">
    <header className="app-header"><div className="header-inner">
      <button className="brand" type="button" onClick={()=>setView('dashboard')} aria-label="Voltar ao início">
        <span className="brand-mark"><Glasses size={24}/></span>
        <span className="brand-words"><strong>Optics</strong><small>MANAGEMENT</small></span></button>
      <nav className={menuOpen?'header-nav open':'header-nav'} aria-label="Navegação principal">
        <button className={view==='dashboard'?'nav-link active':'nav-link'}
          onClick={()=>{setView('dashboard');setMenuOpen(false);}}>Início</button>
        {modules.slice(0,3).map(tile=><button key={tile.key} className={view===tile.key?'nav-link active':'nav-link'}
          onClick={()=>{setView(tile.key);setMenuOpen(false);}}>{tile.title}</button>)}
      </nav>
      <div className="header-actions">
        {me.platformOperator&&<button className="outline-action" onClick={()=>setView('platform')}>Painel LCM</button>}
        <button className="icon-button" title="Notificações" aria-label="Notificações"
          onClick={()=>setView('notices')}><Bell size={19}/></button>
        <button className="icon-button" title="Sair" aria-label="Sair" onClick={()=>void perform(
          async()=>{await api('/auth/logout',{method:'POST'});await csrf();setMe(null);},
          notify)}><LogOut size={19}/></button>
        <button className="icon-button mobile-menu" title="Menu" aria-label="Menu" aria-expanded={menuOpen}
          onClick={()=>setMenuOpen(!menuOpen)}>{menuOpen?<X size={20}/>:<Menu size={20}/>}</button>
      </div></div></header>
    <main className="container" id="content">
      {toast&&<div className={toast.error?'toast error':'toast'} role="status">
        <span>{toast.message}</span><button onClick={()=>setToast(null)} aria-label="Fechar aviso"><X size={16}/></button></div>}
      {view==='dashboard'?<Dashboard me={me} shop={activeShop} modules={modules}
        onOpen={setView} countLabel={countLabel} notify={notify}/>:
        <div className="content-body">
          <button className="back-link" onClick={()=>setView('dashboard')}><ArrowLeft size={17}/> Voltar ao início</button>
          {selected?.key==='sales'&&<Sales notify={notify} can={can}/>}
          {selected?.key==='customers'&&<Customers notify={notify} can={can}/>}
          {selected?.key==='catalog'&&<Catalog notify={notify} can={can}/>}
          {selected?.key==='visits'&&<Visits notify={notify} can={can}/>}
          {selected?.key==='finance'&&<Finance notify={notify} can={can}/>}
          {selected?.key==='machines'&&<Machines notify={notify} can={can}/>}
          {selected?.key==='reports'&&<Reports notify={notify} can={can}/>}
          {selected?.key==='team'&&<Team notify={notify} can={can}/>}
          {selected?.key==='site'&&<Site notify={notify} can={can}/>}
          {isPlatform&&<Platform notify={notify}/>}
          {view==='notices'&&<Notices notify={notify}/>}
          {view==='subscription'&&activeShop?.billing_mode!=='COMPLIMENTARY'&&
            <div className="screen-stack"><h2>Minha assinatura</h2>
              <div className="surface"><h3>{activeShop?.billing_mode==='TRIAL'?'Seu período de teste':'Assinatura'}</h3>
                <p>Plano: {activeShop?.billing_mode==='TRIAL'?'Teste grátis por 7 dias':'Assinatura paga'}</p>
                {activeShop?.billing_mode==='TRIAL'&&<p>Teste até: {date(activeShop?.trial_ends_at)}</p>}
                <p className="helper">A integração de cobrança Asaas ainda está em homologação. Não informe dados de cartão nesta etapa.</p>
              </div></div>}
        </div>}
    </main>
    <footer className="footer"><span>Optics Management · LCM Dev Tech</span>
      <span>Gestão simples para quem cuida do seu olhar</span></footer>
  </div>;
}

function Dashboard({me,shop,modules,onOpen,countLabel,notify}:{
  me:any;shop:any;modules:Tile[];onOpen:(key:string)=>void;
  countLabel:(n:any)=>string;notify:(msg:string,error?:boolean)=>void;
}){
  const dashboard=useRemote<any>('/sales/dashboard');
  const firstName=String(me.account?.full_name||'').split(' ')[0]||'';
  return <>
    <section className="welcome">
      <div><div className="eyebrow"><span className="online-dot"/> O seu dia na ótica</div>
        <h1>Olá, {firstName}! <span>O que vamos fazer hoje?</span></h1>
        <p>{shop?.trade_name||'Bem-vindo à plataforma'} · Escolha um bloco e siga o passo a passo.</p>
        <div className="welcome-pills"><span><ClipboardCheck size={15}/> Tudo no mesmo lugar</span>
          <span><CircleHelp size={15}/> Sem complicação</span></div>
      </div><div className="welcome-art" aria-hidden="true"><Glasses size={110}/><span className="art-ring"/></div>
    </section>
    {shop?.billing_mode!=='COMPLIMENTARY'&&<div className="info-banner">
      {shop?.billing_mode==='TRIAL'?'Você está no teste grátis até '+date(shop.trial_ends_at)+'.':
        'Você está na modalidade de assinatura.'}
      <button className="text-action" onClick={()=>onOpen('subscription')}>Ver assinatura</button>
    </div>}
    <div className="metric-grid">
      {[['Vendas hoje',dashboard.data?.today],['Esta semana',dashboard.data?.week],
        ['Este mês',dashboard.data?.month],['Entregas pendentes',dashboard.data?.pending_delivery],
        ['Visitas pendentes',dashboard.data?.pendingVisits]].map(([label,num])=>
          <div className="metric" key={String(label)}><span>{label}</span><strong>{countLabel(num)}</strong></div>)}
    </div>
    <section className="action-section"><div className="section-heading">
      <div><span className="eyebrow">Acesso rápido</span><h2>Escolha o que deseja fazer</h2></div></div>
      <div className="tile-grid">{modules.map(tile=><button key={tile.key} className="tile"
        onClick={()=>onOpen(tile.key)}>
        <span className={'tile-icon '+tile.accent}><tile.icon size={25}/></span>
        <span className="tile-title">{tile.title}</span>
        <span className="tile-description">{tile.description}</span>
        <span className="tile-cta">Abrir área <ArrowRight size={16}/></span>
      </button>)}</div>
    </section>
    <section className="bottom-note"><div className="bottom-note-icon"><ShieldCheck size={22}/></div>
      <div><strong>Seus dados são da sua ótica</strong><p>Somente quem tem permissão pode acessar cada área.
        Se precisar de ajuda, fale com o administrador da ótica.</p></div></section>
    <div className="surface"><h3>Preferências de notificação</h3>
      <button className="outline-action" onClick={()=>void requestNotification(notify)}>
        Habilitar avisos neste dispositivo</button><p className="helper">
        Avisos na tela requerem sua autorização. Notificações quando o aplicativo está fechado
        dependerão da futura integração com push.</p></div>
  </>;
}
async function requestNotification(notify:(message:string,error?:boolean)=>void) {
  if(!('Notification' in window)){notify('Este navegador não oferece notificações.',true);return;}
  try{const result=await Notification.requestPermission();
    notify(result==='granted'?'Notificações autorizadas.':'Permissão não concedida.',result!=='granted');}
  catch(error){notify(String(error),true);}
}

function Notices({notify}: {notify:(msg:string,error?:boolean)=>void}) {
  const {data,refresh}=useRemote<any[]>('/auth/notifications');
  return <div className="screen-stack"><div className="section-title"><h2>Mensagens da LCM Dev Tech</h2></div>
    <div className="surface">{!data?.length?<p className="empty">Não há mensagens por enquanto.</p>:
      <div className="list-cards">{data.map(n=><div className="mini-row" key={n.id}>
        <div><strong>{n.subject}</strong><small>{n.message}</small><small>{date(n.created_at)}</small></div>
        {!n.read&&<button className="outline-action" onClick={()=>void perform(
          ()=>api('/auth/notifications/'+n.id+'/read',{method:'POST'}),notify,refresh)}>
          Marcar como lida</button>}</div>)}</div>}</div>
  </div>;
}

function AccessScreen({onSignedIn,notify,toast}:{
  onSignedIn:()=>void;notify:(msg:string,error?:boolean)=>void;toast:Toast;
}){
  const [mode,setMode]=useState<'login'|'signup'>('login');
  return <div className="auth-layout">
    <div className="auth-box">
      <div className="brand login-brand"><span className="brand-mark"><Glasses size={26}/></span>
        <span className="brand-words"><strong>Optics</strong><small>MANAGEMENT</small></span></div>
      <h1>{mode==='login'?'Bem-vindo de volta!':'Sua ótica, muito mais organizada'}</h1>
      <p>{mode==='login'?'Entre para cuidar dos seus clientes e vendas.':
        'Crie sua conta e experimente grátis por 7 dias.'}</p>
      {toast&&<div className={toast.error?'toast error':'toast'}>{toast.message}</div>}
      <form className="auth-form" onSubmit={event=>{
        event.preventDefault();const form=event.currentTarget;
        if(mode==='login'){
          void perform(async()=>{await api('/auth/login',{method:'POST',isForm:true,
            body:{email:getField(form,'email'),password:getField(form,'password')}});await csrf();
            onSignedIn();},notify);
        }else{
          void perform(async()=>{
            const email=getField(form,'email'),password=getField(form,'password');
            await api('/public/signup',{method:'POST',body:{
              opticName:getField(form,'opticName'),fullName:getField(form,'fullName'),
              email,password,whatsapp:getField(form,'whatsapp'),
              acceptedTerms:new FormData(form).get('acceptedTerms')==='on',
            }});
            await api('/auth/login',{method:'POST',isForm:true,body:{email,password}});
            await csrf();onSignedIn();
          },notify);
        }
      }}>
        {mode==='signup'&&<><label className="field">Nome da ótica<input name="opticName" required/></label>
          <label className="field">Seu nome<input name="fullName" required/></label>
          <label className="field">WhatsApp da ótica<input name="whatsapp" type="tel"/></label></>}
        <label className="field">Seu e-mail<input name="email" type="email" autoComplete="email" required/></label>
        <label className="field">Senha<input name="password" type="password" minLength={10}
          autoComplete={mode==='login'?'current-password':'new-password'} required /></label>
        {mode==='signup'&&<label className="checkline">
          <input type="checkbox" name="acceptedTerms" required/> Concordo com os termos e com a política de privacidade</label>}
        <button type="submit" className="primary-action wide">
          {mode==='login'?'Entrar no sistema':'Criar minha conta grátis'}</button>
      </form>
      <button className="text-action auth-switch" onClick={()=>setMode(mode==='login'?'signup':'login')}>
        {mode==='login'?'Ainda não tem conta? Experimente grátis.':'Já tem conta? Entrar.'}</button>
      <p className="helper">Ambiente de homologação · Não utilize dados reais nesta etapa.</p>
    </div>
    <aside className="auth-aside"><House size={44}/><h2>Mais tempo para atender, menos tempo com planilhas.</h2>
      <p>Vendas, entregas, financeiro e visitas em domicílio. Tudo claro, no mesmo lugar.</p></aside>
  </div>;
}

function InviteAccept({token,notify,toast}:{
  token:string;notify:(msg:string,error?:boolean)=>void;toast:Toast;
}){
  const [success,setSuccess]=useState(false);
  return <div className="auth-layout"><div className="auth-box"><h1>Bem-vindo à equipe!</h1>
    <p>Complete seus dados e escolha uma senha para acessar a ótica que convidou você.</p>
    {toast&&<div className={toast.error?'toast error':'toast'}>{toast.message}</div>}
    {success?<p>Cadastro concluído! <a href="/">Clique aqui para entrar.</a></p>:
      <form className="auth-form" onSubmit={e=>{
        e.preventDefault();const form=e.currentTarget;
        void perform(()=>api('/public/accept-invite',{method:'POST',body:{
          token,fullName:getField(form,'fullName'),password:getField(form,'password'),
          cpf:getField(form,'cpf'),phone:getField(form,'phone'),
          address:getField(form,'address'),birthDate:getField(form,'birthDate'),
        }}),notify,()=>setSuccess(true));
      }}>
        <label className="field">Nome completo<input name="fullName" required/></label>
        <label className="field">CPF<input name="cpf" required/></label>
        <label className="field">Telefone<input name="phone" required/></label>
        <label className="field">Nascimento<input name="birthDate" type="date" required/></label>
        <label className="field">Endereço (opcional)<input name="address"/></label>
        <label className="field">Crie uma senha<input name="password" type="password" minLength={10} required/></label>
        <button className="primary-action wide">Concluir cadastro</button>
      </form>}
    <a className="text-action" href="/">Voltar para login</a></div></div>;
}

function PublicSite({slug,notify,toast}:{
  slug:string;notify:(msg:string,error?:boolean)=>void;toast:Toast;
}){
  const {data,loading}=useRemote<any>('/public/site/'+encodeURIComponent(slug));
  const [submitted,setSubmitted]=useState(false);
  if(loading)return <div className="center-message">Abrindo página da ótica...</div>;
  if(!data)return <div className="center-message"><h1>Página indisponível</h1>
    <a href="/">Voltar ao Optics Management</a></div>;
  const primary=data.primary_color||'#116b6d';
  const safeFont=['Manrope','DM Sans','Arial','Georgia'].includes(data.font)?data.font:'Manrope';
  return <div className="public-shell" style={{'--site-primary':primary,fontFamily:safeFont} as CSSProperties}>
    <header className="public-header"><div className="public-container">
      <span className="site-logo"><Glasses size={27}/>{data.trade_name}</span>
      <span>Atendimento personalizado</span></div></header>
    <main className="public-container public-main">
      <section className="public-hero"><span className="eyebrow">Cuidar do seu olhar é nossa prioridade</span>
        <h1>{data.campaign_title}</h1><p>{data.campaign_description}</p>
        <div className="welcome-pills"><span><House size={15}/> Atendimento no seu endereço</span>
          <span><CalendarDays size={15}/> Escolha a melhor data</span></div></section>
      <section className="public-form-card">
        <h2>Agende sua visita</h2>
        <p>Preencha os dados abaixo. Vamos entrar em contato para confirmar a disponibilidade.</p>
        {toast&&<div className={toast.error?'toast error':'toast'}>{toast.message}</div>}
        {submitted?<div className="inline-panel"><strong>Pedido recebido!</strong>
          <p>A ótica recebeu sua solicitação e entrará em contato para confirmar.</p></div>:
        <form className="form-grid" onSubmit={event=>{
          event.preventDefault();const form=event.currentTarget;
          void perform(async()=>{
            const file=(form.elements.namedItem('prescription') as HTMLInputElement).files?.[0];
            if(!file)throw new Error('Anexe a receita para agendar.');
            const data64=await fileAsBase64(file);
            const when=getField(form,'appointmentAt');
            await api('/public/site/'+encodeURIComponent(slug)+'/visits',{method:'POST',body:{
              name:getField(form,'name'),phone:getField(form,'phone'),
              email:getField(form,'email'),address:getField(form,'address'),
              appointmentAt:new Date(when).toISOString(),notes:getField(form,'notes'),
              privacyAccepted:new FormData(form).get('privacyAccepted')==='on',
              prescriptionMime:file.type,prescriptionName:file.name,prescriptionBase64:data64,
            }});
          },notify,()=>setSubmitted(true));
        }}>
          <label className="field">Seu nome<input name="name" required/></label>
          <label className="field">WhatsApp<input name="phone" type="tel" required/></label>
          <label className="field">E-mail (opcional)<input name="email" type="email"/></label>
          <label className="field">Endereço completo<input name="address" required/></label>
          <label className="field">Data e hora desejadas<input name="appointmentAt" type="datetime-local" required/></label>
          <label className="field">Anexe sua receita (PDF ou foto)<input name="prescription"
            type="file" accept=".pdf,image/png,image/jpeg,image/webp" required/></label>
          <label className="field">Observações<textarea name="notes" rows={3}/></label>
          <label className="checkline"><input type="checkbox" name="privacyAccepted" required/>
            Concordo em enviar meus dados e a receita à ótica para receber este atendimento.</label>
          <button className="primary-action wide" type="submit">Solicitar visita em casa</button>
        </form>}
      </section>
    </main><footer className="public-footer">Página de {data.trade_name} · Tecnologia Optics Management / LCM Dev Tech</footer>
  </div>;
}
