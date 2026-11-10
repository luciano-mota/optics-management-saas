# Identidade visual, endereço com CEP e localização da ótica

Atualização de 10/11/2026 (branch `claude/hopeful-faraday-5zsg2f`, a partir da `hml`).

## Identidade do Optics Management

- **Logo:** lente em formato de olho com a íris no gradiente "prisma" (ciano → violeta → rosa),
  a luz que se decompõe ao atravessar a lente. Arquivos: `frontend/public/icon.svg`,
  `frontend/src/brand.tsx` (`BrandMark`, `BrandLogo`) e PNGs do PWA (`icon-192`, `icon-512`,
  `icon-maskable-512`, `apple-touch-icon`).
- **Paleta:** tinta `#17153A`, primária `#5B4CF0`, fundo `#F6F6FA`. Diferente do PhysioEvo
  (verde-azulado + coral) e do Ecclesia Finance (azul/verde-azulado). Tokens em `frontend/src/style.css` (`:root`).
- **Fonte:** Plus Jakarta Sans. Manrope e DM Sans continuam disponíveis para o site de cada ótica.
- **Blocos do painel:** cada módulo tem ícone e cor próprios, sem repetição (classes `accent-*`).
  "Site e campanhas" usa o gradiente da marca.
- **Site público da ótica:** usa as cores, a fonte e a logo da própria ótica (`--site-primary`).
  Sites ainda com as cores padrão antigas passam para o padrão novo (migration V16).
- PDFs (relatório de vendas e recibo) e a planilha XLSX usam a nova paleta.

## Endereço com CEP

Componente `frontend/src/screens/AddressFields.tsx`, usado em todos os cadastros com endereço
(clientes, venda com cliente novo, pacientes, clínicas parceiras, convite de funcionário, meus dados,
recibo, endereço da ótica e formulário de visita do site público).

- Ao digitar os 8 números do CEP, rua, bairro, cidade e UF são preenchidos pelo **ViaCEP**
  (com a **BrasilAPI** como alternativa). O foco vai para o número, ou para a rua quando o CEP é geral.
- Se o CEP não for encontrado, a pessoa preenche manualmente.
- O formulário continua enviando um único texto de endereço, no formato
  `Rua, número - complemento - bairro, cidade/UF - CEP 00000-000`, compatível com a API e com
  registros antigos (endereços antigos em texto livre abrem no campo "Rua" para revisão).

## Localização da ótica sem campos técnicos

- O Master informa **CNPJ e endereço da ótica** no primeiro acesso. Óticas já cadastradas sem
  endereço recebem o pedido no próximo login.
- A partir do endereço, o sistema grava sozinho cidade, UF, código IBGE do município e o **fuso
  horário** (deduzido da UF) usados pelo calendário de feriados (`OpticLocationService`).
  A tela de calendário não pede mais UF, código IBGE nem fuso: mostra "Onde fica a ótica"
  e os dias em que a loja abre.
- Para alterar: Calendário → Onde fica a ótica → Alterar endereço (`PUT /api/v1/optic-profile/address`).

## Correções incluídas

- **Datas no horário da ótica** (`OpticClock`): venda, lançamento financeiro, simulação de taxas,
  conversão de visita, painéis de dia/semana/mês, filtros de 3/6/9/12 meses e lembretes. Antes, o
  servidor (UTC) gravava vendas feitas após as 21h com a data do dia seguinte.
- **Limite de tentativas de login por IP real** (`server.forward-headers-strategy: native`): antes,
  atrás do proxy do Render, 30 senhas erradas de qualquer pessoa bloqueavam o login de todas as óticas.
- **Deploy só com CI verde**: recomendado (`autoDeployTrigger: checksPass` / Render → Settings →
  Auto-Deploy → "After CI Checks Pass"), mas **mantido em `commit`** enquanto os minutos do GitHub
  Actions do repositório privado estiverem esgotados; caso contrário o Render nunca publicaria.
- Permissões de "visitas" só aparecem na tela de Equipe quando a ótica tem o atendimento em domicílio liberado.

## Pendências conhecidas

- Feriados **estaduais e municipais** ainda não têm fonte automática: o Master marca na tela de
  calendário. Próximo passo: catálogo de feriados estaduais com fonte legal por UF.

## Equipe: administrador principal (10/11/2026)

- Quem cria a ótica é o **administrador principal** (`optic_memberships.primary_master`, migration V17;
  óticas existentes: o Master ativo mais antigo). Ele nunca é removido, rebaixado nem tem acessos
  alterados por outra pessoa.
- Somente o principal convida, promove, rebaixa ou remove outros administradores master.
  Os demais administradores gerenciam gerentes, secretários(as) e vendedores(as).
- Ninguém altera o próprio acesso. Administradores master têm acesso completo, por isso não há
  "Gerenciar acessos" individuais para eles. Regras em `identity/domain/TeamGovernance`.
- Master e operador LCM não informam endereço pessoal; apenas o endereço da ótica.
