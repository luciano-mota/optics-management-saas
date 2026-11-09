package br.com.lcmtech.optics.security;

import org.springframework.context.annotation.Bean;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserDetailsService users, LoginAttemptThrottle throttle) throws Exception {
        return http
                .addFilterBefore(new LoginThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class)
                .userDetailsService(users)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/v1/webhooks/asaas"))
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
                            resp.setStatus(200);
                            resp.setContentType("application/json;charset=UTF-8");
                            resp.getWriter().write("{\"authenticated\":true}");
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
