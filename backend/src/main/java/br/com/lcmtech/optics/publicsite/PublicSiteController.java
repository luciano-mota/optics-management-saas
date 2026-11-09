package br.com.lcmtech.optics.publicsite;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/site")
public class PublicSiteController {
    private final JdbcTemplate jdbc;
    public PublicSiteController(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @GetMapping("/{slug}")
    public Map<String,Object> site(@PathVariable String slug) {
        return jdbc.queryForList("SELECT o.id,o.trade_name,o.whatsapp,o.contact_phone,"
            +"o.contact_email,s.primary_color,s.secondary_color,s.font,s.template,"
            +"s.campaign_title,s.campaign_description FROM optics o JOIN site_settings s "
            +"ON s.optic_id=o.id WHERE o.slug=? AND o.active AND o.site_enabled",slug)
            .stream().findFirst().orElseThrow(()->new IllegalArgumentException("Esta página não está disponível."));
    }

    public record RequestVisit(String name,String phone,String email,String address,
               Instant appointmentAt,String notes,boolean privacyAccepted,
               String prescriptionMime,String prescriptionName,String prescriptionBase64){}

    @PostMapping("/{slug}/visits")
    @Transactional
    public Map<String,Object> visit(@PathVariable String slug,@RequestBody RequestVisit request) {
        Map<String,Object> shop=site(slug);
        if(request==null||empty(request.name())||empty(request.phone())||
                empty(request.address())||request.appointmentAt()==null||!request.privacyAccepted())
            throw new IllegalArgumentException("Informe nome, telefone, endereço, horário e aceite a privacidade.");
        if(request.appointmentAt().isBefore(Instant.now()))
            throw new IllegalArgumentException("Escolha uma data futura para a visita.");
        if(request.appointmentAt().isAfter(Instant.now().plusSeconds(90L*86400)))
            throw new IllegalArgumentException("Agendamentos podem ser feitos até 90 dias antes.");
        if(empty(request.prescriptionBase64()))
            throw new IllegalArgumentException("Anexe a receita (PDF ou foto) para agendar.");
        UUID optic=(UUID)shop.get("id"),id=UUID.randomUUID();
        jdbc.update("INSERT INTO home_visits(optic_id,id,lead_name,lead_phone,lead_email,"
            +"lead_address,appointment_at,notes,consent_at) VALUES(?,?,?,?,?,?,?, ?, now())",
            optic,id,request.name().trim(),request.phone().trim(),request.email(),
            request.address().trim(),Timestamp.from(request.appointmentAt()),request.notes());
        jdbc.update("INSERT INTO visit_events(id,optic_id,visit_id,event) VALUES(?,?,?,'REQUESTED')",
                UUID.randomUUID(),optic,id);
        if(!empty(request.prescriptionBase64()))
            AttachmentCodec.store(jdbc,optic,null,null,id,request.prescriptionName(),
                    request.prescriptionMime(),request.prescriptionBase64());
        return Map.of("requested",true,"requestId",id,
                "message","Solicitação recebida. A ótica entrará em contato para confirmar o horário.");
    }
    private static boolean empty(String text){return text==null||text.isBlank();}
}
