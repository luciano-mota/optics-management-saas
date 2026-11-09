import { useState } from 'react';
import { api, date } from '../api';
import type { ScreenProps } from './common';
import { perform, useRemote } from './common';

type Reminder = {
  saleId: string;
  customerId: string;
  customerName: string;
  phone: string | null;
  milestoneMonths: number;
  replacementMonths: number;
  lastSaleDate: string;
  reminderDate: string;
  daysUntil: number;
  whatsappOpened: boolean;
};

function whatsappLink(person: Reminder): string {
  const digits = String(person.phone || '').replace(/[^0-9]/g, '');
  const formatted = digits.startsWith('55') ? digits : '55' + digits;
  const message = person.milestoneMonths === 12 ?
    'Olá, ' + person.customerName + '! Tudo bem? Já faz um ano desde a compra dos seus óculos. Se desejar, podemos ajudar a cuidar da sua visão. Conte com a nossa ótica!' :
    'Olá, ' + person.customerName + '! Tudo bem? Já se passaram ' + person.milestoneMonths + ' meses desde sua última compra de óculos. Passando para desejar tudo de bom! Caso precise, estamos à disposição.';
  return 'https://wa.me/' + formatted + '?text=' + encodeURIComponent(message);
}

export function Retention({notify}:ScreenProps) {
  const [month,setMonth]=useState('0');
  const [days,setDays]=useState('30');
  const {data,loading,refresh}=useRemote<Reminder[]>('/retention?month='+month+'&days='+days);
  const summary=useRemote<{dueSoon:number;awaitingContact:number}>('/retention/summary');

  async function contact(reminder:Reminder) {
    if(!reminder.phone) {
      notify('Este cliente não tem telefone cadastrado.', true);
      return;
    }
    const link=whatsappLink(reminder);
    window.open(link, '_blank', 'noopener,noreferrer');
    await perform(() => api('/retention/'+reminder.saleId+'/contact-opened', {
      method:'POST',
      body:{milestoneMonths:reminder.milestoneMonths},
    }), notify, () => {refresh();summary.refresh();});
  }

  return <div className="screen-stack">
    <div className="section-title"><div>
      <h2>Clientes para lembrar</h2>
      <p>Veja quem está completando 3, 6, 9 meses ou 1 ano desde a última compra.</p>
    </div></div>
    <div className="metric-grid">
      <div className="metric"><span>Datas próximas ou recentes</span>
        <strong>{summary.data?.dueSoon ?? '—'}</strong></div>
      <div className="metric"><span>Contatos ainda não iniciados</span>
        <strong>{summary.data?.awaitingContact ?? '—'}</strong></div>
    </div>
    <div className="surface">
      <h3>Filtrar lembretes</h3>
      <div className="filter-bar">
        <label className="field">Tempo desde a compra
          <select value={month} onChange={event=>setMonth(event.target.value)}>
            <option value="0">Todos os marcos</option>
            <option value="3">3 meses</option>
            <option value="6">6 meses</option>
            <option value="9">9 meses</option>
            <option value="12">1 ano</option>
          </select>
        </label>
        <label className="field">Próximos dias
          <select value={days} onChange={event=>setDays(event.target.value)}>
            <option value="0">Apenas hoje e atrasados</option>
            <option value="7">7 dias</option>
            <option value="30">30 dias</option>
            <option value="90">90 dias</option>
          </select>
        </label>
      </div>
      <p className="helper">A lista inclui também datas dos últimos 15 dias para não perder oportunidades. A mensagem é aberta no WhatsApp para revisão; o sistema não a envia automaticamente.</p>
      {loading ? <p className="empty">Consultando datas...</p> : !data?.length ?
        <p className="empty">Não há clientes nesse período.</p> :
        <div className="table-wrap"><table><thead><tr>
          <th>Cliente</th><th>Compra</th><th>Marco</th><th>Data do lembrete</th><th>Contato</th>
        </tr></thead><tbody>{data.map(person=><tr key={person.saleId+'-'+person.milestoneMonths}>
          <td><strong>{person.customerName}</strong>
            <small>{person.phone || 'Sem telefone'}</small></td>
          <td>{date(person.lastSaleDate)}</td>
          <td>{person.milestoneMonths===12?'1 ano':person.milestoneMonths+' meses'}
            <small>Troca habitual: {person.replacementMonths} meses</small></td>
          <td>{date(person.reminderDate)}
            <small>{person.daysUntil<0?'Há '+Math.abs(person.daysUntil)+' dias':
              person.daysUntil===0?'Hoje':'Em '+person.daysUntil+' dias'}</small></td>
          <td>
            <button className="outline-action" disabled={!person.phone}
              onClick={()=>void contact(person)}>
              {person.whatsappOpened?'Abrir novamente':'Preparar WhatsApp'}
            </button>
            {person.whatsappOpened&&<small>Conversa aberta anteriormente</small>}
          </td>
        </tr>)}</tbody></table></div>}
    </div>
  </div>;
}
