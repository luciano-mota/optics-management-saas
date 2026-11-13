package br.com.lcmtech.optics.security;

import br.com.lcmtech.optics.identity.mfa.MfaGateFilter;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/** Sessão no servidor e CSRF obrigatório; APIs privadas autenticadas sem basic auth. */
@Configuration
public class SecurityConfig {
    @Bean LoginAttemptThrottle loginAttemptThrottle() { return new LoginAttemptThrottle(); }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserDetailsService users, LoginAttemptThrottle throttle,
                                            JdbcTemplate jdbc) throws Exception {
        return http
                .addFilterBefore(new LoginThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new MfaGateFilter(), AuthorizationFilter.class)
                .userDetailsService(users)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/v1/webhooks/asaas", "/api/v1/public/messages/unsubscribe/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health",
                                "/api/v1/public/**", "/api/v1/webhooks/asaas", "/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.loginProcessingUrl("/api/v1/auth/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler((req, resp, authentication) -> {
                            throttle.succeeded(req);
                            // Com verificação em duas etapas, a sessão fica pendente até o código (MfaGateFilter).
                            List<UUID> pending = jdbc.query("SELECT id FROM accounts WHERE lower(email)=lower(?) "
                                    + "AND mfa_enabled_at IS NOT NULL", (rs, n) -> (UUID) rs.getObject(1),
                                    authentication.getName());
                            if (!pending.isEmpty())
                                req.getSession().setAttribute(MfaGateFilter.PENDING, pending.get(0).toString());
                            resp.setStatus(200);
                            resp.setContentType("application/json;charset=UTF-8");
                            resp.getWriter().write(pending.isEmpty() ? "{\"authenticated\":true}"
                                    : "{\"authenticated\":true,\"mfaRequired\":true}");
                        })
                        .failureHandler((req, resp, error) -> {
                            throttle.failed(req);
                            resp.sendError(401);
                        }))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "SESSION", "XSRF-TOKEN")
                        .logoutSuccessHandler((req, resp, auth) -> resp.setStatus(204)))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
