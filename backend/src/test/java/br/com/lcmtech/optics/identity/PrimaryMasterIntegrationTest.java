package br.com.lcmtech.optics.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PrimaryMasterIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private record People(UUID optic, String primary, UUID primaryId, String second, UUID secondId,
                          String seller, UUID sellerId) {}

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at,profile_completed_at) "
                + "VALUES(?,?,'hash',?,now(),now())", id, email, email);
        return id;
    }

    private People setup() {
        UUID optic = UUID.randomUUID();
        String tag = optic.toString().substring(0, 8);
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email,tax_document,address) "
                + "VALUES(?,?,?,?,'11222333000181','Rua A, 1 - São Luís/MA')", optic, optic.toString(), "Ótica", tag + "@x.test");
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        String primary = "principal-" + tag + "@x.test", second = "segundo-" + tag + "@x.test",
                seller = "vendedor-" + tag + "@x.test";
        UUID p = account(primary), s = account(second), v = account(seller);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role,primary_master) VALUES(?,?,'MASTER',true)", optic, p);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')", optic, s);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'SELLER')", optic, v);
        return new People(optic, primary, p, second, s, seller, v);
    }

    private int changeRole(String actor, UUID target, String role, boolean active) throws Exception {
        return mvc.perform(patch("/api/v1/team/" + target).with(user(actor)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\",\"active\":" + active + "}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void principalNuncaEAlteradoESoEleGerenciaOutrosAdministradores() throws Exception {
        People team = setup();
        // O Master secundário não remove o principal nem outro administrador.
        assertThat(changeRole(team.second(), team.primaryId(), "SELLER", true)).isEqualTo(400);
        assertThat(changeRole(team.second(), team.primaryId(), "MASTER", false)).isEqualTo(400);
        // Ninguém altera o próprio acesso.
        assertThat(changeRole(team.second(), team.secondId(), "SELLER", true)).isEqualTo(400);
        // O secundário gerencia vendedores, mas não os promove a administrador.
        assertThat(changeRole(team.second(), team.sellerId(), "MANAGER", true)).isEqualTo(200);
        assertThat(changeRole(team.second(), team.sellerId(), "MASTER", true)).isEqualTo(400);
        // O principal revoga o administrador secundário.
        assertThat(changeRole(team.primary(), team.secondId(), "MASTER", false)).isEqualTo(200);
        Boolean active = jdbc.queryForObject("SELECT active FROM optic_memberships WHERE optic_id=? AND account_id=?",
                Boolean.class, team.optic(), team.secondId());
        assertThat(active).isFalse();
    }

    @Test
    void administradorNaoTemAcessosIndividuais() throws Exception {
        People team = setup();
        mvc.perform(put("/api/v1/team/" + team.secondId() + "/permission").with(user(team.primary())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"permission\":\"FINANCE_READ\",\"effect\":\"DENY\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/team/" + team.sellerId() + "/permission").with(user(team.primary())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"permission\":\"FINANCE_READ\",\"effect\":\"ALLOW\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void listaInformaQuemPodeSerGerenciado() throws Exception {
        People team = setup();
        JsonNode rows = json.readTree(mvc.perform(get("/api/v1/team").with(user(team.second())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(rows.get(0).path("primary_master").asBoolean()).isTrue();
        for (JsonNode row : rows) {
            String email = row.path("email").asText();
            boolean manage = row.path("can_manage").asBoolean();
            if (email.equals(team.seller())) assertThat(manage).isTrue();
            else assertThat(manage).as(email).isFalse();
            assertThat(row.path("self").asBoolean()).isEqualTo(email.equals(team.second()));
        }
    }

    @Test
    void conviteGeraLinkMesmoSemEmailConfigurado() throws Exception {
        People team = setup();
        var body = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/team/invites").with(user(team.primary())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nova@example.test\",\"role\":\"SELLER\",\"permissions\":[\"SALES_READ\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("inviteUrl").asText()).contains("?invite=");
        assertThat(body.path("emailSent").asBoolean()).isFalse();
    }
}
