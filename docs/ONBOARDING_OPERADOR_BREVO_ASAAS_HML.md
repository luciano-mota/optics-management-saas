# Acesso inicial do dono LCM, e-mail e Asaas de homologação

**Data:** 09/11/2026  
**Ambiente:** https://optics-management-hml.onrender.com (branch `hml`)  
**Render:** https://dashboard.render.com/web/srv-dbp2vll9fdbs73f1rkng  
**Status:** fluxo do operador implementado, credenciais privadas Brevo e Asaas ainda dependem de provisionamento no serviço. **Não declarar e-mail enviado até testar realmente.**

## 1. Conta do dono / operador global

O painel do proprietário da plataforma é separado de `MASTER` (um Master gerencia apenas sua ótica).

**E-mail autorizado em HML:** `Amoosenhorjesus3@gmail.com`, que já pertence à conta operadora do Render/Ecclesia; alterar `OPTICS_OPERATOR_EMAIL` no Render se o proprietário decidir usar outro endereço. Não reutilizar endereços de remetentes transacionais como credenciais de login.

1. Abrir https://optics-management-hml.onrender.com.
2. Na tela de login, clicar **Sou responsável pela LCM Dev Tech**.
3. Informar o e-mail autorizado e clicar **Enviar meu link de acesso**.
4. Receber e-mail do Brevo com link válido por 30 minutos.
5. No link `/?ownerInvite=...`, informar nome, senha forte e aceitar termos.
6. Concluir ativação, entrar com o mesmo e-mail e senha. Uma conta sem vínculo de ótica entra diretamente no **Painel LCM**.

**Segurança:** o servidor confirma e-mail igual a `OPTICS_OPERATOR_EMAIL`, com token aleatório de 32 bytes/armazenado como SHA-256, 30 minutos de validade, uso único, limite de solicitação por 10 minutos, auditoria no Postgres. Nunca criar ótica fictícia para acesso do operador. O antigo endpoint público de ativação por código foi removido.

## 2. E-mails — reutilizar a mesma infraestrutura da LCM

Configurações públicas já aplicadas via Render:
- `SPRING_MAIL_HOST=smtp-relay.brevo.com`
- `SPRING_MAIL_PORT=2525` (STARTTLS obrigatório no `application.yml`)
- `OPTICS_MAIL_FROM=nao-responda@lcmtech.com.br`
- Suporte/retorno: `contato@lcmtech.com.br`; comunicações financeiras: `financeiro@lcmtech.com.br`.
- `OPTICS_EMAIL_VERIFICATION_REQUIRED=true` — fail closed: o cadastro de ótica não deve ser liberado até o SMTP estar operacional.

**Faltam as duas variáveis secretas para envio real**; adicionar **no Render → optics-management-hml → Environment**, copiando os valores usados no PhysioEvo/Ecclesia ou os valores atuais do painel Brevo:
- `SPRING_MAIL_USERNAME` = **login SMTP Brevo** (pode diferir de e-mail da conta e do remetente).
- `SPRING_MAIL_PASSWORD` = **chave SMTP Brevo** (não é senha do e-mail, nem API key).

**Não inserir segredos em Git, Trello ou chat.** Após salvar, aguardar o deploy automático. Testar convite LCM real, confirmar recebimento em Inbox/Spam, completar cadastro, autenticar e acessar `Painel LCM`. Testar cadastro de ótica separadamente: registro público -> e-mail com link `?verify` -> confirmar -> login. Testar convite por `Minha equipe` e `Esqueci a senha`. A aplicação só declara o convite como enviado se a camada SMTP estiver configurada e o envio ao relay não lançar erro; recebimento no destinatário ainda exige verificação manual.

Fonte SMTP: https://help.brevo.com/hc/pt/articles/7924908994450-Envie-e-mails-transacionais-usando-o-Brevo-SMTP

## 3. Asaas sandbox

A URL da API configurada no Optics HML é **https://api-sandbox.asaas.com/v3**. Falta:
- `OPTICS_ASAAS_API_KEY`: **chave de sandbox** da conta Asaas já usada nos testes de PhysioEvo/Ecclesia. **Não inserir chave de produção** apontando para sandbox.
- `OPTICS_ASAAS_WEBHOOK_TOKEN`: token seguro dedicado à integração Optics sandbox; configurar o mesmo valor no cadastro do webhook sandbox Asaas.
- URL do webhook do Optics: `https://optics-management-hml.onrender.com/api/v1/webhooks/asaas`.
- Ativar eventos de cobrança relevantes, confirmar status/assinaturas e testar repetição idempotente. Não reutilizar endpoint do PhysioEvo/Ecclesia: cada produto possui webhook isolado e referência própria.

**Produção:** manter `main` intacta; usar chave de produção somente na implantação PROD posterior, com webhook próprio que o proprietário criará.

Fonte Asaas: https://docs.asaas.com/docs/criando-um-link-de-pagamentos

## 4. Critérios de aceitação imediatos

- [ ] Brevo conectado e SMTP autenticado; convite proprietário **recebido** (não apenas aceito pelo relay).
- [ ] Link do proprietário de uso único e 30 minutos; repetição negada.
- [ ] Conta global sem ótica abre Painel LCM; MASTER de uma ótica **não** acessa o painel global.
- [ ] Cadastro da ótica envia verificação; até confirmar e-mail, login não funciona.
- [ ] Convite funcionário enviado ao e-mail pessoal/corporativo; pessoa define senha, completa CPF/nascimento/telefone e entra com permissões escolhidas.
- [ ] Recuperação de senha por link funciona.
- [ ] Sandbox Asaas cria link de assinatura e webhook único confirma cobrança; PROD não é afetada.
- [ ] CI verde, Render deploy `Live`, Flyway até `V8`, dados isolados de outras óticas.

## 5. Restrições atuais

O conector Render permite modificar variáveis de ambiente, mas **não disponibiliza valores secretos já existentes em outros serviços/workspaces**. Por isso, credenciais SMTP/Asaas não puderam ser copiadas automaticamente. A criação de contas por e-mail só estará operacional depois que as variáveis privadas forem configuradas manualmente. Não gerar chaves Asaas nem credenciais Brevo fictícias.
