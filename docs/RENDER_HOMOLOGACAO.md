# Render — homologação Optics Management (09/11/2026)

## Estado real

- Workspace selecionado: **Optics Management** (Render).
- Banco criado: `optics-management-hml-db`, PostgreSQL 16, free, região Oregon.
- ID do banco: `dpg-dbp0sup42hec73fpdsbg-a`.
- Painel do banco: https://dashboard.render.com/d/dpg-dbp0sup42hec73fpdsbg-a
- Vencimento informado pelo Render do banco gratuito: **09/12/2026**.
- Web service **não criado**; nem frontend nem backend estão publicamente disponíveis no Render.
- Bloqueio verificado: o Render rejeitou o Blueprint e a criação do serviço porque sua conexão GitHub não tem acesso ao repositório privado `luciano-mota/optics-management-saas`.
- Um Render PostgreSQL gratuito não equivale a um banco de produção (limites, retenção, expiração e backups).

## Ação que depende do proprietário

No GitHub/Render, autorizar a integração GitHub do Render para o repositório privado **luciano-mota/optics-management-saas**, mantendo-o privado. Em geral, isso pode ser feito na configuração das instalações do aplicativo Render no GitHub, dando acesso a esse repositório, ou no fluxo de conectar repositório no Dashboard do Render. Não publicar o repositório para contornar o bloqueio.

Depois da autorização, provisionar o serviço Docker por `render.yaml` a partir da branch `hml` e vincular **o banco existente** `optics-management-hml-db` (não criar banco duplicado). Confirmar nomes e valores do Blueprint antes de aplicar. Não promover `main`.

## Variáveis de ambiente (não versionar segredos)

Obrigatórias para conectar a API ao PostgreSQL:

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME` e `DB_PASSWORD` (preencher com propriedades privadas do banco Render)
- `PORT` (Render define automaticamente; o padrão da aplicação é 8080)
- `COOKIE_SECURE=true`
- `OPTICS_PUBLIC_BASE_URL=https://<URL real do web service>`

Recomendadas antes de cadastrar terceiros:

- `OPTICS_EMAIL_VERIFICATION_REQUIRED=true`
- `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` e `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH/STARTTLS_ENABLE` conforme provedor; `OPTICS_MAIL_FROM` com remetente validado
- `OPTICS_OPERATOR_EMAIL` e `OPTICS_OPERATOR_BOOTSTRAP_CODE` forte, aleatório e privado: o operador cadastra sua conta normal e usa `POST /api/v1/public/operator-claim` uma única vez para habilitar console LCM. Remover o código de bootstrap após o uso.
- `OPTICS_ASAAS_API_KEY`, `OPTICS_ASAAS_WEBHOOK_TOKEN`, `OPTICS_ASAAS_BASE_URL` (sandbox primeiro, **não** usar uma chave de produção antes da homologação de cobrança)

O frontend React é compilado dentro do mesmo contêiner Docker do backend Java, servido no mesmo host para evitar problemas de CORS e autenticação por cookie. O banco roda em recurso PostgreSQL Render separado.

## Validação obrigatória após criar o serviço

1. GitHub Actions: jobs `backend`, `frontend` e `Docker + API + PostgreSQL` verdes no commit da `hml`.
2. Render service `Live` sem erro de startup, `GET /actuator/health` com `UP`, e log do Flyway aplicando V1–V6 em **banco próprio vazio**.
3. `GET /` retorna HTML da SPA compilada; `GET /api/v1/public/status` retorna `optics-management`; `GET /manifest.webmanifest` carrega.
4. `GET /api/v1/customers` sem login retorna 401.
5. Cadastrar ótica fictícia, fazer login, inserir cliente fictício, lente, armação, registrar venda, verificar lançamento/entrega, gerar PDF e consultar contadores.
6. Testar segunda ótica e conferir que não lê clientes/vendas/receitas da primeira.
7. Simular site desligado por padrão; operador libera; enviar solicitação de visita fictícia; confirmar, atender e converter em venda.
8. Validar convite de funcionário com permissões escolhidas, e-mail de recuperação e bloqueio de usuário não autorizado.
9. Sandbox Asaas: criar pagamento, simular eventos válidos/duplicados, rejeitar webhook com token inválido e garantir cortesia sem cobrança.
10. Verificar layout celular, tablet, desktop e expiração de sessão. Não inserir dados reais de clientes nem de receita até aprovação LGPD/segurança.

## Pendências críticas para uso real

- Verificação SMTP/Asaas **não validada em Render**, pois faltam credenciais e serviço web.
- Política formal de retenção e exclusão (inclui anonimização de cliente pela LGPD), varredura de arquivos e proteção
  anti-spam da página pública exigem trabalho adicional. MFA e notificações push (Web Push) já estão implementados.
- Fora do produto por decisão (13/11): emissão fiscal (NF-e) e WhatsApp oficial pago; o WhatsApp é manual, com mensagem pronta.
- PDF e XLSX já são gerados, mas a formatação personalizada avançada com logotipo renderizado ainda está em evolução.
- Não afirmar homologação completa antes de teste E2E no Render.
