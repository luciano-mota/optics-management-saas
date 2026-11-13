import { currency, api } from '../api';
import { DetailedReports } from './DetailedReports';
import { IndicatorDetail } from './IndicatorDetail';
import { useEffect, useState } from 'react';
import type { ScreenProps } from './common';
import { perform, useRemote } from './common';

type Indicator={key:string;name:string;value:number|null;money:boolean;detail:string};
type Monthly={summary:{
  year:number;month:number;asOf:string;opticName:string;goalConfigured:boolean;
  monthlyGoal:number|null;totalOpenDays:number;workedDays:number;
  remainingDays:number;calendarReviewed:boolean;localHolidaysVerified:boolean;
  warnings:string[];totalSales:number;monthlyGoalRemaining:number|null;
};indicators:Indicator[]};

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

export function Reports({notify,can,isMaster}:ScreenProps&{isMaster:boolean}) {
  const {data}=useRemote<any>('/reports/performance');
  const now=new Date();
  const [year,setYear]=useState(now.getFullYear());
  const [month,setMonth]=useState(now.getMonth()+1);
  const [asOf,setAsOf]=useState([now.getFullYear(),String(now.getMonth()+1).padStart(2,'0'),
    String(now.getDate()).padStart(2,'0')].join('-'));
  const [goal,setGoal]=useState('');
  const [opened,setOpened]=useState<Indicator|null>(null);
  const q=new URLSearchParams({year:String(year),month:String(month),asOf});
  const report=useRemote<Monthly>('/reports/monthly-sales-summary?'+q.toString());
  useEffect(()=>{
    setGoal(report.data?.summary.monthlyGoal==null?'':String(report.data.summary.monthlyGoal));
  },[report.data?.summary.monthlyGoal,report.data?.summary.year,report.data?.summary.month]);
  function changePeriod(nextYear:number,nextMonth:number){
    setYear(nextYear);setMonth(nextMonth);setOpened(null);
    const end=new Date(nextYear,nextMonth,0);
    const cutoff=end>new Date()?new Date():end;
    setAsOf([cutoff.getFullYear(),String(cutoff.getMonth()+1).padStart(2,'0'),
      String(cutoff.getDate()).padStart(2,'0')].join('-'));
  }

  return <div className="screen-stack">
    <div className="section-title"><div><h2>Relatórios e resultados</h2>
      <p>Veja o desempenho da equipe, das vendas e das visitas.</p></div></div>
    <div className="surface">
      <h3>Vendas atualizadas e metas</h3>
      <p className="helper">Os mesmos 14 indicadores da planilha da ótica: valores recebidos, a receber, bruto, taxas e metas calculadas sobre os dias de funcionamento da loja.</p>
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
          onChange={e=>setAsOf(e.target.value)}/></label>
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
        <p className="helper">Toque em um indicador para ver a lista que forma o número.</p>
        <div className="table-wrap"><table className="indicator-table"><thead><tr><th>Indicador</th><th>Resultado</th><th aria-label="Detalhe"/></tr></thead>
        <tbody>{report.data.indicators.map(item=><tr key={item.key}
          className={opened?.key===item.key?'selected':undefined}
          onClick={()=>setOpened(opened?.key===item.key?null:item)}>
          <td><button type="button" className="link-button" aria-expanded={opened?.key===item.key}>{item.name}</button></td>
          <td><strong>{item.value==null?'—':item.money?currency(item.value):item.value}</strong></td>
          <td className="row-chevron" aria-hidden="true">›</td>
        </tr>)}</tbody></table></div>
        {opened&&<IndicatorDetail key={opened.key+asOf} kind={opened.detail} indicator={opened.name}
          year={year} month={month} asOf={asOf} notify={notify} onClose={()=>setOpened(null)}/>}
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
    </div>
    <DetailedReports notify={notify} can={can}/>
    <div className="surface"><h3>Vendas por funcionário</h3>
      {!data?.salesByEmployee?.length?<p className="empty">Ainda sem dados.</p>:
      <div className="table-wrap"><table><thead><tr><th>Funcionário</th><th>Vendas</th><th>Valor bruto</th></tr></thead>
        <tbody>{data.salesByEmployee.map((r:any,i:number)=><tr key={i}>
          <td>{r.employee}</td><td>{r.sales_count}</td><td>{currency(r.sales_gross)}</td></tr>)}</tbody></table></div>}
    </div>
    <div className="surface"><h3>Visitas por funcionário</h3>
      {!data?.visitsByEmployee?.length?<p className="empty">Ainda sem dados.</p>:
      <div className="table-wrap"><table><thead><tr><th>Funcionário</th><th>Assumidas</th>
        <th>Atendidas</th><th>Convertidas</th></tr></thead><tbody>
        {data.visitsByEmployee.map((r:any,i:number)=><tr key={i}><td>{r.employee}</td>
          <td>{r.assigned}</td><td>{r.attended}</td><td>{r.converted}</td></tr>)}
      </tbody></table></div>}
    </div>
  </div>;
}
