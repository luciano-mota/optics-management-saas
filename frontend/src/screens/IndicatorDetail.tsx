import { X } from 'lucide-react';
import { currency } from '../api';
import { useRemote } from './common';

type Detail = { title: string; headers: string[]; money: boolean[]; rows: string[][]; summary: string[] };

async function download(kind: string, format: 'pdf' | 'xlsx' | 'csv', query: string) {
  const response = await fetch('/api/v1/reports/monthly-sales-summary/detail/' + kind + '/export/' + format + '?' + query,
    { credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || 'Não foi possível gerar o arquivo.');
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url; link.download = 'optics-' + kind + '.' + format;
  link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 3000);
}

/** A lista que forma um número das Vendas Atualizadas (a soma bate com o indicador). */
export function IndicatorDetail({ kind, indicator, year, month, asOf, onClose, notify }: {
  kind: string; indicator: string; year: number; month: number; asOf: string;
  onClose: () => void; notify: (m: string, error?: boolean) => void;
}) {
  const query = new URLSearchParams({ year: String(year), month: String(month), asOf }).toString();
  const detail = useRemote<Detail>('/reports/monthly-sales-summary/detail/' + kind + '?' + query);
  const d = detail.data;
  return <div className="indicator-detail" role="region" aria-label={'Detalhe de ' + indicator}>
    <div className="indicator-detail-head">
      <div><span className="eyebrow">De onde vem: {indicator}</span><h3>{d?.title ?? 'Carregando...'}</h3></div>
      <button type="button" className="icon-button" onClick={onClose} aria-label="Fechar detalhe"><X size={18} /></button>
    </div>
    {detail.loading ? <p className="empty">Montando a lista...</p> :
     !d ? <p className="empty">Não foi possível carregar o detalhe.</p> : <>
      {d.summary.map((line, i) => <p className="helper" key={i}>{i === 0 ? <strong>{line}</strong> : line}</p>)}
      {d.rows.length === 0 ? <p className="empty">Nada neste período até a data de corte.</p> :
       <div className="table-wrap"><table>
        <thead><tr>{d.headers.map(h => <th key={h}>{h}</th>)}</tr></thead>
        <tbody>{d.rows.map((row, i) => <tr key={i} className={row.includes('Fechada') ? 'muted-row' : undefined}>
          {row.map((v, c) => <td key={c}>{d.money[c] && v !== '' ? currency(Number(v)) : v}</td>)}</tr>)}</tbody>
       </table></div>}
      <div className="filter-bar">{(['pdf', 'xlsx', 'csv'] as const).map(format =>
        <button className="outline-action" key={format} onClick={() => void download(kind, format, query)
          .catch(error => notify(error.message, true))}>Exportar {format.toUpperCase()}</button>)}</div>
     </>}
  </div>;
}
