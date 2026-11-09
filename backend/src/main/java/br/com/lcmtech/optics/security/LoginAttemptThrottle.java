package br.com.lcmtech.optics.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite de tentativas de login por conta e por IP, em memória (vale para um único servidor). Não
 * substitui WAF nem MFA. Nunca guarda senha, corpo da requisição ou e-mail em claro: as chaves são
 * hashes.
 */
public final class LoginAttemptThrottle {
    static final int ACCOUNT_LIMIT = 12;
    /** Por IP: baixo o bastante para travar quem troca de e-mail a cada tentativa. */
    static final int ADDRESS_LIMIT = 30;

    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_KEYS = 30000;

    private record Bucket(int attempts, long expiresAt) {}

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptThrottle() {
        this(Clock.systemUTC());
    }

    LoginAttemptThrottle(Clock clock) {
        this.clock = clock;
    }

    private static String emailKey(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 255) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "account:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private static String addressKey(HttpServletRequest request) {
        // Do not trust a caller-supplied X-Forwarded-For header directly.
        String address = request.getRemoteAddr();
        return "addr:" + (address == null ? "unknown" : address);
    }

    private long remaining(String key, int limit, long now) {
        if (key == null) return 0;
        Bucket bucket = buckets.get(key);
        return bucket == null || bucket.expiresAt() <= now || bucket.attempts() < limit
                ? 0
                : Math.max(1, (bucket.expiresAt() - now + 999) / 1000);
    }

    public long retryAfterSeconds(HttpServletRequest request) {
        long now = clock.millis();
        String email = emailKey(request.getParameter("email"));
        return Math.max(remaining(email, ACCOUNT_LIMIT, now), remaining(addressKey(request), ADDRESS_LIMIT, now));
    }

    private void recordFailure(String key, long now) {
        if (key == null) return;
        buckets.compute(key, (ignored, previous) -> {
            if (previous == null || previous.expiresAt() <= now) return new Bucket(1, now + WINDOW.toMillis());
            return new Bucket(Math.min(previous.attempts() + 1, 1000000), previous.expiresAt());
        });
    }

    /** Pedido sem e-mail ou senha (ex.: corpo JSON): conta só para o IP. */
    public void failedWithoutAccount(HttpServletRequest request) {
        recordFailure(addressKey(request), clock.millis());
    }

    public void failed(HttpServletRequest request) {
        long now = clock.millis();
        recordFailure(emailKey(request.getParameter("email")), now);
        recordFailure(addressKey(request), now);
        if (buckets.size() > MAX_TRACKED_KEYS) {
            buckets.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
            // Keep a hard upper bound under high-cardinality abuse.
            if (buckets.size() > MAX_TRACKED_KEYS) buckets.clear();
        }
    }

    public void succeeded(HttpServletRequest request) {
        String key = emailKey(request.getParameter("email"));
        if (key != null) buckets.remove(key);
    }
}
