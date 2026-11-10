package br.com.lcmtech.optics.identity;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class TeamController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final PasswordEncoder passwordEncoder;
    private final EmailDelivery mail;
    private final SecureRandom random=new SecureRandom();
    private static final List<String> ALL_PERMISSIONS = List.of(
            "FINANCE_READ", "FINANCE_WRITE", "CUSTOMERS_READ", "CUSTOMERS_WRITE",
            "SALES_READ", "SALES_CREATE", "SALES_DELIVER", "VISITS_READ",
            "VISITS_MANAGE", "CATALOG_READ", "CATALOG_WRITE", "REPORTS_READ",
            "USERS_MANAGE", "SITE_MANAGE", "PATIENTS_READ", "PATIENTS_WRITE",
            "APPOINTMENTS_READ", "APPOINTMENTS_MANAGE", "PARTNER_CLINICS_MANAGE",
            "RECEIPTS_READ", "RECEIPTS_ISSUE");
    @Value("${optics.public-base-url:http://localhost:5173}") private String baseUrl;
    public TeamController(JdbcTemplate jdbc, OpticContext context,PasswordEncoder passwordEncoder,EmailDelivery mail) {
        this.jdbc=jdbc;this.context=context;this.passwordEncoder=passwordEncoder;this.mail=mail;
    }

    @GetMapping("/team")
    public List<Map<String,Object>> team(Authentication auth,HttpSession session) {
        context.requireMaster(auth,session);
        return jdbc.queryForList("SELECT m.account_id,a.full_name,a.email,a.phone,m.role,m.active "
                + "FROM optic_memberships m JOIN accounts a ON a.id=m.account_id "
                + "WHERE m.optic_id=? ORDER BY a.full_name",context.optic(auth,session));
    }

    public record Invite(String email,String role,List<String> permissions){}
    @PostMapping("/team/invites")
    @Transactional
    public Map<String,String> invite(@RequestBody Invite data,Authentication auth,HttpSession session) {
        context.requireMaster(auth,session);
        if(data.email()==null||!data.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new IllegalArgumentException("E-mail inválido.");
        if(!List.of("MASTER","MANAGER","SECRETARY","SELLER").contains(data.role()))
            throw new IllegalArgumentException("Escolha um perfil válido.");
        if(data.permissions()!=null && !ALL_PERMISSIONS.containsAll(data.permissions()))
            throw new IllegalArgumentException("O convite contém permissões inválidas.");
        if(!mail.available())
            throw new IllegalStateException("O envio de convites por e-mail ainda não foi configurado.");
        UUID optic=context.optic(auth,session),account=context.accountId(auth),id=UUID.randomUUID();
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash=digest(token);
        jdbc.update("INSERT INTO member_invitations(id,optic_id,invited_email,requested_role,"
                + "token_hash,invited_by,expires_at,permissions_csv,permissions_selected) "
                + "VALUES(?,?,?,?,?,?,now()+interval '72 hours',?,?)",id,optic,
                data.email().trim().toLowerCase(Locale.ROOT),data.role(),hash,account,
                data.permissions()==null?"":String.join(",",data.permissions()),
                data.permissions()!=null);
        mail.send(data.email().trim(),"Convite Optics Management",
                "Olá! Você recebeu acesso à equipe da ótica. Para completar seu cadastro, "
                +"use o link em até 72 horas:\n\n"+baseUrl+"/?invite="+token);
        // Permissões selecionadas são aplicadas somente após aceite válido de convite.
        return Map.of("inviteUrl",baseUrl.replaceAll("/+$","")+"/?invite="+token,
                "message","Convite enviado por e-mail. Validade: 72 horas.");
    }

    public record Accept(String token,String fullName,String password,String cpf,String phone,String address,String birthDate){}
    @PostMapping("/public/accept-invite")
    @Transactional
    public Map<String,Boolean> accept(@RequestBody Accept data) {
        SignupController.validatePassword(data.password());
        if(data.fullName()==null||data.fullName().isBlank()||
                data.phone()==null||data.phone().isBlank()||
                data.birthDate()==null||data.birthDate().isBlank()||
                data.cpf()==null||!ProfileController.validCpf(data.cpf().replaceAll("\\D",""))||
                data.phone().replaceAll("\\D","").length()<10||
                data.phone().replaceAll("\\D","").length()>13)
            throw new IllegalArgumentException("Informe nome, telefone com DDD, nascimento e CPF válido.");
        try {
            java.time.LocalDate birth=java.time.LocalDate.parse(data.birthDate());
            if(birth.isAfter(java.time.LocalDate.now())||
                birth.isBefore(java.time.LocalDate.of(1900,1,1)))
                throw new IllegalArgumentException("Data de nascimento inválida.");
        }catch(java.time.format.DateTimeParseException ex){
            throw new IllegalArgumentException("Data de nascimento inválida.");
        }
        Map<String,Object> invite=readInvite(data.token());
        String email=(String)invite.get("invited_email");
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE lower(email)=lower(?)",
                Integer.class,email);
        if(exists!=null&&exists>0)
            throw new IllegalArgumentException("Esse e-mail já tem conta. Entre na conta e aceite o convite.");
        UUID account=UUID.randomUUID(),optic=(UUID)invite.get("optic_id");
        jdbc.update("INSERT INTO accounts(id,email,password_hash,full_name,cpf,phone,address,birth_date,email_verified_at,profile_completed_at) "
                + "VALUES(?,?,?,?,?,?,?,?::date,now(),now())",account,email,
                passwordEncoder.encode(data.password()),data.fullName().trim(),
                data.cpf().replaceAll("\\D",""),data.phone(),data.address(),data.birthDate());
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,?)",optic,account,invite.get("requested_role"));
        applyPermissions(invite,optic,account);
        jdbc.update("UPDATE member_invitations SET accepted_at=now() WHERE id=? AND accepted_at IS NULL",invite.get("id"));
        return Map.of("created",true);
    }

    @PostMapping("/auth/accept-invite")
    @Transactional
    public Map<String,Boolean> acceptExisting(@RequestBody Map<String,String> data,Authentication auth) {
        Map<String,Object> invite=readInvite(data.get("token"));
        String loggedIn=auth.getName().toLowerCase(Locale.ROOT);
        if(!loggedIn.equalsIgnoreCase((String)invite.get("invited_email")))
            throw new AccessDeniedException("O convite pertence a outro e-mail.");
        UUID account=context.accountId(auth),optic=(UUID)invite.get("optic_id");
        jdbc.update("INSERT INTO optic_memberships(optic_id,account_id,role) VALUES(?,?,?) "
                + "ON CONFLICT (optic_id,account_id) DO NOTHING",optic,account,invite.get("requested_role"));
        applyPermissions(invite,optic,account);
        jdbc.update("UPDATE member_invitations SET accepted_at=now() WHERE id=? AND accepted_at IS NULL",invite.get("id"));
        return Map.of("accepted",true);
    }

    public record RoleChange(String role,boolean active){}
    @PatchMapping("/team/{userId}")
    @Transactional
    public Map<String,Boolean> update(@PathVariable UUID userId,@RequestBody RoleChange data,
                                      Authentication auth,HttpSession session) {
        context.requireMaster(auth,session);
        if(!List.of("MASTER","MANAGER","SECRETARY","SELLER").contains(data.role()))
            throw new IllegalArgumentException("Perfil inválido.");
        UUID optic=context.optic(auth,session);
        if(!data.active()||!"MASTER".equals(data.role())) {
            String current=context.role(userId,optic);
            if("MASTER".equals(current)) {
                Integer masters=jdbc.queryForObject("SELECT count(*) FROM optic_memberships WHERE optic_id=? "
                        + "AND role='MASTER' AND active",Integer.class,optic);
                if(masters==null||masters<=1) throw new IllegalArgumentException("Mantenha ao menos um administrador ativo.");
            }
        }
        if(jdbc.update("UPDATE optic_memberships SET role=?,active=? WHERE optic_id=? AND account_id=?",
                data.role(),data.active(),optic,userId)==0)throw new IllegalArgumentException("Funcionário não encontrado.");
        return Map.of("updated",true);
    }

    public record PermissionChange(String permission,String effect){}
    @PutMapping("/team/{userId}/permission")
    @Transactional
    public Map<String,Boolean> permission(@PathVariable UUID userId,@RequestBody PermissionChange data,
                                          Authentication auth,HttpSession session) {
        context.requireMaster(auth,session);
        if(!ALL_PERMISSIONS.contains(data.permission()))
            throw new IllegalArgumentException("Permissão desconhecida.");
        if(!List.of("ALLOW","DENY","DEFAULT").contains(data.effect()))
            throw new IllegalArgumentException("Efeito inválido.");
        UUID optic=context.optic(auth,session);
        if(!context.isMember(userId,optic)) throw new IllegalArgumentException("Funcionário não encontrado.");
        if("MASTER".equals(context.role(userId,optic)))
            throw new IllegalArgumentException("Acesso de administrador não pode ser restringido por permissões.");
        if("DEFAULT".equals(data.effect())) {
            jdbc.update("DELETE FROM member_permission_overrides WHERE optic_id=? AND account_id=? AND permission=?",
                    optic,userId,data.permission());
        } else {
            jdbc.update("INSERT INTO member_permission_overrides(optic_id,account_id,permission,effect) "
                    + "VALUES(?,?,?,?) ON CONFLICT(optic_id,account_id,permission) "
                    + "DO UPDATE SET effect=excluded.effect",optic,userId,data.permission(),data.effect());
        }
        return Map.of("updated",true);
    }

    private void applyPermissions(Map<String,Object> invitation,UUID optic,UUID account) {
        if(!Boolean.TRUE.equals(invitation.get("permissions_selected")))return;
        if("MASTER".equals(invitation.get("requested_role")))return;
        String saved=(String)invitation.get("permissions_csv");
        List<String> allowed=saved==null||saved.isBlank()?List.of():
                java.util.Arrays.asList(saved.split(","));
        for(String permission:ALL_PERMISSIONS){
            String effect=allowed.contains(permission)?"ALLOW":"DENY";
            jdbc.update("INSERT INTO member_permission_overrides("
                    + "optic_id,account_id,permission,effect) VALUES(?,?,?,?) "
                    + "ON CONFLICT (optic_id,account_id,permission) DO UPDATE SET effect=excluded.effect",
                    optic,account,permission,effect);
        }
    }

    private Map<String,Object> readInvite(String token) {
        if(token==null||token.length()<30) throw new IllegalArgumentException("Convite inválido.");
        return jdbc.queryForList("SELECT id,optic_id,invited_email,requested_role,permissions_csv,permissions_selected FROM member_invitations "
                + "WHERE token_hash=? AND expires_at>now() AND accepted_at IS NULL AND revoked_at IS NULL",
                digest(token)).stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException("Convite expirado, usado ou inválido."));
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
