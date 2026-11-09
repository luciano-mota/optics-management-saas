package br.com.lcmtech.optics.identity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public")
public class SignupController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AccountActions actions;
    @Value("${optics.signup.enabled:true}") private boolean signupEnabled;
    @Value("${optics.email-verification-required:false}") private boolean emailVerificationRequired;

    public SignupController(JdbcTemplate jdbc,PasswordEncoder encoder,AccountActions actions) {
        this.jdbc=jdbc;this.encoder=encoder;this.actions=actions;
    }

    public record Signup(String opticName,String fullName,String email,String password,String whatsapp,boolean acceptedTerms){}
    @PostMapping("/signup")
    @Transactional
    public Map<String,Object> signup(@RequestBody Signup data) {
        if(!signupEnabled) throw new IllegalStateException("Cadastros temporariamente indisponíveis.");
        if(!data.acceptedTerms()) throw new IllegalArgumentException("Aceite os termos antes de continuar.");
        if(blank(data.opticName())||blank(data.fullName())||blank(data.email()))
            throw new IllegalArgumentException("Informe o nome da ótica, seu nome e e-mail.");
        String email=data.email().trim().toLowerCase(Locale.ROOT);
        if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new IllegalArgumentException("E-mail inválido.");
        validatePassword(data.password());
        String slug=Normalizer.normalize(data.opticName().trim().toLowerCase(Locale.ROOT),Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");
        if(slug.isEmpty()) slug="otica";
        slug=slug.substring(0,Math.min(50,slug.length()))+"-"+UUID.randomUUID().toString().substring(0,8);
        UUID account=UUID.randomUUID(),optic=UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,?,?,?)",
                account,email,encoder.encode(data.password()),data.fullName().trim(),
                emailVerificationRequired?null:java.sql.Timestamp.from(java.time.Instant.now()));
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email,whatsapp) VALUES(?,?,?,?,?)",
                optic,slug,data.opticName().trim(),email,data.whatsapp());
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')",optic,account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)",optic);
        jdbc.update("INSERT INTO site_settings(optic_id) VALUES(?)",optic);
        if(emailVerificationRequired)actions.sendVerification(account,email);
        return Map.of("created",true,"opticId",optic.toString(),"slug",slug,"trialDays",7,"verificationSent",emailVerificationRequired);
    }

    public static void validatePassword(String password) {
        if(password==null||password.length()<10||password.length()>128 ||
                !password.matches("(?s).*[A-Za-z].*")||!password.matches("(?s).*\\d.*"))
            throw new IllegalArgumentException("Use uma senha de 10 a 128 caracteres com letras e números.");
    }

    private static boolean blank(String s) {return s==null||s.isBlank();}
}
