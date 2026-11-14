package br.com.lcmtech.optics.identity.mfa;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ativação, conferência e desativação da verificação em duas etapas de uma conta. */
@Service
public class MfaService {
    static final String ISSUER = "Optics Management";
    static final int RECOVERY_CODES = 10;
    private static final int MAX_FAILURES = 10;
    private static final long FAILURE_WINDOW_SECONDS = 15 * 60;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;
    /** Erros por conta: bloqueia novas tentativas por 15 min após 10 erros, mesmo trocando de sessão. */
    private final Map<UUID, long[]> failures = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public MfaService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    MfaService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean enabled(UUID account) {
        Boolean on = jdbc.queryForObject("SELECT mfa_enabled_at IS NOT NULL FROM accounts WHERE id=?",
                Boolean.class, account);
        return Boolean.TRUE.equals(on);
    }

    public Map<String, Object> status(UUID account) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT a.mfa_enabled_at,
                       (SELECT count(*) FROM account_mfa_recovery_codes r WHERE r.account_id=a.id AND r.used_at IS NULL)
                         AS recovery_remaining,
                       (EXISTS (SELECT 1 FROM platform_operators p WHERE p.account_id=a.id AND p.active)
                        OR EXISTS (SELECT 1 FROM optic_memberships m WHERE m.account_id=a.id AND m.active
                                   AND m.role='MASTER')) AS recommended
                FROM accounts a WHERE a.id=?
                """, account);
        return Map.of("enabled", row.get("mfa_enabled_at") != null,
                "enabledAt", row.get("mfa_enabled_at") == null ? "" : row.get("mfa_enabled_at").toString(),
                "recoveryRemaining", ((Number) row.get("recovery_remaining")).intValue(),
                "recommended", Boolean.TRUE.equals(row.get("recommended")));
    }

    /** Gera um novo segredo (ainda inativo) e o QR Code para o aplicativo autenticador. */
    @Transactional
    public Map<String, String> startSetup(UUID account) {
        if (enabled(account))
            throw new IllegalArgumentException("A verificação em duas etapas já está ativa. Desative antes de trocar o aparelho.");
        String secret = Totp.newSecret();
        jdbc.update("UPDATE accounts SET mfa_secret=?, mfa_last_step=NULL WHERE id=?", secret, account);
        String email = jdbc.queryForObject("SELECT email FROM accounts WHERE id=?", String.class, account);
        String label = enc(ISSUER) + ":" + enc(email);
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(ISSUER)
                + "&algorithm=SHA1&digits=6&period=30";
        return Map.of("secret", group(secret), "otpauthUri", uri, "qrCode", qrSvgDataUri(uri));
    }

    /** Confirma o primeiro código do aplicativo e ativa; devolve os códigos de recuperação (mostrados uma vez). */
    @Transactional
    public List<String> enable(UUID account, String code) {
        Map<String, Object> row = jdbc.queryForMap("SELECT mfa_secret,mfa_enabled_at FROM accounts WHERE id=? FOR UPDATE",
                account);
        if (row.get("mfa_enabled_at") != null) throw new IllegalArgumentException("A verificação em duas etapas já está ativa.");
        String secret = (String) row.get("mfa_secret");
        if (secret == null) throw new IllegalArgumentException("Comece a configuração novamente.");
        requireNotLocked(account);
        long step = Totp.verify(secret, code, clock.instant(), null);
        if (step < 0) {
            fail(account);
            throw new IllegalArgumentException("Código inválido. Confira se o horário do celular está automático e tente de novo.");
        }
        jdbc.update("UPDATE accounts SET mfa_enabled_at=now(), mfa_last_step=? WHERE id=?", step, account);
        failures.remove(account);
        return newRecoveryCodes(account);
    }

    /** Confere o código do login (do aplicativo ou de recuperação). */
    @Transactional
    public boolean verify(UUID account, String code) {
        requireNotLocked(account);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT mfa_secret,mfa_enabled_at,mfa_last_step FROM accounts WHERE id=? FOR UPDATE", account);
        if (row.get("mfa_enabled_at") == null) return true;
        String clean = code == null ? "" : code.trim();
        boolean ok;
        if (clean.replaceAll("\\s", "").matches("\\d{6}")) {
            Long last = row.get("mfa_last_step") == null ? null : ((Number) row.get("mfa_last_step")).longValue();
            long step = Totp.verify((String) row.get("mfa_secret"), clean, clock.instant(), last);
            ok = step >= 0 && jdbc.update("UPDATE accounts SET mfa_last_step=? WHERE id=?", step, account) == 1;
        } else {
            ok = jdbc.update("UPDATE account_mfa_recovery_codes SET used_at=now() "
                    + "WHERE account_id=? AND code_hash=? AND used_at IS NULL", account, hash(clean)) == 1;
        }
        if (ok) failures.remove(account);
        else fail(account);
        return ok;
    }

    @Transactional
    public void disable(UUID account, String code) {
        if (!enabled(account)) throw new IllegalArgumentException("A verificação em duas etapas não está ativa.");
        if (!verify(account, code)) throw new IllegalArgumentException("Código inválido.");
        jdbc.update("UPDATE accounts SET mfa_secret=NULL, mfa_enabled_at=NULL, mfa_last_step=NULL WHERE id=?", account);
        jdbc.update("DELETE FROM account_mfa_recovery_codes WHERE account_id=?", account);
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(UUID account, String code) {
        if (!enabled(account)) throw new IllegalArgumentException("A verificação em duas etapas não está ativa.");
        if (!verify(account, code)) throw new IllegalArgumentException("Código inválido.");
        return newRecoveryCodes(account);
    }

    private List<String> newRecoveryCodes(UUID account) {
        jdbc.update("DELETE FROM account_mfa_recovery_codes WHERE account_id=?", account);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < 10; c++) {
                if (c == 5) sb.append('-');
                sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            codes.add(sb.toString());
            jdbc.update("INSERT INTO account_mfa_recovery_codes(account_id,code_hash) VALUES(?,?)",
                    account, hash(sb.toString()));
        }
        return codes;
    }

    private void requireNotLocked(UUID account) {
        long[] state = failures.get(account);
        long now = clock.instant().getEpochSecond();
        if (state != null && now - state[1] < FAILURE_WINDOW_SECONDS && state[0] >= MAX_FAILURES)
            throw new br.com.lcmtech.optics.api.TooManyAttemptsException("Muitos códigos inválidos. Aguarde 15 minutos e tente de novo.");
    }

    private void fail(UUID account) {
        long now = clock.instant().getEpochSecond();
        failures.compute(account, (k, s) -> s == null || now - s[1] >= FAILURE_WINDOW_SECONDS
                ? new long[]{1, now} : new long[]{s[0] + 1, s[1]});
    }

    /** Códigos de recuperação comparados sem hífen, espaços ou caixa. */
    static String hash(String code) {
        String normalized = code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String group(String secret) {
        return secret.replaceAll("(.{4})(?!$)", "$1 ");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String qrSvgDataUri(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            StringBuilder path = new StringBuilder();
            for (int y = 0; y < m.getHeight(); y++)
                for (int x = 0; x < m.getWidth(); x++)
                    if (m.get(x, y)) path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
            String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 " + m.getWidth() + " " + m.getHeight()
                    + "' shape-rendering='crispEdges'><rect width='100%' height='100%' fill='#fff'/><path fill='#17153A' d='"
                    + path + "'/></svg>";
            return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        } catch (WriterException e) {
            throw new IllegalStateException("Não foi possível gerar o QR Code.", e);
        }
    }

}
