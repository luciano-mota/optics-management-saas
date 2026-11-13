package br.com.lcmtech.optics.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
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
class IndicatorDetailIntegrationTest {
    private static final String PERIOD = "?year=2026&month=11&asOf=2026-11-09";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private record Shop(UUID optic, String master, String seller) {}

    private Shop setup(String customerName) {
        UUID master = UUID.randomUUID(), seller = UUID.randomUUID(), optic = UUID.randomUUID();
        for (UUID a : List.of(master, seller))
            jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Pessoa',now())",
                    a, a + "@example.test");
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica Teste',?)",
                optic, optic.toString(), master + "@example.test");
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, master);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'SELLER')", optic, seller);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        jdbc.update("INSERT INTO optic_business_calendar(optic_id,state_uf,municipality_ibge,municipality_name,"
                + "open_weekdays,time_zone) VALUES(?,'MA','2105401','Itapecuru Mirim','1,2,3,4,5,6','America/Fortaleza')", optic);
        jdbc.update("INSERT INTO optic_monthly_goals(optic_id,calendar_year,calendar_month,goal_amount,updated_by) "
                + "VALUES(?,2026,11,23000,?)", optic, master);
        UUID customer = UUID.randomUUID(), lens = UUID.randomUUID(), frame = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(optic_id,id,full_name) VALUES(?,?,?)", optic, customer, customerName);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'LENS','Essilor')", optic, lens);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'FRAME','Ray-Ban')", optic, frame);
        // Pix recebido na hora.
        UUID pix = sale(optic, master, customer, lens, frame, "2026-11-03", "500.00", "0.00", "PIX", 1);
        entry(optic, master, pix, "500.00", "0.00", "2026-11-03", "2026-11-03");
        // Crédito 3x: 1ª parcela recebida antes do corte, 2 pendentes.
        UUID credit = sale(optic, master, customer, lens, frame, "2026-11-04", "900.00", "45.00", "CREDITO", 3);
        entry(optic, master, credit, "300.00", "15.00", "2026-11-05", "2026-11-05");
        entry(optic, master, credit, "300.00", "15.00", "2026-12-04", null);
        entry(optic, master, credit, "300.00", "15.00", "2027-01-04", null);
        // Cancelada antes do corte: fora de tudo.
        UUID cancelled = sale(optic, master, customer, lens, frame, "2026-11-05", "700.00", "0.00", "PIX", 1);
        entry(optic, master, cancelled, "700.00", "0.00", "2026-11-05", "2026-11-05");
        jdbc.update("INSERT INTO sale_events(id,optic_id,sale_id,actor_id,previous_status,next_status,occurred_at) "
                + "VALUES(?,?,?,?,'AWAITING_PRODUCTION','CANCELLED',?)", UUID.randomUUID(), optic, cancelled, master,
                Timestamp.from(Instant.parse("2026-11-06T12:00:00Z")));
        // Depois do corte: fora da fotografia de 09/11.
        UUID later = sale(optic, master, customer, lens, frame, "2026-11-12", "400.00", "0.00", "PIX", 1);
        entry(optic, master, later, "400.00", "0.00", "2026-11-12", "2026-11-12");
        return new Shop(optic, master + "@example.test", seller + "@example.test");
    }

    private UUID sale(UUID optic, UUID seller, UUID customer, UUID lens, UUID frame, String day, String gross,
                      String fee, String method, int installments) {
        UUID id = UUID.randomUUID();
        BigDecimal g = new BigDecimal(gross), f = new BigDecimal(fee);
        jdbc.update("""
                INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,sold_on,replacement_months,
                    gross_amount,fee_amount,charged_amount,net_amount,payment_method,installments,created_at)
                VALUES(?,?,?,?,?,?,?::date,12,?,?,?,?,?,?,?)
                """, optic, id, customer, seller, lens, frame, day, g, f, g, g.subtract(f), method, installments,
                Timestamp.from(Instant.parse(day + "T12:00:00Z")));
        return id;
    }

    private void entry(UUID optic, UUID actor, UUID sale, String gross, String fee, String due, String settled) {
        BigDecimal g = new BigDecimal(gross), f = new BigDecimal(fee);
        jdbc.update("""
                INSERT INTO finance_entries(optic_id,id,direction,category,description,gross_amount,fee_amount,net_amount,
                    occurred_on,due_on,settled_on,origin,sale_id,created_by,created_at)
                VALUES(?,?,'INCOME','Venda','Óculos',?,?,?,?::date,?::date,?::date,'SALE',?,?,
                    (SELECT created_at FROM sales WHERE id=?))
                """, optic, UUID.randomUUID(), g, f, g.subtract(f), due, due, settled, sale, actor, sale);
    }

    private JsonNode get(String who, String path) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .with(user(who))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static int column(JsonNode report, String header) {
        for (int i = 0; i < report.path("headers").size(); i++)
            if (report.path("headers").get(i).asText().equals(header)) return i;
        throw new AssertionError("coluna " + header);
    }

    private static BigDecimal sum(JsonNode report, String header) {
        int c = column(report, header);
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode row : report.path("rows"))
            if (!row.get(c).asText().isEmpty()) total = total.add(new BigDecimal(row.get(c).asText()));
        return total;
    }

    private static long count(JsonNode report, String header, String value) {
        int c = column(report, header);
        long n = 0;
        for (JsonNode row : report.path("rows")) if (row.get(c).asText().equals(value)) n++;
        return n;
    }

    @Test
    void somaDeCadaDetalheIgualAoIndicador() throws Exception {
        Shop shop = setup("Cliente Alfa");
        JsonNode root = get(shop.master(), "/api/v1/reports/monthly-sales-summary" + PERIOD);
        JsonNode s = root.path("summary");
        for (JsonNode indicator : root.path("indicators"))
            assertThat(IndicatorDetailService.TITLES).containsKey(indicator.path("detail").asText());
        String base = "/api/v1/reports/monthly-sales-summary/detail/";

        JsonNode sales = get(shop.master(), base + "vendas" + PERIOD);
        assertThat(sales.path("rows").size()).isEqualTo(s.path("salesCount").asInt()).isEqualTo(2);
        assertThat(sum(sales, "Valor da venda")).isEqualByComparingTo(s.path("totalSales").decimalValue())
                .isEqualByComparingTo("1400.00");

        JsonNode received = get(shop.master(), base + "recebidos" + PERIOD);
        assertThat(sum(received, "Líquido")).isEqualByComparingTo(s.path("netReceived").decimalValue())
                .isEqualByComparingTo("785.00");

        JsonNode pending = get(shop.master(), base + "a-receber" + PERIOD);
        assertThat(sum(pending, "Líquido")).isEqualByComparingTo(s.path("toReceive").decimalValue())
                .isEqualByComparingTo("570.00");
        assertThat(pending.path("rows").get(0).get(column(pending, "Parcela")).asText()).isEqualTo("2/3");

        JsonNode fees = get(shop.master(), base + "taxas" + PERIOD);
        assertThat(sum(fees, "Taxa")).isEqualByComparingTo(s.path("bankFees").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(fees.path("rows").get(0).get(column(fees, "% da taxa")).asText()).isEqualTo("5,00%");

        JsonNode days = get(shop.master(), base + "dias" + PERIOD);
        assertThat(days.path("rows").size()).isEqualTo(30);
        assertThat(count(days, "Loja", "Aberta")).isEqualTo(s.path("totalOpenDays").asLong());
        assertThat(count(days, "Período", "Trabalhado")).isEqualTo(s.path("workedDays").asLong());
        assertThat(count(days, "Período", "Faltando")).isEqualTo(s.path("remainingDays").asLong());

        JsonNode goals = get(shop.master(), base + "metas" + PERIOD);
        assertThat(sum(goals, "Vendido no dia")).isEqualByComparingTo(s.path("totalSales").decimalValue());
        int acc = column(goals, "Acumulado");
        assertThat(new BigDecimal(goals.path("rows").get(8).get(acc).asText())).isEqualByComparingTo("1400.00");
        assertThat(goals.path("rows").get(9).get(acc).asText()).isEmpty(); // depois do corte
        assertThat(new BigDecimal(goals.path("rows").get(29).get(column(goals, "Meta acumulada")).asText()))
                .isEqualByComparingTo(s.path("monthlyGoal").decimalValue());
    }

    @Test
    void exportaOsTresFormatosERecusaDetalheDesconhecido() throws Exception {
        Shop shop = setup("Cliente Beta");
        for (String kind : IndicatorDetailService.TITLES.keySet())
            for (String format : List.of("csv", "xlsx", "pdf")) {
                byte[] body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/reports/monthly-sales-summary/detail/" + kind + "/export/" + format + PERIOD)
                                .with(user(shop.master())))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                assertThat(body).as(kind + "." + format).hasSizeGreaterThan(100);
            }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/reports/monthly-sales-summary/detail/estoque" + PERIOD).with(user(shop.master())))
                .andExpect(status().isNotFound());
    }

    @Test
    void detalheSoMostraDadosDaPropriaOticaESoParaQuemVeOFinanceiro() throws Exception {
        Shop alfa = setup("Cliente Alfa"), beta = setup("Cliente Beta");
        for (String kind : List.of("vendas", "recebidos", "a-receber", "taxas")) {
            JsonNode mine = get(beta.master(), "/api/v1/reports/monthly-sales-summary/detail/" + kind + PERIOD);
            assertThat(mine.path("rows").toString()).as(kind).doesNotContain("Cliente Alfa");
        }
        assertThat(alfa.optic()).isNotEqualTo(beta.optic());
        // Vendedora não tem FINANCE_READ.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/reports/monthly-sales-summary/detail/recebidos" + PERIOD).with(user(alfa.seller())))
                .andExpect(status().isForbidden());
    }
}
