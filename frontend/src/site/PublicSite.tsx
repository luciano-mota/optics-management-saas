import { useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react';
import { api, fileAsBase64 } from '../api';
import { AddressFields } from '../screens/AddressFields';
import { getField, perform, PhoneField, useRemote, type Notify } from '../screens/common';
import { phoneDigits, phoneMask } from '../phone';
import { SiteIcon, serviceIcon } from './icons';
import { readableTextOn } from './color';
import { resolveMood, resolveTemplate, siteColors, siteColorVariables, siteTheme, type TemplateId } from './theme';
import { FONTS, fontsUrl, resolveFonts } from './fonts';
import './site.css';

export type SiteData = {
  slug: string; trade_name: string; address: string; whatsapp: string | null; contact_phone: string | null;
  contact_email: string | null; primary_color: string; secondary_color: string; template: string; mood: string;
  fonts: string; headline: string; subtitle: string | null; about: string | null; hours: string | null;
  instagram: string | null; show_map: boolean; has_logo: boolean;
  services: { name: string; description?: string | null }[];
  testimonials: { author: string; text: string }[];
  faqs: { question: string; answer: string }[];
  home_visits_enabled: boolean; home_visit_title: string; home_visit_description: string;
};

/** Seções que ficam na faixa forte (cor da ótica, texto claro) em cada modelo — igual ao PhysioEvo. */
const BAND_SECTIONS: Record<TemplateId, string[]> = {
  classico: [], imersivo: ['servicos', 'depoimentos'], editorial: [], acolhedor: ['como']
};

export function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(w => w.length > 2 || /^[A-ZÁ-Ú]/.test(w));
  return (words.length ? words : [name]).slice(0, 2).map(w => w[0]).join('').toUpperCase();
}

function whatsappLink(number: string | null, message: string): string | null {
  const digits = phoneDigits(number || '');
  if (digits.length < 10) return null;
  return 'https://wa.me/55' + digits + '?text=' + encodeURIComponent(message);
}

function mapEmbed(address: string) {
  return 'https://www.google.com/maps?q=' + encodeURIComponent(address) + '&output=embed';
}
function mapLink(address: string) {
  return 'https://www.google.com/maps/search/?api=1&query=' + encodeURIComponent(address);
}

/** Aparência: fontes, cores (com contraste conferido), aba do navegador com nome e logo da ótica. */
export function useSiteLook(site: SiteData, logoVersion = '') {
  const template = resolveTemplate(site.template);
  const mood = resolveMood(site.mood);
  const fonts = resolveFonts(site.fonts);
  useEffect(() => {
    const href = fontsUrl(fonts);
    if (document.querySelector(`link[data-site-fonts="${fonts}"]`)) return;
    const link = document.createElement('link');
    link.rel = 'stylesheet'; link.href = href; link.dataset.siteFonts = fonts;
    document.head.appendChild(link);
  }, [fonts]);
  const logo = site.has_logo ? '/api/v1/public/site/' + encodeURIComponent(site.slug) + '/logo' + logoVersion : null;
  const vars = useMemo(() => {
    const theme = siteTheme(site.primary_color, site.secondary_color);
    return {
      '--color-primary': theme.primary, '--color-on-primary': readableTextOn(theme.primary),
      ...siteColorVariables(siteColors(theme, mood)),
      '--font-heading': `'${FONTS[fonts].heading}', Georgia, serif`,
      '--font-body': `'${FONTS[fonts].body}', system-ui, sans-serif`,
    } as CSSProperties;
  }, [site.primary_color, site.secondary_color, mood, fonts]);
  return { template, mood, logo, vars };
}

/** Página pública /?site=slug. */
export function PublicSitePage({ slug, notify, toast }: {
  slug: string; notify: Notify; toast: { message: string; error: boolean } | null;
}) {
  const { data, loading } = useRemote<SiteData>('/public/site/' + encodeURIComponent(slug));
  useEffect(() => {
    if (!data) return;
    const previousTitle = document.title;
    const favicon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
    const previousIcon = favicon?.getAttribute('href');
    document.title = data.trade_name;
    if (favicon) favicon.href = data.has_logo ? '/api/v1/public/site/' + encodeURIComponent(slug) + '/logo' : '/icon.svg';
    return () => {
      document.title = previousTitle;
      if (favicon) favicon.href = previousIcon || '/icon.svg';
    };
  }, [data?.trade_name, data?.has_logo, slug]); // eslint-disable-line react-hooks/exhaustive-deps
  if (loading) return <div className="center-message">Abrindo a página da ótica...</div>;
  if (!data) return <div className="center-message"><h1>Página indisponível</h1>
    <a href="/">Voltar ao Optics Management</a></div>;
  return <OpticSite site={data} notify={notify} toast={toast} />;
}

/**
 * Site da ótica: topo, informações rápidas, campanha de atendimento em domicílio (quando ligada),
 * serviços, sobre, como funciona, depoimentos, dúvidas e contato com mapa. Cada seção só aparece
 * se tiver conteúdo. Modelo, clima e fontes seguem as mesmas opções do site do PhysioEvo.
 */
export function OpticSite({ site, notify, toast, preview = false }: {
  site: SiteData; notify: Notify; toast?: { message: string; error: boolean } | null; preview?: boolean;
}) {
  const { template, mood, logo, vars } = useSiteLook(site);
  const hello = whatsappLink(site.whatsapp || site.contact_phone,
    'Olá! Vim pelo site da ' + site.trade_name + ' e gostaria de mais informações.');
  const homeVisits = site.home_visits_enabled;
  const hasAbout = !!site.about;
  const showMap = site.show_map && !!site.address;
  const hasContact = !!(site.address || site.hours || hello || site.contact_email || site.instagram);
  const tone = (() => {
    let alt = false;
    return (id: string) => {
      // Com a campanha em casa (faixa forte), os serviços logo abaixo não repetem a faixa.
      const bands = homeVisits ? BAND_SECTIONS[template].filter(band => band !== 'servicos') : BAND_SECTIONS[template];
      if (bands.includes(id)) return 'section band';
      alt = !alt;
      return alt ? 'section' : 'section alt';
    };
  })();
  const brand = logo ? <img className="brand-logo" src={logo} alt={'Logo ' + site.trade_name} />
    : <span className="brand-monogram" aria-hidden="true">{initials(site.trade_name)}</span>;
  const primaryCta = homeVisits
    ? <a className="btn btn-primary btn-lg" href="#visita"><SiteIcon name="home" size={20} /> Agendar visita em casa</a>
    : hello && <a className="btn btn-primary btn-lg" href={hello} target="_blank" rel="noreferrer">
      <SiteIcon name="whatsapp" size={20} /> Falar no WhatsApp</a>;
  const heroText = <div className="hero-text">
    <p className="eyebrow">{site.trade_name}</p>
    <h1>{site.headline}</h1>
    {site.subtitle && <p className="hero-sub">{site.subtitle}</p>}
    <div className="hero-actions">
      {primaryCta}
      {site.services.length > 0 && <a className="btn-text" href="#servicos">Ver serviços <SiteIcon name="arrow" size={18} /></a>}
    </div>
  </div>;

  return <div className={`site tpl-${template} mood-${mood} style-clinico${preview ? ' is-preview' : ''}`} style={vars}>
    <a className="skip" href="#servicos">Pular para os serviços</a>
    <header className="site-nav">
      <div className="wrap nav-inner">
        <a className="site-brand" href="#topo" aria-label={site.trade_name + ', início'}>{brand}<span>{site.trade_name}</span></a>
        <nav className="site-links" aria-label="Seções">
          {homeVisits && <a href="#visita">Atendimento em casa</a>}
          {site.services.length > 0 && <a href="#servicos">Serviços</a>}
          {hasAbout && <a href="#sobre">Sobre</a>}
          {site.faqs.length > 0 && <a href="#duvidas">Dúvidas</a>}
          {hasContact && <a href="#contato">Contato</a>}
        </nav>
        {hello && <a className="btn btn-primary btn-sm" href={hello} target="_blank" rel="noreferrer">WhatsApp</a>}
      </div>
    </header>

    <Hero template={template} site={site} text={heroText} />

    {(site.address || site.hours || homeVisits) && <section className="facts" aria-label="Informações rápidas">
      <ul className="wrap facts-list">
        {homeVisits && <li><SiteIcon name="home" size={22} /><div><strong>Atendimento em casa</strong>
          <span>Levamos armações e lentes até você</span></div></li>}
        {site.hours && <li><SiteIcon name="clock" size={22} /><div><strong>Horários</strong><span>{site.hours}</span></div></li>}
        {site.address && <li><SiteIcon name="pin" size={22} /><div><strong>Onde estamos</strong>
          <span>{cityOf(site.address)}</span></div></li>}
      </ul>
    </section>}

    <main>
      {homeVisits && <section className="section band visit-band" id="visita" aria-labelledby="titulo-visita">
        <div className="wrap visit-grid">
          <div className="visit-intro">
            <p className="eyebrow">Atendimento em domicílio</p>
            <h2 id="titulo-visita">{site.home_visit_title}</h2>
            {site.home_visit_description && <p className="visit-text">{site.home_visit_description}</p>}
            <ul className="visit-points">
              <li><SiteIcon name="check" size={20} />Envie a foto ou o PDF da sua receita</li>
              <li><SiteIcon name="check" size={20} />Escolha o melhor dia e horário</li>
              <li><SiteIcon name="check" size={20} />Nossa equipe confirma pelo WhatsApp</li>
            </ul>
          </div>
          <VisitForm slug={site.slug} notify={notify} toast={toast ?? null} disabled={preview} />
        </div>
      </section>}

      {site.services.length > 0 && <section className={tone('servicos')} id="servicos" aria-labelledby="titulo-servicos">
        <div className="wrap">
          <div className="section-head"><p className="eyebrow">O que oferecemos</p>
            <h2 id="titulo-servicos">Cuidado completo com a sua visão</h2></div>
          <ul className="services">{site.services.map((service, index) => <li className="service" key={index}>
            <span className="service-num" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
            <span className="service-icon"><SiteIcon name={serviceIcon(service.name)} size={26} /></span>
            <div className="service-body"><h3>{service.name}</h3>
              {service.description && <p>{service.description}</p>}
              {hello && <p className="service-meta"><a href={whatsappLink(site.whatsapp || site.contact_phone,
                'Olá! Gostaria de saber mais sobre ' + service.name + '.') || hello} target="_blank" rel="noreferrer">
                Saber mais <SiteIcon name="arrow" size={16} /></a></p>}
            </div></li>)}</ul>
        </div>
      </section>}

      {hasAbout && <section className={tone('sobre')} id="sobre" aria-labelledby="titulo-sobre">
        <div className="wrap about"><div className="about-text">
          <p className="eyebrow">Quem somos</p>
          <h2 id="titulo-sobre">{site.trade_name}</h2>
          <p className="about-body">{site.about}</p>
        </div></div>
      </section>}

      {(hello || homeVisits) && <section className={tone('como')} aria-labelledby="titulo-como">
        <div className="wrap">
          <div className="section-head"><p className="eyebrow">Simples assim</p><h2 id="titulo-como">Como funciona</h2></div>
          <ol className="steps">
            <li><span className="step-n">1</span><h3>Traga sua receita</h3><p>Ou agende um exame de vista com uma clínica parceira.</p></li>
            {homeVisits
              ? <li><span className="step-n">2</span><h3>Escolha onde ser atendido</h3><p>Na loja ou em casa: levamos armações e lentes até você.</p></li>
              : <li><span className="step-n">2</span><h3>Escolha armação e lentes</h3><p>Ajudamos a encontrar o modelo ideal para o seu rosto e a sua rotina.</p></li>}
            <li><span className="step-n">3</span><h3>Receba seus óculos</h3><p>Avisamos quando estiverem prontos e fazemos o ajuste final.</p></li>
          </ol>
        </div>
      </section>}

      {site.testimonials.length > 0 && <section className={tone('depoimentos')} aria-labelledby="titulo-depoimentos">
        <div className="wrap">
          <div className="section-head"><p className="eyebrow">Depoimentos</p><h2 id="titulo-depoimentos">Quem já é cliente</h2></div>
          <div className="testimonials">{site.testimonials.map((t, i) => <figure key={i} className="testimonial">
            <blockquote>{t.text}</blockquote><figcaption>{t.author}</figcaption></figure>)}</div>
        </div>
      </section>}

      {site.faqs.length > 0 && <section className={tone('duvidas')} id="duvidas" aria-labelledby="titulo-duvidas">
        <div className="wrap narrow">
          <div className="section-head"><p className="eyebrow">Dúvidas frequentes</p><h2 id="titulo-duvidas">Perguntas e respostas</h2></div>
          <div className="faq">{site.faqs.map((f, i) => <details key={i}><summary>{f.question}</summary><p>{f.answer}</p></details>)}</div>
        </div>
      </section>}

      {hasContact && <section className={tone('contato')} id="contato" aria-labelledby="titulo-contato">
        <div className={showMap ? 'wrap contact with-map' : 'wrap contact'}>
          <div>
            <p className="eyebrow">Contato</p>
            <h2 id="titulo-contato">Venha nos visitar</h2>
            <ul className="contact-list">
              {site.address && <li><SiteIcon name="pin" size={22} /><div><strong>Endereço</strong>
                <a href={mapLink(site.address)} target="_blank" rel="noreferrer">{site.address}</a></div></li>}
              {site.hours && <li><SiteIcon name="clock" size={22} /><div><strong>Horários</strong><span>{site.hours}</span></div></li>}
              {hello && <li><SiteIcon name="whatsapp" size={22} /><div><strong>WhatsApp</strong>
                <a href={hello} target="_blank" rel="noreferrer">{phoneMask(site.whatsapp || site.contact_phone || '')}</a></div></li>}
              {site.contact_email && <li><SiteIcon name="mail" size={22} /><div><strong>E-mail</strong>
                <a href={'mailto:' + site.contact_email}>{site.contact_email}</a></div></li>}
              {site.instagram && <li><SiteIcon name="chat" size={22} /><div><strong>Instagram</strong>
                <a href={'https://instagram.com/' + site.instagram} target="_blank" rel="noreferrer">@{site.instagram}</a></div></li>}
            </ul>
            {primaryCta}
          </div>
          {showMap && !preview && <iframe className="map" title={'Mapa: ' + site.address} src={mapEmbed(site.address)}
            loading="lazy" referrerPolicy="no-referrer-when-downgrade" />}
          {showMap && preview && <div className="map map-placeholder"><SiteIcon name="pin" size={28} />Mapa do endereço</div>}
        </div>
      </section>}
    </main>

    <footer className="site-footer">
      <div className="wrap footer-inner">
        <div className="footer-brand">{brand}<div><strong>{site.trade_name}</strong>
          {site.address && <span>{cityOf(site.address)}</span>}</div></div>
        <p className="footer-note">© {new Date().getFullYear()} {site.trade_name}
          <a className="footer-credit" href="https://lcmtech.com.br" target="_blank" rel="noopener noreferrer">
            Desenvolvido por LCM DEV TECH</a></p>
      </div>
    </footer>

    {hello && <a className="float-wa" href={hello} target="_blank" rel="noreferrer" aria-label="Conversar pelo WhatsApp">
      <SiteIcon name="whatsapp" size={26} /></a>}
  </div>;
}

/** "Rua A, 10 - Centro, São Luís/MA - CEP ..." → "São Luís/MA". */
function cityOf(address: string): string {
  const match = address.match(/([^,-]+\/[A-Z]{2})(?: - CEP|$)/);
  return match ? match[1].trim() : address;
}

/** Topo sem foto: cada modelo organiza texto e destaques de um jeito (como no PhysioEvo). */
function Hero({ template, site, text }: { template: TemplateId; site: SiteData; text: ReactNode }) {
  if (template === 'imersivo') return <section className="hero hero-immersive" id="topo">
    <div className="hero-shade" aria-hidden="true" /><div className="wrap hero-inner">{text}</div></section>;
  if (template === 'editorial') return <section className="hero hero-editorial" id="topo">
    <div className="wrap hero-inner">{text}</div></section>;
  const top = site.services.slice(0, 4);
  return <section className={template === 'acolhedor' ? 'hero hero-warm' : 'hero hero-split'} id="topo">
    <div className="hero-bg" aria-hidden="true" />
    <div className="wrap hero-inner">{text}
      {top.length > 0 && <div className="hero-visual"><div className="hero-panel"><ul>
        {top.map(s => <li key={s.name}><span className="mini-icon"><SiteIcon name={serviceIcon(s.name)} size={20} /></span>{s.name}</li>)}
      </ul></div></div>}
    </div>
  </section>;
}

/** Formulário da campanha: endereço com CEP, receita anexada, data e hora. Avisa toda a equipe da ótica. */
function VisitForm({ slug, notify, toast, disabled }: {
  slug: string; notify: Notify; toast: { message: string; error: boolean } | null; disabled: boolean;
}) {
  const [submitted, setSubmitted] = useState(false);
  if (submitted) return <div className="visit-card visit-done" role="status">
    <SiteIcon name="check" size={32} /><h3>Pedido recebido!</h3>
    <p>A ótica recebeu sua solicitação e vai entrar em contato pelo WhatsApp para confirmar o horário.</p></div>;
  return <form className="visit-card form-grid" onSubmit={event => {
    event.preventDefault();
    if (disabled) return;
    const form = event.currentTarget;
    void perform(async () => {
      const file = (form.elements.namedItem('prescription') as HTMLInputElement).files?.[0];
      if (!file) throw new Error('Anexe a receita para agendar.');
      const data64 = await fileAsBase64(file);
      await api('/public/site/' + encodeURIComponent(slug) + '/visits', { method: 'POST', body: {
        name: getField(form, 'name'), phone: getField(form, 'phone'), email: getField(form, 'email'),
        address: getField(form, 'address'), appointmentAt: new Date(getField(form, 'appointmentAt')).toISOString(),
        notes: getField(form, 'notes'), privacyAccepted: new FormData(form).get('privacyAccepted') === 'on',
        prescriptionMime: file.type, prescriptionName: file.name, prescriptionBase64: data64,
      } });
    }, notify, () => setSubmitted(true));
  }}>
    <h3 className="span-all">Agende sua visita</h3>
    {toast && <div className={toast.error ? 'toast inline error span-all' : 'toast inline span-all'}>{toast.message}</div>}
    <label className="field">Seu nome<input name="name" required autoComplete="name" /></label>
    <PhoneField name="phone" title="WhatsApp" required />
    <label className="field span-all">E-mail (opcional)<input name="email" type="email" autoComplete="email" /></label>
    <AddressFields legend="Onde será a visita?" required />
    <label className="field">Data e hora<input name="appointmentAt" type="datetime-local" required /></label>
    <label className="field">Receita (PDF ou foto)<input name="prescription" type="file"
      accept=".pdf,image/png,image/jpeg,image/webp" required /></label>
    <label className="field span-all">Observações<textarea name="notes" rows={2} /></label>
    <label className="checkline span-all"><input type="checkbox" name="privacyAccepted" required />
      Concordo em enviar meus dados e a receita à ótica para receber este atendimento.</label>
    <button className="btn btn-primary btn-lg span-all" type="submit" disabled={disabled}>Solicitar visita em casa</button>
  </form>;
}
