# Optics Management

SaaS de gestão intuitiva para óticas, da LCM Dev Tech.

**Status:** em homologação na branch `hml` (Render). Módulos principais implementados; ainda não usar com dados reais de clientes.

## Branches
- `main` — reservada à produção; não fazer merge sem homologação.
- `hml` — desenvolvimento e homologação (Render existente deverá apontar para esta branch).

## Stack
Java 21, Spring Boot 3.3.13, PostgreSQL 16, Flyway, React 18, TypeScript e Vite. Monólito modular orientado por domínios.

## Iniciar ambiente
1. `docker compose up -d` (Postgres local; senha de desenvolvimento apenas).
2. `cd backend && DB_PASSWORD=local-dev-only mvn spring-boot:run`.
3. `cd frontend && npm install && npm run dev`.

Health: GET /actuator/health. Status público: GET /api/v1/public/status. Todas as demais rotas /api/** permanecem fechadas.

Entrar como operador da LCM: defina `PLATFORM_ADMIN_EMAIL` e `PLATFORM_ADMIN_PASSWORD` antes de subir o backend
(a conta é criada na primeira inicialização). As demais variáveis estão em [variáveis de ambiente](docs/VARIAVEIS_DE_AMBIENTE.md).

Testes (os mesmos do CI):
- `cd backend && mvn -B verify` (integração com PostgreSQL; banco `optics_management`, senha `local-dev-only`);
- `cd frontend && npm ci && npm run build` (TypeScript + build de produção);
- ponta a ponta no navegador: veja [e2e/README.md](e2e/README.md) (`node e2e/optics-e2e.js`, Playwright + Chromium).

Última auditoria de homologação: [relatório de 14/11/2026](docs/RELATORIO_E2E_HOMOLOGACAO.md).

Documentação: [arquitetura](docs/ARQUITETURA.md), [reuso/roadmap](docs/REUSO_E_ROADMAP.md) e [identidade visual, CEP e localização](docs/IDENTIDADE_VISUAL_E_ENDERECO.md).
Backlog: https://trello.com/b/GxJ8VWIM/optics-management-saas-de-gest%C3%A3o-para-%C3%B3ticas.

**Segurança:** nunca enviar dados reais de clientes ou receitas ao ambiente de homologação. Verificação em duas etapas (MFA) e notificações push já existem; antivírus de anexos ainda não (os anexos são
conferidos pelo tipo real do arquivo e pelo tamanho).
