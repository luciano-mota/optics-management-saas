import { useEffect, useState } from 'react';
import type { LucideIcon } from 'lucide-react';
import { CircleDollarSign, Glasses, Layers, Percent, ReceiptText, Search, Stethoscope, Target, Trophy,
  Wallet } from 'lucide-react';
import { currency, api } from '../api';
import { DetailedReports } from './DetailedReports';
import type { Filters, Kind, ReportRequest } from './DetailedReports';
import { IndicatorDetail } from './IndicatorDetail';
import { SalesAnalysis } from './SalesAnalysis';
import type { ScreenProps } from './common';
import { perform, useRemote } from './common';

type Indicator={key:string;name:string;value:number|null;money:boolean;detail:string};
type Monthly={summary:{
  year:number;month:number;asOf:string;opticName:string;goalConfigured:boolean;
  monthlyGoal:number|null;totalOpenDays:number;workedDays:number;
  remainingDays:number;calendarReviewed:boolean;localHolidaysVerified:boolean;
  warnings:string[];totalSales:number;monthlyGoalRemaining:number|null;
};indicators:Indicator[]};

/** Atalhos na linguagem do dono: cada pergunta abre o relatório certo. */
type Question={icon:LucideIcon;title:string;text:string;accent:string;permission:string;
  action:{indicator:string}|{analysis:string}|{report:Kind}};
const QUESTIONS:Question[]=[
  {icon:CircleDollarSign,title:'Quanto vendi?',text:'As vendas do mês, uma a uma.',accent:'accent-emerald',
    permission:'FINANCE_READ',action:{indicator:'totalSales'}},
  {icon:Wallet,title:'Quanto tenho a receber?',text:'Parcelas e cartões a cair, com data.',accent:'accent-blue',
    permission:'FINANCE_READ',action:{indicator:'toReceive'}},
  {icon:Percent,title:'Quanto paguei de taxa?',text:'Taxas de maquininha venda a venda.',accent:'accent-orange',
    permission:'FINANCE_READ',action:{indicator:'bankFees'}},
  {icon:Target,title:'Como está a meta?',text:'Meta x realizado dia a dia.',accent:'accent-violet',
    permission:'FINANCE_READ',action:{indicator:'monthlyGoalRemaining'}},
  {icon:Trophy,title:'Quem vendeu mais?',text:'Ranking da equipe no período.',accent:'accent-amber',
    permission:'SALES_READ',action:{analysis:'seller'}},
  {icon:Glasses,title:'Quais armações mais vendem?',text:'Marcas de armação mais vendidas.',accent:'accent-pink',
    permission:'SALES_READ',action:{analysis:'frameBrand'}},
  {icon:Layers,title:'Que lentes mais saem?',text:'Por tipo: monofocal, multifocal...',accent:'accent-cyan',
    permission:'SALES_READ',action:{analysis:'lensCategory'}},
  {icon:Search,title:'Buscar vendas',text:'Filtre por funcionária, marca, pagamento...',accent:'accent-indigo',
    permission:'SALES_READ',action:{report:'vendas'}},
  {icon:Stethoscope,title:'Como estão as consultas?',text:'Por clínica, situação e conversão.',accent:'accent-teal',
    permission:'APPOINTMENTS_READ',action:{report:'consultas'}},
  {icon:ReceiptText,title:'Recibos emitidos',text:'Válidos e estornados.',accent:'accent-sky',
    permission:'RECEIPTS_READ',action:{report:'recibos'}},
];

async function downloadMonthly(file:'pdf'|'xlsx'|'csv',year:number,month:number,asOf:string){
  const query=new URLSearchParams({year:String(year),month:String(month),asOf});
  const response=await fetch('/api/v1/reports/monthly-sales-summary.'+file+'?'+query.toString(),{
    credentials:'same-origin',cache:'no-store',
  });
  if(!response.ok){
    const body=await response.json().catch(()=>({}));
    throw new Error(body.error||'Não foi possível gerar o relatório mensal.');
  }
  const objectUrl=URL.createObjectURL(await response.blob());
  const link=document.createElement('a');
  link.href=objectUrl;link.download='optics-vendas-atualizadas-'+year+'-'+
    String(month).padStart(2,'0')+'-'+asOf+'.'+file;
  link.click();window.setTimeout(()=>URL.revokeObjectURL(objectUrl),3000);
}

const isoDate=(d:Date)=>[d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-');

/** Os 14 indicadores da planilha; cada um abre a lista que forma o número. */
function MonthlySummary({notify,isMaster,request}:{notify:ScreenProps['notify'];isMaster:boolean;
  request:{key:string;nonce:number}|null}){
  const now=new Date();
  const [year,setYear]=useState(now.getFullYear());
  const [month,setMonth]=useState(now.getMonth()+1);
  const [asOf,setAsOf]=useState(isoDate(now));
  const [goal,setGoal]=useState('');
  const [opened,setOpened]=useState<Indicator|null>(null);
  const q=new URLSearchParams({year:String(year),month:String(month),asOf});
  const report=useRemote<Monthly>('/reports/monthly-sales-summary?'+q.toString());
  useEffect(()=>{
    setGoal(report.data?.summary.monthlyGoal==null?'':String(report.data.summary.monthlyGoal));
  },[report.data?.summary.monthlyGoal,report.data?.summary.year,report.data?.summary.month]);
  // Pergunta escolhida no topo: abre o indicador assim que os números chegam.
  useEffect(()=>{
    if(!request||!report.data)return;
    const found=report.data.indicators.find(i=>i.key===request.key);
    if(found){
      setOpened(found);
      window.setTimeout(()=>document.getElementById('detalhe-indicador')?.scrollIntoView({behavior:'smooth',block:'start'}),60);
    }
  },[request?.nonce,report.data]); // eslint-disable-line react-hooks/exhaustive-deps
  function changePeriod(nextYear:number,nextMonth:number){
    setYear(nextYear);setMonth(nextMonth);setOpened(null);
    const end=new Date(nextYear,nextMonth,0);
    setAsOf(isoDate(end>new Date()?new Date():end));
  }
  return <div className="surface" id="vendas-atualizadas">
    <h3>Vendas atualizadas e metas</h3>
    <p className="helper">Os mesmos 14 indicadores da planilha da ótica, calculados sobre os dias de funcionamento da loja.
      Toque em qualquer número para ver de onde ele vem.</p>
    <div className="filter-bar">
      <label className="field">Mês<select value={month}
        onChange={e=>changePeriod(year,Number(e.target.value))}>
        {Array.from({length:12},(_,i)=><option value={i+1} key={i+1}>
          {String(i+1).padStart(2,'0')}</option>)}</select></label>
      <label className="field">Ano<input type="number" min={2000} max={2199} value={year}
        onChange={e=>changePeriod(Number(e.target.value),month)}/></label>
      <label className="field">Data de corte<input type="date" value={asOf}
        min={year+'-'+String(month).padStart(2,'0')+'-01'}
        max={new Date(year,month,0).toLocaleDateString('sv-SE')}
        onChange={e=>{setAsOf(e.target.value);setOpened(null);}}/></label>
    </div>
    {isMaster&&<form className="form-grid" onSubmit={e=>{
      e.preventDefault();
      void perform(()=>api('/sales-goals/'+year+'/'+month,{method:'PUT',
        body:{goalAmount:goal}}),notify,report.refresh);
    }}>
      <label className="field">Meta mensal (R$)
        <input type="number" min="0" step="0.01" required value={goal}
          onChange={e=>setGoal(e.target.value)} placeholder="Ex.: 120000.00"/></label>
      <button type="submit" className="primary-action form-action">Salvar meta do mês</button>
    </form>}
    {report.loading?<p className="empty">Calculando os indicadores...</p>:
     !report.data?<p className="empty">Não foi possível carregar o resumo. Confira mês, data de corte e permissões financeiras.</p>:
     <>
      <div className="metric-grid">
        <div className="metric"><span>Total das vendas</span>
          <strong>{currency(report.data.summary.totalSales)}</strong></div>
        <div className="metric"><span>Meta do mês</span><strong>{
          report.data.summary.goalConfigured?
            currency(report.data.summary.monthlyGoal):'Não configurada'
        }</strong></div>
        <div className="metric"><span>Dias de funcionamento restantes</span>
          <strong>{report.data.summary.remainingDays}</strong></div>
      </div>
      <div className="table-wrap"><table className="indicator-table"><thead><tr><th>Indicador</th><th>Resultado</th><th aria-label="Detalhe"/></tr></thead>
      <tbody>{report.data.indicators.map(item=><tr key={item.key}
        className={opened?.key===item.key?'selected':undefined}
        onClick={()=>setOpened(opened?.key===item.key?null:item)}>
        <td><button type="button" className="link-button" aria-expanded={opened?.key===item.key}>{item.name}</button></td>
        <td><strong>{item.value==null?'—':item.money?currency(item.value):item.value}</strong></td>
        <td className="row-chevron" aria-hidden="true">›</td>
      </tr>)}</tbody></table></div>
      <div id="detalhe-indicador">{opened&&<IndicatorDetail key={opened.key+asOf} kind={opened.detail} indicator={opened.name}
        year={year} month={month} asOf={asOf} notify={notify} onClose={()=>setOpened(null)}/>}</div>
      {report.data.summary.warnings.map((warning,i)=><p className="calendar-warning"
        key={i}>{warning}</p>)}
      <div className="filter-bar">
        {(['pdf','xlsx','csv'] as const).map(file=>
          <button className="outline-action" key={file} onClick={()=>
            void downloadMonthly(file,year,month,asOf)
              .catch(error=>notify(error.message,true))}>
            Exportar resumo {file.toUpperCase()}
          </button>)}
      </div>
      <p className="helper">O resumo considera vendas do período e recebimentos dessas vendas até o corte. Receitas de meses anteriores não são misturadas. Baixas informadas retroativamente exigem conferência.</p>
     </>}
  </div>;
}

export function Reports({notify,can,isMaster}:ScreenProps&{isMaster:boolean}) {
  const allowed=(p:string)=>!can||can(p);
  const finance=allowed('FINANCE_READ'),sales=allowed('SALES_READ');
  const {data}=useRemote<any>('/reports/performance');
  const [indicatorRequest,setIndicatorRequest]=useState<{key:string;nonce:number}|null>(null);
  const [analysisRequest,setAnalysisRequest]=useState<{dimension:string;nonce:number}|null>(null);
  const [reportRequest,setReportRequest]=useState<ReportRequest|null>(null);
  const openReport=(kind:Kind,filters:Filters={})=>setReportRequest({kind,filters,nonce:Date.now()});
  const ask=(q:Question)=>{
    const a=q.action;
    if('indicator' in a){
      setIndicatorRequest({key:a.indicator,nonce:Date.now()});
      document.getElementById('vendas-atualizadas')?.scrollIntoView({behavior:'smooth',block:'start'});
    } else if('analysis' in a) setAnalysisRequest({dimension:a.analysis,nonce:Date.now()});
    else openReport(a.report);
  };
  const questions=QUESTIONS.filter(q=>allowed(q.permission)&&allowed('REPORTS_READ'));
  const visits=data?.visitsByEmployee?.filter((r:any)=>Number(r.assigned)>0)??[];

  return <div className="screen-stack">
    <div className="section-title"><div><h2>Relatórios</h2>
      <p>Escolha uma pergunta ou role a página para ver tudo. Todos os relatórios exportam em PDF, Excel e CSV.</p></div></div>
    {questions.length>0&&<div className="surface">
      <h3>O que você quer saber?</h3>
      <div className="question-grid">{questions.map(q=>
        <button type="button" key={q.title} className={'question '+q.accent} onClick={()=>ask(q)}>
          <span className="tile-icon"><q.icon size={20}/></span>
          <span><strong>{q.title}</strong><small>{q.text}</small></span>
        </button>)}</div>
    </div>}
    {finance&&<MonthlySummary notify={notify} isMaster={isMaster} request={indicatorRequest}/>}
    {sales&&<SalesAnalysis notify={notify} preset={analysisRequest}
      onOpenSales={filters=>openReport('vendas',filters)}/>}
    <DetailedReports notify={notify} can={can} request={reportRequest}/>
    {visits.length>0&&<div className="surface"><h3>Visitas em domicílio por funcionário</h3>
      <div className="table-wrap"><table><thead><tr><th>Funcionário</th><th>Assumidas</th>
        <th>Atendidas</th><th>Convertidas</th></tr></thead><tbody>
        {visits.map((r:any,i:number)=><tr key={i}><td>{r.employee}</td>
          <td>{r.assigned}</td><td>{r.attended}</td><td>{r.converted}</td></tr>)}
      </tbody></table></div>
    </div>}
  </div>;
}
