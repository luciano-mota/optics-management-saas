package br.com.lcmtech.optics.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public")
public class AccountActions {
    private final JdbcTemplate jdbc;
    private final EmailDelivery mail;
    private final PasswordEncoder encoder;
    private final SecureRandom random=new SecureRandom();
    @Value("${optics.public-base-url:http://localhost:5173}") private String baseUrl;
    public AccountActions(JdbcTemplate jdbc,EmailDelivery mail,PasswordEncoder encoder) {
        this.jdbc=jdbc;this.mail=mail;this.encoder=encoder;
    }

    public void sendVerification(UUID account,String email) {
        if(!mail.available())throw new IllegalStateException("Verificação por e-mail indisponível.");
        String token=createToken(account,"VERIFY_EMAIL",24);
        mail.send(email,"Confirme sua conta Optics Management",
                "Olá! Confirme sua conta pelo link (válido por 24 horas):\n\n"
                +baseUrl+"/?verify="+token+"\n\nSe você não se cadastrou, ignore este aviso.");
    }

    public record Forgot(String email){}
    @PostMapping("/password/forgot")
    @Transactional
    public Map<String,Boolean> forgot(@RequestBody Forgot request) {
        // Resposta uniforme para não revelar contas existentes.
        if(request.email()!=null&&mail.available()){
            var users=jdbc.query("SELECT id,email FROM accounts WHERE lower(email)=lower(?) AND active",
                    (rs,n)->Map.of("id",(UUID)rs.getObject(1),"email",rs.getString(2)),
                    request.email().trim());
            if(!users.isEmpty()){
                UUID account=(UUID)users.get(0).get("id");
                String email=(String)users.get(0).get("email");
                String token=createToken(account,"RESET_PASSWORD",1);
                mail.send(email,"Redefinição de senha Optics Management",
                        "Para definir uma nova senha, abra este link (válido por 1 hora):\n\n"
                            +baseUrl+"/?reset="+token
                            +"\n\nSe você não solicitou, ignore esta mensagem.");
            }
        }
        return Map.of("accepted",true);
    }

    public record Reset(String token,String password){}
    @PostMapping("/password/reset")
    @Transactional
    public Map<String,Boolean> reset(@RequestBody Reset request) {
        SignupController.validatePassword(request.password());
        UUID account=takeToken(request.token(),"RESET_PASSWORD");
        jdbc.update("UPDATE accounts SET password_hash=?,updated_at=now() WHERE id=?",
                encoder.encode(request.password()),account);
        jdbc.update("DELETE FROM spring_session WHERE principal_name="
                +"(SELECT email FROM accounts WHERE id=?)",account);
        return Map.of("updated",true);
    }

    public record Verify(String token){}
    @PostMapping("/email/verify")
    @Transactional
    public Map<String,Boolean> verify(@RequestBody Verify request) {
        UUID account=takeToken(request.token(),"VERIFY_EMAIL");
        jdbc.update("UPDATE accounts SET email_verified_at=now() WHERE id=?",account);
        return Map.of("verified",true);
    }

    private String createToken(UUID account,String purpose,int hours) {
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO account_action_tokens(id,account_id,token_hash,purpose,expires_at) "
            +"VALUES(?,?,?,?,?)",UUID.randomUUID(),account,digest(token),purpose,
            java.sql.Timestamp.from(Instant.now().plusSeconds(hours*3600L)));
        return token;
    }
    private UUID takeToken(String token,String purpose) {
        if(token==null||token.length()<32)throw new IllegalArgumentException("Link inválido.");
        var rows=jdbc.query("UPDATE account_action_tokens SET consumed_at=now() "
                +"WHERE token_hash=? AND purpose=? AND consumed_at IS NULL AND expires_at>now() "
                +"RETURNING account_id",(rs,n)->(UUID)rs.getObject(1),digest(token),purpose);
        return rows.stream().findFirst().orElseThrow(()->new IllegalArgumentException(
                "Link expirado, utilizado ou inválido."));
    }
    private static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
