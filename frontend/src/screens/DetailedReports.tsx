import { useState } from 'react';
import { currency } from '../api';
import { useRemote } from './common';

type Kind = 'vendas' | 'consultas' | 'recibos' | 'pacientes';
type Preview = {
  title: string; headers: string[]; money: boolean[]; rows: string[][]; totalRows: number;
  truncated: boolean; summary: string[]; groups: Record<string, string | number>[];
};

const REPORTS: { kind: Kind; label: string; permission: string; help: string;
  status?: Record<string, string>; clinic?: boolean }[] = [
  { kind: 'vendas', label: 'Vendas', permission: 'SALES_READ',
    help: 'Cada venda com cliente, vendedor, produtos, pagamento e origem (venda direta ou paciente de consulta). Taxas e líquido aparecem para quem vê o financeiro.',
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

function monthStart() {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), 1).toLocaleDateString('sv-SE');
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

/** Relatórios detalhados com prévia na tela e exportação nos mesmos filtros. */
export function DetailedReports({ notify, can }: { notify: (m: string, error?: boolean) => void;
  can?: (permission: string) => boolean }) {
  const available = REPORTS.filter(r => !can || can(r.permission));
  const [kind, setKind] = useState<Kind>(available[0]?.kind ?? 'vendas');
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(new Date().toLocaleDateString('sv-SE'));
  const [status, setStatus] = useState('');
  const [clinicId, setClinicId] = useState('');
  const report = REPORTS.find(r => r.kind === kind)!;
  const canClinics = !can || can('APPOINTMENTS_READ');
  const params = new URLSearchParams();
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  if (status && report.status) params.set('status', status);
  if (clinicId && report.clinic) params.set('clinicId', clinicId);
  const query = params.toString();
  const preview = useRemote<Preview>('/reports/detailed/' + kind + '?' + query);
  if (!available.length) return null;
  const invalidPeriod = from && to && from > to;

  return <div className="surface">
    <h3>Relatórios detalhados</h3>
    <div className="day-chips">{available.map(r =>
      <button type="button" key={r.kind} className={'day-chip' + (r.kind === kind ? ' active' : '')}
        onClick={() => { setKind(r.kind); setStatus(''); }}>{r.label}</button>)}</div>
    <p className="helper">{report.help}</p>
    <div className="filter-bar">
      <label className="field">De<input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
      <label className="field">Até<input type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
      {report.status && <label className="field">Situação<select value={status}
        onChange={e => setStatus(e.target.value)}><option value="">Todas</option>
        {Object.entries(report.status).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label>}
      {report.clinic && canClinics && <ClinicFilter value={clinicId} onChange={setClinicId} />}
      <button type="button" className="text-action" onClick={() => { setFrom(''); setTo(''); }}>Todo o período</button>
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
