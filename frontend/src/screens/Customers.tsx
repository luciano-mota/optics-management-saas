import { useState } from 'react';
import { api, date, fileAsBase64 } from '../api';
import type { ScreenProps } from './common';
import { FormField, getField, perform, useRemote, PhoneField } from './common';
import { AddressFields } from './AddressFields';

export function Customers({ notify }: ScreenProps) {
  const [query, setQuery] = useState('');
  const [months, setMonths] = useState('');
  const [selected, setSelected] = useState<any | null>(null);
  const [files, setFiles] = useState<any[]>([]);
  const { data, loading, refresh } = useRemote<any[]>(
    '/customers?q=' + encodeURIComponent(query) + (months ? '&months=' + months : '')
  );

  async function showFiles(customer: any) {
    setSelected(customer);
    try { setFiles(await api<any[]>('/attachments/customers/' + customer.id)); }
    catch (error) { notify(String(error), true); }
  }

  async function upload(file: File, customer: any) {
    const dataBase64 = await fileAsBase64(file);
    await api('/attachments/customers/' + customer.id, {
      method: 'POST', body: { filename: file.name, mime: file.type, dataBase64 },
    });
    setFiles(await api<any[]>('/attachments/customers/' + customer.id));
    notify('Receita anexada com sucesso.');
  }

  return <div className="screen-stack">
    <div className="section-title"><div><h2>Meus clientes</h2>
      <p>Cadastre, consulte e acompanhe a próxima troca de óculos.</p></div></div>
    <div className="surface">
      <h3>Cadastrar cliente</h3>
      <form className="form-grid" onSubmit={event => {
        event.preventDefault();
        const form = event.currentTarget;
        void perform(() => api('/customers', { method: 'POST', body: {
          fullName: getField(form, 'fullName'), cpf: getField(form, 'cpf'),
          birthDate: getField(form, 'birthDate'), phone: getField(form, 'phone'),
          address: getField(form, 'address'), email: getField(form, 'email') || null,
          emailOptIn: new FormData(form).get('emailOptIn') === 'on',
        } }), notify, () => { form.reset(); refresh(); });
      }}>
        <FormField name="fullName" title="Nome completo" required />
        <FormField name="cpf" title="CPF" required />
        <FormField name="birthDate" title="Data de nascimento" type="date" required />
        <PhoneField name="phone" title="WhatsApp ou telefone" />
        <FormField name="email" title="E-mail (opcional)" type="email" />
        <label className="checkline span-all"><input type="checkbox" name="emailOptIn" />
          <span>O cliente autoriza receber mensagens da ótica por e-mail (ex.: parabéns no aniversário). Pode cancelar quando quiser.</span></label>
        <AddressFields required />
        <button className="primary-action form-action" type="submit">Salvar cliente</button>
      </form>
    </div>
    <div className="surface">
      <h3>Consultar clientes</h3>
      <div className="filter-bar">
        <label className="field">Pesquisar<input value={query} onChange={e => setQuery(e.target.value)}
          placeholder="Nome, CPF ou telefone" /></label>
        <label className="field">Tempo desde a última compra
          <select value={months} onChange={e => setMonths(e.target.value)}>
            <option value="">Todos os clientes</option>
            {[3,6,9,12].map(n => <option key={n} value={n}>{n} meses</option>)}
          </select></label>
      </div>
      {loading ? <p>Buscando clientes...</p> :
        !data?.length ? <p className="empty">Nenhum cliente encontrado.</p> :
        <div className="table-wrap"><table><thead><tr><th>Cliente</th><th>Contato</th>
          <th>Última compra</th><th>Tempo</th><th>Receitas</th></tr></thead><tbody>
          {data.map(row => <tr key={row.id}><td><strong>{row.full_name}</strong>
            <small>{row.cpf || 'CPF não informado'}</small></td><td>{row.phone || '—'}</td>
            <td>{date(row.last_sale_on)}</td><td>{row.months_since_sale == null ? '—' : row.months_since_sale + ' meses'}</td>
            <td><button className="text-action" onClick={() => void showFiles(row)}>Ver receitas</button></td></tr>)}
        </tbody></table></div>}
    </div>
    {selected && <div className="surface">
      <div className="section-title"><h3>Receitas de {selected.full_name}</h3>
        <button className="outline-action" onClick={() => setSelected(null)}>Fechar</button></div>
      <input type="file" accept=".pdf,image/jpeg,image/png,image/webp" aria-label="Anexar receita"
        onChange={e => { const file = e.target.files?.[0]; if (file) void upload(file, selected)
          .catch(error => notify(String(error), true)); }} />
      <div className="list-cards">{files.length ? files.map(file => <div className="mini-row" key={file.id}>
        <span>{file.original_name}</span><button className="text-action" onClick={() => {
          window.open('/api/v1/attachments/' + file.id + '/download', '_blank', 'noopener,noreferrer');
        }}>Abrir</button>
      </div>) : <p>Nenhuma receita anexada.</p>}</div>
    </div>}
  </div>;
}
