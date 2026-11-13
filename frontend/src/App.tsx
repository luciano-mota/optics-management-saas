import { useEffect, useRef, useState } from 'react';
import {
  ArrowLeft, Bell, BellRing, Cake, CalendarDays, ChartColumnIncreasing,
  CircleUserRound, CreditCard, Eye, Glasses, Globe, LogOut, MapPinned,
  ReceiptText, ShieldCheck, ShoppingBag, UserCog, UsersRound, Wallet, X,
} from 'lucide-react';
import { BrandLogo, BrandMark } from './brand';
import { AddressFields } from './screens/AddressFields';
import { PublicSitePage } from './site/PublicSite';
import { PrivacyPage, TermsPage } from './Legal';
import { api, csrf, date, currency } from './api';
import { Catalog } from './screens/Catalog';
import { Customers } from './screens/Customers';
import { Retention } from './screens/Retention';
import { BusinessCalendar, MonthlyReview } from './screens/BusinessCalendar';
import { Sales } from './screens/Sales';
import { Finance } from './screens/Finance';
import { Machines } from './screens/Machines';
import { Visits } from './screens/Visits';
import { Reports } from './screens/Reports';
import { Team } from './screens/Team';
import { Site } from './screens/Site';
import { Platform } from './screens/Platform';
import { Optometry } from './screens/Optometry';
import { Birthdays, BirthdayWelcome } from './screens/Birthdays';
import { Receipts } from './screens/Receipts';
import { MyProfile } from './screens/MyProfile';
import { DeviceSettings } from './screens/DeviceSettings';
import { syncPush } from './pwa';
import { MfaChallenge, MfaRecommendation, TwoFactorSettings } from './screens/TwoFactor';
import { getField, perform, PhoneField, useRemote } from './screens/common';

type Tile={key:string;title:string;description:string;permission:string;icon:typeof Glasses;accent:string};
/** Cada bloco tem ícone e cor próprios, sem repetição, para ser reconhecido de relance. */
const tiles:Tile[]=[
  {key:'sales',title:'Vendas e entregas',description:'Registre vendas e confirme entregas.',permission:'SALES_READ',icon:ShoppingBag,accent:'accent-emerald'},
  {key:'customers',title:'Clientes',description:'Cadastros, receitas e histórico.',permission:'CUSTOMERS_READ',icon:UsersRound,accent:'accent-blue'},
  {key:'birthdays',title:'Aniversariantes',description:'Parabéns pelo WhatsApp.',permission:'CUSTOMERS_READ',icon:Cake,accent:'accent-pink'},
  {key:'retention',title:'Clientes para lembrar',description:'Retornos de 3, 6, 9 e 12 meses.',permission:'CUSTOMERS_READ',icon:BellRing,accent:'accent-violet'},
  {key:'optometry',title:'Consultas de vista',description:'Pacientes, clínicas e agenda.',permission:'APPOINTMENTS_READ',icon:Eye,accent:'accent-cyan'},
  {key:'visits',title:'Visitas em domicílio',description:'Pedidos de atendimento em casa.',permission:'VISITS_READ',icon:MapPinned,accent:'accent-orange'},
  {key:'receipts',title:'Recibos',description:'Comprovantes com a sua marca.',permission:'RECEIPTS_READ',icon:ReceiptText,accent:'accent-teal'},
  {key:'finance',title:'Financeiro',description:'Entradas, despesas e a receber.',permission:'FINANCE_READ',icon:Wallet,accent:'accent-lime'},
  {key:'catalog',title:'Lentes e armações',description:'Marcas e modelos.',permission:'CATALOG_READ',icon:Glasses,accent:'accent-fuchsia'},
  {key:'machines',title:'Maquininhas',description:'Taxas de débito e crédito.',permission:'FINANCE_READ',icon:CreditCard,accent:'accent-amber'},
  {key:'reports',title:'Relatórios',description:'Vendas, metas e equipe.',permission:'REPORTS_READ',icon:ChartColumnIncreasing,accent:'accent-indigo'},
  {key:'calendar',title:'Calendário',description:'Dias de abertura e feriados.',permission:'REPORTS_READ',icon:CalendarDays,accent:'accent-red'},
  {key:'team',title:'Equipe',description:'Convites e permissões.',permission:'USERS_MANAGE',icon:UserCog,accent:'accent-sky'},
  {key:'site',title:'Site e campanhas',description:'Sua vitrine na internet.',permission:'SITE_MANAGE',icon:Globe,accent:'accent-prism'},
];
type Toast = {message:string;error:boolean}|null;

export function App(){
  const params=new URLSearchParams(window.location.search);
  const site=params.get('site');
  const invite=params.get('invite');
  const reset=params.get('reset');
  const verify=params.get('verify');
  const [me,setMe]=useState<any|null>(null);
  const [ready,setReady]=useState(false);
  const [mfaPending,setMfaPending]=useState(false);
  const [view,setView]=useState('dashboard');
  const [toast,setToast]=useState<Toast>(null);
  const [monthlyReviewDismissed,setMonthlyReviewDismissed]=useState(false);
  const [alerts,setAlerts]=useState<{newVisits:number;retentionDue:number;unreadNotices:number}|null>(null);
  const seenAlertCount=useRef<number|null>(null);
  const notify=(message:string,error=false)=>{setToast({message,error});};
  useEffect(()=>{
    if(!toast)return;
    const timer=window.setTimeout(()=>setToast(null),toast.error?9000:5000);
    return ()=>window.clearTimeout(timer);
  },[toast]);
  const load=async()=>{
    try {setMe(await api('/auth/me'));setMfaPending(false);}
    catch {
      setMe(null);
      // Senha certa, mas a conta usa verificação em duas etapas: falta o código.
      try {setMfaPending((await api<{pending:boolean}>('/auth/mfa/challenge')).pending);}
      catch {setMfaPending(false);}
    }
    finally {setReady(true);}
  };
  useEffect(()=>{if(!site&&!invite&&!reset&&!verify&&!params.has('termos')&&!params.has('privacidade'))void load();},[site,invite,reset,verify]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(()=>{
    if(me?.platformOperator && !(me.shops||[]).length)setView('platform');
  },[me?.platformOperator]);
  useEffect(()=>{setMonthlyReviewDismissed(false);},[me?.activeOpticId]);
  // Mesmo aparelho, outra pessoa entrou: os avisos passam a ir para quem está logado.
  useEffect(()=>{if(me?.account?.id)void syncPush();},[me?.account?.id]);
  useEffect(()=>{
    if(!me?.activeOpticId)return;
    let mounted=true;
    seenAlertCount.current=null;
    const check=async()=>{
      try {
        const next=await api<{newVisits:number;retentionDue:number;unreadNotices:number}>('/auth/alerts');
        if(!mounted)return;
        setAlerts(next);
        const count=next.newVisits+next.retentionDue+next.unreadNotices;
        if(seenAlertCount.current!==null&&count>seenAlertCount.current&&
            'Notification' in window&&Notification.permission==='granted'){
          new Notification('Optics Management', {
            body:'Sua ótica tem novos avisos para consultar no painel.',
            icon:'/icon.svg',tag:'optics-pending-alerts',
          });
        }
        seenAlertCount.current=count;
      } catch { /* Conta sem ótica, sessão expirada ou painel em atualização. */ }
    };
    void check();
    const timer=window.setInterval(()=>void check(),60000);
    return ()=>{mounted=false;window.clearInterval(timer);};
  },[me?.activeOpticId]);
  if(params.has('termos'))return <TermsPage/>;
  if(params.has('privacidade'))return <PrivacyPage/>;
  if(site)return <PublicSitePage slug={site} notify={notify} toast={toast}/>;
  if(invite)return <InviteAccept token={invite} notify={notify} toast={toast}/>;
  if(reset)return <AccountLinkAction kind="reset" token={reset} notify={notify} toast={toast}/>;
  if(verify)return <AccountLinkAction kind="verify" token={verify} notify={notify} toast={toast}/>;
  if(!ready)return <div className="center-message app-splash" role="status">
    <BrandMark size={72}/>
    <h2>Optics Management</h2><p>Preparando sua ótica...</p>
  </div>;
  if(!me&&mfaPending)return <MfaChallenge onDone={()=>void csrf().then(load)}
    onCancel={()=>void api('/auth/logout',{method:'POST'}).catch(()=>undefined)
      .finally(()=>{setMfaPending(false);void csrf();})}/>;
  if(!me)return <AccessScreen onSignedIn={()=>void load()} notify={notify} toast={toast}/>;
  if((!me.platformOperator||(me.shops?.length??0)>0)&&
    (me.profileComplete===false||me.opticRegistrationRequired===true))return <MyProfile
    person={me.account} required requiredCnpj={me.opticRegistrationRequired===true}
    opticCnpj={me.opticCnpj||''} opticAddress={me.opticAddress?.address||''}
    showPersonalAddress={!me.platformOperator&&me.shops?.find((shop:any)=>shop.id===me.activeOpticId)?.role!=='MASTER'}
    onSaved={()=>void load()}
    onLogout={()=>void perform(async()=>{
      await api('/auth/logout',{method:'POST'});await csrf();setMe(null);
    },notify)}/>;
  const activeShop=me.shops?.find((shop:any)=>shop.id===me.activeOpticId);
  const can=(permission:string)=>me.permissions?.includes(permission);
  // O cartão de site aparece para quem administra a ótica mesmo antes da liberação comercial.
  // A autorização real de edição/publicação permanece obrigatória no backend.
  const modules=tiles.filter(tile=>can(tile.permission)&&
    (tile.key!=='visits'||activeShop?.home_visits_enabled));
  const selected=modules.find(tile=>tile.key===view);
  const isPlatform=view==='platform'&&me.platformOperator;
  const countLabel=(count:any)=>count==null?'—':String(count);
  const logout=()=>void perform(async()=>{
    await api('/auth/logout',{method:'POST'});await csrf();setMe(null);
  },notify);
  const pending=alerts?(alerts.newVisits+alerts.retentionDue+alerts.unreadNotices):0;
  return <div className="app-shell">
    <a className="skip-link" href="#content">Ir para o conteúdo</a>
    <header className="app-header"><div className="header-inner">
      <button className="brand" type="button" onClick={()=>setView('dashboard')} aria-label="Voltar ao início">
        <BrandLogo/></button>
      {activeShop&&<span className="shop-chip" title={activeShop.trade_name}><i aria-hidden="true"/>
        <span>{activeShop.trade_name}</span></span>}
      <div className="header-actions">
        {me.platformOperator&&<button className="outline-action" onClick={()=>setView('platform')}>
          <ShieldCheck size={16}/> Painel LCM</button>}
        <button className="icon-button" title="Notificações" aria-label="Notificações"
          onClick={()=>setView('notices')}><Bell size={19}/>
          {pending>0&&<span className="notification-dot" aria-label="Avisos pendentes"/>}</button>
        <button className="icon-button" title="Meus dados" aria-label="Meus dados"
          onClick={()=>setView('profile')}><CircleUserRound size={20}/></button>
        <button className="icon-button" title="Sair" aria-label="Sair" onClick={logout}><LogOut size={19}/></button>
      </div></div></header>
    <main className="container" id="content">
      {toast&&<div className={toast.error?'toast error':'toast'} role="status">
        <span>{toast.message}</span><button type="button" onClick={()=>setToast(null)} aria-label="Fechar aviso"><X size={16}/></button></div>}
      {view==='dashboard'?<Dashboard me={me} shop={activeShop} modules={modules}
        onOpen={setView} countLabel={countLabel} notify={notify}/>:
        <div className="content-body">
          <button className="back-link" onClick={()=>setView('dashboard')}><ArrowLeft size={17}/> Início</button>
          {selected?.key==='sales'&&<Sales notify={notify} can={can}/>}
          {selected?.key==='customers'&&<Customers notify={notify} can={can}/>}
          {selected?.key==='retention'&&<Retention notify={notify} can={can}/>}
          {selected?.key==='birthdays'&&<Birthdays notify={notify} can={can}/>}
          {selected?.key==='catalog'&&<Catalog notify={notify} can={can}/>}
          {selected?.key==='visits'&&<Visits notify={notify} can={can}/>}
          {selected?.key==='optometry'&&<Optometry notify={notify} can={can}/>}
          {selected?.key==='finance'&&<Finance notify={notify} can={can}/>}
          {selected?.key==='receipts'&&<Receipts notify={notify} can={can} isMaster={activeShop?.role==='MASTER'}/>}
          {selected?.key==='machines'&&<Machines notify={notify} can={can}/>}
          {selected?.key==='reports'&&<Reports notify={notify} can={can} isMaster={activeShop?.role==='MASTER'}/>}
          {selected?.key==='calendar'&&<BusinessCalendar notify={notify} isMaster={activeShop?.role==='MASTER'}/>}
          {selected?.key==='team'&&<Team notify={notify} can={can} homeVisits={!!activeShop?.home_visits_enabled}
            isPrimary={!!activeShop?.primary_master}/>}
          {selected?.key==='site'&&<Site notify={notify} can={can} onSaved={()=>void load()}/>}
          {view==='profile'&&<MyProfile person={me.account} required={false}
            showPersonalAddress={!me.platformOperator&&activeShop?.role!=='MASTER'}
            onSaved={()=>void load()} onLogout={()=>setView('dashboard')}/>}
          {view==='profile'&&<TwoFactorSettings notify={notify}/>}
          {view==='profile'&&<DeviceSettings notify={notify}/>}
          {isPlatform&&<Platform notify={notify}/>}
          {view==='notices'&&<Notices notify={notify} onOpen={setView} alerts={alerts}
            homeVisitsEnabled={!!activeShop?.home_visits_enabled}/>}
          {view==='subscription'&&activeShop?.billing_mode!=='COMPLIMENTARY'&&
            <SubscriptionView notify={notify} shop={activeShop}/>}

        </div>}
    </main>
    <footer className="footer"><span>Optics Management</span>
      <a href="https://lcmtech.com.br" target="_blank" rel="noopener noreferrer">Desenvolvido por LCM DEV TECH</a>
    </footer>
    {me.profileComplete!==false&&activeShop?.role==='MASTER'&&!monthlyReviewDismissed&&
      <MonthlyReview shopId={String(activeShop.id)} notify={notify}
        onDone={()=>setMonthlyReviewDismissed(true)}
        onCalendar={()=>setView('calendar')}/>}
    <BirthdayWelcome key={String(activeShop?.id||'no-shop')} allowed={!!activeShop&&can('CUSTOMERS_READ')&&
       (activeShop?.role!=='MASTER'||monthlyReviewDismissed)}
       notify={notify} onOpen={()=>setView('birthdays')}/>

  </div>;
}

function Dashboard({me,shop,modules,onOpen,countLabel}:{
  me:any;shop:any;modules:Tile[];onOpen:(key:string)=>void;
  countLabel:(n:any)=>string;notify:(msg:string,error?:boolean)=>void;
}){
  const dashboard=useRemote<any>('/sales/dashboard');
  const firstName=String(me.account?.full_name||'').split(' ')[0]||'';
  const today=new Date().toLocaleDateString('pt-BR',{weekday:'long',day:'numeric',month:'long'});
  return <>
    <section className="greeting">
      <div><h1>Olá{firstName?', '+firstName:''}!</h1>
        <p>{today.charAt(0).toUpperCase()+today.slice(1)}{shop?.trade_name?' · '+shop.trade_name:''}</p></div>
      {shop&&shop.billing_mode!=='COMPLIMENTARY'&&<span className="plan-chip">
        {shop.billing_mode==='TRIAL'?'Teste grátis até '+date(shop.trial_ends_at):'Plano mensal'}
        <button type="button" onClick={()=>onOpen('subscription')}>Assinatura</button></span>}
    </section>
    {me.mfa?.recommended&&!me.mfa?.enabled&&<MfaRecommendation onOpen={()=>onOpen('profile')}/>}
    {shop&&<div className="metric-grid">
      {[['Vendas hoje',dashboard.data?.today],['Na semana',dashboard.data?.week],
        ['No mês',dashboard.data?.month],['Entregas pendentes',dashboard.data?.pending_delivery],
        ...(shop?.home_visits_enabled?[['Visitas pendentes',dashboard.data?.pendingVisits]]:[])].map(([label,num])=>
          <div className="metric" key={String(label)}><span>{label}</span><strong>{countLabel(num)}</strong></div>)}
    </div>}
    <div className="section-heading"><h2>O que você quer fazer?</h2></div>
    <div className="tile-grid">{modules.map(tile=>{
      const locked=tile.key==='site'&&!shop?.site_enabled;
      return <button key={tile.key} type="button" className={'tile '+tile.accent+(locked?' locked':'')}
        onClick={()=>onOpen(tile.key)}>
        <span className="tile-icon"><tile.icon size={22}/></span>
        <span className="tile-text"><span className="tile-title">{tile.title}</span>
          <span className="tile-description">{locked?'Aguardando liberação':tile.description}</span></span>
      </button>;})}</div>
  </>;
}
function Notices({notify,onOpen,alerts,homeVisitsEnabled}: {notify:(msg:string,error?:boolean)=>void;
  onOpen:(view:string)=>void; homeVisitsEnabled:boolean;
  alerts:{newVisits:number;retentionDue:number;unreadNotices:number}|null}) {
  const {data,refresh}=useRemote<any[]>('/auth/notifications');
  return <div className="screen-stack">
    <div className="section-title"><h2>Notificações da ótica</h2></div>
    <div className="surface">
      <h3>O que precisa de atenção?</h3>
      <div className="metric-grid">
        {homeVisitsEnabled&&<div className="metric"><span>Novas visitas</span><strong>{alerts?.newVisits??'—'}</strong></div>}
        <div className="metric"><span>Clientes para lembrar</span><strong>{alerts?.retentionDue??'—'}</strong></div>
        <div className="metric"><span>Mensagens não lidas</span><strong>{alerts?.unreadNotices??'—'}</strong></div>
      </div>
      <div className="filter-bar">
        {homeVisitsEnabled&&<button className="outline-action" onClick={()=>onOpen('visits')}>Ver visitas</button>}
        <button className="outline-action" onClick={()=>onOpen('retention')}>Ver lembretes</button>
      </div>
      <p className="helper">Os contadores são atualizados enquanto o painel está aberto.</p>
    </div>
    <DeviceSettings notify={notify}/>
    <div className="section-title"><h2>Mensagens da LCM DEV TECH</h2></div>
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
  const [forgot,setForgot]=useState(false);
  if(forgot)return <div className="auth-layout"><div className="auth-box">
    <div className="login-brand"><BrandLogo size={40}/></div>
    <h1>Redefinir senha</h1>
    <p>Informe seu e-mail. Se houver uma conta e o serviço de e-mail estiver configurado,
      enviaremos um link para criar uma nova senha.</p>
    <form className="auth-form" onSubmit={event=>{
      event.preventDefault();const form=event.currentTarget;
      void perform(()=>api('/public/password/forgot',{method:'POST',
        body:{email:getField(form,'email')}}),notify);
    }}>
      <label className="field">E-mail<input name="email" type="email" required /></label>
      <button className="primary-action wide">Solicitar link</button>
    </form>
    {toast&&<div className={toast.error?'toast error':'toast'}>{toast.message}</div>}
    <button className="text-action" onClick={()=>setForgot(false)}>Voltar ao login</button>
  </div></div>;
  return <div className="auth-layout">
    <div className="auth-box">
      <div className="login-brand"><BrandLogo size={40}/></div>
      <h1>{mode==='login'?'Bem-vindo de volta!':'Sua ótica, muito mais organizada'}</h1>
      <p>{mode==='login'?'Entre para cuidar dos seus clientes e vendas.':
        'Crie sua conta e experimente grátis por 7 dias.'}</p>
      {toast&&<div className={toast.error?'toast error':'toast'}>{toast.message}</div>}
      <form className="auth-form" onSubmit={event=>{
        event.preventDefault();const form=event.currentTarget;
        // Ao entrar, só mostramos aviso se algo der errado.
        const onlyErrors=(message:string,error?:boolean)=>{if(error)notify(message,true);};
        if(mode==='login'){
          void perform(async()=>{await api('/auth/login',{method:'POST',isForm:true,
            body:{email:getField(form,'email'),password:getField(form,'password')}});await csrf();
            onSignedIn();},onlyErrors);
        }else{
          void perform(async()=>{
            const email=getField(form,'email'),password=getField(form,'password');
            const created=await api<{verificationSent:boolean}>('/public/signup',{method:'POST',body:{
              opticName:getField(form,'opticName'),fullName:getField(form,'fullName'),
              email,password,whatsapp:getField(form,'whatsapp'),
              acceptedTerms:new FormData(form).get('acceptedTerms')==='on',
            }});
            if(created.verificationSent){
              notify('Enviamos um link para confirmar seu e-mail. Confira sua caixa de entrada.');
              setMode('login');
              return;
            }
            await api('/auth/login',{method:'POST',isForm:true,body:{email,password}});
            await csrf();onSignedIn();
          },onlyErrors);
        }
      }}>
        {mode==='signup'&&<><label className="field">Nome da ótica<input name="opticName" required/></label>
          <label className="field">Seu nome<input name="fullName" required/></label>
          <PhoneField name="whatsapp" title="WhatsApp da ótica"/></>}
        <label className="field">Seu e-mail<input name="email" type="email" autoComplete="email" required/></label>
        <label className="field">Senha<input name="password" type="password" minLength={10}
          autoComplete={mode==='login'?'current-password':'new-password'} required /></label>
        {mode==='signup'&&<label className="checkline">
          <input type="checkbox" name="acceptedTerms" required/>
          <span>Li e concordo com os <a href="/?termos" target="_blank" rel="noreferrer">termos de uso</a> e a{' '}
            <a href="/?privacidade" target="_blank" rel="noreferrer">política de privacidade</a></span></label>}
        {mode==='login'&&<button className="text-action" type="button"
          onClick={()=>setForgot(true)}>Esqueci minha senha</button>}
        <button type="submit" className="primary-action wide">
          {mode==='login'?'Entrar no sistema':'Criar minha conta grátis'}</button>
      </form>
      <button className="text-action auth-switch" onClick={()=>setMode(mode==='login'?'signup':'login')}>
        {mode==='login'?'Ainda não tem conta? Experimente grátis.':'Já tem conta? Entrar.'}</button>
      <p className="helper">Desenvolvido por <a href="https://lcmtech.com.br" target="_blank"
        rel="noopener noreferrer">LCM DEV TECH</a></p>
    </div>
    <aside className="auth-aside"><BrandMark size={56}/>
      <h2>Mais tempo para atender, menos tempo com planilhas.</h2>
      <p>Vendas, entregas, clientes e financeiro da sua ótica. Tudo claro, no mesmo lugar.</p></aside>
  </div>;
}

function InviteAccept({token,notify,toast}:{
  token:string;notify:(msg:string,error?:boolean)=>void;toast:Toast;
}){
  const [success,setSuccess]=useState(false);
  return <div className="auth-layout"><div className="auth-box">
    <div className="login-brand"><BrandLogo size={40}/></div><h1>Bem-vindo à equipe!</h1>
    <p>Complete seus dados e escolha uma senha para acessar a ótica que convidou você.</p>
    {toast&&<div className={toast.error?'toast error':'toast'}>{toast.message}</div>}
    {success?<p>Cadastro concluído! <a href="/">Clique aqui para entrar.</a></p>:
      <form className="auth-form" onSubmit={e=>{
        e.preventDefault();const form=e.currentTarget;
        void perform(()=>api('/public/accept-invite',{method:'POST',body:{
          token,fullName:getField(form,'fullName'),password:getField(form,'password'),
          cpf:getField(form,'cpf'),phone:getField(form,'phone'),
          address:getField(form,'address'),birthDate:getField(form,'birthDate'),
          acceptedTerms:new FormData(form).get('acceptedTerms')==='on',
        }}),notify,()=>setSuccess(true));
      }}>
        <label className="field">Nome completo<input name="fullName" required/></label>
        <label className="field">CPF<input name="cpf" required/></label>
        <PhoneField name="phone" title="Telefone com DDD" required/>
        <label className="field">Nascimento<input name="birthDate" type="date" required/></label>
        <AddressFields legend="Seu endereço"/>
        <label className="field">Crie uma senha<input name="password" type="password" minLength={10} required/></label>
        <label className="checkline"><input type="checkbox" name="acceptedTerms" required/>
          <span>Li e concordo com os <a href="/?termos" target="_blank" rel="noreferrer">termos de uso</a> e a{' '}
            <a href="/?privacidade" target="_blank" rel="noreferrer">política de privacidade</a></span></label>
        <button className="primary-action wide">Concluir cadastro</button>
      </form>}
    <a className="text-action" href="/">Voltar para o login</a></div>
    <aside className="auth-aside"><BrandMark size={56}/>
      <h2>Que bom ter você na equipe.</h2>
      <p>Depois de concluir, é só entrar com seu e-mail e a senha que você criou.</p></aside></div>;
}

function SubscriptionView({notify,shop}:{notify:(message:string,error?:boolean)=>void;shop:any}) {
  const {data,refresh}=useRemote<any>('/subscription');
  return <div className="screen-stack"><h2>Minha assinatura</h2>
    <div className="surface">
      <h3>{shop?.billing_mode==='TRIAL'?'Seu período de teste':'Sua assinatura'}</h3>
      <p>Modalidade: {shop?.billing_mode==='TRIAL'?'Teste grátis por 7 dias':'Plano mensal'}</p>
      {shop?.billing_mode==='TRIAL'&&<p>Teste até: {date(shop?.trial_ends_at)}</p>}
      <p>Valor mensal definido: <strong>{currency(data?.monthly_price)}</strong></p>
      {data?.payment_active&&<p>Pagamento confirmado · acesso até {date(data?.paid_through_at)}</p>}
      <p className="helper">A cobrança acontece na página segura do Asaas. O Optics Management não recebe os dados do seu cartão.</p>
      {data?.payment_link_url&&<a className="primary-action" href={data.payment_link_url}
        rel="noopener noreferrer" target="_blank">Abrir página de pagamento</a>}
      {!data?.payment_link_url&&<button className="primary-action" disabled={!data?.providerConfigured}
        onClick={()=>void perform(async()=>{
          const res=await api<{url:string}>('/subscription/checkout',{method:'POST'});
          if(res.url)window.open(res.url,'_blank','noopener,noreferrer');
        },notify,refresh)}>
        Iniciar assinatura mensal</button>}
      {!data?.providerConfigured&&<p className="helper">
        A conexão com o Asaas ainda está pendente de configuração no ambiente.</p>}
    </div>
  </div>;
}

function AccountLinkAction({kind,token,notify,toast}:{
  kind:'reset'|'verify';token:string;
  notify:(message:string,error?:boolean)=>void;toast:Toast;
}) {
  const [done,setDone]=useState(false);
  if(kind==='verify')return <div className="auth-layout"><div className="auth-box">
    <div className="login-brand"><BrandLogo size={40}/></div>
    <h1>Confirme seu e-mail</h1><p>Este link confirma que você tem acesso ao e-mail de cadastro.</p>
    {toast&&<div className={toast.error?'toast error':'toast'}>{toast.message}</div>}
    <button className="primary-action wide" onClick={()=>void perform(
      ()=>api('/public/email/verify',{method:'POST',body:{token}}),notify,
      ()=>setDone(true))} disabled={done}>{done?'E-mail confirmado':'Confirmar meu e-mail'}</button>
    {done&&<p><a href="/">Agora você pode entrar.</a></p>}
  </div></div>;
  return <div className="auth-layout"><div className="auth-box">
    <div className="login-brand"><BrandLogo size={40}/></div>
    <h1>Crie uma nova senha</h1>
    {toast&&<div className={toast.error?'toast error':'toast'}>{toast.message}</div>}
    {done?<p>Senha alterada. <a href="/">Acesse sua conta.</a></p>:
      <form className="auth-form" onSubmit={event=>{
        event.preventDefault();const form=event.currentTarget;
        void perform(()=>api('/public/password/reset',{method:'POST',
          body:{token,password:getField(form,'password')}}),notify,()=>setDone(true));
      }}>
        <label className="field">Nova senha<input name="password" type="password"
          autoComplete="new-password" minLength={10} required /></label>
        <button className="primary-action wide">Alterar senha</button>
      </form>}
  </div></div>;
}
