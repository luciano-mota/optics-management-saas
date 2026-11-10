package br.com.lcmtech.optics.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Foto da ótica piloto de 09/11: fecha a conciliação e reproduz todos os 14 KPIs.
 * Os dias operados são calendário da loja (não dias com venda).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PilotExcelFixtureIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void reproduzIntegralmenteOsNumerosDoExcel() throws Exception {
        UUID optic=UUID.randomUUID(),account=UUID.randomUUID();
        UUID customer=UUID.randomUUID(),lens=UUID.randomUUID(),frame=UUID.randomUUID();
        String email=account+"@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) "
                +"VALUES(?,?,'hash','Master',now())",account,email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,?,?)",
                optic,optic.toString(),"Ótica da Foto",email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')",
                optic,account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)",optic);
        jdbc.update("""
            INSERT INTO optic_business_calendar(optic_id,state_uf,municipality_ibge,
                municipality_name,open_weekdays,time_zone)
            VALUES(?,'MA','2105401','Itapecuru Mirim','1,2,3,4,5,6','America/Fortaleza')
            """,optic);
        jdbc.update("""
            INSERT INTO optic_monthly_goals(optic_id,calendar_year,calendar_month,
                goal_amount,updated_by) VALUES(?,2026,11,120000,?)
            """,optic,account);
        jdbc.update("""
            INSERT INTO optic_month_reviews(optic_id,calendar_year,calendar_month,reviewed_by)
            VALUES(?,2026,11,?)
            """,optic,account);
        // 25 dias de segunda a sábado, 2/11 e 20/11 fechados por feriado:
        // 23 planejados, menos 09/11, mais expediente excepcional em 20/11
        // e em 29/11 (domingo) = 24; até 09/11 já funcionaram 5 datas.
        for(var date:new String[]{"2026-11-09","2026-11-20","2026-11-29"}) {
            boolean open=!date.equals("2026-11-09");
            jdbc.update("""
                INSERT INTO optic_day_overrides(optic_id,day,is_open,title,updated_by)
                VALUES(?,?,?,?,?)
                """,optic,Date.valueOf(date),open,"Escala da ótica piloto",account);
        }
        jdbc.update("INSERT INTO customers(optic_id,id,full_name) VALUES(?,?,'Cliente')",
                optic,customer);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'LENS','Teste')",
                optic,lens);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'FRAME','Teste')",
                optic,frame);
        for(int n=0;n<29;n++){
            BigDecimal gross=n<27?new BigDecimal("600.00"):
                    n==27?new BigDecimal("1715.00"):new BigDecimal("1400.00");
            BigDecimal fee=n==27?new BigDecimal("797.94"):new BigDecimal("0.00");
            BigDecimal net=gross.subtract(fee);
            boolean received=n<28;
            UUID sale=UUID.randomUUID();
            Timestamp created=Timestamp.from(Instant.parse("2026-11-05T12:00:00Z"));
            jdbc.update("""
                INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,
                    sold_on,replacement_months,gross_amount,fee_amount,charged_amount,
                    net_amount,payment_method,created_at)
                VALUES(?,?,?,?,?,?,'2026-11-05',12,?,?,?,?,'CREDITO',?)
                """,optic,sale,customer,account,lens,frame,gross,fee,gross,net,created);
            jdbc.update("""
                INSERT INTO finance_entries(optic_id,id,direction,category,description,
                    gross_amount,fee_amount,net_amount,occurred_on,due_on,settled_on,
                    origin,sale_id,created_by,created_at)
                VALUES(?,?,'INCOME','Venda','Venda de óculos',?,?,?,
                    '2026-11-05','2026-11-09',?,'SALE',?,?,?)
                """,optic,UUID.randomUUID(),gross,fee,net,
                    received?Date.valueOf("2026-11-05"):null,sale,account,created);
        }
        JsonNode root=mapper.readTree(mvc.perform(get(
                "/api/v1/reports/monthly-sales-summary?year=2026&month=11&asOf=2026-11-09")
                .with(user(email))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        JsonNode s=root.path("summary");
        assertThat(root.path("indicators").size()).isEqualTo(14);
        assertThat(s.path("netReceived").decimalValue()).isEqualByComparingTo("17117.06");
        assertThat(s.path("toReceive").decimalValue()).isEqualByComparingTo("1400.00");
        assertThat(s.path("totalSales").decimalValue()).isEqualByComparingTo("19315.00");
        assertThat(s.path("monthlyGoal").decimalValue()).isEqualByComparingTo("120000.00");
        assertThat(s.path("dailyGoal").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(s.path("monthlyGoalRemaining").decimalValue()).isEqualByComparingTo("100685.00");
        assertThat(s.path("actualDailySales").decimalValue()).isEqualByComparingTo("3863.00");
        assertThat(s.path("totalOpenDays").asInt()).isEqualTo(24);
        assertThat(s.path("workedDays").asInt()).isEqualTo(5);
        assertThat(s.path("remainingDays").asInt()).isEqualTo(19);
        assertThat(s.path("salesCount").asInt()).isEqualTo(29);
        assertThat(s.path("requiredDailyGoal").decimalValue()).isEqualByComparingTo("5299.21");
        assertThat(s.path("glassesAndLensesGross").decimalValue()).isEqualByComparingTo("19315.00");
        assertThat(s.path("bankFees").decimalValue()).isEqualByComparingTo("797.94");
        assertThat(s.path("reconciliationDifference").decimalValue()).isEqualByComparingTo("0.00");
    }
}
