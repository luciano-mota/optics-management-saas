package br.com.lcmtech.optics.identity.mfa;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {
    static final int MAX_SESSION_FAILURES = 5;
    private static final String SESSION_FAILURES = "mfaFailures";

    private final MfaService mfa;
    private final OpticContext context;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public MfaController(MfaService mfa, OpticContext context, JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.mfa = mfa;
        this.context = context;
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    public record Code(String code) {}
    public record Disable(String password, String code) {}

    /** Diz se a sessão atual ainda espera o código (usado pela tela de login ao recarregar a página). */
    @GetMapping("/challenge")
    public Map<String, Boolean> challenge(HttpSession session) {
        return Map.of("pending", session.getAttribute(MfaGateFilter.PENDING) != null);
    }

    /** Segunda etapa do login. Cinco códigos errados na mesma sessão encerram a sessão. */
    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Code body, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object pending = session == null ? null : session.getAttribute(MfaGateFilter.PENDING);
        if (pending == null) return ResponseEntity.ok(Map.of("verified", true));
        UUID account = UUID.fromString(pending.toString());
        if (mfa.verify(account, body == null ? null : body.code())) {
            session.removeAttribute(MfaGateFilter.PENDING);
            session.removeAttribute(SESSION_FAILURES);
            request.changeSessionId();
            return ResponseEntity.ok(Map.of("verified", true));
        }
        int failures = session.getAttribute(SESSION_FAILURES) instanceof Integer n ? n + 1 : 1;
        if (failures >= MAX_SESSION_FAILURES) {
            session.invalidate();
            return ResponseEntity.status(401).body(Map.of("error",
                    "Código inválido várias vezes. Entre novamente com e-mail e senha."));
        }
        session.setAttribute(SESSION_FAILURES, failures);
        return ResponseEntity.badRequest().body(Map.of("error",
                "Código inválido. Use o código atual do aplicativo ou um código de recuperação."));
    }

    @GetMapping
    public Map<String, Object> status(Authentication auth) {
        return mfa.status(context.accountId(auth));
    }

    @PostMapping("/setup")
    public Map<String, String> setup(Authentication auth) {
        return mfa.startSetup(context.accountId(auth));
    }

    @PostMapping("/enable")
    public Map<String, List<String>> enable(@RequestBody Code body, Authentication auth) {
        return Map.of("recoveryCodes", mfa.enable(context.accountId(auth), body == null ? null : body.code()));
    }

    /** Desativar exige a senha e um código, para que uma sessão esquecida aberta não baste. */
    @PostMapping("/disable")
    public Map<String, Boolean> disable(@RequestBody Disable body, Authentication auth) {
        UUID account = context.accountId(auth);
        String hash = jdbc.queryForObject("SELECT password_hash FROM accounts WHERE id=?", String.class, account);
        if (body == null || body.password() == null || !passwords.matches(body.password(), hash))
            throw new IllegalArgumentException("Senha incorreta.");
        mfa.disable(account, body.code());
        return Map.of("enabled", false);
    }

    @PostMapping("/recovery-codes")
    public Map<String, List<String>> recoveryCodes(@RequestBody Code body, Authentication auth) {
        return Map.of("recoveryCodes",
                mfa.regenerateRecoveryCodes(context.accountId(auth), body == null ? null : body.code()));
    }
}
