import { useEffect, useState } from 'react';
import { Mail } from 'lucide-react';
import { api, date } from '../api';
import { perform, useRemote } from './common';

type Settings = {
  appointment_reminder_enabled: boolean; birthday_email_enabled: boolean; appointment_template: string;
  birthday_template: string; version: number; emailConfigured: boolean; appointmentFields: string[];
  birthdayFields: string[]; optedInCustomers: number; optedInPatients: number;
};
type Sent = { id: string; kind: string; recipient_email: string; subject: string; status: string; attempts: number;
  last_error: string | null; created_at: string; sent_at: string | null };

const STATUS: Record<string, string> = { PENDING: 'Na fila', SENT: 'Enviado', FAILED: 'Não enviado', CANCELLED: 'Cancelado' };

/** Avisos automáticos por e-mail (só o Master): ligar, editar o texto e acompanhar os envios. */
export function MessageSettings({ notify }: { notify: (m: string, error?: boolean) => void }) {
  const settings = useRemote<Settings>('/messages/settings');
  const outbox = useRemote<Sent[]>('/messages/outbox');
  const [form, setForm] = useState<{ reminder: boolean; birthday: boolean; appointment: string; birthdayText: string } | null>(null);
  useEffect(() => {
    const s = settings.data;
    if (s) setForm({ reminder: s.appointment_reminder_enabled, birthday: s.birthday_email_enabled,
      appointment: s.appointment_template, birthdayText: s.birthday_template });
  }, [settings.data]);
  const s = settings.data;
  if (!s || !form) return null;
  return <div className="surface stacked-section">
    <h3><Mail size={18} className="inline-icon" /> Avisos automáticos por e-mail</h3>
    <p className="helper">O sistema envia sozinho, só para quem autorizou no cadastro, e todo e-mail tem o link para a pessoa
      deixar de receber. Nunca vai CPF nem receita.</p>
    {!s.emailConfigured && <p className="calendar-warning">O envio de e-mail ainda não está configurado no servidor. Os avisos
      ficam registrados como "Não enviado" até a configuração (fale com a LCM DEV TECH).</p>}
    <form className="message-form" onSubmit={e => {
      e.preventDefault();
      void perform(() => api('/messages/settings', { method: 'PUT', body: {
        appointmentReminderEnabled: form.reminder, birthdayEmailEnabled: form.birthday,
        appointmentTemplate: form.appointment, birthdayTemplate: form.birthdayText } }), notify, settings.refresh);
    }}>
      <fieldset>
        <label className="checkline"><input type="checkbox" checked={form.reminder}
          onChange={e => setForm({ ...form, reminder: e.target.checked })} />
          <span><strong>Lembrete de consulta</strong>: no dia anterior, para pacientes que autorizaram ({s.optedInPatients}).</span></label>
        <label className="field">Texto do lembrete<textarea rows={3} maxLength={1000} value={form.appointment}
          onChange={e => setForm({ ...form, appointment: e.target.value })} /></label>
        <small className="helper">Campos: {s.appointmentFields.map(f => '{' + f + '}').join(' ')}</small>
      </fieldset>
      <fieldset>
        <label className="checkline"><input type="checkbox" checked={form.birthday}
          onChange={e => setForm({ ...form, birthday: e.target.checked })} />
          <span><strong>Parabéns de aniversário</strong>: no dia, para clientes que autorizaram ({s.optedInCustomers}).</span></label>
        <label className="field">Texto do aniversário<textarea rows={3} maxLength={1000} value={form.birthdayText}
          onChange={e => setForm({ ...form, birthdayText: e.target.value })} /></label>
        <small className="helper">Campos: {s.birthdayFields.map(f => '{' + f + '}').join(' ')}</small>
      </fieldset>
      <div className="filter-bar"><button className="primary-action">Salvar avisos</button>
        {s.version > 0 && <span className="helper">Versão {s.version} do texto (cada alteração fica registrada).</span>}</div>
    </form>
    <h4>Últimos envios</h4>
    {!outbox.data?.length ? <p className="empty">Nenhum aviso gerado ainda.</p> :
      <div className="table-wrap"><table><thead><tr><th>Quando</th><th>Aviso</th><th>Para</th><th>Situação</th></tr></thead>
        <tbody>{outbox.data.map(m => <tr key={m.id}><td>{date(m.created_at)}</td>
          <td>{m.kind === 'BIRTHDAY' ? 'Aniversário' : 'Lembrete de consulta'}</td><td>{m.recipient_email}</td>
          <td>{STATUS[m.status] ?? m.status}{m.last_error && <small>{m.last_error}</small>}</td></tr>)}</tbody></table></div>}
  </div>;
}
