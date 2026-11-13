package br.com.lcmtech.optics.push;

import br.com.lcmtech.optics.security.OpticContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Avisos neste aparelho: cada pessoa liga ou desliga no próprio navegador. */
@RestController
@RequestMapping("/api/v1/push")
public class PushController {
    private final PushService push;
    private final OpticContext context;

    public PushController(PushService push, OpticContext context) {
        this.push = push;
        this.context = context;
    }

    public record Subscription(String endpoint, Map<String, String> keys) {}
    public record Endpoint(String endpoint) {}

    @GetMapping("/public-key")
    public Map<String, String> publicKey(Authentication auth) {
        context.accountId(auth);
        return Map.of("publicKey", push.publicKey());
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(required = false) String endpoint, Authentication auth) {
        return push.status(context.accountId(auth), endpoint);
    }

    @PostMapping("/subscriptions")
    public Map<String, Boolean> subscribe(@RequestBody Subscription body, Authentication auth, HttpServletRequest request) {
        if (body == null || body.keys() == null) throw new IllegalArgumentException("Inscrição inválida.");
        push.subscribe(context.accountId(auth), body.endpoint(), body.keys().get("p256dh"), body.keys().get("auth"),
                request.getHeader("User-Agent"));
        return Map.of("subscribed", true);
    }

    @PostMapping("/subscriptions/remove")
    public Map<String, Boolean> unsubscribe(@RequestBody Endpoint body, Authentication auth) {
        push.unsubscribe(context.accountId(auth), body == null ? null : body.endpoint());
        return Map.of("subscribed", false);
    }

    /** Aviso de teste para os aparelhos da própria pessoa. */
    @PostMapping("/test")
    public Map<String, Integer> test(Authentication auth) {
        UUID account = context.accountId(auth);
        int sent = push.sendToAccount(account, new PushService.Message("Optics Management",
                "Tudo certo! Os avisos estão ativos neste aparelho.", "/", "optics-test"));
        return Map.of("sent", sent);
    }
}
