# Optics Management

SaaS de gestão intuitiva para óticas, da LCM Dev Tech.

**Status:** fundação em desenvolvimento, sem autenticação ou módulos comerciais disponíveis. Não usar com dados reais.

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

Testes: `cd backend && mvn test` e `cd frontend && npm run build`.

Documentação: [arquitetura](docs/ARQUITETURA.md) e [reuso/roadmap](docs/REUSO_E_ROADMAP.md).
Backlog: https://trello.com/b/GxJ8VWIM/optics-management-saas-de-gest%C3%A3o-para-%C3%B3ticas.

**Segurança:** nunca enviar dados reais de clientes ou receitas ao bootstrap; ainda não há login, auditoria por caso de uso nem armazenamento privado implementados.
