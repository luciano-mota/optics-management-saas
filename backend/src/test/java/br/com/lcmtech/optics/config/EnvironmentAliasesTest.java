package br.com.lcmtech.optics.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentAliasesTest {

    private static Map<String, Object> resolve(Map<String, String> env) {
        return EnvironmentAliases.resolve(env::get);
    }

    @Test
    void aceitaOPadraoDoPhysioEvo() {
        Map<String, Object> props = resolve(Map.of(
                "PLATFORM_ADMIN_EMAIL", "dono@lcm.test", "PLATFORM_ADMIN_PASSWORD", "SenhaForte2026",
                "BREVO_API_KEY", "chave", "BREVO_SENDER_EMAIL", "nao-responda@lcm.test",
                "SESSION_COOKIE_SECURE", "true", "SIGNUP_EMAIL_VERIFICATION_REQUIRED", "false"));
        assertThat(props).containsEntry("optics.bootstrap.email", "dono@lcm.test")
                .containsEntry("optics.bootstrap.password", "SenhaForte2026")
                .containsEntry("optics.mail.brevo-api-key", "chave")
                .containsEntry("optics.mail.from", "nao-responda@lcm.test")
                .containsEntry("server.servlet.session.cookie.secure", "true")
                .containsEntry("optics.email-verification-required", "false");
    }

    @Test
    void nomeDoOpticsTemPrioridadeENomesVaziosSaoIgnorados() {
        Map<String, Object> props = resolve(Map.of(
                "BOOTSTRAP_ADMIN_EMAIL", "optics@lcm.test", "PLATFORM_ADMIN_EMAIL", "physio@lcm.test",
                "BOOTSTRAP_ADMIN_PASSWORD", "  ", "PLATFORM_ADMIN_PASSWORD", "SenhaForte2026"));
        assertThat(props).containsEntry("optics.bootstrap.email", "optics@lcm.test")
                .containsEntry("optics.bootstrap.password", "SenhaForte2026");
    }

    @Test
    void converteDatabaseUrlDoRender() {
        Map<String, Object> props = resolve(Map.of(
                "DATABASE_URL", "postgresql://usuario:s%40nha@dpg-abc-a.oregon-postgres.render.com:5432/optics_db"));
        assertThat(props).containsEntry("spring.datasource.url",
                        "jdbc:postgresql://dpg-abc-a.oregon-postgres.render.com:5432/optics_db")
                .containsEntry("spring.datasource.username", "usuario")
                .containsEntry("spring.datasource.password", "s@nha");
    }

    @Test
    void dbUrlEUsuarioExplicitoTemPrioridade() {
        Map<String, Object> props = resolve(Map.of(
                "DB_URL", "jdbc:postgresql://localhost:5432/optics",
                "DATABASE_URL", "postgres://outro:x@host/outro", "DB_USERNAME", "optics"));
        assertThat(props).containsEntry("spring.datasource.url", "jdbc:postgresql://localhost:5432/optics")
                .containsEntry("spring.datasource.username", "optics");
    }

    @Test
    void semVariaveisNaoAlteraNada() {
        assertThat(resolve(Map.of())).isEmpty();
    }
}
