import { downloadReport, currency } from '../api';
import type { ScreenProps } from './common';
import { useRemote } from './common';

export function Reports({notify}:ScreenProps) {
  const {data}=useRemote<any>('/reports/performance');
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Relatórios e resultados</h2>
      <p>Veja o desempenho da equipe, das vendas e das visitas.</p></div></div>
    <div className="surface"><h3>Exportar vendas</h3>
      <p className="helper">Relatório por ótica com situação, cliente, produtos, valor bruto, taxas e líquido.</p>
      <div className="filter-bar">{(['pdf','xlsx','csv'] as const).map(file=>
        <button className="outline-action" key={file} onClick={()=>void downloadReport(file)
          .catch(error=>notify(String(error),true))}>Baixar {file.toUpperCase()}</button>)}</div>
    </div>
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
