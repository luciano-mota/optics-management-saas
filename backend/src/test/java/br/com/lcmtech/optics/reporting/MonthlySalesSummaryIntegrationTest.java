package br.com.lcmtech.optics.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MonthlySalesSummaryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private record Sample(UUID optic,UUID account,String email){}

    private Sample setup() {
        UUID account=UUID.randomUUID(),optic=UUID.randomUUID();
        String email=account+"@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) "
                +"VALUES(?,?,'hash','Responsável',now())",account,email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,?,?)",
                optic,optic.toString(),"Ótica Teste",email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) "
                +"VALUES(?,?,'MASTER')",optic,account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)",optic);
        jdbc.update("INSERT INTO optic_business_calendar(optic_id,state_uf,municipality_ibge,"
                +"municipality_name,open_weekdays,time_zone) "
                +"VALUES(?,'MA','2105401','Itapecuru Mirim','1,2,3,4,5,6','America/Fortaleza')",
                optic);
        jdbc.update("INSERT INTO optic_monthly_goals(optic_id,calendar_year,calendar_month,"
                +"goal_amount,updated_by) VALUES(?,2026,11,23000,?)",optic,account);
        UUID customer=UUID.randomUUID(),lens=UUID.randomUUID(),frame=UUID.randomUUID();
        jdbc.update("INSERT INTO customers(optic_id,id,full_name) VALUES(?,?,'Comprador')",
                optic,customer);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'LENS','Teste')",
                optic,lens);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'FRAME','Teste')",
                optic,frame);
        insertSale(optic,account,customer,lens,frame,"1000.00","50.00","950.00",true);
        insertSale(optic,account,customer,lens,frame,"500.00","0.00","500.00",false);
        return new Sample(optic,account,email);
    }

    private void insertSale(UUID optic,UUID account,UUID customer,UUID lens,UUID frame,
            String gross,String fee,String net,boolean settled) {
        UUID sale=UUID.randomUUID();
        Timestamp created=Timestamp.from(Instant.parse("2026-11-05T12:00:00Z"));
        jdbc.update("""
            INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,sold_on,
                replacement_months,gross_amount,fee_amount,charged_amount,net_amount,
                payment_method,created_at)
            VALUES(?,?,?,?,?,?,'2026-11-05',12,?,?,?,?,'PIX',?)
            """,optic,sale,customer,account,lens,frame,
                new java.math.BigDecimal(gross),new java.math.BigDecimal(fee),
                new java.math.BigDecimal(gross),new java.math.BigDecimal(net),created);
        jdbc.update("""
            INSERT INTO finance_entries(optic_id,id,direction,category,description,
                gross_amount,fee_amount,net_amount,occurred_on,due_on,settled_on,
                origin,sale_id,created_by,created_at)
            VALUES(?,?,'INCOME','Venda','Óculos',?,?,?,'2026-11-05','2026-11-10',?,
                'SALE',?,?,?)
            """,optic,UUID.randomUUID(),new java.math.BigDecimal(gross),
                new java.math.BigDecimal(fee),new java.math.BigDecimal(net),
                settled?java.sql.Date.valueOf("2026-11-05"):null,sale,account,created);
    }

    @Test
    void geraOsMesmos14IndicadoresNosTresFormatos() throws Exception {
        Sample a=setup();
        String url="/api/v1/reports/monthly-sales-summary?year=2026&month=11&asOf=2026-11-09";
        var response=mvc.perform(get(url).with(user(a.email())))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode root=mapper.readTree(response.getContentAsString());
        assertThat(root.path("indicators").size()).isEqualTo(14);
        JsonNode s=root.path("summary");
        assertThat(s.path("netReceived").decimalValue()).isEqualByComparingTo("950.00");
        assertThat(s.path("toReceive").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(s.path("totalSales").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(s.path("bankFees").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(s.path("salesCount").asInt()).isEqualTo(2);
        assertThat(s.path("reconciliationDifference").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(s.path("workedDays").asInt()).isEqualTo(6); // 2/11 feriado, sábado aberto
        assertThat(s.path("totalOpenDays").asInt()).isEqualTo(23);
        assertThat(s.path("monthlyGoal").decimalValue()).isEqualByComparingTo("23000.00");
        assertThat(s.path("dailyGoal").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(s.path("actualDailySales").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(s.path("remainingDays").asInt()).isEqualTo(17);

        for(String ext:new String[]{"csv","xlsx","pdf"}) {
            var body=mvc.perform(get("/api/v1/reports/monthly-sales-summary."+ext+
                    "?year=2026&month=11&asOf=2026-11-09").with(user(a.email())))
                    .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(body.getContentAsByteArray()).hasSizeGreaterThan(200);
        }
    }

    @Test
    void outroTenantNaoConsegueLerMetaDoPrimeiro() throws Exception {
        Sample first=setup(),second=setup();
        mvc.perform(put("/api/v1/sales-goals/2026/11").with(user(first.email())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goalAmount\":\"125000.00\"}"))
                .andExpect(status().isOk());
        var other=mvc.perform(get(
                "/api/v1/reports/monthly-sales-summary?year=2026&month=11&asOf=2026-11-09")
                .with(user(second.email())))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(mapper.readTree(other.getContentAsString())
                .path("summary").path("monthlyGoal").decimalValue())
                .isEqualByComparingTo("23000.00");
    }
}
