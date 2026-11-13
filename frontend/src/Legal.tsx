import type { ReactNode } from 'react';
import { ArrowLeft } from 'lucide-react';
import { BrandLogo } from './brand';

/** Versão vigente dos termos e da política; o aceite registra esta versão. Igual a TERMS_VERSION no backend. */
export const TERMS_VERSION = '2026-11-10';
const VERSION = TERMS_VERSION.split('-').reverse().join('/');
const PRODUCT = 'Optics Management';
const COMPANY = 'LCM DEV TECH';
const COMPANY_LEGAL_URL = 'https://lcmtech.com.br/informacoes-legais';
const SUPPORT_EMAIL = 'contato@lcmtech.com.br';

function Contact() {
  return <a href={'mailto:' + SUPPORT_EMAIL}>{SUPPORT_EMAIL}</a>;
}

function Provider() {
  return <p>O {PRODUCT} é desenvolvido e fornecido pela <strong>{COMPANY}</strong>. Identificação completa
    (responsável, documento, endereço e contato): <a href={COMPANY_LEGAL_URL} target="_blank" rel="noopener noreferrer">
      informações legais</a>.</p>;
}

function LegalPage({ title, children }: { title: string; children: ReactNode }) {
  return <div className="legal-shell">
    <header className="legal-header"><a href="/" aria-label="Voltar ao Optics Management"><BrandLogo /></a></header>
    <main className="legal-page">
      <a className="back-link" href="/"><ArrowLeft size={17} /> Voltar</a>
      <h1>{title}</h1>
      <p className="legal-version">Versão de {VERSION}</p>
      {children}
    </main>
    <footer className="footer"><span>{PRODUCT}</span>
      <a href="https://lcmtech.com.br" target="_blank" rel="noopener noreferrer">Desenvolvido por LCM DEV TECH</a></footer>
  </div>;
}

/** Termos de uso da plataforma (/?termos). */
export function TermsPage() {
  return <LegalPage title="Termos de uso">
    <p>Estes termos regem o uso do {PRODUCT} (“plataforma”), sistema on-line para óticas (“ótica”) organizarem vendas,
      entregas, clientes, receitas, consultas com clínicas parceiras, financeiro, maquininhas, recibos, relatórios, equipe
      e site. Ao criar a conta, a ótica declara ter lido e aceitado estes termos e a <a href="/?privacidade">Política de
      privacidade</a>.</p>
    <Provider />

    <h2>1. Conta e acesso</h2>
    <ul>
      <li>A conta é criada pela pessoa responsável pela ótica (administrador principal), que responde pela veracidade dos
        dados informados, inclusive CNPJ e endereço.</li>
      <li>O administrador principal convida a equipe, define o que cada pessoa acessa e deve remover o acesso de quem
        deixar a ótica. Cada pessoa usa o próprio login; senhas não devem ser compartilhadas.</li>
      <li>Cada ótica enxerga somente os próprios dados.</li>
    </ul>

    <h2>2. Uso permitido</h2>
    <ul>
      <li>Use a plataforma para a gestão da ótica. Receitas e informações de saúde devem ser guardadas apenas nos campos
        próprios (anexos de receita e cadastros de pacientes), com acesso restrito à equipe autorizada.</li>
      <li>É proibido enviar mensagens em massa não solicitadas, publicar conteúdo ilegal no site ou tentar acessar dados de
        outras óticas.</li>
      <li>Mensagens de WhatsApp (lembretes, aniversários, confirmações) só devem ser enviadas a clientes que autorizaram o contato.</li>
    </ul>

    <h2>3. Dados dos clientes e pacientes</h2>
    <p>A ótica é a <strong>controladora</strong> dos dados dos seus clientes e pacientes (Lei 13.709/2018 — LGPD) e decide o
      que cadastra; a plataforma atua como <strong>operadora</strong>, tratando esses dados apenas para prestar o serviço.
      Cabe à ótica definir a base legal de cada tratamento, especialmente de receitas e outros dados de saúde (dados
      sensíveis, art. 11 da LGPD), informar os titulares, controlar os acessos e atender aos pedidos deles (acesso,
      correção, exclusão). O formulário de atendimento em domicílio do site pede a concordância do cliente para enviar
      seus dados e a receita à ótica.</p>

    <h2>4. Valores, taxas e relatórios</h2>
    <p>A plataforma não recebe, não guarda e não repassa dinheiro das vendas da ótica. Taxas de maquininha, prazos de
      recebimento, metas e indicadores são calculados a partir do que a ótica cadastra e servem como apoio à gestão;
      a conferência com o banco e com a contabilidade é responsabilidade da ótica. A plataforma não emite nota fiscal.</p>

    <h2>5. Período de teste e assinatura</h2>
    <p>O período de teste gratuito tem a duração informada no cadastro. Depois dele, o acesso pode ficar limitado até a
      contratação. O valor mensal, o vencimento e o meio de pagamento são exibidos antes da confirmação, e as cobranças
      da assinatura são processadas pelo Asaas. A ótica pode cancelar a assinatura, respeitados valores já devidos.
      A {COMPANY} pode conceder cortesias e liberar recursos adicionais, como o site da ótica.</p>

    <h2>6. Site da ótica</h2>
    <p>Quando liberado, o site exibe as informações e campanhas que a ótica cadastrar, que é responsável pelo conteúdo
      publicado (textos, preços, promoções, depoimentos e imagens) e por ter autorização para usá-lo.</p>

    <h2>7. Disponibilidade e responsabilidade</h2>
    <p>Trabalhamos para manter a plataforma no ar e com cópias de segurança, mas não garantimos funcionamento sem
      interrupções. A plataforma não se responsabiliza por decisões tomadas com base em estimativas nem por informações
      cadastradas incorretamente.</p>

    <h2>8. Encerramento</h2>
    <p>O administrador principal pode solicitar o encerramento pelo suporte (<Contact />). Antes disso, a ótica pode pedir
      uma cópia dos dados. Dados sujeitos a guarda legal não são eliminados automaticamente. Contas usadas em desacordo
      com estes termos podem ser suspensas.</p>

    <h2>9. Alterações</h2>
    <p>Estes termos podem mudar. Quando for exigido novo aceite, a versão vigente será apresentada no sistema, que registra
      a data e a versão aceita.</p>

    <h2>Contato</h2>
    <p>Dúvidas: <Contact />.</p>
  </LegalPage>;
}

/** Política de privacidade (/?privacidade). */
export function PrivacyPage() {
  return <LegalPage title="Política de privacidade">
    <p>Esta política explica quais dados o {PRODUCT} trata, para quê e quais são os seus direitos, conforme a Lei Geral de
      Proteção de Dados (Lei 13.709/2018 — LGPD).</p>
    <Provider />

    <h2>Quem é responsável pelos dados</h2>
    <ul>
      <li><strong>Dados da conta e da ótica</strong> (nome, e-mail, CPF de quem usa o sistema, CNPJ e endereço da ótica):
        a plataforma é a controladora.</li>
      <li><strong>Dados de clientes e pacientes</strong> (cadastrados pela ótica ou enviados pelo site): a ótica é a
        controladora e a plataforma é a operadora, tratando os dados apenas para prestar o serviço à ótica.</li>
    </ul>

    <h2>Quais dados</h2>
    <ul>
      <li>Conta: nome, e-mail, CPF, telefone, data de nascimento e senha (guardada só como código irreversível, nunca em texto).</li>
      <li>Ótica: nome, CNPJ, endereço, WhatsApp, e-mail, logo, textos do site, taxas de maquininhas e dados financeiros lançados.</li>
      <li>Clientes e pacientes: nome, CPF, telefone, endereço, data de nascimento, compras, entregas, consultas,
        preferências de contato e receitas anexadas (foto ou PDF).</li>
      <li>Pedidos de atendimento em domicílio pelo site: nome, WhatsApp, e-mail opcional, endereço, data e hora desejadas,
        observações e a receita enviada.</li>
      <li>Dados técnicos: endereço IP e registros de acesso, usados para segurança (por exemplo, limitar tentativas de login).</li>
    </ul>
    <p>Receitas e demais informações de saúde são <strong>dados pessoais sensíveis</strong>. Ficam visíveis apenas para
      a equipe da ótica com permissão e não são enviadas em e-mails de aviso.</p>

    <h2>Para quê</h2>
    <ul>
      <li>Prestar o serviço contratado: vendas, entregas, lembretes de troca de óculos, consultas, recibos, relatórios e site.</li>
      <li>Identificar o cliente pelo CPF e evitar cadastros duplicados; emitir recibos.</li>
      <li>Avisar a equipe da ótica sobre novos pedidos (sem dados do cliente no e-mail) e enviar e-mails da conta
        (convites, confirmação de e-mail e redefinição de senha).</li>
      <li>Enviar, só para quem autorizou na ótica, lembrete de consulta e mensagem de aniversário por e-mail. Todo e-mail
        traz o link para deixar de receber.</li>
      <li>Segurança e prevenção de abuso (legítimo interesse).</li>
    </ul>

    <h2>Com quem compartilhamos</h2>
    <p>Não vendemos dados. Os dados são processados por prestadores de infraestrutura para operar o sistema e manter
      cópias de segurança (hospedagem e banco de dados), pelo serviço de envio de e-mails (Brevo) e, para a assinatura da
      própria ótica, pelo Asaas, que recebe apenas os dados necessários à cobrança. Quando a ótica gera o link de agenda para uma clínica parceira, a clínica vê o dia, o horário e o primeiro
      nome com a inicial do paciente das consultas marcadas com ela, sem telefone, CPF ou e-mail. A busca de endereço pelo CEP envia
      somente o CEP a serviços públicos de consulta (ViaCEP e BrasilAPI). Mensagens de WhatsApp são abertas no aparelho
      de quem envia (link “wa.me”); a plataforma não usa inteligência artificial com dados de clientes.</p>

    <h2>Cookies</h2>
    <p>Usamos apenas cookies necessários para autenticação e proteção de formulários (sessão e CSRF). Não usamos cookies
      de publicidade nem de rastreamento.</p>

    <h2>Por quanto tempo</h2>
    <p>Os dados são mantidos enquanto necessários à prestação do serviço e para cumprir obrigações legais. Itens
      desativados podem permanecer no histórico (como vendas, pagamentos e recibos). Pedidos de exclusão são analisados
      considerando essas obrigações.</p>

    <h2>Segurança</h2>
    <p>Conexão HTTPS, senhas protegidas por hash, sessão com proteção contra falsificação de requisições, limite de
      tentativas de login, verificação em duas etapas (código do celular) para quem ativar, separação total entre óticas e
      controle de acesso por função dentro de cada ótica.</p>

    <h2>Seus direitos</h2>
    <p>Você pode pedir confirmação, acesso, correção, portabilidade, anonimização ou exclusão dos seus dados e informações
      sobre compartilhamento (art. 18 da LGPD). Clientes e pacientes devem procurar primeiro a ótica que os atende; a
      plataforma ajuda a ótica a atender o pedido.</p>

    <h2>Canal de privacidade</h2>
    <p><Contact />.</p>
  </LegalPage>;
}
