# Expansão funcional — Calendário de funcionamento, Consultas, Aniversários e Recibos

**Produto:** Optics Management · **Origem:** requisitos adicionais da ótica piloto, 09/11/2026  
**Branch:** `hml` (sem merge em `main`) · **Status:** especificação e backlog; somente a migração `V7__business_calendar_and_optometry.sql` já está no repositório; APIs, UIs, integrações e testes devem ser executados e homologados antes de marcar concluído.  
**Dependência:** [Relatório de Vendas Atualizadas e Metas](RELATORIO_VENDAS_ATUALIZADAS_METAS_BACKLOG.md) e relatórios detalhados já existentes.

## 1. Princípios e definição de termos

- **Dia de funcionamento**: data na qual a ótica efetivamente abre, independentemente de haver venda ou funcionário específico presente. `dias_trabalhados` da planilha significa **dias abertos da loja**, não presença da equipe e não apenas dias úteis bancários.
- **Feriado**: data legal na jurisdição da ótica; **ponto facultativo** é classe distinta e não significa fechamento automático de estabelecimento privado. A decisão `terá expediente` é da ótica.
- **Paciente**: pessoa cadastrada para consulta oftalmológica/optométrica agendada em clínica parceira; consulta não implica compra.
- **Cliente**: pessoa com vínculo comercial/venda na ótica; pode nunca ter sido paciente de clínica parceira.
- **Pessoa que é paciente e cliente**: mantém ambos os vínculos, com rastreabilidade do relacionamento. Nunca transformar paciente em cliente automaticamente no fechamento da consulta.
- **Recibo**: comprovante de um pagamento efetivamente recebido. **Não substitui NF-e/NFC-e ou documentação fiscal obrigatória**; uma venda não liquidada gera pedido/ordem de venda, não um falso recibo de quitação.

## 2. Calendário operacional e feriados

### 2.1 Cadastro da loja

No cadastro/Configurações da ótica, exigir e validar para funcionamento do calendário: **UF, município (nome + código IBGE de 7 dígitos), fuso horário IANA**, padrão semanal de funcionamento (segunda a domingo, dias com horário e intervalos) e, opcionalmente, exceções por unidade/filial futura. Não assumir localidade do proprietário ou do primeiro cliente como local da loja.

Manter tabela de feriados nacionais, estaduais e municipais com **fonte legal ou órgão emissor**, data de verificação, escopo, ano, UF e município IBGE; diferenciar pontos facultativos. A Lei federal 9.093/1995 prevê feriados civis nacionais, data magna estadual e feriados religiosos instituídos por lei municipal. Não inventar feriado local por correspondência aproximada de nome. Em caso de falha/ausência de fonte regional, mostrar **“feriados locais não verificados”**, oferecer cadastro manual e impedir que o dashboard afirme calendário completo.

Fontes para implementação/avaliação:
- Lei 9.093/1995: https://www.planalto.gov.br/ccivil_03/leis/l9093.htm
- Cadastro oficial IBGE/localidades: https://servicodados.ibge.gov.br/api/docs/localidades
- Calendário federal 2026 (pontos facultativos são para órgãos federais; uso indicativo para privados): https://agenciagov.ebc.com.br/noticias/202512/confira-o-calendario-oficial-de-feriados-nacionais-e-pontos-facultativos-em-2026

Adaptador de provedor: catálogo nacional versionado; UF e município por fonte confiável e verificável; cache, retry, timeout, versionamento, deduplicação de datas nacionais/estaduais/municipais e painel com `source_url` para conferência. Não confiar apenas em endpoint aberto que não comprova legislação municipal. O administrador pode informar manualmente um feriado local com motivo e fonte opcional; evento será registrado como **“informado pela ótica”**, não “verificado oficialmente”.

### 2.2 Fluxo do primeiro acesso do Master no mês

1. Ao entrar: loading/splash com identidade oficial **Optics Management**, seguindo o padrão de experiência da Ecclesia Finance, com proteção contra loader interminável.
2. Depois da autenticação e escolha da ótica: se `MASTER` e o mês da ótica ainda não foi revisado, abrir **modal obrigatório de revisão do calendário**, acima do dashboard. O período é determinado pelo fuso da **ótica**, não pelo fuso do aparelho.
3. Mostrar mês, UF, município, dias habituais de funcionamento, legenda das datas e **somente os feriados/pontos facultativos que coincidam com dias normalmente abertos**; oferecer expansão para ver todos os dias. Em cada data: nome, origem (nacional/estadual/municipal/ponto facultativo/manual), fonte verificada ou aviso e controle **“Terá expediente?”** ligado/desligado.
4. Padrão sugerido: feriado legal reconhecido = **não abrir**; ponto facultativo = **abrir** em dia regular, ambos editáveis pelo Master. Dia de fim de semana que a ótica abre tem a mesma regra.
5. Prévia em tempo real: **dias abertos previstos, dias já abertos, dias ainda abertos, meta diária e meta por dia restante**. Se meta mensal estiver em branco, incluir passo para configurar; nunca inventar uma meta.
6. Botão **“Salvar meu planejamento”** persiste todas as alterações e marca `optic_month_reviews` como revisado por aquele Master; diálogo não reaparece naquele mês para essa ótica depois da confirmação de um Master. Se fechar sem salvar, a revisão permanece pendente e reaparece no próximo acesso do Master (sem bloquear indefinidamente acesso a situações urgentes — oferecer “Revisar depois” visível e auditável).
7. Mais de um Master: alterações concorrentes com controle de versão/última revisão e trilha auditável; nenhuma atualização silenciosa. No início de outro mês a modal reaparece; férias não implicam fechamento permanente.
8. Se o cadastro de UF/município estiver ausente ou feriados locais não carregarem, exibir estado de alerta e permitir configurar município/manual antes de aceitar um calendário “verificado”.

### 2.3 Aba “Calendário e feriados”

- Visualização mês/semana/ano com cores e legenda; filtro por abrangência do feriado, dia aberto/fechado e origem.
- Adicionar feriado municipal/estadual **manual**, fechamento extraordinário, recesso e dia excepcional de abertura; título, data, `terá expediente`, observação e opcional referência da lei.
- Editar/remover exceções próprias da ótica, sem excluir fonte legal compartilhada de outras óticas.
- Permissões: Master e concessão específica de configuração do calendário; consulta do calendário somente para papéis que precisam das metas/agenda.
- Considerar meio expediente separadamente (informativo e para agendamentos); **contagem de dias** pode seguir política de 1 dia inteiro por qualquer abertura ou peso configurável. Para replicar a planilha inicial, padrão **cada data aberta conta 1**. Confirmar com a ótica se meio expediente deve contar meio dia.
- Contagem **não** usa transações para decidir se o dia foi trabalhado: uma ótica aberta com zero vendas conta.

### 2.4 Motor mensal

`dias_total = COUNT(dias entre início e fim do mês em que calendário_efetivo.is_open=true)`; `dias_trabalhados = COUNT(dias entre início e data de corte, inclusive, efetivamente abertos)`; `dias_restantes = COUNT(dias APÓS data de corte até fim do mês previstos como abertos)`. O próprio dia de corte deve ter política explícita (quando a ótica ainda não abriu, não computar como trabalhado; permitir fechamento/abertura corrigido com auditoria). Para o Excel da ótica piloto, usar corte e regra definidos pelo Master. Não recalcular relatórios históricos com alterações retroativas não auditadas.

Todas as **14 linhas do impresso** permanecem obrigatórias no resumo geral e nos PDFs/XLSX; o calendário fornece as linhas `dias total`, `dias trabalhados`, `dias faltando`, `meta dia` e `meta faltando por dia`. Os relatórios detalhados por venda, cliente, funcionário, lentes/armações, recebíveis e visitas permanecem disponíveis, sem substituir o consolidado.

## 3. Clínicas parceiras, pacientes e consultas

### 3.1 Clínicas conveniadas

A ótica cadastra clínicas parceiras: nome fantasia/razão social, telefone/WhatsApp, endereço, CNPJ se houver, situação ativa, dias e horários de atendimento/agenda e informações de contato do responsável. **Não presumir** acesso ao sistema da clínica; agenda da ótica é operacional e pode necessitar confirmação externa, salvo integração futura.

### 3.2 Pacientes (independentes de clientes)

Cadastro de paciente: nome, nascimento, telefone, e-mail opcional, CPF opcional conforme fluxo e LGPD, endereço, observações administrativas mínimas, comunicação/consentimentos por finalidade, vínculo com a ótica. Controle de duplicidade **por CPF quando disponível**, sem unir pessoas só porque têm nomes parecidos. O sistema pode sugerir registros parecidos para conferência manual.

Fluxo:
`CADASTRO DE PACIENTE` → `AGENDAMENTO` → `CONFIRMADO` → `CONSULTA REALIZADA`, `NÃO COMPARECEU`, `CANCELADA`, com remarcar/reagendar e histórico.
Filtros: data, clínica, paciente, situação, funcionário responsável, encaixes e próxima consulta. Prevenir conflitos de horário por clínica/profissional e oferecer confirmação por WhatsApp com mensagem revisável. Histórico com usuário/data e registros mínimos; receitas/exames oftalmológicos só com controles reforçados de dados sensíveis e finalidade clara.

### 3.3 Conversão paciente → cliente no lançamento de venda

Na tela **Nova venda**, adicionar busca/autocomplete de **pacientes cadastrados ainda não convertidos** e opção alternativa **cliente existente** ou **novo cliente que realizou exame fora da parceria**. A vendedora digita nome, vê nome + informação de desambiguação suficiente (nascimento/parte do telefone ou CPF mascarado), clica, e o sistema pré-preenche nome, nascimento, telefone, endereço e documento, respeitando permissões. Permitir editar/completar campos faltantes **sem sobrescrever silenciosamente o registro de paciente**.

Confirmar venda de forma **atômica**, criando (ou vinculando) cliente `customers.source_patient_id` e preservando `optometry_patients`/consulta. Se desistir/cancelar formulário, paciente continua paciente; após compra, a mesma pessoa consta nas duas áreas; cliente que fez consulta fora da parceria continua sem vínculo de paciente.

Repetição: impedir criar dois clientes para um paciente e evitar cliente duplicado pelo CPF existente. Se houver conflito pessoa/CPF ou nomes semelhantes, modal de reconciliação manual; não converter/mesclar automaticamente. Um paciente pode agendar várias consultas e realizar várias vendas.

### 3.4 Métricas e relatórios

Por clínica e por período: pacientes agendados, confirmados, consultados, faltosos, cancelados, convertidos em cliente por venda, vendas realizadas após consulta (sem presumir causalidade clínica), funcionário que agendou, no-show, tempo de conversão; métricas agrupadas sem divulgar detalhes de saúde a não autorizados.

**Permissões propostas:** `PATIENTS_READ`, `PATIENTS_WRITE`, `APPOINTMENTS_READ`, `APPOINTMENTS_MANAGE`, `PARTNER_CLINICS_MANAGE`; Master pleno e gerente/secretário/vendedor com direitos configuráveis por convite. Não reutilizar indiscriminadamente `CUSTOMERS_READ` para expor dados de pacientes.

## 4. Aniversariantes de clientes

- Dados: `customers.birth_date` e `phone`; somente clientes cadastrados, com consentimento/base legal para comunicação; opt-out explícito por cliente. Separar visualização da lista do envio de mensagem.
- No primeiro acesso do dia de cada usuário autorizado (não necessariamente só Master), exibir **modal de aniversariantes do dia** com nomes e WhatsApp; botão `Preparar saudação` gera `wa.me` pré-preenchido para revisão manual (sem disparo em massa automático).
- Aba **Aniversariantes** com hoje, próximos 7/30 dias, mês, aniversariantes sem número e histórico de `abrir WhatsApp` (não registrar como `entregue`). Registrar modal visto por **usuário + ótica + data local**, para não reaparecer em cada atualização.
- Para nascidos em **29/02**, definir política explícita para anos não bissextos (`28/02` ou `01/03`) e testá-la.
- Não exibir CPF/endereço completo na modal; somente perfis com permissão; aniversários não podem atrapalhar modal de planejamento do Master (ordem: splash → revisão mensal → aniversariantes).

## 5. Recibos personalizados, assinatura e WhatsApp

### 5.1 Identidade e informações obrigatórias

Área de configuração da ótica: nome empresarial, nome fantasia, **CNPJ validado**, endereço, contato, logotipo e escolha do responsável pela assinatura (nome e cargo). Recibo HTML/PDF A4 ou térmico, bem diagramado, com numeração sequencial por ótica, data/hora e fuso da emissão, dados do cliente, **itens e produtos** da venda (lente/marca, armação/marca, detalhes quando existentes), funcionário, valores bruto/descontos/acréscimos/taxas quando cabíveis, forma de pagamento e parcelas, valor **efetivamente pago**, saldo pendente, data de recebimento, identificação única e integridade/auditoria. Uma venda ainda não liquidada **não** deve aparecer como quitada.

### 5.2 Assinatura reutilizável

Permitir ao Master/funcionário autorizado desenhar assinatura em canvas (mouse/toque/caneta) ou subir arquivo PNG/JPG validado (tamanho, MIME, assinatura real), com pré-visualização, substituição, desativação e controle de quem pode utilizá-la. Assinatura padrão da ótica deve ser vinculada a signatário responsável e a versão imutável, com auditoria de autor/data; não fingir que uma imagem equivale a assinatura digital certificada por ICP-Brasil. Se não houver autorização, gerar recibo sem assinatura e espaço próprio para assinatura manual.

### 5.3 Geração, histórico, compartilhamento

- `Emitir recibo` em venda com pagamento/baixa efetiva; se parcial, recibo identifica valor parcial e saldo; nunca declarar quitação total antes de liquidar.
- Armazenar metadados e snapshot do recibo no momento da emissão para que alterações posteriores em logo, preço, endereço ou assinatura não reescrevam histórico; cancelamento deve emitir novo evento de estorno/cancelamento sem apagar o documento original.
- `Baixar PDF`, `Imprimir`, `Compartilhar WhatsApp` com **link assinado de uso limitado ou token temporário**, de expiração curta, associado ao destinatário, sem ID previsível ou dados sensíveis nos parâmetros. Não afirmar que `wa.me` envia PDF sozinho; abrir mensagem com link protegido e permitir envio manual do arquivo.
- Segurança: acesso por tenant e permissão `RECEIPTS_ISSUE`/`RECEIPTS_READ`, download sem cache, URL revogável, auditoria, LGPD e anti-enumeration. Informar claramente `Recibo de pagamento — não substitui documento fiscal`.
- Recibo distinto do **pedido de venda**, do **comprovante da entrega** e de nota fiscal; considerar emitente CPF/CNPJ conforme enquadramento legal do estabelecimento e validação fiscal antes de produção.

### 5.4 Relatórios e testes

Relatório de recibos emitidos por período, vendedor, situação, método de pagamento, valor pago e estornos; PDF consolidado e XLSX detalhado. Testar venda Pix, cartão parcelado, pagamento parcial, estorno, alteração posterior de assinatura, assinatura removida, duas óticas e link expirado.

## 6. Migrations já existentes e pendências de implementação

A branch `hml` contém `V7__business_calendar_and_optometry.sql` com tabelas:
- `optic_business_calendar`, `regional_holidays`, `regional_holiday_sync`, `optic_day_overrides`, `optic_month_reviews`, `optic_monthly_goals`;
- `optometry_patients`, `optical_partner_clinics`, `optical_appointments` e `customers.source_patient_id`.

**Verificar que a V7 aplica sem erros em banco de homologação real antes de adicionar mais migrações**. O SQL sozinho não representa a funcionalidade final. Faltam a camada domain/service/api, sincronizador de fontes verificadas, validação de feriados, controle de permissões, UX, aniversários, assinatura, PDF e testes.

Não duplicar tabelas existentes nem editar uma migration já aplicada em Render; mudanças futuras devem ir em `V8+` e ser reversíveis na estratégia de deploy.

## 7. Ordem de implementação (incrementos)

1. **P0**: validação de localização (UF e IBGE), horário semanal e calendário/feriados com importação verificável + cadastro manual.
2. **P0**: splash e modal de revisão do Master no primeiro acesso do mês; calendário configurável; conectar aos 14 indicadores da fotografia.
3. **P0**: CRUD de clínicas parceiras, pacientes e consultas, permissões e controle de conflitos.
4. **P0**: busca de pacientes não convertidos e conversão atômica para cliente/venda, com deduplicação e auditoria.
5. **P0**: aniversariantes do dia, modal após a revisão mensal, consulta/WhatsApp e preferências de comunicação.
6. **P0**: dados fiscais/identidade, assinatura, recibo correto por pagamento e compartilhamento seguro WhatsApp.
7. **P0**: relatórios geral + detalhados com filtros/exportação, cenários de dias operados e recebimentos reais.
8. **P0**: testes de integração e Playwright/E2E no Render de dois tenants, inclusive falha da fonte regional, feriado em sábado aberto, datas 29/02, agenda em conflito, paciente sem compra, assinatura expirada e recibo de venda não quitada.
9. **P1**: integração calendário com mensagens automatizadas (consentimento/provedor), estoque fiscal e integrações com clínicas sob contrato.

## 8. Critérios essenciais de aceite

- [ ] Abertura da ótica em sábado conta dia de funcionamento; segunda sem vendas também conta se a loja abriu.
- [ ] Carnaval e outros pontos facultativos não geram fechamento obrigatório indevido.
- [ ] Feriado municipal só é rotulado `verificado` se fonte para município IBGE foi validada; se não, informar lacuna e permitir ajuste manual.
- [ ] Todo Master vê revisão no primeiro acesso do mês não confirmado; confirmação uma vez por ótica/mês, respeitando concorrência.
- [ ] Relatório geral conserva **14 campos originais** com metas e valores coerentes; detalhados permanecem separados.
- [ ] Paciente de consulta não entra na listagem comercial até que haja venda confirmada.
- [ ] Cliente que consultou fora da parceria não precisa ter ficha de paciente e continua apto a comprar.
- [ ] Busca de paciente não expõe prontuário/receita médica no autocomplete; vínculo e deduplicação preservados.
- [ ] Consulta na mesma clínica/profissional não pode ter conflito de horário sem override autorizado.
- [ ] Aniversariantes de hoje aparecem uma vez no dia por conta/ótica, com opção de abrir WhatsApp, sem envio automático ou contatos sem consentimento.
- [ ] Recibo com identidade e CNPJ correto; apenas pagamentos reais podem ser comprovados; link compartilhável expira e pode ser revogado.
- [ ] Nenhuma ótica acessa feriados manuais, pacientes, aniversariantes, agenda, assinaturas ou recibos de outra.
- [ ] Fluxos testados no **Render**, com frontend, backend e PostgreSQL conectados; nada em `main` sem homologação.
