package br.com.lcmtech.optics.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite de envios por IP nos formulários públicos (sem login): pedido de visita pelo site, cadastro
 * de ótica, "esqueci a senha" e aceite de convite. Evita spam e enchimento do banco com anexos.
 * Em memória (um servidor). O IP real vem do proxy do Render (server.forward-headers-strategy).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PublicRateLimitFilter extends OncePerRequestFilter {
    record Rule(String name, java.util.regex.Pattern path, int limit, Duration window) {}

    static final List<Rule> RULES = List.of(
        new Rule("visit", java.util.regex.Pattern.compile("^/api/v1/public/site/[^/]+/visits$"), 5, Duration.ofHours(1)),
        new Rule("signup", java.util.regex.Pattern.compile("^/api/v1/public/signup$"), 5, Duration.ofHours(1)),
        new Rule("forgot", java.util.regex.Pattern.compile("^/api/v1/public/password/forgot$"), 5, Duration.ofMinutes(15)),
        new Rule("invite", java.util.regex.Pattern.compile("^/api/v1/public/accept-invite$"), 10, Duration.ofMinutes(15)));
    private static final int MAX_KEYS = 50_000;

    private record Bucket(int count, long resetAt) {}
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public PublicRateLimitFilter() { this(Clock.systemUTC()); }
    PublicRateLimitFilter(Clock clock) { this.clock = clock; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || rule(request.getRequestURI()) == null;
    }

    private static Rule rule(String uri) {
        return RULES.stream().filter(r -> r.path().matcher(uri).matches()).findFirst().orElse(null);
    }

    /** true quando o pedido ainda cabe no limite (e já o contabiliza). */
    boolean allow(String ruleName, String address, int limit, Duration window) {
        long now = clock.millis();
        if (buckets.size() > MAX_KEYS) buckets.entrySet().removeIf(e -> e.getValue().resetAt() <= now);
        Bucket next = buckets.compute(ruleName + ":" + address, (key, current) ->
            current == null || current.resetAt() <= now ? new Bucket(1, now + window.toMillis())
                : new Bucket(current.count() + 1, current.resetAt()));
        return next.count() <= limit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = rule(request.getRequestURI());
        String address = request.getRemoteAddr() == null ? "desconhecido" : request.getRemoteAddr();
        if (!allow(rule.name(), address, rule.limit(), rule.window())) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(rule.window().toSeconds()));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Muitas tentativas seguidas. Aguarde alguns minutos e tente de novo.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
