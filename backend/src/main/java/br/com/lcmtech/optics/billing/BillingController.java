package br.com.lcmtech.optics.billing;

import br.com.lcmtech.optics.security.OpticContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Optics nunca recebe dados de cartão. A cobrança recorrente acontece na
 * página hospedada pelo Asaas e só um webhook autenticado pode liberar a assinatura.
 */
@RestController
@RequestMapping("/api/v1/subscription")
public class BillingController {
    private final JdbcTemplate jdbc;
    private final OpticContext context;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12)).build();
    @Value("${optics.asaas.api-key:}") private String apiKey;
    @Value("${optics.asaas.base-url:https://api-sandbox.asaas.com/v3}") private String baseUrl;

    public BillingController(JdbcTemplate jdbc,OpticContext context,ObjectMapper json) {
        this.jdbc=jdbc;this.context=context;this.json=json;
    }

    @GetMapping
    public Map<String,Object> details(Authentication auth,HttpSession session) {
        context.requireMaster(auth,session);
        UUID optic=context.optic(auth,session);
        Map<String,Object> row=jdbc.queryForMap("SELECT s.billing_mode,s.trial_ends_at,"
            +"s.monthly_price,s.payment_active,s.paid_through_at,s.payment_link_url "
            +"FROM optic_subscriptions s WHERE s.optic_id=?",optic);
        var result=new LinkedHashMap<>(row);
        result.put("providerConfigured",!apiKey.isBlank());
        return result;
    }

    @PostMapping("/checkout")
    public Map<String,String> checkout(Authentication auth,HttpSession session) {
        context.requireMaster(auth,session);
        UUID optic=context.optic(auth,session);
        Map<String,Object> sub=jdbc.queryForMap("SELECT s.billing_mode,s.monthly_price,s.payment_link_url,"
            +"o.trade_name FROM optic_subscriptions s JOIN optics o ON o.id=s.optic_id WHERE s.optic_id=?",optic);
        if("COMPLIMENTARY".equals(sub.get("billing_mode")))
            throw new IllegalArgumentException("Esta ótica está em cortesia e não precisa pagar.");
        BigDecimal price=(BigDecimal)sub.get("monthly_price");
        if(price==null||price.signum()<=0)
            throw new IllegalArgumentException("A mensalidade ainda não foi definida pela plataforma.");
        String existing=(String)sub.get("payment_link_url");
        if(existing!=null&&!existing.isBlank()) return Map.of("url",existing);
        if(apiKey==null||apiKey.isBlank())
            throw new IllegalStateException("A integração Asaas ainda não foi configurada.");
        try {
            var payload=Map.of(
                "billingType","UNDEFINED",
                "chargeType","RECURRENT",
                "subscriptionCycle","MONTHLY",
                "name","Assinatura Optics Management",
                "description","Mensalidade Optics Management - "+sub.get("trade_name"),
                "value",price,
                "externalReference",optic.toString()
            );
            HttpRequest request=HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$","")+"/paymentLinks"))
                .header("access_token",apiKey).header("Content-Type","application/json")
                .header("Accept","application/json").header("User-Agent","OpticsManagement/0.1")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()/100!=2)
                throw new IllegalStateException("Não foi possível iniciar a assinatura no Asaas.");
            JsonNode created=json.readTree(response.body());
            String url=created.path("url").asText();
            String id=created.path("id").asText();
            if(id.isBlank()||!isAsaasUrl(url))
                throw new IllegalStateException("Asaas não retornou um link de pagamento válido.");
            jdbc.update("UPDATE optic_subscriptions SET payment_link_id=?,payment_link_url=?,"
                    +"changed_at=now() WHERE optic_id=? AND payment_link_id IS NULL",id,url,optic);
            return Map.of("url",url);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A comunicação com Asaas foi interrompida.");
        } catch(java.io.IOException e) {
            throw new IllegalStateException("O Asaas está indisponível. Tente novamente.");
        }
    }

    private static boolean isAsaasUrl(String value) {
        try {
            URI uri=URI.create(value);
            String host=uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())&&host!=null
                    &&(host.equals("asaas.com")||host.endsWith(".asaas.com"));
        } catch(Exception ignored) {return false;}
    }
}
