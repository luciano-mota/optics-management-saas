import { useState } from 'react';
import { firstName, whatsappUrl } from '../whatsapp';
import { AlertTriangle, MessageCircle } from 'lucide-react';
import { api, currency, date } from '../api';
import type { ScreenProps } from './common';
import { FormField, getField, perform, PhoneField, useRemote } from './common';

type Tab = 'os' | 'stock' | 'suppliers';
const OS_STATUS: Record<string, string> = { ABERTA: 'Aberta', ENVIADA: 'No laboratório', RECEBIDA: 'Pronta na loja',
  ENTREGUE: 'Entregue', CANCELADA: 'Cancelada' };
/** Próxima etapa de cada situação (o mesmo fluxo do servidor). */
const NEXT: Record<string, [string, string][]> = {
  ABERTA: [['ENVIADA', 'Enviar ao laboratório'], ['RECEBIDA', 'Pronta na loja']],
  ENVIADA: [['RECEBIDA', 'Chegou na loja']],
  RECEBIDA: [['ENTREGUE', 'Entregar ao cliente']],
};
const KIND: Record<string, string> = { LENTES: 'Lentes (laboratório)', CONSERTO: 'Conserto' };
const SUPPLIER_KIND: Record<string, string> = { FORNECEDOR: 'Fornecedor', LABORATORIO: 'Laboratório', AMBOS: 'Fornecedor e laboratório' };

function whatsapp(phone: string | null, text: string) {
  return whatsappUrl(phone, text) ?? '#';
}

function ServiceOrders({ notify, can }: ScreenProps) {
  const [filter, setFilter] = useState('EM_ANDAMENTO');
  const orders = useRemote<any[]>('/service-orders?status=' + filter);
  const customers = useRemote<any[]>('/customers');
  const suppliers = useRemote<any[]>('/suppliers');
  const [kind, setKind] = useState('LENTES');
  const writable = !can || can('SALES_DELIVER');
  const labs = (suppliers.data ?? []).filter(s => s.active && s.kind !== 'FORNECEDOR');
  return <>
    {writable && <form className="form-grid" onSubmit={e => {
      e.preventDefault(); const form = e.currentTarget;
      void perform(() => api('/service-orders', { method: 'POST', body: {
        kind, customerId: getField(form, 'customerId'), supplierId: getField(form, 'supplierId') || null,
        description: getField(form, 'description'), expectedOn: getField(form, 'expectedOn') || null,
        price: getField(form, 'price') || null } }), notify, () => { form.reset(); orders.refresh(); });
    }}>
      <label className="field">Tipo<select value={kind} onChange={e => setKind(e.target.value)}>
        <option value="LENTES">Lentes (pedido ao laboratório)</option><option value="CONSERTO">Conserto de óculos</option></select></label>
      <label className="field">Cliente<select name="customerId" required defaultValue="">
        <option value="" disabled>Escolha o cliente</option>
        {customers.data?.map(c => <option key={c.id} value={c.id}>{c.full_name}</option>)}</select></label>
      <label className="field">Laboratório<select name="supplierId" defaultValue="">
        <option value="">{kind === 'CONSERTO' ? 'Na própria loja' : 'Escolha (opcional)'}</option>
        {labs.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
      <FormField name="description" title={kind === 'LENTES' ? 'Pedido (lente, grau, tratamento)' : 'O que consertar'} required />
      <FormField name="expectedOn" title="Previsão de pronto" type="date" />
      {kind === 'CONSERTO' && <FormField name="price" title="Valor do conserto (R$)" type="number" />}
      <button className="primary-action form-action">Abrir ordem de serviço</button>
    </form>}
    <div className="day-chips os-filter">{[['EM_ANDAMENTO', 'Em andamento'], ['ENTREGUE', 'Entregues'], ['', 'Todas']].map(([v, l]) =>
      <button key={v} type="button" className={'day-chip' + (filter === v ? ' active' : '')} onClick={() => setFilter(v)}>{l}</button>)}</div>
    {!orders.data?.length ? <p className="empty">Nenhuma ordem de serviço aqui.</p> :
      <div className="table-wrap"><table><thead><tr><th>Nº</th><th>Cliente</th><th>Serviço</th><th>Previsão</th>
        <th>Situação</th><th>Próxima etapa</th></tr></thead><tbody>{orders.data.map(o => <tr key={o.id}>
        <td><strong>{o.number}</strong></td>
        <td>{o.customer}<small>{o.supplier || (o.kind === 'CONSERTO' ? 'Na loja' : 'Laboratório não informado')}</small></td>
        <td>{KIND[o.kind]}<small>{o.description}{o.price != null ? ' · ' + currency(o.price) : ''}</small></td>
        <td>{o.expected_on ? date(o.expected_on) : '—'}{o.late && <small className="late"><AlertTriangle size={12} /> Atrasada</small>}</td>
        <td><span className={'status-pill ' + (o.status === 'RECEBIDA' ? 'ok' : o.status === 'CANCELADA' ? 'danger' : 'muted')}>
          {OS_STATUS[o.status]}</span></td>
        <td className="row-actions">{writable && (NEXT[o.status] ?? []).map(([next, label]) =>
          <button key={next} className="text-action" onClick={() => void perform(() => api('/service-orders/' + o.id + '/status',
            { method: 'PATCH', body: { status: next } }), notify, orders.refresh)}>{label}</button>)}
          {o.status === 'RECEBIDA' && o.customer_phone && <a className="text-action" target="_blank" rel="noreferrer"
            href={whatsapp(o.customer_phone, 'Olá, ' + firstName(o.customer) + '! Seus óculos já estão prontos na ótica. '
              + 'Pode vir buscar quando quiser.')}><MessageCircle size={14} /> Avisar cliente</a>}
          {writable && !['ENTREGUE', 'CANCELADA'].includes(o.status) && <button className="text-action danger" onClick={() => {
            if (window.confirm('Cancelar a OS nº ' + o.number + '?')) void perform(() => api('/service-orders/' + o.id + '/status',
              { method: 'PATCH', body: { status: 'CANCELADA' } }), notify, orders.refresh);
          }}>Cancelar</button>}</td>
      </tr>)}</tbody></table></div>}
  </>;
}

function StockTab({ notify, can }: ScreenProps) {
  const stock = useRemote<any[]>('/stock');
  const catalog = useRemote<any[]>('/catalog');
  const suppliers = useRemote<any[]>('/suppliers');
  const [history, setHistory] = useState<string | null>(null);
  const writable = !can || can('CATALOG_WRITE');
  const untracked = (catalog.data ?? []).filter(c => c.active && !(stock.data ?? []).some(s => s.id === c.id));
  const name = (i: any) => (i.kind === 'LENS' ? 'Lente ' : 'Armação ') + i.brand + (i.model ? ' ' + i.model : '');
  const refresh = () => { stock.refresh(); catalog.refresh(); };
  const low = (stock.data ?? []).filter(i => i.low).length;
  return <>
    <p className="helper">Controle o estoque só do que fica na loja (normalmente armações). A venda baixa 1 unidade sozinha e o
      cancelamento devolve. Lente feita sob encomenda não precisa controlar.</p>
    {low > 0 && <p className="calendar-warning"><AlertTriangle size={14} /> {low} {low === 1 ? 'produto está' : 'produtos estão'} no
      estoque mínimo ou abaixo.</p>}
    {!stock.data?.length ? <p className="empty">Nenhum produto com estoque controlado ainda.</p> :
      <div className="table-wrap"><table><thead><tr><th>Produto</th><th>Em estoque</th><th>Mínimo</th><th /></tr></thead>
        <tbody>{stock.data.map(i => <tr key={i.id} className={i.low ? 'low-row' : undefined}>
          <td>{name(i)}</td><td><strong>{i.quantity}</strong>{i.quantity < 0 && <small className="late">Negativo: confira</small>}</td>
          <td>{i.min_stock}</td>
          <td className="row-actions"><button className="text-action" onClick={() => setHistory(history === i.id ? null : i.id)}>Histórico</button>
            {writable && <button className="text-action" onClick={() => void perform(() => api('/stock/items/' + i.id,
              { method: 'PATCH', body: { trackStock: false, minStock: 0 } }), notify, refresh)}>Parar de controlar</button>}</td>
        </tr>)}</tbody></table></div>}
    {history && <StockHistory itemId={history} />}
    {writable && <div className="stock-forms">
      <form className="form-grid" onSubmit={e => {
        e.preventDefault(); const form = e.currentTarget;
        void perform(() => api('/stock/movements', { method: 'POST', body: {
          itemId: getField(form, 'itemId'), kind: getField(form, 'kind'), quantity: Number(getField(form, 'quantity')),
          unitCost: getField(form, 'unitCost') || null, supplierId: getField(form, 'supplierId') || null,
          note: getField(form, 'note') || null } }), notify, () => { form.reset(); refresh(); });
      }}>
        <h4 className="span-all">Entrada ou ajuste</h4>
        <label className="field">Produto<select name="itemId" required defaultValue=""><option value="" disabled>Escolha</option>
          {stock.data?.map(i => <option key={i.id} value={i.id}>{name(i)}</option>)}</select></label>
        <label className="field">Tipo<select name="kind" defaultValue="ENTRADA"><option value="ENTRADA">Entrada (compra)</option>
          <option value="AJUSTE">Ajuste (contagem, perda)</option></select></label>
        <FormField name="quantity" title="Quantidade (ajuste pode ser negativo)" type="number" required />
        <FormField name="unitCost" title="Custo por unidade (R$)" type="number" />
        <label className="field">Fornecedor<select name="supplierId" defaultValue=""><option value="">Não informar</option>
          {suppliers.data?.filter(s => s.active).map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
        <FormField name="note" title="Observação (obrigatória no ajuste)" />
        <button className="primary-action form-action">Registrar</button>
      </form>
      <form className="form-grid" onSubmit={e => {
        e.preventDefault(); const form = e.currentTarget;
        void perform(() => api('/stock/items/' + getField(form, 'item'), { method: 'PATCH', body: {
          trackStock: true, minStock: Number(getField(form, 'min') || 0) } }), notify, () => { form.reset(); refresh(); });
      }}>
        <h4 className="span-all">Controlar estoque de um produto</h4>
        <label className="field">Produto<select name="item" required defaultValue=""><option value="" disabled>Escolha</option>
          {untracked.map(i => <option key={i.id} value={i.id}>{name(i)}</option>)}</select></label>
        <FormField name="min" title="Estoque mínimo (aviso)" type="number" />
        <button className="outline-action form-action">Começar a controlar</button>
      </form>
    </div>}
  </>;
}

const MOVE: Record<string, string> = { ENTRADA: 'Entrada', SAIDA_VENDA: 'Venda', DEVOLUCAO: 'Devolução (venda cancelada)', AJUSTE: 'Ajuste' };

function StockHistory({ itemId }: { itemId: string }) {
  const { data } = useRemote<any[]>('/stock/items/' + itemId + '/movements');
  if (!data) return null;
  return <div className="inline-panel"><h4>Movimentos</h4>
    {!data.length ? <p className="helper">Nenhum movimento.</p> : <div className="list-cards">{data.map(m =>
      <div className="mini-row" key={m.id}><div><strong>{MOVE[m.kind]}: {m.quantity > 0 ? '+' : ''}{m.quantity}</strong>
        <small>{date(m.created_at)} · {m.created_by}{m.supplier ? ' · ' + m.supplier : ''}{m.unit_cost != null ? ' · ' + currency(m.unit_cost) + ' cada' : ''}
          {m.note ? ' · ' + m.note : ''}</small></div></div>)}</div>}
  </div>;
}

function Suppliers({ notify, can }: ScreenProps) {
  const { data, refresh } = useRemote<any[]>('/suppliers');
  const writable = !can || can('CATALOG_WRITE');
  return <>
    {writable && <form className="form-grid" onSubmit={e => {
      e.preventDefault(); const form = e.currentTarget;
      void perform(() => api('/suppliers', { method: 'POST', body: {
        name: getField(form, 'name'), kind: getField(form, 'kind'), document: getField(form, 'document') || null,
        phone: getField(form, 'phone') || null, email: getField(form, 'email') || null,
        contactName: getField(form, 'contactName') || null, notes: null } }), notify, () => { form.reset(); refresh(); });
    }}>
      <FormField name="name" title="Nome" required />
      <label className="field">Tipo<select name="kind" defaultValue="FORNECEDOR">
        {Object.entries(SUPPLIER_KIND).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></label>
      <FormField name="document" title="CNPJ ou CPF (opcional)" />
      <PhoneField name="phone" title="Telefone" />
      <FormField name="email" title="E-mail" type="email" />
      <FormField name="contactName" title="Pessoa de contato" />
      <button className="primary-action form-action">Cadastrar</button>
    </form>}
    {!data?.length ? <p className="empty">Nenhum fornecedor ou laboratório cadastrado.</p> :
      <div className="list-cards">{data.map(s => <div className="mini-row" key={s.id}>
        <div><strong>{s.name}</strong><small>{SUPPLIER_KIND[s.kind]}{s.contact_name ? ' · ' + s.contact_name : ''}
          {s.phone ? ' · ' + s.phone : ''}{!s.active ? ' · Inativo' : ''}</small></div>
        <div className="row-actions">{s.phone && <a className="text-action" target="_blank" rel="noreferrer"
          href={whatsapp(s.phone, 'Olá! Aqui é da ótica.')}><MessageCircle size={14} /> WhatsApp</a>}
          {writable && <button className="text-action" onClick={() => void perform(() => api('/suppliers/' + s.id + '/active',
            { method: 'PATCH', body: { active: !s.active } }), notify, refresh)}>{s.active ? 'Desativar' : 'Reativar'}</button>}</div>
      </div>)}</div>}
  </>;
}

/** Ordens de serviço (laboratório e conserto), estoque e fornecedores. */
export function Stock({ notify, can }: ScreenProps) {
  const [tab, setTab] = useState<Tab>('os');
  return <div className="screen-stack">
    <div className="section-title"><div><h2>Estoque e serviços</h2>
      <p>Acompanhe os pedidos ao laboratório e os consertos, o estoque da loja e os fornecedores.</p></div></div>
    <div className="surface">
      <div className="day-chips">{([['os', 'Ordens de serviço'], ['stock', 'Estoque'], ['suppliers', 'Fornecedores e laboratórios']] as const)
        .map(([key, label]) => <button key={key} type="button" className={'day-chip' + (tab === key ? ' active' : '')}
          onClick={() => setTab(key)}>{label}</button>)}</div>
      <div className="tab-body">
        {tab === 'os' && <ServiceOrders notify={notify} can={can} />}
        {tab === 'stock' && <StockTab notify={notify} can={can} />}
        {tab === 'suppliers' && <Suppliers notify={notify} can={can} />}
      </div>
    </div>
  </div>;
}
