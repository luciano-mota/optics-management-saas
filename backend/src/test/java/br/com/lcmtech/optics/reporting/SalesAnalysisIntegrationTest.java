package br.com.lcmtech.optics.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SalesAnalysisIntegrationTest {
    private static final String PERIOD = "from=2026-11-01&to=2026-11-15";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private record Shop(String master, UUID ana, UUID bia) {}

    private UUID account(UUID optic, String name, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash',?,now())",
                id, id + "@example.test", name);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,?)", optic, id, role);
        return id;
    }

    private UUID item(UUID optic, String kind, String brand, String category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand,category) VALUES(?,?,?,?,?)", optic, id, kind, brand, category);
        return id;
    }

    private void sale(UUID optic, UUID customer, UUID seller, UUID lens, UUID frame, String method, String amount,
                      String day, String status) {
        BigDecimal v = new BigDecimal(amount);
        jdbc.update("""
                INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,replacement_months,gross_amount,fee_amount,
                    charged_amount,net_amount,payment_method,sold_on,status)
                VALUES(?,?,?,?,?,?,12,?,0,?,?,?,?::date,?)
                """, optic, UUID.randomUUID(), customer, seller, lens, frame, v, v, v, method, day, status);
    }

    private Shop seed(String prefix) {
        UUID optic = UUID.randomUUID(), master = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Dona',now())",
                master, master + "@example.test");
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica',?)", optic, optic.toString(),
                master + "@example.test");
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, master);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        UUID ana = account(optic, prefix + " Ana", "SELLER"), bia = account(optic, prefix + " Bia", "SELLER");
        UUID multi = item(optic, "LENS", "Essilor", "MULTIFOCAL"), plain = item(optic, "LENS", "Genérica", null);
        UUID rayban = item(optic, "FRAME", prefix + "Ray-Ban", "SOLAR"), rayban2 = item(optic, "FRAME", prefix + "Ray-Ban", "GRAU");
        UUID oakley = item(optic, "FRAME", prefix + "Oakley", "GRAU");
        UUID customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(optic_id,id,full_name) VALUES(?,?,'Cliente')", optic, customer);
        sale(optic, customer, ana, multi, rayban, "PIX", "500.00", "2026-11-03", "DELIVERED");
        sale(optic, customer, bia, multi, oakley, "CREDITO", "900.00", "2026-11-04", "AWAITING_PRODUCTION");
        sale(optic, customer, ana, plain, rayban2, "DEBITO", "300.00", "2026-11-10", "AWAITING_PRODUCTION");
        sale(optic, customer, bia, multi, rayban, "PIX", "200.00", "2026-11-11", "CANCELLED");
        sale(optic, customer, ana, multi, rayban, "PIX", "400.00", "2026-10-10", "DELIVERED");
        sale(optic, customer, ana, multi, rayban, "PIX", "1000.00", "2026-10-20", "DELIVERED"); // fora da janela anterior
        return new Shop(master + "@example.test", ana, bia);
    }

    private JsonNode analysis(String who, String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/reports/sales-analysis?" + query).with(user(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void rankingPorFuncionariaComParticipacaoTicketEComparacao() throws Exception {
        Shop s = seed("A");
        JsonNode a = analysis(s.master(), "dimension=seller&" + PERIOD);
        assertThat(a.path("count").asLong()).isEqualTo(3);
        assertThat(a.path("amount").decimalValue()).isEqualByComparingTo("1700.00");
        assertThat(a.path("previousFrom").asText()).isEqualTo("2026-10-01");
        assertThat(a.path("previousAmount").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(a.path("variation").decimalValue()).isEqualByComparingTo("325.0");
        JsonNode first = a.path("rows").get(0), second = a.path("rows").get(1);
        assertThat(first.path("label").asText()).isEqualTo("A Bia");
        assertThat(first.path("share").decimalValue()).isEqualByComparingTo("52.9");
        assertThat(first.path("variation").isNull()).isTrue();
        assertThat(second.path("label").asText()).isEqualTo("A Ana");
        assertThat(second.path("count").asLong()).isEqualTo(2);
        assertThat(second.path("avgTicket").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(second.path("variation").decimalValue()).isEqualByComparingTo("100.0");
        assertThat(second.path("filterValue").asText()).isEqualTo(s.ana().toString());
    }

    @Test
    void marcasETiposAgrupamCertoECliqueAbreAConsultaComOMesmoTotal() throws Exception {
        Shop s = seed("A");
        JsonNode brands = analysis(s.master(), "dimension=frameBrand&" + PERIOD);
        assertThat(brands.path("rows")).hasSize(2); // os dois modelos Ray-Ban somam juntos
        JsonNode lensTypes = analysis(s.master(), "dimension=lensCategory&" + PERIOD);
        assertThat(lensTypes.toString()).contains("Multifocal").contains("Não informado").contains("\"key\":\"NONE\"");
        // Combinação: ranking por funcionária só com armações Ray-Ban.
        JsonNode combined = analysis(s.master(), "dimension=seller&frameBrand=ARay-Ban&" + PERIOD);
        assertThat(combined.path("rows")).hasSize(1);
        assertThat(combined.path("rows").get(0).path("amount").decimalValue()).isEqualByComparingTo("800.00");
        for (String dimension : SalesAnalysisController.DIMENSIONS.keySet()) {
            JsonNode a = analysis(s.master(), "dimension=" + dimension + "&" + PERIOD);
            for (JsonNode row : a.path("rows")) {
                JsonNode detail = json.readTree(mvc.perform(get("/api/v1/reports/detailed/vendas?" + PERIOD)
                        .param(a.path("filter").asText(), row.path("filterValue").asText())
                        .with(user(s.master()))).andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));
                assertThat(detail.path("summary").get(0).asText()).as(dimension + " " + row.path("label").asText())
                        .startsWith("Vendas válidas: " + row.path("count").asLong() + " ");
            }
        }
    }

    @Test
    void validaExportaEIsola() throws Exception {
        Shop a = seed("A"), b = seed("B");
        mvc.perform(get("/api/v1/reports/sales-analysis?dimension=cor").with(user(a.master()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/sales-analysis?from=2026-11-10&to=2026-11-01").with(user(a.master())))
                .andExpect(status().isBadRequest());
        for (String format : List.of("csv", "xlsx", "pdf"))
            assertThat(mvc.perform(get("/api/v1/reports/sales-analysis/export/" + format + "?dimension=frameBrand&" + PERIOD)
                    .with(user(a.master()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                    .hasSizeGreaterThan(100);
        JsonNode mine = analysis(b.master(), "dimension=frameBrand&" + PERIOD);
        assertThat(mine.toString()).contains("BRay-Ban").doesNotContain("ARay-Ban");
        assertThat(analysis(b.master(), "dimension=seller&sellerId=" + a.ana() + "&" + PERIOD).path("rows")).isEmpty();
    }
}
