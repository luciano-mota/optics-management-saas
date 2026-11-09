package br.com.lcmtech.optics.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Webhook Asaas: valida token do header, evento idempotente, cobrança idempotente
 * e nenhuma credencial ou receita no log. Eventos desconhecidos são registrados,
 * mas não alteram assinaturas.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class AsaasWebhookController {
    private final JdbcTemplate jdbc;
    @Value("${optics.asaas.webhook-token:}") private String secret;
    public AsaasWebhookController(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @PostMapping("/asaas")
    @Transactional
    public Map<String,Boolean> event(@RequestHeader(name="asaas-access-token",required=false) String token,
                                      @RequestBody JsonNode body) {
        if(secret==null||secret.length()<32||token==null ||
           !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                                  token.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Webhook inválido.");
        String id=body.path("id").asText("");
        String kind=body.path("event").asText("");
        if(id.isBlank()||kind.isBlank()||id.length()>128||kind.length()>100)
            throw new IllegalArgumentException("Evento Asaas inválido.");
        JsonNode payment=body.path("payment");
        String payId=payment.path("id").asText("");
        String subId=payment.path("subscription").asText("");
        String link=payment.path("paymentLink").asText("");
        String external=payment.path("externalReference").asText("");
        UUID optic=null;
        if(!subId.isBlank())optic=find("asaas_subscription_id",subId);
        if(optic==null&&!link.isBlank())optic=find("payment_link_id",link);
        if(optic==null&&!external.isBlank()) {
            try {
                UUID candidate=UUID.fromString(external);
                Integer n=jdbc.queryForObject("SELECT count(*) FROM optic_subscriptions WHERE optic_id=?",
                        Integer.class,candidate);
                if(n!=null&&n==1)optic=candidate;
            } catch(IllegalArgumentException ignored) {}
        }
        int inserted=jdbc.update("INSERT INTO asaas_webhook_events(event_id,kind,payment_id,optic_id) "
                +"VALUES(?,?,?,?) ON CONFLICT DO NOTHING",id,kind,payId.isBlank()?null:payId,optic);
        if(inserted==0)return Map.of("received",true);
        if(optic==null)return Map.of("received",true);
        if((kind.equals("PAYMENT_RECEIVED")||kind.equals("PAYMENT_CONFIRMED"))&&!payId.isBlank()){
            int first=jdbc.update("INSERT INTO asaas_payment_receipts(payment_id,optic_id,event_id)"
                    +" VALUES(?,?,?) ON CONFLICT DO NOTHING",payId,optic,id);
            if(first==1){
                jdbc.update("UPDATE optic_subscriptions SET billing_mode='PAID',payment_active=true,"
                    +"paid_through_at=GREATEST(COALESCE(paid_through_at,now()),now())+interval '1 month',"
                    +"asaas_subscription_id=COALESCE(NULLIF(?,''),asaas_subscription_id),"
                    +"payment_updated_at=now() WHERE optic_id=? AND billing_mode<>'COMPLIMENTARY'",subId,optic);
            }
        }else if(kind.equals("PAYMENT_REFUNDED")||kind.equals("PAYMENT_DELETED")){
            jdbc.update("UPDATE optic_subscriptions SET payment_active=false,payment_updated_at=now() "
                    +"WHERE optic_id=? AND billing_mode='PAID'",optic);
        }
        return Map.of("received",true);
    }

    private UUID find(String column,String value) {
        return jdbc.query("SELECT optic_id FROM optic_subscriptions WHERE "+column+"=?",
                (rs,n)->(UUID)rs.getObject(1),value).stream().findFirst().orElse(null);
    }
}
