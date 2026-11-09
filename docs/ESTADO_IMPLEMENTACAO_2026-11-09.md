# Optics Management — estado de implementação em 09/11/2026

Repositório: https://github.com/luciano-mota/optics-management-saas/tree/hml

## Implementado em código (necessita QA Render)

- Monólito Java 21 / Spring Boot, React + Vite, PostgreSQL 16, Flyway, Docker e CI.
- Estrutura multitenant por ótica, contas, vínculos, MASTER e perfis gerente/secretário/vendedor, concessão e negação por permissão, convite com token hash de uso único.
- Cadastro inicial da ótica com 7 dias de teste; painel LCM com lista, contatos, valores, site e avisos internos; cortesia ilimitada e assinatura não visível para cortesias.
- Login com sessão PostgreSQL, CSRF, BCrypt, limitação de tentativas; reset de senha e confirmação por e-mail opcionais quando SMTP configurado.
- Cadastro de clientes e receita anexa foto/PDF protegida, catálogo de lentes e armações.
- Venda com cliente existente ou cadastro no mesmo fluxo transacional, meios Pix/dinheiro/débito/crédito, taxas e recebíveis; entrega e histórico; métricas dia/semana/mês.
- Registros financeiros de entradas/saídas/recebíveis e estornos manuais; cartão com taxas e prazo. Distinção entre caixa liquidado e recebível.
- Portal público por ótica somente após liberação LCM; solicitação de visita com receita, claim atômico, WhatsApp pré-preenchido, estados e conversão lead → venda ou não convertido.
- Retenção por marcos de 3/6/9/12 meses, filtros, WhatsApp preparado sem disparo automático, marcação auditável da abertura.
- Relatórios CSV/XLSX/PDF de vendas e indicadores por funcionário/visita.
- Site com cores, fontes/templates e upload de logo que sugere paleta com contraste; PWA manifesto e cache limitado; badges/avisos do navegador quando aberto.

## Não concluído / limitações verificadas

- **Deploy Render**: repositório privado não acessível pela integração GitHub do Render; DB disponível e web service ausente.
- **Conexão real front + back + banco no Render**: não validada; build/testes GitHub Actions verdes em commits específicos. Pipeline com smoke Docker foi adicionado para ampliar cobertura.
- **Asaas**: integração codificada mas sem chaves/sandbox/webhook homologados.
- **SMTP**: fluxo codificado, mas sem envio real confirmado. `OPTICS_EMAIL_VERIFICATION_REQUIRED` precisa ser ativado com SMTP válido antes de clientes reais.
- **Segurança**: MFA, varredura de malware em anexos, reforço de rate limit público, validação LGPD/fiscal e revisão de isolamento precisam de QA complementar.
- **Notificações**: contadores internos e Notification API durante a sessão; Web Push mesmo com navegador fechado não configurado.
- **Relatórios**: PDF funcional, mas sem template visual avançado/logo gráfico no arquivo.
- **Site**: personalização inicial funcional em código; modelos avançados/campanhas múltiplas ainda não concluídos.
- **Backlog pós-MVP**: estoque, fornecedores, troca/garantia, fiscal NF-e/NFC-e e ordens de serviço não implementados.

## Fontes e controle

Base técnica: módulos de PhysioEvo (`fisio-saas`) e Ecclesia Finance (`church-finance-saas`), reutilizados seletivamente; os bancos e dados de produção originais não foram modificados.

Trello do projeto: https://trello.com/b/GxJ8VWIM/optics-management-saas-de-gest%C3%A3o-para-%C3%B3ticas

Decisão de branches: `hml` homologação e `main` produção. Não realizar merge para `main` sem autorização do proprietário e QA documentado.
