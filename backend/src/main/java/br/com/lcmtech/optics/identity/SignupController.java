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
    @Value("${optics.signup.enabled:true}") private boolean signupEnabled;
    @Value("${optics.operator.email:}") private String operatorEmail;
    @Value("${optics.operator.bootstrap-code:}") private String bootstrapCode;

    public SignupController(JdbcTemplate jdbc,PasswordEncoder encoder) {
        this.jdbc=jdbc;this.encoder=encoder;
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
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at) VALUES(?,?,?,?,now())",
                account,email,encoder.encode(data.password()),data.fullName().trim());
        jdbc.update("INSERT INTO optics(id,slug,trade_name,contact_email,whatsapp) VALUES(?,?,?,?,?)",
                optic,slug,data.opticName().trim(),email,data.whatsapp());
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,'MASTER')",optic,account);
        jdbc.update("INSERT INTO optic_subscriptions(optic_id) VALUES(?)",optic);
        jdbc.update("INSERT INTO site_settings(optic_id) VALUES(?)",optic);
        return Map.of("created",true,"opticId",optic.toString(),"slug",slug,"trialDays",7);
    }

    public record OperatorClaim(String email,String bootstrapCode){}
    @PostMapping("/operator-claim")
    @Transactional
    public Map<String,Boolean> operatorClaim(@RequestBody OperatorClaim request) {
        // Fluxo opcional de bootstrap: nunca pode criar senha nem conta.
        // Exige código configurado no ambiente E conta previamente cadastrada no sistema.
        if(blank(bootstrapCode)||bootstrapCode.length()<24 || blank(operatorEmail) ||
                !operatorEmail.equalsIgnoreCase(request.email()) ||
                !java.security.MessageDigest.isEqual(
                        bootstrapCode.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        (request.bootstrapCode()==null?"":request.bootstrapCode())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Credenciais de operação inválidas.");
        UUID account=jdbc.query("SELECT id FROM accounts WHERE lower(email)=lower(?)",
                (rs,n)->(UUID)rs.getObject(1),operatorEmail).stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException("Cadastre primeiro a conta indicada."));
        jdbc.update("INSERT INTO platform_operators(account_id) VALUES(?) ON CONFLICT DO NOTHING",account);
        return Map.of("enabled",true);
    }

    public static void validatePassword(String password) {
        if(password==null||password.length()<10||password.length()>128 ||
                !password.matches("(?s).*[A-Za-z].*")||!password.matches("(?s).*\\d.*"))
            throw new IllegalArgumentException("Use uma senha de 10 a 128 caracteres com letras e números.");
    }

    private static boolean blank(String s) {return s==null||s.isBlank();}
}
