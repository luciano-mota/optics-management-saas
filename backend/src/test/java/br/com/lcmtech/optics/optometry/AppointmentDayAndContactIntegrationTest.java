package br.com.lcmtech.optics.optometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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

/** "O dia" da agenda segue o fuso da ótica, e as listas trazem o telefone usado no botão de WhatsApp. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AppointmentDayAndContactIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode get(String who, String path) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .with(user(who))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void agendaDoDiaUsaOFusoDaOticaETelefonesVemNasListas() throws Exception {
        UUID optic = UUID.randomUUID(), account = UUID.randomUUID();
        String email = account + "@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Dona',now())",
                account, email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica Manaus',?)", optic, optic.toString(), email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        jdbc.update("INSERT INTO optic_business_calendar(optic_id,state_uf,municipality_ibge,municipality_name,open_weekdays,time_zone) "
                + "VALUES(?,'AM','1302603','Manaus','1,2,3,4,5,6','America/Manaus')", optic);
        UUID patient = UUID.randomUUID(), clinic = UUID.randomUUID();
        jdbc.update("INSERT INTO optometry_patients(optic_id,id,full_name,phone,created_by) VALUES(?,?,'Ana Paciente','92991112222',?)",
                optic, patient, account);
        jdbc.update("INSERT INTO optical_partner_clinics(optic_id,id,name) VALUES(?,?,'Clínica Rio Negro')", optic, clinic);
        // 20/11 03:30 UTC = 19/11 às 23:30 em Manaus (UTC-4); em Fortaleza já seria dia 20.
        Instant start = Instant.parse("2026-11-20T03:30:00Z");
        jdbc.update("INSERT INTO optical_appointments(optic_id,id,patient_id,clinic_id,starts_at,ends_at,created_by) VALUES(?,?,?,?,?,?,?)",
                optic, UUID.randomUUID(), patient, clinic, Timestamp.from(start), Timestamp.from(start.plusSeconds(1800)), account);
        JsonNode day19 = get(email, "/api/v1/optometry/appointments?day=2026-11-19");
        assertThat(day19).hasSize(1);
        assertThat(day19.get(0).path("patient_phone").asText()).isEqualTo("92991112222");
        assertThat(get(email, "/api/v1/optometry/appointments?day=2026-11-20")).isEmpty();

        UUID customer = UUID.randomUUID(), lens = UUID.randomUUID(), frame = UUID.randomUUID(), sale = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(optic_id,id,full_name,phone) VALUES(?,?,'Bruno Cliente','92988887777')", optic, customer);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'LENS','Zeiss')", optic, lens);
        jdbc.update("INSERT INTO catalog_items(optic_id,id,kind,brand) VALUES(?,?,'FRAME','Oakley')", optic, frame);
        jdbc.update("""
                INSERT INTO sales(optic_id,id,customer_id,seller_id,lens_id,frame_id,replacement_months,gross_amount,fee_amount,
                    charged_amount,net_amount,payment_method,status) VALUES(?,?,?,?,?,?,12,400,0,400,400,'DINHEIRO','READY_FOR_DELIVERY')
                """, optic, sale, customer, account, lens, frame);
        jdbc.update("""
                INSERT INTO finance_entries(optic_id,id,direction,category,description,gross_amount,net_amount,due_on,origin,sale_id,created_by)
                VALUES(?,?,'INCOME','Venda','Crediário',400,400,'2026-12-10','SALE',?,?)""", optic, UUID.randomUUID(), sale, account);
        jdbc.update("""
                INSERT INTO finance_entries(optic_id,id,direction,category,description,gross_amount,net_amount,created_by)
                VALUES(?,?,'EXPENSE','Aluguel','Aluguel',1000,1000,?)""", optic, UUID.randomUUID(), account);
        assertThat(get(email, "/api/v1/sales").get(0).path("customer_phone").asText()).isEqualTo("92988887777");
        JsonNode finance = get(email, "/api/v1/finance");
        for (JsonNode e : finance) {
            if (e.path("origin").asText().equals("SALE")) assertThat(e.path("customer_phone").asText()).isEqualTo("92988887777");
            else assertThat(e.path("customer_phone").isNull()).isTrue();
        }
    }
}
