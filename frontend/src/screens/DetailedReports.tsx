import { useEffect, useState } from 'react';
import { SlidersHorizontal, X } from 'lucide-react';
import { currency } from '../api';
import { useRemote } from './common';

export type Kind = 'vendas' | 'consultas' | 'recibos' | 'pacientes';
type Preview = {
  title: string; headers: string[]; money: boolean[]; rows: string[][]; totalRows: number;
  truncated: boolean; summary: string[]; groups: Record<string, string | number>[];
};
/** Filtros na mesma forma da query string da API. */
export type Filters = Partial<Record<'from' | 'to' | 'status' | 'clinicId' | 'sellerId' | 'frameBrand' | 'frameCategory'
  | 'lensBrand' | 'lensCategory' | 'paymentMethod' | 'origin' | 'customer', string>>;
/** Pedido vindo de fora (atalho por pergunta ou clique num ranking) para abrir um relatório já filtrado. */
export type ReportRequest = { kind: Kind; filters: Filters; nonce: number };
type SalesOptions = {
  sellers: { id: string; name: string }[]; lensBrands: string[]; frameBrands: string[];
  lensCategories: Record<string, string>; frameCategories: Record<string, string>;
  payments: Record<string, string>; statuses: Record<string, string>; origins: Record<string, string>;
};

const REPORTS: { kind: Kind; label: string; permission: string; help: string;
  status?: Record<string, string>; clinic?: boolean }[] = [
  { kind: 'vendas', label: 'Vendas', permission: 'SALES_READ',
    help: 'Consulte as vendas por funcionária, marca e tipo de lente, marca e categoria de armação, pagamento, origem ou cliente. Taxas e líquido aparecem para quem vê o financeiro.',
    status: { AWAITING_PRODUCTION: 'Aguardando produção', READY_FOR_DELIVERY: 'Pronto para entrega',
      DELIVERED: 'Entregue', CANCELLED: 'Cancelado' } },
  { kind: 'consultas', label: 'Consultas', permission: 'APPOINTMENTS_READ', clinic: true,
    help: 'Consultas por clínica parceira e situação. A consulta só conta como convertida quando o paciente comprou na ótica a partir da data dela; paciente nunca conta como venda.',
    status: { SCHEDULED: 'Agendado', CONFIRMED: 'Confirmado', COMPLETED: 'Consulta realizada',
      NO_SHOW: 'Não compareceu', CANCELLED: 'Cancelado' } },
  { kind: 'recibos', label: 'Recibos', permission: 'RECEIPTS_READ',
    help: 'Recibos emitidos no período. O recibo fica como estornado quando o pagamento da venda foi estornado.',
    status: { VALID: 'Válido', REVERSED: 'Estornado' } },
  { kind: 'pacientes', label: 'Pacientes', permission: 'PATIENTS_READ', clinic: true,
    help: 'Pacientes das consultas, com quantas consultas tiveram e se compraram na ótica. Sem CPF, telefone ou endereço. Com período ou clínica, entram só os pacientes com consulta nesse filtro.' },
];

export function monthStart() {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), 1).toLocaleDateString('sv-SE');
}
export function today() {
  return new Date().toLocaleDateString('sv-SE');
}

async function download(kind: Kind, format: 'pdf' | 'xlsx' | 'csv', query: string) {
  const response = await fetch('/api/v1/reports/detailed/' + kind + '/export/' + format + '?' + query,
    { credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || 'Não foi possível gerar o relatório.');
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url; link.download = 'optics-' + kind + '.' + format;
  link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 3000);
}

function cell(value: string, money: boolean) {
  return money && value !== '' ? currency(Number(value)) : value;
}

function groupValue(key: string, value: string | number | undefined) {
  if (value === undefined) return key.startsWith('Valor') ? currency(0) : '0';
  return key.startsWith('Valor') ? currency(Number(value)) : String(value);
}

function groupKeys(groups: Record<string, string | number>[]) {
  const keys: string[] = [];
  groups.forEach(g => Object.keys(g).forEach(k => { if (!keys.includes(k)) keys.push(k); }));
  return keys;
}

function ClinicFilter({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const clinics = useRemote<{ id: string; name: string }[]>('/optometry/clinics');
  if (!Array.isArray(clinics.data) || !clinics.data.length) return null;
  return <label className="field">Clínica<select value={value} onChange={e => onChange(e.target.value)}>
    <option value="">Todas</option>
    {clinics.data.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>;
}

function Choice({ label, value, onChange, options, empty = 'Todas', none }: {
  label: string; value: string; onChange: (v: string) => void; options: [string, string][]; empty?: string; none?: string;
}) {
  return <label className="field">{label}<select value={value} onChange={e => onChange(e.target.value)}>
    <option value="">{empty}</option>
    {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
    {none && <option value="NONE">{none}</option>}
  </select></label>;
}

/** Filtros da consulta de vendas: os principais à vista e o resto em "Mais filtros". */
function SalesFilters({ filters, set }: { filters: Filters; set: (key: keyof Filters, value: string) => void }) {
  const options = useRemote<SalesOptions>('/reports/detailed/vendas/options').data;
  const extraActive = !!(filters.frameCategory || filters.origin || filters.customer || filters.status);
  const [more, setMore] = useState(extraActive);
  useEffect(() => { if (extraActive) setMore(true); }, [extraActive]);
  if (!options) return null;
  const entries = (m: Record<string, string>) => Object.entries(m);
  const brands = (list: string[]) => list.map(b => [b, b] as [string, string]);
  return <>
    <Choice label="Funcionária" value={filters.sellerId ?? ''} onChange={v => set('sellerId', v)}
      options={options.sellers.map(s => [s.id, s.name])} />
    <Choice label="Marca da armação" value={filters.frameBrand ?? ''} onChange={v => set('frameBrand', v)}
      options={brands(options.frameBrands)} />
    <Choice label="Marca da lente" value={filters.lensBrand ?? ''} onChange={v => set('lensBrand', v)}
      options={brands(options.lensBrands)} />
    <Choice label="Tipo de lente" value={filters.lensCategory ?? ''} onChange={v => set('lensCategory', v)}
      options={entries(options.lensCategories)} empty="Todos" none="Não informado" />
    <Choice label="Pagamento" value={filters.paymentMethod ?? ''} onChange={v => set('paymentMethod', v)}
      options={entries(options.payments)} />
    {more ? <>
      <Choice label="Categoria da armação" value={filters.frameCategory ?? ''} onChange={v => set('frameCategory', v)}
        options={entries(options.frameCategories)} none="Não informada" />
      <Choice label="Origem" value={filters.origin ?? ''} onChange={v => set('origin', v)} options={entries(options.origins)} />
      <Choice label="Situação" value={filters.status ?? ''} onChange={v => set('status', v)} options={entries(options.statuses)} />
      <label className="field">Cliente (nome ou CPF)<input value={filters.customer ?? ''} maxLength={120}
        onChange={e => set('customer', e.target.value)} placeholder="Ex.: Maria" /></label>
    </> : <button type="button" className="text-action filter-more" onClick={() => setMore(true)}>
      <SlidersHorizontal size={15} /> Mais filtros</button>}
  </>;
}

/** Relatórios detalhados com prévia na tela e exportação nos mesmos filtros. */
export function DetailedReports({ notify, can, request }: { notify: (m: string, error?: boolean) => void;
  can?: (permission: string) => boolean; request?: ReportRequest | null }) {
  const available = REPORTS.filter(r => !can || can(r.permission));
  const [kind, setKind] = useState<Kind>(available[0]?.kind ?? 'vendas');
  const [filters, setFilters] = useState<Filters>({ from: monthStart(), to: today() });
  const [customer, setCustomer] = useState('');
  useEffect(() => {
    if (!request || !available.some(r => r.kind === request.kind)) return;
    setKind(request.kind);
    setFilters({ from: monthStart(), to: today(), ...request.filters });
    setCustomer(request.filters.customer ?? '');
    document.getElementById('relatorios-detalhados')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [request?.nonce]); // eslint-disable-line react-hooks/exhaustive-deps
  // A busca por cliente espera a pessoa parar de digitar.
  useEffect(() => {
    const timer = window.setTimeout(() => setFilters(f => ({ ...f, customer: customer || undefined })), 400);
    return () => window.clearTimeout(timer);
  }, [customer]);
  const report = REPORTS.find(r => r.kind === kind)!;
  const set = (key: keyof Filters, value: string) => {
    if (key === 'customer') { setCustomer(value); return; }
    setFilters(f => ({ ...f, [key]: value || undefined }));
  };
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => {
    if (!v) return;
    if (k === 'clinicId' && !report.clinic) return;
    if (k === 'status' && !report.status) return;
    if (kind !== 'vendas' && !['from', 'to', 'status', 'clinicId'].includes(k)) return;
    params.set(k, v);
  });
  const query = params.toString();
  const preview = useRemote<Preview>('/reports/detailed/' + kind + '?' + query);
  if (!available.length) return null;
  const invalidPeriod = filters.from && filters.to && filters.from > filters.to;
  const activeExtra = kind === 'vendas' ? Object.keys(filters).filter(k => !['from', 'to'].includes(k) && filters[k as keyof Filters]).length : 0;

  return <div className="surface" id="relatorios-detalhados">
    <h3>Relatórios detalhados</h3>
    <div className="day-chips">{available.map(r =>
      <button type="button" key={r.kind} className={'day-chip' + (r.kind === kind ? ' active' : '')}
        onClick={() => { setKind(r.kind); setFilters(f => ({ from: f.from, to: f.to })); setCustomer(''); }}>{r.label}</button>)}</div>
    <p className="helper">{report.help}</p>
    <div className="filter-bar">
      <label className="field">De<input type="date" value={filters.from ?? ''} onChange={e => set('from', e.target.value)} /></label>
      <label className="field">Até<input type="date" value={filters.to ?? ''} onChange={e => set('to', e.target.value)} /></label>
      {kind === 'vendas' ? <SalesFilters filters={{ ...filters, customer }} set={set} /> : <>
        {report.status && <Choice label="Situação" value={filters.status ?? ''} onChange={v => set('status', v)}
          options={Object.entries(report.status)} />}
        {report.clinic && (!can || can('APPOINTMENTS_READ')) &&
          <ClinicFilter value={filters.clinicId ?? ''} onChange={v => set('clinicId', v)} />}
      </>}
      <button type="button" className="text-action" onClick={() => setFilters(f => ({ ...f, from: undefined, to: undefined }))}>
        Todo o período</button>
      {activeExtra > 0 && <button type="button" className="text-action" onClick={() => {
        setFilters(f => ({ from: f.from, to: f.to })); setCustomer('');
      }}><X size={14} /> Limpar filtros ({activeExtra})</button>}
    </div>
    {invalidPeriod ? <p className="calendar-warning">A data inicial deve ser anterior ou igual à data final.</p> :
     preview.loading ? <p className="empty">Gerando o relatório...</p> :
     !preview.data ? <p className="empty">Não foi possível carregar o relatório. Confira os filtros e as suas permissões.</p> :
     <>
      {preview.data.summary.map((line, i) => <p className="helper" key={i}><strong>{line}</strong></p>)}
      {preview.data.groups.length > 0 && <div className="table-wrap"><table>
        <thead><tr>{groupKeys(preview.data.groups).map(h => <th key={h}>{h}</th>)}</tr></thead>
        <tbody>{preview.data.groups.map((g, i) => <tr key={i}>{groupKeys(preview.data!.groups).map((h, c) =>
          <td key={h}>{c === 0 ? g[h] : groupValue(h, g[h])}</td>)}</tr>)}</tbody></table></div>}
      {preview.data.totalRows === 0 ? <p className="empty">Nenhum registro com esses filtros.</p> :
       <div className="table-wrap"><table>
        <thead><tr>{preview.data.headers.map(h => <th key={h}>{h}</th>)}</tr></thead>
        <tbody>{preview.data.rows.map((row, i) => <tr key={i}>{row.map((value, c) =>
          <td key={c}>{cell(value, preview.data!.money[c])}</td>)}</tr>)}</tbody></table></div>}
      {preview.data.totalRows > preview.data.rows.length && <p className="helper">
        Mostrando {preview.data.rows.length} de {preview.data.totalRows} linhas. A exportação traz todas
        {preview.data.truncated ? ' (até 5.000; use um período menor para ver o restante)' : ''}.</p>}
      <div className="filter-bar">{(['pdf', 'xlsx', 'csv'] as const).map(format =>
        <button className="outline-action" key={format} onClick={() => void download(kind, format, query)
          .catch(error => notify(error.message, true))}>Exportar {format.toUpperCase()}</button>)}</div>
     </>}
  </div>;
}
