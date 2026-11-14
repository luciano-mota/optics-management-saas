# Arquitetura — Optics Management

## Princípios
Monólito modular Java 21 / Spring Boot 3.3, banco PostgreSQL com Flyway e frontend React + TypeScript + Vite. DDD tático por contexto, sem microserviços prematuros. Clean Code, SOLID e classes com responsabilidade clara. Comunicação via API /api/v1.

## Contextos
- **platform:** gestão LCM, planos, cortesia, campanhas de avisos e autorização do site por ótica.
- **identity:** contas globais por e-mail, vínculo com óticas e convites; um master ou mais por ótica, nunca zero ativos.
- **authorization:** permissões explícitas e role presets; DENY prevalece; toda operação autenticada conhece tenant do servidor.
- **customers:** clientes de venda e receita anexada com política LGPD.
- **catalog:** marcas e modelos de lentes e armações, histórico de descrição em cada venda.
- **sales:** pedido vinculado a cliente, vendedor, itens, ciclo da entrega, valor bruto e histórico.
- **finance:** receitas/despesas, caixas, taxas, recebíveis, estornos e conciliação.
- **visits:** lead de domicílio, agenda, atribuição atômica, histórico e conversão em cliente + venda.
- **publicsite:** template visual, branding por cor, campanhas, envio de agendamento, somente para óticas habilitadas.
- **notifications:** alertas internos, consentimento e push opt-in.
- **reporting:** CSV, XLSX e PDF com filtros e marca.

Camadas por contexto: domain (regras puras), application (casos de uso), infrastructure (SQL/adaptadores), api (contratos HTTP). JPA não é obrigatório: JDBC com SQL isolado em repositórios favorece legibilidade dos modelos financeiros.

## Regras de isolamento
Uma conta pode pertencer a mais de uma ótica via memberships. O tenant efetivo vem da sessão autenticada e associação validada, jamais de header arbitrário. Toda query de dados de negócio usa optic_id; FKs compostas protegem referências cross-tenant. Plataforma tem autenticação e permissões separadas e não presume acesso a dados comerciais das óticas.

## Segurança e status
Sessão no servidor (JDBC, 45 min sem uso), CSRF obrigatório, senha BCrypt, verificação de e-mail, verificação em duas
etapas (TOTP, com bloqueio por tentativas), limites de tentativa de login e de formulários públicos, convites com
token em hash, cabeçalhos de segurança (CSP, HSTS, X-Frame-Options) e testes de isolamento entre óticas (inclusive
varredura de todas as rotas com ID na auditoria de 14/11/2026). Registro de outra ótica responde 404.

## Convenções
Valores em BigDecimal / NUMERIC(19,2); datas TIMESTAMPTZ em UTC e conversão no front para local. Alterações de banco por migrations novas. Credenciais somente por ambiente. Convites não concedem acesso antes de aceite. Uploads de receita nunca servidos publicamente. Nenhum cache offline de dados pessoais.
