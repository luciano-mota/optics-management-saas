import { useEffect, useState } from 'react';
import { TrendingDown, TrendingUp } from 'lucide-react';
import { currency } from '../api';
import { useRemote } from './common';
import { monthStart, today } from './DetailedReports';
import type { Filters } from './DetailedReports';

type Line = { key: string; filterValue: string; label: string; count: number; amount: number; avgTicket: number | null;
  share: number; previousAmount: number; previousCount: number; variation: number | null };
type Analysis = { dimension: string; title: string; column: string; filter: keyof Filters; from: string; to: string;
  previousFrom: string; previousTo: string; count: number; amount: number; avgTicket: number | null;
  previousCount: number; previousAmount: number; variation: number | null; rows: Line[] };

export const DIMENSIONS: [string, string][] = [
  ['seller', 'Funcionária'], ['frameBrand', 'Marca da armação'], ['lensBrand', 'Marca da lente'],
  ['lensCategory', 'Tipo de lente'], ['frameCategory', 'Categoria da armação'], ['paymentMethod', 'Pagamento'],
  ['origin', 'Origem'],
];

const br = (iso: string) => iso.split('-').reverse().join('/');

function Variation({ value }: { value: number | null }) {
  if (value === null) return <span className="variation neutral">novo</span>;
  const up = value >= 0;
  return <span className={'variation ' + (up ? 'up' : 'down')}>
    {up ? <TrendingUp size={14} /> : <TrendingDown size={14} />}{(up ? '+' : '') + String(value).replace('.', ',')}%</span>;
}

async function download(format: 'pdf' | 'xlsx' | 'csv', query: string) {
  const response = await fetch('/api/v1/reports/sales-analysis/export/' + format + '?' + query,
    { credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || 'Não foi possível gerar o arquivo.');
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url; link.download = 'optics-analise-de-vendas.' + format;
  link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 3000);
}

/** Quem mais vende e o que mais vende, com comparação com o mesmo período do mês anterior. */
export function SalesAnalysis({ notify, onOpenSales, preset }: {
  notify: (m: string, error?: boolean) => void;
  onOpenSales: (filters: Filters) => void;
  preset?: { dimension: string; nonce: number } | null;
}) {
  const [dimension, setDimension] = useState('seller');
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(today());
  useEffect(() => {
    if (!preset) return;
    setDimension(preset.dimension);
    document.getElementById('analise-de-vendas')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [preset?.nonce]); // eslint-disable-line react-hooks/exhaustive-deps
  const query = new URLSearchParams({ dimension, from, to }).toString();
  const result = useRemote<Analysis>('/reports/sales-analysis?' + query);
  const a = result.data;
  const top = a?.rows.length ? Math.max(...a.rows.map(r => r.amount)) : 0;
  const invalid = from && to && from > to;

  return <div className="surface" id="analise-de-vendas">
    <h3>Análise de vendas</h3>
    <p className="helper">Veja quem mais vende e o que mais sai. Toque numa linha para ver as vendas dela. Vendas canceladas ficam fora.</p>
    <div className="day-chips">{DIMENSIONS.map(([key, label]) =>
      <button type="button" key={key} className={'day-chip' + (key === dimension ? ' active' : '')}
        onClick={() => setDimension(key)}>{label}</button>)}</div>
    <div className="filter-bar">
      <label className="field">De<input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
      <label className="field">Até<input type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
    </div>
    {invalid ? <p className="calendar-warning">A data inicial deve ser anterior ou igual à data final.</p> :
     result.loading ? <p className="empty">Calculando...</p> :
     !a ? <p className="empty">Não foi possível carregar a análise.</p> : <>
      <div className="metric-grid">
        <div className="metric"><span>Vendas</span><strong>{a.count}</strong></div>
        <div className="metric"><span>Valor vendido</span><strong>{currency(a.amount)}</strong></div>
        <div className="metric"><span>Ticket médio</span><strong>{a.avgTicket === null ? '—' : currency(a.avgTicket)}</strong></div>
        <div className="metric"><span>Comparado a {br(a.previousFrom)} – {br(a.previousTo)}</span>
          <strong className="metric-variation"><Variation value={a.variation} /></strong>
          <small>{currency(a.previousAmount)} em {a.previousCount} vendas</small></div>
      </div>
      {a.rows.length === 0 ? <p className="empty">Nenhuma venda neste período.</p> :
       <div className="ranking" role="list">{a.rows.map((r, i) =>
        <button type="button" role="listitem" key={r.key} className="ranking-row"
          onClick={() => onOpenSales({ from: a.from, to: a.to, [a.filter]: r.filterValue })}
          title={'Ver as vendas de ' + r.label}>
          <span className="ranking-pos">{i + 1}</span>
          <span className="ranking-main">
            <span className="ranking-title"><strong>{r.label}</strong><span>{currency(r.amount)}</span></span>
            <span className="ranking-bar"><span style={{ width: (top ? Math.max(2, r.amount / top * 100) : 0) + '%' }} /></span>
            <span className="ranking-meta">{r.count} {r.count === 1 ? 'venda' : 'vendas'}{' · ticket '}
              {r.avgTicket === null ? '—' : currency(r.avgTicket)}{' · '}{String(r.share).replace('.', ',')}% do total
              {' · antes '}{currency(r.previousAmount)} <Variation value={r.variation} /></span>
          </span>
        </button>)}</div>}
      <div className="filter-bar">{(['pdf', 'xlsx', 'csv'] as const).map(format =>
        <button className="outline-action" key={format} onClick={() => void download(format, query)
          .catch(error => notify(error.message, true))}>Exportar {format.toUpperCase()}</button>)}</div>
     </>}
  </div>;
}
