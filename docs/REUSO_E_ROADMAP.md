# Reuso técnico verificado — 09/11/2026

Repos de origem (somente leitura): luciano-mota/fisio-saas e luciano-mota/church-finance-saas.

| Origem | Evidência | Estratégia |
| --- | --- | --- |
| PhysioEvo | backend finance/CardFees.java; CardFeesTest.java | Copiados com troca de namespace, preservando testes e cálculo bruto/taxas/líquido/recebíveis |
| Ecclesia Finance | backend finance/domain/FinancialAmount.java | Copiado como objeto de valor monetário |
| PhysioEvo | backend billing/AsaasSubscriptions.java, platform/SubscriptionController.java | Estudar e adaptar depois do domínio de assinatura, sem levar termos de clínica |
| Ecclesia Finance | backend api/SecurityConfig.java e authorization/OrganizationAccessService.java | Adaptar papéis e grant; não copiar hierarquias eclesiásticas |
| Ecclesia Finance | backend reporting/ReportExportService.java | Adaptar CSV/XLSX/PDF, sem referência a igrejas |
| PhysioEvo | backend clinic, publicsite e frontend SiteTab/siteTheme | Adaptar site e branding, sujeito ao flag por cliente |

## Entregas
1. Bootstrap: hml/main, arquitetura, estrutura React/Java, migrations iniciais e testes de domínio.
2. Identidade, RBAC, plataforma LCM, trial 7 dias e cortesia ilimitada.
3. Clientes, lentes/armações, vendas, entrega e financeiro integrado.
4. Visita domiciliar, atribuição, conversão e WhatsApp pré-preenchido.
5. Relatórios, campanhas, site opt-in e notificações PWA.
6. QA ponta a ponta e homologação antes do merge para main.

## Regras inegociáveis
- Nunca copiar dados de produção, tokens, credenciais ou configurações dos ambientes existentes.
- Não usar a mesma tabela/schema de PhysioEvo ou Ecclesia.
- Separar vendas realizadas, contas a receber, liquidação no caixa e cálculo de taxas; não inflar faturamento.
- Gratuidade é modalidade do plano que só o operador pode ativar/desativar; aba de assinatura oculta nessa modalidade por resposta autorizada do servidor.
- Site sempre desabilitado ao criar ótica. Endpoint público não pode habilitar essa flag.
- Status de entrega separado de pagamento e dos status de visita.
- Backlog oficial no Trello: https://trello.com/b/GxJ8VWIM/optics-management-saas-de-gest%C3%A3o-para-%C3%B3ticas.
