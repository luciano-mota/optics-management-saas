package br.com.lcmtech.optics.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Aceita os nomes de variáveis usados no Optics, no PhysioEvo e no Ecclesia Finance, para que o
 * mesmo padrão do Render funcione nos três sistemas. Para cada configuração, vale o primeiro nome
 * preenchido da lista (o nome próprio do Optics vem primeiro). Nenhum valor é registrado em log.
 */
public class EnvironmentAliases implements EnvironmentPostProcessor, Ordered {
    static final String SOURCE = "optics-environment-aliases";

    /** propriedade → variáveis aceitas, em ordem de prioridade. */
    static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();
    static {
        ALIASES.put("optics.bootstrap.email", List.of(
                "BOOTSTRAP_ADMIN_EMAIL", "PLATFORM_ADMIN_EMAIL", "OPTICS_OPERATOR_EMAIL", "ADMIN_EMAIL"));
        ALIASES.put("optics.bootstrap.password", List.of(
                "BOOTSTRAP_ADMIN_PASSWORD", "PLATFORM_ADMIN_PASSWORD", "OPTICS_OPERATOR_PASSWORD", "ADMIN_PASSWORD"));
        ALIASES.put("spring.datasource.username", List.of("DB_USERNAME", "DATABASE_USER", "DATABASE_USERNAME"));
        ALIASES.put("spring.datasource.password", List.of("DB_PASSWORD", "DATABASE_PASSWORD"));
        ALIASES.put("server.servlet.session.cookie.secure", List.of("COOKIE_SECURE", "SESSION_COOKIE_SECURE"));
        ALIASES.put("optics.public-base-url", List.of("OPTICS_PUBLIC_BASE_URL", "APP_PUBLIC_URL", "OPTICS_BASE_URL"));
        ALIASES.put("optics.signup.enabled", List.of("OPTICS_SIGNUP_ENABLED", "SIGNUP_ENABLED"));
        ALIASES.put("optics.email-verification-required", List.of(
                "OPTICS_EMAIL_VERIFICATION_REQUIRED", "EMAIL_VERIFICATION_REQUIRED", "SIGNUP_EMAIL_VERIFICATION_REQUIRED"));
        ALIASES.put("optics.mail.from", List.of("OPTICS_MAIL_FROM", "MAIL_FROM", "BREVO_SENDER_EMAIL"));
        ALIASES.put("optics.mail.from-name", List.of("OPTICS_MAIL_FROM_NAME", "MAIL_FROM_NAME"));
        ALIASES.put("optics.mail.brevo-api-key", List.of("OPTICS_BREVO_API_KEY", "BREVO_API_KEY"));
        ALIASES.put("optics.contact-email", List.of("OPTICS_CONTACT_EMAIL", "PLATFORM_CONTACT_EMAIL"));
        ALIASES.put("spring.mail.host", List.of("SPRING_MAIL_HOST", "MAIL_HOST", "SMTP_HOST"));
        ALIASES.put("spring.mail.port", List.of("SPRING_MAIL_PORT", "MAIL_PORT", "SMTP_PORT"));
        ALIASES.put("spring.mail.username", List.of("SPRING_MAIL_USERNAME", "MAIL_USERNAME", "SMTP_USERNAME"));
        ALIASES.put("spring.mail.password", List.of("SPRING_MAIL_PASSWORD", "MAIL_PASSWORD", "SMTP_PASSWORD"));
        ALIASES.put("optics.asaas.api-key", List.of("OPTICS_ASAAS_API_KEY", "ASAAS_API_KEY"));
        ALIASES.put("optics.asaas.webhook-token", List.of("OPTICS_ASAAS_WEBHOOK_TOKEN", "ASAAS_WEBHOOK_TOKEN"));
        ALIASES.put("optics.asaas.base-url", List.of("OPTICS_ASAAS_BASE_URL", "ASAAS_BASE_URL"));
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> resolved = resolve(environment::getProperty);
        if (!resolved.isEmpty()) environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, resolved));
    }

    /** Separado para testes: recebe a função que lê uma variável. */
    static Map<String, Object> resolve(java.util.function.Function<String, String> env) {
        Map<String, Object> result = new LinkedHashMap<>();
        ALIASES.forEach((property, names) -> names.stream()
                .map(env).filter(value -> value != null && !value.isBlank()).findFirst()
                .ifPresent(value -> result.put(property, value.trim())));
        // Banco: DB_URL (Optics) tem prioridade; DATABASE_URL (PhysioEvo/Ecclesia) também é aceito,
        // tanto no formato JDBC quanto no formato postgres://usuário:senha@host:porta/banco do Render.
        String dbUrl = firstFilled(env, "DB_URL", "JDBC_DATABASE_URL", "DATABASE_URL");
        if (dbUrl != null) datasource(dbUrl).forEach((key, value) -> {
            // Usuário e senha explícitos (DB_USERNAME/DATABASE_USER...) valem mais que os da URL.
            if (key.equals("spring.datasource.url")) result.put(key, value);
            else result.putIfAbsent(key, value);
        });
        return result;
    }

    static Map<String, Object> datasource(String url) {
        Map<String, Object> result = new LinkedHashMap<>();
        String value = url.trim();
        if (value.startsWith("jdbc:")) {
            result.put("spring.datasource.url", value);
            return result;
        }
        if (!value.startsWith("postgres://") && !value.startsWith("postgresql://")) return result;
        URI uri = URI.create(value.replaceFirst("^postgres(ql)?://", "http://"));
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        result.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + port + uri.getRawPath() + query);
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            String[] parts = userInfo.split(":", 2);
            result.putIfAbsent("spring.datasource.username", decode(parts[0]));
            if (parts.length > 1) result.putIfAbsent("spring.datasource.password", decode(parts[1]));
        }
        return result;
    }

    private static String firstFilled(java.util.function.Function<String, String> env, String... names) {
        for (String name : names) {
            String value = env.apply(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
