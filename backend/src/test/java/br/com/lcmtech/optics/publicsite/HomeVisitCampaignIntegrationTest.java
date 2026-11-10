package br.com.lcmtech.optics.publicsite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class HomeVisitCampaignIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void visitasSaoExclusivasDaOticaComLicencaIndividual() throws Exception {
        UUID optic=UUID.randomUUID();
        String slug="otica-"+optic.toString().substring(0,8);
        jdbc.update("""
            INSERT INTO optics(id,slug,trade_name,contact_email,site_enabled)
            VALUES(?,?,'Ótica Exemplo','contato@example.test',true)
            """,optic,slug);
        jdbc.update("INSERT INTO site_settings(optic_id) VALUES(?)",optic);
        var response=mvc.perform(get("/api/v1/public/site/"+slug))
                .andExpect(status().isOk()).andReturn();
        var publicSite=mapper.readTree(response.getResponse().getContentAsString());
        assertThat(publicSite.path("home_visits_enabled").asBoolean()).isFalse();
        assertThat(publicSite.path("campaign_title").asText())
                .isEqualTo("Bem-vindo à nossa ótica");

        String request="{\"name\":\"Cliente\",\"phone\":\"98999990000\","
                +"\"address\":\"Rua Central\",\"appointmentAt\":\"2027-01-01T14:00:00Z\","
                +"\"privacyAccepted\":true,\"prescriptionBase64\":\"YQ==\"}";
        mvc.perform(post("/api/v1/public/site/"+slug+"/visits")
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNotFound());

        jdbc.update("UPDATE optics SET home_visits_enabled=true WHERE id=?",optic);
        publicSite=mapper.readTree(mvc.perform(get("/api/v1/public/site/"+slug))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(publicSite.path("home_visits_enabled").asBoolean()).isTrue();
    }
}
