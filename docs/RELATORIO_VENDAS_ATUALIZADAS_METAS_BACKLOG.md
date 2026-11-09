# Especificação funcional — Relatório de Vendas Atualizadas e Metas

**Produto:** Optics Management  
**Origem:** fotografia de relatório impresso derivado do Excel enviada pela ótica piloto (cabeçalho: `VENDAS ATUALIZADAS – DATA: 09/11`; a imagem não exibe ano).  
**Situação:** backlog de implementação; **não está entregue no sistema nem homologado no Render**.  
**Branch de trabalho:** `hml`. **Não modificar `main`** nesta demanda.  
**Referência existente:** `ReportController` (CSV/XLSX/PDF detalhados), `FinanceController`, `SalesController`, `sales` e `finance_entries` (Flyway V2). O novo resumo é um relatório **adicional**, não substitui os relatórios existentes.

## 1. Objetivo e experiência desejada

Adicionar em **Relatórios → Vendas Atualizadas / Metas** um quadro compacto legível em celular, tablet e desktop, com filtros por ótica ativa (definida na sessão), mês/ano e **data de corte**. Mostrar o nome da ótica, o período, a data/hora de geração e os 14 indicadores do modelo, com números formatados em `pt-BR`, dinheiro `R$` e dias/vendas sem casas decimais.

Permitir visualizar o resumo, imprimir e baixar PDF em A4 (uma página sempre que possível) e XLSX (células numéricas editáveis e aba "Memória de cálculo"/"Lançamentos" para conferência). CSV opcional apenas para a tabela de indicadores. Usar a identidade visual Optics/ótica sem prejudicar a legibilidade; não anexar dados de outros tenants.

## 2. Reprodução dos valores da fotografia (fixture de aceite)

| Nome no impresso | Valor de referência | Semântica para especificação |
|---|---:|---|
| Vendas Líquida | R$ 17.117,06 | **Vendas líquidas recebidas** até o corte: fluxo financeiro de vendas, descontadas taxas |
| Vendas a Receber | R$ 1.400,00 | **Recebíveis líquidos** ainda pendentes no corte; validar com o cliente se o Excel usa valor bruto |
| Total das Vendas | R$ 19.315,00 | Total bruto de vendas do período |
| Meta/Mês | R$ 120.000,00 | Meta mensal configurada pela ótica |
| Meta/Dia | R$ 5.000,00 | Meta mensal / total de dias úteis planejados |
| Meta - Faltando/Mês | R$ 100.685,00 | Máx(0; meta mensal - total bruto das vendas) |
| Vendas/Real | R$ 3.863,00 | Média bruta efetiva por dia trabalhado até o corte |
| Dias TOTAL - Trabalhado/Mês | 24 | **Dias planejados para a ótica abrir** no mês, considerando feriados e exceções confirmados pelo Master |
| Dias - Trabalhado | 5 | **Dias em que a loja funcionou** até o corte; não depende de vendas nem de presença dos funcionários |
| Dias - Faltando | 19 | **Dias de funcionamento restantes** após a data de corte, conforme calendário da loja |
| Quantidade Vendas/Mensal | 29 | Número de vendas válidas do mês até o corte |
| Meta - Faltando/Dia | R$ 5.299,21 | Saldo da meta / dias úteis restantes; evitar divisão por zero |
| Venda Bruta - Óculos/Lentes | R$ 19.315,00 | Soma de vendas da categoria óculos/lentes, conforme tipificação |
| Taxas Bancárias | R$ 797,94 | Taxas de transações das vendas do período (não despesas operacionais gerais) |

### Reconciliação obrigatória do exemplo

- `19.315,00 - 1.400,00 - 797,94 = 17.117,06`.
- `120.000,00 / 24 = 5.000,00`.
- `120.000,00 - 19.315,00 = 100.685,00`.
- `19.315,00 / 5 = 3.863,00`.
- `24 - 5 = 19`.
- `100.685,00 / 19 = 5.299,21` (arredondar em 2 casas só **na apresentação**, preservar decimal exato para cálculo intermediário).

**Importante:** o exemplo prova consistência aritmética, mas **não prova** a regra de reconhecimento das vendas/recebíveis no Excel. O nome “Vendas Líquida” foi normalizado para “Vendas líquidas recebidas”, sujeito à confirmação com o cliente.

## 3. Regras de dados e cálculo (proposta inicial)

### Escopo temporal e categorias

1. A data de corte do relatório deve estar dentro do mês escolhido; por padrão, hoje no fuso **America/Fortaleza** para esta ótica piloto (tornar timezone configurável por ótica antes de expandir para outros fusos). `period_start` é o 1º dia do mês; `period_end` é a data de corte, inclusiva.
2. Vendas válidas: `sales.optic_id` = ótica autenticada, `sales.sold_on` dentro do período, `sales.status != 'CANCELLED'`. Filtrar status com tratamento de cancelamentos/estornos registrados até a data de corte para relatórios históricos reproduzíveis.
3. `total_vendas` e `venda_bruta_oculos_lentes`: preferir `SUM(sales.gross_amount)` como base contratada, **não somar `charged_amount` e `gross_amount`**. Quando `passFee=true`, diferenciar valor comercial do pedido e valor efetivamente cobrado. Validar com a ótica qual base alimenta a meta.
4. `quantidade_vendas`: `COUNT(DISTINCT sales.id)`, não o número de parcelas, itens ou lançamentos financeiros.
5. `taxas_bancarias`: taxas associadas **a essas vendas**, contabilizadas uma vez por venda/parcela conforme modelo, nunca todas as despesas da ótica. Evitar somar `sales.fee_amount` com `finance_entries.fee_amount` para a mesma transação.
6. `liquido_recebido`: `SUM(finance_entries.net_amount)` para lançamentos `origin='SALE'`, de vendas do período, `direction='INCOME'`, `settled_on <= cutoff` e não estornados na data consultada. Não somar entradas manuais não relacionadas a vendas.
7. `vendas_a_receber`: `SUM(finance_entries.net_amount)` para essas mesmas vendas ainda pendentes **no corte** (`settled_on IS NULL OR settled_on > cutoff`); status de estorno deve ser interpretado **como estava no corte**.
8. **Identidade de conciliação:** `total_bruto_da_coorte = liquido_recebido + recebiveis_liquidos + taxas`, **quando** as taxas e `gross_amount` usam a mesma base, não há pagamentos parciais/devoluções e os lançamentos representam 100% de cada venda. Caso contrário, exibir “diferença de conciliação” com memória de cálculo e não inventar valores ou forçar os números.
9. **Não misturar estoque/contas/despesas** no indicador “Vendas líquidas”: trata-se do valor líquido de vendas/recebimentos, não de lucro.

### Metas e calendário

1. Criar configurações **por ótica e mês**: `monthly_goal` (BRL, decimal positivo ou zero), dias da semana/horários de atendimento (inclusive fins de semana abertos), feriados nacionais/estaduais/municipais verificados, pontos facultativos indicativos, exceções de abertura/fechamento e timezone; veja [especificação do calendário e demais módulos](EXPANSAO_CALENDARIO_CONSULTAS_ANIVERSARIOS_RECIBOS.md).
2. Contar **datas de funcionamento da loja**, não simplesmente dias úteis nacionais: dias programados para abrir no mês, dias em que abriu até o corte (mesmo sem vendas) e dias programados de abertura depois do corte. Data de corte e abertura no próprio dia seguem regra explícita e auditável. Permitir exceção manual por **data** (fechamento/abertura, inclusive feriado local), com fonte, motivo, usuário/data; totais derivados do calendário efetivo, evitando divergência entre quantidade de dias e datas. O Master revisa na primeira entrada do mês.
3. `meta_dia = meta_mes / dias_uteis_total` quando denominador > 0; sem dias de funcionamento, mostrar “—”.
4. `media_vendas_dia = total_vendas / dias_trabalhados` quando > 0; caso contrário “—”.
5. `falta_meta_mes = MAX(0,meta_mes-total_vendas)`, com um indicador separado para a **superação** se ultrapassar a meta.
6. `meta_restante_dia = falta_meta_mes / dias_restantes` quando > 0. Com dias restantes = 0 e saldo > 0, mostrar “Meta não atingida”; com saldo = 0, mostrar R$ 0,00.
7. Se não houver meta configurada, **não inventar 120 mil**: mostrar “Configure uma meta” e ocultar projeções dependentes, sem prejudicar os valores de vendas.

### Financeiro, parcelamento e cortes históricos

- Venda com múltiplas parcelas: uma única venda contada no volume; recebíveis conforme competência e liquidação de cada parcela; taxas da operação sem duplicidade.
- Venda cancelada/estornada: excluir ou ajustar conforme data efetiva e trilha auditável, com teste de cancelamento posterior ao corte.
- No modelo atual, os lançamentos têm `settled_on` sem baixa parcial por lançamento; **adicionar suporte a baixas parciais/ajustes ou bloquear o relatório indicando limitação para esse cenário**, sem fingir precisão.
- Para fechamento retroativo confiável, persistir datas de mudança de estado ou snapshots imutáveis; não recalcular uma fotografia histórica apenas com o estado atual.
- Pagamentos recebidos neste mês de **vendas antigas** não entram no resumo de vendas do mês (coorte); se necessário, mostrar caixa mensal em um quadro distinto.

## 4. Proposta de interface

**Relatórios → Vendas Atualizadas / Metas**

- Filtros: **mês**, **ano**, **data de corte**; estado de configuração de meta e calendário.
- Seções organizadas: **Resultado de vendas** (bruta, líquido recebido, a receber, taxas, quantidade); **Meta mensal** (mensal, diária, falta, porcentagem, projeção); **Calendário** (dias planejados, trabalhados, restantes, exigência diária).
- Destaques: percentual da meta alcançado e variação da média real para a meta diária, sem esconder os 15 campos originais.
- Ações: **Configurar meta e dias úteis**, **Exportar PDF**, **Exportar Excel**, **Imprimir**.
- Visualização em formato A4 compacto próxima ao original: título “VENDAS ATUALIZADAS — DATA: DD/MM/AAAA”, nome da ótica, nomes de indicadores à esquerda e números à direita, sem quebrar tabelas.
- Acessibilidade: tabulação por teclado, leitores de tela, foco visível, alto contraste e responsividade.

## 5. Contrato de API sugerido

- `GET /api/v1/reports/monthly-sales-summary?year=2026&month=11&asOf=2026-11-09` → JSON tipado com `opticId` obtido **somente da sessão**, contexto, 14 indicadores, alertas de conciliação e dados sobre a meta.
- `GET /api/v1/reports/monthly-sales-summary.pdf?... ` → PDF; `...xlsx?... ` → XLSX; ambos usam exatamente o **mesmo serviço de cálculo**, nunca queries divergentes por formato.
- `GET /api/v1/sales-goals?year=2026&month=11` e `PUT /api/v1/sales-goals/{year}/{month}` → configuração mensal com validação, autorização e histórico de alterações.
- Permissões: leitura `REPORTS_READ` **e** `FINANCE_READ` para os valores financeiros sensíveis; edição de metas e dias úteis apenas `MASTER` ou permissão administrativa específica; isolamento multitenant obrigatório.

## 6. Backlog técnico — ordem recomendada

**P0-1** Meta mensal e calendário por ótica (persistência, feriados nacionais/estaduais/municipais verificados, exceções, dias efetivos de funcionamento e confirmação Master na primeira entrada do mês).  
**P0-2** Motor de cálculo mensal com corte histórico, coorte de vendas, taxas e pendências; validações de cancelamento/parcelamento e fechamento.  
**P0-3** Tela de consulta e configuração de metas, incluindo compatibilidade mobile.  
**P0-4** PDF A4 fiel ao impresso, XLSX editável com memória de cálculo e ações de download.  
**P0-5** Testes de contrato/isolamento e regressão dos 14 indicadores com fixture do Excel, além de teste E2E.  
**P1-6** Baixas parciais e conciliação de parcelas/estornos para casos reais; se necessário, elevar a P0 antes de liberar relatório financeiro ao cliente.  
**P1-7** Evoluções: comparativo histórico, gráficos, indicadores por vendedor e exportação agendada (fora do escopo mínimo do impresso).

## 7. Critérios de aceite

- [ ] Uma ótica consegue configurar meta e calendário sem afetar outra.
- [ ] Todos os **14 indicadores** estão presentes com explicação do cálculo; valores monetários em `pt-BR`.
- [ ] O **fixture 09/11** reproduz exatamente os 15 números acima quando alimentado com os dados e regras correspondentes.
- [ ] O cálculo de meta ignora lançamento de despesas e entradas manuais sem vínculo a venda.
- [ ] Cancelamentos, estornos, crédito parcelado e data de corte não duplicam receitas ou taxas.
- [ ] Períodos sem venda, sem meta e com zero dias não resultam em divisão por zero ou valores falsos.
- [ ] PDF A4 e XLSX apresentam os **mesmos números** e filtros do painel; Excel mantém tipos numéricos/células.
- [ ] Operações financeiras exigem permissões corretas, com testes explícitos de bloqueio entre óticas.
- [ ] Validação E2E **no Render** com frontend/backend/PostgreSQL online, sem dados reais da ótica piloto.

## 8. Perguntas para homologação com a ótica piloto

1. **“Vendas a receber”** é bruto ou líquido das taxas? Inclui cartões parcelados ainda não creditados?
2. **“Vendas líquidas”** é valor realmente recebido no banco ou total da venda subtraído de taxas e pendências?
3. A **meta** é medida sobre venda bruta contratada ou sobre recebimento? Pagamento de taxa repassada ao cliente compõe a venda?
4. Os 24 dias de funcionamento foram definidos manualmente? Quais dias da semana a ótica abre? A loja abriu todos os dias marcados, inclusive dias sem venda? Existe meio expediente?
5. Há **cancelamentos, descontos, vendas mistas, sinais, recebimentos parciais** ou serviços fora de óculos/lentes que devem compor o relatório?

**Decisão provisória:** usar vendas brutas como base da meta, recebíveis **líquidos**, média de vendas brutas por dia útil transcorrido e calendário configurado. Não afirmar equivalência definitiva ao Excel até o aceite do cliente.


## 9. Expansão validada — dias da loja e novos módulos

O usuário esclareceu que **“Dias Trabalhado” significa dias em que a loja funciona**, não dias úteis de escritório, nem presença individual de funcionários. A agenda e a meta devem seguir o calendário da própria ótica, com feriados nacionais, estaduais e municipais, revisão mensal pelo Master e alterações manuais. Os 14 valores do relatório da fotografia continuam obrigatórios no resumo geral e relatórios detalhados permanecem disponíveis.

Documentação relacionada: [Calendário inteligente, agendamentos de pacientes, aniversariantes e recibos personalizados](EXPANSAO_CALENDARIO_CONSULTAS_ANIVERSARIOS_RECIBOS.md).
