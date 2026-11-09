package br.com.lcmtech.optics.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Defesa em profundidade: marca as respostas da API com {@code Cache-Control: no-store}, para que
 * dados financeiros e pessoais não fiquem guardados em proxies nem no cache do navegador.
 */
@Component
public class PersonalDataCacheProtectionFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            response.setHeader("Cache-Control", "no-store, no-cache, max-age=0, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }
        chain.doFilter(request, response);
    }
}
