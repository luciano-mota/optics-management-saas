package br.com.lcmtech.optics.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Cadastro do operador da LCM por convite entregue ao e-mail autorizado no
 * ambiente. Não exige criar ótica fictícia, não permite escolher outro e-mail,
 * não publica o token e não concede acesso antes da confirmação do link.
 */
@RestController
@RequestMapping("/api/v1/public/platform-owner")
public class PlatformOwnerOnboardingController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final EmailDelivery mail;
    private final SecureRandom random=new SecureRandom();

    @Value("${optics.operator.email:}") private String ownerEmail;
    @Value("${optics.public-base-url:http://localhost:5173}") private String baseUrl;

    public PlatformOwnerOnboardingController(JdbcTemplate jdbc,
            PasswordEncoder passwords, EmailDelivery mail) {
        this.jdbc=jdbc; this.passwords=passwords; this.mail=mail;
    }

    public record InviteRequest(String email) {}

    @PostMapping("/request")
    @Transactional
    public Map<String,Boolean> request(@RequestBody InviteRequest request) {
        if(!mail.available())
            throw new IllegalStateException("O serviço SMTP ainda não foi configurado.");
        if(ownerEmail==null||ownerEmail.isBlank())
            throw new IllegalStateException("O e-mail do operador LCM ainda não foi configurado.");

        // A resposta não revela qual endereço é o titular da plataforma.
        String target=request==null||request.email()==null?"":request.email().trim()
                .toLowerCase(Locale.ROOT);
        if(!target.equals(ownerEmail.trim().toLowerCase(Locale.ROOT)))
            return Map.of("accepted",true);

        Integer recent=jdbc.queryForObject("""
                SELECT count(*) FROM platform_owner_invites
                WHERE lower(invite_email)=lower(?) AND created_at>now()-interval '10 minutes'
                """,Integer.class,target);
        if(recent!=null&&recent>0) return Map.of("accepted",true);
        byte[] raw=new byte[32];random.nextBytes(raw);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("""
            INSERT INTO platform_owner_invites(id,invite_email,token_hash,expires_at)
            VALUES(?,?,?,?)
            """,UUID.randomUUID(),target,digest(token),
                java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));
        String url=baseUrl.replaceAll("/+$","")+"/?ownerInvite="+token;
        mail.send(target,"Ative seu acesso — Administração Optics Management",
                "Olá!\n\nVocê solicitou acesso como operador da LCM Dev Tech."
                +"\nPara concluir seu cadastro, acesse o link abaixo (30 minutos):\n\n"
                +url+"\n\nSe não solicitou, ignore esta mensagem.");
        return Map.of("accepted",true);
    }

    public record CompleteRequest(String token,String fullName,String password,
                                  boolean acceptedTerms) {}

    @PostMapping("/complete")
    @Transactional
    public Map<String,Boolean> complete(@RequestBody CompleteRequest request) {
        if(request==null||!request.acceptedTerms())
            throw new IllegalArgumentException("Confirme os termos de uso.");
        SignupController.validatePassword(request.password());
        if(request.fullName()==null||request.fullName().isBlank()||
                request.fullName().length()>180)
            throw new IllegalArgumentException("Informe seu nome completo.");
        if(request.token()==null||request.token().length()<32)
            throw new IllegalArgumentException("Convite inválido.");

        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT id,invite_email FROM platform_owner_invites
            WHERE token_hash=? AND consumed_at IS NULL AND expires_at>now()
            FOR UPDATE
            """,digest(request.token()));
        if(rows.isEmpty())
            throw new IllegalArgumentException("Convite expirado ou utilizado.");
        UUID invite=(UUID)rows.get(0).get("id");
        String invitedEmail=(String)rows.get(0).get("invite_email");
        if(ownerEmail==null||!invitedEmail.equalsIgnoreCase(ownerEmail.trim()))
            throw new IllegalArgumentException("Este convite não corresponde ao operador atual.");

        List<UUID> accounts=jdbc.query(
                "SELECT id FROM accounts WHERE lower(email)=lower(?) AND active FOR UPDATE",
                (rs,n)->(UUID)rs.getObject(1),invitedEmail);
        UUID account;
        if(accounts.isEmpty()) {
            Integer other=jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE lower(email)=lower(?)",
                Integer.class,invitedEmail);
            if(other!=null&&other>0)
                throw new IllegalStateException("Conta desativada. Solicite suporte ao administrador.");
            account=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO accounts(id,email,password_hash,full_name,email_verified_at)
                VALUES(?,?,?,?,now())
                """,account,invitedEmail,passwords.encode(request.password()),
                    request.fullName().trim());
        } else {
            account=accounts.get(0);
            jdbc.update("""
                UPDATE accounts SET password_hash=?,full_name=?,email_verified_at=now(),
                  updated_at=now() WHERE id=?
                """,passwords.encode(request.password()),request.fullName().trim(),account);
            jdbc.update("DELETE FROM spring_session WHERE principal_name=?",invitedEmail);
        }
        jdbc.update("INSERT INTO platform_operators(account_id) VALUES(?) "
                +"ON CONFLICT(account_id) DO NOTHING",account);
        jdbc.update("UPDATE platform_owner_invites SET consumed_at=now() WHERE id=?",invite);
        jdbc.update("""
            INSERT INTO audit_events(id,actor_account_id,event_type,entity_type,entity_id)
            VALUES(?,?,'PLATFORM_OWNER_EMAIL_ONBOARDED','ACCOUNT',?)
            """,UUID.randomUUID(),account,account);
        return Map.of("created",true);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
