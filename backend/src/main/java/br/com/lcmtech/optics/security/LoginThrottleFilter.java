package br.com.lcmtech.optics.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Recusa o login (HTTP 429) antes de calcular o BCrypt quando o limite de tentativas foi atingido,
 * poupando processamento durante ataques de força bruta.
 */
public final class LoginThrottleFilter extends OncePerRequestFilter {
    private final LoginAttemptThrottle throttle;

    public LoginThrottleFilter(LoginAttemptThrottle throttle) {
        this.throttle = throttle;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !(request.getContextPath() + "/api/v1/auth/login").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long seconds = throttle.retryAfterSeconds(request);
        if (seconds > 0) {
            response.setHeader("Retry-After", Long.toString(seconds));
            json(
                    response,
                    429,
                    "Muitas tentativas de login. Aguarde " + Math.max(1, (seconds + 59) / 60)
                            + " minuto(s) e tente de novo, ou use Esqueci minha senha.");
            return;
        }
        // O login é um formulário (e-mail e senha). Sem eles (ex.: JSON) nunca entraria: responde
        // claro e conta a tentativa para o IP, para não virar porta sem limite.
        String email = request.getParameter("email"), password = request.getParameter("password");
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            throttle.failedWithoutAccount(request);
            json(response, 400, "Informe e-mail e senha.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Resposta no padrão da API ({"error": "..."}); as mensagens daqui não têm aspas nem barras. */
    static void json(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
