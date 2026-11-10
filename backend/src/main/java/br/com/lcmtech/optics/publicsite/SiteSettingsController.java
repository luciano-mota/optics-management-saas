package br.com.lcmtech.optics.publicsite;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/site-settings")
public class SiteSettingsController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    public SiteSettingsController(JdbcTemplate jdbc,OpticContext context){
        this.jdbc=jdbc;this.context=context;
    }

    private UUID activeSite(Authentication auth,HttpSession session){
        UUID optic=context.optic(auth,session);
        Integer enabled=jdbc.queryForObject("SELECT count(*) FROM optics WHERE id=? AND site_enabled",Integer.class,optic);
        if(enabled==null||enabled==0)throw new org.springframework.security.access.AccessDeniedException(
                "Este recurso precisa ser habilitado pela LCM Dev Tech.");
        return optic;
    }

    @GetMapping
    public Map<String,Object> get(Authentication auth,HttpSession session) {
        context.require(auth,session,"SITE_MANAGE");
        UUID optic=activeSite(auth,session);
        return jdbc.queryForMap("SELECT o.slug,o.site_enabled,o.home_visits_enabled,s.primary_color,s.secondary_color,s.font,"
                +"s.template,s.campaign_title,s.campaign_description,(s.logo IS NOT NULL) AS has_logo "
                +"FROM site_settings s JOIN optics o ON o.id=s.optic_id WHERE s.optic_id=?",optic);
    }
    public record Settings(String primaryColor,String secondaryColor,String font,String template,
                          String campaignTitle,String campaignDescription){}
    @PutMapping
    public Map<String,Boolean> put(@RequestBody Settings request,Authentication auth,HttpSession session){
        context.require(auth,session,"SITE_MANAGE");
        UUID optic=activeSite(auth,session);
        if(request.primaryColor()==null||!request.primaryColor().matches("#[A-Fa-f0-9]{6}")||
                request.secondaryColor()==null||!request.secondaryColor().matches("#[A-Fa-f0-9]{6}"))
            throw new IllegalArgumentException("Informe cores válidas (#RRGGBB).");
        if(!List.of("Manrope","DM Sans","Arial","Georgia").contains(request.font()) ||
                !List.of("classic","modern","compact").contains(request.template()))
            throw new IllegalArgumentException("Escolha uma fonte e um modelo disponíveis.");
        if(request.campaignTitle()==null||request.campaignTitle().isBlank())
            throw new IllegalArgumentException("Informe o título da campanha.");
        jdbc.update("UPDATE site_settings SET primary_color=?,secondary_color=?,font=?,template=?,"
                +"campaign_title=?,campaign_description=?,updated_at=now() WHERE optic_id=?",
                request.primaryColor(),request.secondaryColor(),request.font(),request.template(),
                request.campaignTitle(),request.campaignDescription(),optic);
        return Map.of("updated",true);
    }
}
