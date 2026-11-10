package br.com.lcmtech.optics.platform;

import br.com.lcmtech.optics.security.OpticContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    public PlatformController(JdbcTemplate jdbc,OpticContext context){
        this.jdbc=jdbc;this.context=context;
    }

    @GetMapping("/optics")
    public List<Map<String,Object>> optics(Authentication auth){
        context.requirePlatform(auth);
        return jdbc.queryForList("SELECT o.id,o.slug,o.trade_name,o.contact_email,"
            +"o.whatsapp,o.contact_phone,o.address,o.tax_document,o.site_enabled,o.home_visits_enabled,o.active,"
            +"s.billing_mode,s.trial_started_at,s.trial_ends_at,s.monthly_price,"
            +"s.asaas_customer_id,s.asaas_subscription_id,o.created_at "
            +"FROM optics o JOIN optic_subscriptions s ON s.optic_id=o.id "
            +"ORDER BY o.created_at DESC LIMIT 1000");
    }

    @GetMapping("/dashboard")
    public Map<String,Object> dashboard(Authentication auth){
        context.requirePlatform(auth);
        return jdbc.queryForMap("SELECT COUNT(*) AS total,"
            +"COUNT(*) FILTER(WHERE s.billing_mode='TRIAL') AS trial,"
            +"COUNT(*) FILTER(WHERE s.billing_mode='COMPLIMENTARY') AS complimentary,"
            +"COUNT(*) FILTER(WHERE s.billing_mode='PAID') AS paid,"
            +"COUNT(*) FILTER(WHERE o.site_enabled) AS sites_enabled "
            +"FROM optics o JOIN optic_subscriptions s ON s.optic_id=o.id");
    }

    public record ChangeOptic(String billingMode,BigDecimal monthlyPrice,
                              Boolean siteEnabled,Boolean active){}
    @PatchMapping("/optics/{id}")
    @Transactional
    public Map<String,Boolean> change(@PathVariable UUID id,@RequestBody ChangeOptic request,
                                      Authentication auth){
        context.requirePlatform(auth);
        Integer exists=jdbc.queryForObject("SELECT count(*) FROM optics WHERE id=?",Integer.class,id);
        if(exists==null||exists==0)throw new IllegalArgumentException("Ótica não encontrada.");
        if(request.siteEnabled()!=null)
            jdbc.update("UPDATE optics SET site_enabled=?,updated_at=now() WHERE id=?",request.siteEnabled(),id);
        if(request.active()!=null)
            jdbc.update("UPDATE optics SET active=?,updated_at=now() WHERE id=?",request.active(),id);
        if(request.billingMode()!=null) {
            if(!List.of("TRIAL","PAID","COMPLIMENTARY").contains(request.billingMode()))
                throw new IllegalArgumentException("Selecione Trial, Assinatura ou Cortesia.");
            jdbc.update("UPDATE optic_subscriptions SET billing_mode=?,changed_at=now() WHERE optic_id=?",
                    request.billingMode(),id);
        }
        if(request.monthlyPrice()!=null){
            if(request.monthlyPrice().signum()<0)throw new IllegalArgumentException("Mensalidade negativa.");
            jdbc.update("UPDATE optic_subscriptions SET monthly_price=?,changed_at=now() WHERE optic_id=?",
                    request.monthlyPrice(),id);
        }
        jdbc.update("INSERT INTO audit_events(id,optic_id,actor_account_id,event_type) "
                +"VALUES(?,?,?,'PLATFORM_SHOP_UPDATED')",UUID.randomUUID(),id,context.accountId(auth));
        return Map.of("updated",true);
    }

    public record Notice(UUID opticId,String subject,String message){}
    @PostMapping("/notices")
    public Map<String,Object> notice(@RequestBody Notice request,Authentication auth){
        context.requirePlatform(auth);
        if(request.subject()==null||request.subject().isBlank()||
                request.message()==null||request.message().isBlank())
            throw new IllegalArgumentException("Informe o assunto e a mensagem.");
        if(request.opticId()!=null){
            Integer count=jdbc.queryForObject("SELECT count(*) FROM optics WHERE id=?",Integer.class,request.opticId());
            if(count==null||count==0)throw new IllegalArgumentException("Ótica não encontrada.");
        }
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO platform_notices(id,optic_id,subject,message,created_by) VALUES(?,?,?,?,?)",
                id,request.opticId(),request.subject(),request.message(),context.accountId(auth));
        return Map.of("id",id);
    }

    @GetMapping("/notices")
    public List<Map<String,Object>> notices(Authentication auth){
        context.requirePlatform(auth);
        return jdbc.queryForList("SELECT id,optic_id,subject,message,created_at "
                +"FROM platform_notices ORDER BY created_at DESC LIMIT 200");
    }
}
