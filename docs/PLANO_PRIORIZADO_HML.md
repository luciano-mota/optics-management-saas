# Optics Management — sequência de prioridades para a homologação

Data: 09/11/2026  
Repositório: `luciano-mota/optics-management-saas`, branch `hml`  
Render: https://optics-management-hml.onrender.com  
Premissa de aceite: **implementar os módulos e integrações primeiro; um teste de ponta a ponta completo somente quando a lista estiver funcional**. Unitários, testes de integração e compilação no GitHub Actions continuam obrigatórios a cada commit, porque não substituem o aceite E2E final.

## Ordem vinculante de entrega

### 01 — Identidade e jornada inicial (P0)
- [x] Separar conta operadora LCM do Master de uma ótica; acesso global por convite com token único de e-mail.
- [x] Conta criada no cadastro rápido precisa confirmar no primeiro login nome completo, CPF, nascimento e telefone, com endereço opcional; responsável pode sair e concluir mais tarde (V12, ProfileController, MyProfile).
- [x] Funcionário convidado com dados pessoais completos não passa novamente pela modal; convite válido de e-mail continua necessário.
- [x] Máscara brasileira nos campos do onboarding; recusar CPF inválido e duplicação de CPF nas contas.
- [ ] Validar verificação de e-mail e convite efetivo no Brevo quando as credenciais secretas estiverem presentes no Render.
- [ ] Revisar política de dados e permissão do funcionário ao alterar dados, logout por inatividade, recuperação de senha e autenticação de sessão.

### 02 — Maquininhas, venda e recebíveis (P0)
- [x] Substituir campo de taxas em texto pelo modelo de cadastro PhysioEvo: débito, prazo de crédito 1x, faixas do parcelado, componente de parcelamento, tarifa fixa, antecipação (V11).
- [x] Mostrar lista/edição/desativação e prévia de taxas; preservar histórico existente.
- [x] Usar mesmo serviço de taxas no simulador e ao lançar a venda; vendedor pode selecionar maquininha sem acesso ao financeiro geral.
- [x] Exibir na venda os prazos de 1x, a quantidade de parcelas válida e a simulação de valor cobrado, desconto e líquido.
- [ ] Ajustar máscaras e validações de CPF/CNPJ na jornada de clientes e emissão de recibos.
- [ ] Revisar competência de recebíveis em cada parcela, conciliação de taxas e reversão da venda com casos extremos.

### 03 — Site e campanhas da ótica (P0)
- [x] Site não vem habilitado por padrão; a decisão comercial permanece exclusiva do operador LCM.
- [x] Exibir o bloco **Meu site e campanhas** mesmo quando bloqueado, explicando como solicitar a liberação.
- [x] Manter API pública e editor protegidos até `site_enabled=true`.
- [x] Tela de campanha já possui título, descrição, template, fonte, cor, logo e link por slug.
- [ ] Refinar identidade automática derivada da logo, checar acessibilidade/contraste e separar tipos de campanha (vendas ou atendimento domiciliar).
- [ ] Confirmar upload de receita, agendamento pelo site, aviso a funcionários, aceite via WhatsApp, visita e conversão com rastreio completo.
- [ ] Polir tema e visualização da página pública no mobile.

### 04 — Calendário, metas e relatórios (P0)
- [x] Calendário por ótica com exceções e revisão mensal pelo Master; dias de funcionamento contam mesmo sem venda.
- [x] Consolidado com 14 indicadores e exportações PDF/XLSX/CSV.
- [ ] Completar fontes confiáveis de feriados municipais/estaduais (IBGE e legislação), sem inventar dados regionais.
- [ ] Conferir relatório resumido da fotografia e detalhados de vendedores, atendimentos em domicílio e pacientes, inclusive as datas de corte e diferenças de recebíveis.

### 05 — Consultas, cadastros e pós-venda (P0)
- [x] Separação entre paciente de consulta parceira e cliente de venda; conversão ao concluir venda.
- [x] Agendamento de consulta, clínicas parceiras e estados da consulta.
- [x] Aniversariantes e saudação WhatsApp somente com preferência autorizada.
- [ ] Reforçar deduplicação por CPF e nome, fluxos de remarcação/conflito de agenda, anexos de receitas e permissões.
- [ ] Verificar prazos de retorno 3/6/9/12 meses, entrega e recuperação de oportunidades não convertidas.
- [ ] Validar recibo real de cada pagamento liquidado, logo, assinatura e link temporário revogável.

### 06 — SaaS, pagamentos e acesso aos usuários (P0)
- [x] Trial de sete dias, cortesia permanente configurável, operador global, configuração por ótica.
- [ ] Completar sandbox Asaas (chave, webhook separado e idempotência), Brevo SMTP real e e-mails transacionais.
- [ ] Validar bloqueios de acesso/assinatura e transições para PAID; isolar segredos e ambientes.
- [ ] Auditoria e paginação no painel LCM para crescimento.

### 07 — Experiência mobile/PWA e notificações (P1)
- [ ] Revisar instalação PWA via Chrome, estado offline explicativo e compatibilidade mobile/tablet/desktop.
- [ ] Revisar notificações com permissão e políticas de consentimento (push com app fechado dependerá de integração apropriada).
- [ ] Linguagem simples, estados vazios, textos de ajuda por funcionalidade, acessibilidade e feedback de ação.

### 08 — Aceite final de homologação (QA somente após implementar os blocos acima)
- [ ] Compilação e testes CI verdes, sem migrations quebradas no Render (isso é disciplina contínua, não substitui E2E).
- [ ] Executar **um plano E2E completo** com contas fictícias, dois tenants, operador LCM, Master, vendedor, cliente, consulta, venda e parcelas, relatórios, recibos, site liberado/bloqueado, PWA e webhooks sandbox.
- [ ] Corrigir bugs encontrados pelo E2E e apresentar evidências e links.
- [ ] Somente após aprovação promover `hml` para `main` / produção, com credenciais e webhook de produção novos.

## Observações de segurança e licenças
- A liberação do site no painel LCM é funcionalidade comercial controlada pelo servidor, não simples ocultação de bloco no frontend.
- E-mails e pagamentos de sandbox dependem de variáveis privadas no Render. Nenhuma credencial de SaaS anterior deve ser publicada em Git, documentos ou Trello.
- Optics, PhysioEvo e Ecclesia Finance compartilham princípios de arquitetura, **não bancos de dados ou dados pessoais de clientes**.
