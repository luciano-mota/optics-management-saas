# Decisões de produto

Registro das decisões do dono (LCM DEV TECH) que mudam o escopo do Optics Management. Cada decisão vale até ser
revista aqui.

## 13/11/2026 · Sem nota fiscal (NF-e/NFC-e)

- O sistema **não emite nota fiscal**. Emitir exige certificado digital, credenciamento na SEFAZ e um emissor
  (geralmente pago): custo inicial alto para as óticas.
- Os termos de uso já dizem que a plataforma não emite nota fiscal. A ótica continua emitindo pelo sistema que já usa.

## 13/11/2026 · WhatsApp manual (sem WhatsApp oficial pago)

- **Não** há integração com a API oficial do WhatsApp (cobra por mensagem). Nada é enviado automaticamente pelo
  WhatsApp.
- Em cada momento útil, um botão abre o WhatsApp ou o WhatsApp Business do aparelho com a **mensagem pronta**; a pessoa
  da ótica revisa e envia. O link é o `wa.me`, que abre o app instalado (WhatsApp ou Business).
- Saudação só com o primeiro nome; nunca CPF, receita ou valores que não sejam do próprio cliente.
- Número montado num só lugar (`frontend/src/whatsapp.ts`): 55 + DDD + número; número incompleto mostra aviso em vez
  de abrir conversa errada.

Onde o botão aparece:

| Tela | Quando | Mensagem |
| --- | --- | --- |
| Vendas | venda "Pronto para entrega" | óculos prontos para retirar |
| Estoque e serviços → OS | OS "Pronta na loja" | óculos prontos para retirar |
| Consultas de vista | consulta agendada/confirmada futura | lembrete com data, hora e clínica |
| Financeiro | parcela de venda em aberto sem cartão (crediário) | lembrete educado da parcela |
| Clientes para lembrar | 3/6/9/12 meses da compra | retorno para cuidar da visão |
| Aniversariantes | no dia | parabéns |
| Visitas em domicílio | pedido pelo site | confirmar horário |
| Recibos | recibo emitido | link seguro do recibo (24 horas) |
| Fornecedores | cadastro com telefone | abrir conversa |

- Os **e-mails automáticos** (lembrete de consulta e aniversário, só para quem autorizou) continuam: não têm custo
  (Brevo gratuito até 300 por dia) e são desligados por padrão em cada ótica.
