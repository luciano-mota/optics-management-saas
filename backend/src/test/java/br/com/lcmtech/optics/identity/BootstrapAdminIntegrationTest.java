package br.com.lcmtech.optics.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class BootstrapAdminIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test
    void provisionaOperadorUnicoSemOticaENaoRedefineSenhaEmReinicializacao() {
        // O teste remove operadores somente na transação de teste (rollback automático).
        jdbc.update("DELETE FROM platform_operators");
        String email="bootstrap-"+UUID.randomUUID()+"@example.test";
        var bootstrap=new BootstrapAdmin(jdbc,encoder,email,"SenhaDeBootstrap2026Forte!");
        bootstrap.run();
        Integer count=jdbc.queryForObject("SELECT count(*) FROM platform_operators",Integer.class);
        assertThat(count).isEqualTo(1);
        String hash=jdbc.queryForObject(
            "SELECT password_hash FROM accounts WHERE email=?",String.class,email);
        assertThat(encoder.matches("SenhaDeBootstrap2026Forte!",hash)).isTrue();
        Integer optics=jdbc.queryForObject(
            "SELECT count(*) FROM optic_memberships m JOIN accounts a ON a.id=m.account_id "
            +"WHERE a.email=?",Integer.class,email);
        assertThat(optics).isZero();
        new BootstrapAdmin(jdbc,encoder,email,"OutraSenhaQueNaoPodeTrocar2026!").run();
        assertThat(jdbc.queryForObject(
            "SELECT password_hash FROM accounts WHERE email=?",String.class,email)).isEqualTo(hash);
    }
}
