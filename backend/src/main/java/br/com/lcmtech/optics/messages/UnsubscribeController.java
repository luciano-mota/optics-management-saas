package br.com.lcmtech.optics.messages;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Descadastro pelo link do e-mail. O GET só mostra a confirmação (leitores de e-mail abrem links sozinhos);
 * o descadastro acontece no botão (POST). O token aleatório do link é a autorização.
 */
@RestController
@RequestMapping("/api/v1/public/messages/unsubscribe")
public class UnsubscribeController {
    private final AutomaticMessages messages;

    public UnsubscribeController(AutomaticMessages messages) {
        this.messages = messages;
    }

    @GetMapping("/{token}")
    public ResponseEntity<String> confirm(@PathVariable String token) {
        return page("Não receber mais avisos por e-mail",
                "<p>Confirme para não receber mais lembretes e mensagens desta ótica por e-mail.</p>"
                        + "<form method=\"post\"><button type=\"submit\">Não quero mais receber</button></form>");
    }

    @PostMapping("/{token}")
    public ResponseEntity<String> unsubscribe(@PathVariable String token) {
        String shop = token.length() > 100 ? null : messages.unsubscribe(token);
        if (shop == null) return page("Link inválido", "<p>Este link não é mais válido. Fale com a ótica para ajustar seus avisos.</p>");
        return page("Pronto!", "<p>Você não vai mais receber avisos por e-mail da <strong>" + HtmlUtils.htmlEscape(shop, "UTF-8")
                + "</strong>. Se mudar de ideia, é só pedir na ótica.</p>");
    }

    private static ResponseEntity<String> page(String title, String content) {
        String html = "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\"><title>" + title + "</title><style>"
                + "body{font-family:system-ui,sans-serif;background:#f6f6fa;color:#17153a;display:grid;place-items:center;"
                + "min-height:100vh;margin:0;padding:24px;box-sizing:border-box}main{max-width:420px;background:#fff;"
                + "border:1px solid #e6e6ef;border-radius:18px;padding:30px;text-align:center}button{border:0;border-radius:12px;"
                + "background:#5b4cf0;color:#fff;font:700 15px system-ui;padding:12px 20px;cursor:pointer}</style></head><body>"
                + "<main><h1>" + title + "</h1>" + content + "</main></body></html>";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Content-Type", "text/html;charset=UTF-8")
                .header("X-Robots-Tag", "noindex").body(html);
    }
}
