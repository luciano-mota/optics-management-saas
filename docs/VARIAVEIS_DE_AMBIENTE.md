# Variáveis de ambiente aceitas

O Optics aceita os nomes usados no PhysioEvo e no Ecclesia Finance (`config/EnvironmentAliases`).
Para cada item vale o primeiro nome preenchido, na ordem abaixo. Nenhum valor é registrado em log.

| Configuração | Nomes aceitos |
|---|---|
| E-mail do operador (dono do sistema) | `BOOTSTRAP_ADMIN_EMAIL`, `PLATFORM_ADMIN_EMAIL`, `OPTICS_OPERATOR_EMAIL`, `ADMIN_EMAIL` |
| Senha do operador | `BOOTSTRAP_ADMIN_PASSWORD`, `PLATFORM_ADMIN_PASSWORD`, `OPTICS_OPERATOR_PASSWORD`, `ADMIN_PASSWORD` |
| Banco | `DB_URL`, `JDBC_DATABASE_URL`, `DATABASE_URL` (JDBC ou `postgres://...` do Render) ou `DB_HOST`/`DB_PORT`/`DB_NAME` |
| Usuário / senha do banco | `DB_USERNAME`, `DATABASE_USER`, `DATABASE_USERNAME` / `DB_PASSWORD`, `DATABASE_PASSWORD` |
| E-mail pela API do Brevo | `BREVO_API_KEY` (tem prioridade sobre SMTP) |
| E-mail por SMTP | `SPRING_MAIL_*`, `MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD`, `SMTP_*` |
| Remetente | `OPTICS_MAIL_FROM`, `MAIL_FROM`, `BREVO_SENDER_EMAIL`; nome: `MAIL_FROM_NAME` |
| Cookie seguro (HTTPS) | `COOKIE_SECURE`, `SESSION_COOKIE_SECURE` |
| Endereço público do sistema | `OPTICS_PUBLIC_BASE_URL`, `APP_PUBLIC_URL`, `OPTICS_BASE_URL` |
| Cadastro aberto | `OPTICS_SIGNUP_ENABLED`, `SIGNUP_ENABLED` |
| Exigir confirmação de e-mail | `OPTICS_EMAIL_VERIFICATION_REQUIRED`, `EMAIL_VERIFICATION_REQUIRED`, `SIGNUP_EMAIL_VERIFICATION_REQUIRED` |
| Contato da plataforma | `OPTICS_CONTACT_EMAIL`, `PLATFORM_CONTACT_EMAIL` |
| Asaas | `OPTICS_ASAAS_API_KEY`/`ASAAS_API_KEY`, `OPTICS_ASAAS_WEBHOOK_TOKEN`/`ASAAS_WEBHOOK_TOKEN`, `OPTICS_ASAAS_BASE_URL`/`ASAAS_BASE_URL` |

## Operador (dono do sistema)

- Criado na inicialização, **somente enquanto não existir nenhum operador**. Senha: 12 a 128 caracteres,
  com letras e números.
- Se o e-mail já tiver uma conta (por exemplo, uma ótica de teste), essa conta recebe a senha das
  variáveis e o acesso de operador. Depois que o operador existe, novos deploys não alteram senhas.
- O log de inicialização informa o resultado (`BootstrapAdmin`), sem mostrar e-mail nem senha.
