package br.com.lcmtech.optics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Todo erro da API sai como {"error": "mensagem em português"}, sem detalhes internos do Java. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiErrorFormatIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String error(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("error").asText();
    }

    private String master() {
        UUID optic = UUID.randomUUID(), account = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,'hash','Dona',now())",
                account, account + "@example.test");
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica',?)", optic, optic.toString(),
                account + "@example.test");
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        return account + "@example.test";
    }

    @Test
    void semLoginLoginErradoECsrfAusenteRespondemComMensagem() throws Exception {
        MvcResult anonymous = mvc.perform(get("/api/v1/customers")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        assertThat(error(anonymous)).isEqualTo("Entre na sua conta para continuar.");

        MvcResult wrong = mvc.perform(post("/api/v1/auth/login").with(csrf()).param("email", "ninguem@example.test")
                .param("password", "SenhaErrada2026x")).andReturn();
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(error(wrong)).isEqualTo("E-mail ou senha incorretos.");

        MvcResult noCsrf = mvc.perform(post("/api/v1/customers").with(user(master())).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn();
        assertThat(noCsrf.getResponse().getStatus()).isEqualTo(403);
        assertThat(error(noCsrf)).contains("Recarregue");
    }

    @Test
    void telasTemPoliticaDeConteudoEApiNaoFicaEmCache() throws Exception {
        MvcResult page = mvc.perform(get("/")).andReturn();
        assertThat(page.getResponse().getHeader("Content-Security-Policy"))
                .contains("script-src 'self'", "frame-ancestors 'none'", "object-src 'none'");
        MvcResult api = mvc.perform(get("/api/v1/public/status")).andReturn();
        assertThat(api.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(api.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void corpoMalformadoOuIdInvalidoNaoMostramNomesInternos() throws Exception {
        String who = master();
        MvcResult broken = mvc.perform(post("/api/v1/customers").with(user(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\": ")).andReturn();
        assertThat(broken.getResponse().getStatus()).isEqualTo(400);
        assertThat(error(broken)).startsWith("Dados em formato inválido");
        MvcResult wrongType = mvc.perform(put("/api/v1/site-settings").with(user(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"services\":\"texto\"}")).andReturn();
        assertThat(wrongType.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("br.com.lcmtech", "Cannot construct");
        MvcResult badId = mvc.perform(get("/api/v1/finance/nao-e-uuid/settlements").with(user(who))).andReturn();
        assertThat(badId.getResponse().getStatus()).isEqualTo(400);
        assertThat(error(badId)).startsWith("Dados em formato inválido");
    }
}
