package br.com.lcmtech.optics.publicsite;

import br.com.lcmtech.optics.publicsite.domain.SiteContent;
import br.com.lcmtech.optics.security.OpticContext;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper json;
    public SiteSettingsController(JdbcTemplate jdbc,OpticContext context,ObjectMapper json){
        this.jdbc=jdbc;this.context=context;this.json=json;
    }

    private UUID activeSite(Authentication auth,HttpSession session){
        UUID optic=context.optic(auth,session);
        Integer enabled=jdbc.queryForObject("SELECT count(*) FROM optics WHERE id=? AND site_enabled",Integer.class,optic);
        if(enabled==null||enabled==0)throw new org.springframework.security.access.AccessDeniedException(
                "Este recurso precisa ser habilitado pela LCM DEV TECH.");
        return optic;
    }

    private static final String COLUMNS="o.slug,o.site_enabled,o.home_visits_enabled,coalesce(o.address,'') AS address,"
            +"o.whatsapp,o.contact_phone,o.contact_email,s.primary_color,s.secondary_color,s.template,s.mood,s.fonts,"
            +"s.campaign_title AS headline,s.campaign_description AS subtitle,s.about,s.hours,s.instagram,s.show_map,"
            +"s.services::text AS services,s.testimonials::text AS testimonials,s.faqs::text AS faqs,"
            +"s.home_visit_title,s.home_visit_description,(s.logo IS NOT NULL) AS has_logo";

    /** Lê o site com as listas já convertidas de JSON (usado também pelo site público). */
    static Map<String,Object> read(JdbcTemplate jdbc,ObjectMapper json,String where,Object key) {
        Map<String,Object> row=jdbc.queryForList("SELECT o.id,o.trade_name,"+COLUMNS
                +" FROM site_settings s JOIN optics o ON o.id=s.optic_id WHERE "+where,key)
                .stream().findFirst().orElse(null);
        if(row==null)return null;
        Map<String,Object> result=new java.util.HashMap<>(row);
        for(String list:List.of("services","testimonials","faqs")) {
            try { result.put(list,json.readValue((String)row.get(list),List.class)); }
            catch(Exception invalid) { result.put(list,List.of()); }
        }
        return result;
    }

    @GetMapping
    public Map<String,Object> get(Authentication auth,HttpSession session) {
        context.require(auth,session,"SITE_MANAGE");
        UUID optic=activeSite(auth,session);
        Map<String,Object> site=read(jdbc,json,"s.optic_id=?",optic);
        site.remove("id");
        return site;
    }

    @PutMapping
    public Map<String,Boolean> put(@RequestBody SiteContent content,Authentication auth,HttpSession session){
        context.require(auth,session,"SITE_MANAGE");
        UUID optic=activeSite(auth,session);
        try {
            jdbc.update("""
                UPDATE site_settings SET primary_color=?,secondary_color=?,template=?,mood=?,fonts=?,
                    campaign_title=?,campaign_description=?,about=?,hours=?,instagram=?,show_map=?,
                    services=?::jsonb,testimonials=?::jsonb,faqs=?::jsonb,
                    home_visit_title=COALESCE(?,home_visit_title),home_visit_description=COALESCE(?,home_visit_description),
                    updated_at=now() WHERE optic_id=?
                """,content.primaryColor(),content.secondaryColor(),content.template(),content.mood(),content.fonts(),
                    content.headline(),content.subtitle()==null?"":content.subtitle(),content.about(),content.hours(),
                    content.instagram(),content.showMap(),json.writeValueAsString(content.services()),
                    json.writeValueAsString(content.testimonials()),json.writeValueAsString(content.faqs()),
                    content.homeVisitTitle(),content.homeVisitDescription(),optic);
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Conteúdo do site inválido.");
        }
        // Atendimento em domicílio é uma campanha que a própria ótica decide oferecer no site.
        jdbc.update("UPDATE optics SET home_visits_enabled=?,updated_at=now() WHERE id=?",content.homeVisitsEnabled(),optic);
        return Map.of("updated",true);
    }
}
