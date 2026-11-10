package br.com.lcmtech.optics.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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
class BusinessCalendarIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private String setup() {
        UUID id=UUID.randomUUID(),optic=UUID.randomUUID();
        String email=id+"@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) "
                + "VALUES(?,?,'hash','Teste',now())",id,email);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,?,?)",
                optic,optic.toString(),"Ótica Teste",email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) "
                + "VALUES(?,?,'MASTER')",optic,id);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)",optic);
        jdbc.update("INSERT INTO optic_business_calendar(optic_id,state_uf,"
                +"municipality_ibge,municipality_name,open_weekdays) "
                +"VALUES(?,'MA','2105401','Itapecuru Mirim','1,2,3,4,5,6')",optic);
        return email;
    }

    @Test
    void consideraDiasDeAberturaMesmoSemVendasEExcluiFeriadoNacional() throws Exception {
        String email=setup();
        var response=mvc.perform(get("/api/v1/business-calendar/month")
                .param("year","2026").param("month","11").with(user(email)))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan=mapper.readTree(response.getResponse().getContentAsString());
        assertThat(plan.path("localHolidaysVerified").asBoolean()).isFalse();
        assertThat(plan.path("warnings").size()).isGreaterThan(0);
        assertThat(day(plan, "2026-11-02").path("willOpen").asBoolean()).isFalse();
        assertThat(day(plan, "2026-11-07").path("willOpen").asBoolean()).isTrue();
        assertThat(day(plan, "2026-11-20").path("willOpen").asBoolean()).isFalse();
        // Não precisamos de vendas nem lançamentos para determinar o dia aberto.
        assertThat(plan.path("totalOpenDays").asInt()).isGreaterThan(15);
    }

    @Test
    void masterPodeAbrirNoFeriadoPorExcecaoAuditada() throws Exception {
        String email=setup();
        mvc.perform(put("/api/v1/business-calendar/days/2026-11-20")
                .with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"willOpen\":true,\"title\":\"Expediente excepcional\"}"))
                .andExpect(status().isOk());
        var response=mvc.perform(get("/api/v1/business-calendar/month")
                .param("year","2026").param("month","11").with(user(email)))
                .andExpect(status().isOk()).andReturn();
        JsonNode plan=mapper.readTree(response.getResponse().getContentAsString());
        assertThat(day(plan,"2026-11-20").path("willOpen").asBoolean()).isTrue();
        assertThat(day(plan,"2026-11-20").path("overridden").asBoolean()).isTrue();
    }

    @Test
    void datasNacionaisDe2026ConferemComCalendarioOficial() {
        var dates=FederalHolidayCatalog.national(2026);
        assertThat(dates).hasSize(10);
        assertThat(dates.get(LocalDate.of(2026,4,3))).isEqualTo("Paixão de Cristo");
        assertThat(dates.get(LocalDate.of(2026,11,20)))
                .contains("Consciência Negra");
        assertThat(FederalHolidayCatalog.easter(2027)).isEqualTo(LocalDate.of(2027,3,28));
    }

    private static JsonNode day(JsonNode plan,String date) {
        for(JsonNode element:plan.path("days")) {
            if(date.equals(element.path("date").asText()))return element;
        }
        throw new AssertionError("Dia não encontrado: "+date);
    }

    @Autowired br.com.lcmtech.optics.shop.OpticClock clock;

    @Test
    void enderecoDaOticaDefineLocalizacaoEFusoSemCamposTecnicos() throws Exception {
        String email=setup();
        UUID optic=jdbc.queryForObject("SELECT m.optic_id FROM optic_memberships m "
                +"JOIN accounts a ON a.id=m.account_id WHERE a.email=?",UUID.class,email);
        mvc.perform(put("/api/v1/business-calendar/settings").with(user(email)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"openWeekdays\":[1,2,3,4,5]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/optic-profile/address").with(user(email)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"address":"Av. Paulista, 1000 - Bela Vista, São Paulo/SP - CEP 01310-100",
                     "city":"São Paulo","uf":"SP","ibge":"3550308"}
                    """))
                .andExpect(status().isOk());
        JsonNode settings=mapper.readTree(mvc.perform(get("/api/v1/business-calendar/settings")
                .with(user(email))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(settings.path("state_uf").asText()).isEqualTo("SP");
        assertThat(settings.path("municipality_name").asText()).isEqualTo("São Paulo");
        assertThat(settings.path("address").asText()).contains("Av. Paulista");
        // Os dias de abertura escolhidos antes não se perdem ao mudar o endereço.
        assertThat(settings.path("openWeekdays").toString()).isEqualTo("[1,2,3,4,5]");
        assertThat(clock.zone(optic).getId()).isEqualTo("America/Sao_Paulo");
        // Cidade de outra UF é recusada.
        mvc.perform(put("/api/v1/optic-profile/address").with(user(email)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"address":"Rua X, 1","city":"São Luís","uf":"MA","ibge":"3550308"}
                    """))
                .andExpect(status().isBadRequest());
    }
}
