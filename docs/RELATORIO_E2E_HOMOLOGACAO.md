# Relatório de auditoria e teste ponta a ponta — homologação

> Fase 1 (somente leitura): nenhum código da aplicação foi alterado. Este relatório e a pasta de evidências são os únicos
> arquivos novos, ainda **sem commit** (aguardando autorização).

## Resultado final

**REJEITADO — PROBLEMAS PRECISAM SER CORRIGIDOS**

Motivos (detalhes na seção C):

1. **AUD-01 (P1, isolamento):** a ótica B consegue ler o **nome** de um funcionário da ótica A pelo filtro "vendedor(a)" do relatório de vendas e das exportações.
2. **AUD-02 (P1, financeiro):** cancelar uma venda já recebida mantém a baixa **ativa**, não registra devolução ao cliente e faz o valor "sumir" do caixa do mês, sem trilha.
3. **AUD-03 (P1, regra de negócio):** o cenário obrigatório "venda de R$ 1.000 com pagamento parcial de R$ 300" **não é executável numa venda**. Venda não aceita crediário; o pagamento parcial existe só em lançamento manual, sem ligação com venda ou cliente. O cenário passou integralmente nesse lançamento manual (8/8), mas não como venda.

Pontos fortes comprovados nesta execução:
- isolamento por chaves estrangeiras compostas (`optic_id`, id) no banco;
- varredura de 51 rotas sem alteração de dados da outra ótica;
- MFA com bloqueio em duas camadas;
- CSRF e limites de tentativas funcionando;
- XSS bloqueado;
- os 14 indicadores conferidos contra o banco;
- 124/124 testes do backend e 181/181 do E2E existente.

Este resultado **não** atesta prontidão para produção.

---

## A. Identificação da execução

| Item | Valor |
|---|---|
| Data e hora | 14/11/2026, 11h16–11h45 (Brasília) / 14:16–14:45 UTC |
| Repositório | https://github.com/luciano-mota/optics-management-saas |
| Branch | `hml`, auditada na branch temporária local `audit/hml-e2e-20261114` (idêntica, sem alterações, sem push) |
| Commit | `49027c55fa1956eb1020b5c3ef9a6c2167e37cb1` (merge do PR #11) |
| PRs integrados | #8 (`e6f5661`), #9 (`ddb60ba`), #10 (`40cbfec`), #11 (`49027c5`): confirmados no histórico do `hml` |
| Árvore de trabalho | limpa (`git status` vazio) antes dos testes |
| `hml` × `main` | `hml` 84 commits à frente; `main` tem só o commit inicial `6f71a13`; 230 arquivos, +22.327/−3 linhas. `main` não foi tocada |
| Ambiente de teste | Contêiner isolado da sessão. Backend local `:8080` (JAR do próprio commit), painel `:5173` (Vite dev, E2E) e `:4173` (build de produção via `vite preview`, testes visuais) |
| Banco | PostgreSQL 16.15 local, bancos exclusivos: `optics_management` (testes Maven), `optics_audit` (E2E e auditoria), `optics_docker` (smoke Docker) |
| Navegador | Chromium headless 1243 (Playwright 1.63.0), Node 22.23.4 |
| Docker | Docker Engine 29.9.2 |
| Render hml | Somente leitura: deploy `dep-dbrhcrk9v7es73a8s8gg`, commit `49027c5`, status **live**; logs mostram V23→V26 aplicadas ("Successfully applied 4 migrations… now at version v26") e "Started OpticsManagementApplication in 88.698 seconds" |

**Isolamento garantido:**
- Sem credenciais de e-mail (Brevo/SMTP vazios → nada enviado) e sem chave Asaas (nenhuma chamada de cobrança).
- A rede da sessão só libera `optics-management-hml.onrender.com`, então os serviços de push ficaram inacessíveis.
- Nenhuma variável do Render foi alterada.
- No hml só foram feitas requisições GET e um POST de login inválido; nenhum dado foi criado lá.

**Variáveis locais usadas:**
- `DB_NAME=optics_audit`, `DB_USERNAME=optics`, `DB_PASSWORD=local-dev-only`
- `OPTICS_EMAIL_VERIFICATION_REQUIRED=false`
- `PLATFORM_ADMIN_EMAIL=operador@lcm.test`, `PLATFORM_ADMIN_PASSWORD=SenhaForte2026x`
- `BREVO_API_KEY=`, `SPRING_MAIL_USERNAME=`, `SPRING_MAIL_PASSWORD=`, `ASAAS_API_KEY=`
- `LANG=C.UTF-8`

Script: `evidencias/homologacao-2026-11-14/scripts/start-backend.sh`.

A conta `operador@lcm.test` **não existe no banco por padrão**. Ela é criada no arranque pelo `BootstrapAdmin` a partir das variáveis `PLATFORM_ADMIN_*`; comprovado (login 200, `platformOperator=true`).

## B. Resumo dos testes (números reais desta execução)

| Categoria | Total | Passou | Falhou | Não executado |
|---|---:|---:|---:|---:|
| Backend (`mvn -B verify`) | 124 | 124 | 0 | 0 |
| Frontend (TypeScript + build de produção) | 1 | 1 | 0 | testes unitários e lint: **não existem** no `package.json` |
| Frontend (`npm audit`) | 1 | 0 | 1 (2 vulnerabilidades, só de desenvolvimento) | — |
| Docker (build + smoke como no CI + migrações + usuário não-root) | 8 | 8 | 0 | 0 |
| Render hml (somente leitura: 6 recursos públicos, 7 rotas fechadas, CSRF, 2 × 404, 5 cabeçalhos) | 21 | 20 | 1 (sem CSP) | login real e cookie `Secure` (ver C) |
| E2E existente `e2e/optics-e2e.js` (rodada 1) | 181 | 181 | 0 | 0 |
| E2E existente (rodada 2 seguida, sem reiniciar) | 181 | 181 | 0 | 0 |
| E2E adicional — cadastro (validações) | 7 | 7 | 0 | 0 |
| E2E adicional — funcional (cadastro A/B, clientes, estoque, vendas, financeiro na API e na tela, recibos, consultas, visitas, OS) | 88 | 77 | 11 | 0 |
| Segurança (autenticação, sessão, CSRF, limites, permissões, XSS, SQLi, MFA) | 53 | 53 | 0 | 1 (permissão de push em navegador headless) |
| Isolamento multi-tenant | 16 | 15 | 1 | 0 |
| UI responsiva (3 tamanhos × 18 telas + site + páginas legais + console) | 153 | 148 | 0 reais (5 falsos positivos, ver D) | 0 |
| PWA | 6 | 5 | 0 | 1 (permissão de notificação, limitação do headless) |
| Integridade do banco | 14 | 12 | 2 | 0 |

**Comandos executados:**
- `mvn -B verify` (backend)
- `npm install && npm run build && npm audit` (frontend)
- `docker build` + `docker run` com os mesmos testes `curl` do job `docker-smoke` do CI
- `node e2e/optics-e2e.js` (duas vezes)
- os scripts da pasta `evidencias/.../scripts/`

**Docker:** dentro dos contêineres de build, o Maven deste ambiente não confia no certificado do proxy de rede da sessão. O build foi feito com uma cópia do Dockerfile que só acrescenta esse certificado e o proxy nos estágios de build (`logs/Dockerfile.sandbox-ajuste-de-rede.txt`). O estágio final (imagem de execução) é idêntico ao do repositório. Não é defeito do projeto.

## C. Achados

Severidade: P0 crítico · P1 alto · P2 médio · P3 baixo. Não houve P0 confirmado.

### AUD-01 — Nome de funcionário de outra ótica exposto no relatório de vendas · **P1** · Isolamento
- **Onde:** `backend/.../reporting/DetailedReportController.java:240-241`. `describe()` faz `SELECT full_name FROM accounts WHERE id=?` **sem filtrar pela ótica da sessão**.
- **Reprodução:**
  1. Logado como ótica B, chamar `GET /api/v1/reports/detailed/vendas?sellerId={id de conta da ótica A}`.
  2. O mesmo vale para `/export/pdf|xlsx|csv`.
- **Esperado:** filtro ignorado ou 404, sem nenhum dado de A.
- **Obtido:** HTTP 200, 0 linhas, mas o rodapé traz `"Filtros: vendedor(a) Dona AuditA"`. Foi o **único** vazamento entre as 51 rotas varridas (`logs/audit-principal.log`).
- **Por que P1 e não P0:** exige conhecer o UUID (aleatório) de uma conta da outra ótica e expõe só o nome. Ainda assim, quebra a regra de isolamento e precisa ser corrigido antes da homologação.
- **Correção recomendada:** buscar o nome com `JOIN optic_memberships m ON m.account_id=a.id AND m.optic_id=?`, recusar `sellerId` que não pertença à ótica, e adicionar teste de isolamento.

### AUD-02 — Cancelar venda já recebida não registra a devolução · **P1** · Financeiro
- **Reprodução:**
  1. Venda Pix de R$ 1.000,00 (fica "Recebido").
  2. `PATCH /sales/{id}/status {"status":"CANCELLED"}`.
- **Esperado:**
  - a baixa de R$ 1.000,00 estornada com data, ou um lançamento de devolução ao cliente;
  - histórico explicando a saída do dinheiro.
- **Obtido:**
  - `finance_entries.reversed_at` preenchido, mas a baixa em `finance_settlements` continua **ativa**;
  - não há lançamento de devolução;
  - o "Recebido no mês" caiu de R$ 1.350,01 para R$ 350,01 sem nenhum registro;
  - na tela, a linha mostra "Estornado" com "Pago/recebido R$ 1.000,00" (`capturas/desktop-financeiro-cenario-1000.png`);
  - no banco, consulta F6: 8 vendas canceladas com baixa ativa (`logs/integrity.out`).
- **Ponto positivo:** a "fotografia" do relatório Vendas Atualizadas de uma data anterior **não muda** com o cancelamento (verificado).
- **Correção recomendada:**
  - ao cancelar venda com baixas ativas, exigir data e forma da devolução;
  - estornar as baixas (ou criar a saída de devolução) na mesma transação;
  - tratar recibos já emitidos.
  - Decisão de regra com a dona.

### AUD-03 — Cenário obrigatório "venda R$ 1.000 / parcial R$ 300" não representável · **P1** · Regra de negócio
- **Fato:**
  - Venda só aceita `DINHEIRO`, `PIX`, `DEBITO` e `CREDITO` (`CardFees.Method`).
  - Dinheiro e Pix nascem quitados: a tentativa de baixa parcial responde 400 "já está quitado".
  - Recebível de cartão só aceita baixa integral: 400 "Recebimento de cartão é registrado de uma vez".
  - Crediário ou fiado existe só como **lançamento manual** do Financeiro, sem cliente nem venda, e portanto sem lembrete de cobrança com telefone, sem recibo da venda e fora do relatório de vendas.
- **Execução do cenário no lançamento manual:** os 8 passos passaram, com verificação na API, no banco e na tela.
  - R$ 1.000 → paga 300 → aberto 700 → estorno → recebido 0,00 → aberto 1.000,00;
  - histórico com motivo;
  - 1 baixa, 1 estornada, nenhuma negativa ou duplicada;
  - estorno duplo recusado.
  - Também passaram: 333,33 + 333,33 + 333,34 quitando exato, e conta a pagar parcial.
- **O E2E existente** testa o "1.000/300" nesse mesmo lançamento manual (`optics-e2e.js:135-144`), não numa venda.
- **Recomendação:** a dona decide se a venda no crediário entra no produto. Se sim: forma de pagamento "Crediário" na venda, gerando parcelas a receber ligadas à venda e ao cliente.

### AUD-04 — Recibo em PDF com "R$?1.234,56", sem acentos e com data ISO · **P2** · Recibos
- **Evidência:** `capturas/recibo-pdf-pagina1.png` e `logs/recibo-auditoria.txt`:
  - "Valor pago pelo cliente: **R$?1.234,56**";
  - "Emitente: **Otica** AuditA LTDA" (cadastrado como "Ótica AuditA LTDA");
  - "Socia", "IDENTIFICACAO";
  - "Pagamento: **2026-11-14**".
- **Causa provável:** `ReceiptPdfRenderer` usa fonte Standard 14 (Helvetica) e remove acentos (`Normalizer`); o espaço não separável do formato monetário pt-BR vira "?".
- **Esperado:** documento do cliente com a razão social exata, "R$ 1.234,56" e data dd/mm/aaaa.
- **Correção:** embutir uma fonte TrueType com acentos (ex.: incorporada ao JAR), trocar o NBSP por espaço comum e formatar datas em pt-BR.
- **Teste:** o E2E existente só conferia o tipo do arquivo ("Recibo: PDF"); incluir verificação de texto.

### AUD-05 — Estoque aceita saldo negativo · **P2** · Estoque
- **Reprodução:** entrada de 3 unidades, depois `POST /stock/movements {"kind":"AJUSTE","quantity":-10}` → 200, saldo **−7**.
- Vendas também continuam baixando abaixo de zero.
- **Banco:** 4 itens com saldo −12/−13 (consulta S1).
- **Correção:** recusar ajuste ou saída que deixe saldo negativo. Para venda sem estoque, decidir com a dona entre bloquear e "vender sob encomenda" com aviso.

### AUD-06 — Cliente sem desativação nem anonimização (LGPD) · **P2** · Clientes
- `DELETE /customers/{id}` → 405. Não existe desativar cliente nem atender pedido de exclusão ou anonimização de dados pessoais.
- **Correção:** endpoint de desativação e rotina de anonimização preservando valores e datas, com bloqueio enquanto houver obrigação legal de guarda.

### AUD-07 — Consultas: aceita data passada e horário/dia fechado; sem remarcar · **P2** · Agenda
- Agendamento em 01/01/2020 → 200.
- Agendamento às 23h30 → 200 (não usa o calendário de funcionamento nem os feriados da ótica).
- Não há endpoint para remarcar consulta, editar clínica parceira ou editar paciente.
- O conflito de horário na mesma clínica **é** recusado.
- **Correção:** validar contra data atual e calendário de funcionamento (ou avisar), e adicionar remarcação e edição.

### AUD-08 — Spring Boot 3.3.13 fora do suporte gratuito · **P2** · Dependências
- A linha 3.3 do Spring Boot saiu do suporte open source em 2025, segundo a política de suporte da Spring; confirmar em spring.io/projects/spring-boot#support.
- Novas correções de segurança deixam de sair para ela.
- **Correção:** subir para uma linha suportada (3.4/3.5 ou mais nova) e rodar a suíte completa.

### AUD-09 — Cadastro de cliente aceita CPF com dígito verificador errado e nascimento futuro · **P3** · Clientes
- `111.111.111-11` → 200; nascimento 2099-01-01 → 200 (consulta C1: 5 e 5 no banco de teste).
- Convite e perfil já usam `ProfileController.validCpf`; o cadastro de cliente só confere 11 dígitos.

### AUD-10 — Sem cabeçalho Content-Security-Policy · **P3** · Segurança
- O Render responde com HSTS, `X-Frame-Options: DENY`, `nosniff` e `Referrer-Policy`, mas **sem CSP**.
- O React escapa o conteúdo: os XSS testados não executaram.
- Recomenda-se CSP como defesa extra.

### AUD-11 — Códigos de resposta inconsistentes para IDs de outra ótica · **P3** · Isolamento
- Na varredura, 19 rotas responderam 404, mas 31 responderam 400, 403 ou **200 com lista vazia**:
  - `GET /attachments/customers/{id}`, `GET /attachments/visits/{id}`, `GET /receipts/sales/{id}/payments`, `GET /visits/{id}/events` → `[]`;
  - `DELETE /receipts/shares/{id}` → `{"revoked":false}`.
- Nenhuma trouxe dado de A, e o banco de A ficou idêntico (impressão digital de 21 tabelas).
- O padrão do projeto é 404 para registro de outra ótica.

### AUD-12 — Mensagens de erro fora do padrão `{"error": ...}` e com nome de classe Java · **P3** · API
- Login inválido (401), CSRF ausente (403) e rota inexistente (404) devolvem o JSON padrão do Spring (`timestamp/status/error/path`).
- `PUT /site-settings` com estrutura errada devolve `"JSON parse error: Cannot construct instance of br.com.lcmtech.optics.publicsite.domain.SiteContent…"`, expondo detalhe interno.

### AUD-13 — OS aceita fornecedor desativado · **P3** · OS
- Fornecedor com `active=false` ainda pode ser usado em nova OS (200).

### AUD-14 — Vulnerabilidades do servidor de desenvolvimento (Vite 5.4.10 / esbuild) · **P3** · Dependências
- `npm audit`: 1 alta (vite) e 1 moderada (esbuild), ambas **só do servidor de desenvolvimento**; o build de produção não é afetado.
- O script `dev` usa `--host 0.0.0.0`, que expõe esse servidor na rede local.
- O repositório também não tem `frontend/package-lock.json`, e o CI usa `npm install`: cada build pode instalar versões diferentes das dependências.
- **Correção:** atualizar o Vite dentro da 5.4.x corrigida, usar `--host` só quando necessário, versionar o `package-lock.json` e usar `npm ci` no CI e no Dockerfile.

### AUD-15 — Documentação desatualizada · **P3** · Docs
- `README.md:26` diz "MFA, Web Push e antivírus de anexos ainda não implementados". MFA e Web Push **estão** implementados e testados.
- `docs/RENDER_HOMOLOGACAO.md:54-55`, `docs/ARQUITETURA.md:25` ("não há login funcional") e `docs/ESTADO_IMPLEMENTACAO_2026-11-09.md:25-29` (estoque, OS e push "não implementados") estão desatualizados.
- `README.md:21` manda `mvn test`; o CI usa `mvn -B verify`.
- O README não cita o E2E nem as variáveis `PLATFORM_ADMIN_*` para entrar como operador.
- `docs/VARIAVEIS_DE_AMBIENTE.md` não lista `optics.push.*` (VAPID), `optics.messages.scheduler-enabled` e `optics.push.allow-local-endpoints`.

### AUD-16 — Lacunas de teste · **P3** · Qualidade
- Frontend sem testes unitários e sem lint.
- O bloqueio do MFA por conta (10 erros → 15 min) funciona (testado: o código **certo** foi recusado após 10 erros), mas não tem teste no backend e responde 400 em vez de 429.
- O E2E existente:
  - roda **2 vezes seguidas**; a 3ª falharia no cadastro (limite de 5 por IP/hora, 2 por rodada) e exige reiniciar o backend (documentado no `e2e/README.md`);
  - confere o recibo só pelo tipo do arquivo;
  - testa o financeiro parcial fora de uma venda.

### Observações de escopo (não são defeitos; regra atual do produto)
- **Venda:** 1 lente + 1 armação e um valor total; sem vários itens, quantidade ou campo de desconto.
- **Catálogo:** sem preço e sem busca por texto; o valor é informado na venda.
- **Arredondamento:** valores com 3 casas são arredondados para 2 (100,005 → 100,01; 3,333 → 3,33).
- **Telefone do cliente:** guardado como digitado ("(98) 98111-2222").
- **Partida a frio do Render (plano gratuito):** a primeira resposta levou ~115 s.
- **Cookie `Secure` no hml:** não verificado (exige login no hml); depende de `COOKIE_SECURE=true` no Render.

## D. Evidências

Pasta `docs/evidencias/homologacao-2026-11-14/`:

- **Capturas:**
  - pranchas `sheet-celular-1.png`, `sheet-celular-2.png` (390×844) e `sheet-tablet.png` (768×1024);
  - `desktop-03-painel.png`, `desktop-15-relatorios…png`;
  - `desktop-financeiro-cenario-1000.png` (AUD-02 visível);
  - `recibo-pdf-pagina1.png` (AUD-04), `pwa-offline.png`, `celular-90-site-publico.png`.
  - As 56 capturas completas (3 tamanhos × 18 telas + site) foram geradas na execução; a pasta guarda uma seleção.
- **Logs:**
  - `backend-mvn-verify-resumo.log` (124/0/0), `frontend-build.log`, `npm-audit.json`, `docker-build-resumo.log`;
  - `e2e-run1.log`, `e2e-run2.log` (181/181 cada);
  - `audit-principal.log` e `.json` (achados funcionais, segurança e varredura), `audit-validacao.json`;
  - `visual.log`, `visual-results.json`, `pwa-cache.txt`;
  - `integrity.out` (consultas SQL e resultados), `recibo-auditoria.txt`.
- **Scripts dos testes novos** (reproduzíveis): `e2e-audit.js` (fases `validacao` e `principal`), `visual-audit.js`, `xss-site.js`, `mfa-lock.js`, `integrity.sql`, `start-backend.sh`.

**Destaques:**
- **Varredura de isolamento:** 51 chamadas da ótica B com IDs da ótica A (GET, POST, PUT, PATCH e DELETE em todas as rotas com ID, mais criação de venda, OS e consulta com IDs de A). Resultado: 0 respostas com dados de A, exceto AUD-01. A impressão digital MD5 de 21 tabelas da ótica A ficou **idêntica** antes e depois. As 12 listagens de B não contêm nada de A.
- **Banco:**
  - todas as FKs de negócio são compostas (`optic_id`, id), o que impede referência cruzada entre óticas no próprio banco;
  - F1–F5, F7–F8 = 0 inconsistências;
  - T1 (dono coerente em 6 relações) = 0;
  - D1 (idempotência, número de recibo, número de OS, recibo por baixa) = 0 duplicidades;
  - A1 (consultas sobrepostas) = 0;
  - Flyway 26/26 com sucesso.
  - Falharam F6 (AUD-02) e S1 (AUD-05).
- **Indicadores:** os 14 do relatório "Vendas Atualizadas" existem.
  - Os 10 calculáveis foram recalculados em SQL independente para a ótica A (11/2026, corte 14/11) e batem: total 2.634,57; quantidade 5; líquido recebido 1.634,57; a receber 970,00; taxas 30,00; média/dia 239,51; dias 23/11/12.
  - Os 4 de meta ficam nulos sem meta cadastrada; o E2E existente cobre meta preenchida.
- **Console do navegador:** 0 erros de JavaScript e 0 respostas 5xx nas 45 navegações (3 tamanhos × 15 telas).
- **Falsos positivos descartados** (5 do responsivo e 1 do PWA):
  - o link de acessibilidade "Pular para os serviços" fica fora da tela de propósito até receber foco;
  - `Notification.permission = "denied"` é o padrão do Chromium headless.
- **Revisão visual das capturas:** sem rolagem lateral, botões dentro da tela, janelas cabendo no celular e tabelas com rolagem própria. Os cabeçalhos repetidos no meio de capturas de página inteira são efeito da captura com cabeçalho fixo, não da tela.
- **PWA:**
  - manifest completo e service worker `sw.js` ativo;
  - cache só com o esqueleto (`/offline.html`, ícones, manifest e `/assets/*`; nenhuma `/api/`);
  - sem rede, abre a tela offline sem dados da ótica;
  - a API sempre vai à rede;
  - push: inscrição, recusa de endereço fora dos serviços oficiais, isolamento e desativação cobertos pelo E2E existente; nenhuma notificação real enviada.
- **Segurança verificada:**
  - BCrypt custo 12;
  - ID de sessão novo no login; cookie HttpOnly e SameSite=Lax;
  - logout e expiração por inatividade (45 min, simulada no banco de teste) → 401;
  - 13ª tentativa de login errada → 429 com `Retry-After`;
  - limites de cadastro (6º → 429) e "esqueci a senha" (6º → 429) com resposta idêntica para conta existente e inexistente;
  - CSRF obrigatório; `/actuator/env` fechado mesmo logado;
  - SQL injection na busca e nos filtros → 0 linhas, sem erro;
  - JSON malformado sem stack trace;
  - XSS armazenado (cliente e site público) exibido como texto, sem executar;
  - anexo falso, HTML ou maior que 4 MB recusado;
  - vendedora restrita (15 operações proibidas → 403; vende e vê clientes);
  - convite de uso único;
  - MFA: 5 erros encerram a sessão, 10 erros bloqueiam a conta por 15 min, códigos de recuperação de uso único (E2E).

## E. Avaliação final

**REJEITADO — PROBLEMAS PRECISAM SER CORRIGIDOS.** Há três achados P1 em aberto:
- AUD-01, exposição de nome entre óticas;
- AUD-02, cancelamento de venda paga sem devolução registrada;
- AUD-03, o cenário financeiro obrigatório não é executável numa venda.

Isso conforme a regra deste roteiro (só aprovar sem P0/P1 e com validação essencial completa). O restante da base está sólido: isolamento comprovado por varredura e por chaves compostas, cálculos financeiros e indicadores corretos, segurança de sessão, MFA, CSRF e limites funcionando.

**Próximo passo sugerido, após autorização:**
1. Corrigir AUD-01 e AUD-02, com testes de isolamento e financeiro novos.
2. Decidir com a dona a regra do AUD-03.
3. Corrigir AUD-04 e AUD-05.
4. Atualizar a documentação (AUD-15).
5. Rodar de novo `mvn verify`, o E2E existente e os scripts desta auditoria.
