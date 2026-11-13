package br.com.lcmtech.optics.identity.mfa;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Depois da senha certa, a sessão de quem usa verificação em duas etapas fica pendente: nenhuma API privada
 * responde até o código ser confirmado. Ficam liberados só o próprio passo do código, a saída e as rotas públicas.
 */
public final class MfaGateFilter extends OncePerRequestFilter {
    public static final String PENDING = "mfaPendingAccount";
    private static final List<String> ALLOWED = List.of("/api/v1/auth/csrf", "/api/v1/auth/login",
            "/api/v1/auth/logout", "/api/v1/auth/mfa/challenge", "/api/v1/auth/mfa/verify");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.startsWith("/api/v1/public/")
                || path.startsWith("/api/v1/webhooks/") || ALLOWED.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(PENDING) != null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Confirme o código de verificação para continuar.\","
                    + "\"mfaRequired\":true}");
            return;
        }
        chain.doFilter(request, response);
    }
}
