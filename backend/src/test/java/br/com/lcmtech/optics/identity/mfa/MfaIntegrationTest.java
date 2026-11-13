package br.com.lcmtech.optics.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MfaIntegrationTest {
    private static final String PASSWORD = "SenhaForteDeTeste2026";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;

    /** Sessão do navegador: o cookie muda quando o id da sessão é trocado. */
    private static final class Browser {
        Cookie session;
        void keep(MvcResult r) {
            Cookie next = r.getResponse().getCookie("SESSION");
            if (next != null) session = next;
        }
    }

    private String account(String role) {
        UUID optic = UUID.randomUUID(), id = UUID.randomUUID();
        String email = "mfa-" + id + "@example.test";
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at,profile_completed_at) "
                + "VALUES(?,?,?,'Pessoa',now(),now())", id, email, passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email) VALUES(?,?,'Ótica',?)",
                optic, optic.toString(), email);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)", optic);
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,?)", optic, id, role);
        return email;
    }

    private JsonNode login(Browser b, String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").with(csrf()).param("email", email).param("password", PASSWORD))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        b.keep(r);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult call(Browser b, String method, String path, String body) throws Exception {
        var request = "GET".equals(method) ? get(path) : post(path).with(csrf());
        if (b.session != null) request = request.cookie(b.session);
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult r = mvc.perform(request).andReturn();
        b.keep(r);
        return r;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String secret(String email) {
        return jdbc.queryForObject("SELECT mfa_secret FROM accounts WHERE email=?", String.class, email);
    }

    @Test
    void loginComDuasEtapasBloqueiaAteOCodigoESoAceitaCadaCodigoUmaVez() throws Exception {
        String email = account("MASTER");
        Browser first = new Browser();
        assertThat(login(first, email).has("mfaRequired")).isFalse();
        JsonNode status = body(call(first, "GET", "/api/v1/auth/mfa", null));
        assertThat(status.path("recommended").asBoolean()).isTrue();
        assertThat(status.path("enabled").asBoolean()).isFalse();

        JsonNode setup = body(call(first, "POST", "/api/v1/auth/mfa/setup", null));
        assertThat(setup.path("otpauthUri").asText()).startsWith("otpauth://totp/Optics%20Management:");
        assertThat(setup.path("qrCode").asText()).startsWith("data:image/svg+xml;base64,");
        assertThat(call(first, "POST", "/api/v1/auth/mfa/enable", "{\"code\":\"000000\"}").getResponse().getStatus())
                .isEqualTo(400);
        String code = Totp.code(secret(email), Totp.step(Instant.now()));
        JsonNode enabled = body(call(first, "POST", "/api/v1/auth/mfa/enable", "{\"code\":\"" + code + "\"}"));
        assertThat(enabled.path("recoveryCodes")).hasSize(10);
        String recovery = enabled.path("recoveryCodes").get(0).asText();

        Browser second = new Browser();
        assertThat(login(second, email).path("mfaRequired").asBoolean()).isTrue();
        MvcResult blocked = call(second, "GET", "/api/v1/auth/me", null);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(blocked).path("mfaRequired").asBoolean()).isTrue();
        assertThat(call(second, "GET", "/api/v1/customers", null).getResponse().getStatus()).isEqualTo(401);
        assertThat(body(call(second, "GET", "/api/v1/auth/mfa/challenge", null)).path("pending").asBoolean()).isTrue();

        // O mesmo código da ativação não vale de novo (replay).
        assertThat(call(second, "POST", "/api/v1/auth/mfa/verify", "{\"code\":\"" + code + "\"}").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(call(second, "POST", "/api/v1/auth/mfa/verify",
                "{\"code\":\"" + recovery.toLowerCase() + "\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(call(second, "GET", "/api/v1/auth/me", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(body(call(second, "GET", "/api/v1/auth/mfa", null)).path("recoveryRemaining").asInt()).isEqualTo(9);

        // Código de recuperação é de uso único.
        Browser third = new Browser();
        login(third, email);
        assertThat(call(third, "POST", "/api/v1/auth/mfa/verify", "{\"code\":\"" + recovery + "\"}")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void cincoCodigosErradosEncerramASessao() throws Exception {
        String email = account("MASTER");
        Browser b = new Browser();
        login(b, email);
        call(b, "POST", "/api/v1/auth/mfa/setup", null);
        call(b, "POST", "/api/v1/auth/mfa/enable",
                "{\"code\":\"" + Totp.code(secret(email), Totp.step(Instant.now())) + "\"}");
        Browser attacker = new Browser();
        login(attacker, email);
        for (int i = 1; i < MfaController.MAX_SESSION_FAILURES; i++)
            assertThat(call(attacker, "POST", "/api/v1/auth/mfa/verify", "{\"code\":\"111111\"}")
                    .getResponse().getStatus()).isEqualTo(400);
        assertThat(call(attacker, "POST", "/api/v1/auth/mfa/verify", "{\"code\":\"111111\"}")
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(call(attacker, "GET", "/api/v1/auth/me", null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void desativarExigeSenhaECodigoESoAfetaAPropriaConta() throws Exception {
        String email = account("MASTER"), other = account("SELLER");
        Browser b = new Browser();
        login(b, email);
        call(b, "POST", "/api/v1/auth/mfa/setup", null);
        JsonNode enabled = body(call(b, "POST", "/api/v1/auth/mfa/enable",
                "{\"code\":\"" + Totp.code(secret(email), Totp.step(Instant.now())) + "\"}"));
        String recovery = enabled.path("recoveryCodes").get(1).asText();

        // Outra conta (de outra ótica) segue entrando direto e não enxerga nada da primeira.
        Browser seller = new Browser();
        assertThat(login(seller, other).has("mfaRequired")).isFalse();
        JsonNode sellerStatus = body(call(seller, "GET", "/api/v1/auth/mfa", null));
        assertThat(sellerStatus.path("enabled").asBoolean()).isFalse();
        assertThat(sellerStatus.path("recommended").asBoolean()).isFalse();

        assertThat(call(b, "POST", "/api/v1/auth/mfa/disable",
                "{\"password\":\"errada\",\"code\":\"" + recovery + "\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(call(b, "POST", "/api/v1/auth/mfa/setup", null).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(b, "POST", "/api/v1/auth/mfa/disable",
                "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + recovery + "\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_mfa_recovery_codes r JOIN accounts a "
                + "ON a.id=r.account_id WHERE a.email=?", Integer.class, email)).isZero();
        assertThat(login(new Browser(), email).has("mfaRequired")).isFalse();
    }
}
