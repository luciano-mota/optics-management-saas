package br.com.lcmtech.optics.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FinanceSettlementIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private record Shop(UUID optic, UUID account, String email, UUID sale, UUID entry, UUID cardEntry) {}

    /** Ótica com uma venda de R$ 1.000 a receber em dinheiro (crediário) e uma parcela de cartão de R$ 300 (taxa 15). */
    private Shop seed() {
        UUID optic = UUID.randomUUID(), account = UUID.randomUUID();
        String email = account + "@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Dona',now())",
                account, email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email,legal_name,tax_document,address,contact_phone) "
                + "VALUES(?,?,'Ótica Baixa',?,'Ótica Baixa LTDA','11222333000181','Rua A, 1','98999999999')",
                optic, optic.toString(), email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        jdbc.update("INSERT INTO optic_business_calendar(optic_id,state_uf,municipality_ibge,municipality_name,open_weekdays,"
                + "time_zone) VALUES(?,'MA','2111300','São Luís','1,2,3,4,5,6','America/Fortaleza')", optic);
        UUID customer = UUID.randomUUID(), lens = UUID.randomUUID(), frame = UUID.randomUUID(), sale = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(optic_id,id,full_name) VALUES(?,?,'Cliente Crediário')", optic, customer);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'LENS','Essilor')", optic, lens);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'FRAME','Ray-Ban')", optic, frame);
        jdbc.update("""
                INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,replacement_months,gross_amount,fee_amount,
                    charged_amount,net_amount,payment_method,sold_on,created_at)
                VALUES(?,?,?,?,?,?,12,1300,15,1300,1285,'DINHEIRO','2026-11-02','2026-11-02T12:00:00Z')
                """, optic, sale, customer, account, lens, frame);
        UUID entry = UUID.randomUUID(), card = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO finance_entries(optic_id,id,direction,category,description,gross_amount,fee_amount,net_amount,
                    occurred_on,due_on,origin,sale_id,created_by,created_at)
                VALUES(?,?,'INCOME','Venda','Crediário',1000,0,1000,'2026-11-02','2026-11-30','SALE',?,?,'2026-11-02T12:00:00Z')
                """, optic, entry, sale, account);
        jdbc.update("""
                INSERT INTO finance_entries(optic_id,id,direction,category,description,gross_amount,fee_amount,net_amount,
                    occurred_on,due_on,origin,sale_id,created_by,created_at)
                VALUES(?,?,'INCOME','Venda','Cartão',300,15,285,'2026-11-02','2026-12-02','SALE',?,?,'2026-11-02T12:00:00Z')
                """, optic, card, sale, account);
        return new Shop(optic, account, email, sale, entry, card);
    }

    private MvcResult send(String who, String path, String body) throws Exception {
        return mvc.perform(post(path).with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : body)).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode summary(String who, String asOf) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/reports/monthly-sales-summary?year=2026&month=11&asOf=" + asOf)
                .with(user(who))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("summary");
    }

    @Test
    void receberParteDeixaOSaldoEmAbertoEQuitaNoFim() throws Exception {
        Shop s = seed();
        JsonNode first = body(send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements",
                "{\"amount\":300,\"paidOn\":\"2026-11-05\",\"note\":\"Entrada\"}"));
        assertThat(first.path("open").decimalValue()).isEqualByComparingTo("700");
        assertThat(first.path("settled").asBoolean()).isFalse();
        assertThat(send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements", "{\"amount\":800}").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements", "{\"amount\":0}").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements", "{\"amount\":10,\"paidOn\":\"2099-01-01\"}")
                .getResponse().getStatus()).isEqualTo(400);
        JsonNode list = json.readTree(mvc.perform(get("/api/v1/finance").with(user(s.email()))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        JsonNode row = null;
        for (JsonNode r : list) if (r.path("id").asText().equals(s.entry().toString())) row = r;
        assertThat(row.path("paid_amount").decimalValue()).isEqualByComparingTo("300");
        assertThat(row.path("open_amount").decimalValue()).isEqualByComparingTo("700");
        assertThat(row.path("settled_on").isNull()).isTrue();
        JsonNode summary = json.readTree(mvc.perform(get("/api/v1/finance/summary").with(user(s.email()))).andReturn()
                .getResponse().getContentAsString());
        assertThat(summary.path("received_month").decimalValue()).isEqualByComparingTo("300");
        assertThat(summary.path("receivable").decimalValue()).isEqualByComparingTo("985"); // 700 + 285 do cartão

        JsonNode last = body(send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements", "{\"paidOn\":\"2026-11-06\"}"));
        assertThat(last.path("settled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT settled_on FROM finance_entries WHERE id=?", java.sql.Date.class, s.entry())
                .toString()).isEqualTo("2026-11-06");
        assertThat(send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements", "{}").getResponse().getStatus()).isEqualTo(400);
        JsonNode history = json.readTree(mvc.perform(get("/api/v1/finance/" + s.entry() + "/settlements").with(user(s.email())))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("note").asText()).isEqualTo("Entrada");
    }

    @Test
    void cartaoSoRecebeInteiroParaNaoDividirATaxa() throws Exception {
        Shop s = seed();
        assertThat(send(s.email(), "/api/v1/finance/" + s.cardEntry() + "/settlements", "{\"amount\":100}").getResponse().getStatus())
                .isEqualTo(400);
        // Atalho antigo do botão "Recebido" continua quitando o saldo inteiro.
        assertThat(mvc.perform(patch("/api/v1/finance/" + s.cardEntry() + "/settle").with(user(s.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT amount FROM finance_settlements WHERE entry_id=?", BigDecimal.class,
                s.cardEntry())).isEqualByComparingTo("285");
    }

    @Test
    void cortesAntesEDepoisDaBaixaEEstornoComDataEfetiva() throws Exception {
        Shop s = seed();
        UUID settlement = UUID.fromString(body(send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements",
                "{\"amount\":300,\"paidOn\":\"2026-11-05\"}")).path("settlementId").asText());
        JsonNode before = summary(s.email(), "2026-11-04"), after = summary(s.email(), "2026-11-06");
        assertThat(before.path("netReceived").decimalValue()).isEqualByComparingTo("0");
        assertThat(before.path("toReceive").decimalValue()).isEqualByComparingTo("1285");
        assertThat(after.path("netReceived").decimalValue()).isEqualByComparingTo("300");
        assertThat(after.path("toReceive").decimalValue()).isEqualByComparingTo("985");
        // O detalhe soma igual ao resumo, inclusive com o recebimento parcial.
        JsonNode received = json.readTree(mvc.perform(get("/api/v1/reports/monthly-sales-summary/detail/recebidos?year=2026&month=11&asOf=2026-11-06")
                .with(user(s.email()))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(received.path("rows")).hasSize(1);
        assertThat(received.path("rows").get(0).get(7).asText()).isEqualTo("300.00");
        JsonNode open = json.readTree(mvc.perform(get("/api/v1/reports/monthly-sales-summary/detail/a-receber?year=2026&month=11&asOf=2026-11-06")
                .with(user(s.email()))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        BigDecimal openTotal = BigDecimal.ZERO;
        for (JsonNode r : open.path("rows")) openTotal = openTotal.add(new BigDecimal(r.get(9).asText()));
        assertThat(openTotal).isEqualByComparingTo("985");

        assertThat(send(s.email(), "/api/v1/finance/settlements/" + settlement + "/reverse",
                "{\"reversedOn\":\"2026-11-04\"}").getResponse().getStatus()).isEqualTo(400); // antes do recebimento
        JsonNode reversed = body(send(s.email(), "/api/v1/finance/settlements/" + settlement + "/reverse",
                "{\"reversedOn\":\"2026-11-07\",\"reason\":\"Cheque devolvido\"}"));
        assertThat(reversed.path("open").decimalValue()).isEqualByComparingTo("1000");
        assertThat(summary(s.email(), "2026-11-06").path("netReceived").decimalValue()).isEqualByComparingTo("300");
        assertThat(summary(s.email(), "2026-11-08").path("netReceived").decimalValue()).isEqualByComparingTo("0");
        assertThat(send(s.email(), "/api/v1/finance/settlements/" + settlement + "/reverse", "{}").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT reversal_reason FROM finance_settlements WHERE id=?", String.class, settlement))
                .isEqualTo("Cheque devolvido");
    }

    @Test
    void reciboDeRecebimentoParcialMostraOSaldoEEstornoAparece() throws Exception {
        Shop s = seed();
        UUID first = UUID.fromString(body(send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements",
                "{\"amount\":300}")).path("settlementId").asText());
        JsonNode receipt = body(send(s.email(), "/api/v1/receipts/settlements/" + first + "/issue", null));
        assertThat(receipt.path("alreadyIssued").asBoolean()).isFalse();
        assertThat(body(send(s.email(), "/api/v1/receipts/settlements/" + first + "/issue", null)).path("alreadyIssued").asBoolean())
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT payment_gross FROM sale_receipts WHERE settlement_id=?", BigDecimal.class, first))
                .isEqualByComparingTo("300");
        assertThat(jdbc.queryForObject("SELECT snapshot->>'pendingGross' FROM sale_receipts WHERE settlement_id=?",
                String.class, first)).isEqualTo("1000.00"); // 700 do crediário + 300 do cartão ainda não recebido
        UUID second = UUID.fromString(body(send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements", "{}"))
                .path("settlementId").asText());
        JsonNode other = body(send(s.email(), "/api/v1/receipts/settlements/" + second + "/issue", null));
        assertThat(other.path("receiptNumber").asLong()).isEqualTo(receipt.path("receiptNumber").asLong() + 1);
        JsonNode payments = json.readTree(mvc.perform(get("/api/v1/receipts/sales/" + s.sale() + "/payments").with(user(s.email())))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(payments).hasSize(3); // 2 recebimentos do crediário + parcela de cartão sem recebimento
        send(s.email(), "/api/v1/finance/settlements/" + first + "/reverse", "{\"reason\":\"Erro\"}");
        JsonNode report = json.readTree(mvc.perform(get("/api/v1/reports/detailed/recibos?status=REVERSED").with(user(s.email())))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(report.path("totalRows").asInt()).isEqualTo(1);
        assertThat(send(s.email(), "/api/v1/receipts/settlements/" + first + "/issue", null).getResponse().getStatus())
                .isEqualTo(200); // já emitido antes do estorno: devolve o mesmo
    }

    @Test
    void outraOticaNaoBaixaNemEstornaNemVeHistorico() throws Exception {
        Shop a = seed(), b = seed();
        UUID settlement = UUID.fromString(body(send(a.email(), "/api/v1/finance/" + a.entry() + "/settlements",
                "{\"amount\":100}")).path("settlementId").asText());
        assertThat(send(b.email(), "/api/v1/finance/" + a.entry() + "/settlements", "{\"amount\":50}").getResponse().getStatus())
                .isEqualTo(404);
        assertThat(send(b.email(), "/api/v1/finance/settlements/" + settlement + "/reverse", "{}").getResponse().getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/finance/" + a.entry() + "/settlements").with(user(b.email())))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(send(b.email(), "/api/v1/receipts/settlements/" + settlement + "/issue", null).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finance_settlements WHERE entry_id=?", Integer.class, a.entry()))
                .isEqualTo(1);
    }

    private int cancel(String who, UUID sale, String body) throws Exception {
        return mvc.perform(patch("/api/v1/sales/" + sale + "/status").with(user(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }

    private String saleStatus(UUID sale) {
        return jdbc.queryForObject("SELECT status FROM sales WHERE id=?", String.class, sale);
    }

    @Test
    void cancelarVendaJaPagaEstornaORecebidoComDataEMotivo() throws Exception {
        Shop s = seed();
        send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements", "{\"amount\":300,\"paidOn\":\"2026-11-05\"}");
        // Sem motivo, ou com devolução antes do pagamento: recusa e nada muda.
        assertThat(cancel(s.email(), s.sale(), "{\"status\":\"CANCELLED\"}")).isEqualTo(400);
        assertThat(cancel(s.email(), s.sale(), "{\"status\":\"CANCELLED\",\"reason\":\"Desistiu\",\"refundedOn\":\"2026-11-04\"}"))
                .isEqualTo(400);
        assertThat(saleStatus(s.sale())).isEqualTo("AWAITING_PRODUCTION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finance_settlements WHERE entry_id=? AND reversed_at IS NULL",
                Integer.class, s.entry())).isEqualTo(1);

        MvcResult ok = mvc.perform(patch("/api/v1/sales/" + s.sale() + "/status").with(user(s.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\",\"reason\":\"Desistiu\",\"refundedOn\":\"2026-11-08\"}")).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).path("refunded").decimalValue()).isEqualByComparingTo("300");
        var st = jdbc.queryForMap("SELECT reversed_on, reversal_reason, reversed_by FROM finance_settlements WHERE entry_id=?", s.entry());
        assertThat(st.get("reversed_on").toString()).isEqualTo("2026-11-08");
        assertThat(st.get("reversal_reason")).isEqualTo("Venda cancelada: Desistiu");
        assertThat(st.get("reversed_by")).isEqualTo(s.account());
        // Nenhuma baixa ativa em lançamento estornado; o caixa do mês não conta o valor devolvido.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM finance_settlements st JOIN finance_entries e ON e.id=st.entry_id
                WHERE e.sale_id=? AND (st.reversed_at IS NULL OR e.reversed_at IS NULL)""", Integer.class, s.sale())).isZero();
        JsonNode summary = json.readTree(mvc.perform(get("/api/v1/finance/summary").with(user(s.email()))).andReturn()
                .getResponse().getContentAsString());
        assertThat(summary.path("received_month").decimalValue()).isEqualByComparingTo("0");
        // A fotografia de um corte entre o pagamento e a devolução continua mostrando os 300 recebidos.
        assertThat(summary(s.email(), "2026-11-06").path("netReceived").decimalValue()).isEqualByComparingTo("300");
    }

    @Test
    void quemNaoMexeNoFinanceiroNaoCancelaVendaPagaEOutraOticaNaoCancela() throws Exception {
        Shop s = seed(), other = seed();
        send(s.email(), "/api/v1/finance/" + s.entry() + "/settlements", "{\"amount\":100}");
        UUID seller = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Vera',now())",
                seller, seller + "@example.test");
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'SECRETARY')", s.optic(), seller);
        assertThat(cancel(seller + "@example.test", s.sale(), "{\"status\":\"CANCELLED\",\"reason\":\"Teste\"}")).isEqualTo(403);
        assertThat(cancel(other.email(), s.sale(), "{\"status\":\"CANCELLED\",\"reason\":\"Teste\"}")).isEqualTo(404);
        assertThat(saleStatus(s.sale())).isEqualTo("AWAITING_PRODUCTION");
        // Venda sem nenhum recebimento: a secretária pode cancelar, sem motivo obrigatório.
        assertThat(cancel(seller + "@example.test", other.sale(), "{\"status\":\"CANCELLED\"}")).isEqualTo(404);
        assertThat(cancel(other.email(), other.sale(), "{\"status\":\"CANCELLED\"}")).isEqualTo(200);
    }

    @Test
    void estornarLancamentoManualEstornaTambemOQueJaFoiPago() throws Exception {
        Shop s = seed();
        UUID manual = UUID.fromString(body(send(s.email(), "/api/v1/finance",
                "{\"direction\":\"EXPENSE\",\"category\":\"Aluguel\",\"description\":\"Aluguel\",\"amount\":500}")).path("id").asText());
        send(s.email(), "/api/v1/finance/" + manual + "/settlements", "{\"amount\":200}");
        MvcResult r = mvc.perform(patch("/api/v1/finance/" + manual + "/reverse").with(user(s.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Lançado em duplicidade\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).path("settlementsReversed").decimalValue()).isEqualByComparingTo("200");
        assertThat(jdbc.queryForObject("SELECT reversal_reason FROM finance_settlements WHERE entry_id=?", String.class, manual))
                .isEqualTo("Lançado em duplicidade");
        // Lançamento de venda não se estorna por aqui.
        assertThat(mvc.perform(patch("/api/v1/finance/" + s.entry() + "/reverse").with(user(s.email())).with(csrf()))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void lancamentoJaPagoGanhaBaixaIntegralAutomatica() {
        Shop s = seed();
        UUID entry = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO finance_entries(optic_id,id,direction,category,description,gross_amount,net_amount,occurred_on,
                    settled_on,created_by) VALUES(?,?,'EXPENSE','Aluguel','Aluguel',2000,2000,'2026-11-05','2026-11-05',?)
                """, s.optic(), entry, s.account());
        assertThat(jdbc.queryForObject("SELECT amount FROM finance_settlements WHERE entry_id=?", BigDecimal.class, entry))
                .isEqualByComparingTo("2000");
    }
}
