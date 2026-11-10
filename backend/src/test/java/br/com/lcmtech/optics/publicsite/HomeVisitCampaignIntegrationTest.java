package br.com.lcmtech.optics.publicsite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Atendimento em domicílio é uma campanha que a própria ótica liga no editor do site. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HomeVisitCampaignIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private String settings(boolean homeVisits) {
        return """
            {"primaryColor":"#5b4cf0","secondaryColor":"#f6f6fa","font":"Manrope","template":"modern",
             "campaignTitle":"Bem-vindo","campaignDescription":"Texto",
             "homeVisitsEnabled":%s,"homeVisitTitle":"Vamos até você","homeVisitDescription":"Envie a receita"}
            """.formatted(homeVisits);
    }

    @Test
    void aPropriaOticaLigaACampanhaNoSiteEOPedidoChegaParaAEquipe() throws Exception {
        UUID optic = UUID.randomUUID(), master = UUID.randomUUID();
        String slug = "otica-" + optic.toString().substring(0, 8), email = master + "@example.test";
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email,site_enabled) VALUES(?,?,'Ótica Exemplo',?,true)",
                optic, slug, email);
        jdbc.update("INSERT INTO site_settings(optic_id) VALUES(?)", optic);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'x','Dona',now())",
                master, email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role,primary_master) VALUES(?,?,'MASTER',true)",
                optic, master);

        var site = mapper.readTree(mvc.perform(get("/api/v1/public/site/" + slug))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(site.path("home_visits_enabled").asBoolean()).isFalse();

        String pdf = Base64.getEncoder().encodeToString("%PDF-1.4 receita".getBytes());
        String request = "{\"name\":\"Cliente\",\"phone\":\"(98) 99999-0000\",\"address\":\"Rua Central, 1 - São Luís/MA\","
                + "\"appointmentAt\":\"" + Instant.now().plus(3, ChronoUnit.DAYS) + "\",\"privacyAccepted\":true,"
                + "\"prescriptionMime\":\"application/pdf\",\"prescriptionName\":\"receita.pdf\","
                + "\"prescriptionBase64\":\"" + pdf + "\"}";
        mvc.perform(post("/api/v1/public/site/" + slug + "/visits").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isNotFound());

        // A ótica liga a campanha no próprio editor do site, sem depender da LCM DEV TECH.
        mvc.perform(put("/api/v1/site-settings").with(user(email)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(settings(true))).andExpect(status().isOk());
        site = mapper.readTree(mvc.perform(get("/api/v1/public/site/" + slug))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(site.path("home_visits_enabled").asBoolean()).isTrue();
        assertThat(site.path("home_visit_title").asText()).isEqualTo("Vamos até você");

        mvc.perform(post("/api/v1/public/site/" + slug + "/visits").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/visits").with(user(email))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM home_visits WHERE optic_id=?", Integer.class, optic))
                .isEqualTo(1);

        // Desligada de novo: o site para de aceitar pedidos.
        mvc.perform(put("/api/v1/site-settings").with(user(email)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(settings(false))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/site/" + slug + "/visits").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isNotFound());
    }
}
