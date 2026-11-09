import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  ArrowLeft, ArrowRight, Bell, CalendarClock, ChartNoAxesCombined,
  CircleHelp, ClipboardCheck, CreditCard, Glasses, HeartHandshake,
  MapPinned, Menu, PackageCheck, ShieldCheck, ShoppingBag, Store,
  UsersRound, Wallet, X,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import './style.css';

type Tile = {
  key: string;
  title: string;
  description: string;
  icon: LucideIcon;
  steps: readonly string[];
  accent: string;
};

const tiles: readonly Tile[] = [
  {
    key: 'sales', title: 'Nova venda', icon: ShoppingBag,
    description: 'Cadastre a venda e o cliente em um só lugar.',
    steps: ['Encontre ou cadastre o cliente.', 'Escolha lentes e armação.', 'Informe o pagamento e conclua.'],
    accent: 'mint',
  },
  {
    key: 'deliveries', title: 'Entregas de óculos', icon: PackageCheck,
    description: 'Veja os pedidos pendentes e confirme a entrega.',
    steps: ['Veja a lista de óculos aguardando.', 'Abra o pedido e confira o cliente.', 'Registre a entrega e quem entregou.'],
    accent: 'blue',
  },
  {
    key: 'customers', title: 'Meus clientes', icon: UsersRound,
    description: 'Consulte clientes, receitas e datas de retorno.',
    steps: ['Pesquise pelo nome ou CPF.', 'Consulte compras e receitas autorizadas.', 'Filtre por tempo desde a última compra.'],
    accent: 'purple',
  },
  {
    key: 'visits', title: 'Visitas em domicílio', icon: MapPinned,
    description: 'Receba pedidos, confirme horários e acompanhe visitas.',
    steps: ['Veja quem solicitou uma visita.', 'Assuma o atendimento e confirme pelo WhatsApp.', 'Registre se a visita virou venda.'],
    accent: 'orange',
  },
  {
    key: 'finance', title: 'Meu financeiro', icon: Wallet,
    description: 'Acompanhe entradas, despesas e valores a receber.',
    steps: ['Registre receitas e despesas.', 'Veja taxas, recebíveis e valores líquidos.', 'Filtre por período e confira o caixa.'],
    accent: 'green',
  },
  {
    key: 'products', title: 'Lentes e armações', icon: Glasses,
    description: 'Organize marcas e opções para vender mais rápido.',
    steps: ['Cadastre as marcas de lentes.', 'Cadastre as marcas de armações.', 'Use essas opções nas próximas vendas.'],
    accent: 'pink',
  },
  {
    key: 'team', title: 'Minha equipe', icon: HeartHandshake,
    description: 'Convide funcionários e escolha o que cada um pode fazer.',
    steps: ['Informe o e-mail do colaborador.', 'Escolha o perfil e as permissões.', 'Envie o convite para criar a senha.'],
    accent: 'blue',
  },
  {
    key: 'reports', title: 'Relatórios', icon: ChartNoAxesCombined,
    description: 'Veja resultados por data, funcionário e produto.',
    steps: ['Selecione o período.', 'Escolha os filtros necessários.', 'Exporte em PDF, Excel ou CSV.'],
    accent: 'purple',
  },
  {
    key: 'payments', title: 'Maquininhas e taxas', icon: CreditCard,
    description: 'Configure taxas de cartão e prazo de recebimento.',
    steps: ['Cadastre sua maquininha.', 'Informe taxas de débito e crédito.', 'Veja o valor líquido ao vender.'],
    accent: 'orange',
  },
  {
    key: 'business', title: 'Minha ótica', icon: Store,
    description: 'Atualize dados, endereço e identidade da sua loja.',
    steps: ['Atualize seus contatos.', 'Revise preferências da ótica.', 'Personalize o site quando estiver habilitado.'],
    accent: 'mint',
  },
] as const;

function App() {
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const selected = tiles.find(tile => tile.key === selectedKey);
  const greetHour = new Date().getHours();
  const greeting = greetHour < 12 ? 'Bom dia' : greetHour < 18 ? 'Boa tarde' : 'Boa noite';

  return (
    <div className="app-shell">
      <a className="skip-link" href="#content">Ir para o conteúdo</a>
      <header className="app-header">
        <div className="header-inner">
          <button className="brand" type="button" onClick={() => setSelectedKey(null)} aria-label="Voltar ao início">
            <span className="brand-mark"><Glasses size={24} strokeWidth={2.5} /></span>
            <span className="brand-words"><strong>Optics</strong><small>MANAGEMENT</small></span>
          </button>
          <nav aria-label="Navegação principal" className={menuOpen ? 'header-nav open' : 'header-nav'}>
            <button type="button" className="nav-link active" onClick={() => { setSelectedKey(null); setMenuOpen(false); }}>Início</button>
            <button type="button" className="nav-link" onClick={() => { setSelectedKey('customers'); setMenuOpen(false); }}>Clientes</button>
            <button type="button" className="nav-link" onClick={() => { setSelectedKey('sales'); setMenuOpen(false); }}>Vendas</button>
            <button type="button" className="nav-link" onClick={() => { setSelectedKey('finance'); setMenuOpen(false); }}>Financeiro</button>
          </nav>
          <div className="header-actions">
            <span className="prototype-label"><ShieldCheck size={14} /> Protótipo inicial</span>
            <button className="icon-button" type="button" aria-label="Abrir área de notificações" title="Notificações — em desenvolvimento" onClick={() => setSelectedKey('notifications')}><Bell size={20} /></button>
            <button className="icon-button mobile-menu" type="button" aria-label={menuOpen ? 'Fechar menu' : 'Abrir menu'} aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}>{menuOpen ? <X size={21} /> : <Menu size={21} />}</button>
          </div>
        </div>
      </header>

      <main id="content" className="container">
        {selected ? (
          <section className="detail-page" aria-labelledby="detail-title">
            <button className="back-link" type="button" onClick={() => setSelectedKey(null)}><ArrowLeft size={17} /> Voltar ao início</button>
            <div className="detail-hero">
              <span className={`tile-icon ${selected.accent}`}><selected.icon size={30} /></span>
              <div><span className="eyebrow">Sua ótica, organizada</span><h1 id="detail-title">{selected.title}</h1><p>{selected.description}</p></div>
            </div>
            <div className="detail-card">
              <span className="status-chip"><CalendarClock size={15} /> Funcionalidade em desenvolvimento</span>
              <h2>Como vai funcionar</h2>
              <ol className="steps">{selected.steps.map(step => <li key={step}>{step}</li>)}</ol>
              <p className="note">Esta é a primeira versão visual. Nenhum dado de cliente é armazenado nesta tela e as operações ainda não estão habilitadas.</p>
            </div>
          </section>
        ) : selectedKey === 'notifications' ? (
          <section className="detail-page">
            <button className="back-link" type="button" onClick={() => setSelectedKey(null)}><ArrowLeft size={17} /> Voltar ao início</button>
            <div className="detail-card"><Bell size={32} /><h1>Notificações</h1><p>Em desenvolvimento: aqui aparecerão as novas visitas, entregas e lembretes da sua ótica.</p><p className="note">Nenhuma notificação real está habilitada.</p></div>
          </section>
        ) : (
          <>
            <section className="welcome">
              <div>
                <div className="eyebrow"><span className="online-dot" /> Seu novo jeito de organizar a ótica</div>
                <h1>{greeting}! <span>O que vamos fazer hoje?</span></h1>
                <p>Escolha uma opção abaixo. A gente te ajuda em cada etapa, sem complicação.</p>
                <div className="welcome-pills">
                  <span><ClipboardCheck size={15} /> Tudo em um só lugar</span>
                  <span><CircleHelp size={15} /> Passo a passo simples</span>
                </div>
              </div>
              <div className="welcome-art" aria-hidden="true"><Glasses size={114} strokeWidth={1.3} /><span className="art-ring" /></div>
            </section>

            <section className="action-section" aria-labelledby="actions-title">
              <div className="section-heading"><div><span className="eyebrow">Acesso rápido</span><h2 id="actions-title">Por onde deseja começar?</h2></div><span className="desktop-helper">Escolha uma área para conhecer o fluxo</span></div>
              <div className="tile-grid">{tiles.map(tile => <button key={tile.key} type="button" className="tile" onClick={() => setSelectedKey(tile.key)} aria-label={`Abrir ${tile.title} (demonstração do fluxo)`}>
                <span className={`tile-icon ${tile.accent}`}><tile.icon size={25} strokeWidth={1.85}/></span>
                <span className="tile-title">{tile.title}</span>
                <span className="tile-description">{tile.description}</span>
                <span className="tile-cta">Conhecer fluxo <ArrowRight size={16}/></span>
              </button>)}</div>
            </section>

            <section className="bottom-note">
              <div className="bottom-note-icon"><ShieldCheck size={22} /></div>
              <div><strong>Uma base construída com cuidado</strong><p>Esta interface é um protótipo de navegação, não um painel conectado. Login, relatórios e dados reais serão liberados após os testes de segurança.</p></div>
            </section>
          </>
        )}
      </main>
      <footer className="footer"><span>Optics Management</span> <span>Desenvolvido pela LCM Dev Tech · Versão inicial</span></footer>
    </div>
  );
}

createRoot(document.getElementById('root')!).render(<App />);
