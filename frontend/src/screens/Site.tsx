import { useEffect, useState } from 'react';
import { ExternalLink, Plus, Trash2 } from 'lucide-react';
import { api, fileAsBase64 } from '../api';
import type { ScreenProps } from './common';
import { perform, useRemote } from './common';
import { OpticSite, type SiteData } from '../site/PublicSite';
import { MOODS, TEMPLATES, type MoodId, type TemplateId } from '../site/theme';
import { FONTS, type FontsId } from '../site/fonts';

const SUGGESTED_SERVICES = [
  { name: 'Óculos de grau', description: 'Armações para todos os estilos, com lentes feitas a partir da sua receita.' },
  { name: 'Óculos de sol', description: 'Proteção UV com estilo, inclusive com grau.' },
  { name: 'Lentes de contato', description: 'Orientação para escolher e usar com conforto.' },
  { name: 'Exame de vista', description: 'Consulta com clínicas parceiras para você sair com a receita em dia.' },
  { name: 'Ajustes e manutenção', description: 'Ajuste de armação, troca de plaquetas e pequenos reparos.' },
];

type Draft = SiteData;

/** Editor do site da ótica no modelo do PhysioEvo: aparência, textos, listas e prévia ao vivo. */
export function Site({ notify, onSaved }: ScreenProps & { onSaved?: () => void }) {
  const { data, refresh } = useRemote<Draft>('/site-settings');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saving, setSaving] = useState(false);
  useEffect(() => { if (data) setDraft({ ...data, subtitle: data.subtitle || '' }); }, [data]);
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft(current => current && { ...current, [key]: value });

  if (data == null) return <div className="screen-stack">
    <div className="section-title"><div><h2>Site e campanhas</h2>
      <p>Uma página da sua ótica na internet, com as suas cores e campanhas.</p></div></div>
    <div className="surface"><div className="inline-panel">
      <span className="eyebrow">Recurso liberado pela LCM DEV TECH</span>
      <h3>Seu site ainda não está habilitado</h3>
      <p>Com o site, sua ótica ganha uma página com serviços, campanhas, atendimento em domicílio,
        perguntas frequentes, mapa e botão de WhatsApp. A ativação é feita pela equipe LCM DEV TECH.</p>
      <div className="filter-bar">
        <a className="outline-action" href="mailto:contato@lcmtech.com.br?subject=Ativacao%20do%20site%20Optics%20Management">
          Solicitar por e-mail</a>
        <a className="outline-action" target="_blank" rel="noopener noreferrer"
          href="https://wa.me/5598920026493?text=Ol%C3%A1%2C%20quero%20solicitar%20a%20ativa%C3%A7%C3%A3o%20do%20site%20da%20minha%20%C3%B3tica%20no%20Optics%20Management.">
          Solicitar pelo WhatsApp</a>
      </div>
    </div></div>
  </div>;
  if (!draft) return <p className="empty">Carregando o site...</p>;

  const link = window.location.origin + '/?site=' + encodeURIComponent(draft.slug);
  const save = () => void perform(async () => {
    setSaving(true);
    try {
      await api('/site-settings', { method: 'PUT', body: {
        primaryColor: draft.primary_color, secondaryColor: draft.secondary_color,
        template: draft.template, mood: draft.mood, fonts: draft.fonts,
        headline: draft.headline, subtitle: draft.subtitle, about: draft.about, hours: draft.hours,
        instagram: draft.instagram, showMap: draft.show_map,
        services: draft.services.filter(s => s.name.trim()),
        testimonials: draft.testimonials.filter(t => t.author.trim() || t.text.trim()),
        faqs: draft.faqs.filter(f => f.question.trim() || f.answer.trim()),
        homeVisitsEnabled: draft.home_visits_enabled, homeVisitTitle: draft.home_visit_title,
        homeVisitDescription: draft.home_visit_description,
      } });
    } finally { setSaving(false); }
  }, notify, () => { refresh(); onSaved?.(); });

  return <div className="screen-stack site-editor">
    <div className="section-title"><div><h2>Site e campanhas</h2>
      <p>Tudo o que você alterar aparece na prévia. Clique em "Salvar site" para publicar.</p></div>
      <div className="filter-bar no-margin">
        <a className="outline-action" href={link} target="_blank" rel="noreferrer"><ExternalLink size={16} /> Abrir meu site</a>
        <button className="primary-action" type="button" disabled={saving} onClick={save}>
          {saving ? 'Salvando...' : 'Salvar site'}</button>
      </div></div>

    <div className="site-editor-grid">
      <div className="screen-stack no-top">
        <section className="surface"><h3>Endereço do site</h3>
          <div className="filter-bar no-margin"><input readOnly value={link} aria-label="Endereço do site" />
            <button className="outline-action" type="button" onClick={() => void navigator.clipboard.writeText(link)
              .then(() => notify('Link copiado.'))}>Copiar link</button></div>
          <p className="helper">Compartilhe no Instagram, no WhatsApp e no Google Meu Negócio.</p>
        </section>

        <section className="surface"><h3>Logo e cores</h3>
          <div className="logo-row">
            {draft.has_logo ? <img className="logo-preview" src={'/api/v1/public/site/' + encodeURIComponent(draft.slug) + '/logo?v=' + Date.now()}
              alt="Logo da ótica" /> : <span className="logo-preview empty-logo">Sem logo</span>}
            <label className="field">Enviar logo (PNG ou JPG, até 2 MB)
              <input type="file" accept="image/png,image/jpeg" onChange={event => {
                const file = event.target.files?.[0]; if (!file) return;
                void perform(async () => {
                  if (file.size > 2 * 1024 * 1024) throw new Error('O arquivo deve ter até 2 MB.');
                  const dataBase64 = await fileAsBase64(file);
                  await api('/site-settings/logo', { method: 'POST', body: { mime: file.type, dataBase64 } });
                }, notify, refresh);
              }} /></label>
          </div>
          <p className="helper">Ao enviar a logo, sugerimos as cores do site a partir dela. Você pode ajustar abaixo.</p>
          <div className="form-grid">
            <label className="field">Cor principal<input type="color" value={draft.primary_color}
              onChange={e => set('primary_color', e.target.value)} /></label>
            <label className="field">Cor de apoio<input type="color" value={draft.secondary_color}
              onChange={e => set('secondary_color', e.target.value)} /></label>
          </div>
        </section>

        <section className="surface"><h3>Visual</h3>
          <p className="helper">Modelo é a organização da página; clima é o fundo; fontes são as letras.</p>
          <div className="choice-grid">{(Object.keys(TEMPLATES) as TemplateId[]).map(id =>
            <button key={id} type="button" className={draft.template === id ? 'choice active' : 'choice'}
              aria-pressed={draft.template === id} onClick={() => set('template', id)}>
              <strong>{TEMPLATES[id].label}</strong><span>{TEMPLATES[id].description}</span></button>)}</div>
          <div className="form-grid">
            <label className="field">Clima<select value={draft.mood} onChange={e => set('mood', e.target.value)}>
              {(Object.keys(MOODS) as MoodId[]).map(id => <option key={id} value={id}>{MOODS[id].label} · {MOODS[id].description}</option>)}
            </select></label>
            <label className="field">Fontes<select value={draft.fonts} onChange={e => set('fonts', e.target.value)}>
              {(Object.keys(FONTS) as FontsId[]).map(id => <option key={id} value={id}>{FONTS[id].label} · {FONTS[id].heading}</option>)}
            </select></label>
          </div>
        </section>

        <section className="surface"><h3>Topo da página</h3>
          <div className="form-grid one">
            <label className="field">Título principal<input value={draft.headline} maxLength={180}
              onChange={e => set('headline', e.target.value)} /></label>
            <label className="field">Frase de apoio<textarea rows={2} maxLength={600} value={draft.subtitle || ''}
              onChange={e => set('subtitle', e.target.value)} /></label>
          </div>
        </section>

        <section className="surface"><h3>Atendimento em domicílio</h3>
          <label className="checkline"><input type="checkbox" checked={draft.home_visits_enabled}
            onChange={e => set('home_visits_enabled', e.target.checked)} />
            Oferecer atendimento em domicílio, com destaque e formulário de agendamento</label>
          <p className="helper">O cliente envia endereço, receita e o melhor dia e horário. Todos os funcionários
            com acesso a visitas recebem o aviso no sistema e por e-mail.</p>
          {draft.home_visits_enabled && <div className="form-grid one">
            <label className="field">Título do destaque<input value={draft.home_visit_title} maxLength={140}
              onChange={e => set('home_visit_title', e.target.value)} /></label>
            <label className="field">Texto do destaque<textarea rows={2} maxLength={600} value={draft.home_visit_description}
              onChange={e => set('home_visit_description', e.target.value)} /></label>
          </div>}
        </section>

        <ListEditor title="Serviços" helper="Até 12. O ícone é escolhido pelo nome (sol, contato, exame, ajuste...)."
          items={draft.services} max={12} empty={{ name: '', description: '' }}
          onChange={items => set('services', items)}
          extra={<button className="text-action" type="button" onClick={() => set('services', SUGGESTED_SERVICES)}>
            Usar sugestões para ótica</button>}
          render={(item, update) => <>
            <label className="field">Nome<input value={item.name} maxLength={80} onChange={e => update({ ...item, name: e.target.value })} /></label>
            <label className="field">Descrição<input value={item.description || ''} maxLength={300}
              onChange={e => update({ ...item, description: e.target.value })} /></label>
          </>} />

        <section className="surface"><h3>Sobre a ótica e contato</h3>
          <div className="form-grid one">
            <label className="field">Sobre a ótica<textarea rows={4} maxLength={2000} value={draft.about || ''}
              placeholder="Conte a história da ótica, há quanto tempo atende, o que a torna especial."
              onChange={e => set('about', e.target.value)} /></label>
            <label className="field">Horários<input value={draft.hours || ''} maxLength={300}
              placeholder="Seg a sex, 8h às 18h · Sáb, 8h às 12h" onChange={e => set('hours', e.target.value)} /></label>
            <label className="field">Instagram<input value={draft.instagram || ''} maxLength={60} placeholder="@suaotica"
              onChange={e => set('instagram', e.target.value)} /></label>
            <label className="checkline"><input type="checkbox" checked={draft.show_map}
              onChange={e => set('show_map', e.target.checked)} /> Mostrar mapa do endereço</label>
            <p className="helper">Endereço, WhatsApp e e-mail vêm do cadastro da ótica{draft.address ? ': ' + draft.address : '.'}</p>
          </div>
        </section>

        <ListEditor title="Depoimentos" helper="Até 12. Use frases reais de clientes, com autorização."
          items={draft.testimonials} max={12} empty={{ author: '', text: '' }}
          onChange={items => set('testimonials', items)}
          render={(item, update) => <>
            <label className="field">Nome<input value={item.author} maxLength={80} onChange={e => update({ ...item, author: e.target.value })} /></label>
            <label className="field">Depoimento<textarea rows={2} value={item.text} maxLength={500}
              onChange={e => update({ ...item, text: e.target.value })} /></label>
          </>} />

        <ListEditor title="Perguntas frequentes" helper="Até 15 perguntas com resposta."
          items={draft.faqs} max={15} empty={{ question: '', answer: '' }}
          onChange={items => set('faqs', items)}
          render={(item, update) => <>
            <label className="field">Pergunta<input value={item.question} maxLength={200}
              onChange={e => update({ ...item, question: e.target.value })} /></label>
            <label className="field">Resposta<textarea rows={2} value={item.answer} maxLength={800}
              onChange={e => update({ ...item, answer: e.target.value })} /></label>
          </>} />

        <button className="primary-action wide" type="button" disabled={saving} onClick={save}>
          {saving ? 'Salvando...' : 'Salvar site'}</button>
      </div>

      <aside className="site-preview-frame" aria-label="Prévia do site">
        <div className="preview-bar"><span /><span /><span /><small>Prévia</small></div>
        <div className="preview-scroll">
          <OpticSite site={draft} notify={notify} preview />
        </div>
      </aside>
    </div>
  </div>;
}

function ListEditor<T>({ title, helper, items, max, empty, onChange, render, extra }: {
  title: string; helper: string; items: T[]; max: number; empty: T;
  onChange: (items: T[]) => void; render: (item: T, update: (item: T) => void) => React.ReactNode;
  extra?: React.ReactNode;
}) {
  return <section className="surface"><div className="section-title"><h3>{title}</h3>{extra}</div>
    <p className="helper">{helper}</p>
    <div className="list-cards">{items.map((item, index) => <div className="list-editor-row" key={index}>
      <div className="form-grid">{render(item, next => onChange(items.map((old, i) => i === index ? next : old)))}</div>
      <button className="icon-button" type="button" aria-label={'Remover item ' + (index + 1)} title="Remover"
        onClick={() => onChange(items.filter((_, i) => i !== index))}><Trash2 size={17} /></button>
    </div>)}</div>
    {items.length < max && <button className="outline-action list-add" type="button"
      onClick={() => onChange([...items, empty])}><Plus size={16} /> Adicionar</button>}
  </section>;
}
