package br.com.lcmtech.optics.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
class ProfileCompletionIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String incompleteUser() {
        UUID account=UUID.randomUUID();
        String email=account+"@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) "
                +"VALUES(?,?,'hash','Usuário Novo',now())",account,email);
        return email;
    }

    @Test
    void exigeDadosEPermitePrimeiroAcessoComCadastroCompleto() throws Exception {
        String email=incompleteUser();
        String before=mvc.perform(get("/api/v1/auth/me").with(user(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(before).path("profileComplete").asBoolean()).isFalse();

        String invalid="""
             {"fullName":"Usuário Novo","cpf":"00000000000","phone":"(98) 99999-1234",
              "birthDate":"1995-01-01","address":null}
             """;
        mvc.perform(put("/api/v1/auth/profile").with(user(email)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(invalid))
            .andExpect(status().isBadRequest());

        String valid="""
             {"fullName":"Usuário Novo","cpf":"52998224725","phone":"98999991234",
              "birthDate":"1995-01-01","address":"Rua Teste"}
             """;
        mvc.perform(put("/api/v1/auth/profile").with(user(email)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isOk());
        JsonNode after=json.readTree(mvc.perform(get("/api/v1/auth/me").with(user(email)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(after.path("profileComplete").asBoolean()).isTrue();
        assertThat(after.path("account").path("birth_date").asText()).isEqualTo("1995-01-01");
        assertThat(after.path("account").path("cpf").asText()).isEqualTo("52998224725");
    }

    @Test
    void masterPrecisaDoCnpjDaOticaParaConcluirOPrimeiroAcesso() throws Exception {
        String email=incompleteUser();
        UUID account=jdbc.queryForObject("SELECT id FROM accounts WHERE email=?",UUID.class,email);
        UUID optic=UUID.randomUUID();
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,?,?)",
                optic,optic.toString(),"Ótica Nova",email);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) "
                +"VALUES(?,?,'MASTER')",optic,account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)",optic);
        String initial=mvc.perform(get("/api/v1/auth/me").with(user(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(initial).path("opticRegistrationRequired").asBoolean()).isTrue();

        String emptyCnpj="""
             {"fullName":"Usuário Novo","cpf":"52998224725","phone":"98999991234",
              "birthDate":"1995-01-01","address":null}
             """;
        mvc.perform(put("/api/v1/auth/profile").with(user(email)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(emptyCnpj))
            .andExpect(status().isBadRequest());

        String validCnpj="""
             {"fullName":"Usuário Novo","cpf":"52998224725","phone":"98999991234",
              "birthDate":"1995-01-01","address":null,"opticCnpj":"11.222.333/0001-81"}
             """;
        mvc.perform(put("/api/v1/auth/profile").with(user(email)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(validCnpj))
            .andExpect(status().isOk());
        String saved=jdbc.queryForObject("SELECT tax_document FROM optics WHERE id=?",
                String.class,optic);
        assertThat(saved).isEqualTo("11222333000181");
        String after=mvc.perform(get("/api/v1/auth/me").with(user(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(after).path("opticRegistrationRequired").asBoolean()).isFalse();
        assertThat(json.readTree(after).path("profileComplete").asBoolean()).isTrue();
    }

}
