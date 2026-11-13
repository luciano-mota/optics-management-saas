package br.com.lcmtech.optics.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "optics.email-verification-required=false")
@AutoConfigureMockMvc
@Transactional
class SignupAndInviteTermsIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private String signup(String email, boolean terms) {
        return """
            {"opticName":"Ótica Nova","fullName":"Dona da Ótica","email":"%s","password":"SenhaForte2026",
             "whatsapp":"98999990000","acceptedTerms":%s}""".formatted(email, terms);
    }

    @Test
    void cadastroExigeAceiteERegistraVersaoEAdministradorPrincipal() throws Exception {
        String email = "nova-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/v1/public/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(signup(email, false))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/public/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(signup(email, true))).andExpect(status().isOk());
        Map<String, Object> account = jdbc.queryForMap(
                "SELECT terms_version,terms_accepted_at FROM accounts WHERE email=?", email);
        assertThat(account.get("terms_version")).isEqualTo(SignupController.TERMS_VERSION);
        assertThat(account.get("terms_accepted_at")).isNotNull();
        Boolean primary = jdbc.queryForObject("SELECT m.primary_master FROM optic_memberships m "
                + "JOIN accounts a ON a.id=m.account_id WHERE a.email=?", Boolean.class, email);
        assertThat(primary).isTrue();
    }

    @Test
    void conviteSoEAceitoComOsTermos() throws Exception {
        UUID optic = UUID.randomUUID(), owner = UUID.randomUUID();
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica',?)", optic, optic.toString(), "x@example.test");
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name) VALUES(?,?,'x','Dona')", owner, owner + "@example.test");
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role,primary_master) VALUES(?,?,'MASTER',true)", optic, owner);
        String token = "convite-de-teste-" + UUID.randomUUID();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        String invited = "func-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO member_invitations(id,optic_id,invited_email,requested_role,token_hash,invited_by,expires_at) "
                + "VALUES(?,?,?,'SELLER',?,?,now()+interval '1 hour')", UUID.randomUUID(), optic, invited, hash, owner);
        String body = """
            {"token":"%s","fullName":"Vendedora","password":"SenhaForte2026","cpf":"52998224725",
             "phone":"(98) 99999-0000","birthDate":"1995-01-01","address":null,"acceptedTerms":%s}""";
        mvc.perform(post("/api/v1/public/accept-invite").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(token, false))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/public/accept-invite").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(token, true))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT terms_version FROM accounts WHERE email=?", String.class, invited))
                .isEqualTo(SignupController.TERMS_VERSION);
    }
}
