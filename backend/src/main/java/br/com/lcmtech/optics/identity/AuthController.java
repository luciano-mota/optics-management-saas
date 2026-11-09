package br.com.lcmtech.optics.identity;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final OpticContext context;
    private final JdbcTemplate jdbc;
    public AuthController(OpticContext context, JdbcTemplate jdbc) {
        this.context = context; this.jdbc = jdbc;
    }
    @GetMapping("/csrf")
    public Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken()); }

    @GetMapping("/me")
    public Map<String,Object> me(Authentication auth,HttpSession session) {
        UUID account=context.accountId(auth);
        Map<String,Object> person=jdbc.queryForMap(
                "SELECT id,email,coalesce(full_name,'') AS full_name,coalesce(phone,'') AS phone "
                        + "FROM accounts WHERE id=?",account);
        List<Map<String,Object>> shops=context.memberships(account);
        UUID activeOptic=shops.isEmpty()?null:context.optic(auth,session);
        List<String> rights=activeOptic==null?List.of():
                List.of("CUSTOMERS_READ","CUSTOMERS_WRITE","SALES_READ","SALES_CREATE","SALES_DELIVER",
                        "VISITS_READ","VISITS_MANAGE","FINANCE_READ","FINANCE_WRITE",
                        "CATALOG_READ","CATALOG_WRITE","REPORTS_READ","USERS_MANAGE","SITE_MANAGE").stream()
                        .filter(p->context.can(account,activeOptic,p)).toList();
        Integer op=jdbc.queryForObject("SELECT COUNT(*) FROM platform_operators WHERE account_id=? AND active",Integer.class,account);
        return Map.of("account",person,"shops",shops,"activeOpticId",activeOptic==null?"":activeOptic.toString(),
                "permissions",rights,"platformOperator",op!=null&&op>0);
    }

    public record SelectOptic(UUID opticId) {}
    @PostMapping("/select-optic")
    public Map<String,Boolean> selectOptic(@RequestBody SelectOptic request,Authentication auth,HttpSession session) {
        if(!context.isMember(context.accountId(auth),request.opticId()))
            throw new AccessDeniedException("Você não pertence a esta ótica.");
        session.setAttribute("activeOpticId",request.opticId().toString());
        return Map.of("selected",true);
    }

    @GetMapping("/notifications")
    public List<Map<String,Object>> notifications(Authentication auth,HttpSession session) {
        UUID optic=context.optic(auth,session);
        UUID account=context.accountId(auth);
        return jdbc.queryForList("SELECT n.id,n.subject,n.message,n.created_at, "
                        + "(r.notice_id IS NOT NULL) AS read "
                        + "FROM platform_notices n LEFT JOIN notice_reads r ON n.id=r.notice_id AND r.account_id=? "
                        + "WHERE n.optic_id IS NULL OR n.optic_id=? ORDER BY n.created_at DESC LIMIT 50",
                account,optic);
    }
    @PostMapping("/notifications/{id}/read")
    public Map<String,Boolean> read(@PathVariable UUID id,Authentication auth,HttpSession session) {
        UUID account=context.accountId(auth),optic=context.optic(auth,session);
        Integer visible=jdbc.queryForObject("SELECT count(*) FROM platform_notices WHERE id=? "
                + "AND (optic_id IS NULL OR optic_id=?)",Integer.class,id,optic);
        if(visible==null||visible==0) throw new AccessDeniedException("Aviso não encontrado.");
        jdbc.update("INSERT INTO notice_reads(notice_id,account_id) VALUES (?,?) ON CONFLICT DO NOTHING",id,account);
        return Map.of("read",true);
    }
}
